package traceworks.core.register;

import traceworks.core.port.Clock;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.ChainReport;
import traceworks.core.register.Change.Kind;
import traceworks.core.register.Reconciler.Applied;
import traceworks.core.register.Reconciler.Report;
import traceworks.core.testing.InMemoryRegisterStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A decision covers the finding it was made on: an accepted or dismissed finding that a report states with a severity
 * above the one decided on is open again, unless the operator keeps decisions; and a register that an earlier version
 * wrote folds to the bytes it recorded.
 */
class DecisionCoverageTest {

    private static final Instant T1 = Instant.parse("2026-11-11T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-13T10:00:00Z");
    private static final String FIXTURE = "/registers/decided-before-severity/";

    private final RegisterState empty = RegisterState.empty("arch", "wf-1", "architecture");

    private static ReportedFinding stated(Severity severity, String evidence) {
        return new ReportedFinding("size", "A.java", severity, "A is long", "d", evidence, List.of());
    }

    private static Report report(String runId, Instant at, ReportedFinding... findings) {
        return new Report(runId, at, "blob-" + runId, List.of(findings), Set.of());
    }

    /** A report whose text is a blob of the register, as a verifier wants it. */
    private static Report report(RegisterStore.Opened opened, String runId, Instant at, ReportedFinding... findings) {
        return new Report(runId, at, opened.blobs().put(("# " + runId).getBytes(StandardCharsets.UTF_8)).sha256(),
                List.of(findings), Set.of());
    }

    private static Finding only(RegisterState state) {
        assertThat(state.findings()).hasSize(1);
        return state.findings().getFirst();
    }

    /** Run 1 states the finding with {@code severity}, then a person dismisses it. */
    private RegisterState dismissedAt(Severity severity) {
        RegisterState first = Reconciler.apply(empty, report("run-1", T1, stated(severity, "E1")), null).state();
        return first.with(List.of(Disposition.dismiss(only(first), "false positive", "alex", T1)));
    }

    private RegisterState acceptedAt(Severity severity) {
        RegisterState first = Reconciler.apply(empty, report("run-1", T1, stated(severity, "E1")), null).state();
        return first.with(List.of(Disposition.accept(only(first), "known", "alex", T1, null)));
    }

