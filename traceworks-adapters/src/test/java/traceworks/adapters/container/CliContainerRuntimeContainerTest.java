package traceworks.adapters.container;

import traceworks.adapters.testing.ContainerTests;
import traceworks.core.container.RunPaths;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import traceworks.core.testing.ContainerPrecondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real containers through the real {@code podman} or {@code docker} client: the secure defaults hold where they
 * matter, in the container. Tagged {@code container}: it runs with {@code mvn -Pcontainers verify}, and fails there
 * when no runtime works or the pinned test image cannot be pulled. Prints nothing,
 * and leaves no container behind: after each test no container carries its run label.
 */
@Tag("container")
class CliContainerRuntimeContainerTest {

    private static final ContainerLimits LIMITS = new ContainerLimits("512m", 256, "1");

    @TempDir
    static Path traceworksHome;
    private static CliContainerRuntime runtime;
    private static String imageId;

    @TempDir
    Path dir;
    private final String runId = UUID.randomUUID().toString();
    private int containers;

    @BeforeAll
    static void runtimeAndImage() {
        runtime = ContainerTests.runtime(traceworksHome);
        imageId = ContainerTests.baseImageId(runtime);
    }

    @AfterEach
    void noContainerOfTheRunIsLeft() throws Exception {
        try {
            assertThat(containersOfTheRun()).as("containers of run %s left behind", runId).isEmpty();
        } finally {
            runtime.removeLeftovers(runId);
        }
    }

    private CommandResult shell(String script, List<Mount> mounts, boolean network, Map<String, String> env,
                                String stdin, int timeoutSeconds) {
        return runtime.run(new ContainerRun("traceworks-" + runId + "-" + ++containers, runId, imageId,
                "/bin/bash", List.of("-c", script), null, mounts, null, network, env, stdin, timeoutSeconds, LIMITS));
    }

    private CommandResult shell(String script) {
        return shell(script, List.of(), false, Map.of(), null, 60);
    }

    /** What {@code ps -a --filter label=<run label>=<run id> -q} lists: the containers of this test's run. */
    private String containersOfTheRun() throws Exception {
        return ContainerTests.client(runtime.name(), dir, "ps", "-a", "--filter",
                "label=" + ContainerArguments.RUN_LABEL + "=" + runId, "-q").strip();
    }

    @Test
    void true_exitsZero() {
        CommandResult result = shell("true");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void rootFileSystem_isReadOnly_andTmpIsWritable() {
        CommandResult root = shell("touch /x");
        CommandResult tmp = shell("touch /tmp/x && echo written");

        assertThat(root.exitCode()).isNotZero();
        assertThat(root.stderr()).contains("Read-only file system");
        assertThat(tmp.exitCode()).as(tmp.stderr()).isZero();
        assertThat(tmp.stdout()).isEqualTo("written\n");
    }

    @Test
    void readOnlyMount_cannotBeWritten_aWritableOneBelongsToTheOperator() throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path out = Files.createDirectories(dir.resolve("out"));
        List<Mount> mounts = List.of(new Mount(data.toString(), "/data", true), new Mount(out.toString(), "/out",
                false));

        CommandResult readOnly = shell("touch /data/x", mounts, false, Map.of(), null, 60);
        CommandResult writable = shell("echo hello > /out/y", mounts, false, Map.of(), null, 60);

        assertThat(readOnly.exitCode()).isNotZero();
        assertThat(readOnly.stderr()).contains("Read-only file system");
        assertThat(writable.exitCode()).as(writable.stderr()).isZero();
        assertThat(out.resolve("y")).hasContent("hello");
        assertThat(Files.getAttribute(out.resolve("y"), "unix:uid"))
                .isEqualTo(Files.getAttribute(traceworksHome, "unix:uid"));
    }

