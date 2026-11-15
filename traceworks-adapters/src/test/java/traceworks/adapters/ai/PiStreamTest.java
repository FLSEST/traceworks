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
 * The stream of {@code pi -p --mode json}, from which Traceworks takes the tokens and the tool calls of a call.
 * The event shapes are those of the package's type definitions
 * ({@code AgentEvent} of {@code pi-agent-core}, {@code AssistantMessage} and {@code Usage} of {@code pi-ai}). The
 * fixtures {@code tools.jsonl}, {@code api-error.jsonl} and {@code no-credential.jsonl} are what the CLI of the image
 * (1.5.2) printed, offline, against a fake Anthropic API on its loopback, with the arguments of {@link PiCli}; the
 * other streams follow their shape.
 */
class PiStreamTest {

    private static final String PROMPT = "Read hello.txt and missing.txt";
    private static final String SESSION = "{\"type\":\"session\",\"version\":3,\"id\":\"s-1\","
            + "\"timestamp\":\"2026-11-05T02:33:29.158Z\",\"cwd\":\"/workspace\"}";
    private static final String USAGE = "{\"input\":10,\"output\":5,\"cacheRead\":0,\"cacheWrite\":0,"
            + "\"totalTokens\":15,\"cost\":{\"input\":0.001,\"output\":0.001,\"cacheRead\":0,\"cacheWrite\":0,"
            + "\"total\":0.002}}";
    private static final String ANSWER = assistant("[{\"type\":\"text\",\"text\":\"done\"}]", "stop", USAGE);
    /** The end of the agent's run, which Pi writes last; {@code messages} holds them all, and is not read. */
    private static final String AGENT_END = "{\"type\":\"agent_end\",\"messages\":[]}";
    private static final String AGENT_START = "{\"type\":\"agent_start\"}";
    private static final ToolCall READ = new ToolCall("read", "{\"path\":\"a.txt\"}", "text of a", false);

