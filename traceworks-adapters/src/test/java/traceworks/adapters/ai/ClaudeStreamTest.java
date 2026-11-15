package traceworks.adapters.ai;

import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stream of {@code claude -p --output-format stream-json --verbose}. The fixtures
 * {@code tools.jsonl}, {@code not-logged-in.jsonl} and {@code max-turns.jsonl} are what the CLI of the image
 * (2.1.288) printed, offline, against a fake Messages API on its loopback and without credentials; the other streams
 * follow their shape.
 */
class ClaudeStreamTest {

    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"/workspace\","
            + "\"session_id\":\"s-1\",\"tools\":[\"Read\"],\"mcp_servers\":[],\"model\":\"claude-opus-5-5\"}";
    private static final String RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
            + "\"num_turns\":2,\"result\":\"done\",\"session_id\":\"s-1\",\"total_cost_usd\":0.002,"
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    private static final ToolCall READ = new ToolCall("Read", "{\"file_path\":\"a.txt\"}", "text of a", false);

    private static String fixture(String name) {
        try (InputStream in = ClaudeStreamTest.class.getResourceAsStream("/claude/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AiOutcome outcome(int exitCode, String stdout, String stderr) {
        return ClaudeStream.outcome(new CommandResult(exitCode, stdout, stderr, 900, false));
    }

    private static String toolUse(String id, String name, String inputJson) {
        return "{\"type\":\"assistant\",\"message\":{\"model\":\"claude-opus-5-5\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":"
                + inputJson + "}]},\"session_id\":\"s-1\"}";
    }

    private static String toolResult(String id, String contentJson, boolean error) {
        return "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"" + id + "\","
                + "\"type\":\"tool_result\",\"content\":" + contentJson + (error ? ",\"is_error\":true" : "")
                + "}]},\"session_id\":\"s-1\"}";
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** A stream with one Read call and its result, then {@code middle}, then the result line, then {@code end}. */
    private static String withRead(String middle, String end) {
        return INIT + "\n" + toolUse("toolu_1", "Read", "{\"file_path\":\"a.txt\"}") + "\n"
                + toolResult("toolu_1", "\"text of a\"", false) + "\n" + middle + RESULT + "\n" + end;
    }

    private static String decoded(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parse_streamJson() {
        AiOutcome outcome = outcome(0, fixture("tools.jsonl"), "");

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.text()).isEqualTo("hello.txt greets; missing.txt is missing.");
        assertThat(outcome.metadata()).as("the model of the last assistant message; usage from the result line")
                .isEqualTo(new AiResponseMetadata(360, 120, 0.00954, "claude-fake-model-2", false, 47L, 4,
                        "c177691f-fcac-4c70-aaae-bcc6d27bbdd9", List.of("Read", "Read", "Grep"), 6000, 900, 0, 0, 0));
        assertThat(outcome.toolCalls()).as("each call with its result by tool_use_id, in the order of the calls,"
                + " though the second result came first").containsExactly(
                new ToolCall("Read", "{\"file_path\":\"/workspace/hello.txt\"}",
                        "1\thello from the workspace\n2\t<script>alert(\"x\")</script>\n3\t", false),
                new ToolCall("Read", "{\"file_path\":\"/workspace/missing.txt\"}",
                        "File does not exist. Note: your current working directory is /workspace.", true),
                new ToolCall("Grep", "{\"pattern\":\"hello\",\"path\":\"/workspace\"}", "Found 1 file\nhello.txt",
                        false));
    }

    /** An {@code is_error} result fails the call, whatever the exit code. */
    @Test
    void parse_isErrorResult_failsTheCall() {
        AiOutcome notLoggedIn = outcome(1, fixture("not-logged-in.jsonl"), "");
        AiOutcome withExitZero = outcome(0, lines(INIT, "{\"type\":\"result\",\"subtype\":\"success\","
                + "\"is_error\":true,\"result\":\"API Error: 529 Overloaded\",\"num_turns\":1}"), "");

        assertThat(notLoggedIn.failed()).isTrue();
        assertThat(notLoggedIn.exitCode()).isEqualTo(1);
        assertThat(notLoggedIn.text())
                .isEqualTo("Claude CLI reported an error (exit code 1) after 1 turn: Not logged in · Please run"
                        + " /login");
        assertThat(notLoggedIn.metadata()).as("the CLI's own message names no model")
                .isEqualTo(new AiResponseMetadata(0, 0, 0.0, null, true, 0L, 1,
                        "f140b92e-12d1-4735-b5d2-9c0c91bec650", List.of(), 0, 0, 0, 0, 0));
        assertThat(withExitZero.failed()).isTrue();
        assertThat(withExitZero.exitCode()).as("passed through; the provider makes it non-zero").isZero();
        assertThat(withExitZero.text())
                .isEqualTo("Claude CLI reported an error after 1 turn: API Error: 529 Overloaded");
    }

    @Test
    void parse_maxTurns_failsTheCall_namingTheCliErrors_andKeepsItsToolCalls() {
        AiOutcome outcome = outcome(1, fixture("max-turns.jsonl"), "");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.text()).isEqualTo("Claude CLI reported an error (error_max_turns, exit code 1) after 2"
                + " turns: Reached maximum number of turns (1)");
        assertThat(outcome.toolCalls()).containsExactly(new ToolCall("Read",
                "{\"file_path\":\"/workspace/hello.txt\"}", "1\thello from the workspace\n2\t", false));
        assertThat(outcome.metadata().model()).isEqualTo("claude-fake-model-1");
        assertThat(outcome.metadata().inputTokens()).isEqualTo(21);
    }

    @Test
    void parse_aSuccessResultWithANonZeroExitCode_failsTheCall() {
        AiOutcome outcome = outcome(2, lines(INIT, "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"num_turns\":1,\"result\":\"done\"}"), "");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(2);
        assertThat(outcome.text()).isEqualTo("Claude CLI failed (exit code 2) after 1 turn: done");
    }

    @Test
    void parse_aSuccessResultWithoutText_isAnEmptyAnswer() {
        AiOutcome outcome = outcome(0, lines(INIT, "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"num_turns\":1}"), "");

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.text()).isEmpty();
    }

    /** stdout carries the stream, stderr the CLI's own errors: a failed call names them after its message. */
    @Test
    void parse_aFailedCall_namesTheCliStderr_afterItsMessage() {
        AiOutcome outcome = outcome(1, fixture("not-logged-in.jsonl"), "warning: no config\n");
        AiOutcome answered = outcome(0, fixture("tools.jsonl"), "warning: no config\n");

        assertThat(outcome.text()).isEqualTo("Claude CLI reported an error (exit code 1) after 1 turn: Not logged in ·"
                + " Please run /login\nwarning: no config");
        assertThat(answered.text()).as("an answer is the answer only").isEqualTo(
                "hello.txt greets; missing.txt is missing.");
    }

    @Test
    void parse_truncatedLastLine_isRepaired_orIgnored() {
        String stream = fixture("tools.jsonl").stripTrailing();
        String afterTheType = stream.substring(0, stream.indexOf("d79f556e-c263") + 6);
        String beforeTheType = stream.substring(0, stream.indexOf("\"ttft_ms\":64") + 11);
        String cutToolCall = lines(INIT, toolUse("toolu_1", "Read", "{\"file_path\":\"a.txt\"}"),
                toolResult("toolu_1", "\"text of a\"", false))
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_2\","
                + "\"name\":\"Bash\",\"input\":{\"command\":\"ls -l";

        AiOutcome repaired = outcome(0, afterTheType, "");
        AiOutcome ignored = outcome(0, beforeTheType, "");
        AiOutcome toolCallRepaired = outcome(124, cutToolCall, "");

        assertThat(repaired.failed()).isFalse();
        assertThat(repaired.text()).isEqualTo("hello.txt greets; missing.txt is missing.");
        assertThat(repaired.toolCalls()).hasSize(3);
        assertThat(ignored.failed()).as("its result line lost its type, so the stream has none").isTrue();
        assertThat(ignored.text()).startsWith("Claude CLI wrote no result line (exit code 0):\n...")
                .endsWith("\"ttft_ms\":6");
        assertThat(ignored.toolCalls()).hasSize(3);
        assertThat(toolCallRepaired.toolCalls()).containsExactly(READ,
                new ToolCall("Bash", "{\"command\":\"ls -l\"}", null, false));
        assertThat(toolCallRepaired.failed()).isTrue();
    }

    @Test
    void parse_noResultLine_failsWithTheRawTail() {
        String crash = "x".repeat(10_000)
                + "\nTypeError: Cannot read properties of undefined\n    at main (cli.js:1)\n";
        AiOutcome outcome = outcome(1, crash, "fatal: the CLI crashed\n");
        AiOutcome plain = outcome(0, "Invalid API key · Please run /login\n", "");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.text()).isEqualTo("Claude CLI wrote no result line (exit code 1):\n...\nTypeError: Cannot"
                + " read properties of undefined\n    at main (cli.js:1)\nfatal: the CLI crashed");
        assertThat(outcome.metadata()).isNull();
        assertThat(plain.failed()).as("plain text is no answer, also with exit code 0").isTrue();
        assertThat(plain.text()).isEqualTo("Claude CLI wrote no result line (exit code 0):\nInvalid API key · Please"
                + " run /login");
    }

