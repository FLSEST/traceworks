package traceworks.core.app;

/**
 * A use case refuses to act on a run, or to start one: the start check fails, a host run lacks the operator's
 * consent to resume, the package is invalid, an input is reserved, or the run is not in a state to resume or cancel.
 * The message is a short English reason for the person who asked.
 */
public class RunRefusedException extends RuntimeException {

    public RunRefusedException(String reason) {
        super(reason);
    }
}
