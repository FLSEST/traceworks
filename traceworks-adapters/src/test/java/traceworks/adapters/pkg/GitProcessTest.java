package traceworks.adapters.pkg;

import traceworks.core.testing.BackgroundChild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wait for one git call, with a script in git's place. What a call passes to git (the safe options, the
 * environment) is stated where git runs for real: {@link GitSourceCheckoutTest}, {@link GitInfoTest} and
 * {@link FilePackageLoaderTest}.
 */
class GitProcessTest {

    @TempDir
    Path dir;

    private Path fakeGit(String body) throws IOException {
        Path script = dir.resolve("fake-git");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** The wait looks at git every 100 ms: a call that takes several looks and ends in time gives what git gave. */
    @Test
    void run_ofACallThatTakesSeveralLooksOfTheWait_givesItsExitCodeAndOutput() throws Exception {
        GitProcess git = new GitProcess(fakeGit("sleep 0.5; echo out; echo err >&2; exit 4").toString(), List.of(),
                Map.of(), Map.of(), 10_000);

        GitProcess.Result result = git.run(dir, List.of("status"));

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.outputText()).isEqualTo("out\n");
        assertThat(result.errorText()).isEqualTo("err\n");
    }

    /** A git that does not finish in time is killed, not left running: the call fails as timed out. */
    @Test
    void run_ofACallBeyondItsTime_isTimedOut_andGitIsKilled() throws Exception {
        Path pidFile = dir.resolve("git.pid");
        GitProcess git = new GitProcess(fakeGit("echo $$ > '" + pidFile + "'\nexec sleep 30").toString(), List.of(),
                Map.of(), Map.of(), 300);

        assertThatThrownBy(() -> git.run(dir, List.of("status"))).isInstanceOf(GitProcess.TimedOut.class);

        long pid = BackgroundChild.awaitPid(pidFile);
        assertThat(BackgroundChild.endsWithin(pid, Duration.ofSeconds(2))).as("git %d still runs", pid).isTrue();
    }

    /**
     * git has ended, and its output has not: a helper it started still holds the pipe and writes into it. The call
     * waits for the end of the output and gives all of it, not what had arrived when git ended.
     *
     * <p>git runs for a moment first, as every git does: the promise holds for a git that still runs when the reading
     * has started (BoundedOutput#drain). It then ends right after its own line, when the reader may be between two
     * reads.
     */
    @Test
    void run_givesTheOutputToItsEnd_alsoWhatArrivesAfterGitEnded() throws Exception {
        GitProcess git = new GitProcess(fakeGit("sleep 0.3\n(sleep 0.3; echo late; sleep 0.2; echo later) 2>/dev/null &\n"
                + "echo first; exit 0").toString(), List.of(), Map.of(), Map.of(), 10_000);

        GitProcess.Result result = git.run(dir, List.of("status"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.outputText()).isEqualTo("first\nlate\nlater\n");
        assertThat(result.errorText()).isEmpty();
    }

    /** The same for the standard error, which is read on a thread of its own. */
    @Test
    void run_givesTheErrorOutputToItsEnd_alsoWhatArrivesAfterGitEnded() throws Exception {
        GitProcess git = new GitProcess(fakeGit("sleep 0.3\n(sleep 0.3; echo late >&2) >/dev/null &\n"
                + "echo first >&2; exit 3").toString(), List.of(), Map.of(), Map.of(), 10_000);

        GitProcess.Result result = git.run(dir, List.of("status"));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.errorText()).isEqualTo("first\nlate\n");
        assertThat(result.outputText()).isEmpty();
    }
}
