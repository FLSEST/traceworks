package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The workflows a run can reach before it starts: its definition and the workflows its {@code sub-workflow} and
 * {@code foreach} blocks name literally in {@code workflow_id}, at any depth. The start check judges them all: their
 * secrets, and the images and credentials of their AI providers. A reference with a placeholder, or one that names no
 * workflow, is not followed: the block fails when it runs, with its own message. A workflow already reached is not
 * followed again, so a cycle ends. The walk also says which of them come from another package than the run's
 * ({@link WorkflowResolver#fromAnotherPackage}), whose {@code ./images/<name>} the start check refuses.
 */
public final class ChildWorkflows {

    /** How deep the walk follows children of children; the engine itself has no lower limit. */
    private static final int MAX_DEPTH = 16;

    private ChildWorkflows() {
    }

    /**
     * A workflow the walk reached.
     *
     * @param otherPackage whether it comes from another package than the run's; {@code false} for the definition the
     *                     walk starts from
     */
    public record Reached(WorkflowDefinition definition, boolean otherPackage) {}

    /**
     * {@code definition} first, then every workflow it reaches by a literal reference, each once, in the order the
     * walk finds them.
     *
     * @param children finds the child workflows (a child's references through {@link WorkflowResolver#forChild});
     *                 {@code null} looks into none
     */
    public static List<WorkflowDefinition> reachable(WorkflowDefinition definition, WorkflowResolver children) {
        return reached(definition, children).stream().map(Reached::definition).toList();
    }

    /** {@link #reachable}, each with whether it comes from another package than the run's. */
    public static List<Reached> reached(WorkflowDefinition definition, WorkflowResolver children) {
        List<Reached> found = new ArrayList<>();
        found.add(new Reached(definition, false));
        Set<String> seen = new HashSet<>();
        if (definition.id() != null && !definition.id().isBlank()) {
            seen.add(definition.id());
        }
        collect(definition, children, seen, 0, found);
        return found;
    }

    private static void collect(WorkflowDefinition definition, WorkflowResolver children, Set<String> seen, int depth,
                                List<Reached> found) {
        if (children == null || depth >= MAX_DEPTH) {
            return;
        }
        for (BlockDefinition block : definition.blocks()) {
            String ref = block == null ? null : literalRef(block.getType(), block.getConfig());
            if (ref == null) {
                continue;
            }
            WorkflowDefinition child;
            try {
                child = children.resolve(ref);
            } catch (RuntimeException e) {
                continue;   // a reference that names no workflow fails when the block runs, with its own message
            }
            if (child != null && seen.add(child.id() == null || child.id().isBlank() ? ref : child.id())) {
                found.add(new Reached(child, children.fromAnotherPackage(child)));
                collect(child, children.forChild(child), seen, depth + 1, found);
            }
        }
    }

    /** Whether a block of {@code type} with {@code config} runs a child workflow: it sets a {@code workflow_id}. */
    public static boolean runsAChild(String type, Map<String, Object> config) {
        return config != null && ("sub-workflow".equals(type) || "foreach".equals(type))
                && config.get("workflow_id") instanceof String ref && !ref.isBlank();
    }

    /**
     * The {@code workflow_id} a block runs as a child, when it is literal, without its edge spaces, as the block reads
     * it ({@code SubWorkflowLauncher.resolveWorkflowId}); {@code null} otherwise.
     */
    public static String literalRef(String type, Map<String, Object> config) {
        if (config == null || (!"sub-workflow".equals(type) && !"foreach".equals(type))) {
            return null;
        }
        return config.get("workflow_id") instanceof String ref && !ref.isBlank() && !ref.contains("{{")
                ? ref.strip() : null;
    }
}