    @Test
    void parse_emptyStream_failsWithoutToolCalls() {
        AiOutcome outcome = outcome(0, "", "");
        AiOutcome onlyStderr = outcome(1, "", "Error: unknown option '--frobnicate'\n");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.text()).isEqualTo("Claude CLI wrote no result line (exit code 0)");
        assertThat(outcome.toolCalls()).isEmpty();
        assertThat(outcome.metadata()).isNull();
        assertThat(onlyStderr.text()).isEqualTo("Claude CLI wrote no result line (exit code 1):\nError: unknown option"
                + " '--frobnicate'");
    }

    @Test
    void parse_hugeToolInput_andNonUtf8Bytes_doNotThrow() {
        String content = "y".repeat(10 * 1024 * 1024);
        String write = "{\"file_path\":\"big.txt\",\"content\":\"" + content + "\"}";
        String stream = decoded(utf8(lines(INIT, toolUse("toolu_w", "Write", write),
                        toolResult("toolu_w", "\"written\"", false))),
                utf8("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_b\","
                        + "\"name\":\"Bash\",\"input\":{\"command\":\"echo "),
                new byte[] {(byte) 0xc3, 0x28, (byte) 0xff, (byte) 0xfe},
                utf8("\"}}]}}\n"),
                new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01, '\n'},
                utf8(toolResult("toolu_b", "\"bytes \u00e9\"", false) + "\n" + RESULT + "\n"));

        AiOutcome outcome = outcome(0, stream, "");

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.text()).isEqualTo("done");
        assertThat(outcome.toolCalls()).hasSize(2);
        assertThat(outcome.toolCalls().getFirst().argumentsJson()).as("the whole argument, for its blob")
                .isEqualTo(write);
        assertThat(outcome.toolCalls().get(1).argumentsJson()).isEqualTo("{\"command\":\"echo \ufffd(\ufffd\ufffd\"}");
        assertThat(outcome.toolCalls().get(1).result()).isEqualTo("bytes \u00e9");
    }

    static Stream<Arguments> hostileStreams() {
        return Stream.of(
                Arguments.of("a truncated last line", withRead("",
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"te"), READ),
                Arguments.of("a tool result with <script>", INIT + "\n"
                        + toolUse("toolu_1", "Read", "{\"file_path\":\"a.txt\"}") + "\n"
                        + toolResult("toolu_1", "\"<script>alert(document.cookie)</script>\"", false) + "\n" + RESULT,
                        new ToolCall("Read", "{\"file_path\":\"a.txt\"}", "<script>alert(document.cookie)</script>",
                                false)),
                Arguments.of("bytes that are not UTF-8", withRead(decoded(
                        utf8("{\"type\":\"system\",\"subtype\":\"x\",\"v\":\""), new byte[] {(byte) 0xc3, 0x28},
                        utf8("\"}\n"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0x80, '\n'}), ""), READ),
                Arguments.of("unknown event types", withRead(lines(
                        "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\"}}",
                        "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\"}}",
                        "{\"type\":\"system\",\"subtype\":\"hook_started\",\"hook_name\":\"SessionStart\"}",
                        "{\"type\":\"assistant\",\"message\":{\"content\":\"plain\"}}",
                        "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"image\"},42,null]}}",
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\"}]}}",
                        "{\"type\":42}", "{}"), ""),
                        READ),
                Arguments.of("lines that are not JSON", withRead(lines("this is not JSON {", "]]]",
                        "{\"type\":\"result\",\"cut\":", "\u0000\u0001"), ""), READ));
    }

    /** Review focus 5: no stream makes the parser throw, or lose a tool call it reported. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileStreams")
    void parse_aHostileStream_neitherThrows_norLosesTheToolCall(String what, String stream, ToolCall read) {
        AiOutcome outcome = outcome(0, stream, "");

        assertThat(outcome.toolCalls()).as(what).startsWith(read);
        assertThat(outcome.failed()).as(what).isFalse();
        assertThat(outcome.text()).as(what).isEqualTo("done");
    }

    @Test
    void parse_toolResultBlocks_areJoined() {
        AiOutcome outcome = outcome(0, lines(INIT, toolUse("toolu_1", "Bash", "{\"command\":\"ls\"}"),
                toolResult("toolu_1", "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"},"
                        + "{\"type\":\"image\",\"source\":{\"type\":\"base64\"}}]", true), RESULT), "");

        assertThat(outcome.toolCalls()).containsExactly(new ToolCall("Bash", "{\"command\":\"ls\"}",
                "a\nb\n{\"type\":\"image\",\"source\":{\"type\":\"base64\"}}", true));
    }

    @Test
    void parse_aCallWithoutResult_hasNone_andAResultWithoutCall_isIgnored() {
        AiOutcome outcome = outcome(0, lines(INIT, toolUse("toolu_1", "Read", "{\"file_path\":\"a.txt\"}"),
                toolResult("toolu_9", "\"orphan\"", false), toolResult("toolu_1", "\"text of a\"", false),
                toolResult("toolu_1", "\"twice\"", false), toolUse("toolu_2", "Grep", "{}"), RESULT), "");

        assertThat(outcome.toolCalls()).containsExactly(READ, new ToolCall("Grep", "{}", null, false));
        assertThat(outcome.metadata().toolNames()).containsExactly("Read", "Grep");
    }

    /** Without an assistant model, the model is the one model of the result line. */
    @Test
    void parse_withoutAnAssistantModel_theOneModelOfTheResult() {
        String result = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"modelUsage\":{\"claude-sonnet-4-6\":{}}}";
        String two = result.replace("{\"claude-sonnet-4-6\":{}}", "{\"a\":{},\"b\":{}}");

        assertThat(outcome(0, lines(INIT, result), "").metadata().model()).isEqualTo("claude-sonnet-4-6");
        assertThat(outcome(0, lines(INIT, two), "").metadata().model()).isNull();
    }

    /** The result line's count of server-side web requests, when the CLI reports one; nothing made up without. */
    @Test
    void theWebRequestsOfTheUsage_areMetadata_whenReported() {
        AiOutcome with = outcome(0, lines(INIT, "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"result\":\"found\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"server_tool_use\":"
                + "{\"web_search_requests\":2,\"web_fetch_requests\":1}}}"), "");
        AiOutcome without = outcome(0, lines(INIT, "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"result\":\"found\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}"), "");

        assertThat(with.metadata().webSearchRequests()).isEqualTo(2);
        assertThat(with.metadata().webFetchRequests()).isEqualTo(1);
        assertThat(without.metadata().webSearchRequests()).isNull();
        assertThat(without.metadata().webFetchRequests()).isNull();
    }
}
