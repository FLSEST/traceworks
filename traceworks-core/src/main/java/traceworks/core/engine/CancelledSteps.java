package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

/**
 * When a step counts as ended by the run's cancel ({@link StepStatus#CANCELLED}): the run's cancel is requested
 * ({@link ExecutionContext#shouldStop()}, which a pause or a park never sets) and the step did not complete. A
 * command the cancel killed, an AI call it aborted, a wait it ended: each returns {@code FAILED}, and that result
 * becomes {@code CANCELLED} here. A step that completed or was skipped keeps its status although a cancel came. A
 * timeout or an output-limit kill without a cancel stays {@code FAILED}.
 *
 * <p>The engine applies it to every top-level step ({@link BlockStepRunner}); the wrappers apply it to their inner
 * steps, so a cancelled item, iteration, attempt or case block is never counted as a failure.
 */
public final class CancelledSteps {

    /** The output of a step whose executor stopped with an exception after the run's cancel. */
    public static final String WHILE_RUNNING = "Cancelled: the run was cancelled while this step ran";

    private CancelledSteps() {
    }

    /**
     * The output of a step whose executor stopped with {@code thrown} after the run's cancel: {@value #WHILE_RUNNING}.
     * An exception the cancel usually caused (a killed stream, an interruption, a {@code CancellationException}) adds
     * nothing. A JVM {@link Error} ({@code OutOfMemoryError}, {@code StackOverflowError}) is no consequence of a
     * cancel, so a line {@code Ended by <class name>: <message>} follows, without a stack trace; only the class name
     * when the message is null or blank.
     */
    public static String whileRunning(Throwable thrown) {
        if (!(thrown instanceof Error)) {
            return WHILE_RUNNING;
        }
        String message = thrown.getMessage();
        return WHILE_RUNNING + "\nEnded by " + thrown.getClass().getName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** Whether the run's cancel ended {@code result}'s work: it is {@code FAILED} while the cancel is requested. */
    public static boolean endedByCancel(StepResult result, ExecutionContext context) {
        return result.getStatus() == StepStatus.FAILED && context.shouldStop();
    }

    /** Sets {@code result} {@code CANCELLED} when the run's cancel ended its work ({@link #endedByCancel}). */
    public static StepResult mark(StepResult result, ExecutionContext context) {
        if (endedByCancel(result, context)) {
            result.setStatus(StepStatus.CANCELLED);
        }
        return result;
    }
}
