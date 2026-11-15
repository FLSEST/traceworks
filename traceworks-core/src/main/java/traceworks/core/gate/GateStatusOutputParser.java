package traceworks.core.gate;

import traceworks.core.model.GateStatus;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code gate_status}, {@code gate_category} and {@code gate_description} markers from block output
 * text. Stateless.
 */
public final class GateStatusOutputParser {

    private GateStatusOutputParser() {}

    /** One gate verdict as a step wrote it; category and description are {@code null} when absent. */
    public record ParsedGateStatus(GateStatus status, String category, String description) {}

    // foreach puts "[<item>]: " in front of every iteration output: a marker in the first line of an
    // iteration would otherwise not be at the start of the line.
    private static final String FOREACH_PREFIX = "(?:\\[[^\\]\\n]*\\]:\\s*)?";

    // Line-anchored patterns, tolerant of:
    // - Markdown headings (###), bold (**), backticks (`)
    // - Hyphens (Gate-Status) and underscores (gate_status)
    // - Trailing text after the value
    // UNIX_LINES: only a line feed starts a line. Without it ^ also matches after U+2028, U+2029, U+0085 and a lone
    // carriage return, where no terminal shows a new line, so a marker hidden in the middle of a line would count.
    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "^" + FOREACH_PREFIX + "(?:#{1,6}\\s+)?[*`]*gate[_-]status[*`]*:\\s*[*`]*(RED|YELLOW|GREEN)[*`]*(?:\\s.*)?$",
            Pattern.MULTILINE | Pattern.UNIX_LINES | Pattern.CASE_INSENSITIVE);

    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "^" + FOREACH_PREFIX + "(?:#{1,6}\\s+)?[*`]*gate[_-]category[*`]*:\\s*[*`]*(.+?)[*`]*\\s*$",
            Pattern.MULTILINE | Pattern.UNIX_LINES | Pattern.CASE_INSENSITIVE);

    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile(
            "^" + FOREACH_PREFIX + "(?:#{1,6}\\s+)?[*`]*gate[_-]description[*`]*:\\s*[*`]*(.+?)[*`]*\\s*$",
            Pattern.MULTILINE | Pattern.UNIX_LINES | Pattern.CASE_INSENSITIVE);

    /**
     * Parses gate status markers from block output text.
     *
     * @param output the raw block output text
     * @return parsed gate status if a valid gate_status marker is found, empty otherwise
     */
    public static Optional<ParsedGateStatus> parse(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }

        Matcher statusMatcher = STATUS_PATTERN.matcher(output);
        if (!statusMatcher.find()) {
            return Optional.empty();
        }

        GateStatus status;
        try {
            status = GateStatus.valueOf(statusMatcher.group(1).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String category = extractField(CATEGORY_PATTERN, output);
        String description = extractField(DESCRIPTION_PATTERN, output);

        return Optional.of(new ParsedGateStatus(status, category, description));
    }

    private static String extractField(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
