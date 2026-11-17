package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The writable mounts a workflow writes out: the {@code mounts} entries {@code host:container:rw} whose host side
 * is literal text. A step that runs as the operator can leave a file on the host that the host runs later, so a
 * package mounts a host path writable only in a folder the operator listed ({@code writable_mounts} in
 * {@code traceworks.json}). The start check of a run asks that of every entry found here, before any step runs;
 * the container adapter's mount check asks it again of every mount when its step runs, and is the only one for an
 * entry that a placeholder names.
 */
public final class WritableMounts {

    private WritableMounts() {
    }

    /**
     * A writable mount as a workflow writes it.
     *
     * @param where    the block that declares it, as a refusal names it: {@code Block 2 "export"}, with the path of
     *                 a nested block and, for a block of a child workflow, the workflow first
     * @param entry    the entry as written, without white space around it
     * @param hostPath its host side, as written
     */
    public record Written(String where, String entry, String hostPath) {}

    /**
     * The writable mounts with a literal host side that the steps of {@code definition} declare, nested blocks and
     * the workflows it reaches by a literal {@code workflow_id} included, in the order of the blocks.
     *
     * @param children     finds the child workflows; {@code null} looks into none
     * @param containerRun whether the run is a container run. In a host run a command without an image of its own
     *                     runs on the host, without its mounts, so only a step with a {@code container_image} has
     *                     any
     */
    public static List<Written> writtenOut(WorkflowDefinition definition, WorkflowResolver children,
                                           boolean containerRun) {
        List<Written> found = new ArrayList<>();
        for (WorkflowDefinition workflow : ChildWorkflows.reachable(definition, children)) {
            String prefix = workflow == definition ? "" : "workflow " + workflow.id() + ", ";
            List<BlockDefinition> blocks = workflow.blocks();
            for (int i = 0; i < blocks.size(); i++) {
                BlockDefinition block = blocks.get(i);
                if (block == null || block.getConfig() == null) {
                    continue;
                }
                String where = prefix + "Block " + (i + 1) + named(block.getName());
                collect(where, block.getType(), block.getConfig(), false, containerRun, found);
                NestedBlocks.visit(block.getConfig(), nested -> collect(where + " \u203a " + nested.path()
                        + named(nested.name()), nested.type(), nested.config(), true, containerRun, found));
            }
        }
        return found;
    }

    private static void collect(String where, String type, Map<String, Object> config, boolean nested,
                                boolean containerRun, List<Written> found) {
        if (config == null || !RunRequirements.readByAShellStep(type, config, nested)
                || !(config.get("mounts") instanceof List<?> mounts)) {
            return;
        }
        if (!containerRun && !(config.get("container_image") instanceof String image && !image.isBlank())) {
            return;
        }
        for (Object mount : mounts) {
            if (mount instanceof String entry) {
                String host = literalHostOfAWritableEntry(entry);
                if (host != null) {
                    found.add(new Written(where, entry.strip(), host));
                }
            }
        }
    }

    /**
     * The host side of a {@code mounts} entry that is written {@code host:container:rw} without a placeholder;
     * {@code null} for any other entry: a read-only one, one with a placeholder (the step resolves it and its own
     * check judges what it names), and one that is no mount (the step refuses it).
     */
    public static String literalHostOfAWritableEntry(String entry) {
        if (entry == null || entry.contains("{{")) {
            return null;
        }
        String[] parts = entry.strip().split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || !parts[2].equals("rw")) {
            return null;
        }
        return parts[0].strip();
    }

    private static String named(String name) {
        return name == null || name.isBlank() ? "" : " \"" + name + "\"";
    }
}
