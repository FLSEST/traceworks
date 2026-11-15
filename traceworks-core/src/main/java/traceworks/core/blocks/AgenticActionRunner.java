package traceworks.core.blocks;

import traceworks.core.ai.CallPriority;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.UntrustedInput;
import traceworks.core.engine.EventLogException;
import traceworks.core.port.AiProvider;
import traceworks.core.util.TruncationUtil;

import java.util.Map;

/**
 * The ACT phase: compresses oversized actions, executes them through the provider with the block's tools and workspace
 * setting, and records token metadata.
 */
class AgenticActionRunner {

    /** An action longer than this is compressed before it is executed. */
    static final int COMPRESS_THRESHOLD = 100_000;

    record ActOutcome(String action, String output, int exitCode) {}

    private final AgenticPromptBuilder promptBuilder;

    AgenticActionRunner(AgenticPromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder;
    }

    ActOutcome run(ExecutionContext context, int stepIndex, int iteration, String plannedAction,
                   AiProvider provider, String model, String effort, AgenticBlockConfig cfg,
                   AgenticTokenTracker tokenTracker, Map<String, Object> iterData) {
        String action = compressIfNeeded(context, stepIndex, iteration, plannedAction, provider, model, cfg);

        AgenticEvents.emit(context, stepIndex, "agentic_acting",
                Map.of("iteration", iteration, "action",
                        TruncationUtil.head(action, AgenticBlockExecutor.TRUNCATE_MEDIUM, context.secretValues())));

        try {
            AiProvider.ActionResult actResult = provider.executeAction(
                    new AiProvider.ActionRequest(action, model,
                            UntrustedInput.systemPrompt(context.getInstructionPrefix(), cfg.untrustedInput()),
                            context.getProjectPath(),
                            cfg.callTimeoutSeconds(), CallPriority.BACKGROUND, cfg.tools(), cfg.writableWorkspace(),
                            effort, cfg.web()));
            String output = actResult.text() != null ? actResult.text() : "";
            tokenTracker.captureActMeta(iterData, actResult.metadata(), action, output, iteration);
            return new ActOutcome(action, output, actResult.exitCode());
        } catch (EventLogException e) {
            throw e;   // the record of the call could not be written: the run stops, the step result does not matter
        } catch (Exception e) {
            return new ActOutcome(action, "Execution error: " + e.getMessage(), -1);
        }
    }

    private String compressIfNeeded(ExecutionContext context, int stepIndex, int iteration,
                                    String action, AiProvider provider, String model, AgenticBlockConfig cfg) {
        if (action.length() <= COMPRESS_THRESHOLD) {
            return action;
        }
        AgenticEvents.emit(context, stepIndex, "agentic_action_compressed",
                Map.of("iteration", iteration, "originalLength", action.length()));
        try {
            return promptBuilder.compressAction(action, provider, model, cfg.tools(), cfg.writableWorkspace());
        } catch (EventLogException e) {
            throw e;   // the record of the call could not be written: the run stops, the step result does not matter
        } catch (Exception e) {
            return action.substring(0, TruncationUtil.cutBefore(action, COMPRESS_THRESHOLD, context.secretValues()))
                    + "\n\n[TRUNCATED: original was " + action.length() + " chars]";
        }
    }
}
