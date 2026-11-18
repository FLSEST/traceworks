package traceworks.adapters.host;

import traceworks.core.context.ExecutionContext;
import traceworks.core.testing.BackgroundChild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessTreeTest {

    private static Process start(String script) throws IOException {
        return new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
    }

    @Test
    void endsTheProcessAndItsBackgroundChild(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Process proc = start(BackgroundChild.script(pidFile));
        long child = BackgroundChild.awaitPid(pidFile);

        ProcessTree.terminate(proc).get(10, TimeUnit.SECONDS);

        assertThat(proc.isAlive()).isFalse();
        assertThat(BackgroundChild.endsWithin(child, Duration.ofSeconds(1))).as("background child %d still alive", child).isTrue();
    }

    @Test
    void asksPolitelyFirst_soCleanupTrapsStillRun(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("shell.pid");
        Path cleaned = dir.resolve("cleaned");
        // The trap also writes to the pipe: signalling must leave stdout open, or it dies of SIGPIPE
        Process proc = start("trap \"echo cleaning up; touch '" + cleaned + "'; exit 0\" TERM; echo $$ > '" + pidFile + "'; "
                + "while :; do sleep 1; done");
        BackgroundChild.awaitPid(pidFile);

        ProcessTree.terminate(proc).get(10, TimeUnit.SECONDS);

        assertThat(cleaned).exists();
        assertThat(proc.exitValue()).isZero();
    }

    @Test
    void killsWhatIgnoresTheRequestAfterTheGracePeriod(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        // An ignored SIGTERM is inherited, so the background sleep ignores it too
        Process proc = start("trap '' TERM; sleep 300 & echo $! > '" + pidFile + "'; while :; do sleep 1; done");
        long child = BackgroundChild.awaitPid(pidFile);

        ProcessTree.terminate(proc).get(ProcessTree.GRACE.toSeconds() + 5, TimeUnit.SECONDS);

        assertThat(proc.isAlive()).isFalse();
        assertThat(BackgroundChild.endsWithin(child, Duration.ofSeconds(1))).as("background child %d still alive", child).isTrue();
    }

    /**
     * A process without children that ignores SIGTERM: nothing of a tree is left to find after the grace period but
     * the process itself, which is killed then.
     */
    @Test
    void aLoneProcessThatIgnoresSigterm_isKilledAfterTheGracePeriod() throws Exception {
        // Shell builtins only: the loop starts no process of its own
        Process proc = start("trap '' TERM; echo ready; while :; do :; done");
        try {
            String first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return proc.inputReader().readLine();
                } catch (IOException e) {
                    return "reading failed: " + e;
                }
            }).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            assertThat(first).isEqualTo("ready");
            assertThat(proc.descendants()).as("a process without children").isEmpty();

            ProcessTree.terminate(proc).get(ProcessTree.GRACE.toSeconds() + 5, TimeUnit.SECONDS);

            assertThat(proc.waitFor(5, TimeUnit.SECONDS)).as("the process ended").isTrue();
        } finally {
            proc.destroyForcibly();
        }
    }

    /**
     * A cleanup that takes a while gets the grace period to finish: SIGKILL comes only for what is still there after
     * it.
     */
    @Test
    void aCleanupThatTakesAWhile_endsWithinTheGracePeriod_beforeAnythingIsKilled(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("shell.pid");
        Path cleaned = dir.resolve("cleaned");
        Process proc = start("trap 'sleep 0.3; touch " + cleaned + "; exit 0' TERM; echo $$ > " + pidFile + "; "
                + "while :; do sleep 0.1; done");
        BackgroundChild.awaitPid(pidFile);

        ProcessTree.terminate(proc).get(ProcessTree.GRACE.toSeconds() + 5, TimeUnit.SECONDS);

        assertThat(cleaned).as("the cleanup finished").exists();
        assertThat(proc.exitValue()).isZero();
    }

    /** What the process starts while it handles SIGTERM, within the grace period, is ended with it. */
    @Test
    void whatTheProcessStartsDuringTheGracePeriod_isKilledToo(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("shell.pid");
        Path late = dir.resolve("late.pid");
        Process proc = start("trap 'sleep 300 & echo $! > " + late + "' TERM; echo $$ > " + pidFile + "; "
                + "while :; do sleep 1; done");
        BackgroundChild.awaitPid(pidFile);

        ProcessTree.terminate(proc).get(ProcessTree.GRACE.toSeconds() + 5, TimeUnit.SECONDS);
        long child = BackgroundChild.awaitPid(late);

        assertThat(proc.isAlive()).isFalse();
        assertThat(BackgroundChild.endsWithin(child, Duration.ofSeconds(1)))
                .as("child %d, started during the grace period, still alive", child).isTrue();
    }

    /**
     * The process itself ends on SIGTERM, while a child of it ignores it and starts one more process: the child and
     * what it started are ended after the grace period, though the process they came from is gone.
     */
    @Test
    void aDescendantThatOutlivesTheProcess_isKilled_withWhatItStartedDuringTheGracePeriod(@TempDir Path dir)
            throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Path late = dir.resolve("late.pid");
        String inner = "trap \"sleep 300 & echo \\$! > " + late + "\" TERM; echo $$ > " + pidFile + "; "
                + "while :; do sleep 1; done";
        // Its output goes nowhere: the pipe closes when the process is gone, and the shell's own "Terminated" for the
        // sleep that was ended with it would then end the child with SIGPIPE before its trap runs
        Process proc = start("sh -c '" + inner + "' > /dev/null 2>&1 & wait");
        long child = BackgroundChild.awaitPid(pidFile);

        ProcessTree.terminate(proc).get(ProcessTree.GRACE.toSeconds() + 5, TimeUnit.SECONDS);
        long grandchild = BackgroundChild.awaitPid(late);

        assertThat(proc.isAlive()).isFalse();
        assertThat(BackgroundChild.endsWithin(child, Duration.ofSeconds(1)))
                .as("child %d, which outlived its parent, still alive", child).isTrue();
        assertThat(BackgroundChild.endsWithin(grandchild, Duration.ofSeconds(1)))
                .as("grandchild %d, started during the grace period, still alive", grandchild).isTrue();
    }

    /**
     * A command with a hundred children, which a shell waits for; they are signalled one by one, which takes a while.
     * It prints {@code ready} once they all run.
     */
    private static final String BUSY_COMMAND =
            "/bin/sh -c 'i=0; while [ $i -lt 100 ]; do sleep 300 & i=$((i+1)); done; echo ready; wait'";

    /**
     * A command line that is stopped starts nothing more. The shell here waits for a command and would print
     * {@code next} after it. A shell that sees its command end before its own signal has arrived goes on to the next
     * one, so every process is signalled before its children. The command has a hundred children: were they
     * signalled before the shell, it would have the time to go on; with one child it did so in a few of a hundred
     * stops.
     */
    @Test
    void theCommandAfterTheOneInFlight_doesNotStart() throws Exception {
        assertThat(printedAfterTheStop("echo started; " + BUSY_COMMAND + "; echo next")).isEmpty();
    }

    /** The same for a shell further down: a script that runs a second script, which waits for the command. */
    @Test
    void theCommandAfterTheOneInFlight_ofANestedShell_doesNotStart(@TempDir Path dir) throws Exception {
        Path second = Files.writeString(dir.resolve("second.sh"), BUSY_COMMAND + "\necho next\n");
        Path first = Files.writeString(dir.resolve("first.sh"),
                "echo started\n/bin/sh '" + second + "'\n");

        assertThat(printedAfterTheStop("/bin/sh '" + first + "'; :")).isEmpty();
    }

    /**
     * The same for a shell that ignores SIGTERM, and its command with it: they are killed after the grace, the shell
     * before its command, so it does not see the command end and go on.
     */
    @Test
    void theCommandAfterTheOneInFlight_ofAShellThatIgnoresSigterm_doesNotStart() throws Exception {
        assertThat(printedAfterTheStop("trap '' TERM; echo started; " + BUSY_COMMAND + "; echo next")).isEmpty();
    }

    /** How long a test waits for a line of a script, or for a process to be gone; only a failure takes that long. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /**
     * Starts {@code script}, which prints {@code started} and then {@code ready} once its command and the command's
     * children run, stops its tree, and returns what it printed after that. Nothing of the tree is left then,
     * whatever the test finds.
     */
    private static List<String> printedAfterTheStop(String script) throws Exception {
        Process proc = new ProcessBuilder("/bin/sh", "-c", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        List<ProcessHandle> tree = new ArrayList<>();
        try {
            // Read on a thread of its own, so a script that prints nothing fails the test instead of holding it
            BlockingQueue<Optional<String>> lines = new LinkedBlockingQueue<>();
            Thread.ofVirtual().start(() -> {
                try (BufferedReader output = proc.inputReader()) {
                    for (String line = output.readLine(); line != null; line = output.readLine()) {
                        lines.add(Optional.of(line));
                    }
                } catch (IOException e) {
                    lines.add(Optional.of("reading failed: " + e));
                }
                lines.add(Optional.empty());
            });
            assertThat(lines.poll(PATIENCE.toSeconds(), TimeUnit.SECONDS)).contains("started");
            assertThat(lines.poll(PATIENCE.toSeconds(), TimeUnit.SECONDS)).contains("ready");
            proc.descendants().forEach(tree::add);
            assertThat(tree).as("the command and its children").hasSizeGreaterThan(100);

            ProcessTree.terminate(proc).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

            List<String> printed = new ArrayList<>();
            while (true) {
                Optional<String> line = lines.poll(PATIENCE.toSeconds(), TimeUnit.SECONDS);
                assertThat(line).as("the output ends once the tree is gone").isNotNull();
                if (line.isEmpty()) {
                    break;
                }
                printed.add(line.get());
            }
            assertThat(proc.isAlive()).isFalse();
            for (ProcessHandle handle : tree) {
                assertThat(BackgroundChild.endsWithin(handle.pid(), PATIENCE))
                        .as("process %d of the tree still alive", handle.pid()).isTrue();
            }
            return printed;
        } finally {
            proc.descendants().forEach(tree::add);
            tree.forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
        }
    }

    // ---- the order of the signals ----

    /** Every process comes after its parent, whatever the order they were found in. */
    @Test
    void parentsFirst_putsEveryProcessAfterItsParent() {
        // 10 -> 20 -> 30 -> 40, 10 -> 21 -> 31, and 50 -> 60 -> 70 whose 50 is not in the tree any more
        Map<Long, Long> parentOf = Map.of(20L, 10L, 21L, 10L, 30L, 20L, 31L, 21L, 40L, 30L, 60L, 50L, 70L, 60L);
        List<Long> pids = new ArrayList<>(List.of(40L, 70L, 31L, 30L, 60L, 21L, 20L));

        for (int shuffle = 0; shuffle < 50; shuffle++) {
            Collections.shuffle(pids, new Random(shuffle));

            List<Long> ordered = ProcessTree.parentsFirst(pids, parentOf);

            assertThat(ordered).as("of %s", pids).containsExactlyInAnyOrderElementsOf(pids);
            for (long pid : ordered) {
                Long parent = parentOf.get(pid);
                if (parent != null && ordered.contains(parent)) {
                    assertThat(ordered.indexOf(parent)).as("%d before %d in %s", parent, pid, ordered)
                            .isLessThan(ordered.indexOf(pid));
                }
            }
        }
    }

    /** A process whose parent is unknown (it ended, or cannot be read) is one without a parent in the tree. */
    @Test
    void parentsFirst_withoutAParent_isAProcessOfItsOwn() {
        assertThat(ProcessTree.parentsFirst(List.of(3L, 2L, 1L), Map.of(1L, 2L))).containsExactly(3L, 2L, 1L);
        assertThat(ProcessTree.parentsFirst(List.of(), Map.of())).isEmpty();
    }

    /** A chain of parents that leads back to itself (a pid used again while the tree was read) ends. */
    @Test
    void parentsFirst_withACycle_ends() {
        assertThat(ProcessTree.parentsFirst(List.of(1L, 2L), Map.of(1L, 2L, 2L, 1L))).containsExactlyInAnyOrder(1L, 2L);
    }

    /** The handle a runner reports: cancelling the run ends the process and what it started in the background. */
    @Test
    void stopHandle_aCancelEndsTheProcessAndItsBackgroundChild(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Process shell = start(BackgroundChild.script(pidFile));
        long child = BackgroundChild.awaitPid(pidFile);
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.processTracker().started(ProcessTree.stopHandle(shell));

        context.cancel();

        assertThat(shell.waitFor(ProcessTree.GRACE.toSeconds() + 3, TimeUnit.SECONDS)).isTrue();
        assertThat(BackgroundChild.endsWithin(child, Duration.ofSeconds(5)))
                .as("background child %d outlived the cancelled run", child).isTrue();
    }

    @Test
    void anAlreadyFinishedProcessIsANoOp() throws Exception {
        Process proc = start("true");
        proc.waitFor(5, TimeUnit.SECONDS);

        ProcessTree.terminate(proc).get(1, TimeUnit.SECONDS);
        ProcessTree.terminate(null).get(1, TimeUnit.SECONDS);

        assertThat(proc.isAlive()).isFalse();
    }
}
