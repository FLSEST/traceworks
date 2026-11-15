package traceworks.adapters.host;

import traceworks.core.port.HookRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The hook runner: a program on the host, no shell, the payload on stdin, a timeout, the stderr tail. */
class HostHookRunnerTest {

    @TempDir
    Path temp;

    private Path script(String body) throws IOException {
        Path file = temp.resolve("hook.sh");
        Files.writeString(file, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    @Test
    void theHook_getsThePayloadOnStdin_runsInTheDirectory_andItsExitCodeIsReported() throws IOException {
        Path hook = script("cat > received.json; echo \"pwd=$(pwd)\" >> received.json; echo oops >&2; exit 3");
        Path dir = Files.createDirectories(temp.resolve("register"));

        HookRunner.Outcome outcome = new HostHookRunner(Map.of("PATH", System.getenv("PATH"), "SECRET", "x"))
                .run(List.of(hook.toString(), "--arg"), "{\"a\":1}\n".getBytes(StandardCharsets.UTF_8),
                        dir.toString(), Duration.ofSeconds(10));

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.stderrTail()).isEqualTo("oops\n");
        assertThat(outcome.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(Files.readString(dir.resolve("received.json"))).isEqualTo("{\"a\":1}\npwd=" + dir.toRealPath() + "\n");
    }

    @Test
    void aHookThatRunsTooLong_isEnded_withExit124() throws IOException {
        Path hook = script("sleep 30");

        HookRunner.Outcome outcome = new HostHookRunner(Map.of("PATH", System.getenv("PATH")))
                .run(List.of(hook.toString()), new byte[0], temp.toString(), Duration.ofMillis(300));

        assertThat(outcome.exitCode()).isEqualTo(124);
    }

    @Test
    void aHookThatCannotStart_isExit127_withTheReason() {
        HookRunner.Outcome outcome = new HostHookRunner(Map.of("PATH", System.getenv("PATH")))
                .run(List.of(temp.resolve("missing").toString()), new byte[0], temp.toString(), Duration.ofSeconds(1));

        assertThat(outcome.exitCode()).isEqualTo(127);
        assertThat(outcome.stderrTail()).contains("missing");
    }

    @Test
    void aHookThatNeverReadsItsInput_isEndedAtTheTimeout() throws IOException {
        Path hook = script("sleep 30");
        byte[] payload = new byte[256 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');

        HookRunner.Outcome outcome = new HostHookRunner(Map.of("PATH", System.getenv("PATH")))
                .run(List.of(hook.toString()), payload, temp.toString(), Duration.ofMillis(300));

        assertThat(outcome.exitCode()).isEqualTo(124);
        assertThat(outcome.durationMs()).isLessThan(10_000);
    }

    @Test
    void theStderrTail_isTheLast2000Characters_andTheEnvironmentIsTheOperators() throws IOException {
        Path hook = script("echo \"SECRET=${SECRET:-unset}\" >&2; for i in $(seq 1 500); do echo line$i >&2; done");

        HookRunner.Outcome outcome = new HostHookRunner(Map.of("PATH", System.getenv("PATH"), "SECRET", "x"))
                .run(List.of(hook.toString()), new byte[0], temp.toString(), Duration.ofSeconds(10));

        assertThat(outcome.stderrTail()).hasSizeLessThanOrEqualTo(HookRunner.STDERR_TAIL_CHARS).endsWith("line500\n");
        Path first = script("echo \"SECRET=${SECRET:-unset}\" >&2");
        assertThat(new HostHookRunner(Map.of("PATH", System.getenv("PATH"), "SECRET", "x"))
                .run(List.of(first.toString()), new byte[0], temp.toString(), Duration.ofSeconds(10)).stderrTail())
                .as("the hook is the operator's code and gets the operator's whole environment").isEqualTo("SECRET=x\n");
    }
}
