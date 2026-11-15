package traceworks.core.register;

import traceworks.core.util.UntrustedText;

import java.util.regex.Pattern;

/**
 * {@code {{ previous_report }}}: the last valid report of the register, defused for the next model: the envelope cut
 * (machine JSON of the old snapshot, and the part a model would echo with its marker), every other marker without its
 * colon, at most {@value #MAX_CHARS} characters.
 */
public final class PreviousReport {

    public static final String NONE = "(no previous report)";
    public static final int MAX_CHARS = 3000;
    static final String TRUNCATED = "\n[...truncated]";

    /** An envelope as the parser sees one begin, to its end or the end of the text. */
    private static final Pattern ENVELOPE =
            Pattern.compile("<!--\\s*" + AuditResultParser.MARKER_REGEX + "\\s*\\{.*?(?:-->|\\z)", Pattern.DOTALL);

    private PreviousReport() {
    }

    /** The report as the next run gets it; {@link #NONE} for no report or one that was nothing but an envelope. */
    public static String defused(String report) {
        if (report == null) {
            return NONE;
        }
        String text = ENVELOPE.matcher(report).replaceAll("");
        if (text.isBlank()) {
            return NONE;
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + TRUNCATED;
        }
        return UntrustedText.defuseLines(text);
    }
}
