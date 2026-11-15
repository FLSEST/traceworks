package traceworks.core.engine;

/**
 * The run's event sink failed: something that happened in the run could not be recorded. The engine fails closed:
 * a failure during the run stops it ({@code FAILED}, error {@value #MESSAGE_PREFIX}...), and a failure while
 * recording the end of the run makes {@link WorkflowEngine#run} throw this exception, because a run whose end cannot
 * be recorded must not look finished.
 */
public class EventLogException extends RuntimeException {

    /** How the message of every {@code EventLogException}, and the error of a run it stopped, begins. */
    public static final String MESSAGE_PREFIX = "event log write failed: ";

    public EventLogException(Throwable cause) {
        super(MESSAGE_PREFIX + cause.getClass().getSimpleName() + ": "
                + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName()), cause);
    }
}
