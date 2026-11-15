package traceworks.core.port;

/** No run matches a run reference (an id, a prefix or a folder path). */
public final class NoSuchRunException extends IllegalArgumentException {

    public NoSuchRunException(String runRef) {
        super("No run matches '" + runRef + "'");
    }

    /** No run of the home matches, and {@code hint} says what the reference may have meant. */
    public NoSuchRunException(String runRef, String hint) {
        super("No run matches '" + runRef + "': " + hint);
    }
}
