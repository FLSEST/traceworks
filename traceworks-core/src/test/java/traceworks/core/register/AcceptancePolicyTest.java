package traceworks.core.register;

import traceworks.core.register.AcceptancePolicy.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of an accepted risk: it reopens when its evidence got worse, and comes up for review after 180 days. */
class AcceptancePolicyTest {

    private static final Instant ACCEPTED = Instant.parse("2026-01-01T00:00:00Z");

    private static Finding accepted(String frozenEvidence, Instant until) {
        return new Finding("a".repeat(64), "r", "s", Severity.HIGH, "t", null, frozenEvidence, List.of(),
                FindingStatus.ACCEPTED_RISK, new Finding.Seen("run-1", ACCEPTED), new Finding.Seen("run-1", ACCEPTED),
                1, 0, false, null, new Finding.DispositionRecord(FindingStatus.ACCEPTED_RISK, "note", "alex", ACCEPTED,
                until, frozenEvidence));
    }

    private static Instant soon() {
        return ACCEPTED.plus(Duration.ofDays(10));
    }

    @Test
    void aDegradationAboveTenPercent_reopens_andNamesIt() {
        AcceptancePolicy.Assessment a = AcceptancePolicy.reassess(accepted("320 LOC", null), "360 LOC", soon());

        assertThat(a.outcome()).isEqualTo(Outcome.REOPENED);
        assertThat(a.detail()).isEqualTo("worse: 320 LOC → 360 LOC");
    }

    @Test
    void aDegradationAtOrBelowTenPercent_staysAccepted() {
        assertThat(AcceptancePolicy.reassess(accepted("320 LOC", null), "352 LOC", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(AcceptancePolicy.reassess(accepted("320 LOC", null), "300 LOC", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void withoutComparableNumbers_staysAccepted() {
        assertThat(AcceptancePolicy.reassess(accepted("320 LOC", null), "12 deps", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(AcceptancePolicy.reassess(accepted(null, null), "900 LOC", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void aZeroThatBecomesPositive_isADegradation() {
        assertThat(AcceptancePolicy.reassess(accepted("0 errors", null), "1 errors", soon()).outcome())
                .isEqualTo(Outcome.REOPENED);
    }

    @Test
    void aZeroThatStaysZero_staysAccepted() {
        assertThat(AcceptancePolicy.reassess(accepted("0 errors", null), "0 errors", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    /** The hotspot threshold is the first churn that counts: reaching it reopens, being at it already does not. */
    @Test
    void churnThatJustReachesTheThreshold_reopens_andChurnThatWasAtItAlready_doesNot() {
        assertThat(AcceptancePolicy.reassess(accepted("4 changes", null), "5 changes (hotspot from 5)", soon()))
                .satisfies(a -> {
                    assertThat(a.outcome()).isEqualTo(Outcome.REOPENED);
                    assertThat(a.detail()).isEqualTo("worse: 4 changes → 5 changes (hotspot from 5)");
                });
        assertThat(AcceptancePolicy.reassess(accepted("5 changes", null), "6 changes (hotspot from 5)", soon())
                .outcome()).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void churnReachingTheHotspotThreshold_reopens_butNotWhenAlreadyThere() {
        assertThat(AcceptancePolicy.reassess(accepted("3 changes", null), "6 changes (hotspot from 5)", soon()))
                .satisfies(a -> {
                    assertThat(a.outcome()).isEqualTo(Outcome.REOPENED);
                    assertThat(a.detail()).isEqualTo("worse: 3 changes → 6 changes (hotspot from 5)");
                });
        assertThat(AcceptancePolicy.reassess(accepted("7 changes", null), "40 changes", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(AcceptancePolicy.reassess(accepted("no churn stated", null), "40 changes", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void aNewAdvisoryId_reopens_andNamesIt_butVanishedOrReorderedOnesDoNot() {
        assertThat(AcceptancePolicy.reassess(accepted("CVE-2026-1111", null), "CVE-2026-1111, GHSA-j9f9-w8pj-32f8",
                soon())).satisfies(a -> {
                    assertThat(a.outcome()).isEqualTo(Outcome.REOPENED);
                    assertThat(a.detail()).isEqualTo("worse: new advisory GHSA-j9f9-w8pj-32f8");
                });
        assertThat(AcceptancePolicy.reassess(accepted("CVE-2026-1111, CVE-2026-2222", null), "cve-2026-2222, CVE-2026-1111",
                soon()).outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(AcceptancePolicy.reassess(accepted("CVE-2026-1111", null), "nothing left", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void withoutASnapshot_advisoryIdsAreIgnored() {
        assertThat(AcceptancePolicy.reassess(accepted(null, null), "CVE-2026-1111", soon()).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void severalDegradations_areNamedTogether() {
        AcceptancePolicy.Assessment a = AcceptancePolicy.reassess(accepted("320 LOC, CVE-2026-1111", null),
                "400 LOC, CVE-2026-1111, CVE-2026-2222, CVE-2026-3333", soon());

        assertThat(a.detail()).isEqualTo("worse: 320 LOC → 400 LOC, new advisories CVE-2026-2222, CVE-2026-3333");
    }

    @Test
    void olderThanTheReviewInterval_isDueForReview_evenWithoutASighting() {
        Instant later = ACCEPTED.plus(AcceptancePolicy.REVIEW_INTERVAL);

        assertThat(AcceptancePolicy.reassess(accepted("320 LOC", null), null, later).outcome())
                .isEqualTo(Outcome.REVIEW_DUE);
        assertThat(AcceptancePolicy.reassess(accepted("320 LOC", null), null, later.minusSeconds(1)).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void anExplicitUntil_setsTheReviewDate() {
        Instant until = ACCEPTED.plus(Duration.ofDays(30));

        assertThat(AcceptancePolicy.reassess(accepted("x", until), null, until).outcome())
                .isEqualTo(Outcome.REVIEW_DUE);
        assertThat(AcceptancePolicy.reassess(accepted("x", until), null, until.minusSeconds(1)).outcome())
                .isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void aDegradationWinsOverTheReview() {
        Instant later = ACCEPTED.plus(AcceptancePolicy.REVIEW_INTERVAL);

        assertThat(AcceptancePolicy.reassess(accepted("320 LOC", null), "900 LOC", later).outcome())
                .isEqualTo(Outcome.REOPENED);
    }

    @Test
    void otherStatuses_areNotAssessed() {
        Finding open = accepted("320 LOC", null).withStatus(FindingStatus.OPEN);

        assertThat(AcceptancePolicy.reassess(open, "900 LOC", soon()).outcome()).isEqualTo(Outcome.UNCHANGED);
    }
}
