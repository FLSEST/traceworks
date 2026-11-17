package traceworks.cli;

import traceworks.adapters.ai.ClaudeCli;
import traceworks.adapters.ai.PiCli;
import traceworks.adapters.config.CredentialsFile;
import traceworks.adapters.config.HomeLayout;
import traceworks.adapters.config.OperatorConfigFile;
import traceworks.adapters.container.RunDirectoryGuard;
import traceworks.adapters.host.HostCommandRunner;
import traceworks.adapters.host.HostHookRunner;
import traceworks.adapters.images.BuiltInImages;
import traceworks.adapters.ledger.GitLedger;
import traceworks.adapters.pkg.FilePackageLoader;
import traceworks.adapters.pkg.GitSourceCheckout;
import traceworks.adapters.record.FileRegisterStore;
import traceworks.adapters.record.FileRunStore;
import traceworks.adapters.record.FileSealStore;
import traceworks.adapters.sign.SshKeygenSigner;
import traceworks.adapters.system.RandomIds;
import traceworks.adapters.system.SystemClock;
import traceworks.core.app.Actors;
import traceworks.core.app.ContainerSupport;
import traceworks.core.app.Credentials;
import traceworks.core.app.Images;
import traceworks.core.app.OperatorSettings;
import traceworks.core.app.RunRefusedException;
import traceworks.core.app.RunServices;
import traceworks.core.app.SealSupport;
import traceworks.core.app.SecretValues;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.port.AiCli;
import traceworks.core.util.Message;
import traceworks.core.port.LocalModels;
import traceworks.core.port.ContainerRuntime;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Builds the use cases from the adapters, for one home and one environment. */
final class Wiring {

    /** The environment variable that names the home. */
    static final String HOME_ENV = "TRACEWORKS_HOME";

    private Wiring() {
    }

