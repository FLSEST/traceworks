package traceworks.adapters.ai;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.port.CommandRunner.CommandResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What a call of {@code claude -p --output-format stream-json --verbose} came to, read from its stream: one JSON
 * object per line on stdout, the CLI's own errors on stderr.
 *
 * <ul>
 *   <li><b>Lines:</b> {@code assistant} lines carry the model and its {@code tool_use} blocks, {@code user} lines the
 *       {@code tool_result} blocks, the last {@code result} line the answer, usage and verdict. Every other line is
 *       skipped: other event types, text, lines that are not JSON ({@link CliOutput#jsonLines}, which also repairs
 *       an unfinished last line).</li>
 *   <li><b>Tool calls:</b> each {@code tool_use} in the order the model made them, with its {@code input} as JSON
 *       text, paired with the {@code tool_result} of its {@code tool_use_id}, wherever that came; a call without one
 *       has no result. Arguments and results are whole: the record cuts and redacts them.</li>
 *   <li><b>Model:</b> the one of the last assistant message, not counting the CLI's own messages ({@code <synthetic>});
 *       else the one model of the result line's {@code modelUsage}.</li>
 *   <li><b>Verdict:</b> the call failed when the stream has no result line, when its result line reports an error
 *       ({@code is_error}, or an {@code error_*} subtype such as {@code error_max_turns}; the CLI may still end with
 *       0), or when the CLI's exit code is not 0. A failed call's text says why: the CLI's message, or the end of its
 *       stream when there was no result line, and then the end of its stderr. The exit code is passed through.</li>
 * </ul>
 */
final class ClaudeStream {

    /** The model the CLI names in a message it wrote itself, such as {@code Not logged in}. */
    private static final String SYNTHETIC = "<synthetic>";

    private final CliOutput.ToolUses uses = new CliOutput.ToolUses();
    private String model;
    private JsonNode result;

    private ClaudeStream() {
    }

    /** The outcome of the call whose command returned {@code result}. */
    static AiOutcome outcome(CommandResult result) {
        ClaudeStream stream = new ClaudeStream();
        CliOutput.jsonLines(result.stdout()).forEach(stream::read);
        return stream.outcome(result.exitCode(), result.stdout(), result.stderr());
    }

    private void read(JsonNode line) {
        switch (line.path("type").asText("")) {
            case "assistant" -> assistant(line.path("message"));
            case "user" -> blocks(line.path("message")).forEachRemaining(this::toolResult);
            case "result" -> result = line;
            default -> {
                // system, stream and rate limit events, and whatever a later CLI adds
            }
        }
    }

    private void assistant(JsonNode message) {
        JsonNode named = message.path("model");
        if (named.isTextual() && !named.asText().isBlank() && !SYNTHETIC.equals(named.asText())) {
            model = named.asText();
        }
        blocks(message).forEachRemaining(block -> {
            if ("tool_use".equals(block.path("type").asText(""))) {
                JsonNode input = block.path("input");
                JsonNode id = block.path("id");
                uses.started(id.isTextual() ? id.asText() : null, CliOutput.text(block, "name", "unknown"),
                        input.isMissingNode() ? null : input.toString());
            }
        });
    }

    private void toolResult(JsonNode block) {
        if (!"tool_result".equals(block.path("type").asText(""))) {
            return;
        }
        uses.ended(block.path("tool_use_id").asText(""), CliOutput.toolResult(block.path("content")),
                block.path("is_error").asBoolean(false));
    }

    /** The blocks of a message's {@code content}; none when it is text or missing. */
    private static Iterator<JsonNode> blocks(JsonNode message) {
        JsonNode content = message.path("content");
        return content.isArray() ? content.elements() : List.<JsonNode>of().iterator();
    }

    private AiOutcome outcome(int exitCode, String stdout, String stderr) {
        List<ToolCall> toolCalls = uses.calls();
        if (result == null) {
            return CliOutput.noOutcome("Claude CLI wrote no result line", exitCode, stdout, stderr, toolCalls);
        }
        AiResponseMetadata metadata = metadata(toolCalls);
        String answer = CliOutput.text(result, "result", "");
        if (!reportsError() && exitCode == 0) {
            return new AiOutcome(answer, exitCode, false, metadata, toolCalls);
        }
        return new AiOutcome(CliOutput.withStderr(message(exitCode, answer), stderr), exitCode, true, metadata,
                toolCalls);
    }

    /**
     * Whether the result line reports a failure: {@code is_error: true} (an API error, a usage limit, no login) or an
     * {@code error_*} subtype; the CLI may end with 0 in both cases.
     */
    private boolean reportsError() {
        return result.path("is_error").asBoolean(false) || CliOutput.text(result, "subtype", "").startsWith("error");
    }

    /**
     * The sentence for a failed call with a result line: its subtype when it says more than {@code success} (which
     * the CLI also sends with {@code is_error: true}), the exit code when it is not 0, the turns, and the CLI's
     * message: the result text, else its {@code errors}.
     */
    private String message(int exitCode, String answer) {
        List<String> facts = new ArrayList<>();
        String subtype = CliOutput.text(result, "subtype", "");
        if (!subtype.isBlank() && !"success".equals(subtype)) {
            facts.add(subtype);
        }
        if (exitCode != 0) {
            facts.add("exit code " + exitCode);
        }
        return CliOutput.failure(reportsError() ? "Claude CLI reported an error" : "Claude CLI failed", facts,
                CliOutput.afterTurns(integer(result, "num_turns")), answer.isBlank() ? errors() : answer);
    }

    /** The result line's {@code errors}, such as {@code Reached maximum number of turns (1)}, joined. */
    private String errors() {
        JsonNode errors = result.path("errors");
        if (!errors.isArray()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        errors.elements().forEachRemaining(e -> texts.add(e.isTextual() ? e.asText() : e.toString()));
        return texts.stream().map(String::strip).filter(t -> !t.isEmpty()).collect(Collectors.joining("; "));
    }

    /**
     * The usage of the result line, which sums the call's turns: {@code input_tokens} is the input the prompt cache
     * did not serve, the cached part is in the cache fields (absent in older CLIs: {@code null}, never a made-up 0).
     * The web requests of a call with web tools are {@code usage.server_tool_use}, when the CLI reports them.
     * Without any token count the figures are marked estimated.
     */
    private AiResponseMetadata metadata(List<ToolCall> toolCalls) {
        JsonNode usage = result.path("usage");
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        JsonNode cost = result.path("total_cost_usd");
        JsonNode apiMs = result.path("duration_api_ms");
        JsonNode session = result.path("session_id");
        return new AiResponseMetadata(input, output, cost.isNumber() ? cost.asDouble() : null, model(),
                input == 0 && output == 0, apiMs.isNumber() ? apiMs.asLong() : null, integer(result, "num_turns"),
                session.isTextual() ? session.asText() : null, toolCalls.stream().map(ToolCall::tool).toList(),
                integer(usage, "cache_read_input_tokens"), integer(usage, "cache_creation_input_tokens"),
                integer(usage.path("output_tokens_details"), "thinking_tokens"),
                integer(usage.path("server_tool_use"), "web_search_requests"),
                integer(usage.path("server_tool_use"), "web_fetch_requests"));
    }

    private String model() {
        if (model != null) {
            return model;
        }
        JsonNode models = result.path("modelUsage");
        return models.isObject() && models.size() == 1 ? models.fieldNames().next() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.canConvertToInt() && value.isNumber() ? value.asInt() : null;
    }
}
