package traceworks.core.blocks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One structured answer of the agentic REASON phase: the goal status, the rationale, the next action and a summary. */
final class AgenticPlan {

    private final String goalStatus;   // IN_PROGRESS, ACHIEVED, IMPOSSIBLE
    private final String rationale;
    private final String action;
    private final String summary;

    private AgenticPlan(Builder builder) {
        this.goalStatus = builder.goalStatus;
        this.rationale = builder.rationale;
        this.action = builder.action;
        this.summary = builder.summary;
    }

    static Builder builder() {
        return new Builder();
    }

    String getGoalStatus() {
        return goalStatus;
    }

    String getRationale() {
        return rationale;
    }

    String getAction() {
        return action;
    }

    String getSummary() {
        return summary;
    }

    boolean isGoalComplete() {
        return "ACHIEVED".equalsIgnoreCase(goalStatus)
                || "IMPOSSIBLE".equalsIgnoreCase(goalStatus);
    }

    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "GOAL_STATUS:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern RATIONALE_PATTERN = Pattern.compile(
            "RATIONALE:\\s*(.+?)(?=\\nACTION:|\\nGOAL_STATUS:|\\nSUMMARY:|$)", Pattern.DOTALL);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "ACTION:[ \\t]*(.*?)(?=\\nRATIONALE:|\\nGOAL_STATUS:|\\nSUMMARY:|$)", Pattern.DOTALL);
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
            "SUMMARY:\\s*(.+?)(?=\\nRATIONALE:|\\nGOAL_STATUS:|\\nACTION:|$)", Pattern.DOTALL);

    /**
     * Parses a structured reasoning response. Expected format:
     * <pre>
     *   GOAL_STATUS: IN_PROGRESS|ACHIEVED|IMPOSSIBLE
     *   RATIONALE: ...
     *   ACTION: ...
     *   SUMMARY: ...
     * </pre>
     * Falls back to treating the entire response as a single action if parsing fails.
     */
    static AgenticPlan parse(String response) {
        if (response == null || response.isBlank()) {
            return AgenticPlan.builder()
                    .goalStatus("IMPOSSIBLE")
                    .rationale("Empty response from reasoning")
                    .action("")
                    .summary("No response")
                    .build();
        }

        String status = extractField(STATUS_PATTERN, response, "IN_PROGRESS");
        String rationale = extractField(RATIONALE_PATTERN, response, "");
        String action = extractField(ACTION_PATTERN, response, "");
        String summary = extractField(SUMMARY_PATTERN, response, "");

        // An unstructured response becomes the action: only when there is no ACTION: field at all
        boolean hasActionField = response.contains("ACTION:");
        if (action.isEmpty() && !hasActionField && !status.equalsIgnoreCase("ACHIEVED")
                && !status.equalsIgnoreCase("IMPOSSIBLE")) {
            action = response.trim();
            rationale = "Unstructured response, using as direct action";
            summary = "Direct execution";
        }

        return AgenticPlan.builder()
                .goalStatus(status.trim())
                .rationale(rationale.trim())
                .action(action.trim())
                .summary(summary.trim())
                .build();
    }

    private static String extractField(Pattern pattern, String text, String defaultValue) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return defaultValue;
    }

    /** Plain-Java builder; unset fields stay {@code null}. */
    static final class Builder {
        private String goalStatus;
        private String rationale;
        private String action;
        private String summary;

        private Builder() {}

        Builder goalStatus(String v) { this.goalStatus = v; return this; }
        Builder rationale(String v) { this.rationale = v; return this; }
        Builder action(String v) { this.action = v; return this; }
        Builder summary(String v) { this.summary = v; return this; }

        AgenticPlan build() {
            return new AgenticPlan(this);
        }
    }
}
