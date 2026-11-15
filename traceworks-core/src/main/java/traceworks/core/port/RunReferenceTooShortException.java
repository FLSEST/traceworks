package traceworks.core.port;

/** A run reference is a prefix shorter than {@link RunStore#MIN_PREFIX} characters, so it names no run safely. */
public final class RunReferenceTooShortException extends IllegalArgumentException {

    public RunReferenceTooShortException(String runRef) {
        super("A run reference needs at least " + RunStore.MIN_PREFIX + " characters: '" + runRef + "'");
    }
}