    /**
     * A writable workspace that holds the package: the run mounts the package once more, read-only, at its place in
     * {@code /workspace} ({@link RunPaths#standardMounts}), after the workspace and the scratch directory. The runtime
     * mounts the deeper target after the shallower one whatever the order of the arguments, so a write into the
     * package fails, also on the host, while a write elsewhere in the workspace still works.
     */
    @Test
    void thePackageInsideAWritableWorkspace_staysReadOnly() throws IOException {
        Path workspace = Files.createDirectories(dir.resolve("ws"));
        Path pkg = Files.createDirectories(workspace.resolve("pkg"));
        Path script = Files.createDirectories(pkg.resolve("scripts")).resolve("s.sh");
        Files.writeString(script, "echo original\n");
        Path scratch = Files.createDirectories(dir.resolve("scratch"));
        RunPaths paths = new RunPaths(workspace.toRealPath().toString(), pkg.toRealPath().toString(),
                scratch.toRealPath().toString(), true);
        RunDirectories directories = new RunDirectories(paths.workspace(), paths.packageDirectory(), paths.scratch());

        CommandResult intoPackage = runtime.run(new ContainerRun("traceworks-" + runId + "-" + ++containers, runId,
                imageId, "/bin/bash", List.of("-c", "echo changed > /workspace/pkg/scripts/s.sh"), "/workspace",
                paths.standardMounts(true), directories, false, Map.of(), null, 60, LIMITS));
        CommandResult elsewhere = runtime.run(new ContainerRun("traceworks-" + runId + "-" + ++containers, runId,
                imageId, "/bin/bash", List.of("-c", "echo written > /workspace/notes.txt"), "/workspace",
                paths.standardMounts(true), directories, false, Map.of(), null, 60, LIMITS));

        assertThat(intoPackage.exitCode()).isNotZero();
        assertThat(intoPackage.stderr()).contains("Read-only file system");
        assertThat(script).hasContent("echo original");
        assertThat(elsewhere.exitCode()).as(elsewhere.stderr()).isZero();
        assertThat(workspace.resolve("notes.txt")).hasContent("written");
    }

    @Test
    void withoutNetwork_nothingResolvesAndNothingConnects() {
        CommandResult resolve = shell("getent hosts example.org");
        CommandResult connect = shell("exec 3<>/dev/tcp/1.1.1.1/53");

        assertThat(resolve.exitCode()).isNotZero();
        assertThat(connect.exitCode()).isNotZero();
    }

    @Test
    void user_isTheOwnerOfTheTraceworksHome() throws IOException {
        CommandResult result = shell("id -u; id -g; echo \"$HOME $TMPDIR $LANG\"");

        assertThat(result.stdout()).isEqualTo(Files.getAttribute(traceworksHome, "unix:uid") + "\n"
                + Files.getAttribute(traceworksHome, "unix:gid") + "\n/tmp /tmp C.UTF-8\n");
    }

    @Test
    void capabilities_areAllDropped_andNoNewPrivilegesHolds() {
        CommandResult result = shell("grep -E '^(CapEff|NoNewPrivs):' /proc/self/status");

        assertThat(result.stdout()).contains("CapEff:\t0000000000000000").contains("NoNewPrivs:\t1");
    }

    /** The limits are the ones the run asked for, read from the cgroup v2 files of the container. */
    @Test
    void limits_areApplied() {
        CommandResult result = shell("cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/pids.max /sys/fs/cgroup/cpu.max");
        ContainerPrecondition.require(result.exitCode() == 0, "the container has no cgroup v2 files ("
                + result.stderr().strip() + ")", "run the container tests on a machine with cgroup v2");

        assertThat(result.stdout()).isEqualTo("536870912\n256\n100000 100000\n");
    }

    @Test
    void environmentValues_reachTheContainer() {
        CommandResult result = shell("echo \"$TOKEN\"", List.of(), false, Map.of("TOKEN", "s3cr3t-value"), null, 60);

        assertThat(result.stdout()).isEqualTo("s3cr3t-value\n");
    }

    @Test
    void stdin_reachesTheCommand_andALargeScriptRunsFromIt() {
        CommandResult prompt = shell("cat", List.of(), false, Map.of(), "the prompt", 60);
        CommandResult large = shell("value='" + "a".repeat(200_000) + "'\necho \"${#value}\"");

        assertThat(prompt.stdout()).isEqualTo("the prompt");
        assertThat(large.stdout()).as(large.stderr()).isEqualTo("200000\n");
    }

