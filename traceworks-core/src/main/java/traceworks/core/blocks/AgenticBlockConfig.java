package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.WebAccess;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.UntrustedInput;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockSettings;

import java.util.Map;

/**
 * Parsed and validated configuration of an agentic block.
 *
 * @param callTimeoutSeconds {@code timeout_seconds}: how long one model call (REASON or ACT) may take; {@code null} for
 *                           the provider's default
 * @param tools              {@code tools}, required: what every call of the block may do, REASON
 *                           included; there is no default, so no call acts with a tool the workflow does not name
 * @param writableWorkspace  {@code workspace_writable}: the calls' containers may write the workspace
 * @param untrustedInput     {@code untrusted_input}: REASON and ACT get the preamble for foreign content
 */
record AgenticBlockConfig(
        String goal,
        String successCriteria,
        int maxIterations,
        int iterationTimeoutSeconds,
        int totalTimeoutSeconds,
        int maxTotalTokens,
        int maxRepeatedActions,
        boolean requireApproval,
        Integer callTimeoutSeconds,
        ToolPolicy tools,
        boolean writableWorkspace,
        boolean untrustedInput,
        WebAccess web
) {

    static final int DEFAULT_MAX_ITERATIONS = 10;
    static final int DEFAULT_ITERATION_TIMEOUT_SECONDS = 300;
    static final int DEFAULT_TOTAL_TIMEOUT_SECONDS = 1800;
    static final int DEFAULT_MAX_TOTAL_TOKENS = 200_000;
    static final int DEFAULT_MAX_REPEATED_ACTIONS = 3;

    private static final System.Logger LOG = System.getLogger(AgenticBlockConfig.class.getName());

    /**
     * Extracts and validates the agentic block config from the raw config map and the run's context.
     *
     * @throws WorkflowExecutionException without a {@code goal}, or without a valid {@code tools}
     * @throws traceworks.core.model.InvalidSettingException for a setting of the wrong type or out
     *                                                                        of range
     */
    static AgenticBlockConfig from(Map<String, Object> config, ExecutionContext context) {
        BlockSettings settings = BlockSettings.of("agentic", config);
        String rawGoal = requireString(settings, "goal");
        ToolPolicy tools = tools(config);
        String rawSuccessCriteria = settings.text("success_criteria", "");
        int maxIterations = settings.number("max_iterations", DEFAULT_MAX_ITERATIONS);
        int iterationTimeoutSeconds = settings.number("iteration_timeout_seconds", DEFAULT_ITERATION_TIMEOUT_SECONDS);
        int totalTimeoutSeconds = settings.number("total_timeout_seconds", DEFAULT_TOTAL_TIMEOUT_SECONDS);
        int maxTotalTokens = settings.number("max_total_tokens", DEFAULT_MAX_TOTAL_TOKENS);
        int maxRepeatedActions = settings.number("max_repeated_actions", DEFAULT_MAX_REPEATED_ACTIONS);
        boolean requireApproval = settings.flag("require_approval");
        // Within its range (at most a day), so the auto-alignment below stays inside an int
        Integer callTimeoutSeconds = settings.number("timeout_seconds");
        boolean writableWorkspace = settings.flag("workspace_writable");
        String goal = context.resolvePromptTemplate(rawGoal);
        String successCriteria = context.resolvePromptTemplate(rawSuccessCriteria);

        // Auto-align: the iteration timeout must accommodate the call timeout (REASON + ACT + overhead)
        if (callTimeoutSeconds != null && callTimeoutSeconds > 0) {
            int minIterationTimeout = callTimeoutSeconds * 2 + 60; // REASON + ACT + 60s overhead
            if (iterationTimeoutSeconds < minIterationTimeout) {
                LOG.log(System.Logger.Level.WARNING, "Auto-aligning iteration_timeout_seconds: {0} -> {1} "
                                + "(timeout_seconds={2}, need room for REASON + ACT + overhead)",
                        iterationTimeoutSeconds, minIterationTimeout, callTimeoutSeconds);
                iterationTimeoutSeconds = minIterationTimeout;
            }
            // The total timeout must fit at least one full iteration
            if (totalTimeoutSeconds < minIterationTimeout) {
                int newTotalTimeout = minIterationTimeout * maxIterations;
                LOG.log(System.Logger.Level.WARNING, "Auto-aligning total_timeout_seconds: {0} -> {1} "
                                + "(must fit at least one iteration of {2}s)",
                        totalTimeoutSeconds, newTotalTimeout, minIterationTimeout);
                totalTimeoutSeconds = newTotalTimeout;
            }
        }

        return new AgenticBlockConfig(
                goal, successCriteria, maxIterations, iterationTimeoutSeconds,
                totalTimeoutSeconds, maxTotalTokens, maxRepeatedActions,
                requireApproval, callTimeoutSeconds, tools, writableWorkspace,
                UntrustedInput.declared(config), SimpleBlockExecutor.web(config));
    }

    /** The required {@code tools}: without it the block's actions would change nothing, so it is never assumed. */
    private static ToolPolicy tools(Map<String, Object> config) {
        if (config.get(ToolPolicy.KEY) == null) {
            throw new WorkflowExecutionException("Agentic block requires 'tools' in config: " + ToolPolicy.VALUES
                    + " (Traceworks gives an agentic block no tools it does not name)");
        }
        return SimpleBlockExecutor.tools(config);
    }

    private static String requireString(BlockSettings settings, String key) {
        String value = settings.text(key);
        if (value == null) {
            throw new WorkflowExecutionException("Agentic block requires '" + key + "' in config");
        }
        return value;
    }
}
