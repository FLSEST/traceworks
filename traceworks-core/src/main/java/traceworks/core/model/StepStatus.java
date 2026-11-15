package traceworks.core.model;

/**
 * The status of one step of a run.
 *
 * <p>{@link #CANCELLED}: the step was running (or waiting) when the run's cancel took effect, and it did not complete.
 * It is finished (it has an end) but neither a failure (no failure count, no gate, no "failed step") nor a success
 * (its output is no completed result). Appended last.
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    /** The step waits for an approval or a human input. */
    WAITING_APPROVAL,
    /** The run's cancel ended the step before it completed. */
    CANCELLED
}
