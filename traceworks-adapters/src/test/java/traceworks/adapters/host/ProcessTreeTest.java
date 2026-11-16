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

    /** A command with a hundred children, which a shell waits for; they are signalled one by one, which takes a while. */
    private static final String BUSY_COMMAND =
            "/bin/sh -c 'i=0; while [ $i -lt 100 ]; do sleep 300 & i=$((i+1)); done; wait'";

    /**
     * A command line that is stopped starts nothing more. The shell here waits for a command and would print
     * {@code next} after it. A shell that sees its command end before its own signal has arrived goes on to the next
     * one, so every process is signalled before its children. The command has a hundred children: were they
     * signalled before the shell, it would have the time to go on; with one child it did so in a few of a hundred
     * stops.
     */
    @Test
    void theCommandAfterTheOneInFlight_doesNotStart() throws Exception {
        assertThat(printedAfterTheStop("echo started; " + BUSY_COMMAND + "; echo next", 101)).isNull();
    }

    /** The same for a shell further down: a script that runs a second script, which waits for the command. */
    @Test
    void theCommandAfterTheOneInFlight_ofANestedShell_doesNotStart(@TempDir Path dir) throws Exception {
        Path second = Files.writeString(dir.resolve("second.sh"), BUSY_COMMAND + "\necho next\n");
        Path first = Files.writeString(dir.resolve("first.sh"),
                "echo started\n/bin/sh '" + second + "'\n");

        assertThat(printedAfterTheStop("/bin/sh '" + first + "'", 103)).isNull();
    }

    /**
     * Starts {@code script}, which prints {@code started}, stops its tree once it has {@code descendants} processes
     * below it, and returns the next line it printed; {@code null} when it printed nothing more.
     */
    private static String printedAfterTheStop(String script, int descendants) throws Exception {
        Process proc = new ProcessBuilder("/bin/sh", "-c", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (BufferedReader output = proc.inputReader()) {
            assertThat(output.readLine()).isEqualTo("started");
            while (proc.descendants().count() < descendants) {
                Thread.sleep(5);   // until the command and its children are in flight
            }

            ProcessTree.terminate(proc).get(10, TimeUnit.SECONDS);

            return output.readLine();
        }
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
