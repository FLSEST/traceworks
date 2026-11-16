package traceworks.adapters.host;

import traceworks.core.context.ExecutionContext;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.testing.BackgroundChild;
import traceworks.core.testing.RecordingTracker;
import traceworks.core.util.ProcessTracking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The host command runner. There is no live output to test (a later version brings step log events). The runner
 * reports the merged output as stdout; the shell block trims it and adds the
 * timeout line, so these tests see the raw text.
 */
class HostCommandRunnerTest {

    private final HostCommandRunner runner = new HostCommandRunner();

    private static CommandRequest shell(String command, String workingDirectory, int timeoutSeconds) {
        return new CommandRequest(command, workingDirectory, timeoutSeconds, null, null, null, false);
    }

    private CommandResult run(String command, int timeoutSeconds) {
        return runner.run(shell(command, null, timeoutSeconds));
    }

    /** A host runner has no catalog of images: it answers with the name it is given. */
    @Test
    void imageReference_isTheNameItself() {
        assertThat(runner.imageReference("./images/tool")).isEqualTo("./images/tool");
    }

    @Test
    void run_echo_returnsItsOutput() {
        CommandResult result = run("echo hello", 30);

        assertThat(result.stdout()).isEqualTo("hello\n");
        assertThat(result.stderr()).isEmpty();
        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    /**
     * Under a JVM whose encoding is not UTF-8, a value beyond ASCII would reach bash changed; the command
     * is refused like one that cannot start, before anything runs, naming the variable but not its value.
     */
    @Test
    void run_withoutAUtf8Encoding_refusesTextBeyondAscii_beforeAnythingRuns(@TempDir Path dir) {
        Path marker = dir.resolve("ran");
        Map<String, String> inherited = Map.of("PATH", System.getenv("PATH"), "HOME", "/home/op");
        HostCommandRunner ascii = new HostCommandRunner(inherited, "ANSI_X3.4-1968");
        CommandRequest request = new CommandRequest("touch '" + marker + "'", null, 30, Map.of("TOKEN",
                "caf\u00e9-value-1"), null, null, false);

        CommandResult refused = ascii.run(request);
        CommandResult homeRefused = new HostCommandRunner(Map.of("PATH", System.getenv("PATH"), "HOME",
                "/home/jos\u00e9"), "ANSI_X3.4-1968").run(shell("true", null, 30));

        assertThat(refused.exitCode()).isEqualTo(1);
        assertThat(refused.stdout()).startsWith("Error: The command cannot start: the environment variable TOKEN")
                .contains("ANSI_X3.4-1968", "LANG=C.UTF-8").doesNotContain("caf\u00e9");
        assertThat(marker).as("nothing ran").doesNotExist();
        assertThat(refused.notRunReason()).as("the runner knows nothing ran")
                .startsWith("The command cannot start: the environment variable TOKEN").doesNotContain("caf\u00e9");
        assertThat(homeRefused.stdout()).contains("the environment variable HOME").doesNotContain("jos\u00e9");
        assertThat(ascii.run(shell("echo plain", null, 30)).stdout()).as("ASCII passes").isEqualTo("plain\n");
        assertThat(new HostCommandRunner(inherited, "UTF-8").run(request).exitCode()).as("UTF-8 passes").isZero();
        assertThat(marker).exists();
    }

    /**
     * The values of a request are shell variables its command finds set. The text bash parses holds none of their
     * bytes as code, so nothing a value holds runs: every hostile value arrives byte for byte in {@code "$VALUE"},
     * and no file named {@code pwned} appears. A NUL is taken out before the value is sent.
     */
    @Test
    void run_values_arriveByteForByteInTheirVariable_andNothingOfThemRuns(@TempDir Path dir) throws Exception {
        String delimiterWithNul = "x\nTRACEWORKS_VALUE_1_EN\u0000D\ntouch pwned\n: <<'TRACEWORKS_VALUE_1_END'";
        List<String> hostile = new java.util.ArrayList<>(List.of(
                "$(touch pwned)", "`touch pwned`", "'; touch pwned; '", "\"; touch pwned; \"", "x\ntouch pwned",
                "\\x27; touch pwned; \\x27", "\\c", "%s %b \\n \\\\", "a b", "*", "-rf /", "", "\n", "\n\n\n",
                "tail\n\n", "café € 😀", delimiterWithNul, "\u0000", "a\u0000", "\u0000b",
                "a\u0000\u0000b\u0000", "'\u0000'", "TRACEWORKS_VALUE\u0000 X 1 'x'\nunset -f TRACEWORKS_VALUE\ntouch pwned",
                "line with 'quotes', \"more\", $(nothing) and `nothing`\n".repeat(1024 * 1024 / 55)));
        for (int at = 0; at <= "ab'c".length(); at++) {
            hostile.add(new StringBuilder("ab'c").insert(at, '\u0000').toString());
        }
        Path out = dir.resolve("value");
        String command = "set -euo pipefail\nprintf '%s' \"$VALUE\" > '" + out + "'; printf '%s' \"${#NAME}\"";

        for (String value : hostile) {
            java.nio.file.Files.deleteIfExists(out);
            Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("VALUE", value);
            values.put("NAME", "name");
            CommandRequest request = shell(command, dir.toString(), 60).withValues(values);

            CommandResult result = runner.run(request);

            String what = value.length() > 60 ? value.substring(0, 60) + "…" : value;
            assertThat(result.exitCode()).as("%s: %s", what, result.stderr()).isZero();
            assertThat(result.stdout()).as("the second variable: %s", what).isEqualTo("4");
            assertThat(java.nio.file.Files.readString(out).equals(value.replace("\u0000", "")))
                    .as("the value, byte for byte, without NUL: %s", what).isTrue();
            assertThat(dir.resolve("pwned")).as("a command of the value ran: %s", what).doesNotExist();
            assertThat(request.script()).as("no NUL in the text bash reads").doesNotContain("\u0000");
        }
    }

    /** A value as large as the output limit arrives whole, and no child process inherits the variable. */
    @Test
    void run_aValueOf16MiB_arrivesWhole_andIsNotExported(@TempDir Path dir) throws Exception {
        String value = "line with 'quotes', \"more\", $(nothing run) and `nothing`\n".repeat(16 * 1024 * 1024 / 58)
                + "\n\n";
        Path out = dir.resolve("value");
        CommandRequest request = shell("printf '%s' \"$VALUE\" > '" + out + "'; env | grep -c VALUE || true", null, 120)
                .withValues(Map.of("VALUE", value));

        CommandResult result = new HostCommandRunner(64 * 1024 * 1024).run(request);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).as("not in the environment of a child").isEqualTo("0\n");
        assertThat(java.nio.file.Files.readString(out).equals(value)).as("the value, byte for byte").isTrue();
    }

