package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;

import java.util.Collection;
import java.util.List;

/**
 * Rules for {@code foreach} blocks.
 *
 * <p>{@code items-command-without-source}: a block sets {@code items_command}, but {@code items_source} is not
 * {@code "command"}. The foreach executor only runs the command with that source and otherwise takes {@code items};
 * if that list is missing, the block runs with zero items and still ends COMPLETED. {@code ERROR} if nothing runs as
 * a result; {@code WARNING} if {@code items} steps in and only the command is dead.
 */
final class ForEachRules {

    private ForEachRules() {}

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null || !"foreach".equals(block.getType()) || block.getConfig() == null) continue;
            String itemsCommand = BlockText.directValue(block, "items_command");
            Object source = block.getConfig().get("items_source");
            if (itemsCommand == null || itemsCommand.isBlank() || "command".equals(source)) continue;

            boolean hasItems = hasItems(block.getConfig().get("items"));
            out.add(AnalyzerFinding.of("items-command-without-source", hasItems ? Severity.WARNING : Severity.ERROR,
                    i, block.getName(), "sets items_command, but items_source is "
                            + (source == null ? "not set" : "\"" + source + "\"")
                            + " — the command never runs, "
                            + (hasItems ? "the items come from items" : "the block runs with zero items")
                            + "; set items_source: \"command\""));
        }
    }

    /** Like the executor: a list with entries or a text with at least one line. */
    private static boolean hasItems(Object items) {
        if (items instanceof Collection<?> list) {
            return list.stream().anyMatch(item -> item != null && !item.toString().isBlank());
        }
        return items instanceof String text && !text.isBlank();
    }
}
