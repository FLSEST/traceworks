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
 * to copy. So a command is written out with such a text only where the text stays on one terminal line and holds
 * no run of blanks ({@link TerminalText#inline}), and the command of a form, which holds every name, only where
 * all of it fits one terminal line. Else the command is written with a placeholder, and the options
 * or names follow on marked lines ({@link TerminalText#listed}), broken as the lines of a question are.
 */
public final class AnswerCommands {

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
                whole &= TerminalText.inline(lead + command, name, quoted);
                command.append(quoted);
            }
            // Each name was judged at a column counted with the names before it, and the count gives a narrow
            // character from U+1100 on two columns: only a line that fits one row by it puts no name across a row
            whole &= TerminalText.columns(lead + command) <= TerminalText.WIDTH;
            if (whole) {
                lines.add(command.toString());
            } else {
                lines.add(start + " --field '<name>=<value>'");
                lines.add("with one --field for each of these names, quoted for your shell:");
                names.forEach(name -> lines.addAll(TerminalText.listed(lead, name)));
            }
        } else if (options.isEmpty()) {
            lines.add("traceworks approve " + run + " --answer '<option>'");
        } else {
            String start = "traceworks approve " + run + " --answer ";
            List<String> safe = options.stream().map(TerminalFormat::safe).toList();
            if (safe.stream().allMatch(o -> TerminalText.inline(lead + start, o, RunHtml.shellQuote(o)))) {
                safe.forEach(option -> lines.add(start + RunHtml.shellQuote(option)));
            } else {
                lines.add(start + "'<option>'");
                lines.add("with one of these as <option>, quoted for your shell:");
                safe.forEach(option -> lines.addAll(TerminalText.listed(lead, option)));
            }
        }
        return lines;
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
