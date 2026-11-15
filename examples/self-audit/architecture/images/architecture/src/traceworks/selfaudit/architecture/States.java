package traceworks.selfaudit.architecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of every item of the audit after a run, which the gate follows (docs/registers.md, "Items"). The number
 * that can reach its goal is the items that are settled, of all items; the number of findings cannot, since it grows
 * with attention.
 *
 * <p>The report step computes the states from what it knows: what the fact step read of the register (which items
 * it left out as settled, and how many open and decided findings the register holds of each) and what this run
 * judged. Where the register handed the run nothing, nothing was known before, and the same holds.
 *
 * <ul>
 * <li><em>clean</em>: left out as clean; or judged in this run with nothing that goes to the register;</li>
 * <li><em>decided</em>: left out as decided; or judged with nothing new, and a person decided a finding of the item
 * or an answer said that one is gone;</li>
 * <li><em>open</em>: a new finding for the register, an open finding of the register that holds, or one that got no
 * answer, also because its item got no verdict;</li>
 * <li><em>not judged</em>: no place under the cap, a failed call, or an answer with something that did not
 * verify.</li>
 * </ul>
 */
final class States {

    private States() {
    }

    /**
     * The items by their state after the run.
     *
     * @param highStands whether an open finding of high severity stands: a new one, or one of the register that
     *                   this run did not answer as gone
     */
    record Counts(int clean, int decided, int open, int notJudged, boolean highStands) {

        /** Whether every item is settled. */
        boolean allSettled() {
            return open == 0 && notJudged == 0;
        }

        /** The number that can reach its goal, in the wording every self-audit uses. */
        String text() {
            int all = clean + decided + open + notJudged;
            return (clean + decided) + " of " + all + (all == 1 ? " item" : " items") + " settled (" + clean
                    + " clean, " + decided + " decided; " + open + " open, " + notJudged + " not judged)";
        }
    }

    /**
     * The items the gate follows: those the facts list, or, where the facts list none, the lines of this run and the
     * ones the cap cut.
     *
     * @param listed  the {@code items} of the facts, or anything else when the facts have none
     * @param lines   the lines of this run, as the loop got them
     * @param cut     what the facts list as cut by the cap
     * @param cutName the field of a cut entry that names it
     */
    static List<Map<String, Object>> items(Object listed, List<Map<String, Object>> lines, List<?> cut,
                                           String cutName) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (listed instanceof List<?> list) {
            for (Object entry : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                if (entry instanceof Map<?, ?> map) map.forEach((key, value) -> item.put(String.valueOf(key), value));
                items.add(item);
            }
            return items;
        }
        for (Map<String, Object> line : lines) {
            Map<String, Object> item = new LinkedHashMap<>(Known.counts(line));
            item.put("name", name(line));
            item.put("chosen", true);
            items.add(item);
        }
        for (Object entry : cut) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry instanceof Map<?, ?> map ? String.valueOf(map.get(cutName)) : String.valueOf(entry));
            item.put("chosen", false);
            items.add(item);
        }
        return items;
    }

    /** The name a line of the run has as an item: its {@code item}, or without one its {@code id}. */
    static String name(Map<String, Object> line) {
        return line.get("item") instanceof String item && !item.isEmpty() ? item : String.valueOf(line.get("id"));
    }

    /**
     * Counts the items by their state after the run.
     *
     * @param items      the items, each with {@code name}, {@code settled}, {@code state}, {@code open},
     *                   {@code open_high} and {@code decided}
     * @param numberOf   the number each item of this run has in the run, by its name; an item without one got no
     *                   place
     * @param answered   the numbers whose answer was read
     * @param doubtful   the numbers whose answer held something that did not verify
     * @param openNow    the numbers with something that stands after the run: a finding for the register, or an
     *                   open one of the register that the answer left unanswered
     * @param resolved   the numbers whose answer says that an open finding of the register is gone
     * @param highStands whether a finding for the register is high, or an answer left a high one unanswered
     */
    static Counts of(List<Map<String, Object>> items, Map<String, String> numberOf, Set<String> answered,
                     Set<String> doubtful, Set<String> openNow, Set<String> resolved, boolean highStands) {
        return of(items, numberOf, answered, doubtful, openNow, resolved, highStands, false);
    }

    /**
     * As {@link #of(List, Map, Set, Set, Set, Set, boolean)}, for a run the register did not show every finding.
     *
     * @param unseen whether {@code {{ audit_findings }}} left open rows out ({@link Known#missingOpen}). Then
     *               nothing is concluded from a row that is not there: an item the register calls open stays open,
     *               whatever was shown of it and whatever its verdict says, since the block does not say whose the
     *               missing rows are
     */
    static Counts of(List<Map<String, Object>> items, Map<String, String> numberOf, Set<String> answered,
                     Set<String> doubtful, Set<String> openNow, Set<String> resolved, boolean highStands,
                     boolean unseen) {
        int clean = 0;
        int decided = 0;
        int open = 0;
        int notJudged = 0;
        boolean high = highStands;
        for (Map<String, Object> item : items) {
            String number = numberOf.get(String.valueOf(item.get("name")));
            if (Boolean.TRUE.equals(item.get("settled"))) {
                if ("decided".equals(item.get("state"))) decided++;
                else clean++;
            } else if (number != null && openNow.contains(number)
                    || unseen && "open".equals(item.get("state"))) {
                open++;
            } else if (number == null || !answered.contains(number)) {
                // No verdict at all: what the register holds of the item stands as it is
                if (count(item.get("open")) > 0) open++;
                else notJudged++;
                high = high || count(item.get("open_high")) > 0;
            } else if (doubtful.contains(number)) {
                notJudged++;
            } else if (count(item.get("decided")) > 0 || resolved.contains(number)) {
                decided++;
            } else {
                clean++;
            }
        }
        return new Counts(clean, decided, open, notJudged, high);
    }

    private static long count(Object value) {
        return value instanceof Long l ? l : value instanceof Integer i ? i : 0;
    }
}
