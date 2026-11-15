package traceworks.adapters.ai;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.port.CommandRunner.CommandResult;

import java.util.ArrayList;
import java.util.List;

/**
 * What a call of {@code pi -p --mode json} came to, read from its stream: one event per line on stdout, Pi's own
 * messages on stderr. The events are those of the package's type definitions ({@code AgentEvent} of
 * {@code pi-agent-core}, with {@code AssistantMessage} and {@code Usage} of {@code pi-ai}). Traceworks reads this
 * stream because it takes the tokens and the tool calls of a call from it.
 *
 * <ul>
 *   <li><b>Lines:</b> {@code tool_execution_start} and {@code tool_execution_end} carry the tool calls, each
 *       {@code message_end} of the assistant a reply of the model with its usage. Every other line is skipped: other
 *       events, Pi's partial messages should the stream filter have let one through, text, lines that are not JSON
 *       ({@link CliOutput#jsonLines}, which also repairs an unfinished last line).</li>
 *   <li><b>Tool calls:</b> each start in the order Pi started them, with its {@code args} as JSON text, paired with
 *       the end of its {@code toolCallId}, wherever that came (Pi runs a reply's tool calls side by side); a call
 *       without an end has no result. A result is its content ({@link CliOutput#toolResult}), or the result itself
 *       when it is a string. Arguments and results are whole: the record cuts and redacts them.</li>
 *   <li><b>Answer:</b> the text blocks of the last assistant message, one per line.</li>
 *   <li><b>Usage:</b> summed over the assistant messages, one per reply of the model: {@code input} is the input the
 *       prompt cache did not serve, {@code cacheRead} and {@code cacheWrite} the cached part, {@code cost.total}
 *       what Pi's own price list makes of it, in dollars. A stream without tokens (no usage, or none counted) gives
 *       an estimate (characters / 4) and says so ({@code estimated}).</li>
 *   <li><b>Model:</b> the backend and model of the last assistant message ({@code anthropic/claude-sonnet-4-5}),
 *       the backend's own name for the model when it reports one ({@code responseModel}).</li>
 *   <li><b>Verdict:</b> the call failed when no assistant message came, when the last one stopped with an error
 *       ({@code stopReason} {@code error} or {@code aborted}; Pi then still ends with 0) or for a tool call that no
 *       reply followed ({@code toolUse}), when the agent's last run did not end ({@code agent_end} after the last
 *       {@code agent_start}, and not one that announces a retry with {@code willRetry}: the stream was cut), or
 *       when Pi's exit code is not 0. A failed call's text says why: Pi's message, or the end of the stream when no
 *       assistant message came, and then the end of stderr. The exit code is passed through.</li>
 * </ul>
 */
final class PiStream {

    private final CliOutput.ToolUses uses = new CliOutput.ToolUses();
    private final CliOutput.TokenSum input = new CliOutput.TokenSum();
    private final CliOutput.TokenSum output = new CliOutput.TokenSum();
    private final CliOutput.TokenSum cacheRead = new CliOutput.TokenSum();
    private final CliOutput.TokenSum cacheWrite = new CliOutput.TokenSum();
    /** The last assistant message; {@code null} while none came. */
    private JsonNode last;
    private int replies;
    private Double cost;
    /**
     * Whether the agent's last run ended: an {@code agent_end} after the last {@code agent_start} that announces no
     * retry ({@code willRetry}).
     */
    private boolean ended;

    private PiStream() {
    }

    /**
     * The outcome of the call whose command returned {@code result}.
     *
     * @param prompt the call's prompt, for the estimate of a stream without tokens
     */
    static AiOutcome outcome(CommandResult result, String prompt) {
        PiStream stream = new PiStream();
        CliOutput.jsonLines(result.stdout()).forEach(stream::read);
        return stream.outcome(result.exitCode(), result.stdout(), result.stderr(), prompt);
    }

    private void read(JsonNode line) {
        switch (line.path("type").asText("")) {
            case "tool_execution_start" -> start(line);
            case "tool_execution_end" -> end(line);
            case "message_end" -> message(line.path("message"));
            case "agent_start" -> ended = false;
            case "agent_end" -> ended = !line.path("willRetry").asBoolean(false);
            default -> {
                // the session and turn events, partial messages, retries, compaction, and what Pi adds later
            }
        }
    }

    private void start(JsonNode line) {
        JsonNode args = line.path("args");
        JsonNode id = line.path("toolCallId");
        uses.started(id.isTextual() ? id.asText() : null, CliOutput.text(line, "toolName", "unknown"),
                args.isMissingNode() ? null : args.toString());
    }

