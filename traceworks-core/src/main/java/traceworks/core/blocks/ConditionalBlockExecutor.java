package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.ShellValues;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.CancelledSteps;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.GateSources;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code conditional}: evaluates its {@code condition} and runs {@code then_command} when it holds,
 * {@code else_command} otherwise; a missing branch skips the block. In {@code shell} mode the condition is a command
 * whose exit code 0 means true; one whose output went beyond the runner's limit, that timed out or that did not run
 * (the runner's mark) fails the block, since its exit code is no answer. In {@code ai} mode a model is asked for TRUE or FALSE; a failed
 * evaluation follows {@code on_condition_error} ({@link AiCondition}). A condition or branch the run's cancel ended
 * makes the step {@code CANCELLED} ({@link CancelledSteps}): a shell condition killed by the cancel is no answer, so
 * neither branch runs.
 *
 * <p>{@code execution_mode} is required ({@link ExecutionMode#isMissing}): a workflow states whether a step asks
 * a model.
 */
public class ConditionalBlockExecutor implements BlockExecutor {

    private final WrapperCommands commands;

    public ConditionalBlockExecutor() {
        this(new WrapperCommands());
    }

    ConditionalBlockExecutor(WrapperCommands commands) {
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
        boolean isShell = ExecutionMode.isShell(config);
        String rawCondition = settings.text("condition");
        String thenCommand = settings.text("then_command");
        String elseCommand = settings.text("else_command");
        // Made first: a branch it cannot run is refused before the condition runs
        InnerStep branch = InnerStep.of(block, isShell, null);
        // Shell mode: same rule as shell, while and retry (untrusted values always single-quoted, every value with
        // shell_escape_variables). AI mode stays raw: a prompt, no shell.
        String condition = branch.resolve(context, rawCondition);

        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(context.getClock().now());

        boolean conditionMet;
        String evaluationNote = "";
        if (isShell) {
            // Shell mode: exit code 0 = true, anything else = false; a timeout, a cut output or a condition that
            // did not run is no answer
            ShellResult evaluated = commands.runCommand(condition, branch.shellTimeout(), context,
                    ShellValues.resolve(config, context));
            if (context.shouldStop()) {
                result.setInput("if (" + condition + ") → cancelled");
                result.setOutput(CancelledSteps.WHILE_RUNNING);
                result.setStatus(StepStatus.CANCELLED);
                return finish(result, context);
            }
            if (evaluated.timedOut() || evaluated.truncated() || evaluated.didNotRun()) {
                result.setInput("if (" + condition + ") → evaluation failed");
                result.setOutput(evaluated.timedOut() ? WrapperCommands.conditionTimedOut(branch.shellTimeout())
                        : evaluated.didNotRun() ? WrapperCommands.conditionNotRun(evaluated)
                        : WrapperCommands.conditionFailed(evaluated));
                result.setStatus(StepStatus.FAILED);
                return finish(result, context);
            }
            conditionMet = evaluated.exitCode() == 0;
        } else {
            // AI mode: the model answers TRUE or FALSE. The first AI call of the block carries provider and model,
            // even when no branch runs
            AiCondition evaluation = AiCondition.ask(commands, AiStepRequest.builder()
                    .workingDirectory(context.getProjectPath())
                    .prompt("Evaluate this condition and respond with ONLY the word TRUE or FALSE, nothing else: "
                            + condition)
                    .model(context.modelFor(block))
                    .timeoutSeconds(branch.aiTimeout())
                    .providerId(context.providerFor(block))
                    .effort(context.effortFor(block))
                    .build(), result, context);
            if (evaluation.failed()) {
                // on_condition_error: the step fails (default, and always for an unknown provider), is skipped, or
                // takes the else branch
                AiCondition.OnError onError = evaluation.onError(config.get("on_condition_error"),
                        context.shouldStop());
                if (onError != AiCondition.OnError.FALSE) {
                    result.setInput("if (" + condition + ") → evaluation failed");
                    result.setOutput(evaluation.describe(onError));
                    evaluation.settle(result, onError);
                    return finish(result, context);
                }
                evaluationNote = " (evaluation failed, on_condition_error: false)";
            }
            String trimmed = evaluation.failed() ? "" : evaluation.answer().trim().toUpperCase(Locale.ROOT);
            conditionMet = trimmed.startsWith("TRUE");
        }

        String commandToExecute = conditionMet ? thenCommand : elseCommand;
        result.setInput("if (" + condition + ") → " + conditionMet + evaluationNote);
        StepAiMetadata.put(result, "conditionMet", conditionMet);

        if (commandToExecute != null) {
            StepResult stepResult = branch.run(commands, context, commandToExecute);
            result.setOutput(stepResult.getOutput());
            result.setStatus(stepResult.getStatus());
            StepAiMetadata.carryAi(result, stepResult);
            GateSources.carry(result, stepResult);
        } else {
            result.setOutput("No " + (conditionMet ? "then" : "else") + " branch defined, skipping");
            result.setStatus(StepStatus.SKIPPED);
        }

        return finish(result, context);
    }

    private static StepResult finish(StepResult result, ExecutionContext context) {
        CancelledSteps.mark(result, context);
        result.setCompletedAt(context.getClock().now());
        result.setDurationMs(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
        return result;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("conditional");
    }
}
