package traceworks.core.port;

import java.util.regex.Pattern;

/** Port to the generation of ids, so tests can fix them. */
public interface Ids {

    /**
     * The shape of a run id wherever it names a folder or a file (a checkout, a ledger entry): a letter or digit, then
     * letters, digits, {@code .}, {@code _} and {@code -}. So no run id is a path, an option or a hidden name.
     */
    Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Whether {@code id} has the shape of a run id ({@link #RUN_ID}). */
    static boolean isRunId(String id) {
        return id != null && RUN_ID.matcher(id).matches();
    }

    /** A new, random run id (a UUID). */
    String newRunId();

    /** A new, random id of a request to a run, such as an answer (a UUID, like a run id). */
    default String newRequestId() {
        return newRunId();
    }
}
