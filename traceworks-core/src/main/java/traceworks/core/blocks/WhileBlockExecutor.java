package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.context.OutputBudget;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.GateSources;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code while}: runs its {@code command} as long as {@code condition_command} holds, at most
 * {@code max_iterations} times (default 100); without a condition exactly that often. In {@code shell} mode the
 * condition holds while its exit code is 0; one whose output went beyond the runner's limit, that timed out or that
 * did not run (the runner's mark) ends the loop and fails the step, since its exit code is no answer. In {@code ai} mode a model is asked, and an answer of {@code false},
 * {@code 0} or nothing ends the loop; a failed evaluation follows {@code on_condition_error} ({@link AiCondition}). A
 * failed iteration ends the loop and fails the step. Each iteration moves {@code previous_output}. A loop the run's
 * cancel cut short (it did not end by its condition or its limit) is {@code CANCELLED}, and so is an iteration the
 * cancel ended ({@link traceworks.core.engine.CancelledSteps}); a condition the cancel killed is no
 * answer.
 *
 * <p>The outputs it gathers count against the run's {@link OutputBudget}: an iteration whose output would take the run
 * beyond it stays out of the step's output (and out of {@code previous_output}), the loop stops, and the step fails
 * with the budget's note after what it gathered; its output then sets no gate.
 *
 * <p>{@code execution_mode} is required ({@link ExecutionMode#isMissing}): a workflow states whether a step asks
 * a model.
 */
public class WhileBlockExecutor implements BlockExecutor {

    private static final int MAX_ITERATIONS = 100;

    private final WrapperCommands commands;

    public WhileBlockExecutor() {
        this(new WrapperCommands());
    }

    WhileBlockExecutor(WrapperCommands commands) {
        this.commands = commands;
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        StepResult missingMode = RequiredExecutionMode.missing(block, context);
        if (missingMode != null) {
            return missingMode;
        }
        Map<String, Object> config = block.getConfig();
        BlockSettings settings = BlockSettings.of(block);
        String command = settings.text("command");
        String conditionCommand = settings.text("condition_command");
        int maxIterations = settings.number("max_iterations", MAX_ITERATIONS);
        boolean isShell = ExecutionMode.isShell(config);
        InnerStep body = InnerStep.of(block, isShell, null);

        StepResult aggregateResult = new StepResult();
        aggregateResult.setStepIndex(context.nextStepIndex());
        aggregateResult.setStartedAt(context.getClock().now());
        aggregateResult.setInput("while loop (max " + maxIterations + " iterations)");

        OutputBudget budget = context.getOutputBudget();
        GatheredOutput outputs = new GatheredOutput(budget);
        List<Map<String, Object>> recordedIterations = new ArrayList<>();
        int iteration = 0;
        // Every body run, the failed one included; the recorded list stops at MAX_RECORDED_ITEMS
        int executedIterations = 0;
        AiCondition failedCondition = null;
        // Whether the loop ended by its condition or its limit, not by the cancel
        boolean endedItself = false;

        while (iteration < maxIterations && !context.shouldStop()) {
            if (conditionCommand != null) {
                String resolvedCondition = body.resolve(context, conditionCommand);
                if (isShell) {
                    // Shell mode: exit code 0 = go on, anything else = stop; a timeout, a cut output or a condition
                    // that did not run fails the block
                    ShellResult evaluated = commands.runCommand(resolvedCondition, body.shellTimeout(), context);
                    if (evaluated.timedOut() || evaluated.truncated() || evaluated.didNotRun()) {
                        outputs.note("[condition] " + (evaluated.timedOut()
                                        ? WrapperCommands.conditionTimedOut(body.shellTimeout())
                                        : evaluated.didNotRun() ? WrapperCommands.conditionNotRun(evaluated)
                                        : WrapperCommands.conditionFailed(evaluated)),
                                context.secretValues());
                        aggregateResult.setStatus(StepStatus.FAILED);
                        break;
                    }
                    if (evaluated.exitCode() != 0) {
                        endedItself = !context.shouldStop();   // a condition the cancel killed is no answer
                        break;
                    }
                } else {
                    // The first AI call of the block carries provider and model, even if the body never runs
                    AiCondition condition = AiCondition.ask(commands, AiStepRequest.builder()
                            .workingDirectory(context.getProjectPath())
                            .prompt(resolvedCondition)
                            .model(context.modelFor(block))
                            .timeoutSeconds(body.aiTimeout())
                            .providerId(context.providerFor(block))
                            .effort(context.effortFor(block))
                            .build(), aggregateResult, context);
                    if (condition.failed()) {
                        failedCondition = condition;
                        break;
                    }
                    String answer = condition.answer().trim();
                    if ("false".equalsIgnoreCase(answer) || "0".equals(answer) || answer.isEmpty()) {
                        endedItself = !context.shouldStop();
                        break;
                    }
                }
            }

            StepResult stepResult = body.run(commands, context, command);

            executedIterations++;
            boolean fits = outputs.add("[iteration ", String.valueOf(iteration + 1), "]: ", stepResult.getOutput(),
                    "\n");

            // recordOutput: in a tainted run the iteration output is shell-escaped downstream
            if (fits) {
                context.recordOutput(null, stepResult.getOutput());
            }
            StepAiMetadata.carryAi(aggregateResult, stepResult);
            GateSources.carry(aggregateResult, stepResult);
            if (recordedIterations.size() < ForEachBlockExecutor.MAX_RECORDED_ITEMS) {
                recordedIterations.add(Map.of(
                        "index", iteration,
                        "status", stepResult.getStatus().name(),
                        "durationMs", stepResult.getDurationMs() != null ? stepResult.getDurationMs() : 0L));
            }

            if (!fits) {
                // The iteration's output is not in the step's output, so its gate lines can no longer be read whole
                GateSources.noGateSource(aggregateResult);
                break;
            }
            if (stepResult.getStatus() == StepStatus.FAILED) {
                aggregateResult.setStatus(StepStatus.FAILED);
                break;
            }
            if (stepResult.getStatus() == StepStatus.CANCELLED) {
                break;   // the cancel ended this iteration: it does not count towards the limit
            }

            iteration++;
        }
        endedItself |= iteration >= maxIterations;

        if (failedCondition != null) {
            // on_condition_error: the step fails (default, and always for an unknown provider), is skipped, or the
            // loop just ends
            AiCondition.OnError onError = failedCondition.onError(config.get("on_condition_error"),
                    context.shouldStop());
            if (onError != AiCondition.OnError.FALSE) {
                outputs.note("[condition] " + failedCondition.describe(onError), context.secretValues());
                failedCondition.settle(aggregateResult, onError);
            }
        }
        aggregateResult.setOutput(outputs.text());
        StepAiMetadata.put(aggregateResult, "iterationCount", executedIterations);
        StepAiMetadata.put(aggregateResult, "iterations", List.copyOf(recordedIterations));
        if (aggregateResult.getStatus() == null || aggregateResult.getStatus() == StepStatus.PENDING) {
            aggregateResult.setStatus(StepStatus.COMPLETED);
        }
        if (context.shouldStop() && !endedItself) {
            aggregateResult.setStatus(StepStatus.CANCELLED);
        }
        if (budget.exceeded()) {
            budget.fail(aggregateResult);
        }
        aggregateResult.setCompletedAt(context.getClock().now());
        aggregateResult.setDurationMs(
                aggregateResult.getCompletedAt().toEpochMilli() - aggregateResult.getStartedAt().toEpochMilli());
        return aggregateResult;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("while");
    }
}
