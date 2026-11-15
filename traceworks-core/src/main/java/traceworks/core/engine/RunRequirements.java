package traceworks.core.engine;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.WebAccess;
import traceworks.core.context.BlockAiSettings;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a workflow requires and what its AI steps may do, judged from the definition: one copy of the rules, so the run
 * admission refuses exactly what the analyzer reports as an error. Nested blocks are judged as the blocks they are.
 *
 * <p><b>Requirements.</b> A feature beyond the common core must be declared in {@code requires}:
 * <ul>
 *   <li>{@code network: true} where a shell step reads it needs {@value #NETWORK};</li>
 *   <li>a {@code container_image} {@code ./images/<name>} where a shell step reads it needs {@value #PACKAGE_IMAGES};
 *   </li>
 *   <li>a {@code tools} value other than {@code none} on a {@code simple} or {@code agentic} block, or on a
 *       wrapper ({@code foreach}, {@code retry}, {@code while}, {@code conditional}) for its AI step, needs
 *       {@value #AI_TOOLS};</li>
 *   <li>a {@code web} value other than {@code none} on one of the same blocks needs {@value #AI_WEB}. It is a switch
 *       of its own: {@code tools: none} with {@code web: search} needs only this one;</li>
 *   <li>a declared model choice ({@code modelChoices}) needs {@value #AI_MODEL_CHOICE}.</li>
 * </ul>
 * A missing requirement refuses the run ({@code missing-requirement}, at the first block that needs it); a declared one
 * that no block uses is a warning ({@code unused-requirement}). Where a shell step reads a key: a {@code shell} block,
 * a wrapper ({@code conditional}, {@code retry}, {@code while}, {@code foreach}) in shell mode, and a {@code simple}
 * block of a switch case in shell mode.
 *
 * <p><b>The tool policy.</b> A {@code simple} or {@code agentic} block's calls carry its
 * {@code tools}; every other AI call runs with {@code none}. Refused are: an {@code agentic} block without
 * {@code tools} ({@code tools-required}); a {@code tools} value the policy does not know, a placeholder included
 * ({@code invalid-config-value}); {@code mcp_servers} other than {@code []} and {@code user_mcp_servers: true}
 * ({@code unsupported-mcp-servers}).
 *
 * <p><b>Web.</b> The same blocks' calls carry their {@code web} ({@link WebAccess}). Refused are: a value that is no
 * mode, {@code fetch} without {@code web_hosts} and {@code web_hosts} without {@code fetch}, a host that is none, a
 * star mixed with names, a placeholder in either, and {@code untrusted_input: false} next to web
 * ({@code invalid-config-value}); and web on a step that runs on Pi, which has no web tool
 * ({@code unsupported-web}).
 *
 * <p><b>The provider.</b> Traceworks does not run Codex ({@link #CODEX_REFUSAL}). The workflow's
 * {@code provider} naming it, and a {@code provider} of its own on a block that makes an AI call, are refused
 * ({@code unsupported-provider}); a block that inherits the workflow's is covered by the first, so each is reported
 * once. A block's provider is its own, else the workflow's, else {@value BlockAiSettings#DEFAULT_PROVIDER}.
 *
 * <p><b>The model.</b> A workflow names the model of an AI step as it is, or refers to a declared choice
 * ({@link ModelChoices}); a declaration or a reference that cannot be used is refused ({@code invalid-model-choice}).
 * A placeholder in {@code model}, the workflow's or that of a block that makes an AI call, is refused
 * ({@value #PLACEHOLDER_IN_MODEL}): no run resolves it, so the CLI would be asked for a model of that text. A model
 * that a run picks is a declared choice.
 */
public final class RunRequirements {

    public static final String NETWORK = "container.network";
    public static final String PACKAGE_IMAGES = "package.images";
    public static final String AI_TOOLS = "ai.tools";
    public static final String AI_WEB = "ai.web";
    public static final String AI_MODEL_CHOICE = "ai.model_choice";

    /** The rule of a {@code model} that holds a placeholder. */
    public static final String PLACEHOLDER_IN_MODEL = "placeholder-in-model";

    private static final String PI = "PI";

    /** The rule of web on a step whose provider has no web tool. */
    public static final String UNSUPPORTED_WEB = "unsupported-web";

    private static final String CODEX = "CODEX";

    /** The rule of a provider that names Codex. */
    public static final String UNSUPPORTED_PROVIDER = "unsupported-provider";

    /** What a refused Codex provider is told. */
    public static final String CODEX_REFUSAL = "Codex is not supported; Traceworks runs Claude and Pi";

    /** What each requirement is used by, for the warning that none uses it. */
    private static final Map<String, String> NOT_USED = Map.of(
            NETWORK, "no shell step has network: true",
            PACKAGE_IMAGES, "no shell step runs a ./images/ image",
            AI_TOOLS, "no AI step has tools other than none",
            AI_WEB, "no AI step has web other than none",
            AI_MODEL_CHOICE, "the workflow declares no modelChoices");

    /** The blocks whose AI step reads {@code tools}: the two AI blocks, and the wrappers for their inner AI step. */
    private static final java.util.Set<String> TOOL_BLOCKS = java.util.Set.of("simple", "agentic", "foreach",
            "retry", "while", "conditional");

    private RunRequirements() {
    }

    /**
     * One finding of these rules.
     *
     * @param rule       the analyzer's rule key
     * @param refuses    whether the run admission refuses the workflow for it; otherwise it is a warning
     * @param blockIndex the top-level block it concerns; {@code -1} for the definition as a whole
     * @param nested     the nested block it concerns; {@code null} for the top-level block or the definition
     * @param message    what is wrong and what to do, without where it is
     */
    public record Problem(String rule, boolean refuses, int blockIndex, NestedBlocks.Nested nested, String message) {}

    /** Every problem of the definition, block by block, then the requirements nothing uses. */
    public static List<Problem> check(WorkflowDefinition definition) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Problem> firstUse = new LinkedHashMap<>();
        if (isCodex(definition.provider())) {
            problems.add(codexProblem(-1, null, "provider " + definition.provider().trim()));
        }
        if (definition.model() != null && definition.model().contains("{{")) {
            problems.add(placeholderInModel(-1, null, definition.model()));
        }
        problems.addAll(ModelChoices.problems(definition));
        if (!definition.modelChoices().isEmpty()) {
            use(firstUse, AI_MODEL_CHOICE, -1, null, "modelChoices");
        }
        List<BlockDefinition> blocks = definition.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) {
                continue;
            }
            int index = i;
            check(definition, block.getType(), block.getConfig(), false, index, null, problems, firstUse);
            NestedBlocks.visit(block.getConfig(), nested -> check(definition, nested.type(), nested.config(), true,
                    index, nested, problems, firstUse));
        }
        List<String> declared = definition.requires();
        firstUse.forEach((requirement, use) -> {
            if (!declared.contains(requirement)) {
                problems.add(use);
            }
        });
        for (String requirement : WorkflowDefinition.KNOWN_REQUIREMENTS) {
            if (declared.contains(requirement) && !firstUse.containsKey(requirement)) {
                problems.add(new Problem("unused-requirement", false, -1, null, "requires names \"" + requirement
                        + "\", but no step uses it (" + NOT_USED.get(requirement) + ") — remove it"));
            }
        }
        return problems;
    }

    private static void check(WorkflowDefinition definition, String type, Map<String, Object> config, boolean nested,
                              int index, NestedBlocks.Nested at, List<Problem> problems,
                              Map<String, Problem> firstUse) {
        if (type == null || config == null) {
            return;
        }
        if (readByAShellStep(type, config, nested)) {
            if (Boolean.TRUE.equals(config.get("network"))) {
                use(firstUse, NETWORK, index, at, "network: true");
            }
            if (config.get("container_image") instanceof String image && image.startsWith("./images/")) {
                use(firstUse, PACKAGE_IMAGES, index, at, "container_image " + image);
            }
        }
        if (config.containsKey("mcp_servers")
                && !(config.get("mcp_servers") instanceof List<?> servers && servers.isEmpty())) {
            problems.add(new Problem("unsupported-mcp-servers", true, index, at,
                    "mcp_servers: only [] is accepted — Traceworks gives AI steps no MCP servers yet"));
        }
        if (BlockDefinition.userMcpServers(config)) {
            problems.add(new Problem("unsupported-mcp-servers", true, index, at, "user_mcp_servers: true —"
                    + " Traceworks gives AI steps none of the MCP servers of the user's CLI configuration; remove it"));
        }
        if (!callsAi(type, config, nested)) {
            return;
        }
        if (config.get(BlockAiSettings.PROVIDER_KEY) instanceof String own && isCodex(own)) {
            problems.add(codexProblem(index, at, "provider " + own.trim()));
        }
        if (config.get(BlockAiSettings.MODEL_KEY) instanceof String model && model.contains("{{")) {
            problems.add(placeholderInModel(index, at, model));
        }
        boolean ownsTools = TOOL_BLOCKS.contains(type);
        ToolPolicy tools = ToolPolicy.NONE;
        if (ownsTools) {
            if (config.get(ToolPolicy.KEY) == null && "agentic".equals(type)) {
                problems.add(new Problem("tools-required", true, index, at, "an agentic block must state tools ("
                        + ToolPolicy.VALUES + ") — Traceworks gives it no tools it does not"
                        + " name, and without tools its actions change nothing"));
                return;
            }
            try {
                tools = ToolPolicy.of(config);
            } catch (IllegalArgumentException e) {
                problems.add(new Problem("invalid-config-value", true, index, at, e.getMessage()));
                return;
            }
            if (tools != ToolPolicy.NONE) {
                use(firstUse, AI_TOOLS, index, at, "tools: " + tools.value());
            }
            WebAccess web;
            try {
                web = WebAccess.of(config);
            } catch (IllegalArgumentException e) {
                problems.add(new Problem("invalid-config-value", true, index, at, e.getMessage()));
                return;
            }
            if (!web.enabled()) {
                return;
            }
            use(firstUse, AI_WEB, index, at, "web: " + web.value());
            for (String provider : providersOf(definition, config)) {
                if (PI.equals(provider.strip().toUpperCase(Locale.ROOT))) {
                    problems.add(new Problem(UNSUPPORTED_WEB, true, index, at, "web: " + web.value() + " on a step"
                            + " that runs on PI: Pi has no web tool — remove web, or run the step on CLAUDE"));
                    break;
                }
            }
        }
    }

    /**
     * Whether an AI step of a block of this type reads {@code tools}, {@code web} and {@code web_hosts}: the two AI
     * blocks, and the wrappers for their inner AI step. Every other AI call runs without tools and without web.
     */
    public static boolean ownsTools(String type) {
        return type != null && TOOL_BLOCKS.contains(type);
    }

    /**
     * The providers an AI step of this config can run on: every option's of the choice it refers to
     * ({@link ModelChoices#KEY}), else its own, the workflow's or the default.
     */
    private static List<String> providersOf(WorkflowDefinition definition, Map<String, Object> config) {
        if (config.get(ModelChoices.KEY) instanceof String name) {
            for (ModelChoices.Choice choice : ModelChoices.declared(definition)) {
                if (choice.name().equals(name)) {
                    return choice.options().stream().map(ModelChoices.Option::provider).toList();
                }
            }
        }
        return List.of(BlockAiSettings.providerOf(config, definition.provider()));
    }

    private static Problem placeholderInModel(int index, NestedBlocks.Nested at, String model) {
        return new Problem(PLACEHOLDER_IN_MODEL, true, index, at, "model \"" + model + "\" holds a placeholder, which"
                + " no run resolves: the CLI would be asked for a model of that text — name the model, or declare the"
                + " models a run may pick from in modelChoices and refer to the choice with " + ModelChoices.KEY);
    }

    /** Whether the provider id names Codex, compared ignoring case and edge spaces as the providers are matched. */
    public static boolean isCodex(String provider) {
        return provider != null && CODEX.equals(provider.strip().toUpperCase(Locale.ROOT));
    }

    private static Problem codexProblem(int index, NestedBlocks.Nested at, String what) {
        return new Problem(UNSUPPORTED_PROVIDER, true, index, at, what + ": " + CODEX_REFUSAL
                + " — use CLAUDE or PI");
    }

    /**
     * The {@code container_image} {@code ./images/<name>} values where a shell step reads them, block by block, as
     * problems for a workflow that comes from another package than the run's ({@code child-package-image}): a run
     * resolves {@code ./images/} against the package it was started with, so the step would not run its own package's
     * image. The run admission of such a workflow refuses each of them.
     */
    public static List<Problem> packageImagesOfAnotherPackage(WorkflowDefinition definition) {
        List<Problem> problems = new ArrayList<>();
        List<BlockDefinition> blocks = definition.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) {
                continue;
            }
            int index = i;
            packageImage(block.getType(), block.getConfig(), false, index, null, problems);
            NestedBlocks.visit(block.getConfig(), nested -> packageImage(nested.type(), nested.config(), true, index,
                    nested, problems));
        }
        return problems;
    }

    private static void packageImage(String type, Map<String, Object> config, boolean nested, int index,
                                     NestedBlocks.Nested at, List<Problem> problems) {
        if (readByAShellStep(type, config, nested) && config.get("container_image") instanceof String image
                && image.startsWith("./images/")) {
            problems.add(new Problem("child-package-image", true, index, at, "container_image " + image + " names an"
                    + " image of this workflow's package, which is another package than the run's: a run resolves"
                    + " ./images/ against the package it was started with, so the step would not run this package's"
                    + " image — give the image a registry name, or run this workflow as a run of its own"));
        }
    }

    private static void use(Map<String, Problem> firstUse, String requirement, int index, NestedBlocks.Nested at,
                            String what) {
        firstUse.putIfAbsent(requirement, new Problem("missing-requirement", true, index, at, what + " needs \""
                + requirement + "\" in requires — add it to the workflow's requires"));
    }

    /**
     * Whether a shell step reads the block's shell keys ({@code network}, {@code container_image}, {@code mounts}): a
     * {@code shell} block; a wrapper in shell mode, at the top level (a switch case runs only {@code shell} and
     * {@code simple}); a {@code simple} block of a switch case in shell mode.
     */
    public static boolean readByAShellStep(String type, Map<String, Object> config, boolean nested) {
        if (type == null || config == null) return false;
        return switch (type) {
            case "shell" -> true;
            case "conditional", "retry", "while", "foreach" -> !nested && shellMode(config);
            case "simple" -> nested && shellMode(config);
            default -> false;
        };
    }

    /**
     * Whether a block of this type and config makes an AI call that reads {@code provider}: {@code simple} (unless
     * nested in a switch case with {@code execution_mode: shell}, where it runs as a shell), {@code agentic} and
     * {@code compress-ai}; {@code retry}, {@code conditional} and {@code while} in {@code ai} mode, {@code foreach} in
     * {@code ai} mode when it runs a command per item; {@code approval} and {@code human-input} only with an AI actor
     * ({@code actor: ai} or {@code shadow}, or a placeholder that may resolve to one). Never {@code switch}: its case
     * blocks read their own.
     *
     * @param nested whether the block sits inside another block's config (a switch case)
     */
    public static boolean callsAi(String type, Map<String, Object> config, boolean nested) {
        if (type == null || config == null) return false;
        return switch (type) {
            case "simple" -> !(nested && shellMode(config));
            case "agentic", "compress-ai" -> true;
            case "retry", "conditional", "while" -> aiMode(config);
            case "foreach" -> aiMode(config) && !(config.get("workflow_id") instanceof String id && !id.isBlank());
            case "approval", "human-input" -> aiActor(config.get("actor"));
            default -> false;
        };
    }

    private static boolean shellMode(Map<String, Object> config) {
        return ExecutionMode.SHELL.equals(config.get(ExecutionMode.KEY));
    }

    private static boolean aiMode(Map<String, Object> config) {
        return ExecutionMode.AI.equals(config.get(ExecutionMode.KEY));
    }

    /** {@code ai} or {@code shadow} as the executor reads it (trimmed, any case), or a placeholder. */
    private static boolean aiActor(Object actor) {
        if (!(actor instanceof String value)) return false;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("ai") || normalized.equals("shadow") || value.contains("{{");
    }
}
