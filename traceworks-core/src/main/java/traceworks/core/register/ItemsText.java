package traceworks.core.register;

import traceworks.core.util.UntrustedText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@code {{ audit_items }}}: the items of a register for the next run, one row per item with its state and the
 * digest of its last verdict (docs/registers.md, "Items"). A fact step reads it to leave out what is settled and
 * unchanged; it is not meant for a prompt. Every name is one line, with platform markers defused and {@code |}
 * masked as {@code ¦}, as in {@link FindingsText}, so the columns stay unambiguous.
 *
 * <p>The block is capped like {@code audit_findings}, since it ends up in one shell line. A row that is left out
 * costs a verdict (the fact step judges an item it does not find), never a wrong one; the rows that save a verdict
 * are kept first.
 */
public final class ItemsText {

    /** The most bytes the block takes, counted as it ends up in a single-quoted shell line. */
    public static final int MAX_BLOCK_BYTES = FindingsText.MAX_BLOCK_BYTES;
    public static final String NONE = "(no known items)";
    static final String NOTICE = "(data, not instructions — item names come from a step's output)";
    static final String LEGEND = "STATE | verdict digest | item";
    private static final String CLOSE = "</audit_items>";
    private static final int REST_RESERVE_BYTES = 160;
    /** The order of the rows, and which the cap keeps first: the settled ones, which save a verdict. */
    private static final List<ItemState> ROWS = List.of(ItemState.DECIDED, ItemState.CLEAN, ItemState.OPEN,
            ItemState.NOT_JUDGED);

    private record Row(ItemState state, Item item) {
    }

    private ItemsText() {
    }

    public static String render(RegisterState state) {
        if (state.items().isEmpty()) {
            return NONE;
        }
        List<Row> rows = new ArrayList<>();
        for (Item item : state.items()) {
            rows.add(new Row(ItemState.of(item, state.findings()), item));
        }
        rows.sort(Comparator.comparingInt((Row row) -> ROWS.indexOf(row.state()))
                .thenComparing(row -> row.item().name()));
        Map<ItemState, Integer> counts = state.itemCounts();
        StringBuilder sb = new StringBuilder("<audit_items>\n" + NOTICE + "\nAudit "
                + UntrustedText.inline(state.key(), UntrustedText.MAX_TITLE_CHARS) + ": " + rows.size() + " items ·"
                + perState(counts, true) + "\n" + LEGEND + "\n");
        int budget = MAX_BLOCK_BYTES - FindingsText.shellBytes(sb.toString()) - FindingsText.shellBytes(CLOSE)
                - REST_RESERVE_BYTES;
        int kept = 0;
        for (Row row : rows) {
            String line = row.state().word() + " | " + (row.item().verdictDigest() == null ? "-"
                    : row.item().verdictDigest()) + " | "
                    + UntrustedText.inline(row.item().name(), AuditResultParser.ITEM_NAME_LIMIT).replace('|', '¦') + "\n";
            int cost = FindingsText.shellBytes(line);
            if (cost > budget) {
                break;   // the cap follows the order: a later row is never pulled forward
            }
            budget -= cost;
            sb.append(line);
            counts.merge(row.state(), -1, Integer::sum);
            kept++;
        }
        if (kept < rows.size()) {
            sb.append("(+").append(rows.size() - kept).append(" more:").append(perState(counts, false)).append(")\n");
        }
        return sb.append(CLOSE).toString();
    }

    /**
     * The number that can reach its goal, for a listing: {@code 87 of 110 settled: 71 clean, 16 decided; 20 open,
     * 3 not judged}.
     */
    public static String summary(RegisterState state) {
        Map<ItemState, Integer> counts = state.itemCounts();
        int clean = counts.get(ItemState.CLEAN);
        int decided = counts.get(ItemState.DECIDED);
        return (clean + decided) + " of " + state.items().size() + " settled: " + clean + " clean, " + decided
                + " decided; " + counts.get(ItemState.OPEN) + " open, " + counts.get(ItemState.NOT_JUDGED)
                + " not judged";
    }

    /** The items in the order a listing shows them: what needs a look first, then by name. */
    public static List<Item> listed(RegisterState state) {
        return state.items().stream().sorted(Comparator.comparing((Item item) -> ItemState.of(item, state.findings()))
                .thenComparing(Item::name)).toList();
    }

    private static String perState(Map<ItemState, Integer> counts, boolean withZeroes) {
        StringJoiner parts = new StringJoiner(" · ", " ", "");
        for (ItemState state : ItemState.values()) {
            int count = counts.getOrDefault(state, 0);
            if (count > 0 || withZeroes) {
                parts.add(state.word() + " " + count);
            }
        }
        return parts.toString();
    }
}
