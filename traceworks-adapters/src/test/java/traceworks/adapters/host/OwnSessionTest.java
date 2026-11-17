package traceworks.adapters.host;

import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A step's process starts in a session of its own, so the terminal's Ctrl-C, which goes to the foreground process
 * group, does not reach it.
 */
class OwnSessionTest {

    @Test
    void find_takesTheFirstSetsidThatCanRun_andNoneWhereThereIsNone(@TempDir Path dir) throws IOException {
        Path without = Files.createDirectories(dir.resolve("without"));
        Path with = Files.createDirectories(dir.resolve("with"));
        Path program = Files.writeString(with.resolve("setsid"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path notRunnable = Files.createDirectories(dir.resolve("plain"));
        Files.writeString(notRunnable.resolve("setsid"), "text");
        Files.setPosixFilePermissions(notRunnable.resolve("setsid"), PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(OwnSession.find(without + ":" + notRunnable + "::" + with)).contains(program.toString());
        assertThat(OwnSession.find(without.toString())).isEmpty();
        assertThat(OwnSession.find(null)).isEmpty();
    }

    @Test
    void command_goesBehindSetsid_whereThereIsOne() {
        List<String> command = OwnSession.command(List.of("/bin/sh", "-c", "true"));

        if (OwnSession.available()) {
            assertThat(command).hasSize(4).endsWith("/bin/sh", "-c", "true");
            assertThat(command.getFirst()).endsWith("/setsid");
        } else {
            assertThat(command).containsExactly("/bin/sh", "-c", "true");
        }
    }

    /** A program that cannot start is left alone, so that its start fails as before and setsid does not run instead. */
    @Test
    void command_ofAProgramThatCannotStart_staysAsItIs(@TempDir Path dir) {
        assertThat(OwnSession.command(List.of(dir.resolve("no-such-client").toString(), "run")))
                .containsExactly(dir.resolve("no-such-client").toString(), "run");
        assertThat(OwnSession.command(List.of("no-such-client-on-the-path", "run")))
                .containsExactly("no-such-client-on-the-path", "run");
    }

    /** The command of a host step leads a session of its own, and is still the process Traceworks started. */
    @Test
    void aHostCommand_runsInASessionOfItsOwn() {
        assumeTrue(OwnSession.available() && Files.isReadable(Path.of("/proc/self/stat")),
                "needs setsid and /proc");
        // The sixth field of /proc/<pid>/stat is the session id; a session leader's is its own pid
        CommandResult result = new HostCommandRunner().run(new CommandRequest(
                "cut -d' ' -f6 /proc/$$/stat; echo $$", null, 30, null, null, null, false));

        assertThat(result.exitCode()).as(result.stdout()).isZero();
        String[] lines = result.stdout().strip().split("\n");
        assertThat(lines[0]).as("the session id of the command's shell").isEqualTo(lines[1]);
    }

    // ---- the instant before the process reaches its own session ----

    /** Ended with 130 right after its start, and a Ctrl-C came in that time: the command had not started. */
    @Test
    void killedAtStart_isAProcessThatEndedWith130RightAfterItsStart_whileCtrlCCame() {
        assumeTrue(OwnSession.available(), "needs setsid");
        long started = System.nanoTime();
        OwnSession.terminalInterrupt();

        assertThat(OwnSession.killedAtStart(130, started)).isTrue();
        assertThat(OwnSession.killedAtStart(1, started)).as("another exit code").isFalse();
        assertThat(OwnSession.killedAtStart(0, started)).isFalse();
        assertThat(OwnSession.killedAtStart(130, started - TimeUnit.SECONDS.toNanos(3)))
                .as("it had run for three seconds: the 130 is its own").isFalse();
    }

    /** A command that ends with 130 by itself, with no Ctrl-C since long before its start, is not started again. */
    @Test
    void killedAtStart_isNotAProcessThatEndedWith130ByItself() throws InterruptedException {
        OwnSession.terminalInterrupt();
        Thread.sleep(150);   // longer than the slack between the two clocks
        long started = System.nanoTime();

        assertThat(OwnSession.killedAtStart(130, started)).isFalse();
    }

    /** The JVM's own helper died of the Ctrl-C, which the JDK reports as a failed start: the start is tried again. */
    @Test
    void start_thatFailedWhileCtrlCCame_isTriedAgain() throws IOException {
        assumeTrue(OwnSession.available(), "needs setsid");
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        // The Ctrl-C comes while the start is under way, as it does when it kills the helper
        OwnSession.Starter failsOnce = () -> {
            if (starts.incrementAndGet() == 1) {
                OwnSession.terminalInterrupt();
                throw new IOException("Failed to exec spawn helper: pid: 1, signal: 2");
            }
            return new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
        };

        Process process = OwnSession.start(failsOnce);

        assertThat(process).isNotNull();
        assertThat(starts.get()).isEqualTo(2);
    }

    /** A start that fails with no Ctrl-C near it fails: a client that is not there is not looked for twice. */
    @Test
    void start_thatFailsByItself_fails() throws InterruptedException {
        OwnSession.terminalInterrupt();
        Thread.sleep(150);
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OwnSession.start(() -> {
            starts.incrementAndGet();
            throw new IOException("No such file or directory");
        })).isInstanceOf(IOException.class).hasMessage("No such file or directory");
        assertThat(starts.get()).isEqualTo(1);
    }

    /** The host command the Ctrl-C killed while it started runs again, once; one that ends with 130 by itself does not. */
    @Test
    void aHostCommandKilledAtItsStart_runsAgain(@TempDir Path dir) throws Exception {
        assumeTrue(OwnSession.available(), "needs setsid");
        Path calls = dir.resolve("calls");
        Path interrupted = dir.resolve("interrupted");
        // The first call ends as a process a SIGINT killed, once the Ctrl-C has come; the second one works
        String command = "echo call >> '" + calls + "'; if [ \"$(wc -l < '" + calls + "')\" -lt 2 ]; then "
                + "while [ ! -e '" + interrupted + "' ]; do sleep 0.01; done; exit 130; fi; echo done";
        // The Ctrl-C comes while the first call is under way: no duration places it near the start
        Thread ctrlC = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!Files.exists(calls) && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                OwnSession.terminalInterrupt();
                Files.createFile(interrupted);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        ctrlC.start();

        CommandResult result = new HostCommandRunner().run(new CommandRequest(command, null, 30, null, null, null,
                false));
        ctrlC.join();

        assertThat(result.exitCode()).as(result.stdout()).isZero();
        assertThat(result.stdout()).contains("done");
        assertThat(Files.readAllLines(calls)).hasSize(2);
    }

    @Test
    void aHostCommandThatEndsWith130ByItself_runsOnce(@TempDir Path dir) throws Exception {
        Path calls = dir.resolve("calls");
        OwnSession.terminalInterrupt();
        Thread.sleep(150);

        CommandResult result = new HostCommandRunner().run(new CommandRequest(
                "echo call >> '" + calls + "'; exit 130", null, 30, null, null, null, false));

        assertThat(result.exitCode()).isEqualTo(130);
        assertThat(Files.readAllLines(calls)).hasSize(1);
    }
}
