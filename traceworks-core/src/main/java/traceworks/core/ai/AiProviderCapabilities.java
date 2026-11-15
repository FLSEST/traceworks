package traceworks.core.ai;

/**
 * Declares what features an AI provider supports. Used by callers to adapt their behavior (for example, to
 * skip the system prompt for a CLI without one).
 */
public record AiProviderCapabilities(
        boolean supportsSystemPrompt,
        boolean supportsModelSelection,
        boolean supportsMaxTurns,
        boolean supportsStreamJson,
        boolean supportsAllowedTools,
        boolean tokenTracking,
        boolean supportsMultiTurnReasoning,
        boolean supportsEffort,
        boolean supportsWeb
) {
    /** All features supported (Claude, Pi). */
    /** Capabilities without a web tool. */
    public AiProviderCapabilities(boolean supportsSystemPrompt, boolean supportsModelSelection,
                                  boolean supportsMaxTurns, boolean supportsStreamJson, boolean supportsAllowedTools,
                                  boolean tokenTracking, boolean supportsMultiTurnReasoning, boolean supportsEffort) {
        this(supportsSystemPrompt, supportsModelSelection, supportsMaxTurns, supportsStreamJson, supportsAllowedTools,
                tokenTracking, supportsMultiTurnReasoning, supportsEffort, false);
    }

    public static AiProviderCapabilities full() {
        return new AiProviderCapabilities(true, true, true, true, true, true, true, true, true);
    }

    /** Nothing supported: the baseline for providers that only take a prompt. */
    public static AiProviderCapabilities minimal() {
        return new AiProviderCapabilities(false, false, false, false, false, false, false, false, false);
    }

    /** These capabilities, without a web tool: a CLI that has none, whose steps cannot set {@code web}. */
    public AiProviderCapabilities withoutWeb() {
        return new AiProviderCapabilities(supportsSystemPrompt, supportsModelSelection, supportsMaxTurns,
                supportsStreamJson, supportsAllowedTools, tokenTracking, supportsMultiTurnReasoning, supportsEffort,
                false);
    }
}
