package traceworks.core.record;

import traceworks.core.port.BlobRef;

import java.util.ArrayList;
import java.util.List;

/**
 * An AI call a step made, as the record has it: {@code ai.request}, its {@code ai.tool_call}s and its
 * {@code ai.response}. The CLI's container command is among the step's commands.
 *
 * @param seq          the seq of the {@code ai.request}
 * @param item         the foreach item that made the call; {@code null} outside an item
 * @param model        the model asked for; {@code null} for the CLI's default
 * @param tools        the tool policy of the call, such as {@code read}
 * @param web          the web mode of the call: {@code none}, {@code search} or {@code fetch}; {@code null} for a
 *                     record from before web tools
 * @param webHosts     the hosts a {@code fetch} named ({@code web_hosts}); empty otherwise
 * @param systemPrompt {@code null} for none
 * @param toolCalls    the tool calls in order
 * @param response     {@code null} when the record has no {@code ai.response} for the call (its process ended
 *                     during the call)
 * @param local        the model server of the operator's the call went to ({@code local} of {@code ai.request});
 *                     {@code null} for a call to the provider
 */
public record AiCallView(int seq, Integer item, String provider, String model, String effort, String tools,
                         String web, List<String> webHosts,
                         BlobRef prompt, BlobRef systemPrompt, List<ToolCallView> toolCalls, Response response,
                         Local local) {

    /** A call to the provider. */
    public AiCallView(int seq, Integer item, String provider, String model, String effort, String tools,
                      String web, List<String> webHosts,
                      BlobRef prompt, BlobRef systemPrompt, List<ToolCallView> toolCalls, Response response) {
        this(seq, item, provider, model, effort, tools, web, webHosts, prompt, systemPrompt, toolCalls, response,
                null);
    }

    /**
     * The model server of the operator's a call went to, as the record names it.
     *
     * @param backend the backend id of {@code local_models}
     * @param url     the base URL of the server
     * @param api     the API the server was asked in
     */
    public record Local(String backend, String url, String api) {

        /** {@code local backend <id> at <url>}: the words every view names the server with. */
        public String summary() {
            return "local backend " + backend + " at " + url;
        }
    }

    /** {@link Local#summary} of the call's server; {@code null} for a call to the provider. */
    public String localSummary() {
        return local == null ? null : local.summary();
    }

    /** The web mode of a call that may fetch pages itself, from its container. */
    public static final String FETCH = "fetch";

    public AiCallView {
        webHosts = webHosts == null ? List.of() : List.copyOf(webHosts);
        toolCalls = List.copyOf(toolCalls);
    }

    /** Whether the model had a web tool: {@code search} or {@code fetch}. */
    public boolean hasWeb() {
        return web != null && !web.equals("none");
    }

    /**
     * One tool call.
     *
     * @param arguments the arguments the model passed, as JSON text
     * @param result    what the tool returned, cut at 2 000 characters
     */
    public record ToolCallView(int seq, String tool, BlobRef arguments, BlobRef result, boolean error) {}

    /**
     * How the call ended. Every figure is {@code null} when the CLI reported none.
     *
     * @param model    the model that answered
     * @param inputTokens the input tokens the call sent: the record's {@code input_tokens} plus the part the prompt
     *                    cache held ({@code cache_read_input_tokens}, {@code cache_creation_input_tokens}), which
     *                    Claude reports apart
     * @param exitCode the CLI's exit code; {@code null} for a call that threw
     * @param output   the answer of a call with exit code 0
     * @param error    why the call failed: the CLI's message, or what the call threw
     */
    public record Response(String model, Integer inputTokens, Integer outputTokens, Double costUsd, Integer numTurns,
                           Integer exitCode, BlobRef output, BlobRef error) {}

    /** How many characters of an error's first line a view shows with the call. */
    public static final int ERROR_LINE_LIMIT = 200;

    /**
     * The first line of a failed call's error that says something, for the line of the call: trimmed and cut at
     * {@value #ERROR_LINE_LIMIT} characters with {@code ...}. The whole error stays in its blob. The text is the
     * CLI's: a view still has to make it harmless for where it prints it.
     *
     * @return {@code null} when the error is {@code null} or blank
     */
    public static String firstErrorLine(String error) {
        if (error == null) {
            return null;
        }
        String first = error.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse(null);
        if (first == null || first.length() <= ERROR_LINE_LIMIT) {
            return first;
        }
        int end = ERROR_LINE_LIMIT;
        if (Character.isHighSurrogate(first.charAt(end - 1))) {
            end--;   // never half a pair
        }
        return first.substring(0, end) + "...";
    }

    AiCallView withToolCall(ToolCallView call) {
        List<ToolCallView> calls = new ArrayList<>(toolCalls);
        calls.add(call);
        return new AiCallView(seq, item, provider, model, effort, tools, web, webHosts, prompt, systemPrompt, calls,
                response, local);
    }

    AiCallView withResponse(Response response) {
        return new AiCallView(seq, item, provider, model, effort, tools, web, webHosts, prompt, systemPrompt,
                toolCalls, response, local);
    }
}
