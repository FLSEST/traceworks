package traceworks.core.ai;

/**
 * One tool call the model made during an AI call, as its CLI reported it once the call returned. The record writes
 * one {@code ai.tool_call} per tool call, in order.
 *
 * @param tool          the tool's name as the CLI reports it, such as {@code Read} or {@code bash}
 * @param argumentsJson the arguments the model passed, as the CLI's JSON text; {@code null} when it reports none
 * @param result        what the tool returned to the model; {@code null} when the CLI reports no result
 * @param error         whether the tool reported an error
 */
public record ToolCall(String tool, String argumentsJson, String result, boolean error) {}