    private void end(JsonNode line) {
        JsonNode result = line.path("result");
        uses.ended(line.path("toolCallId").asText(""),
                CliOutput.toolResult(result.isTextual() ? result : result.path("content")),
                line.path("isError").asBoolean(false));
    }

    private void message(JsonNode message) {
        if (!"assistant".equals(CliOutput.text(message, "role", ""))) {
            return;
        }
        last = message;
        replies++;
        JsonNode usage = message.path("usage");
        input.add(usage.path("input"));
        output.add(usage.path("output"));
        cacheRead.add(usage.path("cacheRead"));
        cacheWrite.add(usage.path("cacheWrite"));
        JsonNode total = usage.path("cost").path("total");
        if (total.isNumber()) {
            cost = (cost == null ? 0 : cost) + total.asDouble();
        }
    }

    /** The summed cost; {@code null} when no reply had one, or when the figures add up to no number. */
    private Double cost() {
        return cost == null || Double.isFinite(cost) ? cost : null;
    }

    private AiOutcome outcome(int exitCode, String stdout, String stderr, String prompt) {
        List<ToolCall> toolCalls = uses.calls();
        if (last == null) {
            return CliOutput.noOutcome("Pi CLI wrote no assistant message", exitCode, stdout, stderr, toolCalls);
        }
        String answer = answer();
        AiResponseMetadata metadata = metadata(toolCalls, prompt, answer);
        String stopReason = CliOutput.text(last, "stopReason", "");
        boolean reportsError = "error".equals(stopReason) || "aborted".equals(stopReason);
        if (!reportsError && !"toolUse".equals(stopReason) && ended && exitCode == 0) {
            return new AiOutcome(answer, exitCode, false, metadata, toolCalls);
        }
        return new AiOutcome(CliOutput.withStderr(message(reportsError, stopReason, exitCode, answer), stderr),
                exitCode, true, metadata, toolCalls);
    }

    /** The text blocks of the last assistant message, one per line; its thinking and tool calls are not the answer. */
    private String answer() {
        List<String> texts = new ArrayList<>();
        JsonNode content = last.path("content");
        if (content.isArray()) {
            content.elements().forEachRemaining(block -> {
                if ("text".equals(block.path("type").asText("")) && block.path("text").isTextual()) {
                    texts.add(block.path("text").asText());
                }
            });
        }
        return String.join("\n", texts);
    }

    /**
     * The sentence for a failed call with an assistant message: its stop reason when it says more than
     * {@code error}, a run that did not end, the exit code when it is not 0, the replies, and Pi's message (as Pi's
     * text mode words an error without one); for a call that did not end with an error, the answer.
     */
    private String message(boolean reportsError, String stopReason, int exitCode, String answer) {
        List<String> facts = new ArrayList<>();
        if (reportsError && !"error".equals(stopReason)) {
            facts.add(stopReason);
        }
        if ("toolUse".equals(stopReason)) {
            facts.add("stop reason toolUse");
        }
        if (!ended) {
            facts.add("no agent_end");
        }
        if (exitCode != 0) {
            facts.add("exit code " + exitCode);
        }
        String detail = reportsError ? CliOutput.text(last, "errorMessage", "").strip() : answer;
        if (reportsError && detail.isEmpty()) {
            detail = "Request " + stopReason;
        }
        return CliOutput.failure(reportsError ? "Pi CLI reported an error" : "Pi CLI failed", facts,
                CliOutput.afterTurns(replies), detail);
    }

    private AiResponseMetadata metadata(List<ToolCall> toolCalls, String prompt, String answer) {
        List<String> toolNames = toolCalls.stream().map(ToolCall::tool).toList();
        if (input.value() == 0 && output.value() == 0) {
            return CliOutput.estimated(prompt, answer, model(), replies, null, toolNames);
        }
        return new AiResponseMetadata(input.value(), output.value(), cost(), model(), false, null, replies, null,
                toolNames, cacheRead.orNull(), cacheWrite.orNull(), null);
    }

    /** {@code backend/model} of the last assistant message, the model as the backend named it when it did. */
    private String model() {
        String model = CliOutput.text(last, "responseModel", "");
        if (model.isBlank()) {
            model = CliOutput.text(last, "model", "");
        }
        if (model.isBlank()) {
            return null;
        }
        String backend = CliOutput.text(last, "provider", "");
        return backend.isBlank() ? model : backend + "/" + model;
    }
}
