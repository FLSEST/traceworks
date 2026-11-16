package traceworks.core.context;

import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A shell line was not filled in, and nothing of it ran: it names a placeholder whose value is protected (text a
 * model wrote, untrusted input, platform data, an output of a tainted run). Such a value never goes into shell code,
 * wherever the placeholder stands and with {@code shell_escape_variables} too; it reaches a command as a shell
 * variable the block names under {@code values}. Thrown by {@link ExecutionContext#resolveShellTemplate}; the step
 * that owns the line fails with the message, and the run ends with it. A workflow runs as written or is refused, at
 * run time too.
 */
public final class ShellLineRefusedException extends RuntimeException {

    /** @param keys the keys of the protected placeholders in the line, as written; not empty */
    ShellLineRefusedException(Collection<String> keys) {
        super(message(keys));
    }

    /**
     * What is wrong with a shell line that names the protected placeholders {@code keys}, and what to write instead;
     * the analyzer says the same of a line it can judge before a run.
     */
    public static String message(Collection<String> keys) {
        String first = keys.iterator().next().strip();
        String name = variableName(first);
        return "shell line refused, nothing ran: " + keys.stream().map(key -> "{{ " + key.strip() + " }}")
                .collect(Collectors.joining(", ")) + (keys.size() == 1 ? " is" : " are")
                + " text a model wrote or text from outside the run, and such a value never goes into shell code,"
                + " quoted or not. Hand it to the command as a variable: put it under the block's values, for example"
                + " \"values\": {\"" + name + "\": \"{{ " + first + " }}\"}, and write \"$" + name + "\" where the"
                + " placeholder stands";
    }

    /** A variable name for the example: the block's name of {@code block.<name>.output}, else the key, in capitals. */
    public static String variableName(String key) {
        String base = key.startsWith("block.") && key.endsWith(".output") && key.length() > "block..output".length()
                ? key.substring("block.".length(), key.length() - ".output".length()) : key;
        String name = base.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = "VALUE_" + name;
        }
        return name.replaceAll("_+$", "");
    }
}
