package traceworks.adapters.container;

import traceworks.core.context.ExecutionContext;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Connection;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import traceworks.core.port.Egress;
import traceworks.core.util.ProcessTracking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runtime against a fake client: a script named {@code docker} or {@code podman} that logs every call (its
 * arguments, one per line, and for {@code run} its environment) and answers like the real client. The real runtime is
 * {@link CliContainerRuntimeContainerTest}'s.
 */
class CliContainerRuntimeTest {

    private static final String IMAGE_ID = "sha256:" + "c".repeat(64);
    private static final Map<String, String> INHERITED = Map.of("PATH", System.getenv("PATH"), "HOME", "/home/op",
            "DOCKER_HOST", "unix:///run/user/1000/docker.sock", "AWS_SECRET_ACCESS_KEY", "of-the-operator");

    @TempDir
    Path dir;
    private Path calls;
    private Path traceworksHome;
    private Path home;

    @BeforeEach
    void layout() throws IOException {
        calls = Files.createDirectories(dir.resolve("calls"));
        traceworksHome = Files.createDirectories(dir.resolve("home/.traceworks"));
        home = dir.resolve("home");
    }

    /** A fake client: logs the call as {@code <n>.args} (and {@code <n>.env} for run), then runs {@code answer}. */
    private Path client(String name, String answer) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\n"
                + "n=$(ls '" + calls + "' | wc -l)\n"
                + "printf '%s\\n' \"$@\" > '" + calls + "'/$n.args\n"
                + "[ \"$1\" = run ] && env > '" + calls + "'/$n.env\n"
                + answer + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private CliContainerRuntime runtime(Path client) {
        return new CliContainerRuntime(client.toString(), traceworksHome, home, INHERITED);
    }

