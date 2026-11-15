package traceworks.core.register;

import traceworks.core.register.Change.Kind;
import traceworks.core.register.Reconciler.Applied;
import traceworks.core.register.Reconciler.Report;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A report applied to a register state: the reconciliation as pure logic, without a normalisation of subjects and
 * without an inheritance of a disposition across rules.
 */
class ReconcilerTest {

    private static final Instant T1 = Instant.parse("2026-11-11T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-13T10:00:00Z");

    private final RegisterState empty = RegisterState.empty("arch", "wf-1", "architecture");

    private static ReportedFinding finding(String rule, String subject) {
        return new ReportedFinding(rule, subject, Severity.MEDIUM, "Title of " + subject, "desc", "evidence 1",
                List.of("t"));
    }

    private static Report report(String runId, Instant at, ReportedFinding... findings) {
        return new Report(runId, at, "blob-" + runId, List.of(findings), Set.of());
    }

    private static Finding one(RegisterState state, String rule, String subject) {
        String id = FindingIdentity.of(state.key(), rule, subject);
        return state.findings().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aNewFinding_isOpen_withFirstAndLastSeen() {
        Applied applied = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(f.firstSeen()).isEqualTo(new Finding.Seen("run-1", T1));
        assertThat(f.lastSeen()).isEqualTo(new Finding.Seen("run-1", T1));
        assertThat(f.occurrences()).isEqualTo(1);
        assertThat(f.regressions()).isZero();
        assertThat(f.title()).isEqualTo("Title of A.java");
        assertThat(f.disposition()).isNull();
        assertThat(applied.changes()).containsExactly(new Change(Kind.NEW, f.id(), null));
        assertThat(applied.state().lastReport()).isEqualTo(new RegisterState.LastReport("run-1", T1, "blob-run-1", true));
        assertThat(applied.state().validReports()).isEqualTo(1);
    }

    @Test
    void aSeenFinding_updatesTextAndCounts_andKeepsFirstSeen() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), null).state();
        ReportedFinding changed = new ReportedFinding("split", "A.java", Severity.HIGH, "New title", "d2", "evidence 2",
                List.of("x"));

        Applied applied = Reconciler.apply(after1, report("run-2", T2, changed), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(f.severity()).isEqualTo(Severity.HIGH);
        assertThat(f.title()).isEqualTo("New title");
        assertThat(f.evidence()).isEqualTo("evidence 2");
        assertThat(f.tags()).containsExactly("x");
        assertThat(f.firstSeen()).isEqualTo(new Finding.Seen("run-1", T1));
        assertThat(f.lastSeen()).isEqualTo(new Finding.Seen("run-2", T2));
        assertThat(f.occurrences()).isEqualTo(2);
        assertThat(applied.changes()).containsExactly(new Change(Kind.SEEN, f.id(), null));
    }

    @Test
    void anAbsentOpenFinding_isResolved_withResolvedAt() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java"),
                finding("split", "B.java")), null).state();

        Applied applied = Reconciler.apply(after1, report("run-2", T2, finding("split", "B.java")), null);

        Finding a = one(applied.state(), "split", "A.java");
        assertThat(a.status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(a.resolvedAt()).isEqualTo(T2);
        assertThat(a.lastSeen()).isEqualTo(new Finding.Seen("run-1", T1));
        assertThat(applied.changes()).containsExactlyInAnyOrder(new Change(Kind.RESOLVED, a.id(), "absent"),
                new Change(Kind.SEEN, one(applied.state(), "split", "B.java").id(), null));
    }

    @Test
    void anAbsentAwaitingVerification_isResolved() {
        RegisterState state = withStatus(FindingStatus.AWAITING_VERIFICATION);

        Applied applied = Reconciler.apply(state, report("run-2", T2), null);

        assertThat(one(applied.state(), "split", "A.java").status()).isEqualTo(FindingStatus.RESOLVED);
    }

    @Test
    void aResolvedFindingSeenAgain_isReopened_withARegression() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), null).state();
        RegisterState after2 = Reconciler.apply(after1, report("run-2", T2), null).state();

        Applied applied = Reconciler.apply(after2, report("run-3", T3, finding("split", "A.java")), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(f.regressions()).isEqualTo(1);
        assertThat(f.resolvedAt()).isNull();
        assertThat(f.occurrences()).isEqualTo(2);
        assertThat(applied.changes()).containsExactly(new Change(Kind.REOPENED, f.id(), "regression"));
    }

    @Test
    void anAwaitingVerificationSeenAgain_isOpen_withoutARegression() {
        Applied applied = Reconciler.apply(withStatus(FindingStatus.AWAITING_VERIFICATION),
                report("run-2", T2, finding("split", "A.java")), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(f.regressions()).isZero();
        assertThat(applied.changes()).containsExactly(new Change(Kind.REOPENED, f.id(), "seen_again"));
    }

    @Test
    void anAcceptedRiskSeen_stays_andIsNotedAsUnchangedTriaged() {
        Applied applied = Reconciler.apply(withStatus(FindingStatus.ACCEPTED_RISK),
                report("run-2", T2, finding("split", "A.java")), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.ACCEPTED_RISK);
        assertThat(f.occurrences()).isEqualTo(2);
        assertThat(applied.changes()).containsExactly(new Change(Kind.UNCHANGED_TRIAGED, f.id(), null));
    }

    @Test
    void anAcceptedRiskAbsent_stays_andTheValidReportsAreCounted() {
        Applied applied = Reconciler.apply(withStatus(FindingStatus.ACCEPTED_RISK), report("run-2", T2), null);

        assertThat(one(applied.state(), "split", "A.java").status()).isEqualTo(FindingStatus.ACCEPTED_RISK);
        assertThat(applied.state().validReports()).isEqualTo(2);
        assertThat(applied.changes()).isEmpty();
    }

    @Test
    void aDismissedAbsent_stays() {
        Applied applied = Reconciler.apply(withStatus(FindingStatus.DISMISSED), report("run-2", T2), null);

        assertThat(one(applied.state(), "split", "A.java").status()).isEqualTo(FindingStatus.DISMISSED);
    }

    @Test
    void aDuplicateIdentityInOneReport_firstWins_andIsReported() {
        ReportedFinding first = finding("split", "A.java");
        ReportedFinding second = new ReportedFinding("split", "A.java", Severity.LOW, "Second", null, null, List.of());

        Applied applied = Reconciler.apply(empty, report("run-1", T1, first, second), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.title()).isEqualTo("Title of A.java");
        assertThat(f.occurrences()).isEqualTo(1);
        assertThat(applied.changes()).containsExactly(new Change(Kind.NEW, f.id(), null),
                new Change(Kind.DUPLICATE_IN_REPORT, f.id(), "findings[1]"));
    }

    @Test
    void theCatalogsFixedSubject_replacesTheReported() {
        Catalog catalog = Catalog.parse("{\"format\":1,\"rules\":{\"size.total\":{\"subject\":\"pom.xml\"}}}"
                .getBytes());

        Applied applied = Reconciler.apply(empty, report("run-1", T1, finding("size.total", "drifted")), catalog);

        Finding f = one(applied.state(), "size.total", "pom.xml");
        assertThat(f.subject()).isEqualTo("pom.xml");
        assertThat(applied.state().findings()).hasSize(1);
    }

    /** {@code audit_findings} shows {@code |} masked as {@code ¦}; a model echoes the mask. */
    @Test
    void aMaskedPipe_isUnmasked_inEveryTextField() {
        Applied applied = Reconciler.apply(empty, report("run-1", T1,
                new ReportedFinding("a¦b", "s¦t", Severity.LOW, "ti¦tle", "de¦sc", "ev¦id", List.of("x¦y"))), null);

        Finding f = applied.state().findings().getFirst();
        assertThat(f.ruleKey()).isEqualTo("a|b");
        assertThat(f.subject()).isEqualTo("s|t");
        assertThat(f.title()).isEqualTo("ti|tle");
        assertThat(f.description()).isEqualTo("de|sc");
        assertThat(f.evidence()).isEqualTo("ev|id");
        assertThat(f.tags()).containsExactly("x|y");
        assertThat(f.id()).isEqualTo(FindingIdentity.of("arch", "a|b", "s|t"));
    }

    @Test
    void findings_areSortedById_andTheInputStaysUnchanged() {
        Applied applied = Reconciler.apply(empty, report("run-1", T1, finding("z", "z"), finding("a", "a"),
                finding("m", "m")), null);

        assertThat(applied.state().findings()).extracting(Finding::id).isSorted();
        assertThat(empty.findings()).isEmpty();
        assertThat(empty.validReports()).isZero();
    }

    @Test
    void evidenceThatNamesNoFactOfTheRun_isMarkedUnverified_andRecomputedEachRun() {
        Report withFacts = new Report("run-1", T1, "r1", List.of(finding("split", "A.java")),
                Set.of("ncloc_total"));
        Applied first = Reconciler.apply(empty, withFacts, null);
        assertThat(one(first.state(), "split", "A.java").unverifiedEvidence()).as("evidence 1 names no fact").isTrue();

        ReportedFinding supported = new ReportedFinding("split", "A.java", Severity.MEDIUM, "t", null,
                "FACT ncloc_total: 9", List.of());
        Applied second = Reconciler.apply(first.state(), new Report("run-2", T2, "r2", List.of(supported),
                Set.of("ncloc_total")), null);
        assertThat(one(second.state(), "split", "A.java").unverifiedEvidence()).isFalse();
        assertThat(one(Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), null).state(),
                "split", "A.java").unverifiedEvidence()).as("no fact sheet: nothing to check").isFalse();
    }

    @Test
    void anAcceptedRiskSeenWorse_isReopened_withTheReason() {
        RegisterState accepted = withDisposition("320 LOC", null);
        ReportedFinding worse = new ReportedFinding("split", "A.java", Severity.MEDIUM, "t", null, "400 LOC", List.of());

        Applied applied = Reconciler.apply(accepted, report("run-2", T2, worse), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(applied.changes()).containsExactly(new Change(Kind.REOPENED, f.id(), "worse: 320 LOC → 400 LOC"));
    }

    /** An accepted risk reported again after its review date is awaiting verification, as an absent one is. */
    @Test
    void anAcceptedRiskSeenPastItsReviewDate_isAwaitingVerification() {
        RegisterState accepted = withDisposition("320 LOC", T1.plusSeconds(3600));
        ReportedFinding same = new ReportedFinding("split", "A.java", Severity.MEDIUM, "t", null, "320 LOC", List.of());

        Applied applied = Reconciler.apply(accepted, report("run-2", T2, same), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.AWAITING_VERIFICATION);
        assertThat(applied.changes()).containsExactly(new Change(Kind.REVIEW_DUE, f.id(), null));
    }

    /** Decided 2026-11-11: the review date is checked on every applied report, seen or not. */
    @Test
    void anAcceptedRiskPastItsReviewDate_isAwaitingVerification_withoutASighting() {
        RegisterState accepted = withDisposition("320 LOC", T1.plusSeconds(3600));

        Applied applied = Reconciler.apply(accepted, report("run-2", T2), null);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.AWAITING_VERIFICATION);
        assertThat(applied.changes()).containsExactly(new Change(Kind.REVIEW_DUE, f.id(), null));
    }

    /** An accepted or dismissed finding nobody reports any more is retired after three valid reports without it. */
    @Test
    void anAbsentTriagedFinding_isResolvedAfterThreeValidReports() {
        RegisterState state = withDisposition("320 LOC", null);
        Applied one = Reconciler.apply(state, report("run-2", T2), null);
        Applied two = Reconciler.apply(one.state(), report("run-3", T3), null);
        assertThat(one(two.state(), "split", "A.java").status()).isEqualTo(FindingStatus.ACCEPTED_RISK);

        Applied three = Reconciler.apply(two.state(), report("run-4", T3.plusSeconds(60)), null);

        Finding f = one(three.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(f.resolvedAt()).isEqualTo(T3.plusSeconds(60));
        assertThat(three.changes()).containsExactly(new Change(Kind.RESOLVED, f.id(), "retired"));
    }

    @Test
    void aSightingResetsTheRetirementCount() {
        RegisterState state = withDisposition("320 LOC", null);
        RegisterState s1 = Reconciler.apply(state, report("run-2", T2), null).state();
        RegisterState s2 = Reconciler.apply(s1, report("run-3", T3), null).state();
        RegisterState s3 = Reconciler.apply(s2, report("run-4", T3.plusSeconds(1), finding("split", "A.java")), null).state();
        RegisterState s4 = Reconciler.apply(s3, report("run-5", T3.plusSeconds(2)), null).state();
        RegisterState s5 = Reconciler.apply(s4, report("run-6", T3.plusSeconds(3)), null).state();

        assertThat(one(s5, "split", "A.java").status()).isEqualTo(FindingStatus.ACCEPTED_RISK);
    }

    @Test
    void aRuleThatLeftTheCatalog_resolvesItsTriagedFindings() {
        RegisterState state = withDisposition("320 LOC", null);
        Catalog without = Catalog.parse("{\"format\":1,\"rules\":{\"other\":{}}}".getBytes());

        Applied applied = Reconciler.apply(state, report("run-2", T2), without);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(applied.changes()).containsExactly(new Change(Kind.RESOLVED, f.id(), "rule_left_catalog"));
    }

    // ---- a judged rule: a finding of a model is not resolved by silence ----

    /** "split" is a judgement of a model, "size" a measurement. */
    private static final Catalog JUDGED = Catalog.parse(
            "{\"format\":1,\"rules\":{\"split\":{\"judged\":true},\"size\":{}}}".getBytes());

    private static Report reportWithGone(String runId, Instant at, List<GoneFinding> gone, ReportedFinding... findings) {
        return new Report(runId, at, "blob-" + runId, List.of(findings), Set.of(), gone);
    }

    /** The model did not name it again: that says nothing. The finding stays open, and the register counts it. */
    @Test
    void anOpenFindingOfAJudgedRule_thatTheReportDoesNotMention_staysOpen_andIsCounted() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java"),
                finding("size", "A.java")), JUDGED).state();

        Applied second = Reconciler.apply(after1, report("run-2", T2), JUDGED);
        Applied third = Reconciler.apply(second.state(), report("run-3", T3), JUDGED);

        Finding judged = one(second.state(), "split", "A.java");
        assertThat(judged.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(judged.resolvedAt()).isNull();
        assertThat(judged.absentReports()).isEqualTo(1);
        assertThat(judged.lastSeen()).as("the run that last stated it").isEqualTo(new Finding.Seen("run-1", T1));
        assertThat(judged.occurrences()).isEqualTo(1);
        assertThat(one(second.state(), "size", "A.java").status()).as("a measured finding resolves at once")
                .isEqualTo(FindingStatus.RESOLVED);
        assertThat(second.changes()).containsExactlyInAnyOrder(new Change(Kind.NOT_MENTIONED, judged.id(), null),
                new Change(Kind.RESOLVED, one(second.state(), "size", "A.java").id(), "absent"));
        assertThat(one(third.state(), "split", "A.java").absentReports()).isEqualTo(2);
        assertThat(one(third.state(), "split", "A.java").status()).isEqualTo(FindingStatus.OPEN);
    }

    /** The report says explicitly that the run checked it and it is gone: only then is it resolved. */
    @Test
    void anOpenFindingOfAJudgedRule_thatTheReportNamesAsGone_isResolved() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java"),
                finding("split", "B.java")), JUDGED).state();

        Applied applied = Reconciler.apply(after1, reportWithGone("run-2", T2,
                List.of(new GoneFinding("split", "A.java"))), JUDGED);

        Finding gone = one(applied.state(), "split", "A.java");
        assertThat(gone.status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(gone.resolvedAt()).isEqualTo(T2);
        assertThat(one(applied.state(), "split", "B.java").status()).isEqualTo(FindingStatus.OPEN);
        assertThat(applied.changes()).containsExactlyInAnyOrder(new Change(Kind.RESOLVED, gone.id(), "gone"),
                new Change(Kind.NOT_MENTIONED, one(applied.state(), "split", "B.java").id(), null));
    }

    @Test
    void aJudgedFindingStatedAgain_isSeen_andItsCountOfSilentReportsStartsAgain() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), JUDGED).state();
        RegisterState after2 = Reconciler.apply(after1, report("run-2", T2), JUDGED).state();

        Applied applied = Reconciler.apply(after2, report("run-3", T3, finding("split", "A.java")), JUDGED);

        Finding f = one(applied.state(), "split", "A.java");
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(f.absentReports()).isZero();
        assertThat(f.occurrences()).isEqualTo(2);
        assertThat(f.lastSeen()).isEqualTo(new Finding.Seen("run-3", T3));
        assertThat(applied.changes()).containsExactly(new Change(Kind.SEEN, f.id(), null));
    }

    /** A report that states a finding and names it as gone contradicts itself: what it states counts. */
    @Test
    void aFindingBothStatedAndNamedAsGone_isSeen() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), JUDGED).state();

        Applied applied = Reconciler.apply(after1, reportWithGone("run-2", T2,
                List.of(new GoneFinding("split", "A.java")), finding("split", "A.java")), JUDGED);

        assertThat(one(applied.state(), "split", "A.java").status()).isEqualTo(FindingStatus.OPEN);
        assertThat(applied.changes()).extracting(Change::kind).containsExactly(Kind.SEEN);
    }

    /** A fix that a person announced is not verified by silence either: the run has to say it is gone. */
    @Test
    void anAwaitingVerificationOfAJudgedRule_staysUntilTheReportStatesItOrNamesItAsGone() {
        RegisterState awaiting = withStatus(FindingStatus.AWAITING_VERIFICATION);

        Applied silent = Reconciler.apply(awaiting, report("run-2", T2), JUDGED);
        Applied gone = Reconciler.apply(awaiting, reportWithGone("run-2", T2,
                List.of(new GoneFinding("split", "A.java"))), JUDGED);

        assertThat(one(silent.state(), "split", "A.java").status()).isEqualTo(FindingStatus.AWAITING_VERIFICATION);
        assertThat(silent.changes()).extracting(Change::kind).containsExactly(Kind.NOT_MENTIONED);
        assertThat(one(gone.state(), "split", "A.java").status()).isEqualTo(FindingStatus.RESOLVED);
    }

    /**
     * An accepted or dismissed finding of a judged rule is decided: the run is not asked about it and does not report
     * it, so silence does not retire it. It stays until its review date, or until a run names it as gone.
     */
    @Test
    void aTriagedFindingOfAJudgedRule_isNotRetiredBySilence_butComesUpForReview_andResolvesWhenGone() {
        RegisterState state = withDisposition("320 LOC", T3.plusSeconds(3600));
        RegisterState s = state;
        for (int i = 2; i <= 5; i++) {
            s = Reconciler.apply(s, report("run-" + i, T2.plusSeconds(i)), JUDGED).state();
        }
        assertThat(one(s, "split", "A.java").status()).as("four silent reports").isEqualTo(FindingStatus.ACCEPTED_RISK);

        Applied due = Reconciler.apply(s, report("run-6", T3.plusSeconds(7200)), JUDGED);
        Applied gone = Reconciler.apply(s, reportWithGone("run-6", T3, List.of(new GoneFinding("split", "A.java"))),
                JUDGED);

        assertThat(one(due.state(), "split", "A.java").status()).isEqualTo(FindingStatus.AWAITING_VERIFICATION);
        assertThat(due.changes()).extracting(Change::kind).containsExactly(Kind.REVIEW_DUE);
        assertThat(one(gone.state(), "split", "A.java").status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(gone.changes()).containsExactly(new Change(Kind.RESOLVED, one(gone.state(), "split", "A.java").id(),
                "gone"));
    }

    /** Without a catalog, or with one that does not mark the rule, every rule is measured: silence resolves. */
    @Test
    void withoutACatalog_orWithARuleNotMarked_silenceResolves_andAGoneEntryChangesNothing() {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), null).state();
        Catalog measured = Catalog.parse("{\"format\":1,\"rules\":{\"split\":{}}}".getBytes());

        assertThat(one(Reconciler.apply(after1, report("run-2", T2), null).state(), "split", "A.java").status())
                .isEqualTo(FindingStatus.RESOLVED);
        Applied applied = Reconciler.apply(after1, reportWithGone("run-2", T2,
                List.of(new GoneFinding("split", "A.java"), new GoneFinding("split", "never-known"))), measured);
        assertThat(applied.changes()).containsExactly(new Change(Kind.RESOLVED,
                one(applied.state(), "split", "A.java").id(), "absent"));
        assertThat(applied.state().findings()).hasSize(1);
    }

    /** A gone entry is read as a finding is: the catalog's fixed subject applies, and a masked pipe is unmasked. */
    @Test
    void aGoneEntry_isMatchedLikeAFinding_withTheFixedSubjectAndTheUnmaskedPipe() {
        Catalog catalog = Catalog.parse(("{\"format\":1,\"rules\":{\"split\":{\"judged\":true},"
                + "\"metric\":{\"judged\":true,\"subject\":\"whole\"}}}").getBytes());
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "a|b"),
                finding("metric", "x")), catalog).state();

        Applied applied = Reconciler.apply(after1, reportWithGone("run-2", T2,
                List.of(new GoneFinding("split", "a¦b"), new GoneFinding("metric", "anything"))), catalog);

        assertThat(applied.state().findings()).extracting(Finding::status)
                .containsOnly(FindingStatus.RESOLVED);
    }

    private RegisterState withDisposition(String frozenEvidence, Instant until) {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), null).state();
        Finding f = after1.findings().getFirst();
        Finding changed = new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(), f.description(),
                frozenEvidence, f.tags(), FindingStatus.ACCEPTED_RISK, f.firstSeen(), f.lastSeen(), f.occurrences(),
                f.regressions(), f.unverifiedEvidence(), null, new Finding.DispositionRecord(
                        FindingStatus.ACCEPTED_RISK, "note", "flo", T1, until, frozenEvidence));
        return after1.with(List.of(changed));
    }

    private RegisterState withStatus(FindingStatus status) {
        RegisterState after1 = Reconciler.apply(empty, report("run-1", T1, finding("split", "A.java")), null).state();
        Finding f = after1.findings().getFirst();
        Finding changed = new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(), f.description(),
                f.evidence(), f.tags(), status, f.firstSeen(), f.lastSeen(), f.occurrences(), f.regressions(),
                f.unverifiedEvidence(), f.resolvedAt(), f.disposition());
        return after1.with(List.of(changed));
    }

    // ---- items (docs/registers.md, "Items")

    private static Report withItems(String runId, Instant at, boolean complete, List<ReportedItem> items,
                                    ReportedFinding... findings) {
        return new Report(runId, at, "blob-" + runId, List.of(findings), Set.of(), List.of(), items, complete);
    }

    private static ReportedFinding findingOf(String item, String subject) {
        return new ReportedFinding("split", subject, Severity.MEDIUM, "Title", "desc", "evidence 1", List.of(), item);
    }

    @Test
    void aJudgedItem_getsTheVerdictOfTheRun() {
        RegisterState state = Reconciler.apply(empty, withItems("run-1", T1, true,
                List.of(new ReportedItem("p.B", "bb", true), new ReportedItem("p.A", "aa", false))), null).state();

        assertThat(state.items()).containsExactly(new Item("p.A", "aa", null, null),
                new Item("p.B", "bb", "bb", new Finding.Seen("run-1", T1)));
    }

    @Test
    void anItemNotJudged_keepsItsEarlierVerdict_andTakesTheNewDigest() {
        RegisterState first = Reconciler.apply(empty, withItems("run-1", T1, true,
                List.of(new ReportedItem("p.A", "aa", true))), null).state();

        RegisterState second = Reconciler.apply(first, withItems("run-2", T2, true,
                List.of(new ReportedItem("p.A", "changed", false))), null).state();

        assertThat(second.items()).containsExactly(new Item("p.A", "changed", "aa", new Finding.Seen("run-1", T1)));
    }

    @Test
    void aNameWrittenWithTheMaskOfThePipe_isTheSameItem_inTheListAndOnTheFinding() {
        RegisterState state = Reconciler.apply(empty, withItems("run-1", T1, true,
                List.of(new ReportedItem("p.A\u00a6B", "aa", true)), findingOf("p.A\u00a6B", "p.A#1")), null).state();

        assertThat(state.items()).extracting(Item::name).containsExactly("p.A|B");
        assertThat(one(state, "split", "p.A#1").item()).isEqualTo("p.A|B");
        assertThat(ItemState.of(state.items().get(0), state.findings())).isEqualTo(ItemState.OPEN);
    }

    @Test
    void anItemTheCompleteListNoLongerHolds_leaves_andItsFindingsStay() {
        Catalog judged = Catalog.parse("{\"format\":1,\"rules\":{\"split\":{\"judged\":true}}}".getBytes());
        RegisterState first = Reconciler.apply(empty, withItems("run-1", T1, true,
                List.of(new ReportedItem("p.A", "aa", true), new ReportedItem("p.B", "bb", true)),
                findingOf("p.A", "p.A#1")), judged).state();

        RegisterState second = Reconciler.apply(first, withItems("run-2", T2, true,
                List.of(new ReportedItem("p.B", "bb", false))), judged).state();

        assertThat(second.items()).extracting(Item::name).containsExactly("p.B");
        assertThat(one(second, "split", "p.A#1").status()).as("gone only when a run says so").isEqualTo(FindingStatus.OPEN);
        assertThat(one(second, "split", "p.A#1").item()).isEqualTo("p.A");
    }

    @Test
    void aListWithoutExpectedItems_removesNothing() {
        RegisterState first = Reconciler.apply(empty, withItems("run-1", T1, true,
                List.of(new ReportedItem("p.A", "aa", true), new ReportedItem("p.B", "bb", true))), null).state();

        RegisterState second = Reconciler.apply(first, withItems("run-2", T2, false,
                List.of(new ReportedItem("p.B", "b2", true), new ReportedItem("p.C", "cc", false))), null).state();

        assertThat(second.items()).containsExactly(new Item("p.A", "aa", "aa", new Finding.Seen("run-1", T1)),
                new Item("p.B", "b2", "b2", new Finding.Seen("run-2", T2)), new Item("p.C", "cc", null, null));
    }

    @Test
    void aReportWithoutAList_leavesTheItemsAsTheyAre() {
        RegisterState first = Reconciler.apply(empty, withItems("run-1", T1, true,
                List.of(new ReportedItem("p.A", "aa", true))), null).state();

        RegisterState second = Reconciler.apply(first, report("run-2", T2), null).state();

        assertThat(second.items()).isEqualTo(first.items());
    }

    @Test
    void aFinding_takesTheItemOfTheReport_andKeepsItWhenAReportStatesItWithoutOne() {
        RegisterState first = Reconciler.apply(empty, report("run-1", T1, findingOf("p.A", "p.A#1")), null).state();
        assertThat(one(first, "split", "p.A#1").item()).isEqualTo("p.A");

        RegisterState second = Reconciler.apply(first, report("run-2", T2, findingOf(null, "p.A#1")), null).state();
        assertThat(one(second, "split", "p.A#1").item()).isEqualTo("p.A");

        RegisterState third = Reconciler.apply(second, report("run-3", T3, findingOf("p.Moved", "p.A#1")), null).state();
        assertThat(one(third, "split", "p.A#1").item()).isEqualTo("p.Moved");
    }

    @Test
    void aFindingAbsentFromAReport_keepsItsItem() {
        Catalog judged = Catalog.parse("{\"format\":1,\"rules\":{\"split\":{\"judged\":true}}}".getBytes());
        RegisterState first = Reconciler.apply(empty, report("run-1", T1, findingOf("p.A", "p.A#1"),
                findingOf("p.B", "p.B#1")), judged).state();

        // Not mentioned (judged rule), then named as gone; and a measured one resolved by its absence
        RegisterState silent = Reconciler.apply(first, report("run-2", T2), judged).state();
        assertThat(one(silent, "split", "p.A#1").item()).isEqualTo("p.A");
        RegisterState gone = Reconciler.apply(silent, new Report("run-3", T3, "blob", List.of(), Set.of(),
                List.of(new GoneFinding("split", "p.A#1"))), judged).state();
        assertThat(one(gone, "split", "p.A#1").status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(one(gone, "split", "p.A#1").item()).isEqualTo("p.A");
        RegisterState measured = Reconciler.apply(first, report("run-2", T2), null).state();
        assertThat(one(measured, "split", "p.B#1").status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(one(measured, "split", "p.B#1").item()).isEqualTo("p.B");
    }

    @Test
    void aDecidedFindingAbsentFromAReport_keepsItsItem() {
        RegisterState first = Reconciler.apply(empty, report("run-1", T1, findingOf("p.A", "p.A#1")), null).state();
        Finding dismissed = Disposition.dismiss(one(first, "split", "p.A#1"), "no concern", "flo", T1);
        assertThat(dismissed.item()).isEqualTo("p.A");

        RegisterState second = Reconciler.apply(first.with(List.of(dismissed)), report("run-2", T2), null).state();

        assertThat(one(second, "split", "p.A#1").item()).isEqualTo("p.A");
        assertThat(second.with(List.of()).items()).as("with(findings) keeps the items").isEqualTo(second.items());
    }
}

