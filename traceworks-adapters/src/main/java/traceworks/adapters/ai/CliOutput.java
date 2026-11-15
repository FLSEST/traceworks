package traceworks.adapters.ai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.util.TruncationUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading what an AI CLI printed: the JSON lines of its stream, the end of a text for a message, and what the CLIs'
 * streams have in common: tool calls paired with their results, a tool result's text, and the outcome of a stream that
 * holds none. Whatever the CLI printed, nothing here throws.
 */
final class CliOutput {

    /**
     * Reads the lines. The whole output is in memory already, so a string in it may be as long as a Java string (a
     * tool's argument, a file the model writes), not only Jackson's default of 20 million characters.
     */
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .build());

    /** How much of a stream and of stderr a failed call's text keeps, each: the end, where the error is. */
    static final int TAIL = 4000;

    private CliOutput() {
    }

    /** The text of {@code node}'s {@code field}; {@code absent} when it is missing or no text. */
    static String text(JsonNode node, String field, String absent) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : absent;
    }

    /**
     * A tool's result content as text: a string as it is; blocks one per line, a text block as its text and any other
     * block as its JSON (an image as its data); no content, missing or {@code null}, as the empty text; any other
     * value as its JSON.
     */
    static String toolResult(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            content.elements().forEachRemaining(block -> parts.add("text".equals(block.path("type").asText(""))
                    ? block.path("text").asText("") : block.toString()));
            return String.join("\n", parts);
        }
        return content.isMissingNode() || content.isNull() ? "" : content.toString();
    }

    /**
     * The outcome of a stream that holds no outcome: a failed call, whose text is {@code sentence} with the exit code
     * and, on their own lines, the end of the stream and of stderr, where what went wrong is; no usage.
     *
     * @param sentence what is missing, such as {@code Claude CLI wrote no result line}
     */
    static AiOutcome noOutcome(String sentence, int exitCode, String stdout, String stderr, List<ToolCall> toolCalls) {
        String ends = String.join("\n", tail(stdout, TAIL), tail(stderr, TAIL)).strip();
        return new AiOutcome(sentence + " (exit code " + exitCode + ")" + (ends.isEmpty() ? "" : ":\n" + ends),
                exitCode, true, null, toolCalls);
    }

    /**
     * The sentence of a failed call: {@code what}, the facts in parentheses when there are any, {@code after} as it
     * is, and the detail after a colon when there is one.
     *
     * @param what   such as {@code Pi CLI reported an error}
     * @param facts  such as {@code exit code 2}, each a few words
     * @param after  what follows the facts, such as {@link #afterTurns}; empty for nothing
     * @param detail the CLI's message or the answer, stripped; {@code null} or blank for none
     */
    static String failure(String what, List<String> facts, String after, String detail) {
        StringBuilder sentence = new StringBuilder(what);
        if (!facts.isEmpty()) {
            sentence.append(" (").append(String.join(", ", facts)).append(')');
        }
        sentence.append(after);
        String text = detail == null ? "" : detail.strip();
        return text.isEmpty() ? sentence.toString() : sentence.append(": ").append(text).toString();
    }

    /** {@code " after 1 turn"} or {@code " after <n> turns"}; empty for {@code null}. */
    static String afterTurns(Integer turns) {
        return turns == null ? "" : " after " + turns + (turns == 1 ? " turn" : " turns");
    }

    /**
     * The usage of a call whose stream counted no token: an estimate (characters / 4) of the prompt
     * and the answer, marked as estimated, with what the stream did name; no cost and no cache figures.
     */
    static AiResponseMetadata estimated(String prompt, String answer, String model, Integer numTurns, String sessionId,
                                        List<String> toolNames) {
        AiResponseMetadata estimate = AiResponseMetadata.estimated(prompt, answer);
        return new AiResponseMetadata(estimate.inputTokens(), estimate.outputTokens(), null, model, true, null,
                numTurns, sessionId, toolNames, null, null, null);
    }

    /** {@code message} with the end of {@code stderr} on a line of its own, when stderr holds anything. */
    static String withStderr(String message, String stderr) {
        String end = tail(stderr, TAIL);
        return end.isEmpty() ? message : message + "\n" + end;
    }

    /**
     * A token figure summed over a stream's replies, bounded by the largest int: a number counts (a negative one as
     * 0, a fraction cut to its whole part), anything else does not; {@code null} while no figure counted.
     */
    static final class TokenSum {
        private long total;
        private boolean present;

        void add(JsonNode figure) {
            if (figure.isNumber()) {
                double value = figure.asDouble();
                long bounded = value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : value > 0 ? (long) value : 0;
                total = Math.min(Integer.MAX_VALUE, total + bounded);
                present = true;
            }
        }

        int value() {
            return (int) total;
        }

        Integer orNull() {
            return present ? value() : null;
        }
    }

    /**
     * The tool calls a stream reports, each paired with the result of its id, wherever that came: in the order they
     * were made, and a call without a result has none. A result for an id no call has, or a second one, is dropped.
     */
    static final class ToolUses {

        private final List<Use> uses = new ArrayList<>();
        private final Map<String, Use> unanswered = new HashMap<>();

        /** One tool call, and the result that came for it. */
        private static final class Use {
            final String tool;
            final String arguments;
            String result;
            boolean error;

            Use(String tool, String arguments) {
                this.tool = tool;
                this.arguments = arguments;
            }
        }

        /**
         * A call of {@code tool} with its arguments as JSON text ({@code null} for none); {@code id} {@code null} for
         * a call no result can name.
         */
        void started(String id, String tool, String argumentsJson) {
            Use use = new Use(tool, argumentsJson);
            uses.add(use);
            if (id != null) {
                unanswered.put(id, use);
            }
        }

        /** A call that came with its result: a stream that reports an end it reported no start for. */
        void answered(String tool, String argumentsJson, String result, boolean error) {
            Use use = new Use(tool, argumentsJson);
            use.result = result;
            use.error = error;
            uses.add(use);
        }

        /** The result of the call {@code id}. */
        void ended(String id, String result, boolean error) {
            Use use = unanswered.remove(id);
            if (use != null) {
                use.result = result;
                use.error = error;
            }
        }

        List<ToolCall> calls() {
            return uses.stream().map(u -> new ToolCall(u.tool, u.arguments, u.result, u.error)).toList();
        }
    }

    /**
     * The JSON objects of a stream that holds one per line, in order. A line that is no JSON object is skipped: text,
     * another JSON value, a line broken by bytes that are not UTF-8 (decoded to U+FFFD, so it may still be one). The
     * last line is the one a CLI that was cut off leaves unfinished: if it is no JSON, it is closed where it broke off
     * (an open string, arrays and objects, a trailing comma), and
     * kept if that makes it an object. Any other line is never repaired.
     *
     * @param stdout the CLI's stdout; {@code null} for none
     */
    static List<JsonNode> jsonLines(String stdout) {
        List<JsonNode> objects = new ArrayList<>();
        if (stdout == null) {
            return objects;
        }
        String[] lines = stdout.split("\n", -1);
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isBlank()) {
            last--;
        }
        for (int i = 0; i <= last; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode node = object(line);
            if (node == null && i == last) {
                String closed = closed(line);
                node = closed == null ? null : object(closed);
            }
            if (node != null) {
                objects.add(node);
            }
        }
        return objects;
    }

    /** The line as a JSON object; {@code null} when it is none. */
    private static JsonNode object(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * The line closed where it broke off: an open string closed, then each open array and object, with a trailing
     * comma removed before each; {@code null} for a line that starts no JSON object or array.
     */
    private static String closed(String line) {
        if (!line.startsWith("{") && !line.startsWith("[")) {
            return null;
        }
        StringBuilder text = new StringBuilder(line);
        Deque<Character> open = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                open.push(c);
            } else if ((c == '}' || c == ']') && !open.isEmpty() && open.peek() == (c == '}' ? '{' : '[')) {
                open.pop();
            }
        }
        if (inString) {
            text.append('"');
        }
        while (!open.isEmpty()) {
            dropTrailingComma(text);
            text.append(open.pop() == '{' ? '}' : ']');
        }
        return text.toString();
    }

    private static void dropTrailingComma(StringBuilder text) {
        int i = text.length() - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        if (i >= 0 && text.charAt(i) == ',') {
            text.deleteCharAt(i);
        }
    }

    /** The end of {@code text}, cut where it splits no secret value ({@link TruncationUtil#secretSafeTail}). */
    static String tail(String text, int limit) {
        return TruncationUtil.secretSafeTail(text, limit);
    }
}
