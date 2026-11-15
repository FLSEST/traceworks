package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.CancelledSteps;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.util.Map;
import java.util.Set;

/**
 * {@code retry}: runs its {@code command} until it completes, at most {@code max_retries} + 1 times (default 3
 * retries), waiting {@code delay} seconds between attempts (default 5), doubled per attempt with
 * {@code exponential_backoff}. A run asked to stop (a cancel, Ctrl-C) ends the wait at once and starts no further
 * attempt: an attempt the cancel ended is not retried, and the step is {@code CANCELLED} unless an attempt completed
 * ({@link CancelledSteps}). The command is resolved once, so every attempt runs the same command. The step is the
 * last attempt's, with {@code attempts} and {@code maxAttempts} in its metadata.
 *
 * <p>{@code execution_mode} ({@code shell} or {@code ai}) is required ({@link ExecutionMode#isMissing}).
 */
public class RetryBlockExecutor implements BlockExecutor {

    private final WrapperCommands commands;

    public RetryBlockExecutor() {
        this(new WrapperCommands());
    }

    RetryBlockExecutor(WrapperCommands commands) {
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
        // In range (0 to 100, 0 to 3,600 s), so the attempts and the waits cannot wrap
        int maxRetries = settings.number("max_retries", 3);
        int delaySeconds = settings.number("delay", 5);
        boolean exponentialBackoff = settings.flag("exponential_backoff");
        String rawCommand = settings.text("command");
        InnerStep attemptStep = InnerStep.of(block, ExecutionMode.isShell(config), null);
        // Resolved once: every attempt runs the same command
        String command = attemptStep.resolve(context, rawCommand);

        StepResult lastResult = StepResult.builder()
                .stepIndex(context.nextStepIndex())
                .status(StepStatus.FAILED)
                .input(command)
                .output("No retry attempts executed")
                .build();

        int maxAttempts = Math.max(0, maxRetries) + 1;
        int attempts = 0;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (context.shouldStop()) break;

            // A cancel (or Ctrl-C) ends the wait at once, and no attempt starts after it
            if (attempt > 0 && !context.sleepUnlessStopped(
                    saturatedMillis(waitSeconds(delaySeconds, exponentialBackoff, attempt)))) {
                break;
            }

            attempts++;
            lastResult = attemptStep.runResolved(commands, context, command);

            if (lastResult.getStatus() == StepStatus.COMPLETED) {
                break;
            }
        }

        // The cancel cut the retries short: before the first attempt, during a wait, or in an attempt
        CancelledSteps.mark(lastResult, context);
        // Inner shell and AI results keep their own metadata (aiRequest, provider); add the retry facts
        StepAiMetadata.put(lastResult, "attempts", attempts);
        StepAiMetadata.put(lastResult, "maxAttempts", maxAttempts);
        return lastResult;
    }

    /**
     * The wait in seconds before attempt {@code attempt} (counted from 0): {@code delay}, doubled per attempt after the
     * first retry with {@code exponential_backoff}. It saturates at {@link Long#MAX_VALUE} instead of wrapping around,
     * and a negative delay waits nothing.
     */
    static long waitSeconds(int delaySeconds, boolean exponentialBackoff, int attempt) {
        long delay = Math.max(0, delaySeconds);
        int doublings = exponentialBackoff ? attempt - 1 : 0;
        if (delay == 0 || doublings <= 0) {
            return delay;
        }
        return doublings >= Long.numberOfLeadingZeros(delay) ? Long.MAX_VALUE : delay << doublings;
    }

    private static long saturatedMillis(long seconds) {
        return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("retry");
    }
}
