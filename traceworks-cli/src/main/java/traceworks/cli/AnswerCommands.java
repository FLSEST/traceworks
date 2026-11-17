package traceworks.cli;

import traceworks.core.view.RunHtml;

import java.util.ArrayList;
import java.util.List;

/**
 * The commands that answer a question, as a person could type them; every value is shell-quoted.
 *
 * <p>The options of an approval and the names of a form's fields are text of the workflow. A terminal breaks a line
 * wider than it is, and what follows the break starts a terminal line of its own, without anything that marks it:
 * an option padded to the terminal's width would put its end there, to read as a hint of Traceworks with a command
 * to copy. So a command is written out with such a text only where the text stays on one terminal line of
 * {@value #LINE_WIDTH} columns and holds no run of blanks. Else the command is written with a placeholder, and the
 * options or names follow on marked lines, broken as the lines of a question are.
 */
public final class AnswerCommands {

    /** The columns of the terminal line that no text of the workflow may cross. */
    static final int LINE_WIDTH = 80;
    /** What the first line of a listed option or name begins with, and what its further lines begin with. */
    private static final String LISTED = "| ";
    private static final String LISTED_ON = "|   ";

    private AnswerCommands() {
    }

    /**
     * The lines of the hint, each to be printed behind {@code lead}: one command per option or one for the form,
     * or the command with a placeholder, a line that says so, and the options or names on marked lines.
     *
     * @param lead       what the caller prints before each line; its width counts
     * @param kind       {@code approval} or {@code human-input}
     * @param options    the options of an approval
     * @param fieldNames the variable names of a form; empty for a single input
     */
    public static List<String> of(String lead, String runId, String kind, List<String> options,
                                  List<String> fieldNames) {
        String run = RunHtml.shellQuote(TerminalFormat.safe(runId));
        List<String> lines = new ArrayList<>();
        if ("human-input".equals(kind)) {
            String start = "traceworks answer " + run;
            if (fieldNames.isEmpty()) {
                lines.add(start + " --value '<text>'");
                return lines;
            }
            List<String> names = fieldNames.stream().map(TerminalFormat::safe).toList();
            StringBuilder command = new StringBuilder(start);
            boolean whole = true;
            for (String name : names) {
                command.append(" --field ");
                String quoted = RunHtml.shellQuote(name + "=<value>");
                whole &= staysOnOneLine(lead + command, name, quoted);
                command.append(quoted);
            }
            if (whole) {
                lines.add(command.toString());
            } else {
                lines.add(start + " --field '<name>=<value>'");
                lines.add("with one --field for each of these names, quoted for your shell:");
                names.forEach(name -> listed(lines, lead, name));
            }
        } else if (options.isEmpty()) {
            lines.add("traceworks approve " + run + " --answer '<option>'");
        } else {
            String start = "traceworks approve " + run + " --answer ";
            List<String> safe = options.stream().map(TerminalFormat::safe).toList();
            if (safe.stream().allMatch(o -> staysOnOneLine(lead + start, o, RunHtml.shellQuote(o)))) {
                safe.forEach(option -> lines.add(start + RunHtml.shellQuote(option)));
            } else {
                lines.add(start + "'<option>'");
                lines.add("with one of these as <option>, quoted for your shell:");
                safe.forEach(option -> listed(lines, lead, option));
            }
        }
        return lines;
    }

    /**
     * Whether {@code quoted}, written behind {@code before}, lies on one terminal line and not at its start, and
     * {@code value} holds no two blanks in a row. What comes before may be wider than the terminal (a run named by
     * its folder): that is the operator's text and Traceworks' own, and only the workflow's may not cross a line.
     */
    private static boolean staysOnOneLine(String before, String value, String quoted) {
        int from = RunReport.columns(before);
        int to = from + RunReport.columns(quoted) - 1;
        if (from / LINE_WIDTH != to / LINE_WIDTH || from > 0 && from % LINE_WIDTH == 0) {
            return false;
        }
        return !blankRun(value);
    }

    /** Whether {@code value} holds two blanks in a row: white space of any kind, the no-break spaces too. */
    static boolean blankRun(String value) {
        boolean blank = false;
        for (int i = 0; i < value.length(); ) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            boolean isBlank = Character.isWhitespace(c) || Character.isSpaceChar(c);
            if (blank && isBlank) {
                return true;
            }
            blank = isBlank;
        }
        return false;
    }

    /**
     * The lines that show texts of the workflow (options, a label) behind a caption of Traceworks, each line to be
     * printed behind {@code lead}: one line, the texts joined by {@code " / "} behind the caption and between
     * {@code open} and {@code close}, where that line fits the terminal and no text holds a run of blanks; else
     * the caption alone (with {@code elsewhere} behind it) and each text on marked lines. So no text of the
     * workflow ends on a terminal line of its own without a mark, wherever options or names are shown.
     *
     * @param caption   Traceworks' own text, or the operator's; may be empty
     * @param elsewhere what the caption's line ends with when the texts follow on marked lines
     */
    public static List<String> shown(String lead, String caption, String open, String close, String elsewhere,
                              List<String> values) {
        List<String> safe = values.stream().map(TerminalFormat::safe).toList();
        String line = caption + open + String.join(" / ", safe) + close;
        List<String> lines = new ArrayList<>();
        if (safe.stream().noneMatch(AnswerCommands::blankRun)
                && RunReport.columns(lead + line) <= LINE_WIDTH) {
            lines.add(line);
            return lines;
        }
        if (!(caption + elsewhere).isEmpty()) {
            lines.add(caption + elsewhere);
        }
        safe.forEach(value -> listed(lines, lead, value));
        return lines;
    }

    /** One option or name on marked lines that fit the terminal behind {@code lead}; the blanks at a break go. */
    private static void listed(List<String> lines, String lead, String value) {
        int width = Math.max(20, LINE_WIDTH - RunReport.columns(lead) - LISTED_ON.length());
        List<String> parts = RunReport.broken(value, width);
        for (int i = 0; i < parts.size(); i++) {
            lines.add((i == 0 ? LISTED : LISTED_ON) + parts.get(i));
        }
    }

    /**
     * The command that resumes the run.
     *
     * @param hostFlag whether it needs {@code --allow-host-execution}: a host run whose consent came from the flag
     */
    public static String resume(String runId, boolean hostFlag) {
        return "traceworks resume " + RunHtml.shellQuote(TerminalFormat.safe(runId))
                + (hostFlag ? " --allow-host-execution" : "");
    }
}