    /** The calls the client got, in order, each as its argument list. */
    private List<List<String>> calls() throws IOException {
        try (var files = Files.list(calls)) {
            return files.filter(f -> f.toString().endsWith(".args"))
                    .sorted((a, b) -> Integer.compare(number(a), number(b)))
                    .map(f -> {
                        try {
                            return Files.readAllLines(f);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
        }
    }

    private static int number(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(0, name.indexOf('.')));
    }

    private static ContainerRun run(List<Mount> mounts, RunDirectories directories, Map<String, String> env) {
        return new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, "/bin/bash", List.of("-c", "make"), "/workspace",
                mounts, directories, false, env, null, 30, ContainerLimits.DEFAULTS);
    }

    @Test
    void nameAndVersion_comeFromTheExecutableAndItsProbe() throws IOException {
        CliContainerRuntime docker = runtime(client("docker", "echo 29.1.3"));
        CliContainerRuntime podman = runtime(client("podman", "echo 5.4.0"));

        assertThat(docker.name()).isEqualTo("docker");
        assertThat(docker.version()).isEqualTo("29.1.3");
        assertThat(podman.name()).isEqualTo("podman");
        assertThat(podman.version()).isEqualTo("5.4.0");
        assertThat(calls()).containsExactly(List.of("version", "--format", "{{.Server.Version}}"),
                List.of("version", "--format", "{{.Version}}"));
    }

    @Test
    void probe_ofAClientWithoutRuntime_isEmpty() throws IOException {
        Path broken = client("docker", "echo 'Cannot connect to the Docker daemon' >&2; exit 1");

        assertThat(runtime(broken).probe()).isEmpty();
        assertThat(runtime(dir.resolve("missing")).probe()).isEmpty();
    }

    @Test
    void imageId_isWhatInspectPrints_emptyWhenItFails() throws IOException {
        Path docker = client("docker", "[ \"$5\" = alpine:3 ] && echo " + IMAGE_ID
                + " || { echo 'No such image' >&2; exit 1; }");

        assertThat(runtime(docker).imageId("alpine:3")).hasValue(IMAGE_ID);
        assertThat(runtime(docker).imageId("absent:1")).isEmpty();
        assertThat(calls().getFirst()).containsExactly("image", "inspect", "--format", "{{.Id}}", "alpine:3");
    }

    /**
     * A lookup a cancel of its step ended, or that went beyond the output limit, cannot tell whether the image is
     * present: it says so instead of answering "not present". A plain failure means not present.
     */
    @Test
    void lookup_thatACancelOrTheLimitEnded_saysSo_insteadOfNotPresent() throws IOException {
        Path slow = client("docker", "sleep 0.3; echo " + IMAGE_ID);
        Path loud = client("docker-loud", "yes x | head -c 5000; echo " + IMAGE_ID);
        Path missing = client("docker-missing", "echo 'No such image' >&2; exit 1");
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.cancel();

        ContainerRuntime.ImageLookup cancelled = ProcessTracking.bind(context.processTracker(),
                () -> runtime(slow).lookup("alpine:3"));
        ContainerRuntime.ImageLookup cut = new CliContainerRuntime(loud.toString(), traceworksHome, home, INHERITED,
                "UTF-8", 1024).lookup("alpine:3");

        assertThat(cancelled.imageId()).isNull();
        assertThat(cancelled.failure()).isEqualTo("the lookup of image alpine:3 failed: the run was cancelled");
        assertThat(cut.imageId()).isNull();
        assertThat(cut.failure()).startsWith("the lookup of image alpine:3 failed: The command's standard output"
                + " exceeded 1 KiB");
        assertThat(runtime(missing).lookup("absent:1")).isEqualTo(new ContainerRuntime.ImageLookup(null, null));
        assertThat(runtime(slow).lookup("alpine:3")).isEqualTo(new ContainerRuntime.ImageLookup(IMAGE_ID, null));
    }

    /** Podman prints the bare hex; the record keeps every image ID in one form. */
    @Test
    void imageId_ofPodman_isPrefixedLikeDockers() throws IOException {
        Path podman = client("podman", "echo " + "c".repeat(64));

        assertThat(runtime(podman).imageId("alpine:3")).hasValue(IMAGE_ID);
    }

    @Test
    void imageId_ofAReferenceThatLooksLikeAnOption_isEmpty_withoutAskingTheClient() throws IOException {
        Path docker = client("docker", "echo " + IMAGE_ID);

        assertThat(runtime(docker).imageId("--help")).isEmpty();
        assertThat(runtime(docker).imageId(" ")).isEmpty();
        assertThat(calls()).isEmpty();
    }

    /**
     * {@code --force-rm}: with Docker's legacy builder a failed {@code RUN} step would leave its container behind.
     * BuildKit ({@code docker buildx build}) takes the option and ignores it, and Podman has it on by default.
     */
    @Test
    void build_tagsAndLabelsTheImage_andRemovesTheContainerOfAFailedStep() throws IOException {
        Path docker = client("docker", "exit 0");
        Path context = Files.createDirectories(dir.resolve("images/shell"));

        runtime(docker).build("traceworks/shell:0123456789ab", context.toString());

        assertThat(calls()).containsExactly(List.of("build", "--force-rm", "-t", "traceworks/shell:0123456789ab",
                "--label", "traceworks.image=traceworks/shell:0123456789ab", context.toString()));
    }

    @Test
    void buildOrPull_thatFails_throwsWithTheClientsOutput() throws IOException {
        Path docker = client("docker", "echo 'step 3/4: npm install' ; echo 'network unreachable' >&2; exit 1");

        assertThatThrownBy(() -> runtime(docker).build("traceworks/pi:0123456789ab", dir.toString()))
                .isInstanceOf(IOException.class).hasMessageContaining("traceworks/pi:0123456789ab")
                .hasMessageContaining("network unreachable");
        assertThatThrownBy(() -> runtime(docker).pull("alpine:3")).isInstanceOf(IOException.class)
                .hasMessageContaining("alpine:3").hasMessageContaining("network unreachable");
        assertThatThrownBy(() -> runtime(docker).pull("--all-tags")).isInstanceOf(IOException.class);
    }

    /**
     * The client's output is cut to its end where the cut splits no secret value: at a character no value holds, such
     * as a line break; an end without one is left out.
     */
    @Test
    void buildThatFails_withLongOutput_keepsAnEndThatStartsWhereNoValueCanBeSplit() throws IOException {
        Path docker = client("docker", "printf %4100s '' | tr ' ' x; echo; echo the last line; exit 1");

        assertThatThrownBy(() -> runtime(docker).build("traceworks/pi:0123456789ab", dir.toString()))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).endsWith(":\n...\nthe last line").doesNotContain("xxx"));
    }

    /** A build whose output goes beyond 16 MiB is stopped, as every client call, and fails saying so. */
    @Test
    void buildWithOutputBeyondTheLimit_failsWithTheNote() throws IOException {
        Path docker = client("docker", "exec yes 'step 3/4: RUN make'");

        assertThatThrownBy(() -> runtime(docker).build("traceworks/pi:0123456789ab", dir.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("docker build -t traceworks/pi:0123456789ab failed: The command's standard"
                        + " output exceeded 16 MiB; Traceworks stopped it and kept the first 16 MiB:\n")
                .hasMessageContaining("step 3/4: RUN make\n");
    }

    /** The operator's output limit holds for every client call: a build's and a container's. */
    @Test
    void theOperatorsOutputLimit_holdsForABuild_andForAContainer() throws IOException {
        Path docker = client("docker", "exec yes 'step 3/4: RUN make'");
        CliContainerRuntime oneMib = new CliContainerRuntime(docker.toString(), traceworksHome, home, INHERITED,
                "UTF-8", 1024 * 1024);

        assertThatThrownBy(() -> oneMib.build("traceworks/pi:0123456789ab", dir.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("docker build -t traceworks/pi:0123456789ab failed: The command's standard"
                        + " output exceeded 1 MiB; Traceworks stopped it and kept the first 1 MiB:\n");
        CommandResult run = oneMib.run(run(List.of(), null, Map.of()));
        assertThat(run.exitCode()).isEqualTo(153);
        assertThat(run.stdout()).hasSize(1024 * 1024);
        assertThat(run.outputLimitBytes()).isEqualTo(1024 * 1024);
        assertThat(run.truncationNote()).contains("exceeded 1 MiB");
    }

    @Test
    void pull_pullsTheReference() throws IOException {
        Path docker = client("docker", "exit 0");

        runtime(docker).pull("debian:bookworm-slim@sha256:" + "9".repeat(64));

        assertThat(calls()).containsExactly(List.of("pull", "debian:bookworm-slim@sha256:" + "9".repeat(64)));
    }

    private static final List<String> PS = List.of("ps", "-a", "--filter", "label=traceworks.run=r1",
            "--format", "{{.Names}}");

    private static final List<String> NETWORKS = List.of("network", "ls", "--filter",
            "label=traceworks.run=r1", "--format", "{{.Name}}");

    /** The removal is confirmed only when a last listing finds nothing of the run. */
    @Test
    void removeLeftovers_removesWhatPsFindsByTheRunLabel_andConfirmsThatNothingIsLeft() throws IOException {
        Path removed = dir.resolve("removed");
        Path docker = client("docker", "[ \"$1\" = rm ] && touch '" + removed + "'\n"
                + "[ \"$1\" = ps ] && [ ! -f '" + removed + "' ] && printf 'traceworks-r1-1\\ntraceworks-r1-2\\n'\n"
                + "exit 0");
        CliContainerRuntime runtime = runtime(docker);

        assertThat(runtime.removeLeftovers("r1")).isTrue();
        assertThat(calls()).containsExactly(PS, List.of("rm", "-f", "traceworks-r1-1", "traceworks-r1-2"), PS,
                NETWORKS);
        assertThat(runtime.mayStillRun("r1")).isFalse();
    }

    @Test
    void removeLeftovers_thatCannotList_cannotConfirm_andTheRunsContainersMayStillRun() throws IOException {
        Path broken = dir.resolve("broken");
        Files.writeString(broken, "");
        Path docker = client("docker", "[ -f '" + broken + "' ] && exit 1\nexit 0");
        CliContainerRuntime runtime = runtime(docker);

        assertThat(runtime.removeLeftovers("r1")).isFalse();
        assertThat(runtime.mayStillRun("r1")).as("the listing still fails").isTrue();
        Files.delete(broken);
        assertThat(runtime.mayStillRun("r1")).as("the listing works and finds nothing").isFalse();
    }

    @Test
    void removeLeftovers_whoseRemovalFails_cannotConfirm_andNamesWhatMayStillRun() throws IOException {
        Path docker = client("docker", "[ \"$1\" = ps ] && echo traceworks-r1-3\n[ \"$1\" = rm ] && exit 1\nexit 0");
        CliContainerRuntime runtime = runtime(docker);

        assertThat(runtime.removeLeftovers("r1")).isFalse();
        assertThat(runtime.mayStillRun("r1")).isTrue();
        assertThat(calls().getLast()).as("asked once more, and removed once more")
                .containsExactly("rm", "-f", "traceworks-r1-3");
    }

    @Test
    void removeLeftovers_thatStillFindsAContainerAfterTheRemoval_cannotConfirm() throws IOException {
        Path docker = client("docker", "[ \"$1\" = ps ] && echo traceworks-r1-4\nexit 0");

        assertThat(runtime(docker).removeLeftovers("r1")).isFalse();
    }

    /**
     * A cancel of the step whose thread removes the leftovers must not end the removal: on a cancelled
     * step's tracker, every process is killed as it registers.
     */
    @Test
    void removeLeftovers_onACancelledStepsThread_stillRemoves() throws IOException {
        Path removed = dir.resolve("removed");
        Path docker = client("docker", "[ \"$1\" = rm ] && touch '" + removed + "'\n"
                + "[ \"$1\" = ps ] && [ ! -f '" + removed + "' ] && { sleep 0.3; echo a1; }\nexit 0");
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.cancel();

        boolean confirmed = ProcessTracking.bind(context.processTracker(), () -> runtime(docker).removeLeftovers("r1"));

        assertThat(confirmed).isTrue();
        assertThat(calls()).containsExactly(PS, List.of("rm", "-f", "a1"), PS, NETWORKS);
    }

    /**
     * The scratch directory is listed only when no container of the run can still write into it.
     * After a removal that was confirmed (the normal end), nothing is asked.
     */
    @Test
    void mayStillRun_afterAConfirmedRemoval_isFalse_withoutAskingTheClient() throws IOException {
        Path docker = client("docker", "exit 0");
        CliContainerRuntime runtime = runtime(docker);

        runtime.run(run(List.of(), null, Map.of()));

        assertThat(runtime.mayStillRun("r1")).isFalse();
        assertThat(calls()).hasSize(2);
    }

    /**
     * A removal that fails, or does not end within its bound, leaves a container that may still run: the client is
     * asked for the run's containers by its label, what is still there is removed once more, and the answer stays
     * true until the container is gone.
     */
    @Test
    void mayStillRun_afterARemovalThatFailed_isTrue_untilTheContainerIsGone() throws IOException {
        Path gone = dir.resolve("gone");
        Path docker = client("docker", "[ \"$1\" = rm ] && { [ -f '" + gone + "' ]; exit $?; }\n"
                + "[ \"$1\" = ps ] && { [ -f '" + gone + "' ] || echo traceworks-r1-1; echo traceworks-r1-10; }\n"
                + "exit 0");
        CliContainerRuntime runtime = runtime(docker);
        runtime.run(run(List.of(), null, Map.of()));

        assertThat(runtime.mayStillRun("r1")).isTrue();
        assertThat(runtime.mayStillRun("r2")).as("another run").isFalse();
        Files.writeString(gone, "");
        assertThat(runtime.mayStillRun("r1")).isFalse();
        assertThat(runtime.mayStillRun("r1")).isFalse();

        List<List<String>> calls = calls();
        assertThat(calls.get(2)).containsExactly("ps", "-a", "--filter", "label=traceworks.run=r1",
                "--format", "{{.Names}}");
        assertThat(calls.get(3)).isEqualTo(NETWORKS);
        assertThat(calls.get(4)).as("removed once more").containsExactly("rm", "-f", "traceworks-r1-1");
        assertThat(calls).as("nothing is asked once the container is gone").hasSize(7);
    }

    @Test
    void removeLeftovers_withNothingLeft_removesNothing_andConfirms() throws IOException {
        Path docker = client("docker", "exit 0");

        assertThat(runtime(docker).removeLeftovers("r1")).isTrue();
        assertThat(calls()).containsExactly(PS, NETWORKS);
    }

    // ---- listed hosts: the egress proxy as the container's only door ----

    private static final String EGRESS_ID = "sha256:" + "e".repeat(64);
    private static final String LABEL = "traceworks.run=r1";

    private static ContainerRun listedHosts() {
        return new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of("call"), "/workspace", List.of(),
                null, Egress.hosts(List.of("api.example:443")), Map.of("TOKEN", "s3cr3t-value"), null, 30,
                ContainerLimits.DEFAULTS, EGRESS_ID);
    }

    /** The client's answers of a run through the proxy: {@code logs} prints {@code log}, the step prints "answer". */
    private static String proxied(String version, String log) {
        return "case \"$1\" in\n"
                + "  version) echo " + version + ";;\n"
                + "  logs) printf '" + log + "';;\n"
                + "  run) [ \"$2\" = -d ] && echo proxy-id || echo answer;;\n"
                + "esac\nexit 0";
    }

    /**
     * Around the step: the run's two networks with the run's label, the proxy on both, the wait for READY; then the
     * step on the internal network only; then, always, the proxy's log and its removal. The networks are the run's:
     * they stay until the run ends.
     */
    @Test
    void run_withListedHosts_opensTheDoor_runsTheStepBehindIt_andClosesItAgain() throws IOException {
        Path docker = client("docker", proxied("29.1.3",
                "READY\\nALLOW api.example:443\\nDENY evil.example:443\\nALLOW api.example:443\\n"));
        CliContainerRuntime runtime = runtime(docker);

        CommandResult result = runtime.run(listedHosts());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("answer\n");
        assertThat(result.connections()).containsExactly(new Connection("api.example:443", "ALLOW", 2),
                new Connection("evil.example:443", "DENY", 1));
        List<List<String>> calls = calls();
        assertThat(calls).hasSize(9);
        assertThat(calls.get(0)).as("the version decides whether the host keeps an address on the step's network")
                .startsWith("version");
        assertThat(calls.get(1)).containsExactly("network", "create", "--internal", "--label", LABEL,
                "-o", "com.docker.network.bridge.gateway_mode_ipv4=isolated", "traceworks-r1-net");
        assertThat(calls.get(2)).containsExactly("network", "create", "--label", LABEL, "traceworks-r1-out");
        assertThat(calls.get(3)).startsWith("run", "-d", "--init", "--pull", "never", "--name",
                "traceworks-r1-1-egress", "--label", LABEL)
                .containsSubsequence("--network", "traceworks-r1-out", "--network", "traceworks-r1-net")
                .endsWith(EGRESS_ID, "api.example:443")
                .doesNotContain("--rm", "--log-driver", "--mount", "TOKEN");
        assertThat(calls.get(4)).containsExactly("logs", "traceworks-r1-1-egress");
        assertThat(calls.get(5)).startsWith("run", "--rm", "--init").containsSubsequence("--log-driver", "none")
                .containsSubsequence("--network", "traceworks-r1-net")
                .contains("HTTPS_PROXY=http://traceworks-r1-1-egress:3128")
                .doesNotContain("traceworks-r1-out").endsWith(IMAGE_ID, "call");
        assertThat(calls.get(6)).containsExactly("rm", "-f", "traceworks-r1-1");
        assertThat(calls.get(7)).containsExactly("logs", "traceworks-r1-1-egress");
        assertThat(calls.get(8)).containsExactly("rm", "-f", "traceworks-r1-1-egress");
        runtime.runEnded("r1");
        assertThat(calls().getLast()).as("the run's networks go when the run ends")
                .containsExactly("network", "rm", "traceworks-r1-net", "traceworks-r1-out");
        assertThat(Files.readString(this.calls.resolve("3.env"))).as("the proxy gets no value of the step")
                .doesNotContain("s3cr3t-value");
        assertThat(runtime.mayStillRun("r1")).as("everything was removed: nothing is asked").isFalse();
        assertThat(calls()).hasSize(10);
    }

    /** An older Docker does not know the option, and Podman has another bridge: they are not asked for it. */
    @Test
    void run_withListedHosts_onAnOlderDockerOrPodman_createsThePlainInternalNetwork() throws IOException {
        runtime(client("docker", proxied("27.5.1", "READY\\n"))).run(listedHosts());
        List<List<String>> docker = calls();
        for (Path file : Files.list(calls).toList()) {
            Files.delete(file);
        }
        runtime(client("podman", proxied("5.4.0", "READY\\n"))).run(listedHosts());
        List<List<String>> podman = calls();

        assertThat(docker.get(1)).containsExactly("network", "create", "--internal", "--label", LABEL,
                "traceworks-r1-net");
        assertThat(podman.getFirst()).as("podman's version is not asked for").containsExactly("network", "create",
                "--internal", "--label", LABEL, "traceworks-r1-net");
        assertThat(podman.get(2)).containsSubsequence("--network", "traceworks-r1-out,traceworks-r1-net")
                .endsWith("e".repeat(64), "api.example:443");
    }

    /**
     * The two networks are the run's, not the container's: a second container of the same run with listed hosts
     * creates none. Each network is a bridge on the host that the desktop's network services react to; a run of a
     * hundred AI calls made two hundred of them before.
     */
    @Test
    void run_withListedHosts_twiceInOneRun_createsTheRunsNetworksOnce_andRemovesThemWhenTheRunEnds()
            throws IOException {
        Path docker = client("docker", proxied("29.1.3", "READY\\n"));
        CliContainerRuntime runtime = runtime(docker);
        ContainerRun second = new ContainerRun("traceworks-r1-2", "r1", IMAGE_ID, null, List.of("call"), "/workspace",
                List.of(), null, Egress.hosts(List.of("api.example:443")), Map.of(), null, 30,
                ContainerLimits.DEFAULTS, EGRESS_ID);

        assertThat(runtime.run(listedHosts()).exitCode()).isZero();
        assertThat(runtime.run(second).exitCode()).isZero();

        assertThat(calls()).filteredOn(call -> call.subList(0, 2).equals(List.of("network", "create"))).hasSize(2);
        assertThat(calls()).as("no network is removed while the run goes on")
                .noneMatch(call -> call.subList(0, 2).equals(List.of("network", "rm")));
        assertThat(calls()).filteredOn(call -> call.contains("traceworks-r1-2-egress") && call.contains("-d"))
                .singleElement().asList().containsSubsequence("--network", "traceworks-r1-out", "--network",
                        "traceworks-r1-net");

        runtime.runEnded("r1");
        runtime.runEnded("r1");

        assertThat(calls()).filteredOn(call -> call.subList(0, 2).equals(List.of("network", "rm")))
                .containsExactly(List.of("network", "rm", "traceworks-r1-net", "traceworks-r1-out"));
    }

    /** A run that made no network asks the client for nothing when it ends. */
    @Test
    void runEnded_ofARunWithoutListedHosts_callsNothing() throws IOException {
        Path docker = client("docker", "exit 0");

        runtime(docker).runEnded("r1");

        assertThat(calls()).isEmpty();
    }

    /** A network left by an earlier process of the run (it carries the run's label) is taken as it is. */
    @Test
    void run_withListedHosts_whoseNetworksAreThereFromAnEarlierProcess_usesThem() throws IOException {
        Path docker = client("docker", "case \"$1 $2\" in\n"
                + "  version*) echo 29.1.3;;\n"
                + "  'network create') echo 'network with name already exists' >&2; exit 1;;\n"
                + "  'network ls') printf 'traceworks-r1-net\\ntraceworks-r1-out\\n';;\n"
                + "  logs*) echo READY;;\n"
                + "  run*) [ \"$2\" = -d ] && echo proxy-id || echo answer;;\n"
                + "esac\nexit 0");

        CommandResult result = runtime(docker).run(listedHosts());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("answer\n");
    }

    /** Without its door the container would have no network or an open one: the step is refused, and not run. */
    @Test
    void run_withListedHosts_whoseProxyNeverSaysReady_isRefusedAsNotRun_andTheDoorIsRemoved() throws IOException {
        Path docker = client("docker", proxied("29.1.3", "starting\\n"));

        CommandResult result = runtime(docker).withProxyReadyWait(java.time.Duration.ofMillis(300))
                .run(listedHosts());

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.didNotRun()).isTrue();
        assertThat(result.notRunReason()).contains("the egress proxy", "did not start", "did not say READY");
        assertThat(result.connections()).isEmpty();
        List<List<String>> calls = calls();
        assertThat(calls).as("the step's container never started")
                .noneMatch(call -> call.size() > 1 && call.get(0).equals("run") && call.get(1).equals("--rm"));
        assertThat(calls.getLast()).as("the proxy goes; the networks are the run's and stay until it ends")
                .isEqualTo(List.of("rm", "-f", "traceworks-r1-1-egress"));
    }

    @Test
    void run_withListedHosts_whoseNetworkCannotBeCreated_isRefused_andStartsNoContainer() throws IOException {
        Path docker = client("docker", "[ \"$1\" = version ] && echo 29.1.3\n[ \"$1\" = network ] && [ \"$2\" ="
                + " create ] && { echo 'all predefined address pools have been fully subnetted' >&2; exit 1; }\n"
                + "exit 0");

        CommandResult result = runtime(docker).run(listedHosts());

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.notRunReason()).contains("the network traceworks-r1-net could not be created",
                "address pools");
        assertThat(calls()).noneMatch(call -> call.getFirst().equals("run"))
                .noneMatch(call -> call.equals(List.of("network", "rm", "traceworks-r1-net")));
        assertThat(calls().getLast()).as("it asked whether the network is there from an earlier process of the run")
                .startsWith("network", "ls");
    }

    /** A first network made before the second one failed is removed again: the run that is refused keeps none. */
    @Test
    void run_withListedHosts_whoseSecondNetworkCannotBeCreated_removesTheFirst() throws IOException {
        Path docker = client("docker", "case \"$*\" in\n"
                + "  version*) echo 29.1.3;;\n"
                + "  'network create'*traceworks-r1-out*) echo 'no more pools' >&2; exit 1;;\n"
                + "esac\nexit 0");

        CommandResult result = runtime(docker).run(listedHosts());

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.notRunReason()).contains("the network traceworks-r1-out could not be created");
        assertThat(calls()).anyMatch(call -> call.size() >= 3 && call.subList(0, 2).equals(List.of("network", "rm"))
                && call.contains("traceworks-r1-net"));
    }

    /** A proxy that cannot be removed is a leftover of the run: it may still run. */
    @Test
    void run_withListedHosts_whoseProxyCannotBeRemoved_mayStillRun() throws IOException {
        Path docker = client("docker", "case \"$1 $2\" in\n"
                + "  version*) echo 29.1.3;;\n"
                + "  logs*) echo READY;;\n"
                + "  'rm -f') case \"$3\" in *-egress) exit 1;; esac;;\n"
                + "  ps*) echo traceworks-r1-1-egress;;\n"
                + "esac\nexit 0");
        CliContainerRuntime runtime = runtime(docker);

        CommandResult result = runtime.run(listedHosts());

        assertThat(result.exitCode()).as("the step ran").isZero();
        assertThat(runtime.mayStillRun("r1")).isTrue();
    }

    /** A network that cannot be removed when the run ends is a leftover of the run, removed once more when asked. */
    @Test
    void run_withListedHosts_whoseDoorCannotBeRemoved_leavesALeftover_untilItIsGone() throws IOException {
        Path gone = dir.resolve("gone");
        Path docker = client("docker", "case \"$1 $2\" in\n"
                + "  version*) echo 29.1.3;;\n"
                + "  logs*) echo READY;;\n"
                + "  'network rm') [ -f '" + gone + "' ]; exit $?;;\n"
                + "  'network ls') [ -f '" + gone + "' ] || printf 'traceworks-r1-net\\ntraceworks-r1-out\\n';;\n"
                + "esac\nexit 0");
        CliContainerRuntime runtime = runtime(docker);

        CommandResult result = runtime.run(listedHosts());
        runtime.runEnded("r1");

        assertThat(result.exitCode()).as("the step ran").isZero();
        assertThat(runtime.mayStillRun("r1")).isTrue();
        assertThat(calls().getLast()).as("removed once more").containsExactly("network", "rm", "traceworks-r1-net",
                "traceworks-r1-out");
        Files.writeString(gone, "");
        assertThat(runtime.mayStillRun("r1")).isFalse();
        int asked = calls().size();
        assertThat(runtime.mayStillRun("r1")).isFalse();
        assertThat(calls()).as("nothing is asked once the networks are gone").hasSize(asked);
    }

    /**
     * A cleanup that could not list the run's containers knows no name. Once the client answers again, whatever
     * still carries the run's label is a leftover: it is removed, and while that fails it may still run.
     */
    @Test
    void mayStillRun_afterACleanupThatCouldNotList_removesWhatTheLabelFindsLater_andIsTrueWhileItStays()
            throws IOException {
        Path broken = dir.resolve("broken");
        Files.writeString(broken, "");
        Path gone = dir.resolve("gone");
        Path docker = client("docker", "[ -f '" + broken + "' ] && exit 1\n"
                + "case \"$1 $2\" in\n"
                + "  'rm -f') [ -f '" + gone + "' ]; exit $?;;\n"
                + "  ps*) [ -f '" + gone + "' ] || echo traceworks-r1-7;;\n"
                + "  'network ls') [ -f '" + gone + "' ] || echo traceworks-r1-7-net;;\n"
                + "esac\nexit 0");
        CliContainerRuntime runtime = runtime(docker);
        assertThat(runtime.removeLeftovers("r1")).as("the client does not answer").isFalse();
        Files.delete(broken);

        assertThat(runtime.mayStillRun("r1")).as("a container with the run's label is there, and stays").isTrue();
        assertThat(calls()).contains(List.of("rm", "-f", "traceworks-r1-7"));
        Files.writeString(gone, "");
        assertThat(runtime.mayStillRun("r1")).isFalse();
    }

    /** The same for a network the failed cleanup never saw. */
    @Test
    void mayStillRun_afterACleanupThatCouldNotList_removesTheNetworkTheLabelFindsLater() throws IOException {
        Path broken = dir.resolve("broken");
        Files.writeString(broken, "");
        Path docker = client("docker", "[ -f '" + broken + "' ] && exit 1\n"
                + "case \"$1 $2\" in\n"
                + "  'network rm') exit 1;;\n"
                + "  'network ls') echo traceworks-r1-7-net;;\n"
                + "esac\nexit 0");
        CliContainerRuntime runtime = runtime(docker);
        assertThat(runtime.removeLeftovers("r1")).isFalse();
        Files.delete(broken);

        assertThat(runtime.mayStillRun("r1")).as("a network with the run's label is there, and stays").isTrue();
        assertThat(calls().getLast()).containsExactly("network", "rm", "traceworks-r1-7-net");
    }

    /** Docker 28 is the first that takes the option: the host gets no address on the step's network from there on. */
    @Test
    void run_withListedHosts_onDocker28_theFirstThatCan_keepsTheHostOffTheStepsNetwork() throws IOException {
        runtime(client("docker", proxied("28.0.0", "READY\\n"))).run(listedHosts());

        assertThat(calls().get(1)).containsExactly("network", "create", "--internal", "--label", LABEL,
                "-o", "com.docker.network.bridge.gateway_mode_ipv4=isolated", "traceworks-r1-net");
    }

    /**
     * A version that is no number counts as one that cannot (a Docker built from source reports {@code dev}): the
     * option an older daemon refuses is not sent on a guess.
     */
    @Test
    void run_withListedHosts_onADockerWhoseVersionIsNoNumber_createsThePlainInternalNetwork() throws IOException {
        runtime(client("docker", proxied("dev", "READY\\n"))).run(listedHosts());

        assertThat(calls().get(1)).containsExactly("network", "create", "--internal", "--label", LABEL,
                "traceworks-r1-net");
    }

    /**
     * The operator's output limit is for what a step prints. The proxy's log is the record of where the container
     * went: a small limit must not cut the connections off it.
     */
    @Test
    void run_withListedHosts_andASmallOutputLimit_stillRecordsEveryConnectionOfTheProxysLog() throws IOException {
        Path stepRan = dir.resolve("step-ran");
        String connections = "ALLOW api.example:443\\n".repeat(40) + "DENY evil.example:443\\n";
        Path docker = client("docker", "case \"$1\" in\n"
                + "  version) echo 29.1.3;;\n"
                + "  logs) echo READY; [ -f '" + stepRan + "' ] && printf '" + connections + "';;\n"
                + "  run) [ \"$2\" = -d ] && echo proxy-id || { touch '" + stepRan + "'; echo answer; };;\n"
                + "esac\nexit 0");
        CliContainerRuntime small = new CliContainerRuntime(docker.toString(), traceworksHome, home, INHERITED,
                "UTF-8", 64);

        CommandResult result = small.run(listedHosts());

        assertThat(result.exitCode()).isZero();
        assertThat(result.connections()).containsExactly(new Connection("api.example:443", "ALLOW", 40),
                new Connection("evil.example:443", "DENY", 1));
    }

    /** The networks of a run that another process left behind go with its containers, after them. */
    @Test
    void removeLeftovers_removesTheRunsNetworks_afterItsContainers() throws IOException {
        Path removed = dir.resolve("removed");
        Path docker = client("docker", "case \"$1 $2\" in\n"
                + "  'rm -f') touch '" + removed + "-c';;\n"
                + "  'network rm') touch '" + removed + "-n';;\n"
                + "  ps*) [ -f '" + removed + "-c' ] || echo traceworks-r1-1-egress;;\n"
                + "  'network ls') [ -f '" + removed + "-n' ] || printf 'traceworks-r1-net\\ntraceworks-r1-out\\n';;\n"
                + "esac\nexit 0");
        CliContainerRuntime runtime = runtime(docker);

        assertThat(runtime.removeLeftovers("r1")).isTrue();
        assertThat(calls()).containsExactly(PS, List.of("rm", "-f", "traceworks-r1-1-egress"), PS, NETWORKS,
                List.of("network", "rm", "traceworks-r1-net", "traceworks-r1-out"), NETWORKS);
        assertThat(runtime.mayStillRun("r1")).isFalse();
    }

    @Test
    void removeLeftovers_whoseNetworkStays_cannotConfirm() throws IOException {
        Path docker = client("docker", "[ \"$1 $2\" = 'network ls' ] && echo traceworks-r1-net\n"
                + "[ \"$1 $2\" = 'network rm' ] && exit 1\nexit 0");
        CliContainerRuntime runtime = runtime(docker);

        assertThat(runtime.removeLeftovers("r1")).isFalse();
        assertThat(runtime.mayStillRun("r1")).isTrue();
    }

    /** A cancel before the door opens starts nothing at all. */
    @Test
    void run_withListedHosts_onACancelledStep_startsNothing() throws IOException {
        Path docker = client("docker", proxied("29.1.3", "READY\\n"));
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.cancel();

        CommandResult result = ProcessTracking.bind(context.processTracker(),
                () -> runtime(docker).run(listedHosts()));

        assertThat(result.exitCode()).isEqualTo(130);
        assertThat(result.didNotRun()).isTrue();
        assertThat(calls()).isEmpty();
    }

    /** What the proxy's command line cannot carry is found before anything starts, as for the step's own. */
    @Test
    void refusal_namesAnEgressImageThatIsNoImageId() throws IOException {
        Path docker = client("docker", "exit 0");
        ContainerRun run = new ContainerRun("traceworks-r1-1", "r1", IMAGE_ID, null, List.of("call"), "/workspace",
                List.of(), null, Egress.hosts(List.of("api.example:443")), Map.of(), null, 30,
                ContainerLimits.DEFAULTS, "traceworks/egress:latest");

        assertThat(runtime(docker).refusal(run)).hasValueSatisfying(reason -> assertThat(reason)
                .contains("traceworks/egress:latest", "not a local image ID"));
        assertThat(runtime(docker).run(run).exitCode()).isEqualTo(125);
        assertThat(calls()).isEmpty();
    }

    /**
     * The client gets the allowlist of the operator's environment, the runtime's own connection settings and the
     * values of the container's variables; nothing else of the JVM's environment, and no value on its command line.
     */
    @Test
    void run_givesTheClientTheArguments_andOnlyTheAllowlistPlusTheContainersValues() throws IOException {
        Path docker = client("docker", "echo built; exit 0");

        CommandResult result = runtime(docker).run(run(List.of(), null, Map.of("TOKEN", "s3cr3t-value")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("built\n");
        List<String> arguments = calls().getFirst();
        assertThat(arguments).startsWith("run", "--rm", "--init").containsSubsequence("--log-driver", "none")
                .containsSubsequence("-e", "TOKEN")
                .noneMatch(argument -> argument.contains("s3cr3t-value"));
        String env = Files.readString(calls.resolve("0.env"));
        assertThat(env).contains("TOKEN=s3cr3t-value", "DOCKER_HOST=unix:///run/user/1000/docker.sock",
                "HOME=/home/op").doesNotContain("of-the-operator");
        assertThat(calls().get(1)).as("the container is removed afterwards")
                .containsExactly("rm", "-f", "traceworks-r1-1");
    }

    /**
     * Under a JVM whose encoding is not UTF-8, a value beyond ASCII would reach the client changed; the
     * run is refused like a forbidden mount, before the client starts, naming the variable but not its value.
     */
    @Test
    void run_withoutAUtf8Encoding_refusesTextBeyondAscii_beforeTheClientStarts() throws IOException {
        Path docker = client("docker", "exit 0");
        CliContainerRuntime ascii = new CliContainerRuntime(docker.toString(), traceworksHome, home, INHERITED,
                "ANSI_X3.4-1968");

        CommandResult refused = ascii.run(run(List.of(), null, Map.of("TOKEN", "caf\u00e9-value-1")));

        assertThat(refused.exitCode()).isEqualTo(125);
        assertThat(refused.stderr()).contains("the environment variable TOKEN", "ANSI_X3.4-1968", "LANG=C.UTF-8")
                .doesNotContain("caf\u00e9");
        assertThat(calls()).as("nothing started").isEmpty();
        assertThat(ascii.run(run(List.of(), null, Map.of("TOKEN", "plain-value-1"))).exitCode())
                .as("ASCII passes").isZero();
        assertThat(new CliContainerRuntime(docker.toString(), traceworksHome, home, INHERITED, "UTF-8")
                .run(run(List.of(), null, Map.of("TOKEN", "caf\u00e9-value-1"))).exitCode())
                .as("UTF-8 passes").isZero();
    }

    @Test
    void run_asTheOwnerOfTheTraceworksHome() throws IOException {
        Path docker = client("docker", "exit 0");
        String owner = Files.getAttribute(traceworksHome, "unix:uid") + ":"
                + Files.getAttribute(traceworksHome, "unix:gid");

        runtime(docker).run(run(List.of(), null, Map.of()));

        assertThat(calls().getFirst()).containsSubsequence("--user", owner);
    }

    @Test
    void run_refusesAForbiddenDeclaredMount_withoutStartingAnything() throws IOException {
        Path docker = client("docker", "exit 0");

        CommandResult result = runtime(docker).run(run(List.of(new Mount("/etc", "/host-etc", true),
                new Mount("~/.ssh", "/ssh", true)), null, Map.of()));

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.stderr()).contains("/etc:/host-etc:ro", "forbidden path /etc", "~/.ssh:/ssh:ro", ".ssh");
        assertThat(result.notRunReason()).as("the runtime knows nothing ran").isEqualTo(result.stderr());
        assertThat(calls()).as("nothing started").isEmpty();
    }

    /**
     * The container's bash says when the script did not arrive whole on stdin: nothing of it ran. A container that
     * exits 74 or 125 by itself, or a command whose script was its argument, ran.
     */
    @Test
    void run_aScriptThatDidNotArriveWhole_isMarkedAsNotRun() throws IOException {
        String notWhole = "[ \"$1\" = run ] && { echo 'traceworks: the command did not arrive whole on stdin' >&2;"
                + " exit 74; }; exit 0";
        CommandResult cut = runtime(client("docker", notWhole)).run(run(List.of(), null, Map.of()));
        CommandResult ownStdin = runtime(client("docker", notWhole)).run(new ContainerRun("traceworks-r1-1", "r1",
                IMAGE_ID, "/bin/bash", List.of("-c", "make"), "/workspace", List.of(), null, false, Map.of(),
                "a prompt", 30, ContainerLimits.DEFAULTS));
        CommandResult itself74 = runtime(client("docker", "[ \"$1\" = run ] && exit 74; exit 0"))
                .run(run(List.of(), null, Map.of()));
        CommandResult itself125 = runtime(client("docker", "[ \"$1\" = run ] && { echo 'docker: Error' >&2;"
                + " exit 125; }; exit 0")).run(run(List.of(), null, Map.of()));

        assertThat(cut.exitCode()).isEqualTo(74);
        assertThat(cut.notRunReason()).isEqualTo("the command did not arrive whole on stdin");
        assertThat(ownStdin.didNotRun()).as("its script was the argument, its stdin the step's own").isFalse();
        assertThat(itself74.didNotRun()).isFalse();
        assertThat(itself125.exitCode()).isEqualTo(125);
        assertThat(itself125.didNotRun()).as("a container's command may exit 125 by itself").isFalse();
    }

    /**
     * What {@code run} would refuse is known before: {@code refusal} names a refused mount, a run the command line
     * cannot carry and text the encoding cannot pass, and starts nothing; a run it does not refuse starts.
     */
    @Test
    void refusal_namesWhatRunWouldRefuse_withoutStartingAnything() throws IOException {
        Path docker = client("docker", "exit 0");
        Path comma = Files.createDirectories(dir.resolve("data,dst=etc"));
        CliContainerRuntime ascii = new CliContainerRuntime(docker.toString(), traceworksHome, home, INHERITED,
                "ANSI_X3.4-1968");

        assertThat(runtime(docker).refusal(run(List.of(new Mount("~/.ssh", "/ssh", true)), null, Map.of())))
                .hasValueSatisfying(why -> assertThat(why).contains("~/.ssh:/ssh:ro is refused", ".ssh"));
        assertThat(runtime(docker).refusal(run(List.of(new Mount(comma.toString(), "/data", true)), null, Map.of())))
                .hasValueSatisfying(why -> assertThat(why).contains("comma, quote or line break"));
        assertThat(ascii.refusal(run(List.of(), null, Map.of("TOKEN", "café-value-1"))))
                .hasValueSatisfying(why -> assertThat(why).contains("the environment variable TOKEN"));
        assertThat(runtime(docker).refusal(run(List.of(), null, Map.of()))).isEmpty();
        assertThat(calls()).as("nothing started").isEmpty();
    }

    /**
     * The run's own directories are not checked (the run checks the workspace once at start): the scratch directory
     * lies in the Traceworks home. A declared writable mount of the workspace is checked: refused while the
     * workspace's own mount is read-only, allowed with {@code workspace_writable}, where the step writes the
     * workspace anyway. A writable mount of any other folder needs the operator's word in {@code writable_mounts},
     * which the runtime takes from the home's layout.
     */
    @Test
    void run_checksOnlyTheDeclaredMounts_andWritableTheWorkspaceOnlyWhileItIsReadOnly() throws IOException {
        Path docker = client("docker", "exit 0");
        Path workspace = Files.createDirectories(dir.resolve("project"));
        Path out = Files.createDirectories(dir.resolve("out"));
        Path scratch = Files.createDirectories(traceworksHome.resolve("runs/r1/scratch"));
        Mount again = new Mount(workspace.toString(), "/again", false);
        Mount elsewhere = new Mount(out.toString(), "/out", false);
        RunDirectories directories = new RunDirectories(workspace.toString(), null, scratch.toString());
        Mount scratchMount = new Mount(scratch.toString(), "/scratch", false);
        Mount readOnly = new Mount(workspace.toString(), "/workspace", true);
        Mount writable = new Mount(workspace.toString(), "/workspace", false);

        CommandResult standardOnly = runtime(docker).run(run(List.of(readOnly, scratchMount), directories, Map.of()));
        CommandResult refused = runtime(docker).run(run(List.of(readOnly, scratchMount, again), directories,
                Map.of()));
        CommandResult inTheWorkspace = runtime(docker).run(run(List.of(writable, scratchMount, again), directories,
                Map.of()));
        CommandResult unlisted = runtime(docker).run(run(List.of(writable, scratchMount, elsewhere), directories,
                Map.of()));
        CliContainerRuntime listing = new CliContainerRuntime(docker.toString(),
                traceworks.adapters.config.HomeLayout.of(traceworksHome).withWritableMounts(List.of(out)),
                home, INHERITED, traceworks.adapters.host.ProcessEncoding.ofTheJvm(), 1024 * 1024);
        CommandResult allowed = listing.run(run(List.of(writable, scratchMount, elsewhere), directories, Map.of()));

        assertThat(standardOnly.exitCode()).isZero();
        assertThat(refused.exitCode()).isEqualTo(125);
        assertThat(refused.stderr()).contains("workspace_writable");
        assertThat(inTheWorkspace.exitCode()).as(inTheWorkspace.stderr()).isZero();
        assertThat(unlisted.exitCode()).isEqualTo(125);
        assertThat(unlisted.stderr()).contains("the mount " + out + ":/out:rw is refused", "writable_mounts",
                traceworksHome.resolve("traceworks.json").toString());
        assertThat(allowed.exitCode()).as(allowed.stderr()).isZero();
    }

    /**
     * The container of a step that may write the workspace gets the workspace's {@code .git} read-only, whatever
     * is in it; the operator's {@code workspace_git_writable}, which the runtime takes from the home's layout,
     * leaves it writable with the rest of the workspace.
     */
    @Test
    void run_mountsTheGitOfAWritableWorkspaceReadOnly_unlessTheOperatorOpenedIt() throws IOException {
        Path docker = client("docker", "exit 0");
        Path workspace = Files.createDirectories(dir.resolve("repository"));
        Path git = Files.createDirectories(workspace.resolve(".git"));
        RunDirectories directories = new RunDirectories(workspace.toString(), null, null);
        ContainerRun writable = run(List.of(new Mount(workspace.toString(), "/workspace", false)), directories,
                Map.of());
        String cover = "type=bind,src=" + git + ",dst=/workspace/.git,readonly";

        assertThat(runtime(docker).refusal(writable)).as("no hooks folder and no config: nothing is asked").isEmpty();
        CommandResult covered = runtime(docker).run(writable);
        CliContainerRuntime opening = new CliContainerRuntime(docker.toString(),
                traceworks.adapters.config.HomeLayout.of(traceworksHome).withWorkspaceGitWritable(true),
                home, INHERITED, traceworks.adapters.host.ProcessEncoding.ofTheJvm(), 1024 * 1024);
        CommandResult opened = opening.run(writable);

        assertThat(covered.exitCode()).as(covered.stderr()).isZero();
        assertThat(opened.exitCode()).as(opened.stderr()).isZero();
        List<List<String>> started = calls().stream().filter(call -> call.getFirst().equals("run")).toList();
        assertThat(started).hasSize(2);
        assertThat(started.get(0)).containsSubsequence("--mount", "type=bind,src=" + workspace + ",dst=/workspace",
                "--mount", cover);
        assertThat(started.get(1)).contains("type=bind,src=" + workspace + ",dst=/workspace").doesNotContain(cover);
        assertThat(git).as("nothing is written into the repository").isEmptyDirectory();
    }

    /** The run's own checkout of its source is a run's data: its {@code .git} is writable with the checkout. */
    @Test
    void run_leavesTheGitOfTheRunsOwnCheckoutWritable() throws IOException {
        Path docker = client("docker", "exit 0");
        Path checkout = Files.createDirectories(traceworksHome.resolve("workspaces/r1"));
        Files.createDirectories(checkout.resolve(".git"));
        RunDirectories directories = new RunDirectories(checkout.toString(), null, null);

        CommandResult result = runtime(docker).run(run(List.of(new Mount(checkout.toString(), "/workspace", false)),
                directories, Map.of()));

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(calls().getFirst()).contains("type=bind,src=" + checkout + ",dst=/workspace")
                .noneMatch(argument -> argument.contains("/.git"));
    }

    /** A {@code .git} that is a symbolic link cannot be held read-only in place: refused, and nothing started. */
    @Test
    void run_refusesAWritableWorkspace_whoseGitIsASymbolicLink_andStartsNothing() throws IOException {
        Path docker = client("docker", "exit 0");
        Path workspace = Files.createDirectories(dir.resolve("repository"));
        Files.createSymbolicLink(workspace.resolve(".git"), Files.createDirectories(dir.resolve("elsewhere.git")));
        RunDirectories directories = new RunDirectories(workspace.toString(), null, null);
        ContainerRun writable = run(List.of(new Mount(workspace.toString(), "/workspace", false)), directories,
                Map.of());

        assertThat(runtime(docker).refusal(writable)).hasValueSatisfying(why -> assertThat(why)
                .contains("symbolic link", workspace.resolve(".git").toString(), "workspace_git_writable"));
        CommandResult refused = runtime(docker).run(writable);

        assertThat(refused.exitCode()).isEqualTo(125);
        assertThat(calls()).as("nothing started").isEmpty();
    }

    /**
     * The start check judges the run's own directories once. A step with a writable workspace can rename a folder
     * above the package afterwards and leave a link in its place; the next container would get whatever the link
     * names. So each directory must resolve, at every container, to the real path the run was started with.
     */
    @Test
    void run_refusesARunDirectoryThatNoLongerResolvesToThePathTheRunWasStartedWith() throws IOException {
        Path docker = client("docker", "exit 0");
        Path workspace = Files.createDirectories(dir.resolve("project"));
        Path pkg = Files.createDirectories(workspace.resolve("a/pkg"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere/pkg"));
        RunDirectories directories = new RunDirectories(workspace.toString(), pkg.toString(), null);
        List<Mount> mounts = List.of(new Mount(workspace.toString(), "/workspace", false),
                new Mount(pkg.toString(), "/package", true));
        CliContainerRuntime runtime = runtime(docker);

        CommandResult first = runtime.run(run(mounts, directories, Map.of()));
        Files.move(workspace.resolve("a"), workspace.resolve("moved"));
        Files.createSymbolicLink(workspace.resolve("a"), elsewhere.getParent());
        CommandResult second = runtime.run(run(mounts, directories, Map.of()));

        assertThat(first.exitCode()).as(first.stderr()).isZero();
        assertThat(second.exitCode()).isEqualTo(125);
        assertThat(second.stderr()).contains("the package", pkg.toString(), "now resolves to", elsewhere.toString(),
                "something replaced it");
    }

    /**
     * The run hands its directories on as the real paths its start check judged. A directory that is a link at the
     * run's first container already was replaced between the check and that container, so it is refused there too.
     */
    @Test
    void run_refusesARunDirectoryThatIsALinkAlreadyAtTheRunsFirstContainer() throws IOException {
        Path docker = client("docker", "exit 0");
        Path workspace = Files.createDirectories(dir.resolve("project"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere/pkg"));
        Path pkg = Files.createSymbolicLink(workspace.resolve("pkg"), elsewhere);
        RunDirectories directories = new RunDirectories(workspace.toString(), pkg.toString(), null);
        List<Mount> mounts = List.of(new Mount(workspace.toString(), "/workspace", false),
                new Mount(pkg.toString(), "/package", true));

        CommandResult first = runtime(docker).run(run(mounts, directories, Map.of()));

        assertThat(first.exitCode()).isEqualTo(125);
        assertThat(first.stderr()).contains("the package", pkg.toString(), "now resolves to", elsewhere.toString(),
                "something replaced it");
        assertThat(calls()).as("no container was started").noneMatch(call -> call.contains("run"));
    }

    /**
     * A run directory that cannot be resolved any more is not taken at its word. It resolved when the run's start
     * checked it, so a link planted at its place whose target the operator cannot reach (a folder without the
     * permission to pass, a target that is not there yet, a loop) is a replacement too: a runtime that runs as root
     * would follow the link and mount what nothing judged.
     */
    @Test
    void run_refusesARunDirectoryThatCanNoLongerBeResolved() throws IOException {
        Path docker = client("docker", "exit 0");
        Path workspace = Files.createDirectories(dir.resolve("project"));
        Path locked = Files.createDirectories(dir.resolve("locked"));
        Files.createDirectories(locked.resolve("x"));
        java.util.Map<String, Path> targets = new java.util.LinkedHashMap<>();
        targets.put("it or a folder on the way to it is gone", dir.resolve("not-there-yet"));
        targets.put("the system does not resolve it", workspace.resolve("case-1/pkg"));   // a link to itself
        targets.put("permission denied", locked.resolve("x"));
        Files.setPosixFilePermissions(locked, java.util.Set.of());
        try {
            int number = 0;
            for (var target : targets.entrySet()) {
                boolean reachable = Files.isDirectory(target.getValue());   // only where the tests run as root
                Path above = Files.createDirectories(workspace.resolve("case-" + number++));
                Path pkg = Files.createSymbolicLink(above.resolve("pkg"), target.getValue());
                RunDirectories directories = new RunDirectories(workspace.toString(), pkg.toString(), null);
                List<Mount> mounts = List.of(new Mount(workspace.toString(), "/workspace", false),
                        new Mount(pkg.toString(), "/package", true));

                CommandResult result = runtime(docker).run(run(mounts, directories, Map.of()));

                assertThat(result.exitCode()).as(target.getKey()).isEqualTo(125);
                if (!reachable) {
                    assertThat(result.stderr()).contains("the package", pkg.toString(), "can no longer be resolved",
                            target.getKey(), "Something replaced or removed it", "put the directory back");
                }
                assertThat(calls()).as("no container was started").noneMatch(call -> call.contains("run"));
            }
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * A run directory is mounted under the name it was checked under. Cutting white space off its end, as a step's
     * own mount is read, would mount another entry: here the link {@code wf} beside the directory {@code "wf "}.
     */
    @Test
    void run_mountsARunDirectoryUnderItsOwnName_alsoWhenItEndsInASpace() throws IOException {
        Path docker = client("docker", "exit 0");
        Path workspace = Files.createDirectories(dir.resolve("project"));
        Path pkg = Files.createDirectories(dir.resolve("wf "));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.createSymbolicLink(dir.resolve("wf"), elsewhere);
        RunDirectories directories = new RunDirectories(workspace.toString(), pkg.toString(), null);
        List<Mount> mounts = List.of(new Mount(workspace.toString(), "/workspace", true),
                new Mount(pkg.toString(), "/package", true));

        CommandResult result = runtime(docker).run(run(mounts, directories, Map.of()));

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        List<String> started = calls().stream().filter(call -> call.contains("run")).findFirst().orElseThrow();
        assertThat(started).contains("type=bind,src=" + pkg + ",dst=/package,readonly")
                .noneMatch(argument -> argument.contains(elsewhere.toString()));
    }

    /**
     * An image reference comes from the package ({@code container_image}). One with white space or a control
     * character is none: the client is not called, and the refusal holds no line break, so it cannot forge a line of
     * the terminal.
     */
    @Test
    void aReferenceWithWhiteSpaceOrAControlCharacter_isNoReference() throws IOException {
        CliContainerRuntime runtime = runtime(client("docker", "exit 0"));

        for (String reference : List.of("x\ntraceworks: done", "alpine 3", "alpine\t3", "alpine\u001b[2K",
                "alpine\u00a03", "-alpine", " ")) {
            assertThatThrownBy(() -> runtime.pull(reference)).as(reference).isInstanceOf(IOException.class)
                    .hasMessageContaining("not an image reference").message().doesNotContain("\n", "\u001b");
            assertThatThrownBy(() -> runtime.build(reference, dir.toString())).as(reference)
                    .isInstanceOf(IOException.class).message().doesNotContain("\n", "\u001b");
            assertThat(runtime.lookup(reference).imageId()).as(reference).isNull();
        }
        assertThat(calls()).as("the client was never called").isEmpty();
    }

    /** The folder of the runtime's own socket ({@code DOCKER_HOST}) is a session path too. */
    @Test
    void run_refusesTheFolderOfTheRuntimesSocket_fromDockerHost() throws IOException {
        Path docker = client("docker", "exit 0");
        Path sockets = Files.createDirectories(dir.resolve("docker-run"));
        Map<String, String> inherited = new java.util.HashMap<>(INHERITED);
        inherited.put("DOCKER_HOST", "unix://" + sockets.resolve("docker.sock"));
        CliContainerRuntime runtime = new CliContainerRuntime(docker.toString(), traceworksHome, home, inherited);

        CommandResult result = runtime.run(run(List.of(new Mount(sockets.toString(), "/run-docker", true)), null,
                Map.of()));

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.stderr()).contains("session");
        assertThat(calls()).isEmpty();
    }

    /** A declared path that does not exist is refused before the runtime could create or mount it. */
    @Test
    void run_refusesADeclaredPathThatDoesNotExist() throws IOException {
        Path docker = client("docker", "exit 0");

        CommandResult result = runtime(docker).run(run(List.of(new Mount(dir.resolve("missing").toString(), "/m",
                true)), null, Map.of()));

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.stderr()).contains("cannot be resolved");
        assertThat(calls()).isEmpty();
    }

    /** The session's runtime directory comes from the environment the runtime was built with. */
    @Test
    void run_refusesTheSessionsRuntimeDirectory_fromItsEnvironment() throws IOException {
        Path docker = client("docker", "exit 0");
        Path runtimeDir = Files.createDirectories(dir.resolve("runtime-1000"));
        Map<String, String> inherited = new java.util.HashMap<>(INHERITED);
        inherited.put("XDG_RUNTIME_DIR", runtimeDir.toString());
        CliContainerRuntime runtime = new CliContainerRuntime(docker.toString(), traceworksHome, home, inherited);

        CommandResult result = runtime.run(run(List.of(new Mount(runtimeDir.resolve("bus").toString(), "/bus",
                true)), null, Map.of()));

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.stderr()).contains("session");
        assertThat(calls()).isEmpty();
    }

    @Test
    void run_refusesWhatItsCommandLineCannotCarry() throws IOException {
        Path docker = client("docker", "exit 0");
        Path comma = Files.createDirectories(dir.resolve("data,dst=etc"));

        CommandResult result = runtime(docker).run(run(List.of(new Mount(comma.toString(), "/data", true)), null,
                Map.of()));

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.stderr()).contains("comma, quote or line break");
        assertThat(calls()).isEmpty();
    }

    /**
     * The command line carries the path the guard judged, for each of the step's own mounts by its place in the
     * list: two mounts of one host path are judged one by one, and nothing resolves the given path again. The judge
     * here answers with paths no resolution of the given one could give, so a second resolution would show.
     */
    @Test
    void run_mountsThePathTheGuardJudged_forEachDeclaredMountByItsPlace(@TempDir Path dir) throws IOException {
        Path docker = client("docker", "exit 0");
        Path data = Files.createDirectories(dir.resolve("data"));
        CliContainerRuntime runtime = runtime(docker);
        List<String> judgedMounts = new ArrayList<>();
        runtime.judge = (mount, context) -> {
            judgedMounts.add(mount.containerPath());
            return new MountGuard.Judged(Optional.empty(), "/judged/" + judgedMounts.size());
        };

        CommandResult result = runtime.run(run(List.of(new Mount(data.toString(), "/ro", true),
                new Mount(data.toString(), "/rw", false)), null, Map.of()));

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(judgedMounts).containsExactly("/ro", "/rw");
        List<String> started = calls().stream().filter(call -> call.contains("run")).findFirst().orElseThrow();
        assertThat(started).contains("type=bind,src=/judged/1,dst=/ro,readonly", "type=bind,src=/judged/2,dst=/rw")
                .noneMatch(argument -> argument.contains(data.toString()));
    }
}
