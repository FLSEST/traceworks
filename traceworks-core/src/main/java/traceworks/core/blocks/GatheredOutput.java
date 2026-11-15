package traceworks.core.blocks;

import traceworks.core.context.OutputBudget;
import traceworks.core.util.TruncationUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The output a wrapper block gathers from what it runs: the items of a {@code foreach}, the iterations of a
 * {@code while}, the blocks of a {@code switch} case. It keeps the parts as they come and joins them once, at the exact
 * size of the result, into the text {@code String.trim()} of their concatenation gives. A growing
 * {@code StringBuilder} followed by {@code toString().trim()} holds up to twice the text in spare capacity and makes
 * two more copies of it at the end; for a loop that gathers a large output, that is most of the memory the step needs.
 *
 * <p>What it gathers counts against the run's {@link OutputBudget} until {@link #text} hands it on as the step's
 * output, which the engine counts then: as the memory the joined text will take ({@link OutputBudget#sizeOf}), two
 * bytes per character for all of it once one part holds a character above {@code U+00FF}. A part appended as
 * {@code null} counts as the text {@code "null"}, as {@link StringBuilder#append(String)} has it.
 */
final class GatheredOutput {

    /** The most characters of a line that {@link #note} keeps. */
    static final int NOTE_MAX_CHARS = 2_000;

    private final OutputBudget budget;
    private final List<String> parts = new ArrayList<>();
    /** The characters gathered so far. */
    private long length;
    /** Whether a part holds a character above {@code U+00FF}: the joined text then takes two bytes per character. */
    private boolean wide;
    /** What this output has taken from the budget: the size of its joined text so far. */
    private long taken;

    /** An output that counts against no budget. */
    GatheredOutput() {
        this(OutputBudget.none());
    }

    GatheredOutput(OutputBudget budget) {
        this.budget = budget;
    }

    /**
     * Appends {@code pieces} in order, if the run's budget takes what the joined text grows by.
     *
     * @return {@code false}, and nothing is appended, when they would take the run beyond its budget
     */
    boolean add(String... pieces) {
        return append(pieces, false);
    }

    /**
     * Appends a line the block writes itself to say how it ended (its tally of failed items, a condition that failed),
     * whether or not the budget takes it: the cause stays visible next to the budget's note, should the run end at the
     * budget. It counts all the same. A line longer than {@value #NOTE_MAX_CHARS} characters is cut there, before a
     * value of {@code keepWhole} it would split, and says so at its end.
     */
    void note(String line, Collection<String> keepWhole) {
        String kept = line.length() <= NOTE_MAX_CHARS ? line
                : line.substring(0, TruncationUtil.cutBefore(line, NOTE_MAX_CHARS, keepWhole)) + " [cut at "
                + NOTE_MAX_CHARS + " of " + line.length() + " characters]";
        append(new String[] {kept}, true);
    }

    /** Appends {@code pieces}: if the budget takes them, or {@code always}, counted without a check. */
    private boolean append(String[] pieces, boolean always) {
        long addedLength = 0;
        boolean addedWide = false;
        for (String piece : pieces) {
            String part = String.valueOf(piece);
            addedLength += part.length();
            addedWide = addedWide || OutputBudget.sizeOf(part) > part.length();
        }
        long size = (wide || addedWide ? 2 : 1) * (length + addedLength);
        if (always) {
            budget.hold(size - taken);
        } else if (!budget.take(size - taken)) {
            return false;
        }
        taken = size;
        wide = wide || addedWide;
        for (String piece : pieces) {
            String part = String.valueOf(piece);
            parts.add(part);
            length += part.length();
        }
        return true;
    }

    /** Whether nothing has been gathered so far, or only empty text. */
    boolean isEmpty() {
        return length == 0;
    }

    /**
     * The gathered text, trimmed as {@code String.trim()} trims it: without the characters up to {@code U+0020} at its
     * start and end. Only a part at either end that has such characters is copied before the join, and a single part
     * that needs no trim is the text itself. Afterwards nothing is gathered any more, and the budget has back what
     * the gathering took.
     */
    String text() {
        budget.release(taken);
        taken = 0;
        int first = 0;
        int start = 0;
        while (first < parts.size()) {
            start = firstVisible(parts.get(first));
            if (start >= 0) {
                break;
            }
            first++;
        }
        if (first == parts.size()) {
            clear();
            return "";
        }
        int last = parts.size() - 1;
        int end = lastVisible(parts.get(last));
        while (end < 0) {
            last--;
            end = lastVisible(parts.get(last));
        }
        List<String> kept = new ArrayList<>(last - first + 1);
        for (int i = first; i <= last; i++) {
            String part = parts.get(i);
            int from = i == first ? start : 0;
            int to = i == last ? end : part.length();
            kept.add(from == 0 && to == part.length() ? part : part.substring(from, to));
        }
        clear();
        return kept.size() == 1 ? kept.getFirst() : String.join("", kept);
    }

    private void clear() {
        parts.clear();
        length = 0;
        wide = false;
    }

    /** The index of the first character above {@code U+0020}; {@code -1} when there is none. */
    private static int firstVisible(String part) {
        for (int i = 0; i < part.length(); i++) {
            if (part.charAt(i) > ' ') {
                return i;
            }
        }
        return -1;
    }

    /** The index after the last character above {@code U+0020}; {@code -1} when there is none. */
    private static int lastVisible(String part) {
        for (int i = part.length(); i > 0; i--) {
            if (part.charAt(i - 1) > ' ') {
                return i;
            }
        }
        return -1;
    }
}
