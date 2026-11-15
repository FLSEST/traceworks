package traceworks.it;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one {@code traceworks} command did: its arguments, its exit code and what it printed.
 *
 * @param args the arguments after {@code java -jar traceworks.jar}
 * @param code the exit code
 * @param out  standard output
 * @param err  standard error
 */
record Result(List<String> args, int code, String out, String err) {

    /** The summary line {@code run <id> STATUS} that {@code run}, {@code resume} and {@code cancel} print last. */
    private static final Pattern SUMMARY =
            Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) ([A-Z_]+)$");

    Result {
        args = List.copyOf(args);
    }

    /** The run id of the summary line; fails when the command printed none. */
    String runId() {
        return summary().group(1);
    }

    /** The status of the summary line, such as {@code COMPLETED} or {@code PARKED}. */
    String status() {
        return summary().group(2);
    }

    /** Standard output and standard error together. */
    String both() {
        return out + err;
    }

    /** The command, its exit code and what it printed: the description of every assertion on it. */
    String describe() {
        return "traceworks " + String.join(" ", args) + "\nexit " + code + "\n--- stdout\n" + out + "--- stderr\n"
                + err;
    }

    private Matcher summary() {
        Matcher matcher = SUMMARY.matcher(out);
        if (!matcher.find()) {
            throw new AssertionError("No summary line 'run <id> <STATUS>' in the output of:\n" + describe());
        }
        return matcher;
    }
}
