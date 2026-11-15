package traceworks.core.register;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The state of an item is derived from its last verdict and from the findings that name it; nothing stores it. */
class ItemStateTest {

    private static final Instant T = Instant.parse("2026-11-13T10:00:00Z");
    private static final Finding.Seen RUN = new Finding.Seen("run-1", T);

    private static Finding finding(String item, FindingStatus status) {
        return new Finding("f".repeat(64), "r", "s", Severity.MEDIUM, "t", null, null, List.of(), status, RUN, RUN, 1,
                0, false, null, null, 0, item);
    }

    @Test
    void anItemNoRunJudged_isNotJudged() {
        assertThat(ItemState.of(new Item("p.A", "aa", null, null), List.of())).isEqualTo(ItemState.NOT_JUDGED);
    }

    @Test
    void anItemJudgedWithoutAFinding_isClean() {
        assertThat(ItemState.of(new Item("p.A", "aa", "aa", RUN), List.of(finding("p.B", FindingStatus.OPEN))))
                .isEqualTo(ItemState.CLEAN);
    }

    @Test
    void anItemWithAnOpenFinding_orOneDueForReview_isOpen() {
        Item item = new Item("p.A", "aa", "aa", RUN);

        assertThat(ItemState.of(item, List.of(finding("p.A", FindingStatus.OPEN)))).isEqualTo(ItemState.OPEN);
        assertThat(ItemState.of(item, List.of(finding("p.A", FindingStatus.DISMISSED),
                finding("p.A", FindingStatus.AWAITING_VERIFICATION)))).isEqualTo(ItemState.OPEN);
    }

    @Test
    void anItemWhoseFindingsAreAllDecidedOrResolved_isDecided() {
        Item item = new Item("p.A", "aa", "aa", RUN);

        for (FindingStatus status : List.of(FindingStatus.ACCEPTED_RISK, FindingStatus.DISMISSED,
                FindingStatus.RESOLVED)) {
            assertThat(ItemState.of(item, List.of(finding("p.A", status)))).as(status.name())
                    .isEqualTo(ItemState.DECIDED);
        }
    }

    @Test
    void anItemThatChangedSinceItsVerdict_isNotJudged() {
        Item changed = new Item("p.A", "bb", "aa", RUN);

        assertThat(ItemState.of(changed, List.of())).isEqualTo(ItemState.NOT_JUDGED);
        assertThat(ItemState.of(changed, List.of(finding("p.A", FindingStatus.DISMISSED))))
                .isEqualTo(ItemState.NOT_JUDGED);
    }

    @Test
    void anOpenFinding_winsOverAChangedDigest() {
        assertThat(ItemState.of(new Item("p.A", "bb", "aa", RUN), List.of(finding("p.A", FindingStatus.OPEN))))
                .isEqualTo(ItemState.OPEN);
    }

    @Test
    void theWords_areLowerCase_andOnlyCleanAndDecidedAreSettled() {
        assertThat(List.of(ItemState.values())).extracting(ItemState::word)
                .containsExactly("open", "not judged", "decided", "clean");
        assertThat(List.of(ItemState.values())).filteredOn(ItemState::settled)
                .containsExactly(ItemState.DECIDED, ItemState.CLEAN);
    }
}
