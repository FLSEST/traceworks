package traceworks.core.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized response metadata across AI providers. For providers without real token tracking,
 * {@code estimated} is true and token counts are approximated with a chars/4 heuristic.
 *
 * <p>{@code inputTokens} is what the provider reports as input: for Claude, the input the prompt cache did not
 * serve. The cached part comes separately as {@code cacheReadInputTokens} and {@code cacheCreationInputTokens},
 * {@code null} when the provider does not report it (Pi, older Claude CLIs). The agentic token budget
 * counts {@code inputTokens} only.
 *
 * <p>{@code thinkingTokens} is the part of {@code outputTokens} the model spent on extended thinking: included in
 * {@code outputTokens}, never added on top; {@code null} when not reported.
 */
public record AiResponseMetadata(
        int inputTokens,
        int outputTokens,
        Double costUsd,
        String model,
        boolean estimated,
        Long durationApiMs,
        Integer numTurns,
        String sessionId,
        List<String> toolNames,
        Integer cacheReadInputTokens,
        Integer cacheCreationInputTokens,
        Integer thinkingTokens,
        Integer webSearchRequests,
        Integer webFetchRequests
) {
    /** Metadata of a call whose CLI reported no web requests. */
    public AiResponseMetadata(int inputTokens, int outputTokens, Double costUsd, String model, boolean estimated,
                              Long durationApiMs, Integer numTurns, String sessionId, List<String> toolNames,
                              Integer cacheReadInputTokens, Integer cacheCreationInputTokens,
                              Integer thinkingTokens) {
        this(inputTokens, outputTokens, costUsd, model, estimated, durationApiMs, numTurns, sessionId, toolNames,
                cacheReadInputTokens, cacheCreationInputTokens, thinkingTokens, null, null);
    }

    /** Metadata without thinking figures. */
    public AiResponseMetadata(int inputTokens, int outputTokens, Double costUsd, String model, boolean estimated,
                              Long durationApiMs, Integer numTurns, String sessionId, List<String> toolNames,
                              Integer cacheReadInputTokens, Integer cacheCreationInputTokens) {
        this(inputTokens, outputTokens, costUsd, model, estimated, durationApiMs, numTurns, sessionId, toolNames,
                cacheReadInputTokens, cacheCreationInputTokens, null, null, null);
    }

    /** Metadata without cache figures. */
    public AiResponseMetadata(int inputTokens, int outputTokens, Double costUsd, String model, boolean estimated,
                              Long durationApiMs, Integer numTurns, String sessionId, List<String> toolNames) {
        this(inputTokens, outputTokens, costUsd, model, estimated, durationApiMs, numTurns, sessionId, toolNames,
                null, null, null, null, null);
    }

    /** Estimate tokens from text lengths (chars / 4 heuristic). */
    public static AiResponseMetadata estimated(String inputText, String outputText) {
        int inputTokens = inputText != null ? inputText.length() / 4 : 0;
        int outputTokens = outputText != null ? outputText.length() / 4 : 0;
        return estimated(inputTokens, outputTokens);
    }

    /** Create estimated metadata with pre-calculated token counts. */
    public static AiResponseMetadata estimated(int inputTokens, int outputTokens) {
        return new AiResponseMetadata(inputTokens, outputTokens, null, null, true, null, null, null, List.of());
    }

    /** Convert to the map format used by step and run metadata. */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("inputTokens", inputTokens);
        map.put("outputTokens", outputTokens);
        if (cacheReadInputTokens != null) map.put("cacheReadInputTokens", cacheReadInputTokens);
        if (cacheCreationInputTokens != null) map.put("cacheCreationInputTokens", cacheCreationInputTokens);
        if (thinkingTokens != null) map.put("thinkingTokens", thinkingTokens);
        if (webSearchRequests != null) map.put("webSearchRequests", webSearchRequests);
        if (webFetchRequests != null) map.put("webFetchRequests", webFetchRequests);
        if (costUsd != null) map.put("costUsd", costUsd);
        if (model != null) map.put("model", model);
        if (estimated) map.put("estimated", true);
        if (durationApiMs != null) map.put("apiDurationMs", durationApiMs);
        if (numTurns != null) map.put("numTurns", numTurns);
        if (sessionId != null) map.put("sessionId", sessionId);
        if (toolNames != null && !toolNames.isEmpty()) {
            map.put("toolNames", toolNames);
            map.put("toolUseCount", toolNames.size());
        }
        return map;
    }
}
