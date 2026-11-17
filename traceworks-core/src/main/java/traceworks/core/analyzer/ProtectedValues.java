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
 * @param first the position of the first top-level block that asks a model; -1 for a workflow without one
 */
record ProtectedValues(List<BlockDefinition> blocks, WorkflowResolver children, int first) {

    /** Outputs of earlier runs: always untrusted. */
    static final Set<String> PLATFORM_DATA = Set.of("previous_report", "audit_findings", "audit_items");

    /** Text from outside: a chain hands on the last entry's output. */
    private static final Set<String> FOREIGN_TEXT = Set.of("previous_chain_output");

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
        int first = -1;
        for (int j = 0; j < blocks.size() && first < 0; j++) {
            if (blocks.get(j) != null && asksModel(blocks.get(j), children)) first = j;
        }
        return new ProtectedValues(blocks, children, first);
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