    /**
     * The home: the flag, else {@code TRACEWORKS_HOME}, else {@code ~/.traceworks}. A relative path counts from
     * {@code workingDirectory}.
     */
    static Path home(String flag, Map<String, String> env, Path workingDirectory) {
        String chosen = flag != null ? flag : env.get(HOME_ENV);
        if (chosen == null || chosen.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".traceworks");
        }
        return workingDirectory.resolve(chosen).normalize();
    }

    /**
     * The use cases of {@code home}. Every path below the home, and the places of the runs, the ledger and the
     * registers that the operator may set elsewhere, come from the home's {@link HomeLayout}. The container runtime
     * is looked for once, when a use case first needs it (the start of a run, a resume, a cancel), so a command that
     * runs nothing probes nothing. Both command runners, the
     * container runtime's client and the host runner, keep the operator's output limit ({@code output_limit_mib}), so
     * every command of a run has the same one.
     *
     * @param env the environment of the process: the actor, the operator's session paths, which no run may mount, and
     *            the values of secrets that {@code <home>/credentials.json} does not hold. The credentials file is
     *            read when a run that declares a secret starts or resumes, and its modification time after it, for
     *            the warning of a resume
     */
    static RunServices services(Path home, Map<String, String> env, RuntimeSource runtimes) {
        OperatorSettings settings = settings(home);
        HomeLayout layout = layout(home);
        String actor = Actors.resolve(settings, env, System.getProperty("user.name", "unknown"), hostName());
        ContainerSupport containers = new ContainerSupport(once(() -> runtimes.find(settings.containerRuntime(), layout,
                settings.outputLimitBytes())), new BuiltInImages(),
                new RunDirectoryGuard(layout, Path.of(System.getProperty("user.home")), env));
        return new RunServices(new FileRunStore(layout.runs()),
                new FilePackageLoader(layout.workspaces(), layout.runsFolders()),
                new GitLedger(layout.ledger(), layout.ledgerLock()), new SystemClock(), new RandomIds(),
                CoreBlocks.registry(layout.toolCache()),
                new HostCommandRunner(settings.outputLimitBytes()), containers,
                new GitSourceCheckout(layout.workspaces(), layout.runsFolders(),
                        settings.outputLimitBytes()), aiClis(settings.localModels()),
                () -> new SecretValues(CredentialsFile.read(home), env, CredentialsFile.modified(home),
                        CredentialsFile.location(home), name -> settings.secretFromEnvironmentAllowed(name)
                                || AI_CREDENTIAL_NAMES.contains(name)), settings,
                new FileRegisterStore(layout.registers()), new HostHookRunner(env), actor,
                seals(layout, settings, env));
    }

    /**
     * The seals of the home of {@code layout}: the seal log under {@code seals/}, which no setting moves, the allowed
     * signers file (the operator's {@code allowed_signers}, else {@code allowed_signers} in the home) and {@code ssh-keygen} with the operator's
     * {@code signing_key}. Nothing is touched until a use case reads or signs.
     */
    private static SealSupport seals(HomeLayout layout, OperatorSettings settings, Map<String, String> env) {
        Path allowed = settings.seals().allowedSigners() == null ? layout.allowedSigners()
                : settings.seals().allowedSigners();
        return new SealSupport(new FileSealStore(layout.seals(), allowed),
                new SshKeygenSigner(settings.seals().signingKey(), env));
    }

    /**
     * The names of the AI providers' credentials: {@code secret_names} does not govern them, since an operator sets
     * {@code ANTHROPIC_API_KEY} in the shell as a matter of course, and no step can declare one as a secret.
     */
    private static final Set<String> AI_CREDENTIAL_NAMES = AiCli.CREDENTIALS.values().stream()
            .flatMap(List::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** The use case of the credentials stored in {@code home}: its {@code credentials.json}. */
    static Credentials credentials(Path home) {
        return new Credentials(CredentialsFile.store(home));
    }

    /** The AI CLIs a run can use, one per provider: Claude and Pi, in a home without a model server of its own. */
    static List<AiCli> aiClis() {
        return aiClis(LocalModels.NONE);
    }

    /**
     * The AI CLIs a run can use, one per provider: Claude, and Pi with the operator's model servers
     * ({@code local_models}). The same CLIs judge a package for {@code check} and describe it for {@code describe},
     * so a model that names a declared server is no unknown backend there either.
     */
    static List<AiCli> aiClis(LocalModels localModels) {
        return List.of(new ClaudeCli(), new PiCli(localModels));
    }

    /**
     * The images use case on the container runtime of {@code runtimes}: on the machine, the configured one or the
     * first of {@code docker} and {@code podman} that works, with the operator's output limit for a build.
     *
     * @throws RunRefusedException if there is no container runtime
     */
    static Images images(Path home, RuntimeSource runtimes) {
        OperatorSettings settings = settings(home);
        HomeLayout layout = layout(home);
        ContainerRuntime runtime = runtimes.find(settings.containerRuntime(), layout, settings.outputLimitBytes())
                .orElseThrow(() -> new RunRefusedException("No container runtime found: "
                        + (settings.containerRuntime() == null ? "install podman or docker"
                        : "container_runtime " + settings.containerRuntime() + " in traceworks.json")
                        + ", and make sure it answers 'version'"));
        return new Images(runtime, new BuiltInImages(),
                new FilePackageLoader(layout.workspaces(), layout.runsFolders()), settings.packagePath());
    }

    private static OperatorSettings settings(Path home) {
        try {
            return OperatorConfigFile.read(home);
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of(e));
        }
    }

    /** Where {@code home} keeps what Traceworks writes, as its {@code traceworks.json} sets it. */
    private static HomeLayout layout(Path home) {
        try {
            return HomeLayout.read(home);
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of(e));
        }
    }

    /** {@code supplier}'s value, computed on the first call only. */
    private static <T> Supplier<T> once(Supplier<T> supplier) {
        return new Supplier<>() {
            private T value;
            private boolean known;

            @Override
            public synchronized T get() {
                if (!known) {
                    value = supplier.get();
                    known = true;
                }
                return value;
            }
        };
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException e) {
            return "localhost";
        }
    }
}
