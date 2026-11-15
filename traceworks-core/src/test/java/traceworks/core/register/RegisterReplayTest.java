package traceworks.core.register;

import traceworks.core.port.Clock;
import traceworks.core.port.RegisterStore;
import traceworks.core.testing.InMemoryRegisterStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The state of a register is a fold over its log: the replay gives what the reconciler gave when the lines were written. */
class RegisterReplayTest {

    private static final Instant T1 = Instant.parse("2026-11-11T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-12T10:00:00Z");

    private final InMemoryRegisterStore store = new InMemoryRegisterStore();
    private final Clock clock = () -> T1;

    private static ReportedFinding finding(String subject) {
        return new ReportedFinding("split", subject, Severity.MEDIUM, "Title " + subject, null, "ev", List.of());
    }

    @Test
    void replay_equalsTheReconcilersState() {
        RegisterState expected;
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            RegisterState s0 = RegisterState.empty("arch", "wf-1", "architecture");
            String r1 = opened.blobs().put("report 1".getBytes()).sha256();
            String r2 = opened.blobs().put("report 2".getBytes()).sha256();
            Reconciler.Applied a1 = writer.applied(s0, new Reconciler.Report("run-1", T1, r1, List.of(finding("A"),
                    finding("B")), Set.of()), null, "head-1");
            Reconciler.Applied a2 = writer.applied(a1.state(), new Reconciler.Report("run-2", T2, r2,
                    List.of(finding("B"), finding("C")), Set.of()), null, "head-2");
            writer.rejected("run-3", "report step missing", List.of());
            expected = a2.state();
        }

