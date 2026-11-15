package traceworks.core.gate;

import java.util.regex.Pattern;

/**
 * The gate description a {@code checked_command} block makes up from its exit code when the command writes none:
 * {@code Command exit <n>}, or {@code Command did not run (exit <n>)} when the runner knows that it did not run (a
 * refusal, a start failure). It says whether the command ran, not what it found, so it never outranks a description
 * with words of its own (see the run's gate aggregation). One place for both sides, so the text the block writes
 * and the text the run completion recognises cannot drift apart.
 */
public final class CommandExitDescription {

    private static final Pattern SYNTHETIC = Pattern.compile("Command (?:exit -?\\d+|did not run \\(exit -?\\d+\\))");

    private CommandExitDescription() {
    }

    /** The description for this exit code. */
    public static String of(int exitCode) {
        return "Command exit " + exitCode;
    }

    /** The description for a command that did not run, which ended with this exit code. */
    public static String notRun(int exitCode) {
        return "Command did not run (exit " + exitCode + ")";
    }

    /** Whether a parsed gate description is exactly one that {@link #of} writes. */
    public static boolean isSynthetic(String description) {
        return description != null && SYNTHETIC.matcher(description.strip()).matches();
    }
}
