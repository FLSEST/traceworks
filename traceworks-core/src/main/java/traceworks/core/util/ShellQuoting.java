package traceworks.core.util;

/**
 * How Traceworks quotes a value for a POSIX shell: in single quotes, with each {@code '} inside written as
 * {@code '\''}. The one copy: a shell placeholder quotes with it, and so do the viewers' copy-paste commands. A secret
 * value holds no {@code '} ({@code Redactor#problem}), so the quoted form of a text holds each value as it is.
 */
public final class ShellQuoting {

    private ShellQuoting() {
    }

    /** {@code value} in single quotes, each {@code '} written as {@code '\''}; {@code ''} for {@code null}. */
    public static String quote(String value) {
        return value == null ? "''" : "'" + insideQuotes(value) + "'";
    }

    /** {@code value} as {@link #quote} writes it between the quotes: each {@code '} as {@code '\''}. */
    public static String insideQuotes(String value) {
        return value.replace("'", "'\\''");
    }
}
