package traceworks.adapters.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.port.AiCli.AiOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every AI CLI adapter reads its CLI's output with: the JSON lines of a stream, the end of a text, and the pieces
 * the streams share: the tool calls paired with their results, a field's text, a tool result's
 * blocks, and the outcome of a stream that holds none.
 */
class CliOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> types(List<JsonNode> lines) {
        return lines.stream().map(node -> node.path("type").asText()).toList();
    }

    @Test
    void jsonLines_areTheObjectsOfTheStream_otherLinesAreSkipped() {
        String stream = "{\"type\":\"a\"}\r\nnot json\n\n42\n\"text\"\n[1,2]\n{\"type\":\"b\"}\n";

        assertThat(types(CliOutput.jsonLines(stream))).containsExactly("a", "b");
    }

    @Test
    void jsonLines_ofNothing_areNone() {
        assertThat(CliOutput.jsonLines(null)).isEmpty();
        assertThat(CliOutput.jsonLines("")).isEmpty();
        assertThat(CliOutput.jsonLines(" \n\n")).isEmpty();
    }

    /** A CLI that is cut off leaves its last line unfinished: that one is closed if it can be, no other is. */
    @Test
    void jsonLines_repairOnlyTheLastLine() {
        String stream = "{\"type\":\"a\",\"cut\":\"in the mid\n{\"type\":\"b\"}\n{\"type\":\"c\",\"list\":[1,2,\n\n";

        List<JsonNode> lines = CliOutput.jsonLines(stream);

        assertThat(types(lines)).containsExactly("b", "c");
        assertThat(lines.get(1).path("list")).hasSize(2);
    }

    @Test
    void jsonLines_aLastLineThatCannotBeClosed_isSkipped() {
        assertThat(CliOutput.jsonLines("{\"type\":\"a\"}\n{\"type\":\"b\",\"key\"")).hasSize(1);
        assertThat(CliOutput.jsonLines("{\"type\":\"a\"}\nplain text, cut")).hasSize(1);
    }

    @Test
    void jsonLines_keepAStringBeyondJacksonsDefaultLimit() {
        String big = "x".repeat(25_000_000);

        List<JsonNode> lines = CliOutput.jsonLines("{\"type\":\"a\",\"v\":\"" + big + "\"}");

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().path("v").asText()).hasSize(25_000_000);
    }

    @Test
    void tail_keepsAShortText() {
        assertThat(CliOutput.tail("short\ntext", 100)).isEqualTo("short\ntext");
        assertThat(CliOutput.tail(null, 100)).isEmpty();
    }

    /**
     * A cut never splits a secret: the end it keeps starts at a character no secret value holds ({@code "}, {@code \}
     * or a control character, such as a line break; {@code Redactor.problem}), so the record can still redact it.
     */
    @Test
    void tail_startsAtACharacterNoSecretHolds() {
        String secret = "sk-ant-api03-Abc_def-123";
        String text = "first line\nAPI key " + secret + " rejected\nlast";

        String tail = CliOutput.tail(text, 30);

        assertThat(tail).isEqualTo("...\nlast");
        assertThat(CliOutput.tail("{\"key\":\"" + secret + "\",\"x\":1}", 20)).isEqualTo("...\",\"x\":1}");
    }

    /** The edges are stripped before the limit counts, as for a short text: only the end that is text is kept. */
    @Test
    void tail_ofALongOutput_stripsItsEdges_beforeItCuts() {
        String spaces = " ".repeat(1_000_000);

        assertThat(CliOutput.tail(spaces + "abc\nend" + "\n \t".repeat(1_000_000), 5)).isEqualTo("...\nend");
        assertThat(CliOutput.tail(spaces + "short" + spaces, 5)).isEqualTo("short");
        assertThat(CliOutput.tail(spaces, 5)).isEmpty();
    }

    @Test
    void tail_withoutSuchACharacter_keepsNothingOfTheText() {
        assertThat(CliOutput.tail("first\n" + "x".repeat(50), 20)).isEqualTo("...");
    }

    /** Each end goes to the start of its id, wherever it came; an end without a start, or a second one, is dropped. */
    @Test
    void toolUses_pairEachEndWithItsStart_inTheOrderOfTheStarts() {
        CliOutput.ToolUses uses = new CliOutput.ToolUses();
        uses.started("a", "read", "{\"path\":\"a\"}");
        uses.started(null, "unknown", null);
        uses.started("c", "grep", "{}");
        uses.ended("c", "found", false);
        uses.ended("x", "orphan", false);
        uses.ended("a", "text of a", true);
        uses.ended("a", "twice", false);

        assertThat(uses.calls()).containsExactly(new ToolCall("read", "{\"path\":\"a\"}", "text of a", true),
                new ToolCall("unknown", null, null, false), new ToolCall("grep", "{}", "found", false));
    }

    /** A call reported with its result takes its place among the others, in the order of the stream. */
    @Test
    void toolUses_takeACallWithItsResult_inItsPlace() {
        CliOutput.ToolUses uses = new CliOutput.ToolUses();
        uses.started("a", "read", "{}");
        uses.answered("ls", null, "a.txt", false);
        uses.ended("a", "text of a", false);

        assertThat(uses.calls()).containsExactly(new ToolCall("read", "{}", "text of a", false),
                new ToolCall("ls", null, "a.txt", false));
    }

    /**
     * A token figure summed over a stream's replies: a number counts, bounded by the largest int, and a negative one
     * as 0; a figure that is no number does not count. A sum that no figure counted for is {@code null}.
     */
    @Test
    void tokenSum_addsTheNumbers_boundedByTheLargestInt() {
        CliOutput.TokenSum sum = new CliOutput.TokenSum();
        CliOutput.TokenSum none = new CliOutput.TokenSum();
        CliOutput.TokenSum huge = new CliOutput.TokenSum();
        sum.add(json("10"));
        sum.add(json("-5"));
        sum.add(json("\"many\""));
        sum.add(json("{}").path("missing"));
        sum.add(json("2.9"));
        none.add(json("null"));
        huge.add(json("1e300"));
        huge.add(json("18446744073709551621"));

        assertThat(sum.value()).isEqualTo(12);
        assertThat(sum.orNull()).isEqualTo(12);
        assertThat(none.value()).isZero();
        assertThat(none.orNull()).isNull();
        assertThat(huge.value()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void text_isTheTextOfAField_elseTheAbsentValue() {
        JsonNode node = json("{\"s\":\"x\",\"n\":1,\"o\":{},\"z\":null}");

        assertThat(CliOutput.text(node, "s", "-")).isEqualTo("x");
        assertThat(List.of("n", "o", "z", "missing")).allSatisfy(field ->
                assertThat(CliOutput.text(node, field, "-")).isEqualTo("-"));
        assertThat(CliOutput.text(json("42"), "s", "-")).isEqualTo("-");
    }

    /**
     * A tool's result content: a string as it is; blocks one per line, a text block as its text and any other as its
     * JSON; no content (missing or {@code null}) as the empty text; any other value as its JSON.
     */
    @Test
    void toolResult_joinsItsBlocks_andTakesOtherContentAsJson() {
        assertThat(CliOutput.toolResult(json("\"plain <script>\""))).isEqualTo("plain <script>");
        assertThat(CliOutput.toolResult(json("[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"image\","
                + "\"data\":\"iVBO\"},42,{\"type\":\"text\"},{\"type\":\"text\",\"text\":\"b\"}]")))
                .isEqualTo("a\n{\"type\":\"image\",\"data\":\"iVBO\"}\n42\n\nb");
        assertThat(CliOutput.toolResult(json("{}").path("content"))).isEmpty();
        assertThat(CliOutput.toolResult(json("null"))).isEmpty();
        assertThat(CliOutput.toolResult(json("{\"k\":1}"))).isEqualTo("{\"k\":1}");
        assertThat(CliOutput.toolResult(json("7"))).isEqualTo("7");
    }

    @Test
    void noOutcome_failsTheCall_withTheExitCode_andTheEndsOfStdoutAndStderr() {
        List<ToolCall> calls = List.of(new ToolCall("read", "{}", null, false));

        AiOutcome both = CliOutput.noOutcome("X CLI wrote no result line", 3, "x".repeat(5000) + "\nlast line\n",
                "warning\n", calls);
        AiOutcome neither = CliOutput.noOutcome("X CLI wrote no result line", 0, "", " \n", List.of());

        assertThat(both.failed()).isTrue();
        assertThat(both.exitCode()).isEqualTo(3);
        assertThat(both.text()).isEqualTo("X CLI wrote no result line (exit code 3):\n...\nlast line\nwarning");
        assertThat(both.metadata()).isNull();
        assertThat(both.toolCalls()).isEqualTo(calls);
        assertThat(neither.text()).isEqualTo("X CLI wrote no result line (exit code 0)");
    }

    /**
     * The sentence of a failed call: what failed, the facts in parentheses when there are any, what follows them, and
     * the detail after a colon when there is one, stripped.
     */
    @Test
    void failure_isWhatFailed_withItsFacts_andItsDetail() {
        assertThat(CliOutput.failure("X CLI failed", List.of("no end", "exit code 2"), " after 2 turns", " done\n"))
                .isEqualTo("X CLI failed (no end, exit code 2) after 2 turns: done");
        assertThat(CliOutput.failure("X CLI reported an error", List.of(), "", "  ")).isEqualTo(
                "X CLI reported an error");
        assertThat(CliOutput.failure("X CLI failed", List.of("exit code 1"), "", null)).isEqualTo(
                "X CLI failed (exit code 1)");
        assertThat(CliOutput.afterTurns(1)).isEqualTo(" after 1 turn");
        assertThat(CliOutput.afterTurns(3)).isEqualTo(" after 3 turns");
        assertThat(CliOutput.afterTurns(null)).isEmpty();
    }

    /** A stream that counted no token: an estimate (characters / 4), with what the stream did name. */
    @Test
    void estimated_isTheEstimateOfThePromptAndTheAnswer_withWhatTheStreamNamed() {
        assertThat(CliOutput.estimated("p".repeat(40), "a".repeat(8), "m", 2, "s-1", List.of("read")))
                .isEqualTo(new AiResponseMetadata(10, 2, null, "m", true, null, 2, "s-1", List.of("read"), null, null,
                        null));
        assertThat(CliOutput.estimated(null, null, null, null, null, List.of())).isEqualTo(new AiResponseMetadata(0,
                0, null, null, true, null, null, null, List.of(), null, null, null));
    }

    @Test
    void withStderr_addsTheEndOfStderr_onALineOfItsOwn() {
        assertThat(CliOutput.withStderr("failed", "  oops\n")).isEqualTo("failed\noops");
        assertThat(CliOutput.withStderr("failed", "\n")).isEqualTo("failed");
        assertThat(CliOutput.withStderr("failed", null)).isEqualTo("failed");
        assertThat(CliOutput.TAIL).isEqualTo(4000);
    }
}
