package traceworks.core.register;

import traceworks.core.port.Clock;
import traceworks.core.port.RegisterStore;
import traceworks.core.testing.EarlierRegister;
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
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture", "shop");
            RegisterState s0 = RegisterState.empty("arch", "wf-1", "architecture").ofProject("shop");
            String r1 = opened.blobs().put("report 1".getBytes()).sha256();
            String r2 = opened.blobs().put("report 2".getBytes()).sha256();
            Reconciler.Applied a1 = writer.applied(s0, new Reconciler.Report("run-1", T1, r1, List.of(finding("A"),
                    finding("B")), Set.of()), null, "head-1");
            Reconciler.Applied a2 = writer.applied(a1.state(), new Reconciler.Report("run-2", T2, r2,
                    List.of(finding("B"), finding("C")), Set.of()), null, "head-2");
            writer.rejected("run-3", "report step missing", List.of());
            expected = a2.state();
        }

        try (RegisterStore.Opened opened = store.open("arch", "shop")) {
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
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture", "shop");
            state = RegisterState.empty("arch", "wf-1", "architecture").ofProject("shop");
            String r1 = opened.blobs().put("report 1".getBytes()).sha256();
            String r2 = opened.blobs().put("report 2".getBytes()).sha256();
            state = writer.applied(state, new Reconciler.Report("run-1", T1, r1,
                    List.of(finding("A.java"), finding("B.java"), finding("C.java")), Set.of()), catalog, catalogJson,
                    "head-1").state();
            state = writer.applied(state, new Reconciler.Report("run-2", T2, r2, List.of(finding("A.java")), Set.of(),
                    List.of(new GoneFinding("split", "B.java"))), catalog, catalogJson, "head-2").state();
        }

        try (RegisterStore.Opened opened = store.open("arch", "shop")) {
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
        try (RegisterStore.Opened opened = store.open("none", "shop")) {
            assertThat(RegisterReplay.replay("none", List.of(), opened.blobs()))
                    .isEqualTo(RegisterState.empty("none", null, null));
        }
    }

    /** The items a report listed are part of what was applied: the log keeps the list as a blob, as the findings. */
    @Test
    void aReplay_givesTheItemsTheWriterLeft() {
        RegisterState state;
        try (RegisterStore.Opened opened = store.openForWrite("testing", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "testing", "shop");
            state = RegisterState.empty("testing", "wf-1", "testing").ofProject("shop");
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

        try (RegisterStore.Opened opened = store.open("testing", "shop")) {
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
            assertThat(RegisterVerifier.verify("testing", "shop", opened.events().readAll(), opened.blobs(),
                    java.util.Optional.of(StateJson.write(state))).intact()).isTrue();
        }
    }

    /** The findings blob of a report keeps each finding's tags, so the fold gives them back. */
    @Test
    void aReplay_keepsTheTagsOfAReportedFinding() {
        RegisterState expected;
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterWriter writer = new RegisterWriter(new RegisterLog(opened, clock), opened.blobs());
            writer.created("wf-1", "architecture", "shop");
            String r1 = opened.blobs().put("report 1".getBytes()).sha256();
            expected = writer.applied(RegisterState.empty("arch", "wf-1", "architecture").ofProject("shop"), new Reconciler.Report(
                    "run-1", T1, r1, List.of(new ReportedFinding("split", "A", Severity.MEDIUM, "T", null, "ev",
                    List.of("backend", "security"))), Set.of()), null, "head-1").state();
        }

        try (RegisterStore.Opened opened = store.open("arch", "shop")) {
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
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture", "shop");
            writer.applied(RegisterState.empty("arch", "wf-1", "architecture").ofProject("shop"), new Reconciler.Report("run-1", T1,
                    "r1", List.of(finding("A")), Set.of()), null, "head-1");
        }

        try (RegisterStore.Opened opened = store.open("arch", "shop")) {
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

    // ---- the project of a register, and a register from before registers had projects ----

    /** The fixture of an earlier version in the store, with these lines appended to its log. */
    private List<RegisterEvent> earlierLogWith(java.util.function.Consumer<RegisterLog> more) {
        store.putFromBefore(EarlierRegister.KEY, EarlierRegister.lines(), EarlierRegister.blobs(),
                EarlierRegister.state());
        try (RegisterStore.Opened opened = store.openUnassignedForWrite(EarlierRegister.KEY)) {
            RegisterLog log = new RegisterLog(opened, clock);
            more.accept(log);
            return log.read();
        }
    }

    private static java.util.Map<String, Object> assigned(String project) {
        return java.util.Map.of("project", project, "actor", "operator@host");
    }

    private traceworks.core.port.BlobStore earlierBlobs() {
        InMemoryRegisterStore blobs = new InMemoryRegisterStore();
        blobs.putFromBefore("b", List.of(), EarlierRegister.blobs(), null);
        return blobs.openUnassignedForWrite("b").blobs();
    }

    /** The log of an earlier version names no project; the line of its assignment gives it one, from there on. */
    @Test
    void registerAssigned_setsTheProjectOfARegisterFromBefore_andChangesNothingElse() {
        List<RegisterEvent> events = earlierLogWith(log -> log.append(RegisterEvents.ASSIGNED, assigned("shop")));
        List<RegisterEvent> before = events.subList(0, events.size() - 1);

        RegisterState earlier = RegisterReplay.replay("arch", before, earlierBlobs());
        RegisterState state = RegisterReplay.replay("arch", "shop", events, earlierBlobs());

        assertThat(earlier.project()).isNull();
        assertThat(StateJson.write(earlier)).as("the bytes that version recorded").isEqualTo(EarlierRegister.state());
        assertThat(state.project()).isEqualTo("shop");
        assertThat(state).isEqualTo(earlier.ofProject("shop"));
        assertThat(new String(StateJson.write(state), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"project\":\"shop\"");
        assertThat(StateJson.read(StateJson.write(state)).project()).isEqualTo("shop");
        assertThat(RegisterReplay.assignedTo(events)).isEqualTo("shop");
        assertThat(RegisterReplay.assignedTo(before)).isNull();
    }

    @Test
    void theLogOfARegisterFromBefore_isNotTheRegisterOfAProject_untilItIsAssignedToThatOne() {
        List<RegisterEvent> events = earlierLogWith(log -> log.append(RegisterEvents.ASSIGNED, assigned("shop")));
        List<RegisterEvent> before = events.subList(0, events.size() - 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", "shop", before,
                earlierBlobs())).isInstanceOf(IllegalStateException.class).hasMessageContaining("names no project");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", "web", events,
                earlierBlobs())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("names the project shop")
                .hasMessageContaining("is not the register of web");
    }

    @Test
    void registerAssigned_isRefused_twice_onARegisterThatNamesAProject_andWithAKeyThatIsNone() {
        List<RegisterEvent> twice = earlierLogWith(log -> {
            log.append(RegisterEvents.ASSIGNED, assigned("shop"));
            log.append(RegisterEvents.ASSIGNED, assigned("web"));
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", twice,
                earlierBlobs())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seq 11: register.assigned names the project web")
                .hasMessageContaining("is that of shop already");

        List<RegisterEvent> bad = earlierLogWith(log -> log.append(RegisterEvents.ASSIGNED, assigned("../shop")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", bad, earlierBlobs()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is no project key");

        try (RegisterStore.Opened opened = store.openForWrite("named", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            new RegisterWriter(log, opened.blobs()).created("wf-1", "architecture", "shop");
            log.append(RegisterEvents.ASSIGNED, assigned("web"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("named", log.read(),
                    opened.blobs())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is that of shop already");
        }
    }

    /** The verifier reads the project from the lines: an assigned register verifies as that of its project only. */
    @Test
    void anAssignedRegisterFromBefore_verifiesAsTheRegisterOfItsProject_andOfNoOther() {
        List<RegisterEvent> events = earlierLogWith(log -> log.append(RegisterEvents.ASSIGNED, assigned("shop")));
        List<byte[]> lines = events.stream().map(RegisterEvent::raw).toList();
        List<byte[]> before = lines.subList(0, lines.size() - 1);

        assertThat(RegisterVerifier.verify("arch", "shop", lines, earlierBlobs(), java.util.Optional.empty()).intact())
                .isTrue();
        assertThat(RegisterVerifier.verify("arch", null, before, earlierBlobs(),
                java.util.Optional.of(EarlierRegister.state())).intact()).as("as that version left it").isTrue();
        assertThat(RegisterVerifier.verify("arch", "web", lines, earlierBlobs(), java.util.Optional.empty())
                .problems()).singleElement().asString().contains("names the project shop");
        assertThat(RegisterVerifier.verify("arch", "shop", before, earlierBlobs(), java.util.Optional.empty())
                .problems()).singleElement().asString().contains("names no project");
    }

    // ---- a log that names no project is the register of no project ----

    /** The log of the register arch of shop: its first line, one report, and a decision. */
    private List<RegisterEvent> aRegisterOfShop() {
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture", "shop");
            String report = opened.blobs().put("report 1".getBytes()).sha256();
            writer.applied(RegisterState.empty("arch", "wf-1", "architecture").ofProject("shop"),
                    new Reconciler.Report("run-1", T1, report, List.of(finding("A")), Set.of()), null, "head-1");
            return log.read();
        }
    }

    private traceworks.core.port.BlobStore blobsOfShop() {
        return store.open("arch", "shop").blobs();
    }

    /**
     * A copied folder whose first line was deleted names no project. It is no log that "has not begun": it holds
     * lines of a register, and none that says whose.
     */
    @Test
    void aLogThatLostTheLineItBeginsWith_isTheRegisterOfNoProject() {
        List<RegisterEvent> events = aRegisterOfShop();
        List<RegisterEvent> headless = events.subList(1, events.size());

        assertThat(RegisterReplay.begun(headless)).as("it holds lines of a register").isTrue();
        for (String project : List.of("web", "shop")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", project, headless,
                    blobsOfShop())).as(project).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("the log of the register arch of " + project + " does not begin with the"
                            + " line that says whose it is (register.created)")
                    .hasMessageContaining("its first line is seq 1, " + headless.getFirst().type())
                    .hasMessageContaining("is not the register of " + project);
        }
        List<byte[]> lines = headless.stream().map(RegisterEvent::raw).toList();
        assertThat(RegisterVerifier.verify("arch", "web", lines, blobsOfShop(), java.util.Optional.empty())
                .problems()).anySatisfy(problem -> assertThat(problem).contains("does not begin with the line that"
                + " says whose it is").contains("is not the register of web"));
    }

    /** A line that says whose the register is does not make the lines before it that project's. */
    @Test
    void aLogWithLinesBeforeTheOneThatSaysWhoseItIs_isTheRegisterOfNoProject() {
        List<RegisterEvent> headless = aRegisterOfShop();
        headless = headless.subList(1, headless.size());
        List<RegisterEvent> events;
        try (RegisterStore.Opened opened = store.openForWrite("arch", "web")) {
            headless.forEach(event -> opened.events().append(event.raw()));
            RegisterLog log = new RegisterLog(opened, clock);
            new RegisterWriter(log, opened.blobs()).created("wf-1", "architecture", "web");
            events = log.read();
        }
        List<RegisterEvent> all = events;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", "web", all,
                blobsOfShop())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not begin with the line that says whose it is");
        assertThat(RegisterVerifier.verify("arch", "web", all.stream().map(RegisterEvent::raw).toList(),
                blobsOfShop(), java.util.Optional.empty()).problems()).anySatisfy(problem -> assertThat(problem)
                .contains("does not begin with the line that says whose it is"));
    }

    /** Only the notes of the tool may stand before the line a register begins with: such a log has not begun. */
    @Test
    void aLogWithNothingButNotesOfTheTool_hasNotBegun_andIsTheRegisterOfAnyProjectYet() {
        List<RegisterEvent> notes;
        try (RegisterStore.Opened opened = store.openForWrite("arch", "web")) {
            RegisterLog log = new RegisterLog(opened, clock);
            log.append(RegisterEvents.REPAIRED, java.util.Map.of("discarded_bytes", 12));
            log.append(RegisterEvents.UNANCHORED, java.util.Map.of("first_seq", 0, "last_seq", 0));
            notes = log.read();
        }

        assertThat(RegisterReplay.begun(notes)).isFalse();
        assertThat(RegisterReplay.begun(List.of())).isFalse();
        assertThat(RegisterReplay.replay("arch", "web", notes, blobsOfShop()).project()).isNull();
        assertThat(RegisterVerifier.verify("arch", "web", notes.stream().map(RegisterEvent::raw).toList(),
                blobsOfShop(), java.util.Optional.empty()).intact()).isTrue();
    }

    // ---- a log says once whose it is ----

    /** The log of shop, copied to the place of web, with one more line appended by {@code more}. */
    private List<RegisterEvent> theLogOfShopAtWebWith(java.util.function.Consumer<RegisterLog> more) {
        List<RegisterEvent> ofShop = aRegisterOfShop();
        try (RegisterStore.Opened opened = store.openForWrite("arch", "web")) {
            ofShop.forEach(event -> opened.events().append(event.raw()));
            RegisterLog log = new RegisterLog(opened, clock);
            more.accept(log);
            return log.read();
        }
    }

    /**
     * Reproduced 2026-11-21: the folder of one project copied to the place of another's, with one more
     * {@code register.created} appended that names the other project and has the right {@code seq} and
     * {@code prev}. The fold took the last such line, so the copy was the other project's register, with every
     * decision in it; the same line could name another workflow.
     */
    @Test
    void aSecondRegisterCreated_isRefusedByTheReplay_andReportedByTheVerifier() {
        List<RegisterEvent> events = theLogOfShopAtWebWith(log ->
                new RegisterWriter(log, store.open("arch", "web").blobs()).created("wf-other", "architecture", "web"));
        int second = events.size() - 1;
        String refusal = "seq " + second + ": register.created stands after the line the log begins with (seq 0,"
                + " register.created): a log says once whose it is";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", "web", events,
                blobsOfShop())).isInstanceOf(IllegalStateException.class).hasMessageContaining(refusal);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", events,
                blobsOfShop())).as("whatever project is asked for").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(refusal);

        List<byte[]> lines = events.stream().map(RegisterEvent::raw).toList();
        List<String> asWeb = RegisterVerifier.verify("arch", "web", lines, blobsOfShop(), java.util.Optional.empty())
                .problems();
        assertThat(asWeb).anySatisfy(problem -> assertThat(problem).contains(refusal));
        assertThat(asWeb).as("the log still names the project of its first line").anySatisfy(problem ->
                assertThat(problem).contains("names the project shop").contains("is not the register of web"));
        assertThat(RegisterVerifier.verify("arch", "shop", lines, blobsOfShop(), java.util.Optional.empty())
                .problems()).as("nor does it verify as the register it was copied from").singleElement().asString()
                .contains(refusal);
    }

    /** The same line after the first line of a register from before, which names no project. */
    @Test
    void aRegisterCreatedAfterTheOneOfARegisterFromBefore_givesItNoProject() {
        List<RegisterEvent> events = earlierLogWith(log -> log.append(RegisterEvents.CREATED,
                java.util.Map.of("workflow_id", "wf-1", "category", "architecture", "project", "shop")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", "shop", events,
                earlierBlobs())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seq 10: register.created stands after the line the log begins with");
        assertThat(RegisterVerifier.verify("arch", "shop", events.stream().map(RegisterEvent::raw).toList(),
                earlierBlobs(), java.util.Optional.empty()).problems())
                .anySatisfy(problem -> assertThat(problem).contains("a log says once whose it is"))
                .anySatisfy(problem -> assertThat(problem).contains("names no project"));
    }

    /** {@code register.assigned} is the one other line that names a project: it does not begin a log. */
    @Test
    void aLogThatBeginsWithRegisterAssigned_hasNoLineThatSaysWhoseItIs() {
        List<RegisterEvent> events;
        try (RegisterStore.Opened opened = store.openForWrite("arch", "web")) {
            RegisterLog log = new RegisterLog(opened, clock);
            log.append(RegisterEvents.ASSIGNED, assigned("web"));
            events = log.read();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterReplay.replay("arch", "web", events,
                blobsOfShop())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not begin with the line that says whose it is (register.created)")
                .hasMessageContaining("its first line is seq 0, register.assigned");
        assertThat(RegisterVerifier.verify("arch", "web", events.stream().map(RegisterEvent::raw).toList(),
                blobsOfShop(), java.util.Optional.empty()).problems()).anySatisfy(problem -> assertThat(problem)
                .contains("does not begin with the line that says whose it is"));
    }
}
