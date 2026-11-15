package traceworks.adapters.host;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** How a shell command reaches bash: the runner, the script on stdin and its end marker. */
class BashScriptTest {

    /**
     * A runner that cannot read its stdin ends with EX_IOERR (74), not 126: the shell block's recovery hint reads 126
     * as a permission problem and would suggest chmod.
     */
    @Test
    void runner_withoutAReadableStdin_failsWithItsOwnExitCode() throws Exception {
        Process process = new ProcessBuilder("/bin/bash", "-c", "exec 0<&-; exec /bin/bash -c \"$1\"", "outer",
                BashScript.RUNNER).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();

        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(BashScript.UNREADABLE_STDIN_EXIT).isEqualTo(74);
    }

    @Test
    void viaStdin_countsUtf8Bytes() {
        String twoBytesEach = "é".repeat(BashScript.ARGUMENT_LIMIT_BYTES / 2);

        assertThat(BashScript.viaStdin(twoBytesEach)).as("two bytes each").isTrue();
        assertThat(BashScript.viaStdin(twoBytesEach.substring(1))).isFalse();
    }

    @Test
    void argumentAndStdin_ofASmallScript_areTheRunnerAndTheScriptWithItsEnd_too() {
        assertThat(BashScript.argument("echo hi")).isEqualTo(BashScript.RUNNER);
        assertThat(BashScript.stdin("echo hi")).isEqualTo("echo hi" + BashScript.END);
    }

    /** Runs the runner the way the runners do, with {@code stdin} as its standard input. */
    private static Process runner(String stdin) throws Exception {
        Process process = new ProcessBuilder("/bin/bash", "-c", BashScript.RUNNER).redirectErrorStream(true).start();
        try (var in = process.getOutputStream()) {
            in.write(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return process;
    }

    /**
     * When the client loses the script, the runner gets an empty or cut stdin; it then runs nothing
     * and fails with 74 instead of running an empty script that succeeds. An empty command is refused the same way.
     */
    @Test
    void runner_refusesAStdinWithoutTheWholeScript_withExit74() throws Exception {
        for (String stdin : List.of("", "echo cut-off", BashScript.stdin(""))) {
            Process process = runner(stdin);

            assertThat(process.exitValue()).as("[%s]", stdin).isEqualTo(74);
            String output = new String(process.getInputStream().readAllBytes());
            assertThat(output).as(stdin).contains("did not arrive whole on stdin").doesNotContain("cut-off\n");
            assertThat(BashScript.notWhole(process.exitValue(), output)).as("[%s] is known as not run", stdin).isTrue();
        }
    }

    /** Only the runner's own line with its exit code says that the script did not arrive whole. */
    @Test
    void notWhole_needsTheRunnersLine_asTheLastLine_andExit74() {
        assertThat(BashScript.notWhole(74, BashScript.NOT_WHOLE + "\n")).isTrue();
        assertThat(BashScript.notWhole(74, "a client warning\n" + BashScript.NOT_WHOLE)).isTrue();
        assertThat(BashScript.notWhole(74, "")).as("a script that exits 74 by itself").isFalse();
        assertThat(BashScript.notWhole(74, "cannot read input")).isFalse();
        assertThat(BashScript.notWhole(1, BashScript.NOT_WHOLE)).isFalse();
        assertThat(BashScript.notWhole(74, BashScript.NOT_WHOLE + "\nmore")).isFalse();
        assertThat(BashScript.notWhole(74, "x" + BashScript.NOT_WHOLE)).isFalse();
        assertThat(BashScript.notWhole(74, null)).isFalse();
    }

    /**
     * A script that sends itself SIGTERM fails with 143, as under {@code bash -c}; the runner's TERM trap
     * never turns that into success, and nothing after the signal runs.
     */
    @Test
    void runner_aScriptThatTermsItself_failsWith143() throws Exception {
        for (String script : List.of("echo a; kill $$; echo b", "die() { echo \"$1\" >&2; kill $$; }; false || die bad",
                "x=$(echo v; kill $$); echo \"$x\"; echo b")) {
            Process process = runner(BashScript.stdin(script));

            assertThat(process.exitValue()).as(script).isEqualTo(143);
            assertThat(new String(process.getInputStream().readAllBytes())).as(script).doesNotContain("b\n");
        }
    }

    /** A script's own TERM trap decides, as under {@code bash -c}: here it exits 7. */
    @Test
    void runner_aScriptsOwnTermTrap_decidesTheExitCode() throws Exception {
        Process process = runner(BashScript.stdin("trap 'echo trapped; exit 7' TERM; kill $$; echo b"));

        assertThat(process.exitValue()).isEqualTo(7);
        assertThat(new String(process.getInputStream().readAllBytes())).isEqualTo("trapped\n");
    }

    /** The end marker keeps the script's own trailing newlines, which a command substitution would drop. */
    @Test
    void runner_keepsTheTrailingNewlinesOfTheScript() throws Exception {
        Process process = runner(BashScript.stdin("echo a \\\n"));

        assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getInputStream().readAllBytes())).isEqualTo("a\n");
    }

    @Test
    void argumentAndStdin_ofALargeScript_areTheRunnerAndTheScript() {
        String large = "# " + "x".repeat(BashScript.ARGUMENT_LIMIT_BYTES);

        assertThat(BashScript.argument(large)).isEqualTo(BashScript.RUNNER);
        assertThat(BashScript.stdin(large)).isEqualTo(large + BashScript.END);
    }
}
