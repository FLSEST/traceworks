package traceworks.core.register;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a person may decide about a finding, and from which status. */
class DispositionTest {

    private static final Instant T = Instant.parse("2026-11-11T10:00:00Z");

    private static Finding with(FindingStatus status) {
        return new Finding("a".repeat(64), "r", "s", Severity.HIGH, "t", "d", "320 LOC", List.of(), status,
                new Finding.Seen("run-1", T), new Finding.Seen("run-1", T), 1, 0, false, null, null);
    }

    @Test
    void accept_freezesTheEvidence_andSetsTheReviewDate_default180Days() {
        Finding accepted = Disposition.accept(with(FindingStatus.OPEN), "known and bounded", "flo@box", T, null);

        assertThat(accepted.status()).isEqualTo(FindingStatus.ACCEPTED_RISK);
        assertThat(accepted.disposition()).isEqualTo(new Finding.DispositionRecord(FindingStatus.ACCEPTED_RISK,
                "known and bounded", "flo@box", T, T.plus(Duration.ofDays(180)), "320 LOC", Severity.HIGH));
        assertThat(accepted.resolvedAt()).isNull();
    }

    @Test
    void accept_takesAnExplicitUntil_atMost180DaysAhead() {
        Instant until = T.plus(Duration.ofDays(30));

        assertThat(Disposition.accept(with(FindingStatus.AWAITING_VERIFICATION), "n", "a", T, until).disposition()
                .until()).isEqualTo(until);
        assertThatThrownBy(() -> Disposition.accept(with(FindingStatus.OPEN), "n", "a", T,
                T.plus(Duration.ofDays(181)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("180 days");
        assertThatThrownBy(() -> Disposition.accept(with(FindingStatus.OPEN), "n", "a", T, T.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("past");
    }

    @Test
    void dismiss_hasNoReviewDate_andNoFrozenEvidence() {
        Finding dismissed = Disposition.dismiss(with(FindingStatus.OPEN), "not ours", "flo@box", T);

        assertThat(dismissed.status()).isEqualTo(FindingStatus.DISMISSED);
        assertThat(dismissed.disposition()).isEqualTo(new Finding.DispositionRecord(FindingStatus.DISMISSED,
                "not ours", "flo@box", T, null, null, Severity.HIGH));
    }

    @Test
    void reopen_clearsTheDisposition_andOpensTheFinding() {
        Finding accepted = Disposition.accept(with(FindingStatus.OPEN), "n", "a", T, null);

        Finding reopened = Disposition.reopen(accepted, "fix it after all", "flo@box", T.plusSeconds(60));

        assertThat(reopened.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(reopened.disposition()).isNull();
        assertThat(reopened.resolvedAt()).isNull();
        assertThat(Disposition.reopen(with(FindingStatus.RESOLVED), null, "a", T).status()).isEqualTo(FindingStatus.OPEN);
        assertThat(Disposition.reopen(with(FindingStatus.DISMISSED), null, "a", T).status()).isEqualTo(FindingStatus.OPEN);
    }

    @Test
    void aNoteIsRequired_forAcceptAndDismiss() {
        for (String note : new String[] {null, " "}) {
            assertThatThrownBy(() -> Disposition.accept(with(FindingStatus.OPEN), note, "a", T, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("note");
            assertThatThrownBy(() -> Disposition.dismiss(with(FindingStatus.OPEN), note, "a", T))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("note");
        }
    }

    @Test
    void theTransitions_areChecked() {
        for (FindingStatus from : List.of(FindingStatus.RESOLVED, FindingStatus.ACCEPTED_RISK, FindingStatus.DISMISSED)) {
            assertThatThrownBy(() -> Disposition.accept(with(from), "n", "a", T, null)).as("accept from " + from)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(from.name())
                    .hasMessageContaining("ACCEPTED_RISK");
            assertThatThrownBy(() -> Disposition.dismiss(with(from), "n", "a", T)).as("dismiss from " + from)
                    .isInstanceOf(IllegalStateException.class);
        }
        for (FindingStatus from : List.of(FindingStatus.OPEN, FindingStatus.AWAITING_VERIFICATION)) {
            assertThatThrownBy(() -> Disposition.reopen(with(from), null, "a", T)).as("reopen from " + from)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(from.name());
        }
    }
}