    /**
     * What the views rely on when they add the cached input to the input (docs/events.md, "ai.response"): Pi counts
     * {@code input}, {@code cacheRead} and {@code cacheWrite} apart. In what Pi 1.5.2 printed against the fake
     * Anthropic API, each reply's {@code totalTokens} is the sum of the four figures, so {@code input} holds no
     * cached token.
     */
    @Test
    void theFixturesUsage_countsTheCachedInputApartFromTheInput() throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        int withCache = 0;
        for (String line : fixture("tools.jsonl").split("\n")) {
            com.fasterxml.jackson.databind.JsonNode usage = json.readTree(line).path("message").path("usage");
            if (!line.startsWith("{\"type\":\"message_end\"") || usage.path("cacheRead").asInt() == 0) continue;
            withCache++;
            assertThat(usage.path("totalTokens").asInt()).isEqualTo(usage.path("input").asInt()
                    + usage.path("output").asInt() + usage.path("cacheRead").asInt()
                    + usage.path("cacheWrite").asInt());
        }
        assertThat(withCache).as("replies with cached input in the fixture").isPositive();
    }

    private static String fixture(String name) {
        try (InputStream in = PiStreamTest.class.getResourceAsStream("/pi/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AiOutcome outcome(int exitCode, String stdout, String stderr) {
        return PiStream.outcome(new CommandResult(exitCode, stdout, stderr, 900, false), PROMPT);
    }

    private static String assistant(String contentJson, String stopReason, String usageJson) {
        return "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":" + contentJson
                + ",\"api\":\"anthropic-messages\",\"provider\":\"anthropic\",\"model\":\"m-1\""
                + (usageJson == null ? "" : ",\"usage\":" + usageJson) + ",\"stopReason\":\"" + stopReason
                + "\",\"timestamp\":1}}";
    }

    private static String start(String id, String name, String argsJson) {
        return "{\"type\":\"tool_execution_start\",\"toolCallId\":\"" + id + "\",\"toolName\":\"" + name
                + "\",\"args\":" + argsJson + "}";
    }

    private static String end(String id, String name, String resultJson, boolean error) {
        return "{\"type\":\"tool_execution_end\",\"toolCallId\":\"" + id + "\",\"toolName\":\"" + name
                + "\",\"result\":" + resultJson + ",\"isError\":" + error + "}";
    }

    private static String text(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /**
     * A stream with one read call and its result, then {@code middle}, then the answer and the end of the agent, then
     * {@code end}.
     */
    private static String withRead(String middle, String end) {
        return SESSION + "\n" + start("t-1", "read", "{\"path\":\"a.txt\"}") + "\n"
                + end("t-1", "read", text("text of a"), false) + "\n" + middle + ANSWER + "\n" + AGENT_END + "\n" + end;
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
    void parse_jsonMode() {
        AiOutcome outcome = outcome(0, fixture("tools.jsonl"), "");

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.text()).as("the text of the last assistant message").isEqualTo(
                "hello.txt greets; missing.txt is missing.");
        double cost = 0.002685;
        assertThat(outcome.metadata()).as("usage and cost summed over the call's three assistant messages; the model"
                + " with its backend").isEqualTo(new AiResponseMetadata(360, 120, cost + cost + cost,
                "anthropic/claude-sonnet-4-5", false, null, 3, null, List.of("read", "read", "ls", "grep"), 6000, 900,
                null));
        assertThat(outcome.toolCalls()).as("each start with its end by toolCallId, in the order of the starts, though"
                + " the ends came in reverse").containsExactly(
                new ToolCall("read", "{\"path\":\"hello.txt\"}",
                        "hello from the workspace\n<script>alert(\"x\")</script>\n", false),
                new ToolCall("read", "{\"path\":\"missing.txt\"}",
                        "ENOENT: no such file or directory, access '/workspace/missing.txt'", true),
                new ToolCall("ls", "{}", "hello.txt\nnotes/", false),
                new ToolCall("grep", "{\"pattern\":\"hello\"}", "hello.txt:1: hello from the workspace", false));
    }

    /**
     * Pi in JSON mode ends with 0 when the model's API refused the call: the last assistant message says so, with
     * {@code stopReason: "error"} and the API's message.
     */
    @Test
    void parse_anApiError_failsTheCall_thoughPiEndsWith0() {
        AiOutcome outcome = outcome(0, fixture("api-error.jsonl"), "");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.exitCode()).as("passed through; the provider makes it non-zero").isZero();
        assertThat(outcome.text()).isEqualTo("Pi CLI reported an error after 1 turn: 400 {\"type\":\"error\","
                + "\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fake: the request was refused\"}}");
        assertThat(outcome.metadata()).as("no tokens in the stream: estimated from the prompt").isEqualTo(
                new AiResponseMetadata(PROMPT.length() / 4, 0, null, "anthropic/claude-sonnet-4-5", true, null, 1,
                        null, List.of(), null, null, null));
    }

    @Test
    void parse_anAbortedCall_fails_namingWhy() {
        AiOutcome outcome = outcome(143, lines(SESSION, assistant("[]", "aborted", null), AGENT_END),
                "warning: stopped\n");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(143);
        assertThat(outcome.text()).as("Pi's own words for an error without a message").isEqualTo(
                "Pi CLI reported an error (aborted, exit code 143) after 1 turn: Request aborted\nwarning: stopped");
    }

    @Test
    void parse_anAnswerWithANonZeroExitCode_failsTheCall() {
        AiOutcome outcome = outcome(2, lines(SESSION, ANSWER, AGENT_END), "");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(2);
        assertThat(outcome.text()).isEqualTo("Pi CLI failed (exit code 2) after 1 turn: done");
    }

    /**
     * Pi writes {@code agent_end} when its agent's run is over. A stream whose last run did not
     * end (none came, a retry started one after it, or the last one announced a retry with {@code willRetry}) was
     * cut, so the call fails, also with exit code 0.
     */
    @Test
    void parse_aStreamWithoutTheEndOfTheAgent_failsTheCall() {
        AiOutcome none = outcome(0, lines(SESSION, AGENT_START, ANSWER), "");
        AiOutcome retried = outcome(0, lines(SESSION, AGENT_START, ANSWER, AGENT_END,
                "{\"type\":\"auto_retry_start\",\"attempt\":1}", AGENT_START), "");
        AiOutcome willRetry = outcome(0, lines(SESSION, AGENT_START, ANSWER,
                "{\"type\":\"agent_end\",\"messages\":[],\"willRetry\":true}"), "");
        AiOutcome ended = outcome(0, lines(SESSION, AGENT_START, ANSWER, AGENT_END), "");
        AiOutcome settled = outcome(0, lines(SESSION, AGENT_START, ANSWER,
                "{\"type\":\"agent_end\",\"messages\":[],\"willRetry\":false}",
                "{\"type\":\"agent_settled\",\"aborted\":false}"), "");

        assertThat(none.failed()).isTrue();
        assertThat(none.text()).isEqualTo("Pi CLI failed (no agent_end) after 1 turn: done");
        assertThat(none.exitCode()).isZero();
        assertThat(retried.failed()).isTrue();
        assertThat(retried.text()).isEqualTo("Pi CLI failed (no agent_end) after 1 turn: done");
        assertThat(willRetry.failed()).isTrue();
        assertThat(willRetry.text()).isEqualTo("Pi CLI failed (no agent_end) after 1 turn: done");
        assertThat(ended.failed()).isFalse();
        assertThat(settled.failed()).isFalse();
    }

    /** A last reply that asks for a tool was never answered, so the call has no answer. */
    @Test
    void parse_aLastReplyThatStoppedForATool_failsTheCall() {
        String asks = assistant("[{\"type\":\"text\",\"text\":\"Looking.\"},{\"type\":\"toolCall\","
                + "\"id\":\"t-1\",\"name\":\"read\",\"arguments\":{}}]", "toolUse", USAGE);

        AiOutcome outcome = outcome(0, lines(SESSION, asks, AGENT_END), "");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.text()).isEqualTo("Pi CLI failed (stop reason toolUse) after 1 turn: Looking.");
        assertThat(outcome.metadata().inputTokens()).isEqualTo(10);
    }

    /** Without a credential Pi writes only its session line, and why on stderr. */
    @Test
    void parse_noAssistantMessage_failsWithTheEndsOfStdoutAndStderr() {
        String stderr = "No API key found for anthropic.\n\nUse /login to log into a provider via OAuth or API key.\n";
        AiOutcome outcome = outcome(1, fixture("no-credential.jsonl"), stderr);

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.text()).isEqualTo("Pi CLI wrote no assistant message (exit code 1):\n"
                + fixture("no-credential.jsonl").strip() + "\n" + stderr.strip());
        assertThat(outcome.metadata()).as("nothing answered, so nothing to count").isNull();
        assertThat(outcome.toolCalls()).isEmpty();
    }

    @Test
    void parse_emptyStream_failsWithoutToolCalls() {
        AiOutcome outcome = outcome(0, "", "");
        AiOutcome onlyStderr = outcome(1, "", "Error: Unknown provider \"nosuch\".\n");

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.text()).isEqualTo("Pi CLI wrote no assistant message (exit code 0)");
        assertThat(outcome.toolCalls()).isEmpty();
        assertThat(outcome.metadata()).isNull();
        assertThat(onlyStderr.text()).isEqualTo("Pi CLI wrote no assistant message (exit code 1):\nError: Unknown"
                + " provider \"nosuch\".");
    }

    /** The answer is the last assistant message's text blocks, one per line; thinking and tool calls are not. */
    @Test
    void parse_theAnswer_isTheTextOfTheLastAssistantMessage() {
        String first = assistant("[{\"type\":\"text\",\"text\":\"Looking.\"},{\"type\":\"toolCall\",\"id\":\"t-1\","
                + "\"name\":\"read\",\"arguments\":{\"path\":\"a.txt\"}}]", "toolUse", USAGE);
        String last = assistant("[{\"type\":\"thinking\",\"thinking\":\"hmm\"},{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"toolCall\",\"id\":\"t-2\",\"name\":\"ls\",\"arguments\":{}},{\"type\":\"text\","
                + "\"text\":\"b\"}]", "stop", USAGE);
        String user = "{\"type\":\"message_end\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"later\"}],\"timestamp\":2}}";

        AiOutcome outcome = outcome(0, lines(SESSION, first, last, user, AGENT_END), "");

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.text()).isEqualTo("a\nb");
        assertThat(outcome.metadata().numTurns()).isEqualTo(2);
        assertThat(outcome.metadata().inputTokens()).isEqualTo(20);
        assertThat(outcome.metadata().costUsd()).isEqualTo(0.004);
    }

    /** Tokens come from the stream; a stream without them gives an estimate (chars / 4). */
    @Test
    void parse_withoutTokensInTheStream_theyAreEstimated() {
        String zero = "{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":0}";
        String answer = "x".repeat(40);
        for (String usage : new String[] {null, zero, "\"none\""}) {
            AiOutcome outcome = outcome(0, lines(SESSION, assistant("[{\"type\":\"text\",\"text\":\"" + answer
                    + "\"}]", "stop", usage), AGENT_END), "");

            assertThat(outcome.failed()).isFalse();
            assertThat(outcome.metadata()).as(String.valueOf(usage)).isEqualTo(new AiResponseMetadata(
                    PROMPT.length() / 4, 10, null, "anthropic/m-1", true, null, 1, null, List.of(), null, null, null));
        }
    }

    /** The model that answered: the backend's own name for it when it reports one ({@code responseModel}). */
    @Test
    void parse_theModel_isTheBackendWithTheModelThatAnswered() {
        String routed = ANSWER.replace("\"provider\":\"anthropic\",\"model\":\"m-1\"",
                "\"provider\":\"openrouter\",\"model\":\"auto\",\"responseModel\":\"meta-llama/llama-3.3\"");
        String unnamed = ANSWER.replace("\"provider\":\"anthropic\",", "");

        assertThat(outcome(0, lines(SESSION, routed, AGENT_END), "").metadata().model())
                .isEqualTo("openrouter/meta-llama/llama-3.3");
        assertThat(outcome(0, lines(SESSION, unnamed, AGENT_END), "").metadata().model()).isEqualTo("m-1");
    }

    @Test
    void parse_toolResultBlocks_areJoined_andMissingPartsAreNone() {
        AiOutcome outcome = outcome(0, lines(SESSION, start("t-1", "read", "{\"path\":\"p.png\"}"),
                end("t-1", "read", "{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"image\","
                        + "\"data\":\"iVBO\",\"mimeType\":\"image/png\"},{\"type\":\"text\",\"text\":\"b\"}]}", false),
                start("t-2", "bash", "{\"command\":\"ls\"}"), end("t-2", "bash", "\"plain\"", true),
                "{\"type\":\"tool_execution_start\",\"toolCallId\":\"t-3\"}", end("t-3", "x", "{}", false),
                start("t-4", "ls", "{}"), ANSWER, AGENT_END), "");

        assertThat(outcome.toolCalls()).containsExactly(
                new ToolCall("read", "{\"path\":\"p.png\"}",
                        "a\n{\"type\":\"image\",\"data\":\"iVBO\",\"mimeType\":\"image/png\"}\nb", false),
                new ToolCall("bash", "{\"command\":\"ls\"}", "plain", true),
                new ToolCall("unknown", null, "", false),
                new ToolCall("ls", "{}", null, false));
    }

    /** The one join of {@link CliOutput#toolResult}: content that is no list is its JSON. */
    @Test
    void parse_aResultWhoseContentIsNoList_isItsJson() {
        AiOutcome outcome = outcome(0, lines(SESSION, start("t-1", "read", "{}"),
                end("t-1", "read", "{\"content\":{\"k\":1}}", false), ANSWER, AGENT_END), "");

        assertThat(outcome.toolCalls()).containsExactly(new ToolCall("read", "{}", "{\"k\":1}", false));
    }

    @Test
    void parse_anEndWithoutItsStart_isIgnored_andASecondEndToo() {
        AiOutcome outcome = outcome(0, lines(SESSION, end("t-9", "bash", text("orphan"), false),
                start("t-1", "read", "{\"path\":\"a.txt\"}"), end("t-1", "read", text("text of a"), false),
                end("t-1", "read", text("twice"), true), ANSWER, AGENT_END), "");

        assertThat(outcome.toolCalls()).containsExactly(READ);
        assertThat(outcome.metadata().toolNames()).containsExactly("read");
    }

    /**
     * The stream filter drops Pi's partial messages in the container; should one arrive anyway, it is skipped like
     * any event the parser does not read.
     */
    @Test
    void parse_partialMessages_areSkipped() {
        String update = "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\","
                + "\"delta\":\"do\"},\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"do\"}],\"stopReason\":\"error\"}}";
        String toolUpdate = "{\"type\":\"tool_execution_update\",\"toolCallId\":\"t-1\",\"toolName\":\"read\","
                + "\"args\":{},\"partialResult\":{\"content\":[]}}";

        AiOutcome outcome = outcome(0, withRead(lines(update, toolUpdate), ""), "");

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.text()).isEqualTo("done");
        assertThat(outcome.toolCalls()).containsExactly(READ);
    }

    @Test
    void parse_aTenMegabyteArgument_andNonUtf8Bytes_doNotThrow() {
        String content = "y".repeat(10 * 1024 * 1024);
        String write = "{\"path\":\"big.txt\",\"content\":\"" + content + "\"}";
        String stream = decoded(utf8(lines(SESSION, start("t-w", "write", write),
                        end("t-w", "write", text("written"), false))),
                utf8("{\"type\":\"tool_execution_start\",\"toolCallId\":\"t-b\",\"toolName\":\"bash\","
                        + "\"args\":{\"command\":\"echo "),
                new byte[] {(byte) 0xc3, 0x28, (byte) 0xff, (byte) 0xfe},
                utf8("\"}}\n"),
                new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01, '\n'},
                utf8(end("t-b", "bash", text("bytes \u00e9"), false) + "\n" + ANSWER + "\n" + AGENT_END + "\n"));

        AiOutcome outcome = outcome(0, stream, "");

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.text()).isEqualTo("done");
        assertThat(outcome.toolCalls()).hasSize(2);
        assertThat(outcome.toolCalls().getFirst().argumentsJson()).as("the whole argument, for its blob")
                .isEqualTo(write);
        assertThat(outcome.toolCalls().get(1).argumentsJson()).isEqualTo("{\"command\":\"echo \ufffd(\ufffd\ufffd\"}");
        assertThat(outcome.toolCalls().get(1).result()).isEqualTo("bytes \u00e9");
    }

    @Test
    void parse_aTruncatedLastLine_isRepaired_orIgnored() {
        String cutStart = lines(SESSION, start("t-1", "read", "{\"path\":\"a.txt\"}"),
                end("t-1", "read", text("text of a"), false))
                + "{\"type\":\"tool_execution_start\",\"toolCallId\":\"t-2\",\"toolName\":\"bash\","
                + "\"args\":{\"command\":\"ls -l";
        String cutAnswer = lines(SESSION) + ANSWER.substring(0, ANSWER.indexOf("done") + 2);

        AiOutcome toolCallRepaired = outcome(124, cutStart, "");
        AiOutcome answerCut = outcome(137, cutAnswer, "");

        assertThat(toolCallRepaired.toolCalls()).containsExactly(READ,
                new ToolCall("bash", "{\"command\":\"ls -l\"}", null, false));
        assertThat(toolCallRepaired.failed()).isTrue();
        assertThat(answerCut.failed()).as("its exit code says it was cut").isTrue();
        assertThat(answerCut.text()).isEqualTo("Pi CLI failed (no agent_end, exit code 137) after 1 turn: do");
    }

    static Stream<Arguments> brokenStreams() {
        return Stream.of(
                Arguments.of("a truncated last line", withRead("",
                        "{\"type\":\"message_end\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"te"), READ),
                Arguments.of("a tool result with <script>", lines(SESSION, start("t-1", "read", "{\"path\":\"a.txt\"}"),
                        end("t-1", "read", text("<script>alert(document.cookie)</script>"), false), ANSWER,
                        AGENT_END),
                        new ToolCall("read", "{\"path\":\"a.txt\"}", "<script>alert(document.cookie)</script>",
                                false)),
                Arguments.of("bytes that are not UTF-8", withRead(decoded(
                        utf8("{\"type\":\"turn_start\",\"v\":\""), new byte[] {(byte) 0xc3, 0x28},
                        utf8("\"}\n"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0x80, '\n'}), ""), READ),
                Arguments.of("unknown event types", withRead(lines(
                        "{\"type\":\"compaction_start\",\"reason\":\"overflow\"}",
                        "{\"type\":\"auto_retry_start\",\"attempt\":1,\"maxAttempts\":3}",
                        "{\"type\":\"a_later_event\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\"}}",
                        "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":\"plain\","
                                + "\"stopReason\":\"error\"}}".replace("assistant", "custom"),
                        "{\"type\":\"message_end\",\"message\":42}",
                        "{\"type\":\"tool_execution_start\",\"toolCallId\":7}",
                        "{\"type\":\"tool_execution_end\",\"toolCallId\":\"t-1\"}",
                        "{\"type\":42}", "{}", "[1,2]"), ""),
                        READ),
                Arguments.of("lines that are not JSON", withRead(lines("this is not JSON {", "]]]",
                        "{\"type\":\"message_end\",\"cut\":", "\u0000\u0001", "Warning: (startup) EROFS"), ""), READ));
    }

    /** Review focus 5: no stream makes the parser throw, or lose a tool call it reported. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenStreams")
    void parse_brokenLines_doNotThrow(String what, String stream, ToolCall read) {
        AiOutcome outcome = outcome(0, stream, "");

        assertThat(outcome.toolCalls()).as(what).startsWith(read);
        assertThat(outcome.failed()).as(what).isFalse();
        assertThat(outcome.text()).as(what).isEqualTo("done");
    }

    /**
     * Hostile figures do not overflow: each sum stops at the largest int, a negative figure counts as none, and a
     * cost that is no number, or adds up to none, is none.
     */
    @Test
    void parse_hostileUsage_isBounded() {
        String huge = "{\"input\":1e300,\"output\":2147483647,\"cacheRead\":-5,\"cacheWrite\":\"many\","
                + "\"cost\":{\"total\":\"free\"}}";
        String wrapping = "{\"input\":18446744073709551621,\"output\":1,\"cost\":{\"total\":1e308}}";

        AiResponseMetadata metadata = outcome(0, lines(SESSION, assistant("[]", "stop", huge),
                assistant("[]", "stop", huge)), "").metadata();
        AiResponseMetadata wrapped = outcome(0, lines(SESSION, assistant("[]", "stop", wrapping),
                assistant("[]", "stop", wrapping)), "").metadata();

        assertThat(metadata.inputTokens()).isEqualTo(Integer.MAX_VALUE);
        assertThat(metadata.outputTokens()).isEqualTo(Integer.MAX_VALUE);
        assertThat(metadata.cacheReadInputTokens()).isZero();
        assertThat(metadata.cacheCreationInputTokens()).isNull();
        assertThat(metadata.costUsd()).isNull();
        assertThat(metadata.estimated()).isFalse();
        assertThat(wrapped.inputTokens()).as("2^64 + 5 does not wrap to 5").isEqualTo(Integer.MAX_VALUE);
        assertThat(wrapped.costUsd()).as("1e308 twice is no number").isNull();
    }
}
