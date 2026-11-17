package traceworks.core.describe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.BlockAiSettings;
import traceworks.core.context.UntrustedInput;
import traceworks.core.container.ImageCatalog;
import traceworks.core.container.ImageFacts;
import traceworks.core.describe.WorkflowDescription.Audit;
import traceworks.core.describe.WorkflowDescription.Block;
import traceworks.core.describe.WorkflowDescription.Counts;
import traceworks.core.describe.WorkflowDescription.Line;
import traceworks.core.describe.WorkflowDescription.Section;
import traceworks.core.engine.ShellValues;
import traceworks.core.engine.ModelChoices;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.engine.RunRequirements;
import traceworks.core.engine.SecretNames;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageImage;
import traceworks.core.port.WebAccess;
import traceworks.core.port.WorkflowResolver;
import traceworks.core.record.WorkflowOutline;
import traceworks.core.model.AuditConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads a loaded package into its {@link WorkflowDescription}. What a block is and may touch is asked of the classes
 * the run asks: {@link RunRequirements} for whether it runs a shell command or calls an AI, {@link BlockSettings} for
 * its settings, {@link BlockAiSettings}, {@link ToolPolicy}, {@link WebAccess} and {@link UntrustedInput} for its AI
 * call, {@link SecretNames} for its secrets, {@link NestedBlocks} for the blocks it holds and {@link ChildWorkflows}
 * for the workflow it starts. A package that loads is always described: a value the run would refuse is named as
 * invalid, and {@code traceworks check} says why.
 *
 * <p>Nothing is started and nothing is looked up but child workflows: a secret is a name here, never a value.
 */
public final class WorkflowDescriber {

    /** How deep child workflows of child workflows are shown; as deep as the start check follows them. */
    private static final int MAX_DEPTH = 16;

    private static final String INVALID = ": invalid value";

    private static final ObjectMapper JSON = new ObjectMapper();

    private int blocks;
    private int aiSteps;
    private int networkSteps;
    private int personSteps;
    private int webSteps;
    private int notShown;

    /** The package's own images, by name: what a {@code ./images/<name>} of the package's workflows names. */
    private final Map<String, PackageImage> images;
    /** How many workflows of another package the walk is inside: there {@code ./images/} is not this package's. */
    private int otherPackages;

    /** Finds the model server of the operator's a step runs against. */
    private final LocalServers local;

    /** Tells which model server of the operator's a step of a provider with a model runs against. */
    @FunctionalInterface
    public interface LocalServers {

        /** No server: every step goes to its provider. */
        LocalServers NONE = (provider, model) -> null;

        /** The server of the step; {@code null} for a step that goes to its provider. */
        traceworks.core.port.LocalModels.Use of(String provider, String model);
    }

    /** Tells whether a writable mount lies in no folder the operator listed. */
    private final java.util.function.Predicate<String> unlistedWritableMount;

    /** Whether the operator lets a step that may write its workspace write the workspace's {@code .git} too. */
    private final boolean workspaceGitWritable;

    private WorkflowDescriber(Map<String, PackageImage> images, LocalServers local,
                              java.util.function.Predicate<String> unlistedWritableMount,
                              boolean workspaceGitWritable) {
        this.images = images;
        this.local = local == null ? LocalServers.NONE : local;
        this.unlistedWritableMount = unlistedWritableMount == null ? host -> false : unlistedWritableMount;
        this.workspaceGitWritable = workspaceGitWritable;
    }

    /**
     * The description of a package.
     *
     * @param children finds the workflows its blocks name in {@code workflow_id}, as a run of the package finds them;
     *                 {@code null} shows none
     */
    public static WorkflowDescription describe(LoadedPackage pkg, WorkflowResolver children) {
        return describe(pkg, children, LocalServers.NONE);
    }

    /**
     * {@link #describe(LoadedPackage, WorkflowResolver)} in a home with model servers of the operator's: an AI step
     * whose model names one says so, with the server's URL.
     *
     * @param local finds the server of a step, from the operator's {@code local_models}
     */
    public static WorkflowDescription describe(LoadedPackage pkg, WorkflowResolver children, LocalServers local) {
        return describe(pkg, children, local, null);
    }

