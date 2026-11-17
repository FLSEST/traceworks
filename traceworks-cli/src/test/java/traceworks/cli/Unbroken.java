package traceworks.cli;

/**
 * What a command printed, with every line that {@link TerminalText} broke for the terminal's width put together
 * again: what follows a {@code "|+"} at the start of a line follows the end of the line above. So a test of what
 * a command says does not depend on where an 80 column terminal ends, which the name of the temporary folder
 * would decide; the tests of the breaking itself
 * ({@code TerminalTextTest}, {@code PaddedTextTest} and those that name marked lines) read the output as it is.
 */
public final class Unbroken {

    private Unbroken() {
    }

    /** {@code printed} with its broken lines joined; the lines of a block and of a list stay as they are. */
    public static String of(String printed) {
        return printed.replaceAll("\\R *\\|\\+", "");
    }
}
