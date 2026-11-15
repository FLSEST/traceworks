package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.time.Instant;

/**
 * {@code conditional}, {@code retry}, {@code while} and {@code foreach} must write out their {@code execution_mode}
 * ({@link ExecutionMode#isMissing}). A missing value read as AI would make a condition ask a model although the
 * workflow never said so; the run admission refuses the workflow, and a block that gets here anyway fails before it
 * runs anything.
 */
final class RequiredExecutionMode {

    private RequiredExecutionMode() {
    }

    /**
     * The failed step of a block without the {@code execution_mode} it needs, with
     * {@link ExecutionMode#REQUIRED_MESSAGE} as its output; {@code null} when the block writes the value out or needs
     * none.
     */
    static StepResult missing(BlockDefinition block, ExecutionContext context) {
        if (!ExecutionMode.isMissing(block.getType(), block.getConfig())) {
            return null;
        }
        Instant now = context.getClock().now();
        return StepResult.builder()
                .stepIndex(context.nextStepIndex())
                .status(StepStatus.FAILED)
                .output(ExecutionMode.REQUIRED_MESSAGE)
                .startedAt(now)
                .completedAt(now)
                .durationMs(0L)
                .build();
    }
}
