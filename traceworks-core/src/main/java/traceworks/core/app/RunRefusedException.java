package traceworks.core.app;

import traceworks.core.util.Message;

/**
 * A use case refuses to act on a run, or to start one: the start check fails, a host run lacks the operator's
 * consent to resume, the package is invalid, an input is reserved, or the run is not in a state to resume or cancel.
 * The message is a short English reason for the person who asked.
 */
public class RunRefusedException extends RuntimeException implements Message.Carrier {

    private final transient Message parts;

    public RunRefusedException(String reason) {
        super(reason);
        this.parts = null;
    }

    /** A refusal that says which parts of its reason quote a value from outside; its message is the plain text. */
    public RunRefusedException(Message reason) {
        super(reason.text());
        this.parts = reason;
    }

    @Override
    public Message parts() {
        return parts;
    }
}
