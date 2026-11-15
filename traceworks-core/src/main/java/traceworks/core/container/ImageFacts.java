package traceworks.core.container;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an image says about itself, read from its Dockerfile, so a run can show what a container holds: the base it
 * starts from and the description its author gave it. The record keeps these facts for every image a run may use,
 * since a package names its images as it likes and a name says little.
 *
 * @param reference   the image as a step names it: {@code ./images/<name>}, a built-in name or a registry reference
 * @param base        the image of the last {@code FROM} line, without its digest and at most
 *                    {@value #DESCRIPTION_LIMIT} characters; {@code null} without a Dockerfile
 * @param description the OCI label {@value #DESCRIPTION_LABEL}, on one line and at most {@value #DESCRIPTION_LIMIT}
 *                    characters; {@code null} when the Dockerfile has none
 */
public record ImageFacts(String reference, String base, String description) {

    /** The label a Dockerfile says what its image holds with; the OCI standard name. */
    public static final String DESCRIPTION_LABEL = "org.opencontainers.image.description";

    /** The most characters of a description, and of a base, the record keeps. */
    public static final int DESCRIPTION_LIMIT = 200;

    /**
     * The flags are matched possessively ({@code *+}), here and in the quoted value of {@link #PAIR}: the engine
     * then keeps no way back into the group, and without that it takes a stack frame per repetition, so a line of a
     * package's Dockerfile with thousands of them overflowed the stack.
     */
    private static final Pattern FROM = Pattern.compile("(?i)^\\s*FROM(?:\\s+--\\S+)*+\\s+(\\S+)(?:\\s+AS\\s+\\S+)?\\s*$");
    private static final Pattern LABEL = Pattern.compile("(?i)^\\s*LABEL\\s+(.*)$");
    /** One {@code key=value} of a LABEL line: the value double-quoted, single-quoted or bare. */
    private static final Pattern PAIR = Pattern.compile("([^\\s=]+)=(?:\"((?:[^\"\\\\]|\\\\.)*+)\"|'([^']*)'|(\\S+))");

    /** The facts of {@code reference} from {@code dockerfile}; with {@code null}, only the reference is known. */
    public static ImageFacts of(String reference, String dockerfile) {
        if (dockerfile == null) {
            return new ImageFacts(reference, null, null);
        }
        String base = null;
        String description = null;
        for (String line : dockerfile.split("\\R")) {
            Matcher from = FROM.matcher(line);
            if (from.matches()) {
                String image = from.group(1);
                int digest = image.indexOf('@');
                base = oneLine(digest < 0 ? image : image.substring(0, digest));
                continue;
            }
            Matcher label = LABEL.matcher(line);
            if (label.matches()) {
                Matcher pair = PAIR.matcher(label.group(1));
                while (pair.find()) {
                    if (pair.group(1).toLowerCase(Locale.ROOT).equals(DESCRIPTION_LABEL)) {
                        String value = pair.group(2) != null ? pair.group(2).replace("\\\"", "\"")
                                : pair.group(3) != null ? pair.group(3) : pair.group(4);
                        description = oneLine(value);
                    }
                }
            }
        }
        return new ImageFacts(reference, base, description);
    }

    private static String oneLine(String value) {
        String text = value.replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= DESCRIPTION_LIMIT ? text : text.substring(0, DESCRIPTION_LIMIT - 1) + "…";
    }
}
