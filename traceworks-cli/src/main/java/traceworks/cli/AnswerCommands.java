package traceworks.cli;

import traceworks.core.view.RunHtml;

import java.util.ArrayList;
import java.util.List;

/** The commands that answer a question, as a person could type them; every value is shell-quoted. */
public final class AnswerCommands {

    private AnswerCommands() {
    }

    /**
     * @param kind       {@code approval} or {@code human-input}
     * @param options    the options of an approval
     * @param fieldNames the variable names of a form; empty for a single input
     */
    public static List<String> of(String runId, String kind, List<String> options, List<String> fieldNames) {
        String run = RunHtml.shellQuote(TerminalFormat.safe(runId));
        List<String> commands = new ArrayList<>();
        if ("human-input".equals(kind)) {
            if (fieldNames.isEmpty()) {
                commands.add("traceworks answer " + run + " --value '<text>'");
            } else {
                StringBuilder fields = new StringBuilder();
                fieldNames.forEach(name -> fields.append(" --field ")
                        .append(RunHtml.shellQuote(TerminalFormat.safe(name) + "=<value>")));
                commands.add("traceworks answer " + run + fields);
            }
        } else if (options.isEmpty()) {
            commands.add("traceworks approve " + run + " --answer '<option>'");
        } else {
            options.forEach(option -> commands.add("traceworks approve " + run + " --answer "
                    + RunHtml.shellQuote(TerminalFormat.safe(option))));
        }
        return commands;
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
