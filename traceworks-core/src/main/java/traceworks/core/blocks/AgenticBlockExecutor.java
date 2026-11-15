package traceworks.core.blocks;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.ConversationPrompt.Turn;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.UntrustedInput;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.AiProvider;
import traceworks.core.port.Clock;
import traceworks.core.util.TruncationUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code agentic}: a Reason-Act-Observe loop towards a {@code goal}. Each iteration asks the model for a plan (REASON,
 * {@link AiProvider#reason}), runs its action (ACT, {@link AiProvider#executeAction} in the run's working directory)
 * and feeds the result back (OBSERVE), until the model reports the goal achieved or impossible, or a safety rule stops
 * it: {@code max_iterations}, the total and iteration timeouts, the token budget ({@code max_total_tokens}), a repeated
 * action, or missing token data. Every call of the loop carries the block's required {@code tools} and its
 * {@code workspace_writable}. With {@code require_approval: true} a person
 * approves each action ({@link AgenticApprovalGate}). The step completes only when the goal was achieved.
 *
 * <p>Prompt construction lives in {@link AgenticPromptBuilder}, the ACT phase in {@link AgenticActionRunner}, the
 * post-action rules in {@link AgenticStopRules}; the loop reports its phases as run events ({@link AgenticEvents}).
 */
public class AgenticBlockExecutor implements BlockExecutor {

    /** Lengths the loop cuts its texts to, in the output, the events and the observation it feeds back. */
    static final int TRUNCATE_LONG = 2000;
    static final int TRUNCATE_MEDIUM = 500;
    static final int TRUNCATE_SHORT = 200;
    static final int TRUNCATE_OBSERVATION = 4000;

    private final AgenticPromptBuilder promptBuilder;
    private final AgenticApprovalGate approvalGate;
    private final AgenticActionRunner actionRunner;
    private final AgenticStopRules stopRules;

    public AgenticBlockExecutor() {
        this.promptBuilder = new AgenticPromptBuilder();
        this.approvalGate = new AgenticApprovalGate();
        this.actionRunner = new AgenticActionRunner(promptBuilder);
        this.stopRules = new AgenticStopRules();
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("agentic");
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Clock clock = context.getClock();
        Instant start = clock.now();
        // What every cut of the run's text below keeps whole, so the record can redact it
        Collection<String> keepWhole = context.secretValues();

        AgenticBlockConfig cfg = AgenticBlockConfig.from(block.getConfig(), context);

        String systemPrompt = promptBuilder.buildSystemPrompt(cfg.goal(), cfg.successCriteria(),
                UntrustedInput.systemPrompt(context.getInstructionPrefix(), cfg.untrustedInput()));

        // An unknown provider is refused: the step fails before any call, never runs on another provider
        AiProvider provider;
        try {
            provider = context.getAiProviders().resolve(context.providerFor(block));
        } catch (IllegalArgumentException e) {
            return refused(stepIndex, start, cfg.goal(), e.getMessage(), clock);
        }
        String model = context.modelFor(block);
        String effort = context.effortFor(block);
        AiProviderCapabilities capabilities = provider.getCapabilities() != null
                ? provider.getCapabilities() : AiProviderCapabilities.minimal();

        List<Turn> history = new ArrayList<>();
        history.add(new Turn("user",
                "Begin working on the goal. Analyze what needs to be done and propose your first action."));

        AgenticTokenTracker tokenTracker = new AgenticTokenTracker();
        if (!capabilities.tokenTracking()) {
            tokenTracker.setExpectEstimatedTokens(true);
        }
        AgenticSafetyGuard safetyGuard = new AgenticSafetyGuard(clock,
                cfg.totalTimeoutSeconds(), cfg.iterationTimeoutSeconds(), cfg.maxRepeatedActions());

        AgenticEvents.emit(context, stepIndex, "agentic_started",
                Map.of("goal", cfg.goal(), "maxIterations", cfg.maxIterations()));

        StringBuilder fullOutput = new StringBuilder();
        int iteration = 0;
        boolean goalAchieved = false;
        boolean approvalRequired = cfg.requireApproval();
        String stopReason = null;
        String reasonThinking = null; // pi's --thinking level of the REASON calls

        while (iteration < cfg.maxIterations() && !context.shouldStop()) {
            // Total timeout check
            String timeoutReason = safetyGuard.checkTotalTimeout();
            if (timeoutReason != null) {
                stopReason = timeoutReason;
                long elapsed = safetyGuard.elapsedSeconds();
                fullOutput.append("[TOTAL TIMEOUT after ").append(elapsed).append("s]\n");
                AgenticEvents.emit(context, stepIndex, "agentic_total_timeout", Map.of("elapsedSeconds", elapsed));
                break;
            }

            // Handle pause/cancel
            if (!safetyGuard.waitIfPaused(context)) break;
            if (context.shouldStop()) break;

            iteration++;
            Instant iterStart = clock.now();
            Map<String, Object> iterData = new LinkedHashMap<>();
            iterData.put("iteration", iteration);

            // === REASON phase ===
            AgenticEvents.emit(context, stepIndex, "agentic_reasoning", Map.of("iteration", iteration));

            String reasoning;
            try {
                String conversationPrompt = provider.buildConversationPrompt(systemPrompt, history);
                AiProvider.ReasoningResult reasonResult = provider.reason(
                        new AiProvider.ReasoningRequest(conversationPrompt, systemPrompt, model,
                                null, null, cfg.callTimeoutSeconds(), effort, cfg.tools(), cfg.writableWorkspace(),
                                cfg.web()));
                reasoning = reasonResult.text();
                if (reasonResult.thinkingLevel() != null) reasonThinking = reasonResult.thinkingLevel();
                tokenTracker.captureReasonMeta(iterData, reasonResult.metadata(), conversationPrompt, reasoning,
                        iteration);
            } catch (EventLogException e) {
                throw e;   // the record of the call could not be written: the run stops
            } catch (Exception e) {
                iterData.put("phase", "reason");
                iterData.put("error", e.getMessage());
                tokenTracker.trackIteration(iterData);
                AgenticEvents.emit(context, stepIndex, "agentic_error",
                        Map.of("iteration", iteration, "phase", "reason", "error", String.valueOf(e.getMessage())));
                fullOutput.append("[iteration ").append(iteration)
                        .append(" - REASON ERROR]: ").append(e.getMessage()).append("\n");
                break;
            }

            history.add(new Turn("assistant", reasoning));
            iterData.put("reasoning", TruncationUtil.head(reasoning, TRUNCATE_LONG, keepWhole));

            AgenticEvents.emit(context, stepIndex, "agentic_reasoned",
                    Map.of("iteration", iteration, "reasoning",
                            TruncationUtil.head(reasoning, TRUNCATE_LONG, keepWhole)));

            // Parse structured response
            AgenticPlan plan = AgenticPlan.parse(reasoning);

            if (plan.isGoalComplete()) {
                goalAchieved = "ACHIEVED".equalsIgnoreCase(plan.getGoalStatus());
                fullOutput.append("[iteration ").append(iteration).append(" - ")
                        .append(plan.getGoalStatus()).append("]: ")
                        .append(plan.getSummary()).append("\n");
                iterData.put("result", plan.getGoalStatus().toLowerCase(Locale.ROOT));
                iterData.put("summary", plan.getSummary());
                tokenTracker.trackIteration(iterData);
                AgenticEvents.emit(context, stepIndex,
                        goalAchieved ? "agentic_goal_achieved" : "agentic_goal_impossible",
                        Map.of("iteration", iteration, "summary", plan.getSummary()));
                break;
            }

            // Cancelled while reasoning: neither ask for approval nor start the action
            if (context.shouldStop()) break;

            // === Optional APPROVAL gate ===
            if (approvalRequired && !plan.getAction().isEmpty()) {
                AgenticApprovalGate.Decision decision = approvalGate.ask(context, stepIndex, iteration, plan, iterData,
                        fullOutput, history);
                if (decision == AgenticApprovalGate.Decision.STOP) break;
                if (decision == AgenticApprovalGate.Decision.RETRY_ITERATION) {
                    tokenTracker.trackIteration(iterData);
                    continue;
                }
                if (decision == AgenticApprovalGate.Decision.PROCEED_AND_DISABLE) {
                    approvalRequired = false;
                }
            }

            // === ACT phase ===
            if (plan.getAction().isEmpty()) {
                history.add(new Turn("user",
                        "Your response did not contain a clear action. Please specify an ACTION to execute."));
                iterData.put("result", "no_action");
                tokenTracker.trackIteration(iterData);
                continue;
            }

            AgenticActionRunner.ActOutcome act = actionRunner.run(context, stepIndex, iteration, plan.getAction(),
                    provider, model, effort, cfg, tokenTracker, iterData);
            String action = act.action();
            String actionOutput = act.output();
            int exitCode = act.exitCode();

            // Track tokens and check for blind iterations
            tokenTracker.trackIteration(iterData);

            String blindReason = tokenTracker.checkBlindIterations();
            if (blindReason != null) {
                stopReason = blindReason;
                int blind = tokenTracker.getConsecutiveBlindIterations();
                AgenticEvents.emit(context, stepIndex, "agentic_token_tracking_failed",
                        Map.of("iteration", iteration, "consecutiveBlind", blind));
                fullOutput.append("[TOKEN TRACKING FAILED: ").append(blind)
                        .append(" consecutive iterations without real usage data — using fallback estimates, "
                                + "budget enforcement is approximate]\n");
                break;
            }

            iterData.put("action", action);
            iterData.put("actionOutput", TruncationUtil.head(actionOutput, TRUNCATE_LONG, keepWhole));
            iterData.put("exitCode", exitCode);

            AgenticEvents.emit(context, stepIndex, "agentic_acted",
                    Map.of("iteration", iteration,
                            "action", TruncationUtil.head(action, TRUNCATE_MEDIUM, keepWhole),
                            "output", TruncationUtil.head(actionOutput, TRUNCATE_LONG, keepWhole),
                            "exitCode", exitCode));

            // === OBSERVE phase ===
            String observation = "Action result (exit code " + exitCode + "):\n" + actionOutput;
            history.add(new Turn("user",
                    "Observation from executing your action:\n"
                            + TruncationUtil.head(observation, TRUNCATE_OBSERVATION, keepWhole)
                            + "\n\nAnalyze this result. Has the goal been achieved? "
                            + "If not, what should we do next?"));

            // recordOutput: in a tainted run the action output is shell-escaped downstream
            context.recordOutput(null, actionOutput);

            fullOutput.append("[iteration ").append(iteration).append("]\n")
                    .append("  Reasoning: ")
                    .append(TruncationUtil.head(plan.getRationale(), TRUNCATE_SHORT, keepWhole)).append("\n")
                    .append("  Action: ").append(TruncationUtil.head(plan.getAction(), TRUNCATE_SHORT, keepWhole))
                    .append("\n")
                    .append("  Exit code: ").append(exitCode).append("\n")
                    .append("  Result: ").append(TruncationUtil.head(actionOutput, TRUNCATE_MEDIUM, keepWhole))
                    .append("\n\n");

            long iterMs = clock.now().toEpochMilli() - iterStart.toEpochMilli();
            iterData.put("durationMs", iterMs);

            String stop = stopRules.afterAct(context, stepIndex, iteration, iterStart, action, cfg, tokenTracker,
                    safetyGuard, fullOutput);
            if (stop != null) {
                stopReason = stop;
                break;
            }

            promptBuilder.compressHistory(history, keepWhole);
        }

        // Finalize result
        Instant end = clock.now();
        StepResult result = StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Agentic] " + cfg.goal())
                .output(fullOutput.toString().trim())
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .status(goalAchieved ? StepStatus.COMPLETED : StepStatus.FAILED)
                .build();

        Map<String, Object> metadata = tokenTracker.buildFinalMetadata(
                cfg.goal(), cfg.successCriteria(), iteration, goalAchieved, stopReason,
                model, provider.getId());
        // a provider that ignores an effort (no CLI flag) does not claim one
        Map<String, Object> aiRequest = StepAiMetadata.aiRequest(provider.getId(), model,
                capabilities.supportsEffort() ? effort : null);
        // pi always reasons with a --thinking level; without an effort its maxTurns heuristic picks it
        if (reasonThinking != null) aiRequest.put("reasonThinking", reasonThinking);
        metadata.put(StepAiMetadata.AI_REQUEST, aiRequest);
        result.setMetadata(metadata);

        context.setVariable("agentic_goal_achieved", goalAchieved);
        context.setVariable("agentic_iterations", iteration);

        AgenticEvents.emit(context, stepIndex, "agentic_completed", Map.of(
                "goalAchieved", goalAchieved,
                "totalIterations", iteration,
                "durationMs", result.getDurationMs()));

        return result;
    }

    /** The step of a block whose provider the run does not know: failed before any call, with the reason. */
    private static StepResult refused(int stepIndex, Instant start, String goal, String reason, Clock clock) {
        Instant end = clock.now();
        return StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Agentic] " + goal)
                .output("Error: " + reason)
                .status(StepStatus.FAILED)
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .metadata(new LinkedHashMap<>(Map.of("error", reason)))
                .build();
    }
}
