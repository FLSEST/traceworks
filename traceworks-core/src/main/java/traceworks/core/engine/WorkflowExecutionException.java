package traceworks.core.engine;

/** A run cannot go on: no executor for a block type, a sub-workflow that timed out, and so on. */
public class WorkflowExecutionException extends RuntimeException {

    public WorkflowExecutionException(String message) {
        super(message);
    }

    public WorkflowExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
