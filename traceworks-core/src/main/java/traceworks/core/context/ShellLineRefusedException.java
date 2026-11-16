package traceworks.core.context;

import java.util.Collection;

/**
 * A shell line was not filled in, and nothing of it ran: a placeholder whose value the run has to quote (text a
 * model wrote, untrusted input, platform data, an output of a tainted run) stands where the run's quotes do not
 * hold: inside the author's quotes, inside backticks or an arithmetic expression, in the body of a heredoc, in a
 * comment or after a backslash. Thrown by {@link ExecutionContext#resolveShellTemplate}; the step that owns the line
 * fails with the message, and the run ends with it. A workflow runs as written or is refused, at run time too.
 */
public final class ShellLineRefusedException extends RuntimeException {

    /**
     * @param places each offending placeholder with its place, such as {@code {{ item }} stands inside double
     *               quotes}; not empty
     * @param first  the first of those placeholders as written, for the example in the message
     */
    ShellLineRefusedException(Collection<String> places, String first) {
        super("shell line refused, nothing ran: " + String.join(", ", places) + ", and such a value is text a model"
                + " wrote or comes from outside the run. The run quotes it itself, which only holds for a bare word:"
                + " there a $(…), a quote or a line break in the value would run as a command. Write the placeholder"
                + " bare (X=" + first + ", then \"$X\"), or pass the value through a file or standard input (printf"
                + " '%s\\n' " + first + " > file)");
    }
}
