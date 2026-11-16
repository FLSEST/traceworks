package traceworks.core.context;

import traceworks.core.util.ShellQuoting;

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

    /** No caller interested in which placeholders were substituted (prompt paths). */
    private static final Consumer<String> IGNORE = key -> { };

    private TemplateResolver() {}

    /** Raw substitution — values are inserted verbatim. Never reports substitutions: prompts are not a shell path. */
    static String resolve(String template, String previousOutput,
                          Map<String, String> blockOutputs, Map<String, Object> vars) {
        return substitute(template, previousOutput, blockOutputs, vars, false, IGNORE);
    }

    /**
     * Raw substitution that reports every placeholder that really resolved — identical output to {@link #resolve}.
     * For an AI prompt: platform data in a prompt does not taint the run (a prompt is no command line), it marks the
     * output of the block that used it ({@link ExecutionContextTaint#substitutedIntoPrompt}). And for the template
     * of a shell value ({@code values}), whose result reaches a shell as a variable's value, never as shell text:
     * platform data there taints the run as in a shell line ({@link ExecutionContextTaint#substitutedIntoShell}).
     */
    static String resolvePrompt(String template, String previousOutput, Map<String, String> blockOutputs,
                                Map<String, Object> vars, Consumer<String> onSubstitution) {
        return substitute(template, previousOutput, blockOutputs, vars, false, onSubstitution);
    }

    /**
     * Which values of a run are protected: variable names, named block outputs and whether {@code previous_output}
     * is one. Untrusted input, platform data, the outputs of a tainted run and text a model wrote. {@link #NONE} is a
     * clean run.
     */
    record Taint(Set<String> variables, Set<String> outputs, boolean previousOutput) {

        static final Taint NONE = new Taint(Set.of(), Set.of(), false);

        boolean isEmpty() {
            return variables.isEmpty() && outputs.isEmpty() && !previousOutput;
        }
    }

    /**
     * Fills in a shell command line: every placeholder is looked up once and what is inserted is never scanned
     * again, so a value that itself contains {@code {{ … }}} stays literal. A value is inserted as it is, or with
     * {@code escapeAll} ({@code shell_escape_variables}) wrapped in POSIX single quotes, internal single quotes
     * written as {@code '\''}. A placeholder that names nothing stays literal.
     *
     * <p><b>A protected value never goes into a shell line.</b> A placeholder that names a value of {@code taint}
     * refuses the line, wherever it stands and with {@code escapeAll} too: Traceworks does not read shell text to
     * decide where a value is harmless, because such a reading is never bash's. Such a value reaches a command as a
     * shell variable the block names under {@code values} ({@code core.util.ShellVariables}).
     *
     * @throws ShellLineRefusedException when the template names a value of {@code taint}; nothing is filled in then
     */
    static String shellLine(String template, String previousOutput, Map<String, String> blockOutputs,
                            Map<String, Object> vars, Taint taint, boolean escapeAll) {
        if (template == null) return null;
        if (!taint.isEmpty()) {
            Set<String> refused = new LinkedHashSet<>();
            for (String key : PlaceholderSyntax.keysIn(template)) {
                if (names(key, taint) && lookup(key, previousOutput, blockOutputs, vars) != null) refused.add(key);
            }
            if (!refused.isEmpty()) throw new ShellLineRefusedException(refused);
        }
        return substitute(template, previousOutput, blockOutputs, vars, escapeAll, IGNORE);
    }

    /**
     * Whether {@code template} names a value of {@code taint}. For the places that take no protected value: a tool
     * step's arguments, the host path of a mount.
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
                                     Map<String, Object> vars, boolean escape, Consumer<String> onSubstitution) {
        if (template == null) return null;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(2);
            String raw = lookup(key, previousOutput, blockOutputs, vars);
            String replacement = raw == null ? matcher.group() : escape ? shellEscape(raw) : raw;
            if (raw != null) onSubstitution.accept(key);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
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
