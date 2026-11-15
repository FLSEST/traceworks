package traceworks.core.app;

/**
 * A request (an answer or a cancel) went into the inbox of a running run, and the run's process has not recorded it
 * within the wait. Nothing is known yet: the process may still carry it out or refuse it, and the record will say
 * which. The message names the request and the run.
 */
public final class RequestQueuedException extends RuntimeException {

    public RequestQueuedException(String message) {
        super(message);
    }
}
