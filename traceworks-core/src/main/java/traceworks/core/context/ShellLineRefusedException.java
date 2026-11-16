package traceworks.core.context;

import java.util.Collection;

/**
 * A shell line was not filled in, and nothing of it ran: a placeholder whose value the run has to protect (text a
 * model wrote, untrusted input, platform data, an output of a tainted run) stands where no reference to it can
 * stand ({@link ShellValuePlace}): where the shell expands none, or where the shell or the command evaluates what
 * the value holds. Thrown by {@link ExecutionContext#resolveShellTemplate}; the step that owns the line fails with
 * the message, and the run ends with it. A workflow runs as written or is refused, at run time too.
 */
public final class ShellLineRefusedException extends RuntimeException {

    /**
     * @param places each offending placeholder with its place and why it does not do, such as
     *               {@code {{ item }} stands inside single quotes, where the shell expands no reference}; not empty
     * @param first  the first of those placeholders as written, for the example in the message
     */
    ShellLineRefusedException(Collection<String> places, String first) {
        super("shell line refused, nothing ran: " + String.join("; ", places) + ". Its value is text a model"
                + " wrote or comes from outside the run; the run hands such a value to the shell as a variable and"
                + " writes a reference in the placeholder's place, so it never is shell code, and that needs a place"
                + " where the shell takes a value. Write the placeholder as a bare word or inside double quotes (X="
                + first + ", then use \"$X\"), or pass the value through a file or standard input (printf '%s\\n' "
                + first + " > file)");
    }
}
