package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.engine.ModelChoices;
import traceworks.core.engine.PlatformVariables;
import traceworks.core.engine.RunVariables;
import traceworks.core.engine.StepOutputs;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for the <b>variables</b> of a definition: does something set what a block reads, and does a block read what a
 * run sets?
 *
 * <p>The case behind them: {@code {{ nme }}} for {@code {{ name }}} reached the shell as text and the run was green,
 * and {@code --input nme=Anna} was accepted while the greeting stayed {@code Hello, world}.
 *
 * <p>{@code unknown-variable} ({@code WARNING}): a placeholder {@code {{ name }}} that nothing in the definition
 * sets. A warning, not an error, because a workflow cannot declare its inputs: a variable may come only from a run
 * input ({@code --input}, or the variables a parent run hands to its child). What sets a variable, as the resolver
 * and the block executors do it:
 * <ul>
 *   <li>the definition's {@code defaultContext} and the inputs of the run ({@link #apply} gets their names);</li>
 *   <li>the engine: {@code previous_output}, the {@link RunVariables} and {@code package_dir};</li>
 *   <li>a block, at the top level or nested: the {@code variable_name} of an {@code approval} (default
 *       {@code approval_answer}), of a {@code human-input} (default {@code user_input}) and of each of its
 *       {@code fields}, the {@code variable_name} of a {@code foreach} (default {@code item}) with its
 *       {@code <name>_index} and the names of its {@code context} entries, and {@code agentic_goal_achieved} and
 *       {@code agentic_iterations} of an {@code agentic} block. Where a block sits is not judged: a variable a later
 *       block sets is known to an earlier one.</li>
 * </ul>
 * Not judged, so that a finding is a typo and not noise: a key that is no plain name ({@code {{ item.id }}}, a field
 * of a {@code foreach} item, or {@code {{ .State.Running }}}, the template of another tool in a shell command), and
 * what another rule judges ({@code block.*}, {@code step_N_output}, the chain variables, the platform variables and
 * the other variables Traceworks does not provide). A definition in which a {@code variable_name} is itself a
 * placeholder is not judged at all.
 */
final class VariableRules {

    private VariableRules() {}

    /** A plain variable name: what a person writes as an input or a {@code variable_name}. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The config key under which a block names the variable it sets. */
    private static final String VARIABLE_NAME = "variable_name";

    /** The variable a block sets when its {@value #VARIABLE_NAME} is absent, per block type. */
    private static final Map<String, String> DEFAULT_VARIABLE = Map.of("approval", "approval_answer",
            "human-input", "user_input", "foreach", "item");

    /** What an {@code agentic} block sets when it ends. */
    private static final Set<String> AGENTIC_VARIABLES = Set.of("agentic_goal_achieved", "agentic_iterations");

    /** Protection against pathologically deep configs, as in {@link BlockText}. */
    private static final int MAX_DEPTH = 12;

