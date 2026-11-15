package traceworks.core.model;

/** The status of a workflow run. */
public enum RunStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED,
    /** The process ended while the run was active; the run can be resumed. */
    INTERRUPTED,
    /**
     * The run waits for a person outside the process: it stopped at a top-level approval or human input that nobody
     * could answer in the process; {@code traceworks resume} continues it.
     */
    PARKED
}
