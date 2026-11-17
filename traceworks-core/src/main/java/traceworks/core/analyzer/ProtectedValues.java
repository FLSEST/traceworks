package traceworks.core.analyzer;

import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.engine.StepModes;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which placeholders of a workflow name a <b>protected</b> value, as far as the definition tells: outside text, and
 * what a model wrote. A run never writes such a value into shell code ({@link QuotingRules},
 * {@code protected-value-in-shell}) and takes none in the arguments of a tool, in a mount or in a
 * {@code workflow_id} ({@link ProtectedPlaceRules}, {@code protected-value-outside-shell}).
 *
 * <p><b>Outside text</b>: the platform data Traceworks keeps ({@code previous_report}, {@code audit_findings},
 * {@code audit_items}) and what a chain hands on ({@code previous_chain_output}).
 *
 * <p><b>What a model wrote</b>, as far as the block list tells: the output of a block that asks a model
 * ({@code simple}, {@code agentic}, {@code compress-ai}, a wrapper in AI mode, a block with AI steps nested in it, a
 * step an AI actor decides, and a {@code sub-workflow} or {@code foreach} whose child workflow has such a block, when
 * the analyzer can find the child), and the output of every block after the first of them, because the run protects
 * those too: a later step can print what the model wrote without naming it. The block is found through
 * {@code block.<name>.output}, {@code previous_output} in the block's own fields, and {@code step_N_output} while no
 * wrapper before it draws further step indices. A {@code step_N_output} after such a wrapper, and a
 * {@code previous_output} in a nested block, name an output whose step the list does not tell: they count as what a
 * model wrote when the workflow asks a model before the block. What the list does not tell at all (a loop item, a
 * variable, a child whose reference is a placeholder, the values a parent hands to a child) the run decides: it
 * refuses the value where it is used.
 *
 * <p>Two questions are answered. {@link #sourceOf} says whether a run <em>may</em> protect a value: outside text by
 * its name, and model text from the first block on that could ask a model. {@link #protectionOf} also says whether
 * <em>every</em> run does ({@link Protection#always}), which is less: the run marks model text when a model is
 * called, so a block that may be skipped ({@code run_if}), a {@code switch} case, a loop that may have no item, a
 * compression that may refuse its input and a child workflow protect what follows only in the runs in which they
 * ask; and outside text is protected only where a run sets it.
 *
 * @param first     the position of the first top-level block that could ask a model; -1 for a workflow without one
 * @param certain   the position of the first top-level block that asks a model in every run that gets past it; -1
 *                  for a workflow without one
 * @param audit     whether the workflow feeds a register, so that a run sets the platform data
 * @param inputs    the names of the inputs of the run the workflow is checked for
 */
record ProtectedValues(List<BlockDefinition> blocks, WorkflowResolver children, int first, int certain,
                       boolean audit, Set<String> inputs) {

    /** Outputs of earlier runs: always untrusted. */
    static final Set<String> PLATFORM_DATA = Set.of("previous_report", "audit_findings", "audit_items");

    /** Text from outside: a chain hands on the last entry's output. */
    private static final Set<String> FOREIGN_TEXT = Set.of("previous_chain_output");

    /** The block types whose step calls a model whenever it runs in AI mode: no item list, no case, no input limit. */
    private static final Set<String> ALWAYS_ASKING = Set.of("simple", "agentic", "retry", "while", "conditional");

    /**
     * How a run protects the value of a placeholder.
     *
     * @param source what the placeholder stands for, as the words a finding says it with
     * @param always whether every run that reaches the block protects the value
     * @param when   for a value only some runs protect, which ones, as the words after "a run": {@code "in which ask
     *               asks a model"}; {@code null} with {@code always}
     */
    record Protection(String source, boolean always, String when) {
    }

    private static final Pattern STEP_OUTPUT = Pattern.compile("step_(\\d{1,6})_output");
    private static final Pattern BLOCK_OUTPUT = Pattern.compile("block\\.([^.\\s]+)\\.output");

    /** Blocks that draw further step indices, so a later step_N_output no longer matches the block position. */
    private static final Set<String> INDEX_DRAWING = Set.of("foreach", "while", "retry", "conditional", "switch",
            "sub-workflow");

    /**
     * @param children finds the workflows of {@code sub-workflow} and {@code foreach} blocks, to see whether one
     *                 asks a model; {@code null} looks into none
     */
    static ProtectedValues of(List<BlockDefinition> blocks, WorkflowResolver children) {
        return of(blocks, children, Map.of(), Set.of());
    }

    /**
     * @param metadata the definition's metadata, which says whether it is an audit workflow
     * @param inputs   the names of the inputs of the run the workflow is checked for; empty for a check on its own
     */
    static ProtectedValues of(List<BlockDefinition> blocks, WorkflowResolver children, Map<String, Object> metadata,
                              Set<String> inputs) {
        int first = -1;
        int certain = -1;
        for (int j = 0; j < blocks.size(); j++) {
            if (blocks.get(j) == null) continue;
            if (first < 0 && asksModel(blocks.get(j), children)) first = j;
            if (certain < 0 && alwaysAsks(blocks.get(j))) certain = j;
        }
        return new ProtectedValues(blocks, children, first, certain, WiringRules.auditWorkflow(metadata), inputs);
    }

    /**
     * Whether every run that gets past the top-level block has asked a model: the block makes an AI call itself
     * whenever it runs, and no {@code run_if} skips it.
     */
    private static boolean alwaysAsks(BlockDefinition block) {
        return block.getType() != null && ALWAYS_ASKING.contains(block.getType()) && StepModes.usesAi(block)
                && (block.getConfig() == null || !block.getConfig().containsKey("run_if"));
    }

    /**
     * How a run protects the value of the placeholder {@code key} in block {@code i}; null for a value no run
     * protects, as far as the workflow tells. Unlike {@link #sourceOf}, outside text counts only where a run sets
     * it: the platform data in an audit workflow, the output of a chain in a run that gets it as an input.
     *
     * @param own whether the placeholder stands in a field of the top-level block itself, not of a block nested in it
     */
    Protection protectionOf(int i, String key, boolean own) {
        String name = key.strip();
        if (outsideText(name)) {
            boolean set = PLATFORM_DATA.contains(name) ? audit : inputs.contains(name);
            return set ? new Protection("text from outside the run", true, null) : null;
        }
        String source = modelText(i, name, own);
        if (source == null) return null;
        if (always(i, name, own)) return new Protection(source, true, null);
        boolean untoldStep = STEP_OUTPUT.matcher(name).matches() && atStepIndex(blocks, stepIndex(name), i) == null;
        return new Protection(source, false, untoldStep
                ? "in which the step of that number runs after a model was asked"
                : "in which " + label(blocks.get(first)) + " asks a model");
    }

    /**
     * Whether every run that reaches block {@code i} protects {@code name}: the output it names is recorded before
     * the block and after a call of a model that every such run makes, or is the output of that call.
     */
    private boolean always(int i, String name, boolean own) {
        if (certain < 0) return false;
        Matcher byName = BLOCK_OUTPUT.matcher(name);
        Source source = null;
        if (byName.matches()) {
            source = named(blocks, children, byName.group(1));
        } else if ("previous_output".equals(name)) {
            if (!own) return certain < i;   // the output before a nested step is recorded after block i - 1 at least
            source = i > 0 && blocks.get(i - 1) != null ? new Source(blocks.get(i - 1), i - 1) : null;
        } else if (STEP_OUTPUT.matcher(name).matches()) {
            source = atStepIndex(blocks, stepIndex(name), i);
        }
        if (source == null || source.position() >= i) return false;
        boolean topLevel = blocks.get(source.position()) == source.block();
        return certain < source.position() || (certain == source.position() && topLevel);
    }

    private static int stepIndex(String name) {
        Matcher byIndex = STEP_OUTPUT.matcher(name);
        return byIndex.matches() ? Integer.parseInt(byIndex.group(1)) : -1;
    }

    /**
     * What the placeholder {@code key} stands for in block {@code i}, when the run protects its value, as the words a
     * finding says it with; null otherwise.
     *
     * @param own whether the placeholder stands in a field of the top-level block itself, not of a block nested in it
     */
    String sourceOf(int i, String key, boolean own) {
        String name = key.strip();
        return outsideText(name) ? "text from outside the run" : modelText(i, name, own);
    }

    /** Whether the run protects the value of the placeholder {@code key} in block {@code i}. */
    boolean protects(int i, String key, boolean own) {
        return sourceOf(i, key, own) != null;
    }

    private static boolean outsideText(String name) {
        return PLATFORM_DATA.contains(name) || FOREIGN_TEXT.contains(name);
    }

    /**
     * A block a placeholder stands for, and the position of the top-level block it is, or is nested in.
     *
     * @param afterNestedModel for a nested block: whether a block nested in the same top-level block may ask a model
     *                         before it runs, one written before it in a {@code switch}, any one in a loop
     */
    private record Source(BlockDefinition block, int position, boolean afterNestedModel) {

        Source(BlockDefinition block, int position) {
            this(block, position, false);
        }
    }

    /**
     * What {@code name} stands for in block {@code i}, when the run quotes it as text a model wrote, as the words a
     * finding says it with; null otherwise.
     */
    private String modelText(int i, String name, boolean own) {
        if (first < 0) return null;
        Source source = null;
        // Whether the name is an output whose step the block list does not tell
        boolean untold = false;
        Matcher byName = BLOCK_OUTPUT.matcher(name);
        Matcher byIndex = STEP_OUTPUT.matcher(name);
        if (byName.matches()) {
            source = named(blocks, children, byName.group(1));
        } else if ("previous_output".equals(name)) {
            source = own && i > 0 && blocks.get(i - 1) != null ? new Source(blocks.get(i - 1), i - 1) : null;
            untold = !own;   // in a nested block it may be the output of the step nested before it
        } else if (byIndex.matches()) {
            int index = Integer.parseInt(byIndex.group(1));
            source = atStepIndex(blocks, index, i);
            untold = source == null && drawnBefore(blocks, Math.min(index, own ? i : i + 1));
        }
        if (source == null) {
            // The run quotes it when it was recorded after a model was asked: possible from the first AI step on
            boolean afterModel = first < i || (first == i && !own);
            return untold && afterModel ? "an output whose step the workflow does not tell, in a run that asks"
                    + " a model before it (the AI step " + label(blocks.get(first)) + "), so it may hold what the"
                    + " model wrote" : null;
        }
        if (asksModel(source.block(), children)) return "the output of the AI step " + label(source.block());
        if (source.position() > first || source.afterNestedModel()) {
            return "the output of the step " + label(source.block()) + ", which runs after the AI step "
                    + label(blocks.get(first)) + " and may hold what the model wrote";
        }
        return null;
    }

    private static String label(BlockDefinition block) {
        return block.getName() != null && !block.getName().isBlank() ? block.getName() : block.getType();
    }

    /** The block of that name: a top-level one first, else a nested one. */
    private static Source named(List<BlockDefinition> blocks, WorkflowResolver children, String name) {
        for (int j = 0; j < blocks.size(); j++) {
            if (blocks.get(j) != null && name.equals(blocks.get(j).getName())) return new Source(blocks.get(j), j);
        }
        List<Source> nested = new ArrayList<>();
        for (int j = 0; j < blocks.size(); j++) {
            if (blocks.get(j) == null) continue;
            int position = j;
            // A switch runs its nested blocks once, in the order written; any other wrapper may run them again
            boolean inOrder = "switch".equals(blocks.get(j).getType());
            boolean[] modelBefore = {!inOrder && asksModel(blocks.get(j), children)};
            NestedBlocks.visit(blocks.get(j).getConfig(), inner -> {
                BlockDefinition block = BlockDefinition.builder().type(inner.type()).name(inner.name())
                        .config(inner.config() != null ? inner.config() : Map.of()).build();
                if (name.equals(inner.name())) nested.add(new Source(block, position, modelBefore[0]));
                if (inner.type() != null && asksModel(block, children)) modelBefore[0] = true;
            });
        }
        return nested.isEmpty() ? null : nested.getFirst();
    }

    /** Whether a block before position {@code end} draws further step indices, so the numbers after it are not known. */
    private static boolean drawnBefore(List<BlockDefinition> blocks, int end) {
        for (int j = 0; j < Math.min(end, blocks.size()); j++) {
            if (blocks.get(j) == null || INDEX_DRAWING.contains(blocks.get(j).getType())) return true;
        }
        return false;
    }

    /** The block at position {@code index}, before {@code i}, while no block before it draws further indices. */
    private static Source atStepIndex(List<BlockDefinition> blocks, int index, int i) {
        if (index >= i) return null;
        for (int j = 0; j < index; j++) {
            if (blocks.get(j) == null || INDEX_DRAWING.contains(blocks.get(j).getType())) return null;
        }
        return blocks.get(index) == null ? null : new Source(blocks.get(index), index);
    }

    /** Whether a run of the block asks a model: the block writes AI text itself, or its child workflow does. */
    private static boolean asksModel(BlockDefinition block, WorkflowResolver children) {
        return writesAiText(block) || childAsksModel(block, children);
    }

    /**
     * Whether the workflow the block runs by a literal {@code workflow_id}, or one that workflow reaches, has a block
     * that writes AI text. A reference with a placeholder or one that names no workflow is not followed, and neither
     * is any without a resolver: the run quotes such a child's output when the child asked a model.
     */
    private static boolean childAsksModel(BlockDefinition block, WorkflowResolver children) {
        String ref = children == null ? null : ChildWorkflows.literalRef(block.getType(), block.getConfig());
        if (ref == null) return false;
        WorkflowDefinition child;
        try {
            child = children.resolve(ref);
        } catch (RuntimeException e) {
            return false;   // a reference that names no workflow fails at run time, with its own message
        }
        if (child == null) return false;
        for (ChildWorkflows.Reached reached : ChildWorkflows.reached(child, children.forChild(child))) {
            for (BlockDefinition inner : reached.definition().blocks()) {
                if (inner != null && writesAiText(inner)) return true;
            }
        }
        return false;
    }

    /** Whether the block's output is text a model wrote: it calls an AI itself, or a block nested in it does. */
    private static boolean writesAiText(BlockDefinition block) {
        if (block.getType() == null) return false;
        if (StepModes.usesAi(block)) return true;
        boolean[] nestedAi = {false};
        NestedBlocks.visit(block.getConfig(), inner -> {
            if (inner.type() != null && StepModes.usesAi(BlockDefinition.builder().type(inner.type())
                    .config(inner.config() != null ? inner.config() : Map.of()).build())) {
                nestedAi[0] = true;
            }
        });
        return nestedAi[0];
    }
}