    /**
     * A value that does not arrive whole ends the shell before the command's first line: the length bash finds is
     * compared with the length that was sent, here made wrong on purpose.
     */
    @Test
    void run_aValueThatDidNotArriveWhole_endsTheShell_beforeTheCommandRuns(@TempDir Path dir) {
        Path marker = dir.resolve("ran");
        CommandRequest request = shell("touch '" + marker + "'", null, 30).withValues(Map.of("VALUE", "four"));
        String cut = request.script().replace("TRACEWORKS_VALUE VALUE 4 'four'", "TRACEWORKS_VALUE VALUE 4 'fou'");
        assertThat(cut).isNotEqualTo(request.script());

        CommandResult result = run(cut, 30);

        assertThat(result.exitCode()).isEqualTo(74);
        assertThat(result.stdout() + result.stderr()).contains("traceworks: the value of VALUE did not arrive whole");
        assertThat(marker).doesNotExist();
    }

    @Test
    void run_failingCommand_returnsItsExitCode() {
        CommandResult result = run("exit 42", 30);

        assertThat(result.exitCode()).isEqualTo(42);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.didNotRun()).isFalse();
    }

    /** A command that exits 1, 74 or 125 by itself ran: only the runner's own knowledge marks a command not run. */
    @Test
    void run_aCommandThatExitsLikeARefusalByItself_ran() {
        for (int code : new int[] {1, 74, 125}) {
            CommandResult result = run("echo 'Error: odd' >&2; exit " + code, 30);

            assertThat(result.exitCode()).isEqualTo(code);
            assertThat(result.didNotRun()).as("exit " + code).isFalse();
        }
    }

    /**
     * The wait looks at the command every 200 ms: a command that takes several looks and ends well within its time
     * ends with its own exit code and output, not as timed out.
     */
    @Test
    void run_commandThatTakesLongerThanALookButLessThanItsTime_endsByItself() {
        CommandResult result = run("sleep 0.7; echo done; exit 3", 30);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEqualTo("done\n");
    }

    /** Exit code 124 as the shell block reports a timeout; the block adds the "timed out" line itself. */
    @Test
    void run_timeout_isTimedOutWithExitCode124() {
        long start = System.nanoTime();

        CommandResult result = run("sleep 60", 1);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void run_timeout_keepsThePartialOutput() {
        CommandResult result = run("printf 'half a line'; sleep 60", 1);

        assertThat(result.timedOut()).isTrue();
        // The runner outlives its command, so bash may report how it ended ("Terminated", in the locale's words)
        assertThat(result.stdout()).startsWith("half a line");
    }

    @Test
    void run_timeout_alsoEndsWhatTheCommandStartedInTheBackground(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Thread command = Thread.ofVirtual().start(() -> run(BackgroundChild.script(pidFile), 1));
        long child = BackgroundChild.awaitPid(pidFile);
        command.join(15_000);

        assertThat(BackgroundChild.endsWithin(child, Duration.ofSeconds(5)))
                .as("background child %d outlived the timed-out command", child).isTrue();
    }

    /** A runner that keeps at most {@code limit} bytes of the output, as the operator's limit does. */
    private static HostCommandRunner limitedTo(int limit) {
        return new HostCommandRunner(System.getenv(), ProcessEncoding.ofTheJvm(), limit);
    }

    /**
     * A command that prints without end is stopped as soon as its output goes beyond the limit, long before its
     * timeout: it fails with the limit's exit code, keeps exactly the first bytes and names the cut stream, which on
     * the host is the merged output.
     */
    @Test
    void run_outputBeyondTheLimit_stopsTheCommandAtOnce_andKeepsTheFirstBytes(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("yes.pid");
        long start = System.nanoTime();

        CommandResult result = limitedTo(64 * 1024).run(shell("sh -c 'echo $$ > \"$0\"; exec yes' '" + pidFile
                + "'", null, 120));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
        assertThat(result.exitCode()).isEqualTo(CommandRunner.OUTPUT_LIMIT_EXIT_CODE).isEqualTo(153);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.truncatedStreams()).containsExactly("stdout");
        assertThat(result.stdout().length()).isEqualTo(64 * 1024);
        assertThat(result.stdout().replace("y\n", "")).as("the first lines, none cut").isEmpty();
        assertThat(result.outputLimitBytes()).as("the limit it had").isEqualTo(64 * 1024);
        assertThat(result.truncationNote()).contains("exceeded 64 KiB");
        long yes = Long.parseLong(Files.readString(pidFile).strip());
        assertThat(BackgroundChild.endsWithin(yes, Duration.ofSeconds(5))).as("yes was stopped").isTrue();
    }

    /** By default the runner keeps 16 MiB; the operator's limit is what the CLI wires. */
    @Test
    void run_endlessOutput_keepsSixteenMebibytes() {
        CommandResult result = run("yes", 120);

        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.truncatedStreams()).containsExactly("stdout");
        assertThat(result.stdout().length()).isEqualTo(CommandRunner.OUTPUT_LIMIT_BYTES).isEqualTo(16 * 1024 * 1024);
        assertThat(new HostCommandRunner(1024 * 1024).run(shell("yes", null, 120)).stdout().length())
                .as("the public runner with the operator's limit").isEqualTo(1024 * 1024);
    }

    /** A command that wrote beyond the limit has failed, also when it ended by itself before the runner noticed. */
    @Test
    void run_outputBeyondTheLimit_failsAlsoWhenTheCommandEndedByItself() {
        CommandResult result = limitedTo(1024).run(shell("head -c 1025 /dev/zero | tr '\\0' a", null, 30));

        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.truncatedStreams()).containsExactly("stdout");
        assertThat(result.stdout()).isEqualTo("a".repeat(1024));
    }

    /** "é" is two bytes in UTF-8; the one the limit splits is left out whole, not decoded to U+FFFD. */
    @Test
    void run_aCharacterThatStraddlesTheLimit_isNotSplit() {
        CommandResult result = limitedTo(1024).run(shell("head -c 1023 /dev/zero | tr '\\0' a; printf '\\303\\251 and"
                + " more'", null, 30));

        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.stdout()).isEqualTo("a".repeat(1023));
    }

    /** Up to the limit nothing changes: the output is whole, byte for byte, and not marked. */
    @Test
    void run_outputUpToTheLimit_isWholeAndNotMarked() {
        HostCommandRunner limited = limitedTo(1024);

        CommandResult below = limited.run(shell("head -c 1022 /dev/zero | tr '\\0' a; printf '\\303\\251'", null,
                30));
        CommandResult at = limited.run(shell("head -c 1024 /dev/zero | tr '\\0' b", null, 30));

        assertThat(below.exitCode()).isZero();
        assertThat(below.truncatedStreams()).isEmpty();
        assertThat(below.stdout()).isEqualTo("a".repeat(1022) + "é");
        assertThat(at.exitCode()).isZero();
        assertThat(at.truncatedStreams()).isEmpty();
        assertThat(at.stdout()).isEqualTo("b".repeat(1024));
    }

    @Test
    void run_workingDirectory_isWhereTheCommandStarts(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("testfile.txt"), "content");

        CommandResult result = runner.run(shell("ls testfile.txt", dir.toString(), 30));

        assertThat(result.stdout()).isEqualTo("testfile.txt\n");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void run_inAWorkingDirectoryThatDoesNotExist_failsWithTheReason(@TempDir Path dir) {
        CommandResult result = runner.run(shell("echo hi", dir.resolve("missing").toString(), 30));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).startsWith("Error: ");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.notRunReason()).as("the process never started").startsWith("the process could not start: ");
    }

    @Test
    void run_multilineOutput_capturedFully() {
        CommandResult result = run("echo line1 && echo line2 && echo line3", 30);

        assertThat(result.stdout()).isEqualTo("line1\nline2\nline3\n");
    }

    /** One stream: stderr lines stand where the command wrote them, stderr stays empty. */
    @Test
    void run_stderr_isMergedIntoStdoutInTheOrderItWasWritten() {
        CommandResult result = run("echo out1; echo err1 >&2; echo out2; echo err2 >&2", 30);

        assertThat(result.stdout()).isEqualTo("out1\nerr1\nout2\nerr2\n");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void run_envAssignmentPrefix_isVisibleInsideQuotedHeredoc() {
        CommandResult result = run("PROJECT_ID='abc' bash <<'SH'\necho \"$PROJECT_ID\"\nSH", 10);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("abc\n");
    }

    // --- environment ---

    @Test
    void run_scrubsTheInstanceKeyFromTheInheritedEnvironment() {
        HostCommandRunner withKey = new HostCommandRunner(Map.of(
                "AUTOMATION_API_KEY", "instance-key", "PATH", System.getenv("PATH"), "LANG", "C.UTF-8"));

        CommandResult result = withKey.run(shell("echo \"[${AUTOMATION_API_KEY-unset}] [$LANG]\"", null, 30));

        assertThat(result.stdout()).isEqualTo("[unset] [C.UTF-8]\n");
    }

    /**
     * Only the operator's own environment reaches a command: credentials the JVM was started with (an AI provider's
     * key, cloud credentials, any other name) stay out, PATH and the locale come through.
     */
    @Test
    void run_passesOnlyTheAllowlistedOperatorEnvironment() {
        String path = System.getenv("PATH");
        HostCommandRunner inherited = new HostCommandRunner(Map.of(
                "PATH", path, "HOME", "/home/operator", "LANG", "C.UTF-8", "LC_TIME", "C", "TZ", "UTC",
                "ANTHROPIC_API_KEY", "sk-ant-secret", "AWS_SECRET_ACCESS_KEY", "aws-secret", "GITHUB_TOKEN", "ghp-x"));

        CommandResult result = inherited.run(shell("echo \"[${ANTHROPIC_API_KEY-unset}]"
                + " [${AWS_SECRET_ACCESS_KEY-unset}] [${GITHUB_TOKEN-unset}]\"; echo \"$PATH\";"
                + " echo \"$HOME $LANG $LC_TIME $TZ\"", null, 30));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("[unset] [unset] [unset]\n" + path + "\n/home/operator C.UTF-8 C UTC\n");
    }

    @Test
    void hostEnvironment_passesOnlyTheNamedLocaleCategories() {
        Map<String, String> parent = new java.util.HashMap<>();
        List<String> categories = List.of("LC_ALL", "LC_CTYPE", "LC_MESSAGES", "LC_TIME", "LC_NUMERIC", "LC_COLLATE",
                "LC_MONETARY", "LC_PAPER", "LC_NAME", "LC_ADDRESS", "LC_TELEPHONE", "LC_MEASUREMENT",
                "LC_IDENTIFICATION");
        categories.forEach(name -> parent.put(name, "C"));
        parent.put("LC_TOKEN", "secret");

        assertThat(ChildProcessEnv.operatorEnvironment(parent)).containsOnlyKeys(categories);
    }

    @Test
    void run_setsTheRequestsEnvironment() {
        HostCommandRunner inherited = new HostCommandRunner(Map.of("PATH", System.getenv("PATH")));

        CommandResult result = inherited.run(new CommandRequest("echo \"$GREETING $API_TOKEN\"", null, 30,
                Map.of("GREETING", "hello from the request", "API_TOKEN", "the step's own"), null, null, false));

        assertThat(result.stdout()).isEqualTo("hello from the request the step's own\n");
    }

    /** An environment the OS cannot take fails like a process that cannot start: a result, not an exception. */
    @Test
    void run_requestEnvironmentTheProcessCannotTake_failsWithTheReason() {
        CommandResult badName = runner.run(new CommandRequest("echo hi", null, 30, Map.of("BAD=NAME", "x"), null,
                null, false));
        CommandResult badValue = runner.run(new CommandRequest("echo hi", null, 30, Map.of("GOOD", "a\u0000b"), null,
                null, false));

        assertThat(badName.exitCode()).isEqualTo(1);
        assertThat(badName.stdout()).startsWith("Error: ").contains("BAD=NAME");
        assertThat(badName.timedOut()).isFalse();
        assertThat(badName.didNotRun()).isTrue();
        assertThat(badValue.exitCode()).isEqualTo(1);
        assertThat(badValue.stdout()).startsWith("Error: ");
    }

    // --- refused requests: containers are not the host's job ---

    @Test
    void run_requestWithAnImage_isRefusedAndRunsNothing(@TempDir Path dir) {
        Path marker = dir.resolve("ran");
        CommandRequest inImage = new CommandRequest("touch '" + marker + "'", "/workspace", 30, null, "alpine:3.20",
                List.of(new Mount(dir.toString(), "/workspace", false)), false);

        assertThatThrownBy(() -> runner.run(inImage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The host command runner does not run container images; this command asks for image "
                        + "'alpine:3.20' and needs a container runtime");
        assertThat(marker).doesNotExist();
    }

    @Test
    void run_toolModeRequest_isRefused() {
        CommandRequest tool = CommandRequest.entrypoint("semgrep/semgrep:1.90", List.of("scan", "--sarif"),
                "/workspace", 30, null, null, false);

        assertThatThrownBy(() -> runner.run(tool))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image 'semgrep/semgrep:1.90'");
    }

    @Test
    void run_hostRequestWithMounts_isRefused() {
        CommandRequest mounted = new CommandRequest("ls", null, 30, null, null,
                List.of(new Mount("/srv", "/data", true)), false);

        assertThatThrownBy(() -> runner.run(mounted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The host command runner cannot mount directories; mounts need a container image");
    }

    /**
     * A request that brings its own stdin (an AI call's prompt) never runs on the host: AI requests always go to a
     * container, and the host runner has the command itself on stdin. It is refused, not run without it.
     */
    @Test
    void run_requestWithItsOwnStdin_isRefusedAndRunsNothing(@TempDir Path dir) {
        Path marker = dir.resolve("ran");
        CommandRequest withStdin = new CommandRequest("touch '" + marker + "'", dir.toString(), 30, null, null,
                List.of(), false, null, "the prompt", false);

        assertThatThrownBy(() -> runner.run(withStdin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The host command runner does not take a request's own stdin; such a request (an AI"
                        + " call) needs a container runtime");
        assertThat(marker).doesNotExist();
    }

    // --- the command reaches bash on stdin ---

    /**
     * The command never stands on the process's command line, which every process on the machine can
     * read; a later step's command can carry a secret value from an earlier output.
     */
    @Test
    void run_theCommandText_isNeverOnTheCommandLineOfTheProcess() {
        CommandResult result = run("tr '\\0' ' ' < /proc/$$/cmdline; echo; : tw-command-text-7f3a", 30);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().lines().findFirst().orElseThrow()).startsWith("/bin/bash -c ")
                .doesNotContain("tw-command-text-7f3a");
    }

    // --- commands beyond the argument limit ---

    /** A first line that pushes a script past the argument limit, so it goes in on stdin. */
    private static final String LARGE_COMMENT = "# " + "x".repeat(BashScript.ARGUMENT_LIMIT_BYTES) + "\n";

    /**
     * Linux caps one argument at 128 KiB ({@code MAX_ARG_STRLEN}); a command that large used to fail before bash even
     * started, for example when placeholders insert big block outputs.
     */
    @Test
    void run_commandBeyondTheArgumentLimit_runsLikeBashC() {
        String value = "a".repeat(200_000);

        CommandResult result = run("set -e\nvalue='" + value + "'\necho \"${#value} $0 $#\"\nfalse\necho not-reached",
                30);

        assertThat(result.stdout()).isEqualTo("200000 /bin/bash 0\n");
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void run_largeCommand_keepsTheLineNumbersAndMessagesOfBashC() {
        CommandResult result = run(LARGE_COMMENT + "echo first\nno_such_command_00aa6e92", 30);

        assertThat(result.exitCode()).isEqualTo(127);
        // "line 3" in English; the word depends on the locale bash runs in
        assertThat(result.stdout()).startsWith("first\n/bin/bash: ").contains(" 3: no_such_command_00aa6e92");
    }

    @Test
    void run_largeCommand_seesNoHelperVariableAndAWorkingDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marker.txt"), "x");

        CommandResult result = runner.run(shell(
                LARGE_COMMENT + "echo \"[${" + BashScript.VARIABLE + "-unset}]\"; ls marker.txt", dir.toString(), 30));

        assertThat(result.stdout()).isEqualTo("[unset]\nmarker.txt\n");
    }

    /** The largest command that still fits an argument runs as one, a byte more goes in on stdin. */
    @Test
    void run_commandsAtTheArgumentLimit_runEitherWay() {
        String prefix = "echo ok; : ";
        String largestArgument = prefix + "a".repeat(BashScript.ARGUMENT_LIMIT_BYTES - 1 - prefix.length());
        assertThat(BashScript.viaStdin(largestArgument)).isFalse();
        assertThat(BashScript.viaStdin(largestArgument + "a")).isTrue();

        assertThat(run(largestArgument, 30).stdout()).isEqualTo("ok\n");
        assertThat(run(largestArgument + "a", 30).stdout()).isEqualTo("ok\n");
    }

    // --- stdin ---

    /** A command that reads stdin ({@code cat}, {@code read}) gets its end at once instead of waiting for the timeout. */
    @Test
    void run_commandReadingStdin_endsAtOnceInsteadOfWaitingForTheTimeout() {
        CommandResult result = run("cat; read -r line || echo \"read got EOF\"; echo done", 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("read got EOF\ndone\n");
        assertThat(result.durationMs()).isLessThan(4_000);
    }

    @Test
    void run_largeCommandReadingStdin_getsNeitherItsOwnTextNorAWait() {
        CommandResult result = run(LARGE_COMMENT + "echo \"[$(cat)]\"; read -r line || echo \"read got EOF\"", 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("[]\nread got EOF\n");
        assertThat(result.durationMs()).isLessThan(4_000);
    }

    // --- cancel ---

    /**
     * The process registers with the run whose step runs on this thread, so a cancel kills it: the shell block's own
     * command, the inner shell step of a wrapper block and a condition alike.
     */
    @Test
    void run_onARunThread_endsWhenTheRunIsCancelled(@TempDir Path dir) throws Exception {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        Path started = dir.resolve("started");
        AtomicReference<CommandResult> result = new AtomicReference<>();

        Thread run = Thread.ofVirtual().start(() -> result.set(ProcessTracking.bind(context.processTracker(),
                () -> run("touch '" + started + "'; sleep 30; echo not-reached", 20))));
        awaitFile(started);
        context.cancel();
        run.join(10_000);

        assertThat(run.isAlive()).as("command still running after the cancel").isFalse();
        assertThat(result.get().exitCode()).isNotZero();
        assertThat(result.get().timedOut()).isFalse();
        assertThat(result.get().stdout()).doesNotContain("not-reached");
    }

    /**
     * A tracker tells stop handles apart by identity: the runner reports the end with the very handle it reported the
     * start with, once each, so a command that ended leaves nothing behind for a later cancel to call.
     */
    @Test
    void run_onARunThread_unregistersTheVeryHandleItRegistered() {
        RecordingTracker tracker = new RecordingTracker();

        CommandResult result = ProcessTracking.bind(tracker, () -> run("echo hello", 30));

        assertThat(result.exitCode()).isZero();
        assertThat(tracker.started()).hasSize(1);
        assertThat(tracker.finished()).hasSize(1);
        assertThat(tracker.finished().getFirst()).isSameAs(tracker.started().getFirst());
    }

    @Test
    void run_cancelledAlsoEndsWhatTheCommandStartedInTheBackground(@TempDir Path dir) throws Exception {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        Path pidFile = dir.resolve("child.pid");
        Thread run = Thread.ofVirtual().start(() -> ProcessTracking.bind(context.processTracker(),
                () -> run(BackgroundChild.script(pidFile), 60)));
        long child = BackgroundChild.awaitPid(pidFile);

        context.cancel();
        run.join(10_000);

        assertThat(BackgroundChild.endsWithin(child, Duration.ofSeconds(5)))
                .as("background child %d outlived the cancelled command", child).isTrue();
    }

    private static void awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(file).as("command started").exists();
    }
}
