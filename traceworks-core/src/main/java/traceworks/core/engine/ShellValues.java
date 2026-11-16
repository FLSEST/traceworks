package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.util.ShellVariables;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code values} key of a block: shell variables the block's shell lines find set, each a name and a template.
 *
 * <pre>
 * "values": {"REVIEW": "{{ block.review.output }}", "TITLE": "Report for {{ name }}"},
 * "command": "printf '%s\n' \"$REVIEW\" &gt; \"$TITLE.md\""
 * </pre>
 *
 * <p>It is how a value reaches a command without being shell code. A template takes any placeholder, a protected
 * one included (text a model wrote, untrusted input, platform data), which a shell line refuses
 * ({@code ShellLineRefusedException}). The run fills the template in and sets the variable before the block's shell
 * lines run: its {@code command}, {@code items_command}, {@code run_if}, shell condition and branches, and the inner
 * shell steps of a wrapper, each filled in anew for the line it is set for (a loop's item, say). The value is never
 * part of the text the shell parses ({@link ShellVariables}), the variable is not exported, and a NUL in the value
 * is taken out. What the command does with the variable is the workflow's: {@code "$REVIEW"} is one argument.
 *
 * <p>A name matches {@code [A-Z_][A-Z0-9_]*} and is none the run or the shell reserves: what a secret may not be
 * called ({@link SecretNames#problem}), {@code IFS} and the names that start with {@code BASH}, and no secret of the
 * same block. A tool step ({@code container_args}) runs no shell and takes no values. One rule for the analyzer and
 * the step, so they cannot drift apart.
 */
public final class ShellValues {

    /** The block key. */
    public static final String KEY = "values";

    private static final Pattern NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    private static final Set<String> SHELL_OWN = Set.of("IFS", "SHELLOPTS", "ENV", "PS4", "OPTIND", "OPTARG",
            "REPLY", "RANDOM", "SECONDS", "LINENO", "FUNCNAME", "PIPESTATUS", "COMP_WORDBREAKS", "GLOBIGNORE",
            "CDPATH", "PROMPT_COMMAND", "EUID", "UID", "PPID", "SHLVL", "OLDPWD", "PWD");

    private ShellValues() {
    }

    /**
     * What is wrong with the {@code values} of a block's config, one line per problem, each starting with
     * {@code values}; empty when the key is absent or everything is valid.
     */
    public static List<String> problems(Map<String, Object> config) {
        if (config == null || config.get(KEY) == null) {
            return List.of();
        }
        if (!(config.get(KEY) instanceof Map<?, ?> entries)) {
            return List.of(KEY + " is not an object of names and templates — write it as {\"NAME\": \"{{ … }}\"}");
        }
        List<String> problems = new ArrayList<>();
        List<String> secrets = SecretNames.of(config);
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String problem = problem(name, secrets);
            if (problem != null) {
                problems.add(KEY + ": " + problem);
            }
            if (!(entry.getValue() instanceof String)) {
                problems.add(KEY + ": the value of \"" + name + "\" is not a text — write a template such as"
                        + " \"{{ block.review.output }}\"");
            }
        }
        if (!entries.isEmpty() && config.get("container_args") != null) {
            problems.add(KEY + ": a tool step (container_args) runs no shell, so no variable can be set for it —"
                    + " remove values, or run the tool from a command");
        }
        return problems;
    }

    /** Why {@code name} cannot name a value, starting with the name in quotes; {@code null} when it can. */
    static String problem(String name, List<String> secretsOfTheBlock) {
        String quoted = "\"" + name + "\"";
        if (!NAME.matcher(name).matches()) {
            return quoted + " is not a variable name — use capital letters, digits and _, not starting with a digit";
        }
        if (SHELL_OWN.contains(name) || name.startsWith("BASH")) {
            return quoted + " is reserved — the shell uses it; choose another name";
        }
        if (secretsOfTheBlock.contains(name)) {
            return quoted + " is a secret of this block — a value needs a name of its own";
        }
        return SecretNames.problem(name);
    }

    /** The names in the {@code values} of a block's config, in the order written; empty without the key. */
    public static List<String> names(Map<String, Object> config) {
        if (config == null || !(config.get(KEY) instanceof Map<?, ?> entries)) {
            return List.of();
        }
        return entries.keySet().stream().map(String::valueOf).toList();
    }

    /**
     * The variables of a block's config with their templates filled in against the run, in the order written: what
     * a shell line of the block finds set now. Platform data in a template taints the run as it does in a shell
     * line, and a NUL is taken out of a value.
     *
     * @throws WorkflowExecutionException for {@code values} that are not valid ({@link #problems}); nothing runs
     */
    public static Map<String, String> resolve(Map<String, Object> config, ExecutionContext context) {
        if (config == null || config.get(KEY) == null) {
            return Map.of();
        }
        List<String> problems = problems(config);
        if (!problems.isEmpty()) {
            throw new WorkflowExecutionException(String.join("; ", problems));
        }
        Map<String, String> values = new LinkedHashMap<>();
        ((Map<?, ?>) config.get(KEY)).forEach((name, template) -> values.put(String.valueOf(name),
                ShellVariables.withoutNul(context.resolveShellValue((String) template))));
        return values;
    }

    /**
     * Values that were filled in before, as {@link #resolve} returned them and a wrapper put them into the config of
     * its inner shell step: taken as they are, never filled in twice.
     */
    public static Map<String, String> resolved(Map<String, Object> config) {
        if (config == null || !(config.get(KEY) instanceof Map<?, ?> entries)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        entries.forEach((name, value) -> values.put(String.valueOf(name), String.valueOf(value)));
        return values;
    }
}