    /**
     * @param inputs the names of the inputs of the run the definition is checked for; empty when it is checked on its
     *               own
     */
    static void apply(WorkflowDefinition workflow, Set<String> inputs, List<AnalyzerFinding> out) {
        List<BlockDefinition> blocks = workflow.blocks();
        Set<String> set = new LinkedHashSet<>(workflow.defaultContext().keySet());
        set.addAll(inputs);
        set.addAll(ModelChoices.variableNames(workflow));   // the run sets them for its picked model choices
        if (!addBlockVariables(blocks, set)) return;
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            Set<String> unknown = new LinkedHashSet<>();
            for (String key : PlaceholderSyntax.keysIn(BlockText.joined(block))) {
                if (PLAIN_NAME.matcher(key).matches() && !set.contains(key) && !providedOrJudgedElsewhere(key)) {
                    unknown.add(key);
                }
            }
            if (unknown.isEmpty()) continue;
            String them = unknown.size() == 1 ? "it" : "them";
            out.add(AnalyzerFinding.of("unknown-variable", Severity.WARNING, i, block.getName(),
                    String.join(", ", unknown.stream().map(VariableRules::placeholder).toList())
                            + " — nothing sets " + them + suggestions(unknown, set) + ": neither defaultContext nor a"
                            + " block does, and Traceworks does not provide " + them + "; only a run input of the same"
                            + " name (--input, or the parent run of a sub-workflow) gives a value, otherwise the"
                            + " placeholder stays literal in the run"));
        }
    }

    /**
     * What is wrong with the inputs of a run of {@code workflow}, one text per input: an input that no block reads
     * and that is not in {@code defaultContext} changes nothing in the run, which is mostly a typo of its name. A
     * block reads an input by a placeholder, at the top level or nested. {@code previous_output} and the reserved
     * names ({@code __...}) are read by the engine. A workflow that starts a child ({@code sub-workflow}, or a block
     * with a {@code workflow_id}) is not judged: the child gets every variable of its parent.
     *
     * @param inputs the names of the inputs
     */
    static List<String> unreadInputs(WorkflowDefinition workflow, Collection<String> inputs) {
        List<BlockDefinition> blocks = workflow.blocks();
        if (inputs.isEmpty() || startsAChild(blocks)) return List.of();
        Set<String> read = new LinkedHashSet<>(workflow.defaultContext().keySet());
        read.addAll(workflow.modelChoices().keySet());   // the input of a model choice's name picks its option
        for (BlockDefinition block : blocks) {
            read.addAll(PlaceholderSyntax.keysIn(BlockText.joined(block)));
        }
        List<String> problems = new ArrayList<>();
        for (String input : new LinkedHashSet<>(inputs)) {
            if (read.contains(input) || "previous_output".equals(input) || input.startsWith("__")) continue;
            String near = FindingText.suggestion(input, plainNames(read));
            problems.add("input \"" + input + "\" is read by no block and is not in defaultContext, so it changes"
                    + " nothing in this run" + (near == null ? "" : "; did you mean \"" + near + "\"?"));
        }
        return problems;
    }

    /** What the engine provides, or what a rule of its own judges: no unknown variable either way. */
    private static boolean providedOrJudgedElsewhere(String key) {
        return "previous_output".equals(key) || RunVariables.NAMES.contains(key)
                || RunVariables.PACKAGE_DIR.equals(key) || StepOutputs.isStepOutput(key)
                || WiringRules.CHAIN_ONLY.contains(key) || PlatformVariables.contains(key)
                || TraceworksRules.unprovided(key);
    }

    /**
     * Adds the variables the blocks set, nested blocks included; {@code false} when a block names its variable by a
     * placeholder, so that the names are only known in the run.
     */
    private static boolean addBlockVariables(List<BlockDefinition> blocks, Set<String> set) {
        List<String> named = new ArrayList<>();
        for (BlockDefinition block : blocks) {
            if (block == null) continue;
            addBlockVariables(block.getType(), block.getConfig(), set, named);
            NestedBlocks.visit(block.getConfig(), nested -> addBlockVariables(nested.type(), nested.config(), set,
                    named));
        }
        set.addAll(named);
        return named.stream().noneMatch(name -> name.contains("{{"));
    }

    private static void addBlockVariables(String type, Map<String, Object> config, Set<String> set,
                                          List<String> named) {
        if ("agentic".equals(type)) set.addAll(AGENTIC_VARIABLES);
        List<String> own = new ArrayList<>();
        collectVariableNames(config, own, 0, true);
        boolean namesItsVariable = config != null && config.get(VARIABLE_NAME) instanceof String name
                && !name.isBlank();
        if (!namesItsVariable && type != null && DEFAULT_VARIABLE.containsKey(type)) own.add(DEFAULT_VARIABLE.get(type));
        if ("foreach".equals(type)) {
            List.copyOf(own).forEach(name -> own.add(name + "_index"));
            if (config != null && config.get("context") instanceof Map<?, ?> mapping) {
                mapping.keySet().forEach(name -> own.add(String.valueOf(name)));
            }
        }
        named.addAll(own);
    }

    /**
     * Every text under a {@value #VARIABLE_NAME} key of one block's config, at any depth (the {@code fields} of a
     * human input), without the configs of its nested blocks, which are visited on their own.
     */
    private static void collectVariableNames(Object value, List<String> named, int depth, boolean top) {
        if (value == null || depth > MAX_DEPTH) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (VARIABLE_NAME.equals(entry.getKey()) && entry.getValue() instanceof String name
                        && !name.isBlank()) {
                    named.add(name);
                } else if (!(top && isNestedBlockList(entry.getKey()))) {
                    collectVariableNames(entry.getValue(), named, depth + 1, false);
                }
            }
        } else if (value instanceof Collection<?> items) {
            items.forEach(item -> collectVariableNames(item, named, depth + 1, false));
        }
    }

    /** The config keys under which {@link NestedBlocks} finds nested blocks. */
    private static boolean isNestedBlockList(Object key) {
        return "cases".equals(key) || "default".equals(key) || "blocks".equals(key);
    }

    /** Whether a block starts a child workflow: a {@code sub-workflow}, or any block with a {@code workflow_id}. */
    private static boolean startsAChild(List<BlockDefinition> blocks) {
        boolean[] found = {false};
        for (BlockDefinition block : blocks) {
            if (block == null) continue;
            found[0] |= startsAChild(block.getType(), block.getConfig());
            NestedBlocks.visit(block.getConfig(), nested -> found[0] |= startsAChild(nested.type(), nested.config()));
        }
        return found[0];
    }

    private static boolean startsAChild(String type, Map<String, Object> config) {
        return "sub-workflow".equals(type) || config != null && config.containsKey("workflow_id");
    }

    /** {@code  (did you mean {{ name }} for {{ nme }}?)} for the unknown names with a near known one, or nothing. */
    private static String suggestions(Set<String> unknown, Set<String> set) {
        Set<String> known = plainNames(set);
        known.addAll(RunVariables.NAMES);
        known.add(RunVariables.PACKAGE_DIR);
        known.add("previous_output");
        List<String> near = new ArrayList<>();
        for (String name : unknown) {
            String suggestion = FindingText.suggestion(name, known);
            if (suggestion != null) near.add(placeholder(suggestion) + " for " + placeholder(name));
        }
        return near.isEmpty() ? "" : " (did you mean " + String.join(", ", near) + "?)";
    }

    private static Set<String> plainNames(Set<String> names) {
        Set<String> plain = new LinkedHashSet<>();
        for (String name : names) {
            if (PLAIN_NAME.matcher(name).matches()) plain.add(name);
        }
        return plain;
    }

    private static String placeholder(String name) {
        return "{{ " + name + " }}";
    }
}
