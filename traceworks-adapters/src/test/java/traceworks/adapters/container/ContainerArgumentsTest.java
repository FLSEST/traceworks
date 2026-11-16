package traceworks.adapters.container;

import traceworks.adapters.container.ContainerArguments.Client;
import traceworks.adapters.container.ContainerArguments.Invocation;
import traceworks.adapters.host.BashScript;
import traceworks.adapters.host.ChildProcessEnv;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import traceworks.core.port.Egress;
import traceworks.core.port.ReservedVariables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command line of one container: the secure defaults always, in a fixed order, and nothing a step
 * could use to remove them. Values of the environment never go on the command line.
 */
class ContainerArgumentsTest {

    private static final String IMAGE_ID = "sha256:" + "ab".repeat(32);
    private static final String USER = "1000:1001";
    private static final List<Mount> STANDARD = List.of(new Mount("/w", "/workspace", true),
            new Mount("/p", "/package", true), new Mount("/s", "/scratch", false));
    private static final RunDirectories DIRECTORIES = new RunDirectories("/w", "/p", "/s");

    private static ContainerRun run(boolean network, Map<String, String> environment) {
        return new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", "make"), "/workspace",
                STANDARD, DIRECTORIES, network, environment, null, 60, ContainerLimits.DEFAULTS);
    }

    private static ContainerRun withMounts(List<Mount> mounts) {
        return new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", "true"), "/workspace",
                mounts, RunDirectories.NONE, false, Map.of(), null, 60, ContainerLimits.DEFAULTS);
    }

    private static ContainerRun shell(String script, String stdin) {
        return new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", script), "/workspace",
                List.of(), null, false, Map.of(), stdin, 60, ContainerLimits.DEFAULTS);
    }

    private static List<String> arguments(Client client, ContainerRun run) {
        return ContainerArguments.of(client, run, USER, UnaryOperator.identity()).arguments();
    }

    @Test
    void arguments_haveTheSecureDefaults_inOrder() {
        List<String> docker = List.of("run", "--rm", "--init", "--pull", "never", "--name", "traceworks-r1-1",
                "--label", "traceworks.run=r1", "--user", "1000:1001", "--log-driver", "none",
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=1g", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--memory", "4g", "--pids-limit", "1024", "--cpus", "2",
                "--workdir", "/workspace", "--network", "none",
                "--mount", "type=bind,src=/w,dst=/workspace,readonly",
                "--mount", "type=bind,src=/p,dst=/package,readonly",
                "--mount", "type=bind,src=/s,dst=/scratch",
                "-e", "A_TOKEN", "-e", "B_TOKEN", "-e", "HOME=/tmp", "-e", "TMPDIR=/tmp", "-e", "LANG=C.UTF-8",
                "-e", "TINI_KILL_PROCESS_GROUP=1",
                "-i", "--entrypoint", "/bin/bash", IMAGE_ID, "-c", BashScript.RUNNER);
        ContainerRun run = run(false, Map.of("B_TOKEN", "b-value", "A_TOKEN", "a-value"));

        assertThat(arguments(Client.DOCKER, run)).containsExactlyElementsOf(docker);

        // podman maps the operator's uid to itself, so what a step writes to /scratch belongs to the operator; it
        // gets the image ID as the bare hex it reports
        List<String> podman = new ArrayList<>(docker);
        podman.addAll(podman.indexOf("--read-only"), List.of("--userns", "keep-id"));
        podman.set(podman.indexOf(IMAGE_ID), "ab".repeat(32));
        assertThat(arguments(Client.PODMAN, run)).containsExactlyElementsOf(podman);
    }

    @Test
    void arguments_networkStep_dropsOnlyNetworkNone() {
        List<String> without = new ArrayList<>(arguments(Client.DOCKER, run(false, Map.of())));
        int network = without.indexOf("--network");
        without.subList(network, network + 2).clear();

        assertThat(arguments(Client.DOCKER, run(true, Map.of()))).containsExactlyElementsOf(without);
    }

    private static final String EGRESS_ID = "sha256:" + "e9".repeat(32);

    private static ContainerRun listedHosts(Map<String, String> environment) {
        return new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of("call"), "/workspace",
                STANDARD, DIRECTORIES, Egress.hosts(List.of("b.example:8443", "api.example:443")), environment, null,
                60, ContainerLimits.DEFAULTS, EGRESS_ID);
    }

    /** Listed hosts: the step's only network is the internal one, and the proxy on it is where every client goes. */
    @Test
    void arguments_listedHosts_joinOnlyTheInternalNetwork_andPointEveryClientAtTheProxy() {
        List<String> open = arguments(Client.DOCKER, new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null,
                List.of("call"), "/workspace", STANDARD, DIRECTORIES, true, Map.of("A_TOKEN", "a"), null, 60,
                ContainerLimits.DEFAULTS));
        List<String> expected = new ArrayList<>(open);
        expected.addAll(expected.indexOf("--mount"), List.of("--network", "traceworks-r1-net"));
        expected.addAll(expected.indexOf(IMAGE_ID), List.of("-e", "HTTPS_PROXY=http://traceworks-r1-1-egress:3128",
                "-e", "HTTP_PROXY=http://traceworks-r1-1-egress:3128",
                "-e", "https_proxy=http://traceworks-r1-1-egress:3128",
                "-e", "http_proxy=http://traceworks-r1-1-egress:3128"));

        assertThat(arguments(Client.DOCKER, listedHosts(Map.of("A_TOKEN", "a")))).containsExactlyElementsOf(expected);
        assertThat(ContainerArguments.of(Client.DOCKER, listedHosts(Map.of("A_TOKEN", "a")), USER,
                UnaryOperator.identity()).environment()).as("the proxy's address is no value of the client's")
                .containsOnlyKeys("A_TOKEN");
    }

    /** A step cannot point its clients elsewhere, or past the proxy: the names are the client's own. */
    @Test
    void arguments_listedHosts_aStepCannotSetAProxyVariable() {
        for (String name : List.of("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "NO_PROXY", "no_proxy")) {
            assertThatThrownBy(() -> arguments(Client.DOCKER, listedHosts(Map.of(name, "http://elsewhere:1"))))
                    .as(name).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        }
    }

    /**
     * The proxy is a container like every other, with the same secure defaults, on both networks, with the allowed
     * hosts as its arguments. It is detached, keeps the runtime's log (read with {@code logs}) and is removed by
     * Traceworks, so it has no {@code --rm} and no {@code --log-driver none}; it has no mount and no variable of
     * the step.
     */
    @Test
    void proxy_hasTheSecureDefaults_bothNetworks_andTheHostsAsArguments() {
        ContainerRun run = listedHosts(Map.of("A_TOKEN", "a-value"));

        assertThat(ContainerArguments.proxy(Client.DOCKER, run, USER)).containsExactly("run", "-d", "--init",
                "--pull", "never", "--name", "traceworks-r1-1-egress", "--label", "traceworks.run=r1",
                "--user", "1000:1001", "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=1g",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "4g", "--pids-limit", "1024",
                "--cpus", "2", "--network", "traceworks-r1-out", "--network", "traceworks-r1-net",
                "-e", "HOME=/tmp", "-e", "TMPDIR=/tmp", "-e", "LANG=C.UTF-8", "-e", "TINI_KILL_PROCESS_GROUP=1",
                EGRESS_ID, "api.example:443", "b.example:8443");
        assertThat(ContainerArguments.proxy(Client.PODMAN, run, USER)).containsExactly("run", "-d", "--init",
                "--pull", "never", "--name", "traceworks-r1-1-egress", "--label", "traceworks.run=r1",
                "--user", "1000:1001", "--userns", "keep-id", "--read-only", "--tmpfs",
                "/tmp:rw,exec,nosuid,nodev,size=1g", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--memory", "4g", "--pids-limit", "1024", "--cpus", "2",
                "--network", "traceworks-r1-out,traceworks-r1-net",
                "-e", "HOME=/tmp", "-e", "TMPDIR=/tmp", "-e", "LANG=C.UTF-8", "-e", "TINI_KILL_PROCESS_GROUP=1",
                "e9".repeat(32), "api.example:443", "b.example:8443");
    }

    /** The step's network has no way out; on a Docker that can, the host has no address on it either. */
    @Test
    void networks_areLabelledWithTheRun_andTheStepsOneIsInternal() {
        ContainerRun run = listedHosts(Map.of());

        assertThat(ContainerArguments.internalNetwork(Client.DOCKER, run, true)).containsExactly("network", "create",
                "--internal", "--label", "traceworks.run=r1",
                "-o", "com.docker.network.bridge.gateway_mode_ipv4=isolated", "traceworks-r1-net");
        assertThat(ContainerArguments.internalNetwork(Client.DOCKER, run, false)).containsExactly("network", "create",
                "--internal", "--label", "traceworks.run=r1", "traceworks-r1-net");
        assertThat(ContainerArguments.internalNetwork(Client.PODMAN, run, true)).as("an option of Docker's bridge")
                .containsExactly("network", "create", "--internal", "--label", "traceworks.run=r1",
                        "traceworks-r1-net");
        assertThat(ContainerArguments.outsideNetwork(run)).containsExactly("network", "create",
                "--label", "traceworks.run=r1", "traceworks-r1-out");
    }

    /** A model server of the operator's that speaks http: the proxy gets it marked, so it forwards plain HTTP to it. */
    @Test
    void proxy_marksAPlainTarget_andNoOther() {
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of("call"), "/workspace",
                STANDARD, DIRECTORIES, Egress.server("llm.internal:8080", true), Map.of(), null, 60,
                ContainerLimits.DEFAULTS, EGRESS_ID);
        ContainerRun tls = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of("call"), "/workspace",
                STANDARD, DIRECTORIES, Egress.server("llm.example.org:443", false), Map.of(), null, 60,
                ContainerLimits.DEFAULTS, EGRESS_ID);

        List<String> plain = ContainerArguments.proxy(Client.DOCKER, run, USER);
        List<String> tunnelled = ContainerArguments.proxy(Client.DOCKER, tls, USER);

        assertThat(plain.getLast()).isEqualTo("http://llm.internal:8080");
        assertThat(tunnelled.getLast()).isEqualTo("llm.example.org:443");
        assertThat(plain.subList(0, plain.size() - 1)).isEqualTo(tunnelled.subList(0, tunnelled.size() - 1));
        assertThat(ContainerArguments.proxy(Client.DOCKER, listedHosts(Map.of()), USER))
                .as("the hosts of a provider are never marked").noneMatch(argument -> argument.startsWith("http://"));
    }

    @Test
    void proxy_refusesAnImageThatIsNoLocalImageId() {
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of(), "/workspace",
                STANDARD, DIRECTORIES, Egress.hosts(List.of("api.example:443")), Map.of(), null, 60,
                ContainerLimits.DEFAULTS, "traceworks/egress:latest");

        assertThatThrownBy(() -> ContainerArguments.proxy(Client.DOCKER, run, USER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a local image ID");
    }

    @Test
    void arguments_neverCarryAnEnvironmentValue() {
        Invocation invocation = ContainerArguments.of(Client.DOCKER, run(false, Map.of("TOKEN", "s3cr3t-value")),
                USER, UnaryOperator.identity());

        assertThat(invocation.arguments()).containsSubsequence("-e", "TOKEN");
        assertThat(invocation.arguments()).noneMatch(argument -> argument.contains("s3cr3t-value"));
        assertThat(invocation.environment()).as("the client's process environment carries the value")
                .containsExactlyEntriesOf(Map.of("TOKEN", "s3cr3t-value"));
    }

    /** An invocation that ends up in a log line or an exception names its variables, and no value of them. */
    @Test
    void anInvocationAsText_namesItsVariables_andHoldsNoValue() {
        Invocation invocation = ContainerArguments.of(Client.DOCKER,
                run(false, Map.of("TOKEN", "s3cr3t-value", "API_USER", "s3cr3t-user")), USER,
                UnaryOperator.identity());

        assertThat(invocation.toString()).contains("environment=[API_USER, TOKEN]")
                .doesNotContain("s3cr3t-value", "s3cr3t-user");
    }

    /**
     * A variable of the container goes into the client's own environment first, so a name the client itself reads
     * (its search path, the dynamic loader, its connection to the runtime) would change the client: it is refused.
     */
    @Test
    void arguments_environmentNameTheClientReads_isRefused() {
        for (String name : List.of("PATH", "HOME", "LD_PRELOAD", "DOCKER_HOST", "DOCKER_CONFIG", "CONTAINERS_CONF",
                "CONTAINER_HOST", "XDG_RUNTIME_DIR", "LC_ALL", "STORAGE_DRIVER", "STORAGE_OPTS",
                "_CONTAINERS_USERNS_CONFIGURED", "BUILDAH_ISOLATION", "REGISTRY_AUTH_FILE", "GODEBUG",
                "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTP_PROXY", "https_proxy", "no_proxy", "SSH_AUTH_SOCK",
                "NOTIFY_SOCKET")) {
            assertThatThrownBy(() -> arguments(Client.DOCKER, run(false, Map.of(name, "x"))))
                    .as(name).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        }
        for (String name : List.of("A=B", "", "1ABC", "A B", "-e")) {
            assertThatThrownBy(() -> arguments(Client.DOCKER, run(false, Map.of(name, "x"))))
                    .as(name).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("environment");
        }
    }

    /** Every variable the client gets from the operator's environment is one a container variable must not be. */
    @Test
    void theClientsOwnVariables_areAllReserved() {
        assertThat(ChildProcessEnv.OPERATOR_ENVIRONMENT).allMatch(ReservedVariables::reserved);
        assertThat(ChildProcessEnv.LOCALE_CATEGORIES).allMatch(ReservedVariables::reserved);
        assertThat(CliContainerRuntime.RUNTIME_ENVIRONMENT).allMatch(ReservedVariables::reserved);
        assertThat(List.of("HOME", "TMPDIR", "LANG")).allMatch(ReservedVariables::reserved);
    }

    @Test
    void arguments_mountsAreBindMountsOfRealPaths_withReadonly(@TempDir Path home) throws IOException {
        Path data = Files.createDirectories(home.resolve("data"));
        Path link = Files.createSymbolicLink(home.resolve("link"), data);
        Path docs = Files.createDirectories(home.resolve("docs"));
        ContainerRun run = withMounts(List.of(new Mount(link.toString(), "/data", true),
                new Mount("~/docs", "/docs", false)));

        List<String> arguments = ContainerArguments.of(Client.DOCKER, run, USER,
                host -> MountGuard.realHostPath(host, home)).arguments();

        assertThat(arguments).containsSubsequence(
                "--mount", "type=bind,src=" + data.toRealPath() + ",dst=/data,readonly",
                "--mount", "type=bind,src=" + docs.toRealPath() + ",dst=/docs");
        assertThat(arguments).noneMatch(argument -> argument.startsWith("-v"));
    }

    /** {@code --mount} splits its value at commas and reads quotes; such a path is refused rather than mis-mounted. */
    @Test
    void arguments_pathWithCommaQuoteOrNewline_isRefused() {
        for (Mount mount : List.of(new Mount("/data,dst=/etc", "/data", true), new Mount("/data", "/d\"x", true),
                new Mount("/da\nta", "/data", false), new Mount("/data", "/da\rta", false))) {
            assertThatThrownBy(() -> arguments(Client.DOCKER, withMounts(List.of(mount))))
                    .as(mount.toString()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("comma, quote or line break");
        }
    }

    @Test
    void arguments_relativeMountPath_isRefused() {
        for (Mount mount : List.of(new Mount("volume", "/data", true), new Mount("/data", "data", true),
                new Mount("/data", "./x", true))) {
            assertThatThrownBy(() -> arguments(Client.DOCKER, withMounts(List.of(mount))))
                    .as(mount.toString()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("absolute");
        }
    }

    @Test
    void arguments_withoutWorkingDirectory_keepTheImagesOwn() {
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", "pwd"),
                null, List.of(), null, false, Map.of(), null, 60, ContainerLimits.DEFAULTS);

        assertThat(arguments(Client.DOCKER, run)).doesNotContain("--workdir");
    }

    @Test
    void arguments_toolMode_keepTheImagesEntrypoint_andPassItsArguments() {
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of("fs", "--format", "x"),
                "/workspace", List.of(), null, false, Map.of(), null, 60, ContainerLimits.DEFAULTS);

        List<String> arguments = arguments(Client.DOCKER, run);

        assertThat(arguments).doesNotContain("--entrypoint", "-i");
        assertThat(arguments.subList(arguments.indexOf(IMAGE_ID), arguments.size()))
                .containsExactly(IMAGE_ID, "fs", "--format", "x");
    }

    @Test
    void arguments_withStdin_attachIt() {
        Invocation invocation = ContainerArguments.of(Client.DOCKER, shell("cat", "the prompt"), USER,
                UnaryOperator.identity());

        assertThat(invocation.arguments()).containsSubsequence("-e", "LANG=C.UTF-8", "-i", "--entrypoint",
                "/bin/bash", IMAGE_ID, "-c", "cat");
        assertThat(invocation.stdin()).isEqualTo("the prompt");
        Invocation toolMode = ContainerArguments.of(Client.DOCKER, new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID,
                null, List.of("scan"), "/workspace", List.of(), null, false, Map.of(), null, 60,
                ContainerLimits.DEFAULTS), USER, UnaryOperator.identity());
        assertThat(toolMode.arguments()).as("nothing to read: stdin stays closed").doesNotContain("-i");
        assertThat(toolMode.stdin()).isNull();
    }

    /**
     * A shell command goes to the container's bash on stdin, never on the client's command line, which
     * every process on the machine can read: a later step's command can carry a secret value from an earlier output.
     */
    @Test
    void arguments_shellCommand_goesOnStdin_neverOnTheCommandLine() {
        String script = "echo 'tw-command-text-7f3a'";

        Invocation invocation = ContainerArguments.of(Client.DOCKER, shell(script, null), USER,
                UnaryOperator.identity());

        assertThat(invocation.arguments()).noneMatch(argument -> argument.contains("tw-command-text-7f3a"));
        assertThat(invocation.arguments()).containsSubsequence("-i", "--entrypoint", "/bin/bash", IMAGE_ID, "-c",
                BashScript.RUNNER);
        assertThat(invocation.stdin()).isEqualTo(BashScript.stdin(script)).startsWith(script)
                .endsWith(BashScript.END);
    }

    /**
     * Only {@code bash -c <script>} alone is a shell command. With more arguments, bash gives them to the script as
     * {@code $0}, {@code $1}, ...: they stay where they are, or the script would run without them.
     */
    @Test
    void arguments_bashWithAScriptAndItsParameters_keepsThemAll_andReadsNothingFromStdin() {
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash",
                List.of("-c", "echo \"$1\"", "name", "first"), "/workspace", List.of(), null, false, Map.of(), null,
                60, ContainerLimits.DEFAULTS);

        Invocation invocation = ContainerArguments.of(Client.DOCKER, run, USER, UnaryOperator.identity());

        assertThat(invocation.arguments()).endsWith("--entrypoint", "/bin/bash", IMAGE_ID, "-c", "echo \"$1\"",
                "name", "first").doesNotContain("-i");
        assertThat(invocation.stdin()).isNull();
    }

    /** Two arguments of bash are a script only after -c: bash -x script.sh keeps both, and nothing goes on stdin. */
    @Test
    void arguments_bashWithTwoArgumentsThatAreNoScript_keepsThem() {
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash",
                List.of("-x", "script.sh"), "/workspace", List.of(), null, false, Map.of(), null,
                60, ContainerLimits.DEFAULTS);

        Invocation invocation = ContainerArguments.of(Client.DOCKER, run, USER, UnaryOperator.identity());

        assertThat(invocation.arguments()).endsWith("--entrypoint", "/bin/bash", IMAGE_ID, "-x", "script.sh");
        assertThat(invocation.stdin()).isNull();
    }

    /** Linux caps one argument at 128 KiB: a larger script goes to the container's bash on stdin. */
    @Test
    void arguments_scriptBeyondTheArgumentLimit_goesOnStdin() {
        String script = "value='" + "a".repeat(BashScript.ARGUMENT_LIMIT_BYTES) + "'\necho \"${#value}\"";

        Invocation invocation = ContainerArguments.of(Client.DOCKER, shell(script, null), USER,
                UnaryOperator.identity());

        assertThat(invocation.arguments()).containsSubsequence("-i", "--entrypoint", "/bin/bash", IMAGE_ID, "-c",
                BashScript.RUNNER);
        assertThat(invocation.arguments()).last().isEqualTo(BashScript.RUNNER);
        assertThat(invocation.stdin()).isEqualTo(BashScript.stdin(script));
    }

    /**
     * A request's own stdin wins, and its script stays the argument of {@code bash -c} as long as it fits; a script
     * that would need stdin as well cannot run and says why.
     */
    @Test
    void arguments_scriptBeyondTheLimitWithItsOwnStdin_isRefused() {
        String script = "#" + "a".repeat(BashScript.ARGUMENT_LIMIT_BYTES);

        assertThatThrownBy(() -> ContainerArguments.of(Client.DOCKER, shell(script, "the prompt"), USER,
                UnaryOperator.identity())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128 KiB").hasMessageContaining("stdin");
    }

    /** What would reach the client as an option, or is no local image ID, never gets that far. */
    @Test
    void arguments_valueThatIsNoImageIdOrLooksLikeAnOption_isRefused() {
        for (String image : List.of("alpine", "-v", "", "sha256:" + "AB".repeat(32), "sha256:abc")) {
            ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", image, null, List.of(), "/workspace",
                    List.of(), null, false, Map.of(), null, 60, ContainerLimits.DEFAULTS);
            assertThatThrownBy(() -> arguments(Client.DOCKER, run)).as(image)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image");
        }
        ContainerRun workdir = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of(), "-v",
                List.of(), null, false, Map.of(), null, 60, ContainerLimits.DEFAULTS);
        assertThatThrownBy(() -> arguments(Client.DOCKER, workdir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("working directory");
        ContainerRun entrypoint = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "--privileged", List.of(),
                "/workspace", List.of(), null, false, Map.of(), null, 60, ContainerLimits.DEFAULTS);
        assertThatThrownBy(() -> arguments(Client.DOCKER, entrypoint)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entrypoint");
        ContainerRun name = new ContainerRun("--privileged", "r1", IMAGE_ID, null, List.of(), "/workspace",
                List.of(), null, false, Map.of(), null, 60, ContainerLimits.DEFAULTS);
        assertThatThrownBy(() -> arguments(Client.DOCKER, name)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> ContainerArguments.of(Client.DOCKER, run(false, Map.of()), "-v",
                UnaryOperator.identity())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user");
    }


    /** The package digest leaves the package's .git out, so a container must not see it (docs/security.md). */
    @Test
    void arguments_coverTheGitDirectoryOfThePackage_withAnEmptyReadOnlyTmpfs(@TempDir Path home) throws IOException {
        Path pkg = Files.createDirectories(home.resolve("pkg"));
        Files.createDirectories(pkg.resolve(".git"));
        Path workspace = Files.createDirectories(home.resolve("ws"));
        Files.createDirectories(workspace.resolve(".git"));
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", "true"),
                "/workspace", List.of(new Mount(workspace.toString(), "/workspace", true),
                        new Mount(pkg.toString(), "/package", true)),
                new RunDirectories(workspace.toString(), pkg.toString(), null), false, Map.of(), null, 60,
                ContainerLimits.DEFAULTS);

        List<String> arguments = ContainerArguments.of(Client.DOCKER, run, USER, UnaryOperator.identity()).arguments();

        assertThat(arguments).containsSubsequence("--mount", "type=bind,src=" + pkg + ",dst=/package,readonly",
                "--tmpfs", "/package/.git:ro,size=4k,mode=555");
        assertThat(arguments.stream().filter("--tmpfs"::equals)).hasSize(2);   // /tmp and the package's .git only
    }

    @Test
    void arguments_coverNothing_whenThePackageHasNoGitDirectory(@TempDir Path home) throws IOException {
        Path pkg = Files.createDirectories(home.resolve("pkg"));
        Files.writeString(pkg.resolve(".git"), "gitdir: elsewhere");   // a file is hashed like any other file
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", "true"),
                "/workspace", List.of(new Mount(pkg.toString(), "/package", true)),
                new RunDirectories(null, pkg.toString(), null), false, Map.of(), null, 60, ContainerLimits.DEFAULTS);

        List<String> arguments = ContainerArguments.of(Client.DOCKER, run, USER, UnaryOperator.identity()).arguments();

        assertThat(arguments.stream().filter("--tmpfs"::equals)).hasSize(1);
    }

    /**
     * A {@code .git} that is a link is no directory of the package, even when it points at one: the cover is
     * mounted at the path, and the runtime would follow the link to wherever it points.
     */
    @Test
    void arguments_coverNothing_whenThePackagesGitIsALinkToADirectory(@TempDir Path home) throws IOException {
        Path pkg = Files.createDirectories(home.resolve("pkg"));
        Files.createSymbolicLink(pkg.resolve(".git"), Files.createDirectories(home.resolve("elsewhere")));
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", "true"),
                "/workspace", List.of(new Mount(pkg.toString(), "/package", true)),
                new RunDirectories(null, pkg.toString(), null), false, Map.of(), null, 60, ContainerLimits.DEFAULTS);

        List<String> arguments = ContainerArguments.of(Client.DOCKER, run, USER, UnaryOperator.identity()).arguments();

        assertThat(arguments).doesNotContain("/package/.git:ro,size=4k,mode=555");
        assertThat(arguments.stream().filter("--tmpfs"::equals)).hasSize(1);
    }
}
