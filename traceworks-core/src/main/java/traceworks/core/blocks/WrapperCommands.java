package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.ShellValues;
import traceworks.core.engine.GateSources;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner.CommandRequest;

import java.util.List;
import java.util.Map;

/**
 * How the wrapper blocks ({@code retry}, {@code conditional}, {@code switch}, {@code while}, {@code foreach}) run
 * commands: their inner shell and AI steps, a shell condition or {@code items_command}, and an AI question whose answer
 * they read. Commands go through the run's {@link traceworks.core.port.CommandRunner}, AI calls through
 * {@link AiStepCall}.
 */
class WrapperCommands {

    private final AiStepCall aiCall;

    WrapperCommands() {
        this(new AiStepCall());
    }

    WrapperCommands(AiStepCall aiCall) {
        this.aiCall = aiCall;
    }

    /**
     * Runs an inner shell step ({@link InnerStep#shellBlock}) where a {@code shell} block would run it
     * ({@link ShellTarget}): with {@code container_image} in that image, without one where the run's runner runs
     * image-less commands. Unlike a {@code shell} block, the step adds no recovery hint and no {@code checked_command}
     * gate. A step whose output the limit cut is no gate source ({@link GateSources#noGateSource}).
     *
     * @param shellBlock     a block whose config holds the resolved {@code command}
     * @param stepIndex      the index of the step
     * @param timeoutSeconds how long the command may run
     * @throws WorkflowExecutionException without a command, for a malformed mount, for
     *                                    {@code mount_docker_socket: true} or for a secret without a value, before
     *                                    anything runs
     */
    StepResult shellStep(BlockDefinition shellBlock, int stepIndex, int timeoutSeconds, ExecutionContext context) {
        Map<String, Object> config = shellBlock.getConfig();
        BlockSettings settings = BlockSettings.of(shellBlock);
        String command = settings.text("command");
        if (command == null) {
            throw new WorkflowExecutionException("Block has no 'command' in config");
        }
        // The mounts were resolved when the shell block was made; resolving them again would expand a value twice
        List<String> mounts = settings.texts("mounts", List.of());
        ShellTarget target = ShellTarget.of(config, () -> mounts, context.isContainerRun(), context.getSecrets());

        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(context.getClock().now());
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);
        // The values were filled in when the shell block was made, like the mounts
        ShellResult result = ShellResult.of(
                context.getCommandRunner().run(target.request(command, context.getProjectPath(), timeoutSeconds)
                        .withValues(ShellValues.resolved(config))),
                timeoutSeconds, context.secretValues());
        step.setOutput(result.output());
        step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setMetadata(Map.of("exitCode", result.exitCode(), "executionMode", target.executionMode()));
        step.setCompletedAt(context.getClock().now());
        step.setDurationMs(result.durationMs());
        if (result.truncated()) {
            GateSources.noGateSource(step);   // the wrapper carries it (GateSources#carry)
        }
        return step;
    }

    /**
     * Runs a command whose exit code or output the block reads itself: a shell condition or an {@code items_command}.
     * It runs in the project without an image and without network, whatever the block's {@code container_image}: in
     * the run's {@code shell} image in a container run, on the host in a host run. A
     * {@linkplain ShellResult#truncated() truncated} result is no answer, nor is one that
     * {@linkplain ShellResult#didNotRun() did not run}: the block fails instead of reading it.
     *
     * @param values the shell variables the command finds set: the block's {@code values}, filled in
     */
    ShellResult runCommand(String command, int timeoutSeconds, ExecutionContext context, Map<String, String> values) {
        return ShellResult.of(context.getCommandRunner().run(
                new CommandRequest(command, context.getProjectPath(), timeoutSeconds, null, null, null, false)
                        .withValues(values)),
                timeoutSeconds, context.secretValues());
    }

    /** The output of a block whose shell condition ran out of time: the timeout line is no answer. */
    static String conditionTimedOut(int timeoutSeconds) {
        return "The condition failed: it timed out after " + timeoutSeconds + " seconds";
    }

    /** The output of a block whose shell condition did not run: the runner's refusal is no answer. */
    static String conditionNotRun(ShellResult condition) {
        return "The condition failed: it did not run: " + condition.notRunReason();
    }

    /** The output of a block whose shell condition went beyond the output limit. */
    static String conditionFailed(ShellResult condition) {
        return "The condition failed: " + condition.truncationNote();
    }

    /** Runs an inner AI step. */
    StepResult aiStep(AiStepRequest request, ExecutionContext context) {
        return aiCall.run(request, context);
    }

    /**
     * Asks the AI a question whose answer the block reads itself (a condition).
     *
     * @throws AiStepCall.ProviderNotAvailable when the run has no such provider
     * @throws WorkflowExecutionException      when the call fails
     */
    AiStepCall.Answer ask(AiStepRequest request, ExecutionContext context) {
        return aiCall.ask(request, context);
    }
}
