package traceworks.core.context;

import traceworks.core.util.ShellQuoting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder substitution for {@code {{ ... }}} templates (previous output, named block outputs,
 * context variables). Stateless companion of {@link ExecutionContext}.
 */
final class TemplateResolver {

    /** Shared with static analyses (the analyzer) — see {@link PlaceholderSyntax#PLACEHOLDER}. */
    private static final Pattern PLACEHOLDER = PlaceholderSyntax.PLACEHOLDER;

    /**
     * The names of the shell variables that carry a protected value into a shell line: this prefix and a number.
     * No workflow can set one: a secret's name must not start with {@code TRACEWORKS_}
     * ({@code core.engine.SecretNames}), and a workflow's own variables never reach the shell as variables.
     */
    static final String VALUE_VARIABLE_PREFIX = "TRACEWORKS_VALUE_";

    /** No caller interested in which placeholders were substituted (prompt paths). */
    private static final Consumer<String> IGNORE = key -> { };

    private TemplateResolver() {}

    /** Raw substitution — values are inserted verbatim. Never reports substitutions: prompts are not a shell path. */
    static String resolve(String template, String previousOutput,
                          Map<String, String> blockOutputs, Map<String, Object> vars) {
        return substitute(template, previousOutput, blockOutputs, vars, IGNORE);
    }

    /**
     * Raw substitution for an AI prompt — identical output to {@link #resolve}, but every placeholder
     * that really resolved is reported. Platform data in a prompt does not taint the run (a prompt is
     * no command line), it marks the output of the block that used it
     * ({@link ExecutionContextTaint#substitutedIntoPrompt}).
     */
    static String resolvePrompt(String template, String previousOutput, Map<String, String> blockOutputs,
                                Map<String, Object> vars, Consumer<String> onSubstitution) {
        return substitute(template, previousOutput, blockOutputs, vars, onSubstitution);
    }

    /**
     * Which values of a run a shell line has to protect: variable names, named block outputs and whether
     * {@code previous_output} is one. Untrusted input, platform data, the outputs of a tainted run and text a model
     * wrote. {@link #NONE} is a clean run.
     */
    record Taint(Set<String> variables, Set<String> outputs, boolean previousOutput) {

        static final Taint NONE = new Taint(Set.of(), Set.of(), false);

        boolean isEmpty() {
            return variables.isEmpty() && outputs.isEmpty() && !previousOutput;
        }
    }

    /**
     * A shell command line of a block without {@code shell_escape_variables}: {@link #shellLine} with the other
     * values inserted as they are.
     */
    static String resolveShell(String template, String previousOutput, Map<String, String> blockOutputs,
                               Map<String, Object> vars, Taint taint, Consumer<String> onSubstitution) {
        return shellLine(template, previousOutput, blockOutputs, vars, taint, false, onSubstitution);
    }

    /**
     * A shell command line of a block with {@code shell_escape_variables}: {@link #shellLine} with each of the other
     * values wrapped in POSIX single quotes, internal single quotes written as {@code '\''}.
     */
    static String resolveShellSafe(String template, String previousOutput, Map<String, String> blockOutputs,
                                   Map<String, Object> vars, Taint taint, Consumer<String> onSubstitution) {
        return shellLine(template, previousOutput, blockOutputs, vars, taint, true, onSubstitution);
    }

