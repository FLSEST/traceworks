package traceworks.core.app;

import traceworks.core.util.Message;


/**
 * A use case refuses to act on a run because its record does not hold: a blob it reads has other content than the
 * record names, the chain does not verify, or a line cannot be read. The message names what is wrong and points to
 * {@code traceworks verify}.
 */
public final class RecordDamagedException extends RunRefusedException {

    public RecordDamagedException(String reason) {
        super(reason);
    }

    /** A reason that says which of its parts quote a value from outside. */
    public RecordDamagedException(Message reason) {
        super(reason);
    }

    /** The one-line pointer to the command that says what is wrong with the record. */
    static String seeVerify(String runId) {
        return "; check the record with traceworks verify " + runId;
    }
}