    /**
     * {@link #describe(LoadedPackage, WorkflowResolver, LocalServers)} in a home that lists the folders a package
     * may mount writable: a writable mount that a block writes out and that lies in none of them is marked, since
     * a run of the package is refused for it.
     *
     * @param unlistedWritableMount whether the host side of a writable mount, as the workflow writes it, lies in no
     *                              folder the operator's {@code writable_mounts} lists; {@code null} marks none
     */
    public static WorkflowDescription describe(LoadedPackage pkg, WorkflowResolver children, LocalServers local,
                                               java.util.function.Predicate<String> unlistedWritableMount) {
        return describe(pkg, children, local, unlistedWritableMount, false);
    }

    /**
     * {@link #describe(LoadedPackage, WorkflowResolver, LocalServers, java.util.function.Predicate)} in a home
     * whose operator said whether a step that may write its workspace may write the workspace's {@code .git} too
     * ({@code workspace_git_writable}): a writable workspace says which holds.
     *
     * @param workspaceGitWritable the operator's setting; {@code false} is its default, a {@code .git} that such a
     *                             step only reads
     */
    public static WorkflowDescription describe(LoadedPackage pkg, WorkflowResolver children, LocalServers local,
                                               java.util.function.Predicate<String> unlistedWritableMount,
                                               boolean workspaceGitWritable) {
        WorkflowDefinition definition = pkg.definition();
        WorkflowDescriber describer = new WorkflowDescriber(pkg.images(), local, unlistedWritableMount,
                workspaceGitWritable);
        List<Block> blocks = describer.workflow(definition, children, List.of(definition));
        Map<String, String> inputs = new LinkedHashMap<>();
        definition.defaultContext().forEach((key, value) -> inputs.put(key, shown(value)));
        return new WorkflowDescription(definition.id(), definition.name(), definition.description(),
                String.valueOf(pkg.directory()), pkg.digest(), pkg.gitCommit(), Boolean.TRUE.equals(pkg.gitDirty()),
                inputs, definition.requires().stream().map(String::valueOf).toList(), aiDefaults(definition),
                definition.instructions() == null || definition.instructions().isBlank() ? null
                        : WorkflowOutline.firstLine(definition.instructions()),
                audit(definition.metadata()), blocks, new Counts(describer.blocks, describer.aiSteps,
                describer.networkSteps, describer.personSteps, describer.webSteps, describer.notShown),
                modelChoices(definition));
    }

    /** The model choices the workflow declares, each with the blocks that refer to it. */
    private static List<WorkflowDescription.ModelChoice> modelChoices(WorkflowDefinition definition) {
        List<WorkflowDescription.ModelChoice> choices = new ArrayList<>();
        for (ModelChoices.Choice choice : ModelChoices.declared(definition)) {
            List<String> usedBy = new ArrayList<>();
            for (ModelChoices.Use use : ModelChoices.uses(definition)) {
                if (!use.choice().equals(choice.name())) {
                    continue;
                }
                String top = definition.blocks().get(use.blockIndex()).getName();
                String block = top == null || top.isBlank() ? "block " + use.blockIndex() : top;
                usedBy.add(use.nested() == null ? block : block + " › " + use.nested().label());
            }
            choices.add(new WorkflowDescription.ModelChoice(choice.name(), choice.defaultOption(),
                    choice.options().stream().map(option -> new WorkflowDescription.ModelChoice.Option(
                            option.name(), option.provider(), option.model())).toList(), usedBy));
        }
        return choices;
    }

