package traceworks.core.compare;

import traceworks.core.compare.LineDiff.Line;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LineDiffTest {

    private static List<String> rendered(String a, String b) {
        return LineDiff.of(a, b).orElseThrow().stream().map(line -> line.tag() + line.text()).toList();
    }

    @Test
    void equalTexts_haveOnlyEqualLines() {
        assertThat(rendered("a\nb\n", "a\nb\n")).containsExactly(" a", " b");
    }

    @Test
    void aChangedLine_isRemovedAndAdded_betweenTheLinesThatStay() {
        assertThat(rendered("a\nb\nc\n", "a\nx\nc\n")).containsExactly(" a", "-b", "+x", " c");
    }

    @Test
    void addedAndRemovedLines_keepTheLongestCommonSubsequence() {
        assertThat(rendered("a\nb\nc\nd", "b\nd\ne")).containsExactly("-a", " b", "-c", " d", "+e");
    }

    @Test
    void anEmptySide_isAllAddedOrAllRemoved() {
        assertThat(rendered("", "a\nb")).containsExactly("+a", "+b");
        assertThat(rendered("a\nb", "")).containsExactly("-a", "-b");
    }

    /** The bound counts what is left after the common start and end, so one changed line of a long text is diffed. */
    @Test
    void theBound_countsOnlyTheMiddleThatDiffers() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            a.append("line ").append(i).append('\n');
            b.append(i == 10_000 ? "other" : "line " + i).append('\n');
        }

        List<Line> diff = LineDiff.of(a.toString(), b.toString()).orElseThrow();

        assertThat(diff).hasSize(20_001);
        assertThat(diff.stream().filter(line -> line.tag() != ' ').map(line -> line.tag() + line.text()))
                .containsExactly("-line 10000", "+other");
    }

    @Test
    void overTheBound_thereIsNoDiff() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (int i = 0; i < 2_100; i++) {
            a.add("a" + i);
            b.add("b" + i);
        }

        Optional<List<Line>> diff = LineDiff.of(a, b);

        assertThat((long) a.size() * b.size()).isGreaterThan(LineDiff.MAX_CELLS);
        assertThat(diff).isEmpty();
    }

    /**
     * With one side empty the table has no cells, whatever the other side holds: a text of millions of lines would
     * get a row of the table and a line of the diff each. So each side has a bound of its own.
     */
    @Test
    void aSideOverItsOwnBound_hasNoDiff_alsoAgainstAnEmptyOne() {
        List<String> most = java.util.Collections.nCopies(LineDiff.MAX_LINES, "x");
        List<String> more = java.util.Collections.nCopies(LineDiff.MAX_LINES + 1, "x");

        assertThat(LineDiff.of(most, List.of()).orElseThrow()).hasSize(LineDiff.MAX_LINES);
        assertThat(LineDiff.of(more, List.of())).isEmpty();
        assertThat(LineDiff.of(List.of(), more)).isEmpty();
        assertThat(LineDiff.of(more, more)).as("equal texts too: every line would be a line of the diff").isEmpty();
    }

    /** Long runs of equal lines are folded: the context around each change stays, the rest becomes a count. */
    @Test
    void fold_keepsTheContextAroundAChange_andCountsTheRest() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            a.append(i).append('\n');
            b.append(i == 10 ? "ten" : String.valueOf(i)).append('\n');
        }

        List<String> folded = LineDiff.fold(LineDiff.of(a.toString(), b.toString()).orElseThrow(), 2);

        assertThat(folded).containsExactly("  ... 8 equal lines", "  8", "  9", "- 10", "+ ten", "  11", "  12",
                "  ... 7 equal lines");
    }

    @Test
    void fold_keepsAShortRunWhole() {
        List<String> folded = LineDiff.fold(LineDiff.of("a\nb\nc\nd", "a\nb\nc\nx").orElseThrow(), 2);

        assertThat(folded).containsExactly("  a", "  b", "  c", "- d", "+ x");
    }
}