    @Test
    void aDecision_keepsTheSeverityItWasMadeOn() {
        assertThat(only(dismissedAt(Severity.LOW)).disposition().severity()).isEqualTo(Severity.LOW);
        assertThat(only(acceptedAt(Severity.HIGH)).disposition().severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void aDismissedFindingStatedWithAHigherSeverity_isOpenAgain_andTheReasonNamesBothSeverities() {
        Applied applied = Reconciler.apply(dismissedAt(Severity.LOW), report("run-2", T2,
                stated(Severity.CRITICAL, "E2")), null);

        Finding f = only(applied.state());
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.evidence()).isEqualTo("E2");
        assertThat(f.regressions()).as("it was never resolved").isZero();
        assertThat(applied.changes()).containsExactly(new Change(Kind.REOPENED, f.id(),
                "higher_severity: LOW → CRITICAL"));
        // What was decided stays with the finding: the note, and the severity it was made on
        assertThat(f.disposition().note()).isEqualTo("false positive");
        assertThat(f.disposition().severity()).isEqualTo(Severity.LOW);
        assertThat(f.decidedOnOtherSeverity()).isEqualTo(Severity.LOW);
    }

    @Test
    void aDismissedFindingStatedWithTheSameOrALowerSeverity_staysDismissed_whateverItsEvidence() {
        for (Severity now : List.of(Severity.MEDIUM, Severity.LOW)) {
            Applied applied = Reconciler.apply(dismissedAt(Severity.MEDIUM), report("run-2", T2, stated(now, "E2")),
                    null);

            Finding f = only(applied.state());
            assertThat(f.status()).as("stated as " + now).isEqualTo(FindingStatus.DISMISSED);
            assertThat(f.evidence()).isEqualTo("E2");
            assertThat(applied.changes()).containsExactly(new Change(Kind.UNCHANGED_TRIAGED, f.id(), null));
            assertThat(f.disposition().severity()).isEqualTo(Severity.MEDIUM);
        }
    }

    /** The decision covers up to the severity it was made on, not up to the last one reported. */
    @Test
    void aSeverityThatFellAndRoseAgainToTheOneDecidedOn_staysDismissed() {
        RegisterState lower = Reconciler.apply(dismissedAt(Severity.HIGH), report("run-2", T2,
                stated(Severity.LOW, "E1")), null).state();
        assertThat(only(lower).decidedOnOtherSeverity()).isEqualTo(Severity.HIGH);

        Applied back = Reconciler.apply(lower, report("run-3", T3, stated(Severity.HIGH, "E1")), null);

        assertThat(only(back.state()).status()).isEqualTo(FindingStatus.DISMISSED);
        assertThat(only(back.state()).decidedOnOtherSeverity()).isNull();
    }

    @Test
    void aFindingDismissedAgainAtTheHigherSeverity_staysDismissedThere_andComesBackOnlyAboveIt() {
        RegisterState reopened = Reconciler.apply(dismissedAt(Severity.LOW), report("run-2", T2,
                stated(Severity.HIGH, "E2")), null).state();
        RegisterState again = reopened.with(List.of(Disposition.dismiss(only(reopened), "still not ours", "alex", T2)));
        assertThat(only(again).disposition().severity()).isEqualTo(Severity.HIGH);

        Applied same = Reconciler.apply(again, report("run-3", T3, stated(Severity.HIGH, "E3")), null);
        assertThat(only(same.state()).status()).isEqualTo(FindingStatus.DISMISSED);

        Applied above = Reconciler.apply(same.state(), report("run-4", T3, stated(Severity.CRITICAL, "E3")), null);
        assertThat(only(above.state()).status()).isEqualTo(FindingStatus.OPEN);
        assertThat(above.changes()).containsExactly(new Change(Kind.REOPENED, only(above.state()).id(),
                "higher_severity: HIGH → CRITICAL"));
    }

    @Test
    void anAcceptedRiskStatedWithAHigherSeverity_isOpenAgainToo_thoughItsEvidenceIsNoWorse() {
        Applied applied = Reconciler.apply(acceptedAt(Severity.MEDIUM), report("run-2", T2,
                stated(Severity.HIGH, "E1")), null);

        assertThat(only(applied.state()).status()).isEqualTo(FindingStatus.OPEN);
        assertThat(applied.changes()).containsExactly(new Change(Kind.REOPENED, only(applied.state()).id(),
                "higher_severity: MEDIUM → HIGH"));
    }

    @Test
    void withKeep_theDecisionStays_andTheFindingSaysWhichSeverityWasDecidedOn() {
        for (RegisterState decided : List.of(dismissedAt(Severity.LOW), acceptedAt(Severity.LOW))) {
            FindingStatus status = only(decided).status();

            Applied applied = Reconciler.apply(decided, report("run-2", T2, stated(Severity.CRITICAL, "E1")), null,
                    DecisionOnHigherSeverity.KEEP);

            Finding f = only(applied.state());
            assertThat(f.status()).isEqualTo(status);
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.decidedOnOtherSeverity()).isEqualTo(Severity.LOW);
            assertThat(applied.changes()).containsExactly(new Change(Kind.UNCHANGED_TRIAGED, f.id(), null));
        }
    }

    @Test
    void stateJson_namesTheSeverityDecidedOn_onlyWhereTheFindingHasAnotherOne() {
        RegisterState same = Reconciler.apply(dismissedAt(Severity.LOW), report("run-2", T2,
                stated(Severity.LOW, "E2")), null).state();
        RegisterState kept = Reconciler.apply(dismissedAt(Severity.LOW), report("run-2", T2,
                stated(Severity.CRITICAL, "E2")), null, DecisionOnHigherSeverity.KEEP).state();

        String sameJson = new String(StateJson.write(same), StandardCharsets.UTF_8);
        String keptJson = new String(StateJson.write(kept), StandardCharsets.UTF_8);

        assertThat(sameJson).as("no case, no key: the bytes are what they always were")
                .contains("\"disposition\":{\"actor\":\"alex\",\"at\":\"2026-11-11T10:00:00Z\",\"evidence\":null,"
                        + "\"kind\":\"DISMISSED\",\"note\":\"false positive\",\"until\":null}");
        assertThat(keptJson).contains("\"kind\":\"DISMISSED\",\"note\":\"false positive\",\"severity\":\"LOW\","
                + "\"until\":null}").contains("\"severity\":\"CRITICAL\",\"status\":\"DISMISSED\"");
        RegisterState read = StateJson.read(StateJson.write(kept));
        assertThat(only(read).decidedOnOtherSeverity()).isEqualTo(Severity.LOW);
        assertThat(StateJson.write(read)).isEqualTo(StateJson.write(kept));
        assertThat(StateJson.write(StateJson.read(StateJson.write(same)))).isEqualTo(StateJson.write(same));
    }