    /** A default as text: a text as it is, every other value as JSON. */
    private static String shown(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static List<String> aiDefaults(WorkflowDefinition definition) {
        List<String> facts = new ArrayList<>();
        addIfSet(facts, "provider ", definition.provider());
        addIfSet(facts, "model ", definition.model());
        addIfSet(facts, "effort ", definition.effort());
        return facts;
    }

    private static void addIfSet(List<String> facts, String label, String value) {
        if (value != null && !value.isBlank()) {
            facts.add(label + value.strip());
        }
    }

    /** The audit block when it is enabled and valid, or enabled and broken; {@code null} otherwise. */
    private static Audit audit(Map<String, Object> metadata) {
        List<String> problems = AuditConfig.problems(metadata);
        if (problems.isEmpty()) {
            return AuditConfig.of(metadata).map(audit -> new Audit(audit.key(), audit.category(),
                    audit.reportBlock(), List.of())).orElse(null);
        }
        Map<?, ?> raw = metadata.get("audit") instanceof Map<?, ?> map ? map : Map.of();
        return new Audit(raw.get("key") instanceof String key ? key : null,
                raw.get("category") instanceof String category ? category : null,
                raw.get("report_block") instanceof String block ? block : null, problems);
    }

    /**
     * The top-level blocks of a workflow.
     *
     * @param path the workflows from the package's own down to this one
     */
    private List<Block> workflow(WorkflowDefinition definition, WorkflowResolver children,
                                 List<WorkflowDefinition> path) {
        List<Block> described = new ArrayList<>();
        List<BlockDefinition> list = definition.blocks();
        for (int i = 0; i < list.size(); i++) {
            BlockDefinition block = list.get(i);
            described.add(block == null ? block(i, null, null, null, false, definition, children, path)
                    : block(i, block.getName(), block.getType(), block.getConfig(), false, definition, children,
                    path));
        }
        return described;
    }

    private Block block(int position, String name, String type, Map<String, Object> config, boolean nested,
                        WorkflowDefinition workflow, WorkflowResolver children, List<WorkflowDefinition> path) {
        blocks++;
        Map<String, Object> settings = config == null ? Map.of() : config;
        List<Line> lines = new ArrayList<>();
        if (RunRequirements.readByAShellStep(type, settings, nested)) {
            lines.add(new Line(Line.SHELL, shell(type, settings)));
        }
        if (RunRequirements.callsAi(type, settings, nested)) {
            aiSteps++;
            lines.add(new Line(Line.AI, ai(name, type, settings, workflow)));
        }
        WorkflowOutline.Block outline = WorkflowOutline.block(position, name, type, config);
        List<String> also = also(type, settings, nested, outline.what());
        if (!also.isEmpty()) {
            lines.add(new Line(Line.ALSO, also));
        }
        List<String> person = person(type, settings, nested);
        if (!person.isEmpty()) {
            lines.add(new Line(Line.PERSON, person));
        }
        List<Section> sections = new ArrayList<>();
        for (NestedBlocks.NestedList list : NestedBlocks.lists(settings)) {
            sections.add(section(list, workflow, children, path));
        }
        Section child = child(type, settings, children, path);
        if (child != null) {
            sections.add(child);
        }
        return new Block(outline, lines, sections);
    }

    /** What the command of a shell step may touch; the defaults are said too, since they are what protects. */
    private List<String> shell(String type, Map<String, Object> config) {
        BlockSettings settings = BlockSettings.of(type, config);
        List<String> facts = new ArrayList<>();
        Boolean network = read(() -> settings.flag("network"));
        if (Boolean.TRUE.equals(network)) {
            networkSteps++;
        }
        facts.add(network == null ? "network" + INVALID : network ? "network open" : "network none");
        facts.add(workspace(settings));
        List<String> secrets = SecretNames.of(config);
        if (!secrets.isEmpty()) {
            facts.add("secrets " + String.join(", ", secrets));
        } else if (config.get(SecretNames.KEY) != null && !(config.get(SecretNames.KEY) instanceof List<?>)) {
            facts.add("secrets" + INVALID);
        }
        List<String> values = ShellValues.names(config);
        if (!values.isEmpty()) {
            facts.add("values " + String.join(", ", values));
        }
        if (config.get("container_image") == null) {
            facts.add("image: the run's shell image");
        } else {
            String image = read(() -> settings.text("container_image"));
            facts.add(image == null ? "image" + INVALID : image.isBlank() ? "image: the run's shell image"
                    : "image " + image + holds(image));
        }
        if (config.get("mounts") != null) {
            List<String> mounts = read(() -> settings.texts("mounts"));
            if (mounts == null) {
                facts.add("mounts" + INVALID);
            } else if (!mounts.isEmpty()) {
                boolean writesTheWorkspace = Boolean.TRUE.equals(config.get("workspace_writable"));
                facts.add("mounts " + String.join(", ", mounts.stream()
                        .map(entry -> mount(entry, writesTheWorkspace)).toList()));
            }
        }
        timeout(facts, settings, config);
        return facts;
    }

    /**
     * What a package image holds, as its Dockerfile says ({@link ImageFacts}): {@code " — <base>: <description>"}, or
     * nothing for an image that is not one of this package's or says neither. A child workflow of another package
     * gets nothing: a run refuses a {@code ./images/} there.
     */
    private String holds(String image) {
        if (otherPackages > 0 || !image.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX)) {
            return "";
        }
        PackageImage own = images.get(image.substring(ImageCatalog.PACKAGE_IMAGE_PREFIX.length()));
        if (own == null) {
            return "";
        }
        ImageFacts facts = ImageFacts.of(image, own.dockerfile());
        List<String> said = java.util.stream.Stream.of(facts.base(), facts.description())
                .filter(java.util.Objects::nonNull).toList();
        return said.isEmpty() ? "" : " — " + String.join(": ", said);
    }

