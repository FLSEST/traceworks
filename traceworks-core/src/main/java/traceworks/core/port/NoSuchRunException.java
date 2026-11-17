package traceworks.core.port;

import traceworks.core.util.Message;


/** No run matches a run reference (an id, a prefix or a folder path). */
public final class NoSuchRunException extends IllegalArgumentException implements Message.Carrier {

    private final transient Message parts;


    public NoSuchRunException(String runRef) {
        super("No run matches '" + runRef + "'");
        this.parts = Message.of("No run matches '", Message.typed(runRef), "'");
    }

    /** No run of the home matches, and {@code hint} says what the reference may have meant. */
    public NoSuchRunException(String runRef, String hint) {
        super("No run matches '" + runRef + "': " + hint);
        this.parts = Message.of("No run matches '", Message.typed(runRef), "': ", Message.typed(hint));
    }

    @Override
    public Message parts() {
        return parts;
    }
}