    @Test
    void timeout_gives124_andLeavesNoContainer() throws Exception {
        long start = System.nanoTime();

        CommandResult result = shell("sleep 30", List.of(), false, Map.of(), null, 1);

        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.timedOut()).isTrue();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(20);
        assertThat(containersOfTheRun()).isEmpty();
    }

    /**
     * On a timeout the command gets the SIGTERM the client forwards, and its own TERM trap runs to its
     * end: the init sends the signal to the runner's whole process group, and the runner waits for its command.
     * Nothing after the command runs.
     */
    @Test
    void timeout_letsTheCommandsTermTrapRun_andRunsNothingAfterIt() {
        CommandResult result = shell("echo started; bash -c 'trap \"sleep 0.5; echo cleaned up; exit 0\" TERM;"
                + " sleep 60 & wait'; echo after-the-command", List.of(), false, Map.of(), null, 3);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.stdout()).contains("started", "cleaned up").doesNotContain("after-the-command");
        assertThat(result.exitCode()).isEqualTo(124);
    }

    /**
     * A command that prints without end is stopped as soon as a stream goes beyond 16 MiB, long before its timeout,
     * and its container is removed as on a timeout: the result keeps exactly the first 16 MiB, names the stream and
     * has the limit's exit code. The output has no line breaks: the runtime's default log driver writes a container's
     * output line by line, which makes {@code yes} reach 16 MiB only after some seconds.
     */
    @Test
    void endlessOutput_stopsTheContainerAtTheLimit_andLeavesNone() throws Exception {
        long start = System.nanoTime();

        CommandResult stdout = shell("echo to stderr >&2; tr '\\0' y < /dev/zero; echo after-the-command >&2",
                List.of(), false, Map.of(), null, 120);
        CommandResult stderr = shell("tr '\\0' n < /dev/zero >&2", List.of(), false, Map.of(), null, 120);

        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(60);
        assertThat(stdout.exitCode()).isEqualTo(153);
        assertThat(stdout.timedOut()).isFalse();
        assertThat(stdout.truncatedStreams()).containsExactly("stdout");
        assertThat(stdout.stdout().length()).isEqualTo(16 * 1024 * 1024);
        assertThat(stdout.stdout().replace("y", "")).isEmpty();
        // bash may report how its command ended ("Terminated"), as on a timeout
        assertThat(stdout.stderr()).startsWith("to stderr\n").doesNotContain("after-the-command");
        assertThat(stderr.exitCode()).isEqualTo(153);
        assertThat(stderr.truncatedStreams()).containsExactly("stderr");
        assertThat(stderr.stderr().length()).isEqualTo(16 * 1024 * 1024);
        assertThat(containersOfTheRun()).isEmpty();
    }

    /** A script that sends itself SIGTERM fails with 143 in a container too. */
    @Test
    void aScriptThatTermsItself_failsWith143() {
        CommandResult result = shell("echo a; kill $$; echo b");

        assertThat(result.exitCode()).isEqualTo(143);
        assertThat(result.stdout()).isEqualTo("a\n");
    }

    /** A script that does not arrive whole, such as none at all, runs nothing and fails with 74. */
    @Test
    void aShellCommandThatIsEmpty_runsNothing_andExits74() {
        CommandResult result = shell("");

        assertThat(result.exitCode()).isEqualTo(74);
        assertThat(result.stderr()).contains("did not arrive whole on stdin");
    }

    @Test
    void removeLeftovers_removesAContainerStartedWithTheRunLabel() throws Exception {
        ContainerTests.client(runtime.name(), dir, "run", "-d", "--rm", "--name", "traceworks-" + runId + "-left",
                "--label", ContainerArguments.RUN_LABEL + "=" + runId, "--network", "none", imageId, "sleep", "60");
        assertThat(containersOfTheRun()).isNotEmpty();

        assertThat(runtime.removeLeftovers(runId)).as("confirmed gone").isTrue();

        assertThat(containersOfTheRun()).isEmpty();
        assertThat(runtime.mayStillRun(runId)).isFalse();
    }
}