    /**
     * A {@code mounts} entry as written; a writable one whose literal host side lies in no folder the operator
     * listed says so, since a run is refused for it before its first step. A step that may write its workspace
     * needs no listed folder for a mount in the workspace, which no description knows: its mark says so.
     *
     * @param writesTheWorkspace whether the step that declares the mount sets {@code workspace_writable: true}
     */
    private String mount(String entry, boolean writesTheWorkspace) {
        String host = traceworks.core.engine.WritableMounts.literalHostOfAWritableEntry(entry);
        if (host == null || !unlistedWritableMount.test(host)) {
            return entry;
        }
        return entry + " (writable, in no folder of writable_mounts: a run is refused"
                + (writesTheWorkspace ? " unless the folder lies in its workspace)" : ")");
    }

    /**
     * Whether the step may write its workspace; a writable one says whether its top-level {@code .git} is writable
     * too, as the operator set it. That is the {@code .git} of a workspace of the operator's in a container: the
     * run's own checkout ({@code run --source}) is writable with its {@code .git}, and a host run has no mounts.
     */
    private String workspace(BlockSettings settings) {
        Boolean writable = read(() -> settings.flag("workspace_writable"));
        if (writable == null) {
            return "workspace" + INVALID;
        }
        if (!writable) {
            return "workspace read-only";
        }
        return workspaceGitWritable ? "workspace writable, with its .git (workspace_git_writable)"
                : "workspace writable, its .git read-only";
    }

    /** What a block that refers to a model choice runs with: the default, and what a run may pick instead. */
    private static String modelChoice(Object reference, WorkflowDefinition workflow) {
        for (ModelChoices.Choice choice : ModelChoices.declared(workflow)) {
            if (choice.name().equals(reference)) {
                ModelChoices.Option standard = choice.option(choice.defaultOption()).orElseThrow();
                List<String> others = choice.optionNames().stream()
                        .filter(name -> !name.equals(choice.defaultOption())).toList();
                return "model choice " + choice.name() + ": " + standard.name() + " (provider " + standard.provider()
                        + ", model " + standard.model() + ") unless the run picks"
                        + (others.isEmpty() ? " (it has no other option)" : " " + String.join(" or ", others));
            }
        }
        return "model choice " + reference + ": not declared in model_choices";
    }

    /**
     * Says that a step with {@code model} runs against a model server of the operator's, and which: nothing for a
     * step that goes to its provider.
     */
    private void localFact(String provider, String model, List<String> facts) {
        traceworks.core.port.LocalModels.Use use = local.of(provider, model);
        if (use == null) {
            return;
        }
        String fact = "model " + model + " runs on the local backend " + use.backend().id() + " at "
                + use.backend().url() + " (local_models in traceworks.json)"
                + (use.listed() ? "" : ", which does not list it");
        if (!facts.contains(fact)) {
            facts.add(fact);
        }
    }

    private static void timeout(List<String> facts, BlockSettings settings, Map<String, Object> config) {
        if (config.get("timeout_seconds") != null) {
            Integer seconds = read(() -> settings.number("timeout_seconds"));
            facts.add(seconds == null ? "timeout" + INVALID : "timeout " + seconds + " s");
        }
    }

