package traceworks.core.app;

import traceworks.core.analyzer.WorkflowAnalyzer;
import traceworks.core.container.ImageCatalog;
import traceworks.core.context.BlockAiSettings;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.ModelChoices;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a run of a workflow definition uses, read from the definition and the child workflows it reaches by a literal
 * {@code workflow_id}: the AI providers of its AI steps and the images its steps name as literals. The start check
 * asks for the images and the credentials of both.
 *
 * <p>A step that refers to a model choice ({@link ModelChoices}) uses what a run picks. {@link Picks} says which
 * runs are meant: the one with given inputs, whose start check then asks only for what the picked option needs, or
 * every run the package allows.
 */
final class WorkflowUses {

    private WorkflowUses() {
    }

    /** The forms of a workflow definition whose AI steps are read: each with its model choices picked. */
    @FunctionalInterface
    interface Picks {

        /** The definition as each run that is meant runs it ({@link ModelChoices#resolve}). */
        List<WorkflowDefinition> of(WorkflowDefinition definition);

        /**
         * Every run the package allows: each option of each choice. For what must hold whichever option a run picks:
         * the images {@code images build} builds, the names a record redacts.
         */
        Picks EVERY = ModelChoices::everyPick;

        /**
         * The run of {@code root} with {@code inputs}: its picks, and those of the child workflows, which pick from
         * the inputs the root run starts with (its {@code default_context} under its inputs), as the engine does.
         *
         * @param inputs the run's inputs; {@code null} for none
         */
        static Picks of(WorkflowDefinition root, Map<String, ?> inputs) {
            Map<String, Object> started = new java.util.HashMap<>(root.defaultContext());
            if (inputs != null) {
                started.putAll(inputs);
            }
            return definition -> List.of(ModelChoices.resolve(definition, started).definition());
        }
    }

    /**
     * The AI providers the AI steps use, in lowercase, in the order they are found: of the definition and of every
     * workflow it reaches by a literal {@code workflow_id} ({@link ChildWorkflows}); a step's own {@code provider},
     * else its workflow's, else {@value BlockAiSettings#DEFAULT_PROVIDER} ({@link BlockAiSettings#providerOf}). The
     * start check needs their images and their credentials.
     *
     * @param children finds the child workflows; {@code null} looks into none
     */
    static Set<String> providers(WorkflowDefinition definition, WorkflowResolver children) {
        return providers(definition, children, Picks.EVERY);
    }

    /**
     * {@link #providers(WorkflowDefinition, WorkflowResolver)} of the runs {@code picks} means: a step that refers
     * to a model choice uses the provider of the picked option.
     */
    static Set<String> providers(WorkflowDefinition definition, WorkflowResolver children, Picks picks) {
        Set<String> names = new LinkedHashSet<>();
        for (WorkflowDefinition reached : ChildWorkflows.reachable(definition, children)) {
            for (WorkflowDefinition workflow : picks.of(reached)) {
                for (BlockDefinition block : workflow.blocks()) {
                    if (block == null || block.getConfig() == null) continue;
                    addProvider(names, block.getType(), block.getConfig(), false, workflow.provider());
                    NestedBlocks.visit(block.getConfig(), nested ->
                            addProvider(names, nested.type(), nested.config(), true, workflow.provider()));
                }
            }
        }
        return names;
    }

    /**
     * The models the AI steps of each provider are called with, where the definition tells them, by provider in
     * lowercase, in the order they are found: the literal models, and {@code null} for a step that names none. A
     * model with a placeholder is not among them: the run admission refuses it. Over the same workflows as
     * {@link #providers}.
     *
     * @param children finds the child workflows; {@code null} looks into none
     * @param picks    the runs that are meant: a step that refers to a model choice has the model of the picked option
     */
    static Map<String, Set<String>> literalModels(WorkflowDefinition definition, WorkflowResolver children,
                                                  Picks picks) {
        Map<String, Set<String>> models = new java.util.LinkedHashMap<>();
        for (WorkflowDefinition reached : ChildWorkflows.reachable(definition, children)) {
            for (WorkflowDefinition workflow : picks.of(reached)) {
                for (BlockDefinition block : workflow.blocks()) {
                    if (block == null || block.getConfig() == null) continue;
                    addModel(models, block.getType(), block.getConfig(), false, workflow);
                    NestedBlocks.visit(block.getConfig(), nested ->
                            addModel(models, nested.type(), nested.config(), true, workflow));
                }
            }
        }
        return models;
    }

    private static void addModel(Map<String, Set<String>> models, String type, Map<String, Object> config,
                                 boolean nested, WorkflowDefinition workflow) {
        if (!WorkflowAnalyzer.readsProvider(type, config, nested)) return;
        BlockAiSettings.StepModel model = BlockAiSettings.stepModel(config, workflow.provider(), workflow.model());
        if (model.literal()) {
            models.computeIfAbsent(BlockAiSettings.providerOf(config, workflow.provider()).toLowerCase(Locale.ROOT),
                    provider -> new LinkedHashSet<>()).add(model.model());
        }
    }

    private static void addProvider(Set<String> names, String type, Map<String, Object> config, boolean nested,
                                    String workflowProvider) {
        if (!WorkflowAnalyzer.readsProvider(type, config, nested)) return;
        names.add(BlockAiSettings.providerOf(config, workflowProvider).toLowerCase(Locale.ROOT));
    }

    /**
     * The {@code container_image} values that a run of {@code definition} names as literals: the definition's own and
     * those of the workflows it reaches by a literal {@code workflow_id}, each once, in the order the walk finds them;
     * a {@code ./images/<name>} only from the workflows of the run's own package, since the run resolves it against
     * that package.
     *
     * @param children finds the child workflows; {@code null} looks into none
     */
    static List<String> literalImages(WorkflowDefinition definition, WorkflowResolver children) {
        Set<String> images = new LinkedHashSet<>();
        for (ChildWorkflows.Reached reached : ChildWorkflows.reached(definition, children)) {
            for (String image : literalImages(reached.definition())) {
                if (!reached.otherPackage() || !image.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX)) {
                    images.add(image);
                }
            }
        }
        return List.copyOf(images);
    }

    /**
     * The {@code container_image} values the definition's blocks name as literals (no placeholder), nested blocks
     * included, each once, in definition order.
     */
    static List<String> literalImages(WorkflowDefinition definition) {
        Set<String> images = new LinkedHashSet<>();
        for (BlockDefinition block : definition.blocks()) {
            if (block == null || block.getConfig() == null) continue;
            addImage(images, block.getConfig().get("container_image"));
            NestedBlocks.visit(block.getConfig(), nested -> {
                if (nested.config() != null) addImage(images, nested.config().get("container_image"));
            });
        }
        return List.copyOf(images);
    }

    private static void addImage(Set<String> images, Object image) {
        if (image instanceof String reference && !reference.isBlank()
                && !PlaceholderSyntax.PLACEHOLDER.matcher(reference).find()) {
            images.add(reference);
        }
    }
}