        try (RegisterStore.Opened opened = store.open("arch")) {
            RegisterState replayed = RegisterReplay.replay("arch", new RegisterLog(opened, clock).read(),
                    opened.blobs());

            assertThat(replayed.findings()).isEqualTo(expected.findings());
            assertThat(replayed.validReports()).isEqualTo(2);
            assertThat(replayed.lastReport()).isEqualTo(new RegisterState.LastReport("run-3", null, null, false));
            assertThat(replayed.workflowId()).isEqualTo("wf-1");
        }
    }

    /**
     * What a report named as gone is part of what was applied: the log keeps it as a blob, so the fold resolves the
     * same findings.
     */
    @Test
    void replay_appliesTheGoneListOfAReport() {
        byte[] catalogJson = "{\"format\":1,\"rules\":{\"split\":{\"judged\":true}}}".getBytes();
        Catalog catalog = Catalog.parse(catalogJson);
        RegisterState state;
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            state = RegisterState.empty("arch", "wf-1", "architecture");
            String r1 = opened.blobs().put("report 1".getBytes()).sha256();
            String r2 = opened.blobs().put("report 2".getBytes()).sha256();
            state = writer.applied(state, new Reconciler.Report("run-1", T1, r1,
                    List.of(finding("A.java"), finding("B.java"), finding("C.java")), Set.of()), catalog, catalogJson,
                    "head-1").state();
            state = writer.applied(state, new Reconciler.Report("run-2", T2, r2, List.of(finding("A.java")), Set.of(),
                    List.of(new GoneFinding("split", "B.java"))), catalog, catalogJson, "head-2").state();
        }

        try (RegisterStore.Opened opened = store.open("arch")) {
            java.util.List<RegisterEvent> events = new RegisterLog(opened, clock).read();
            RegisterState replayed = RegisterReplay.replay("arch", events, opened.blobs());
            assertThat(replayed).isEqualTo(state);
            assertThat(replayed.findings()).extracting(Finding::subject, Finding::status).containsExactlyInAnyOrder(
                    org.assertj.core.groups.Tuple.tuple("A.java", FindingStatus.OPEN),
                    org.assertj.core.groups.Tuple.tuple("B.java", FindingStatus.RESOLVED),
                    org.assertj.core.groups.Tuple.tuple("C.java", FindingStatus.OPEN));
            RegisterEvent applied = events.stream().filter(e -> e.type().equals(RegisterEvents.REPORT_APPLIED))
                    .reduce((a, b) -> b).orElseThrow();
            assertThat(applied.text("gone_blob")).isNotNull();
            assertThat(applied.fields().path("counts").path("not_mentioned").asInt()).isEqualTo(1);
        }
    }

    @Test
    void replay_ofAnEmptyLog_isTheEmptyState_withoutAWorkflow() {
        try (RegisterStore.Opened opened = store.open("none")) {
            assertThat(RegisterReplay.replay("none", List.of(), opened.blobs()))
                    .isEqualTo(RegisterState.empty("none", null, null));
        }
    }

    /** The items a report listed are part of what was applied: the log keeps the list as a blob, as the findings. */
    @Test
    void aReplay_givesTheItemsTheWriterLeft() {
        RegisterState state;
        try (RegisterStore.Opened opened = store.openForWrite("testing")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "testing");
            state = RegisterState.empty("testing", "wf-1", "testing");
            ReportedFinding named = new ReportedFinding("split", "p.A#1", Severity.MEDIUM, "T", null, "ev", List.of(),
                    "p.A");
            String r1 = opened.blobs().put("report 1".getBytes()).sha256();
            String r2 = opened.blobs().put("report 2".getBytes()).sha256();
            String r3 = opened.blobs().put("report 3".getBytes()).sha256();
            state = writer.applied(state, new Reconciler.Report("run-1", T1, r1, List.of(named), Set.of(), List.of(),
                    List.of(new ReportedItem("p.A", "aa", true), new ReportedItem("p.B", "bb", true)), true), null,
                    "head-1").state();
            state = writer.applied(state, new Reconciler.Report("run-2", T2, r2, List.of(named), Set.of(), List.of(),
                    List.of(new ReportedItem("p.A", "a2", false)), true), null, "head-2").state();
            state = writer.applied(state, new Reconciler.Report("run-3", T2, r3, List.of(named), Set.of(), List.of(),
                    List.of(new ReportedItem("p.C", "cc", false)), false), null, "head-3").state();
        }

        try (RegisterStore.Opened opened = store.open("testing")) {
            List<RegisterEvent> events = new RegisterLog(opened, clock).read();
            RegisterState replayed = RegisterReplay.replay("testing", events, opened.blobs());

            assertThat(replayed).isEqualTo(state);
            assertThat(replayed.items()).containsExactly(new Item("p.A", "a2", "aa", new Finding.Seen("run-1", T1)),
                    new Item("p.C", "cc", null, null));
            assertThat(replayed.findings()).singleElement().extracting(Finding::item).isEqualTo("p.A");
            List<RegisterEvent> applied = events.stream().filter(e -> e.type().equals(RegisterEvents.REPORT_APPLIED))
                    .toList();
            assertThat(applied.get(0).text("items_blob")).isNotNull();
            assertThat(applied.get(0).fields().path("items_complete").asBoolean()).isTrue();
            assertThat(applied.get(0).fields().path("items").toString())
                    .isEqualTo("{\"judged\":2,\"listed\":2,\"settled\":1}");
            assertThat(applied.get(2).fields().path("items_complete").asBoolean()).isFalse();
            assertThat(RegisterVerifier.verify("testing", opened.events().readAll(), opened.blobs(),
                    java.util.Optional.of(StateJson.write(state))).intact()).isTrue();
        }
    }

    /** The findings blob of a report keeps each finding's tags, so the fold gives them back. */
    @Test
    void aReplay_keepsTheTagsOfAReportedFinding() {
        RegisterState expected;
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterWriter writer = new RegisterWriter(new RegisterLog(opened, clock), opened.blobs());
            writer.created("wf-1", "architecture");
            String r1 = opened.blobs().put("report 1".getBytes()).sha256();
            expected = writer.applied(RegisterState.empty("arch", "wf-1", "architecture"), new Reconciler.Report(
                    "run-1", T1, r1, List.of(new ReportedFinding("split", "A", Severity.MEDIUM, "T", null, "ev",
                    List.of("backend", "security"))), Set.of()), null, "head-1").state();
        }

        try (RegisterStore.Opened opened = store.open("arch")) {
            RegisterState replayed = RegisterReplay.replay("arch", new RegisterLog(opened, clock).read(),
                    opened.blobs());

            assertThat(replayed.findings()).isEqualTo(expected.findings());
            assertThat(replayed.findings()).singleElement()
                    .satisfies(f -> assertThat(f.tags()).containsExactly("backend", "security"));
        }
    }

    /** A line of an earlier version names no items blob: it replays as it did, and the state has no items. */
    @Test
    void aLineWithoutItemsBlob_replaysAsBefore() {
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            writer.applied(RegisterState.empty("arch", "wf-1", "architecture"), new Reconciler.Report("run-1", T1,
                    "r1", List.of(finding("A")), Set.of()), null, "head-1");
        }

        try (RegisterStore.Opened opened = store.open("arch")) {
            List<RegisterEvent> events = new RegisterLog(opened, clock).read();
            RegisterEvent applied = events.stream().filter(e -> e.type().equals(RegisterEvents.REPORT_APPLIED))
                    .findFirst().orElseThrow();

            assertThat(applied.fields().has("items_blob")).isFalse();
            assertThat(applied.fields().has("items")).isFalse();
            assertThat(RegisterReplay.replay("arch", events, opened.blobs()).items()).isEmpty();
            assertThat(new String(opened.blobs().get(applied.text("findings_blob")).orElseThrow()))
                    .doesNotContain("\"item\"");
        }
    }
}