    /** What the AI call of a block runs with: the block's own setting, else its workflow's, else the default. */
    private List<String> ai(String name, String type, Map<String, Object> config, WorkflowDefinition workflow) {
        BlockDefinition block = BlockDefinition.builder().name(name).type(type).config(config).build();
        BlockSettings settings = BlockSettings.of(type, config);
        List<String> facts = new ArrayList<>();
        if (config.get(ModelChoices.KEY) != null) {
            facts.add(modelChoice(config.get(ModelChoices.KEY), workflow));
            for (ModelChoices.Choice choice : ModelChoices.declared(workflow)) {
                if (choice.name().equals(config.get(ModelChoices.KEY))) {
                    choice.options().forEach(option -> localFact(option.provider(), option.model(), facts));
                }
            }
        } else {
            boolean named = config.get(BlockAiSettings.PROVIDER_KEY) instanceof String own && !own.isBlank()
                    || workflow.provider() != null && !workflow.provider().isBlank();
            facts.add("provider " + BlockAiSettings.providerOf(config, workflow.provider())
                    + (named ? "" : " (the default)"));
            String model = read(() -> BlockAiSettings.modelFor(block, workflow.provider(), workflow.model()), "");
            facts.add(model == null ? "model: the provider's default" : model.isEmpty() ? "model" + INVALID
                    : "model " + model);
            if (model != null && !model.isEmpty()) {
                localFact(BlockAiSettings.providerOf(config, workflow.provider()), model, facts);
            }
        }
        String effort = read(() -> BlockAiSettings.effortFor(block, workflow.effort()), "");
        if (effort != null) {
            facts.add(effort.isEmpty() ? "effort" + INVALID : "effort " + effort.strip());
        }
        boolean ownsTools = RunRequirements.ownsTools(type);
        ToolPolicy tools = ownsTools ? read(() -> ToolPolicy.of(config)) : ToolPolicy.NONE;
        if (ownsTools && "agentic".equals(type) && config.get(ToolPolicy.KEY) == null) {
            facts.add("tools: not stated");
            tools = null;
        } else {
            facts.add(tools == null ? "tools" + INVALID : "tools " + tools.value());
        }
        if (ownsTools) {
            WebAccess web = read(() -> WebAccess.of(config));
            if (web == null) {
                facts.add("web" + INVALID);
            } else {
                facts.add("web " + web.value() + (web.hosts().isEmpty() ? ""
                        : " (" + String.join(", ", web.hosts()) + ")"));
            }
            if (web == null ? WebAccess.asked(config) : web.enabled()) {
                webSteps++;
            }
        }
        if (UntrustedInput.declared(config)) {
            facts.add("untrusted input");
        }
        if (ownsTools && tools != ToolPolicy.NONE) {
            facts.add(workspace(settings));
        }
        timeout(facts, settings, config);
        return facts;
    }

    /**
     * The commands a block runs besides the one its first line shows: the test of {@code run_if} before a top-level
     * block, the command that lists a foreach's items, the condition of a {@code while} or a {@code conditional}, and
     * the two branches of a {@code conditional}. A {@code run_if}, an {@code items_command} and a shell condition
     * always run in the run's shell image without network, whatever the block says; a branch runs as the block does.
     *
     * @param shown the first line the block's outline shows; a condition that is this line is not named again
     */
    private static List<String> also(String type, Map<String, Object> config, boolean nested, String shown) {
        String helper = " (a shell command without network): ";
        List<String> facts = new ArrayList<>();
        BlockSettings settings = BlockSettings.of(type, config);
        if (!nested) {
            addCommand(facts, "run_if" + helper, read(() -> settings.text("run_if")));
        }
        if ("foreach".equals(type) && "command".equals(read(() -> settings.text("items_source", "list")))) {
            addCommand(facts, "items_command" + helper, read(() -> settings.text("items_command")));
        }
        if ("while".equals(type) || "conditional".equals(type)) {
            String condition = read(() -> settings.text("condition"));
            if (condition != null && !WorkflowOutline.firstLine(condition).equals(shown)) {
                addCommand(facts, "condition" + (RunRequirements.readByAShellStep(type, config, nested) ? helper
                        : " (a question to the AI): "), condition);
            }
        }
        if ("conditional".equals(type)) {
            addCommand(facts, "then: ", read(() -> settings.text("then_command")));
            addCommand(facts, "else: ", read(() -> settings.text("else_command")));
        }
        return facts;
    }

    private static void addCommand(List<String> facts, String label, String command) {
        if (command != null && !command.isBlank()) {
            facts.add(label + WorkflowOutline.firstLine(command));
        }
    }