    /**
     * Fills in a shell command line. One pass over the template: every placeholder is looked up once and what is
     * inserted is never scanned again, so a value that itself contains {@code {{ … }}} stays literal.
     *
     * <p><b>A value of {@code taint} never becomes part of the shell text.</b> In its placeholder's place stands a
     * reference to a shell variable, {@code "${TRACEWORKS_VALUE_1}"} for a bare word and {@code ${TRACEWORKS_VALUE_1}}
     * inside the author's double quotes or an unquoted heredoc ({@link ShellValuePlace}), and the line starts by
     * setting the variables, one per value ({@link #valueAssignment}):
     *
     * <pre>
     * IFS= read -r -d '' TRACEWORKS_VALUE_1 &lt;&lt;'TRACEWORKS_VALUE_1_END' || :
     * it's $(not run)
     * TRACEWORKS_VALUE_1_END
     * TRACEWORKS_VALUE_1=${TRACEWORKS_VALUE_1%$'\n'}
     * printf '%s\n' "${TRACEWORKS_VALUE_1}" &gt; "notes/${TRACEWORKS_VALUE_1}.txt"
     * </pre>
     *
     * The shell does not read the result of a parameter expansion as code, so the value cannot become a command,
     * whatever it holds and whatever the author or another value wrote around the reference. The assignments are
     * the first thing in the text, written by this method alone, where no text of the workflow can change how the
     * shell reads them. They are plain shell variables: not exported, so no child process inherits them, and not
     * bound by the size limits of arguments and the environment. The text is still the whole of what runs: whoever
     * reads the recorded command sees each reference and its value. A NUL in a value does not reach the variable
     * (bash holds none in a variable or a script), as it did not reach the command before.
     *
     * <p>Every other value is inserted as it always was: as it is, or with {@code escapeAll} in single quotes.
     * A placeholder that names nothing stays literal. {@code onSubstitution} is told the key of every placeholder
     * that resolved, the signal that turns platform data into a run-level taint source
     * ({@link ExecutionContextTaint#substitutedIntoShell}).
     *
     * @throws ShellLineRefusedException when a value of {@code taint} stands where no reference can stand
     *                                   ({@link ShellValuePlace.Form#REFUSED}); nothing is filled in then
     */
    static String shellLine(String template, String previousOutput, Map<String, String> blockOutputs,
                            Map<String, Object> vars, Taint taint, boolean escapeAll,
                            Consumer<String> onSubstitution) {
        if (template == null) return null;
        Map<Integer, ShellQuoteScanner.Occurrence> places = new HashMap<>();
        boolean scanned = false;
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> refusals = new LinkedHashSet<>();
        String firstRefused = null;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder line = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(2);
            String raw = lookup(key, previousOutput, blockOutputs, vars);
            String replacement;
            if (raw == null) {
                replacement = matcher.group();
            } else if (!names(key, taint)) {
                onSubstitution.accept(key);
                replacement = escapeAll ? shellEscape(raw) : raw;
            } else {
                onSubstitution.accept(key);
                if (!scanned) {
                    ShellQuoteScanner.occurrences(template).forEach(found -> places.put(found.start(), found));
                    scanned = true;
                }
                ShellValuePlace place = ShellValuePlace.of(places.get(matcher.start()));
                if (place.form() == ShellValuePlace.Form.REFUSED) {
                    String shown = "{{ " + key + " }}";
                    if (firstRefused == null) firstRefused = shown;
                    refusals.add(shown + " stands " + place.refusal());
                    replacement = "";
                } else {
                    String name = names.computeIfAbsent(key, k -> VALUE_VARIABLE_PREFIX + (names.size() + 1));
                    values.put(name, raw);
                    replacement = place.form() == ShellValuePlace.Form.QUOTED ? "\"${" + name + "}\""
                            : "${" + name + "}";
                }
            }
            matcher.appendReplacement(line, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(line);
        if (!refusals.isEmpty()) throw new ShellLineRefusedException(refusals, firstRefused);
        if (values.isEmpty()) return line.toString();
        StringBuilder text = new StringBuilder();
        values.forEach((name, value) -> text.append(valueAssignment(name, value)));
        return text.append(line).toString();
    }

    /**
     * The lines that set the shell variable {@code name} to {@code value}, byte for byte:
     *
     * <pre>
     * IFS= read -r -d '' NAME &lt;&lt;'NAME_END' || :
     * the value, as it is
     * NAME_END
     * NAME=${NAME%$'\n'}
     * </pre>
     *
     * The value is the body of a heredoc with a quoted delimiter, where the shell expands nothing and nothing needs
     * an escape, so the text holds the value as it is and bash takes a value of any size in the time it takes to
     * read it (a value in single quotes costs bash time with every {@code '} in it, minutes for megabytes). The
     * body ends at the first line that is the delimiter, so the delimiter is one no line of the value is: the
     * variable's name and {@code _END}, with a {@code _} more for as long as the value has such a line. {@code read}
     * with an empty delimiter takes the whole body, the newline the heredoc ends it with included, which the last
     * line takes off again; it ends with a status other than 0 because the body holds no NUL, hence {@code || :},
     * so a line under {@code set -e} goes on.
     */
    static String valueAssignment(String name, String value) {
        String end = name + "_END";
        while (hasLine(value, end)) end += "_";
        return "IFS= read -r -d '' " + name + " <<'" + end + "' || :\n" + value + "\n" + end + "\n"
                + name + "=${" + name + "%$'\\n'}\n";
    }

    private static boolean hasLine(String value, String line) {
        int at = value.indexOf(line);
        while (at >= 0) {
            int after = at + line.length();
            if ((at == 0 || value.charAt(at - 1) == '\n') && (after == value.length() || value.charAt(after) == '\n')) {
                return true;
            }
            at = value.indexOf(line, at + 1);
        }
        return false;
    }

    /**
     * Whether {@code template} names a value of {@code taint}. For the places that pass no shell, so that no
     * reference can stand there — a tool step's arguments, the host path of a mount — which refuse such values.
     */
    static boolean namesEscapedValue(String template, Taint taint) {
        for (String key : PlaceholderSyntax.keysIn(template)) {
            if (names(key, taint)) return true;
        }
        return false;
    }

    /** Whether the placeholder key names a value of {@code taint}. */
    private static boolean names(String key, Taint taint) {
        if (taint.variables().contains(key)) return true;
        if ("previous_output".equals(key)) return taint.previousOutput();
        return key.startsWith("block.") && key.endsWith(".output")
                && taint.outputs().contains(key.substring("block.".length(), key.length() - ".output".length()));
    }

    /** {@code value} quoted for the shell ({@link ShellQuoting#quote}), the form the record's redactor knows too. */
    static String shellEscape(String value) {
        return ShellQuoting.quote(value);
    }

    /**
     * One pass over the template: every placeholder is looked up once and the inserted value is never
     * scanned again, so a value that itself contains {@code {{ … }}} (a log, an AI answer) stays literal.
     */
    private static String substitute(String template, String previousOutput, Map<String, String> blockOutputs,
                                     Map<String, Object> vars, Consumer<String> onSubstitution) {
        if (template == null) return null;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(2);
            String raw = lookup(key, previousOutput, blockOutputs, vars);
            if (raw != null) onSubstitution.accept(key);
            matcher.appendReplacement(out, Matcher.quoteReplacement(raw == null ? matcher.group() : raw));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Value for one placeholder key, or null when unknown (the placeholder then stays literal). */
    private static String lookup(String key, String previousOutput, Map<String, String> blockOutputs,
                                 Map<String, Object> vars) {
        if ("previous_output".equals(key)) return previousOutput != null ? previousOutput : "";
        if (key.startsWith("block.") && key.endsWith(".output") && key.length() > "block..output".length()) {
            String name = key.substring("block.".length(), key.length() - ".output".length());
            if (blockOutputs.containsKey(name)) {
                String output = blockOutputs.get(name);
                return output == null ? "" : output;
            }
        }
        if (vars.containsKey(key)) return String.valueOf(vars.get(key));
        return null;
    }

    /** Whether {@code key} would resolve to a value (possibly blank) rather than stay a literal placeholder — the exact question {@link #lookup} answers, reused rather than duplicated. */
    static boolean resolves(String key, String previousOutput, Map<String, String> blockOutputs, Map<String, Object> vars) {
        return lookup(key, previousOutput, blockOutputs, vars) != null;
    }
}