    @Test
    void auditFindings_sayWhichSeverityADecisionWasMadeOn_whenItIsNotTheOneShown() {
        RegisterState kept = Reconciler.apply(dismissedAt(Severity.LOW), report("run-2", T2,
                stated(Severity.CRITICAL, "E2")), null, DecisionOnHigherSeverity.KEEP).state();
        RegisterState reopened = Reconciler.apply(dismissedAt(Severity.LOW), report("run-2", T2,
                stated(Severity.CRITICAL, "E2")), null).state();

        assertThat(FindingsText.render(kept, null)).contains("DISMISSED | CRITICAL | size | A.java | A is long"
                + " | Evidence: E2 | Disposition: false positive | Decided on: LOW, not on CRITICAL");
        assertThat(FindingsText.render(dismissedAt(Severity.LOW), null))
                .contains("| Disposition: false positive\n").doesNotContain("| Decided on: LOW");
        assertThat(FindingsText.render(reopened, null)).as("open again: a finding like any other open one")
                .contains("OPEN | CRITICAL | size | A.java | A is long | Evidence: E2\n")
                .contains("OPEN 1").contains("DISMISSED 0");
    }

    // --- the log ---

    private static RegisterState decide(RegisterWriter writer, RegisterState state, boolean dismiss, Instant at) {
        Finding f = only(state);
        Finding after = dismiss ? Disposition.dismiss(f, "false positive", "alex", at)
                : Disposition.accept(f, "known", "alex", at, null);
        return writer.disposition(state.with(List.of(after)), after, after.status(), after.disposition().note(),
                "alex", at, after.disposition().until());
    }

    @Test
    void theLog_namesTheSetting_theSeverityDecidedOn_andWhyTheFindingIsOpenAgain_andReplaysToTheSameState() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        Clock clock = () -> T1;
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            RegisterState state = writer.applied(empty, report(opened, "run-1", T1, stated(Severity.LOW, "E1")),
                    null, "head-1").state();
            state = decide(writer, state, true, T1);
            state = writer.applied(state, report(opened, "run-2", T2, stated(Severity.CRITICAL, "E2")), null, "head-2")
                    .state();
            state = decide(writer, state, true, T2);
            state = writer.applied(state, report(opened, "run-3", T3, stated(Severity.CRITICAL, "E3")), null,
                    null, "head-3", DecisionOnHigherSeverity.KEEP).state();
            opened.writeState(StateJson.write(state));

            List<RegisterEvent> events = log.read();
            assertThat(events).extracting(RegisterEvent::type).containsExactly("register.created",
                    "register.requires", "report.applied", "finding.new", "finding.dismissed", "report.applied",
                    "finding.reopened", "finding.dismissed", "report.applied");
            assertThat(events.get(1).text("feature")).isEqualTo("decision_severity");
            assertThat(events.get(2).text("decision_on_higher_severity")).isEqualTo("reopen");
            assertThat(events.get(8).text("decision_on_higher_severity")).isEqualTo("keep");
            assertThat(events.get(4).text("severity")).isEqualTo("LOW");
            assertThat(events.get(4).text("evidence")).isEqualTo("E1");
            assertThat(events.get(5).fields().path("counts").path("reopened").asInt()).isEqualTo(1);
            assertThat(events.get(6).text("reason")).isEqualTo("higher_severity: LOW → CRITICAL");
            assertThat(events.get(6).text("decided_severity")).isEqualTo("LOW");
            assertThat(events.get(6).text("severity")).isEqualTo("CRITICAL");
            assertThat(events.get(7).text("severity")).isEqualTo("CRITICAL");