    /** Whether the block asks a person, and who answers in a person's place. */
    private List<String> person(String type, Map<String, Object> config, boolean nested) {
        if ("approval".equals(type) || "human-input".equals(type)) {
            if (!RunRequirements.callsAi(type, config, nested)) {
                personSteps++;
                return List.of("a person is asked");
            }
            String actor = String.valueOf(config.get("actor")).strip();
            return switch (actor.toLowerCase(Locale.ROOT)) {
                case "ai" -> List.of("actor ai: an AI answers in place of a person");
                case "shadow" -> {
                    personSteps++;
                    yield List.of("actor shadow: a person is asked, an AI call takes part");
                }
                default -> {
                    personSteps++;
                    yield List.of("actor " + actor + ": a person or an AI is asked, decided when the block runs");
                }
            };
        }
        if ("agentic".equals(type) && config.get("require_approval") != null) {
            Boolean required = read(() -> BlockSettings.of(type, config).flag("require_approval"));
            if (required == null) {
                return List.of("require_approval" + INVALID);
            }
            if (required) {
                personSteps++;
                return List.of("a person is asked before each action (require_approval)");
            }
        }
        return List.of();
    }

    private Section section(NestedBlocks.NestedList list, WorkflowDefinition workflow, WorkflowResolver children,
                            List<WorkflowDefinition> path) {
        if (list.kind() == NestedBlocks.Kind.DEAD) {
            // not counted and not judged: nothing here ever runs
            return new Section("blocks", list.blocks().stream().map(nested -> new Block(WorkflowOutline.block(-1,
                    nested.name(), nested.type(), nested.config()), List.of(), List.of())).toList(),
                    "never run: no block type runs a plain list of blocks");
        }
        String label = list.kind() == NestedBlocks.Kind.DEFAULT ? "default"
                : "case " + (list.caseValue() instanceof String value ? "\"" + value + "\""
                : String.valueOf(list.caseValue()));
        List<Block> described = new ArrayList<>();
        for (NestedBlocks.Nested nested : list.blocks()) {
            described.add(block(-1, nested.name(), nested.type(), nested.config(), true, workflow, children, path));
        }
        return new Section(label, described, described.isEmpty() ? "no blocks" : null);
    }

    /** The workflow a {@code sub-workflow} or a {@code foreach} with {@code workflow_id} starts; else {@code null}. */
    private Section child(String type, Map<String, Object> config, WorkflowResolver children,
                          List<WorkflowDefinition> path) {
        if (!"sub-workflow".equals(type) && !"foreach".equals(type)
                || !(config.get("workflow_id") instanceof String written) || written.isBlank()) {
            return null;
        }
        String ref = ChildWorkflows.literalRef(type, config);
        String label = "workflow " + written.strip();
        if (ref == null) {
            return notShown(label, "the reference is a placeholder, resolved when the block runs");
        }
        if (children == null) {
            return notShown(label, "no other workflow can be looked up here");
        }
        WorkflowDefinition found;
        try {
            found = children.resolve(ref);
        } catch (RuntimeException e) {
            return notShown(label, e.getMessage() == null ? "it cannot be resolved" : e.getMessage());
        }
        boolean otherPackage = Boolean.TRUE.equals(read(() -> children.fromAnotherPackage(found)));
        label = "workflow " + (found.name() == null || found.name().isBlank() ? idOr(found, ref)
                : found.name() + " (" + idOr(found, ref) + ")") + (otherPackage ? ", of another package" : "");
        if (path.stream().anyMatch(above -> above == found || above.id() != null && above.id().equals(found.id()))) {
            return notShown(label, "it is the workflow this block is part of");
        }
        if (path.size() > MAX_DEPTH) {
            return notShown(label, "it is nested more than " + MAX_DEPTH + " workflows deep");
        }
        List<WorkflowDefinition> below = new ArrayList<>(path);
        below.add(found);
        if (otherPackage) {
            otherPackages++;
        }
        try {
            return new Section(label, workflow(found, children.forChild(found), below), null);
        } finally {
            if (otherPackage) {
                otherPackages--;
            }
        }
    }

    private static String idOr(WorkflowDefinition workflow, String ref) {
        return workflow.id() == null || workflow.id().isBlank() ? ref : workflow.id();
    }

    private Section notShown(String label, String why) {
        notShown++;
        return new Section(label, List.of(), "not shown: " + why);
    }

    /** What {@code setting} reads; {@code null} for a value the run refuses, whatever it refuses it with. */
    private static <T> T read(Supplier<T> setting) {
        return read(setting, null);
    }

    private static <T> T read(Supplier<T> setting, T invalid) {
        try {
            return setting.get();
        } catch (RuntimeException e) {
            return invalid;
        }
    }
}
