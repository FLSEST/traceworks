package traceworks.core.port;

import traceworks.core.util.Message;


/** A run reference is a prefix shorter than {@link RunStore#MIN_PREFIX} characters, so it names no run safely. */
public final class RunReferenceTooShortException extends IllegalArgumentException implements Message.Carrier {

    private final transient Message parts;


    public RunReferenceTooShortException(String runRef) {
        super("A run reference needs at least " + RunStore.MIN_PREFIX + " characters: '" + runRef + "'");
        this.parts = Message.of("A run reference needs at least " + RunStore.MIN_PREFIX + " characters: '",
                Message.typed(runRef), "'");
    }

    @Override
    public Message parts() {
        return parts;
    }
}
