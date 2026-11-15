package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.model.BlockDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules for the <b>names</b> of the blocks.
 *
 * <p>{@code duplicate-block-name} ({@code ERROR}): two blocks of one name that can both run in one run. A run keeps
 * one output per name: {@code {{ block.<name>.output }}} then holds the output of whichever of the two ran last, and a
 * {@code compress} block refuses the name as its source. The names of all blocks of a run share one namespace: the
 * top-level blocks and the blocks of a {@code switch} case, which record their output under their name like a
 * top-level block. Only one case of a {@code switch} runs, so the same name in two cases of one {@code switch} (or in
 * a case and its {@code default}) is one name and no finding. A plain {@code blocks} list is run by no executor
 * ({@code dead-nested-blocks}) and records nothing. A child workflow has its own namespace.
 */
final class BlockNameRules {

    private BlockNameRules() {}

    /** The separator of {@link NestedBlocks.Nested#path()}. */
    private static final String PATH_SEPARATOR = " › ";

    /**
     * One named block.
     *
     * @param index  the index of its top-level block
     * @param path   where it sits below that block, as the segments of {@link NestedBlocks.Nested#path()}; empty for
     *               the top-level block itself
     * @param nested the nested block; {@code null} for the top-level block itself
     */
    private record Named(int index, List<String> path, String name, NestedBlocks.Nested nested) { }

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        List<Named> seen = new ArrayList<>();
        for (Named block : named(blocks)) {
            boolean twice = seen.stream().anyMatch(earlier -> earlier.name().equals(block.name())
                    && !alternatives(earlier, block));
            seen.add(block);
            if (!twice) continue;
            String where = block.nested() == null ? ""
                    : FindingText.nestedPath(block.index(), block.nested()) + " ";
            BlockDefinition top = blocks.get(block.index());
            out.add(AnalyzerFinding.of("duplicate-block-name", Severity.ERROR, block.index(), top.getName(),
                    where + "shares its name with an earlier block — {{ block." + block.name() + ".output }} then"
                            + " holds the output of whichever of them ran last, and a compress block refuses the name"
                            + " as its source; give each block its own name"));
        }
    }

    /** Every name a {@code {{ block.<name>.output }}} can mean: the top-level blocks and every nested block. */
    static Set<String> allNames(List<BlockDefinition> blocks) {
        Set<String> names = new LinkedHashSet<>();
        for (BlockDefinition block : blocks) {
            if (block == null) continue;
            if (hasName(block.getName())) names.add(block.getName());
            NestedBlocks.visit(block.getConfig(), nested -> {
                if (hasName(nested.name())) names.add(nested.name());
            });
        }
        return names;
    }

    /** The named blocks that a run can record, in definition order: without those of a plain {@code blocks} list. */
    private static List<Named> named(List<BlockDefinition> blocks) {
        List<Named> named = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            int index = i;
            if (hasName(block.getName())) named.add(new Named(index, List.of(), block.getName(), null));
            NestedBlocks.visit(block.getConfig(), nested -> {
                List<String> path = List.of(nested.path().split(PATH_SEPARATOR));
                if (hasName(nested.name()) && !path.contains("blocks")) {
                    named.add(new Named(index, path, nested.name(), nested));
                }
            });
        }
        return named;
    }

    /**
     * Whether a run records at most one of the two: they sit in different branches of one {@code switch}, so their
     * paths below the same top-level block part at a branch ({@code cases[0]} against {@code cases[1]} or
     * {@code default}), not at a block.
     */
    private static boolean alternatives(Named a, Named b) {
        if (a.index() != b.index()) return false;
        int shared = Math.min(a.path().size(), b.path().size());
        for (int i = 0; i < shared; i++) {
            String left = a.path().get(i);
            String right = b.path().get(i);
            if (!left.equals(right)) return isBranch(left) && isBranch(right);
        }
        return false;
    }

    private static boolean isBranch(String segment) {
        return segment.startsWith("cases[") || segment.equals("default");
    }

    private static boolean hasName(String name) {
        return name != null && !name.isBlank();
    }
}
