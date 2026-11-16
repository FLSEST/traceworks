package traceworks.adapters.container;

import traceworks.adapters.config.HomeLayout;
import traceworks.adapters.container.ContainerArguments.Client;
import traceworks.adapters.container.ContainerArguments.Invocation;
import traceworks.adapters.host.BashScript;
import traceworks.adapters.host.ChildProcessEnv;
import traceworks.adapters.host.ProcessEncoding;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Connection;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.Egress;
import traceworks.core.util.ProcessTracking;
import traceworks.core.util.TruncationUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@link ContainerRuntime} of the {@code docker} or {@code podman} command line client. The core decides what a
 * container may do; this adapter starts exactly that, with the secure defaults no workflow or operator setting removes
 * ({@link ContainerArguments}), after checking the step's own mounts ({@link MountGuard}), and ends and removes it
 * on timeout and cancel ({@link ContainerProcess}).
 *
 * <ul>
 *   <li><b>Which client:</b> {@code podman} when the executable's name says so, {@code docker} otherwise.</li>
 *   <li><b>User:</b> every container runs as the owner of the Traceworks home ({@code <uid>:<gid>}).</li>
 *   <li><b>Client environment:</b> every client call gets the operator's allowlist
 *       ({@link ChildProcessEnv#operatorEnvironment}) and the {@linkplain #RUNTIME_ENVIRONMENT runtime's connection
 *       settings}; a container's client also gets the values of the container's variables, which the command line
 *       only names.</li>
 *   <li><b>Refusals:</b> a declared mount the guard refuses, a run the command line cannot carry, and one whose
 *       arguments or environment hold a character beyond ASCII while the JVM's encoding is not UTF-8
 *       ({@link ProcessEncoding}) end with exit code {@value #REFUSED_EXIT_CODE} and the reasons on stderr, and
 *       nothing is started; the result is marked as not run ({@link CommandResult#notRunReason()}), as is a script
 *       that did not arrive whole in the container ({@link BashScript#notWhole}). {@link #refusal} finds them the same way before, so the run's plan can record that
 *       nothing starts.</li>
 *   <li><b>Images:</b> {@code image inspect} gives the ID ({@code sha256:...}, also for Podman, which prints the
 *       bare hex), {@code build} labels what it builds with {@value #IMAGE_LABEL}, and neither {@code build} nor
 *       {@code pull} prints anything: a failure throws with the end of the client's output.</li>
 *   <li><b>Output limit:</b> every client call, a container's and a build's alike, keeps at most the operator's
 *       output limit of each stream ({@link ContainerProcess}).</li>
 *   <li><b>Listed hosts</b> ({@link Egress#hosts}, an AI call): the container gets no network of its own but a door,
 *       the egress proxy ({@link #throughProxy}). The run has two networks that carry its label,
 *       {@code traceworks-<run id>-net} without a way out and {@code traceworks-<run id>-out}, made at its first
 *       such container and removed when the run ends ({@link #runEnded}). Around the step this adapter starts the
 *       proxy {@code <name>-egress} on both, waits until it listens, and afterwards, whatever happened, reads what
 *       the proxy printed into the result ({@link CommandResult#connections()}) and removes the proxy.
 *       What it cannot remove is a leftover, which {@link #removeLeftovers} and {@link #mayStillRun} find by the
 *       label ({@link RunLeftovers}). Docker needs version 25 or newer for a container on two networks, and from
 *       version 28 leaves the host without an address on the step's network; Podman gets the networks as one list.
 *       Verified with Docker 29 only.</li>
 * </ul>
 *
 * <p>This class holds the client: the probe, the images, the start of a container with its refusals, and the door.
 * What may be left of a run, and the listing, removing and confirming of it, is {@link RunLeftovers}; this class
 * tells it which of its own removals failed and hands it {@link #removeLeftovers} and {@link #mayStillRun}.
 */
public final class CliContainerRuntime implements ContainerRuntime {

    /**
     * The variables of the operator's environment a client needs besides the allowlist to reach the operator's
     * runtime: the clients' connection settings, and the user session a rootless runtime lives in. They reach no
     * container.
     */
    static final Set<String> RUNTIME_ENVIRONMENT = Set.of("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG",
            "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "CONTAINER_HOST", "CONTAINER_CONNECTION", "CONTAINERS_CONF",
            "CONTAINERS_STORAGE_CONF", "CONTAINERS_REGISTRIES_CONF", "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME",
            "XDG_DATA_HOME", "DBUS_SESSION_BUS_ADDRESS");

    /** The label of an image Traceworks builds, with its tag as the value. */
    static final String IMAGE_LABEL = "traceworks.image";

    /** The exit code of a container that is refused before it starts, as {@code docker run} reports a start failure. */
    static final int REFUSED_EXIT_CODE = 125;

    /** How long the version probe may take. */
    static final int PROBE_TIMEOUT_SECONDS = 10;
    /** How long inspecting, listing and removing may take. */
    static final int CALL_TIMEOUT_SECONDS = 60;
    /** How long a build or a pull may take. */
    static final int TRANSFER_TIMEOUT_SECONDS = 30 * 60;

    /** How long the egress proxy may take until it listens. */
    static final Duration PROXY_READY_WAIT = Duration.ofSeconds(15);
    /** How often the proxy's log is read while it starts. */
    private static final long PROXY_POLL_MILLIS = 200;
    /**
     * How much of the proxy's log is read: more than its {@code 10 000} lines can hold, whatever the operator's
     * output limit is, so the connections of a step are never cut.
     */
    static final int PROXY_LOG_LIMIT = 4 * 1024 * 1024;
    /** The first Docker that leaves the host without an address on an internal network when asked to. */
    private static final int ISOLATED_GATEWAY_DOCKER = 28;
    /** How much of a failed client call's output a refusal keeps. */
    private static final int REASON_TAIL = 500;

    /** How much of a failed build's or pull's output its exception keeps: the end, where the error is. */
    private static final int OUTPUT_TAIL = 4000;

    private static final Pattern IMAGE_ID = Pattern.compile("(?:sha256:)?[0-9a-f]{64}");

    private final String executable;
    private final Client client;
    private final Path traceworksHome;
    /** The home's places, which no step's mount may name ({@link MountGuard}). */
    private final HomeLayout layout;
    private final Path home;
    private final Map<String, String> clientEnvironment;
    private final List<Path> sessionPaths;
    private final String processEncoding;
    private final int outputLimit;
    /** What may be left of each run, and how it is removed and confirmed. */
    /**
     * Judges a step's own mount: the guard. A field so that a test can see what is judged and what is mounted.
     */
    java.util.function.BiFunction<Mount, MountGuard.Context, MountGuard.Judged> judge = MountGuard::judge;
    private final RunLeftovers leftovers = new RunLeftovers(arguments -> call(CALL_TIMEOUT_SECONDS, arguments));
    /** The runs whose two networks this runtime made and has not removed yet ({@link #runEnded}). */
    private final Set<String> networkedRuns = new java.util.HashSet<>();
    private volatile Duration proxyReadyWait = PROXY_READY_WAIT;
    private volatile String version;

    /**
     * With the default output limit, {@link CommandRunner#OUTPUT_LIMIT_BYTES}.
     *
     * @param executable     {@code docker} or {@code podman}, found on {@code PATH}, or a path to one of them
     * @param traceworksHome the Traceworks home; its owner is the user every container runs as
     */
    public CliContainerRuntime(String executable, Path traceworksHome) {
        this(executable, traceworksHome, CommandRunner.OUTPUT_LIMIT_BYTES);
    }

    /**
     * @param outputLimit how many bytes of each stream of a client call are kept: the operator's
     *                    {@code output_limit_mib}
     */
    public CliContainerRuntime(String executable, Path traceworksHome, int outputLimit) {
        this(executable, HomeLayout.of(traceworksHome), outputLimit);
    }

    /**
     * @param layout      the Traceworks home, whose owner is the user every container runs as, and where it keeps
     *                    its runs, ledger and registers
     * @param outputLimit how many bytes of each stream of a client call are kept: the operator's
     *                    {@code output_limit_mib}
     */
    public CliContainerRuntime(String executable, HomeLayout layout, int outputLimit) {
        this(executable, layout, Path.of(System.getProperty("user.home")), System.getenv(),
                ProcessEncoding.ofTheJvm(), outputLimit);
    }

    /**
     * @param home                 the operator's home, where a mount's {@code ~} points
     * @param inheritedEnvironment the environment the client's allowlist and the operator's session paths
     *                             ({@link MountGuard#sessionPaths}) are taken from
     */
    CliContainerRuntime(String executable, Path traceworksHome, Path home, Map<String, String> inheritedEnvironment) {
        this(executable, traceworksHome, home, inheritedEnvironment, ProcessEncoding.ofTheJvm());
    }

    /** @param processEncoding the encoding the JVM hands a process its arguments and environment in */
    CliContainerRuntime(String executable, Path traceworksHome, Path home, Map<String, String> inheritedEnvironment,
                        String processEncoding) {
        this(executable, traceworksHome, home, inheritedEnvironment, processEncoding, CommandRunner.OUTPUT_LIMIT_BYTES);
    }

    /** @param outputLimit how many bytes of each stream of a client call are kept, at least 1 */
    CliContainerRuntime(String executable, Path traceworksHome, Path home, Map<String, String> inheritedEnvironment,
                        String processEncoding, int outputLimit) {
        this(executable, HomeLayout.of(Objects.requireNonNull(traceworksHome, "traceworksHome")), home,
                inheritedEnvironment, processEncoding, outputLimit);
    }

    /** @param layout the Traceworks home and its places */
    CliContainerRuntime(String executable, HomeLayout layout, Path home, Map<String, String> inheritedEnvironment,
                        String processEncoding, int outputLimit) {
        if (outputLimit < 1) {
            throw new IllegalArgumentException("An output limit is at least 1 byte: " + outputLimit);
        }
        this.executable = Objects.requireNonNull(executable, "executable");
        Path file = Path.of(executable).getFileName();
        this.client = file != null && file.toString().contains("podman") ? Client.PODMAN : Client.DOCKER;
        this.layout = Objects.requireNonNull(layout, "layout");
        this.traceworksHome = layout.home();
        this.home = Objects.requireNonNull(home, "home");
        this.clientEnvironment = clientEnvironment(inheritedEnvironment);
        this.sessionPaths = MountGuard.sessionPaths(inheritedEnvironment);
        this.processEncoding = processEncoding;
        this.outputLimit = outputLimit;
    }

    /** The environment of every client call: the operator's allowlist and the runtime's connection settings. */
    private static Map<String, String> clientEnvironment(Map<String, String> inherited) {
        Map<String, String> environment = new HashMap<>(ChildProcessEnv.operatorEnvironment(inherited));
        inherited.forEach((name, value) -> {
            if (RUNTIME_ENVIRONMENT.contains(name)) environment.put(name, value);
        });
        return Map.copyOf(environment);
    }

    @Override
    public String name() {
        return client == Client.PODMAN ? "podman" : "docker";
    }

    /** The version the probe reported; probes once more if it has not answered yet, {@code unknown} if it fails. */
    @Override
    public String version() {
        String known = version;
        return known != null ? known : probe().orElse("unknown");
    }

    /**
     * Whether this runtime works, and its version: {@code docker version --format {{.Server.Version}}}, or
     * {@code podman version --format {{.Version}}}, within {@value #PROBE_TIMEOUT_SECONDS} seconds. A client
     * without its daemon or service fails here.
     */
    Optional<String> probe() {
        CommandResult result = call(PROBE_TIMEOUT_SECONDS, "version", "--format",
                client == Client.PODMAN ? "{{.Version}}" : "{{.Server.Version}}");
        String reported = result.stdout().strip();
        if (result.exitCode() != 0 || result.timedOut() || reported.isEmpty()) {
            return Optional.empty();
        }
        String first = reported.lines().findFirst().orElseThrow().strip();
        version = first;
        return Optional.of(first);
    }

    @Override
    public Optional<String> imageId(String reference) {
        return Optional.ofNullable(lookup(reference).imageId());
    }

    /**
     * {@code image inspect --format {{.Id}}}, within {@value #CALL_TIMEOUT_SECONDS} seconds. A lookup that timed out,
     * went beyond the output limit or was ended by a cancel of its step cannot tell whether the image is present, and
     * says so; any other failure means the image is not present.
     */
    @Override
    public ImageLookup lookup(String reference) {
        if (!isReference(reference)) {
            return new ImageLookup(null, null);
        }
        CommandResult result = call(CALL_TIMEOUT_SECONDS, "image", "inspect", "--format", "{{.Id}}", reference);
        String failure = result.timedOut() ? "it timed out after " + CALL_TIMEOUT_SECONDS + " seconds"
                : result.truncated() ? result.truncationNote()
                : ProcessTracking.current().cancelled() ? "the run was cancelled" : null;
        if (failure != null) {
            return new ImageLookup(null, "the lookup of image " + reference + " failed: " + failure);
        }
        String id = result.stdout().strip();
        if (result.exitCode() != 0 || !IMAGE_ID.matcher(id).matches()) {
            return new ImageLookup(null, null);
        }
        return new ImageLookup(id.startsWith("sha256:") ? id : "sha256:" + id, null);
    }

    /** What {@link #run} would refuse, found the same way, without starting anything. */
    @Override
    public Optional<String> refusal(ContainerRun run) {
        return Optional.ofNullable(start(run).refusal());
    }

    @Override
    public CommandResult run(ContainerRun run) {
        Start start = start(run);
        if (start.refusal() != null) {
            return refused(start.refusal());
        }
        return run.network().restricted() ? throughProxy(run, start) : execute(run, start);
    }

    /** For the tests: how long the proxy may take until it listens. */
    CliContainerRuntime withProxyReadyWait(Duration wait) {
        this.proxyReadyWait = wait;
        return this;
    }

    /**
     * Runs a container that connects to listed hosts only: opens its door (two networks and the egress proxy), runs
     * it, and closes the door again, whatever happened. The result names every connection the container asked the
     * proxy for. A door that does not open refuses the step as not run, with the reason; a cancel while it opens
     * starts nothing.
     */
    private CommandResult throughProxy(ContainerRun run, Start start) {
        if (ProcessTracking.current().cancelled()) {
            return CommandResult.notRun(ContainerProcess.CANCELLED_EXIT_CODE, "Cancelled before container start",
                    outputLimit);
        }
        Door door = new Door(run);
        CommandResult result;
        try {
            String failure = open(door, start);
            if (failure == null) {
                result = execute(run, start);
            } else if (ProcessTracking.current().cancelled()) {
                result = CommandResult.notRun(ContainerProcess.CANCELLED_EXIT_CODE,
                        "Cancelled before container start", outputLimit);
            } else {
                result = refused("the egress proxy of the step's container did not start, so the step did not run: "
                        + failure);
            }
        } catch (RuntimeException e) {
            close(door);
            throw e;
        }
        return result.withConnections(close(door));
    }

    /** The door of one container: what was created for it, and so must be removed. */
    private static final class Door {
        final ContainerRun run;
        final String proxy;
        final String internal;
        final String outside;
        boolean proxyStarted;

        Door(ContainerRun run) {
            this.run = run;
            this.proxy = ContainerArguments.proxyName(run);
            this.internal = ContainerArguments.internalNetworkName(run);
            this.outside = ContainerArguments.outsideNetworkName(run);
        }
    }

    /**
     * Creates the two networks, starts the proxy and waits until it listens. The calls belong to the step, so a
     * cancel ends them.
     *
     * @return why the door is not open; {@code null} when it is
     */
    private String open(Door door, Start start) {
        String noNetworks = runNetworks(door);
        if (noNetworks != null) {
            return noNetworks;
        }
        door.proxyStarted = true;
        CommandResult started = call(CALL_TIMEOUT_SECONDS, start.proxy().toArray(String[]::new));
        if (failed(started)) {
            return "its container " + door.proxy + " could not be started" + reason(started);
        }
        long deadline = System.nanoTime() + proxyReadyWait.toNanos();
        while (true) {
            if (ProcessTracking.current().cancelled()) {
                return "the run was cancelled";
            }
            CommandResult log = call(CALL_TIMEOUT_SECONDS, "logs", door.proxy);
            if (!failed(log) && EgressLog.ready(log.stdout())) {
                return null;
            }
            if (System.nanoTime() >= deadline) {
                return "it did not say READY within " + proxyReadyWait.toSeconds() + " seconds" + reason(log);
            }
            try {
                Thread.sleep(PROXY_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "the wait for it was interrupted";
            }
        }
    }

    /**
     * Makes sure the run's two networks are there: created at the run's first container with listed hosts, and used
     * by every later one. A network of that name with the run's label that is already there (left by an earlier
     * process of the run, which a resume's cleanup could not remove) is taken as it is.
     *
     * @return why they are not there; {@code null} when they are
     */
    private String runNetworks(Door door) {
        String runId = door.run.runId();
        synchronized (networkedRuns) {
            if (networkedRuns.contains(runId)) {
                return null;
            }
            List<String> created = new ArrayList<>();
            List<List<String>> creations = List.of(
                    ContainerArguments.internalNetwork(client, door.run, isolatesGateway()),
                    ContainerArguments.outsideNetwork(door.run));
            List<String> names = List.of(door.internal, door.outside);
            for (int i = 0; i < names.size(); i++) {
                CommandResult result = call(CALL_TIMEOUT_SECONDS, creations.get(i).toArray(String[]::new));
                if (failed(result) && !isRunNetwork(runId, names.get(i))) {
                    removeNetworks(runId, created);
                    return "the network " + names.get(i) + " could not be created" + reason(result);
                }
                created.add(names.get(i));
            }
            networkedRuns.add(runId);
            return null;
        }
    }

    /** Whether a network called {@code name} with the label of run {@code runId} exists. */
    private boolean isRunNetwork(String runId, String name) {
        CommandResult listed = call(CALL_TIMEOUT_SECONDS, "network", "ls", "--filter",
                "label=" + RUN_LABEL + "=" + runId, "--format", "{{.Name}}");
        return !failed(listed) && listed.stdout().lines().map(String::strip).anyMatch(name::equals);
    }

    /** Removes networks of a run; what cannot be removed is kept for {@link #mayStillRun}. */
    private void removeNetworks(String runId, List<String> networks) {
        if (networks.isEmpty()) {
            return;
        }
        RunLeftovers.untracked(() -> {
            List<String> remove = new ArrayList<>(List.of("network", "rm"));
            remove.addAll(networks);
            if (failed(call(CALL_TIMEOUT_SECONDS, remove.toArray(String[]::new)))) {
                leftovers.networksUnconfirmed(runId, networks);
            }
            return true;
        });
    }

    /**
     * The run has ended in this process: its two networks go, when this runtime made them. They stay while the run
     * goes on, so that a run of many containers with listed hosts creates and removes two network devices on the
     * host, not two per container.
     */
    @Override
    public void runEnded(String runId) {
        boolean made;
        synchronized (networkedRuns) {
            made = networkedRuns.remove(runId);
        }
        if (made) {
            removeNetworks(runId, ContainerArguments.runNetworkNames(runId));
        }
    }

    /**
     * Reads what the proxy printed, then removes the proxy. The networks stay: they are the run's
     * ({@link #runEnded}). Like {@link #removeLeftovers} it runs on a cancel too, as no step's. What cannot be
     * removed is kept for {@link #mayStillRun}.
     *
     * @return the connections the container asked the proxy for; empty when the proxy never started
     */
    private List<Connection> close(Door door) {
        List<Connection> connections = new ArrayList<>();
        RunLeftovers.untracked(() -> {
            if (door.proxyStarted) {
                List<String> logs = List.of(executable, "logs", door.proxy);
                CommandResult log = ContainerProcess.call(logs, clientEnvironment, CALL_TIMEOUT_SECONDS,
                        Math.max(outputLimit, PROXY_LOG_LIMIT));
                connections.addAll(EgressLog.connections(log.stdout()));
                if (failed(call(CALL_TIMEOUT_SECONDS, "rm", "-f", door.proxy))) {
                    leftovers.containerUnconfirmed(door.run.runId(), door.proxy);
                }
            }
            return true;
        });
        return connections;
    }

    private static boolean failed(CommandResult result) {
        return result.exitCode() != 0 || result.timedOut();
    }

    /** The end of what a failed client call printed, as the end of a sentence; empty when it printed nothing. */
    private static String reason(CommandResult result) {
        String printed = TruncationUtil.secretSafeTail(result.stderr().isBlank() ? result.stdout() : result.stderr(),
                REASON_TAIL).strip();
        return (result.timedOut() ? " (the client did not answer in time)" : "")
                + (printed.isEmpty() ? "" : ": " + printed);
    }

    /**
     * Whether the runtime can leave the host without an address on the step's network: Docker from version
     * {@value #ISOLATED_GATEWAY_DOCKER}. A version that cannot be read counts as one that cannot.
     */
    private boolean isolatesGateway() {
        if (client != Client.DOCKER) {
            return false;
        }
        String reported = version();
        int dot = reported.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? reported : reported.substring(0, dot)) >= ISOLATED_GATEWAY_DOCKER;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private CommandResult execute(ContainerRun run, Start start) {
        CommandResult result = ContainerProcess.execute(start.command(), start.environment(), start.stdin(),
                run.timeoutSeconds(), List.of(executable, "rm", "-f", run.name()),
                () -> leftovers.containerUnconfirmed(run.runId(), run.name()), outputLimit);
        // Only a script that went in on stdin can arrive cut; the container's bash then says so on stderr
        boolean scriptOnStdin = run.stdin() == null && start.stdin() != null;
        return scriptOnStdin && BashScript.notWhole(result.exitCode(), result.stderr())
                ? result.asNotRun(BashScript.NOT_WHOLE_REASON) : result;
    }

    /**
     * The run's own directories (workspace, package, scratch) that no longer resolve to themselves. The run hands
     * each on as the real path its start check judged, before any step ran, so a fresh resolution that gives another
     * path means something was replaced since: between the check and the first container, or by a step that may
     * write the workspace and renamed a folder above the package, leaving a link at its place. The container would
     * get whatever the link names, unchecked. They are resolved as they are mounted
     * ({@link MountGuard#realRunDirectory}).
     */
    private static List<String> movedRunDirectories(ContainerRun run) {
        List<String> moved = new ArrayList<>();
        if (run.directories() == null) {
            return moved;
        }
        String[][] named = {{"the workspace", run.directories().workspace()},
                {"the package", run.directories().packageDirectory()},
                {"the scratch directory", run.directories().scratch()},
                {"the register folder", run.directories().register()}};
        for (String[] directory : named) {
            if (directory[1] == null || directory[1].isBlank()) {
                continue;
            }
            String now = MountGuard.realRunDirectory(directory[1]);
            if (!directory[1].equals(now)) {
                moved.add(directory[0] + " " + directory[1] + " now resolves to " + now + ", not to itself as when"
                        + " the run's start checked it: something replaced it or a folder above it");
            }
        }
        return moved;
    }

    /**
     * The client call that starts {@code run}, or why it is refused: a declared mount the guard refuses, a run the
     * command line cannot carry, or arguments or an environment the JVM's encoding cannot pass.
     */
    private Start start(ContainerRun run) {
        MountGuard.Context context = MountGuard.Context.of(home, layout, run, sessionPaths);
        List<String> refusals = new ArrayList<>();
        // The real path each of the step's own mounts was judged as, by its place among them: the command line gets
        // that path and does not resolve the given one again. By place, not by the host path as written: two mounts
        // of one path are judged one by one, and each is mounted as it was judged
        List<String> judgedAs = new ArrayList<>();
        for (Mount mount : run.declaredMounts()) {
            MountGuard.Judged judged = judge.apply(mount, context);
            judged.refusal().ifPresent(reason -> refusals.add("the mount " + mount.hostPath() + ":"
                    + mount.containerPath() + ":" + (mount.readOnly() ? "ro" : "rw") + " is refused: " + reason));
            judgedAs.add(judged.realPath());
        }
        refusals.addAll(movedRunDirectories(run));
        if (!refusals.isEmpty()) {
            return Start.refused(String.join("\n", refusals));
        }
        Invocation invocation;
        try {
            invocation = ContainerArguments.of(client, run, user(), judgedAs);
        } catch (IllegalArgumentException e) {
            return Start.refused(e.getMessage());
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(invocation.arguments());
        Map<String, String> environment = new HashMap<>(clientEnvironment);
        environment.putAll(invocation.environment());
        Optional<String> unpassable = ProcessEncoding.refusal(command, environment, processEncoding);
        if (unpassable.isPresent()) {
            return Start.refused(unpassable.get());
        }
        List<String> proxy = null;
        if (run.network().restricted()) {
            try {
                proxy = ContainerArguments.proxy(client, run, user());
            } catch (IllegalArgumentException e) {
                return Start.refused(e.getMessage());
            }
        }
        return new Start(command, environment, invocation.stdin(), null, proxy);
    }

    /**
     * The client's command line, environment and stdin for one container; or, with {@code refusal}, none.
     *
     * @param proxy the client's arguments that start the container's egress proxy; {@code null} for a container
     *              without listed hosts
     */
    private record Start(List<String> command, Map<String, String> environment, String stdin, String refusal,
                         List<String> proxy) {
        static Start refused(String why) {
            return new Start(null, null, null, why, null);
        }
    }

    /** {@code <uid>:<gid>} of the Traceworks home's owner. */
    private String user() {
        try {
            return Files.getAttribute(traceworksHome, "unix:uid") + ":"
                    + Files.getAttribute(traceworksHome, "unix:gid");
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("Cannot read the owner of the Traceworks home " + traceworksHome
                    + ", whom every container runs as: " + e.getMessage(), e);
        }
    }

    @Override
    public void build(String tag, String contextDirectory) throws IOException {
        if (!isReference(tag)) {
            throw new IOException("'" + shown(tag) + "' is not an image tag: it is blank, begins with '-' or holds"
                    + " white space or a control character");
        }
        String context = Path.of(contextDirectory).toAbsolutePath().toString();
        // --force-rm: Docker's legacy builder leaves the container of a failed RUN step behind without it. BuildKit
        // (docker buildx build) takes the option and ignores it, and Podman has it on by default
        CommandResult result = call(TRANSFER_TIMEOUT_SECONDS, "build", "--force-rm", "-t", tag, "--label",
                IMAGE_LABEL + "=" + tag, context);
        if (result.exitCode() != 0 || result.timedOut()) {
            throw failed("build -t " + tag, result);
        }
    }

    @Override
    public void pull(String reference) throws IOException {
        if (!isReference(reference)) {
            throw new IOException("'" + shown(reference) + "' is not an image reference: it is blank, begins"
                    + " with '-' or holds white space or a control character");
        }
        CommandResult result = call(TRANSFER_TIMEOUT_SECONDS, "pull", reference);
        if (result.exitCode() != 0 || result.timedOut()) {
            throw failed("pull " + reference, result);
        }
    }

    /** Removes what is left of the run's containers and networks: {@link RunLeftovers#removeLeftovers}. */
    @Override
    public boolean removeLeftovers(String runId) {
        // Whatever the cleanup finds of the run goes, its networks too: a later container makes them again
        synchronized (networkedRuns) {
            networkedRuns.remove(runId);
        }
        return leftovers.removeLeftovers(runId);
    }

    /** Whether a container of the run may still be there: {@link RunLeftovers#mayStillRun}. */
    @Override
    public boolean mayStillRun(String runId) {
        return leftovers.mayStillRun(runId);
    }

    private CommandResult call(int timeoutSeconds, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        return ContainerProcess.call(command, clientEnvironment, timeoutSeconds, outputLimit);
    }

    /**
     * Whether {@code reference}, which a package may have written ({@code container_image}), can name an image at
     * all: not blank, no option of the client ({@code -}), and no white space or control character, which no
     * reference has and which would break a line of the terminal where the reference is named.
     */
    private static boolean isReference(String reference) {
        return reference != null && !reference.isBlank() && !reference.startsWith("-")
                && reference.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                        || Character.isISOControl(c));
    }

    /** A refused reference for a message, on one line: white space and control characters as {@code ?}. */
    private static String shown(String reference) {
        if (reference == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        reference.codePoints().forEach(c -> out.appendCodePoint(c != ' ' && (Character.isWhitespace(c)
                || Character.isSpaceChar(c) || Character.isISOControl(c)) ? '?' : c));
        return out.toString();
    }

    private CommandResult refused(String why) {
        return CommandResult.notRun(REFUSED_EXIT_CODE, why, outputLimit);
    }

    private IOException failed(String what, CommandResult result) {
        String output = TruncationUtil.secretSafeTail(result.stdout() + result.stderr(), OUTPUT_TAIL);
        String how = result.timedOut() ? "timed out after " + TRANSFER_TIMEOUT_SECONDS + " seconds"
                : result.truncated() ? "failed: " + result.truncationNote()
                : "failed with exit code " + result.exitCode();
        return new IOException(name() + " " + what + " " + how + (output.isEmpty() ? "" : ":\n" + output));
    }
}
