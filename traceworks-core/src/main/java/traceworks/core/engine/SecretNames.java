package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;
import traceworks.core.port.ReservedVariables;
import traceworks.core.port.WorkflowResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The {@code secrets} key of a block: the names of the secrets a {@code shell} step gets as environment
 * variables, its tool mode and the inner shell steps of the wrappers included. A name is literal, matches
 * {@code [A-Z_][A-Z0-9_]*}, and is none the step's environment or the container client sets: not {@code PATH},
 * {@code HOME}, {@code TMPDIR}, {@code LANG}, {@code USER}, no name that starts with {@code TRACEWORKS_},
 * {@code LC_} or {@code TINI_} (the container's init reads those), none {@link ReservedVariables} reserves, and no
 * credential name of an AI provider ({@link AiCli#CREDENTIALS}): a credential reaches only its
 * provider's containers. One rule for the run admission, the analyzer, the start check and the step, so they cannot
 * drift apart.
 */
public final class SecretNames {

    /** The block key. */
    public static final String KEY = "secrets";

    private static final Pattern NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    private SecretNames() {
    }

    /**
     * What is wrong with the {@code secrets} of a block's config, one line per problem, each starting with
     * {@code secrets}; empty when the key is absent or every name is valid.
     */
    public static List<String> problems(Map<String, Object> config) {
        if (config == null || !config.containsKey(KEY)) {
            return List.of();
        }
        if (!(config.get(KEY) instanceof List<?> entries)) {
            return List.of(KEY + " is not a list of names — write it as [\"NAME\"]");
        }
        List<String> problems = new ArrayList<>();
        for (Object entry : entries) {
            String problem = entry instanceof String name ? problem(name) : String.valueOf(entry) + " is not a name";
            if (problem != null) {
                problems.add(KEY + ": " + problem);
            }
        }
        return problems;
    }

    /**
     * Whether {@code name} has the form of a name: {@code [A-Z_][A-Z0-9_]*}. The credentials file takes every such
     * name, a reserved one and an AI provider's credential included; {@link #problem} says what a step may declare.
     */
    public static boolean wellFormed(String name) {
        return NAME.matcher(name).matches();
    }

    /** Why {@code name} cannot name a secret, starting with the name in quotes; {@code null} when it can. */
    public static String problem(String name) {
        String quoted = "\"" + name + "\"";
        if (name.contains("{{")) {
            return quoted + " is a placeholder — secret names are literal";
        }
        if (!NAME.matcher(name).matches()) {
            return quoted + " is not a secret name — use capital letters, digits and _, not starting with a digit";
        }
        if (name.startsWith("TRACEWORKS_")) {
            return quoted + " is reserved — names that start with TRACEWORKS_ are Traceworks' own; choose another"
                    + " name";
        }
        if (name.startsWith("LC_")) {
            return quoted + " is reserved — names that start with LC_ set the locale; choose another name";
        }
        if (name.startsWith("TINI_")) {
            return quoted + " is reserved — the container's init reads names that start with TINI_; choose another"
                    + " name";
        }
        if (ReservedVariables.reserved(name)) {
            return quoted + " is reserved — the step's environment or the container client uses it; choose another"
                    + " name";
        }
        if (AiCli.credentialNamesOfAllProviders().contains(name)) {
            return quoted + " is reserved — it is the credential of an AI provider, which only that provider's"
                    + " containers get; store the value under another name, such as DEPLOY_" + name;
        }
        return null;
    }

    /** The names in the {@code secrets} of a block's config, as written; an entry that is no string is left out. */
    public static List<String> of(Map<String, Object> config) {
        if (config == null || !(config.get(KEY) instanceof List<?> entries)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof String name) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Every name the definition declares, valid or not, sorted: in every block, nested blocks included, and in the
     * workflows its {@code sub-workflow} and {@code foreach} blocks name literally in {@code workflow_id}, at any
     * depth. A child that cannot be resolved, or a reference with a placeholder, is skipped: its run fails on its
     * own.
     *
     * @param children finds the child workflows; {@code null} looks into none
     */
    public static SortedSet<String> declared(WorkflowDefinition definition, WorkflowResolver children) {
        SortedSet<String> names = new TreeSet<>();
        for (WorkflowDefinition workflow : ChildWorkflows.reachable(definition, children)) {
            for (BlockDefinition block : workflow.blocks()) {
                if (block == null) {
                    continue;
                }
                names.addAll(of(block.getConfig()));
                NestedBlocks.visit(block.getConfig(), nested -> names.addAll(of(nested.config())));
            }
        }
        return names;
    }
}
