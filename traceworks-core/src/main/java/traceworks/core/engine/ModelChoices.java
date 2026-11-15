package traceworks.core.engine;

import traceworks.core.context.BlockAiSettings;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ModelPick;
import traceworks.core.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The model choices of a workflow: the workflow names the model of an AI step, and it may open the choice. A
 * definition declares a choice under {@code model_choices}: a name, a {@code default} and the {@code options}, each a
 * {@code provider} and {@code model} pair. A block refers to it with {@value #KEY} instead of naming
 * {@code provider} and {@code model}. Whoever starts the run picks one option by its name, as the run input of the
 * choice's name; without a pick the default holds; anything else is refused.
 *
 * <p>{@link #resolve} is the one place that decides: it reads the pick from the inputs a run starts with, never from
 * anything a step made, and returns the definition as the run runs it, every {@value #KEY} replaced by the picked
 * option's {@code provider} and {@code model}. Whatever reads a provider or a model after it (the admission, the
 * executors, the start check) reads those two keys, as for a workflow without a choice. A definition that declares
 * no choice and refers to none comes back as it is.
 *
 * <p>A run also gets three variables per choice, set after its inputs so that none replaces them: the choice's name
 * (the picked option), {@code <name>_provider} and {@code <name>_model}.
 */
public final class ModelChoices {

    /** The config key by which a block refers to a declared choice. */
    public static final String KEY = "model_choice";

    /** The rule of a declaration or a reference that cannot be used. */
    public static final String RULE = "invalid-model-choice";

    /** The suffixes of the two variables that name the picked option's provider and model. */
    public static final String PROVIDER_SUFFIX = "_provider";
    public static final String MODEL_SUFFIX = "_model";

    private static final Pattern CHOICE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern OPTION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> CHOICE_KEYS = Set.of("default", "options");
    private static final Set<String> OPTION_KEYS = Set.of("provider", "model");

    private ModelChoices() {
    }

    /** One option of a choice: a provider and a model that belong together. */
    public record Option(String name, String provider, String model) {}

    /**
     * A declared choice.
     *
     * @param defaultOption the name of the option a run without a pick runs with
     * @param options       the options, in declaration order
     */
    public record Choice(String name, String defaultOption, List<Option> options) {

        public Choice {
            options = List.copyOf(options);
        }

        /** The option of this name. */
        public Optional<Option> option(String optionName) {
            return options.stream().filter(option -> option.name().equals(optionName)).findFirst();
        }

        /** The names of the options, in declaration order. */
        public List<String> optionNames() {
            return options.stream().map(Option::name).toList();
        }
    }

    /**
     * A block that refers to a choice.
     *
     * @param blockIndex the top-level block, or the one the nested block sits in
     * @param nested     the nested block; {@code null} for the top-level block
     * @param choice     the name it refers to, as written
     */
    public record Use(int blockIndex, NestedBlocks.Nested nested, String choice) {}

    /**
     * What {@link #resolve} made of a definition and a run's inputs.
     *
     * @param definition the definition as the run runs it; the one given when there are problems
     * @param picks      the picked option of every declared choice, in declaration order; empty with problems
     * @param problems   why the run is refused: a pick that is none of the options, an input named like the
     *                   provider or model variable of a choice. A declaration or a reference that
     *                   cannot be used is not repeated here: {@link #problems} reports it, and the run admission
     *                   refuses the definition for it
     */
    public record Resolution(WorkflowDefinition definition, List<ModelPick> picks, List<String> problems) {

        public Resolution {
            picks = List.copyOf(picks);
            problems = List.copyOf(problems);
        }

        /** The variables the run gets for its picks, by name. */
        public Map<String, String> variables() {
            Map<String, String> variables = new LinkedHashMap<>();
            for (ModelPick pick : picks) {
                variables.put(pick.choice(), pick.option());
                variables.put(pick.choice() + PROVIDER_SUFFIX, pick.provider());
                variables.put(pick.choice() + MODEL_SUFFIX, pick.model());
            }
            return variables;
        }
    }

    /** The choices the definition declares that can be used, in declaration order. */
    public static List<Choice> declared(WorkflowDefinition definition) {
        List<Choice> choices = new ArrayList<>();
        definition.modelChoices().forEach((name, raw) -> {
            if (declarationProblems(definition, name, raw).isEmpty()) {
                choices.add(choice(name, (Map<?, ?>) raw));
            }
        });
        return choices;
    }

    /** The names of the variables a run of the definition gets for its choices. */
    public static List<String> variableNames(WorkflowDefinition definition) {
        List<String> names = new ArrayList<>();
        for (Choice choice : declared(definition)) {
            names.add(choice.name());
            names.add(choice.name() + PROVIDER_SUFFIX);
            names.add(choice.name() + MODEL_SUFFIX);
        }
        return names;
    }

    /** Every block that refers to a choice with a text, nested blocks included, in definition order. */
    public static List<Use> uses(WorkflowDefinition definition) {
        List<Use> uses = new ArrayList<>();
        List<BlockDefinition> blocks = definition.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null || block.getConfig() == null) {
                continue;
            }
            int index = i;
            if (block.getConfig().get(KEY) instanceof String name) {
                uses.add(new Use(index, null, name));
            }
            NestedBlocks.visit(block.getConfig(), nested -> {
                if (nested.config() != null && nested.config().get(KEY) instanceof String name) {
                    uses.add(new Use(index, nested, name));
                }
            });
        }
        return uses;
    }

    /**
     * What is wrong with the declaration and with the blocks that refer to it, as problems the run admission refuses
     * the workflow for ({@value #RULE}): a declaration that is not understood completely, a default that is none of
     * the options, a name a run sets itself, a {@code default_context} entry of a choice's name or of one of its two
     * other variables, two choices that would set the same variable, a block that sets a variable of a choice, a
     * block that refers to a choice that is not declared, and a block that names {@code provider} or {@code model}
     * next to {@value #KEY}.
     */
    public static List<RunRequirements.Problem> problems(WorkflowDefinition definition) {
        List<RunRequirements.Problem> problems = new ArrayList<>();
        definition.modelChoices().forEach((name, raw) -> declarationProblems(definition, name, raw)
                .forEach(message -> problems.add(new RunRequirements.Problem(RULE, true, -1, null, message))));
        Set<String> names = definition.modelChoices().keySet();
        Map<String, String> owners = variableOwners(names);
        for (String name : names) {
            for (String variable : variablesOf(name)) {
                String owner = owners.get(variable);
                // Only a choice named like another one's provider or model variable can collide
                if (!owner.equals(name)) {
                    problems.add(new RunRequirements.Problem(RULE, true, -1, null, "model_choices." + owner
                            + ": the run sets the variable \"" + variable + "\" for the model choice \"" + name
                            + "\" too — give one of the two choices another name"));
                }
            }
        }
        List<BlockDefinition> blocks = definition.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) {
                continue;
            }
            int index = i;
            setVariables(block.getType(), block.getConfig(), owners, index, null, problems);
            NestedBlocks.visit(block.getConfig(), nested -> setVariables(nested.type(), nested.config(), owners,
                    index, nested, problems));
            if (block.getConfig() == null) {
                continue;
            }
            reference(block.getConfig(), names, index, null, problems);
            NestedBlocks.visit(block.getConfig(), nested -> {
                if (nested.config() != null) {
                    reference(nested.config(), names, index, nested, problems);
                }
            });
        }
        return problems;
    }

    /** The three variables a run sets for a choice of this name. */
    private static List<String> variablesOf(String choice) {
        return List.of(choice, choice + PROVIDER_SUFFIX, choice + MODEL_SUFFIX);
    }

    /**
     * Which choice each variable belongs to: where two choices would set one variable (the choices {@code x} and
     * {@code x_model}), the choice the variable is the name of.
     */
    private static Map<String, String> variableOwners(Set<String> choices) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String choice : choices) {
            for (String variable : variablesOf(choice)) {
                if (variable.equals(choice) || !owners.containsKey(variable)) {
                    owners.put(variable, choice);
                }
            }
        }
        return owners;
    }

    /** The variable a block of a type sets when its config names none. */
    private static final Map<String, String> DEFAULT_VARIABLE = Map.of("foreach", "item", "approval",
            "approval_answer", "human-input", "user_input");

    /**
     * A problem for each variable of a choice that a block would set: its {@code variable_name} (at any depth of its
     * config, as the {@code fields} of a human input name theirs), the default name of its type without one, the
     * {@code <variable_name>_index} of a loop, and what an {@code agentic} block sets when it ends. The variables of
     * a pick say who runs the steps of the choice, so no step may replace one. A {@code context} of a loop is not
     * among them: it sets variables of the child run, which sets its own picks after them.
     */
    private static void setVariables(String type, Map<String, Object> config, Map<String, String> owners, int index,
                                     NestedBlocks.Nested nested, List<RunRequirements.Problem> problems) {
        if (owners.isEmpty() || type == null) {
            return;
        }
        Set<String> set = new java.util.LinkedHashSet<>();
        collectVariableNames(config, set, 0, true);
        boolean own = config != null && config.get("variable_name") instanceof String name && !name.isBlank();
        if (!own && DEFAULT_VARIABLE.containsKey(type)) {
            set.add(DEFAULT_VARIABLE.get(type));
        }
        if ("foreach".equals(type)) {
            String item = own ? ((String) config.get("variable_name")).strip() : DEFAULT_VARIABLE.get(type);
            set.add(item + "_index");
        }
        if ("agentic".equals(type)) {
            set.add("agentic_goal_achieved");
            set.add("agentic_iterations");
        }
        for (String variable : set) {
            String choice = owners.get(variable);
            if (choice != null) {
                problems.add(new RunRequirements.Problem(RULE, true, index, nested, "the block sets the variable \""
                        + variable + "\", which the run sets for the model choice \"" + choice
                        + "\" — give the block's variable another name"));
            }
        }
    }

    /** Every text under a {@code variable_name} key of a config, without the blocks nested in it. */
    private static void collectVariableNames(Object value, Set<String> named, int depth, boolean top) {
        if (value == null || depth > 12) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("variable_name".equals(entry.getKey()) && entry.getValue() instanceof String name
                        && !name.isBlank()) {
                    named.add(name.strip());
                } else if (!(top && ("cases".equals(entry.getKey()) || "default".equals(entry.getKey())
                        || "blocks".equals(entry.getKey())))) {
                    collectVariableNames(entry.getValue(), named, depth + 1, false);
                }
            }
        } else if (value instanceof java.util.Collection<?> items) {
            items.forEach(item -> collectVariableNames(item, named, depth + 1, false));
        }
    }

    private static void reference(Map<String, Object> config, Set<String> declared, int index,
                                  NestedBlocks.Nested nested, List<RunRequirements.Problem> problems) {
        Object value = config.get(KEY);
        if (value == null) {
            return;
        }
        if (!(value instanceof String name) || name.isBlank()) {
            problems.add(new RunRequirements.Problem(RULE, true, index, nested, KEY + " must be the name of a choice"
                    + " declared in model_choices, a text"));
            return;
        }
        if (!declared.contains(name)) {
            problems.add(new RunRequirements.Problem(RULE, true, index, nested, KEY + " \"" + name + "\" is not"
                    + " declared in model_choices" + (declared.isEmpty() ? " (the workflow declares none)"
                    : " (declared: " + String.join(", ", declared) + ")")));
        }
        for (String own : List.of(BlockAiSettings.PROVIDER_KEY, BlockAiSettings.MODEL_KEY)) {
            if (config.get(own) != null) {
                problems.add(new RunRequirements.Problem(RULE, true, index, nested, KEY + " \"" + name + "\" next to "
                        + own + ": the choice names the provider and the model — remove " + own));
            }
        }
    }

    private static List<String> declarationProblems(WorkflowDefinition definition, String name, Object raw) {
        List<String> problems = new ArrayList<>();
        String where = "model_choices." + name;
        if (name == null || !CHOICE_NAME.matcher(name).matches()) {
            problems.add(where + ": the name of a choice is the name of the run input that picks, so it must be a"
                    + " plain name (letters, digits and _, not starting with a digit)");
        } else if (reserved(name)) {
            problems.add(where + ": a run sets a variable of this name itself — give the choice another name");
        }
        if (definition.defaultContext().containsKey(name)) {
            problems.add(where + ": default_context sets \"" + name + "\" too — the default of a choice stands in its"
                    + " declaration; remove the default_context entry");
        }
        for (String variable : List.of(name + PROVIDER_SUFFIX, name + MODEL_SUFFIX)) {
            if (definition.defaultContext().containsKey(variable)) {
                problems.add(where + ": default_context sets \"" + variable + "\", which the run sets for the choice"
                        + " itself — remove the default_context entry");
            }
        }
        if (!(raw instanceof Map<?, ?> choice)) {
            problems.add(where + " must be an object with default and options");
            return problems;
        }
        for (Object key : choice.keySet()) {
            if (!CHOICE_KEYS.contains(String.valueOf(key))) {
                problems.add(where + " has the unknown key \"" + key + "\" (known: default, options)");
            }
        }
        if (!(choice.get("options") instanceof Map<?, ?> options) || options.isEmpty()) {
            problems.add(where + ".options must be an object of at least one option, each a provider and a model");
            return problems;
        }
        options.forEach((optionName, option) -> option(where + ".options." + optionName, optionName, option,
                problems));
        if (!(choice.get("default") instanceof String defaultOption)) {
            problems.add(where + ".default must name one of the options (" + names(options) + ")");
        } else if (!options.containsKey(defaultOption)) {
            problems.add(where + ".default \"" + defaultOption + "\" is none of the options (" + names(options)
                    + ")");
        }
        return problems;
    }

    private static void option(String where, Object name, Object raw, List<String> problems) {
        if (!(name instanceof String text) || !OPTION_NAME.matcher(text).matches()) {
            problems.add(where + ": the name of an option is what a run input picks, so it must be letters, digits,"
                    + " '.', '_' and '-', starting with a letter or a digit");
        }
        if (!(raw instanceof Map<?, ?> option)) {
            problems.add(where + " must be an object with provider and model");
            return;
        }
        for (Object key : option.keySet()) {
            if (!OPTION_KEYS.contains(String.valueOf(key))) {
                problems.add(where + " has the unknown key \"" + key + "\" (known: provider, model)");
            }
        }
        for (String key : List.of("provider", "model")) {
            if (!(option.get(key) instanceof String value) || value.isBlank()) {
                problems.add(where + "." + key + " must be a text: an option is a provider and a model that belong"
                        + " together");
            } else if (value.contains("{{")) {
                problems.add(where + "." + key + " holds a placeholder, which is never resolved: an option names its "
                        + key + " as it is");
            } else if (key.equals("provider") && RunRequirements.isCodex(value)) {
                problems.add(where + ".provider " + value.strip() + ": " + RunRequirements.CODEX_REFUSAL
                        + " — use CLAUDE or PI");
            }
        }
    }

    private static String names(Map<?, ?> options) {
        return String.join(", ", options.keySet().stream().map(String::valueOf).toList());
    }

    /** Whether a run sets a variable of this name itself, or reads the name as something else than an input. */
    private static boolean reserved(String name) {
        return RunVariables.NAMES.contains(name) || RunVariables.PACKAGE_DIR.equals(name)
                || PlatformVariables.contains(name) || StepOutputs.isStepOutput(name)
                || "previous_output".equals(name) || name.startsWith("__");
    }

    private static Choice choice(String name, Map<?, ?> raw) {
        List<Option> options = new ArrayList<>();
        ((Map<?, ?>) raw.get("options")).forEach((optionName, option) -> {
            Map<?, ?> pair = (Map<?, ?>) option;
            options.add(new Option((String) optionName, ((String) pair.get("provider")).strip(),
                    ((String) pair.get("model")).strip()));
        });
        return new Choice(name, (String) raw.get("default"), options);
    }

    /**
     * The definition as a run with {@code inputs} runs it, and the picks.
     *
     * <p>The pick of a choice is the input of its name, a text that names one of its options; without such an input
     * the default. The inputs are those a run starts with: for a child run, those of the run that started first, so
     * that no step of a parent chooses a child's model.
     *
     * @param inputs the inputs the run starts with; {@code null} for none
     */
    public static Resolution resolve(WorkflowDefinition definition, Map<String, ?> inputs) {
        if (definition.modelChoices().isEmpty() && uses(definition).isEmpty()) {
            return new Resolution(definition, List.of(), List.of());
        }
        if (!problems(definition).isEmpty()) {
            return new Resolution(definition, List.of(), List.of());
        }
        List<String> problems = new ArrayList<>();
        Map<String, Option> picked = new LinkedHashMap<>();
        List<ModelPick> picks = new ArrayList<>();
        for (Choice choice : declared(definition)) {
            for (String variable : List.of(choice.name() + PROVIDER_SUFFIX, choice.name() + MODEL_SUFFIX)) {
                if (inputs != null && inputs.containsKey(variable)) {
                    problems.add("the input " + variable + " names a variable the run sets for the model choice \""
                            + choice.name() + "\": the input " + choice.name() + " picks an option, and nothing"
                            + " else sets its provider or its model");
                }
            }
            Object given = inputs == null ? null : inputs.get(choice.name());
            String optionName = given == null ? choice.defaultOption() : given instanceof String text ? text : null;
            Option option = optionName == null ? null : choice.option(optionName).orElse(null);
            if (option == null) {
                problems.add("the model choice \"" + choice.name() + "\" has no option "
                        + (given instanceof String text ? "\"" + text + "\"" : "that is no text") + ": the input "
                        + choice.name() + " picks one of " + String.join(", ", choice.optionNames())
                        + " (the default is " + choice.defaultOption() + ")");
                continue;
            }
            picked.put(choice.name(), option);
            picks.add(new ModelPick(choice.name(), option.name(), option.provider(), option.model(),
                    option.name().equals(choice.defaultOption())));
        }
        if (!problems.isEmpty()) {
            return new Resolution(definition, List.of(), problems);
        }
        List<BlockDefinition> blocks = new ArrayList<>();
        for (BlockDefinition block : definition.blocks()) {
            blocks.add(block == null || !refers(block.getConfig()) ? block : applied(block, picked));
        }
        return new Resolution(definition.withBlocks(blocks), picks, List.of());
    }

    /**
     * The definition under every single pick: for each option of each choice, the definition a run that picks it
     * runs (the other choices at their defaults), and the one of a run that picks nothing. For whoever must know
     * everything any run of the package could use, such as the images to build. A definition without a usable
     * declaration is the only entry.
     */
    public static List<WorkflowDefinition> everyPick(WorkflowDefinition definition) {
        List<WorkflowDefinition> forms = new ArrayList<>();
        forms.add(resolve(definition, Map.of()).definition());
        for (Choice choice : declared(definition)) {
            for (Option option : choice.options()) {
                if (!option.name().equals(choice.defaultOption())) {
                    forms.add(resolve(definition, Map.of(choice.name(), option.name())).definition());
                }
            }
        }
        return forms;
    }

    /** Whether the config, or a block nested in it, refers to a choice. */
    private static boolean refers(Map<String, Object> config) {
        if (config == null) {
            return false;
        }
        boolean[] found = {config.get(KEY) != null};
        NestedBlocks.visit(config, nested -> found[0] |= nested.config() != null && nested.config().get(KEY) != null);
        return found[0];
    }

    private static BlockDefinition applied(BlockDefinition block, Map<String, Option> picked) {
        BlockDefinition copy = BlockDefinition.builder().type(block.getType()).name(block.getName())
                .config(appliedConfig(block.getConfig(), picked)).build();
        block.unknownFields().forEach(copy::putUnknownField);
        return copy;
    }

    /** A copy of the config with the picked option in place of {@value #KEY}, in the blocks nested in it too. */
    private static Map<String, Object> appliedConfig(Map<String, Object> config, Map<String, Option> picked) {
        Map<String, Object> copy = new LinkedHashMap<>(config);
        if (copy.remove(KEY) instanceof String name) {
            Option option = picked.get(name);
            copy.put(BlockAiSettings.PROVIDER_KEY, option.provider());
            copy.put(BlockAiSettings.MODEL_KEY, option.model());
        }
        if (copy.get("cases") instanceof List<?> cases) {
            List<Object> appliedCases = new ArrayList<>();
            for (Object entry : cases) {
                if (entry instanceof Map<?, ?> caseEntry && caseEntry.get("blocks") instanceof List<?> blocks) {
                    Map<Object, Object> appliedCase = new LinkedHashMap<>(caseEntry);
                    appliedCase.put("blocks", appliedBlocks(blocks, picked));
                    appliedCases.add(appliedCase);
                } else {
                    appliedCases.add(entry);
                }
            }
            copy.put("cases", appliedCases);
        }
        for (String list : List.of("default", "blocks")) {
            if (copy.get(list) instanceof List<?> blocks) {
                copy.put(list, appliedBlocks(blocks, picked));
            }
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> appliedBlocks(List<?> blocks, Map<String, Option> picked) {
        List<Object> applied = new ArrayList<>();
        for (Object entry : blocks) {
            if (entry instanceof Map<?, ?> nested && nested.get("config") instanceof Map<?, ?> config) {
                Map<Object, Object> copy = new LinkedHashMap<>(nested);
                copy.put("config", appliedConfig((Map<String, Object>) config, picked));
                applied.add(copy);
            } else {
                applied.add(entry);
            }
        }
        return applied;
    }
}
