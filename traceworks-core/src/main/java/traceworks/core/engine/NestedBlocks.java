package traceworks.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Walks the blocks nested inside a block's raw config, depth first: switch {@code cases[].blocks} and
 * {@code default} (the only nested blocks any executor runs) plus a plain {@code config.blocks} list. <b>No
 * executor runs {@code config.blocks}</b>, on no block type: foreach, retry, conditional and while run their
 * {@code command} (or {@code then_command}/{@code else_command}), never a block list. The list is walked anyway, so
 * that every validation sees what the definition holds; the analyzer reports it as {@code dead-nested-blocks}.
 *
 * <p>One walker for every validation of the same tree, so the type check and the {@link ExecutionMode} check cannot
 * drift apart.
 *
 * <p>Nested blocks are raw maps, not {@code BlockDefinition}s: they are only deserialized when the enclosing block
 * runs. An entry without a {@code config} map is still visited: a block with no type at all is exactly what the type
 * check must reject.
 */
public final class NestedBlocks {

    private NestedBlocks() {
    }

    /**
     * One nested block as it sits in the definition JSON.
     *
     * @param name   its {@code name}, or null
     * @param type   its {@code type}, or null when missing or not a string
     * @param config its {@code config} map, or null when missing or not a map
     * @param path   where it sits below the enclosing top-level block, e.g. {@code "cases[0] › Block 1"}
     *               or {@code "default › Block 2 › blocks › Block 1"}; block numbers count from 1
     * @param fields every key of the nested block's map: besides {@code name}, {@code type} and
     *               {@code config} whatever else stands there, which a run never reads
     */
    public record Nested(String name, String type, Map<String, Object> config, String path, Set<String> fields) {

        /** Name if it has one, else the type: the label validation messages use. */
        public String label() {
            return name != null && !name.isBlank() ? name : type;
        }
    }

    /** What a list of nested blocks is to the block that holds it. */
    public enum Kind {
        /** The blocks of one entry of a switch's {@code cases}. */
        CASE,
        /** A switch's {@code default} blocks. */
        DEFAULT,
        /** A plain {@code config.blocks} list, which no executor runs. */
        DEAD
    }

    /**
     * One list of blocks nested directly in a block's config.
     *
     * @param kind      what the list is
     * @param caseValue the {@code value} of the case as written, for {@link Kind#CASE}; else {@code null}
     * @param blocks    its blocks, in definition order; an entry that is no JSON object is left out
     */
    public record NestedList(Kind kind, Object caseValue, List<Nested> blocks) {

        public NestedList {
            blocks = List.copyOf(blocks);
        }
    }

    /**
     * The lists of blocks nested directly in {@code config}, one level deep, in the order {@link #visit} walks them:
     * each case of {@code cases} (also one without blocks), then {@code default}, then {@code blocks}.
     */
    public static List<NestedList> lists(Map<String, Object> config) {
        return lists(config, "");
    }

    /** Visits every block nested in {@code config}, recursively, in definition order. */
    public static void visit(Map<String, Object> config, Consumer<Nested> visitor) {
        visit(config, "", visitor);
    }

    private static void visit(Map<String, Object> config, String prefix, Consumer<Nested> visitor) {
        for (NestedList list : lists(config, prefix)) {
            for (Nested nested : list.blocks()) {
                visitor.accept(nested);
                visit(nested.config(), nested.path() + " › ", visitor);
            }
        }
    }

    private static List<NestedList> lists(Map<String, Object> config, String prefix) {
        if (config == null) {
            return List.of();
        }
        List<NestedList> lists = new ArrayList<>();
        if (config.get("cases") instanceof List<?> cases) {
            for (int i = 0; i < cases.size(); i++) {
                if (cases.get(i) instanceof Map<?, ?> caseEntry) {
                    lists.add(new NestedList(Kind.CASE, caseEntry.get("value"),
                            blocks(caseEntry.get("blocks"), prefix + "cases[" + i + "]")));
                }
            }
        }
        if (config.get("default") instanceof List<?>) {
            lists.add(new NestedList(Kind.DEFAULT, null, blocks(config.get("default"), prefix + "default")));
        }
        if (config.get("blocks") instanceof List<?>) {
            lists.add(new NestedList(Kind.DEAD, null, blocks(config.get("blocks"), prefix + "blocks")));
        }
        return lists;
    }

    @SuppressWarnings("unchecked")
    private static List<Nested> blocks(Object nestedBlocks, String listPath) {
        if (!(nestedBlocks instanceof List<?> list)) {
            return List.of();
        }
        List<Nested> blocks = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> nested)) {
                continue;
            }
            Map<String, Object> nestedConfig = nested.get("config") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : null;
            blocks.add(new Nested(
                    nested.get("name") instanceof String name ? name : null,
                    nested.get("type") instanceof String type ? type : null,
                    nestedConfig,
                    listPath + " › Block " + (i + 1),
                    nested.keySet().stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new))));
        }
        return blocks;
    }
}
