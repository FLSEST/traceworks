package traceworks.core.compare;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A line diff of two texts by their longest common subsequence, in plain Java. The lines the texts start and end
 * with in common are set aside first; the table of the rest is bounded by {@link #MAX_CELLS} and each text by
 * {@link #MAX_LINES}, and two texts beyond a bound get no diff, so a caller says so instead of waiting for one.
 */
public final class LineDiff {

    /** The most cells of the table: the product of the line counts that differ between the common start and end. */
    public static final long MAX_CELLS = 4_000_000;

    /**
     * The most lines of one text: the table has a row and the diff a line for each, whatever the product is (with an
     * empty side it is 0).
     */
    public static final int MAX_LINES = 200_000;

    private LineDiff() {
    }

    /**
     * One line of a diff.
     *
     * @param tag {@code ' '} for a line of both texts, {@code '-'} for one only the first has, {@code '+'} for one
     *            only the second has
     */
    public record Line(char tag, String text) {}

    /** The diff of two texts, split into lines as {@link String#lines} splits them. */
    public static Optional<List<Line>> of(String a, String b) {
        return of(a.lines().toList(), b.lines().toList());
    }

    /**
     * The diff of two lists of lines: every line of both, in order, the removed lines of a change before the added.
     *
     * @return empty when the part that differs is beyond {@link #MAX_CELLS}, or a text beyond {@link #MAX_LINES}
     */
    public static Optional<List<Line>> of(List<String> a, List<String> b) {
        if (a.size() > MAX_LINES || b.size() > MAX_LINES) {
            return Optional.empty();
        }
        int start = 0;
        while (start < a.size() && start < b.size() && a.get(start).equals(b.get(start))) {
            start++;
        }
        int endA = a.size();
        int endB = b.size();
        while (endA > start && endB > start && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        int n = endA - start;
        int m = endB - start;
        if ((long) n * m > MAX_CELLS) {
            return Optional.empty();
        }
        // lcs[i][j]: the length of the longest common subsequence of a[start+i..endA) and b[start+j..endB)
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(start + i).equals(b.get(start + j)) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> lines = new ArrayList<>(a.size() + m);
        for (int i = 0; i < start; i++) {
            lines.add(new Line(' ', a.get(i)));
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(start + i).equals(b.get(start + j))) {
                lines.add(new Line(' ', a.get(start + i)));
                i++;
                j++;
            } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                lines.add(new Line('-', a.get(start + i++)));
            } else {
                lines.add(new Line('+', b.get(start + j++)));
            }
        }
        for (int k = endA; k < a.size(); k++) {
            lines.add(new Line(' ', a.get(k)));
        }
        return Optional.of(lines);
    }

    /**
     * The diff as text lines, each with its tag and a space in front, and the long runs of equal lines folded: up to
     * {@code context} equal lines stay next to a change, and two or more hidden ones become
     * {@code "  ... N equal lines"}.
     */
    public static List<String> fold(List<Line> diff, int context) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < diff.size()) {
            if (diff.get(i).tag() != ' ') {
                out.add(diff.get(i).tag() + " " + diff.get(i).text());
                i++;
                continue;
            }
            int end = i;
            while (end < diff.size() && diff.get(end).tag() == ' ') {
                end++;
            }
            int after = i == 0 ? 0 : context;                 // equal lines kept after the change before the run
            int before = end == diff.size() ? 0 : context;    // and before the change after it
            int hidden = end - i - after - before;
            if (hidden < 2) {
                for (int k = i; k < end; k++) {
                    out.add("  " + diff.get(k).text());
                }
            } else {
                for (int k = i; k < i + after; k++) {
                    out.add("  " + diff.get(k).text());
                }
                out.add("  ... " + hidden + " equal lines");
                for (int k = end - before; k < end; k++) {
                    out.add("  " + diff.get(k).text());
                }
            }
            i = end;
        }
        return out;
    }
}