            RegisterState replayed = RegisterReplay.replay("arch", events, opened.blobs());
            assertThat(replayed).isEqualTo(state);
            assertThat(only(replayed).status()).isEqualTo(FindingStatus.DISMISSED);
            ChainReport report = RegisterVerifier.verify("arch", opened.events().readAll(), opened.blobs(),
                    opened.state());
            assertThat(report.intact()).as(report.problems().toString()).isTrue();
        }
    }

    /** An acceptance names its review date and the evidence it froze, and its line replays to the state it recorded. */
    @Test
    void anAcceptanceInTheLog_namesItsReviewDateAndItsEvidence_andReplaysToTheSameState() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T1);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            RegisterState state = writer.applied(empty, report(opened, "run-1", T1, stated(Severity.HIGH, "320 LOC")),
                    null, "head-1").state();
            Finding after = Disposition.accept(only(state), "known", "alex", T2, T3);
            state = writer.disposition(state.with(List.of(after)), after, FindingStatus.ACCEPTED_RISK, "known", "alex",
                    T2, T3);
            opened.writeState(StateJson.write(state));

            List<RegisterEvent> events = log.read();
            RegisterEvent accepted = events.getLast();
            assertThat(accepted.type()).isEqualTo("finding.accepted");
            assertThat(accepted.text("until")).isEqualTo(T3.toString());
            assertThat(accepted.text("evidence")).isEqualTo("320 LOC");
            assertThat(accepted.text("severity")).isEqualTo("HIGH");
            assertThat(only(state).disposition().until()).isEqualTo(T3);
            assertThat(RegisterReplay.replay("arch", events, opened.blobs())).isEqualTo(state);
            ChainReport report = RegisterVerifier.verify("arch", opened.events().readAll(), opened.blobs(),
                    opened.state());
            assertThat(report.intact()).as(report.problems().toString()).isTrue();
        }
    }

    @Test
    void aReportIsNotApplied_withTheRuleOfAnEarlierVersion() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterWriter writer = new RegisterWriter(new RegisterLog(opened, () -> T1), opened.blobs());

            assertThatThrownBy(() -> writer.applied(empty, report("run-1", T1), null, null, "head-1",
                    DecisionOnHigherSeverity.UNCHECKED)).isInstanceOf(IllegalArgumentException.class);
            assertThat(opened.events().readAll()).isEmpty();
        }
    }

    // --- a register an earlier version wrote: LOW dismissed and MEDIUM accepted, then stated as CRITICAL and HIGH ---

    private static byte[] resource(String name) {
        try (InputStream in = DecisionCoverageTest.class.getResourceAsStream(FIXTURE + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The fixture in a store: the lines and the blobs as the version before this rule wrote them. */
    private static InMemoryRegisterStore earlierRegister() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        String text = new String(resource("events.jsonl"), StandardCharsets.UTF_8);
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            for (String line : text.split("\n")) {
                opened.events().append(line.getBytes(StandardCharsets.UTF_8));
                for (var fields = RegisterEvent.parse(line.getBytes(StandardCharsets.UTF_8)).fields().fields();
                     fields.hasNext(); ) {
                    var field = fields.next();
                    if (field.getKey().endsWith("_blob") && field.getValue().isTextual()) {
                        store.blobs("arch").putRaw(field.getValue().asText(), resource("blobs/"
                                + field.getValue().asText()));
                    }
                }
            }
            opened.writeState(resource("state.json"));
        }
        return store;
    }

    @Test
    void aRegisterOfAnEarlierVersion_verifies_andFoldsToTheBytesItRecorded() {
        InMemoryRegisterStore store = earlierRegister();
        try (RegisterStore.Opened opened = store.open("arch")) {
            ChainReport report = RegisterVerifier.verify("arch", opened.events().readAll(), opened.blobs(),
                    opened.state());

            assertThat(report.intact()).as(report.problems().toString()).isTrue();
            RegisterState state = RegisterReplay.replay("arch", new RegisterLog(opened, () -> T3).read(),
                    opened.blobs());
            assertThat(StateJson.write(state)).isEqualTo(resource("state.json"));
            assertThat(state.namesDecidedSeverity()).isFalse();
            // The fold knows what the state of that version does not say: the severity each decision was made on
            assertThat(state.findings()).extracting(Finding::status, Finding::severity, f -> f.disposition().severity())
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(FindingStatus.DISMISSED, Severity.CRITICAL, Severity.LOW),
                            org.assertj.core.groups.Tuple.tuple(FindingStatus.ACCEPTED_RISK, Severity.HIGH,
                                    Severity.MEDIUM));
        }
    }

    /** What that version covered silently comes back with the first report this version applies. */
    @Test
    void theNextReportOnARegisterOfAnEarlierVersion_reopensWhatWasDecidedOnALowerSeverity() {
        InMemoryRegisterStore store = earlierRegister();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T3);
            RegisterState before = RegisterReplay.replay("arch", log.read(), opened.blobs());

            Applied applied = new RegisterWriter(log, opened.blobs()).applied(before, report(opened, "run-3", T3,
                    stated(Severity.CRITICAL, "E2"),
                    new ReportedFinding("size", "B.java", Severity.HIGH, "B is long", "d", "E1", List.of())), null,
                    "head-3");
            opened.writeState(StateJson.write(applied.state()));

            assertThat(applied.changes()).extracting(Change::kind, Change::detail).containsExactlyInAnyOrder(
                    org.assertj.core.groups.Tuple.tuple(Kind.REOPENED, "higher_severity: LOW → CRITICAL"),
                    org.assertj.core.groups.Tuple.tuple(Kind.REOPENED, "higher_severity: MEDIUM → HIGH"));
            assertThat(applied.state().namesDecidedSeverity()).isTrue();
            assertThat(log.read()).extracting(RegisterEvent::type).as("the line an earlier version refuses comes"
                    + " before the first line it would fold otherwise").startsWith("register.created",
                    "report.applied", "finding.new", "finding.new", "finding.dismissed", "finding.accepted",
                    "report.applied", "register.requires", "report.applied");
            assertThat(new String(StateJson.write(applied.state()), StandardCharsets.UTF_8))
                    .contains("\"severity\":\"LOW\",\"until\":null}");
            ChainReport report = RegisterVerifier.verify("arch", opened.events().readAll(), opened.blobs(),
                    opened.state());
            assertThat(report.intact()).as(report.problems().toString()).isTrue();
            assertThat(RegisterReplay.replay("arch", log.read(), opened.blobs())).isEqualTo(applied.state());
        }
    }

    /**
     * A decision this version records on such a register: from its line on the states name the severity of every
     * decision that was made on another one, the ones of the earlier version too.
     */
    @Test
    void aDecisionOnARegisterOfAnEarlierVersion_isRecordedWithTheStateItsLineReplaysTo() {
        InMemoryRegisterStore store = earlierRegister();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T3);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            RegisterState state = RegisterReplay.replay("arch", log.read(), opened.blobs());
            Finding dismissed = state.findings().stream().filter(f -> f.status() == FindingStatus.DISMISSED)
                    .findFirst().orElseThrow();
            Finding accepted = state.findings().stream().filter(f -> f.status() == FindingStatus.ACCEPTED_RISK)
                    .findFirst().orElseThrow();

            Finding open = Disposition.reopen(dismissed, "look again", "alex", T3);
            state = writer.disposition(state.with(List.of(open, accepted)), open, FindingStatus.OPEN, "look again",
                    "alex", T3, null);
            assertThat(state.namesDecidedSeverity()).as("a reopening names no severity").isFalse();
            assertThat(log.read().getLast().type()).as("and is a line an earlier version folds the same way")
                    .isEqualTo("finding.reopened");
            Finding again = Disposition.dismiss(open, "checked: not ours", "alex", T3);
            state = writer.disposition(state.with(List.of(again, accepted)), again, FindingStatus.DISMISSED,
                    "checked: not ours", "alex", T3, null);
            opened.writeState(StateJson.write(state));

            assertThat(state.namesDecidedSeverity()).isTrue();
            assertThat(log.read()).extracting(RegisterEvent::type).endsWith("finding.reopened", "register.requires",
                    "finding.dismissed");
            assertThat(state.finding(again.id()).disposition().severity()).isEqualTo(Severity.CRITICAL);
            assertThat(new String(StateJson.write(state), StandardCharsets.UTF_8))
                    .as("the acceptance of the earlier version was made on MEDIUM")
                    .contains("\"kind\":\"ACCEPTED_RISK\",\"note\":\"known\",\"severity\":\"MEDIUM\"");
            ChainReport report = RegisterVerifier.verify("arch", opened.events().readAll(), opened.blobs(),
                    opened.state());
            assertThat(report.intact()).as(report.problems().toString()).isTrue();
            assertThat(RegisterReplay.replay("arch", log.read(), opened.blobs())).isEqualTo(state);
        }
    }

    // --- two findings of one identity in one report ---

    private static ReportedFinding titled(Severity severity, String title, String evidence) {
        return new ReportedFinding("size", "A.java", severity, title, "d", evidence, List.of());
    }

    /** Whether a decision holds must not depend on the order a report states the duplicates in. */
    @Test
    void ofTwoFindingsOfOneIdentity_theMostSevereCounts_inEitherOrder() {
        ReportedFinding low = titled(Severity.LOW, "low one", "E-low");
        ReportedFinding critical = titled(Severity.CRITICAL, "critical one", "E-critical");
        for (List<ReportedFinding> order : List.of(List.of(low, critical), List.of(critical, low))) {
            Applied applied = Reconciler.apply(dismissedAt(Severity.LOW), new Report("run-2", T2, "blob-run-2", order,
                    Set.of()), null);

            Finding f = only(applied.state());
            assertThat(f.status()).as(order.toString()).isEqualTo(FindingStatus.OPEN);
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.title()).isEqualTo("critical one");
            assertThat(f.evidence()).isEqualTo("E-critical");
            assertThat(f.occurrences()).isEqualTo(2);
            assertThat(applied.changes()).containsExactlyInAnyOrder(
                    new Change(Kind.REOPENED, f.id(), "higher_severity: LOW → CRITICAL"),
                    new Change(Kind.DUPLICATE_IN_REPORT, f.id(), "findings[" + order.indexOf(low) + "]"));
        }
    }

    @Test
    void ofTwoFindingsOfOneIdentityWithTheSameSeverity_theFirstCounts_andANewFindingTakesTheMostSevereToo() {
        Applied same = Reconciler.apply(empty, report("run-1", T1, titled(Severity.HIGH, "first", "E1"),
                titled(Severity.HIGH, "second", "E2")), null);
        assertThat(only(same.state()).title()).isEqualTo("first");
        assertThat(same.changes()).containsExactly(new Change(Kind.NEW, only(same.state()).id(), null),
                new Change(Kind.DUPLICATE_IN_REPORT, only(same.state()).id(), "findings[1]"));

        Applied fresh = Reconciler.apply(empty, report("run-1", T1, titled(Severity.LOW, "low one", "E1"),
                titled(Severity.HIGH, "high one", "E2")), null);
        assertThat(only(fresh.state()).title()).isEqualTo("high one");
        assertThat(fresh.changes()).containsExactly(
                new Change(Kind.DUPLICATE_IN_REPORT, only(fresh.state()).id(), "findings[0]"),
                new Change(Kind.NEW, only(fresh.state()).id(), null));
    }

    /** A line of an earlier version folds as that version applied it: the first of the duplicates won. */
    @Test
    void withTheRuleOfAnEarlierVersion_theFirstOfTwoFindingsOfOneIdentityWins() {
        Applied applied = Reconciler.apply(empty, report("run-1", T1, titled(Severity.LOW, "low one", "E1"),
                titled(Severity.HIGH, "high one", "E2")), null, DecisionOnHigherSeverity.UNCHECKED);

        assertThat(only(applied.state()).title()).isEqualTo("low one");
        assertThat(applied.changes()).containsExactly(new Change(Kind.NEW, only(applied.state()).id(), null),
                new Change(Kind.DUPLICATE_IN_REPORT, only(applied.state()).id(), "findings[1]"));
    }

    // --- lines a replay refuses ---

    private static RegisterEvent line(int seq, String type, String json) {
        return RegisterEvent.parse(("{\"seq\":" + seq + ",\"at\":\"2026-11-11T10:00:00.000Z\",\"type\":\"" + type
                + "\",\"register\":\"arch\",\"prev\":\"p\"," + json + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** A missing setting is a line of an earlier version; a value nobody knows is a broken line. */
    @Test
    void aReportAppliedWithASettingNobodyKnows_isRefused_likeAnUnknownEvent() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T1);
            new RegisterWriter(log, opened.blobs()).applied(empty, report(opened, "run-1", T1,
                    stated(Severity.LOW, "E1")), null, "head-1");
            List<byte[]> lines = new java.util.ArrayList<>(opened.events().readAll());
            int applied = 1;
            assertThat(new String(lines.get(applied), StandardCharsets.UTF_8))
                    .contains("\"decision_on_higher_severity\":\"reopen\"");
            RegisterEvent tampered = RegisterEvent.parse(new String(lines.get(applied), StandardCharsets.UTF_8)
                    .replace("\"decision_on_higher_severity\":\"reopen\"", "\"decision_on_higher_severity\":\"sometimes\"")
                    .getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> RegisterReplay.replay("arch", List.of(log.read().getFirst(), tampered),
                    opened.blobs())).isInstanceOf(IllegalStateException.class)
                    .hasMessage("seq 1: unknown decision_on_higher_severity 'sometimes'");
        }
    }

    @Test
    void aRegisterThatRequiresWhatThisVersionDoesNotKnow_isRefused_andTheKnownRequirementIsFolded() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.open("arch")) {
            assertThat(RegisterReplay.replay("arch", List.of(line(0, "register.requires",
                    "\"feature\":\"decision_severity\"")), opened.blobs()).namesDecidedSeverity()).isTrue();
            assertThatThrownBy(() -> RegisterReplay.replay("arch", List.of(line(0, "register.requires",
                    "\"feature\":\"time_travel\"")), opened.blobs())).isInstanceOf(IllegalStateException.class)
                    .hasMessage("seq 0: the register requires 'time_travel', which this version of Traceworks does"
                            + " not know: a newer version wrote it");
        }
    }

    // --- a decision that is no longer in force ---

    @Test
    void theFindingsOfAList_sayWhenADecisionIsNotInForce_andNothingWhenItIs() {
        RegisterState inForce = dismissedAt(Severity.LOW);
        RegisterState open = Reconciler.apply(inForce, report("run-2", T2, stated(Severity.CRITICAL, "E2")), null)
                .state();
        // A dismissed finding of a measured rule that three reports in a row did not state is retired as resolved
        RegisterState resolved = inForce;
        for (int i = 2; i <= 4; i++) {
            resolved = Reconciler.apply(resolved, report("run-" + i, T2), null).state();
        }
        assertThat(only(resolved).status()).isEqualTo(FindingStatus.RESOLVED);

        assertThat(only(inForce).decisionInForce()).isTrue();
        assertThat(only(open).decisionInForce()).isFalse();
        assertThat(only(resolved).decisionInForce()).isFalse();
        assertThat(StateJson.findings(inForce.findings())).doesNotContain("in_force");
        assertThat(StateJson.findings(open.findings())).contains("\"status\":\"OPEN\"")
                .contains("\"evidence\":null,\"in_force\":false,\"kind\":\"DISMISSED\"").contains("\"severity\":\"LOW\"");
        assertThat(StateJson.findings(resolved.findings())).contains("\"status\":\"RESOLVED\"")
                .contains("\"evidence\":null,\"in_force\":false,\"kind\":\"DISMISSED\"");
        assertThat(new String(StateJson.write(resolved), StandardCharsets.UTF_8))
                .as("state.json keeps its keys: the status says it there").doesNotContain("in_force");
    }

    @Test
    void theSetting_hasTwoWords_andTheRuleOfAnEarlierVersionHasNone() {
        assertThat(DecisionOnHigherSeverity.of("reopen")).contains(DecisionOnHigherSeverity.REOPEN);
        assertThat(DecisionOnHigherSeverity.of("keep")).contains(DecisionOnHigherSeverity.KEEP);
        assertThat(DecisionOnHigherSeverity.of("unchecked")).isEmpty();
        assertThat(DecisionOnHigherSeverity.of(null)).isEmpty();
        assertThat(DecisionOnHigherSeverity.DEFAULT).isEqualTo(DecisionOnHigherSeverity.REOPEN);
        assertThat(Optional.ofNullable(DecisionOnHigherSeverity.UNCHECKED.word())).isEmpty();
    }
}
