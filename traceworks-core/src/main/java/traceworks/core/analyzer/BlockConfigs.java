package traceworks.core.analyzer;

import traceworks.core.engine.NestedBlocks;
import traceworks.core.model.BlockDefinition;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The walk over a workflow's blocks that a rule makes when it checks the configuration of every block: each top-level
 * block, then every block nested in it (the branches of a {@code condition}, the body of a loop ...). A finding hangs
 * on the top-level block (its index and name); for a nested block the location prefix tells where it sits. The rules
 * word that prefix in two ways, and both are here, so no message changes with the walk: {@link Config#where()}
 * ({@code Block 2 > cases[0] › Block 1: }) and {@link Config#prefix()}, which names the nested block too
 * ({@link FindingText#nestedPath}).
 */
final class BlockConfigs {

    private BlockConfigs() {
    }

    /**
     * One block to check: its type and configuration (each may be null), the index and name of the top-level block it
     * belongs to, and where it sits, the prefix for a finding's message: empty for a top-level block,
     * {@code "Block 2 > cases[0] › Block 1: "} for a nested one.
     *
     * @param block the top-level block: the block itself, or the one the nested block sits in
     * @param inner the nested block; {@code null} for a top-level block
     */
    record Config(String type, Map<String, Object> config, int index, String name, String where,
                  BlockDefinition block, NestedBlocks.Nested inner) {

        /** Whether the block is nested in a top-level block. */
        boolean nested() {
            return inner != null;
        }

        /** Where a nested block sits, with its name ({@link FindingText#nestedPath}); {@code null} at the top level. */
        String path() {
            return inner == null ? null : FindingText.nestedPath(index, inner);
        }

        /** {@link #path()} as the prefix of a finding's message; empty for a top-level block. */
        String prefix() {
            return inner == null ? "" : path() + ": ";
        }
    }

    /** Hands each top-level and nested block to {@code check}. Null blocks are skipped. */
    static void walk(List<BlockDefinition> blocks, Consumer<Config> check) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            walk(block, i, check);
        }
    }

    /**
     * Hands the top-level block at {@code index}, then every block nested in it, to {@code check}: the walk of a rule
     * that goes through the blocks itself and checks one at a time.
     */
    static void walk(BlockDefinition block, int index, Consumer<Config> check) {
        check.accept(new Config(block.getType(), block.getConfig(), index, block.getName(), "", block, null));
        NestedBlocks.visit(block.getConfig(), nested -> check.accept(new Config(nested.type(), nested.config(), index,
                block.getName(), "Block " + (index + 1) + " > " + nested.path() + ": ", block, nested)));
    }
}
