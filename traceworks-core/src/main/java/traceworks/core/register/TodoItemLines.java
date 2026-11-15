package traceworks.core.register;

import traceworks.core.util.TimedRegex;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code todo_item:} lines as register input, the simple form a report takes when it has no envelope:
 * {@code todo_item: <title> | <PRIORITY> | <tag,tag> | [description]}, tolerant of headings, bold and backticks and
 * of a foreach item prefix. Each line is a finding of the rule {@value #RULE_KEY} whose
 * subject is its title: a reworded title is a new finding. So a workflow whose findings are tasks gets a register
 * without an envelope of its own.
 */
public final class TodoItemLines {

    /** The rule key every finding of this form carries. */
    public static final String RULE_KEY = "todo-item";

    private static final String FOREACH_PREFIX = "(?:\\[[^\\]\\n]*\\]:\\s*)?";
    private static final Pattern LINE = Pattern.compile(
            "^" + FOREACH_PREFIX + "(?:#{1,6}\\s+)?[*`]*todo[_-]item[*`]*:\\s*(.+?)\\s*\\|\\s*(CRITICAL|HIGH|MEDIUM|LOW)"
                    + "\\s*\\|\\s*(.+?)(?:\\s*\\|\\s*(.+?))?\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * How long reading the lines of one report may take. A report of the largest output a step may have by default
     * (16 MiB) of ordinary lines takes well under a second.
     */
    public static final Duration TIME_LIMIT = Duration.ofSeconds(10);

    private TodoItemLines() {
    }

    /**
     * The findings the lines of {@code text} state, in order; none for {@code null} or no line.
     *
     * @throws TimedRegex.TimedOut if the text cannot be read within {@link #TIME_LIMIT}: a report is a step's output,
     *                             so it is untrusted, and a line built for it (a title, very many spaces, no pipe)
     *                             costs the line's pattern quadratic time
     * @throws IllegalArgumentException if a line is no finding the register takes: a title beyond
     *                             {@link AuditResultParser#TITLE_LIMIT} or a description beyond
     *                             {@link AuditResultParser#DESCRIPTION_LIMIT} characters. A line has the limits of
     *                             a finding of an envelope: the register keeps what a report states, and reads it at
     *                             every replay
     */
    public static List<ReportedFinding> parse(String text) {
        return parse(text, TIME_LIMIT);
    }

    static List<ReportedFinding> parse(String text, Duration timeLimit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ReportedFinding> findings = new ArrayList<>();
        Matcher matcher = TimedRegex.matcher(LINE, text.replace("\r\n", "\n"), timeLimit);
        while (find(matcher)) {
            String title = plain(matcher.group(1));
            if (title.isEmpty()) {
                continue;
            }
            Severity severity = Severity.parse(matcher.group(2)).orElseThrow();
            List<String> tags = Arrays.stream(matcher.group(3).split(",")).map(String::strip)
                    .filter(tag -> !tag.isEmpty()).toList();
            String description = matcher.group(4) == null ? null : plain(matcher.group(4));
            String where = "todo_item line " + (findings.size() + 1);
            requireWithin(where, "title", title, AuditResultParser.TITLE_LIMIT);
            requireWithin(where, "description", description, AuditResultParser.DESCRIPTION_LIMIT);
            findings.add(new ReportedFinding(RULE_KEY, title, severity, title, description, null, tags));
        }
        return findings;
    }

    private static void requireWithin(String where, String field, String value, int limit) {
        if (value != null && value.length() > limit) {
            throw new IllegalArgumentException(where + ": the " + field + " is " + value.length()
                    + " characters long, at most " + limit + " characters are read");
        }
    }

    private static boolean find(Matcher matcher) {
        try {
            return matcher.find();
        } catch (StackOverflowError e) {
            throw TimedRegex.tooDeep();
        }
    }

    private static String plain(String text) {
        return text.replaceAll("[*`]", "").strip();
    }
}
