package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import traceworks.core.model.RunStatus;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.register.Change;
import traceworks.core.register.Finding;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterState;
import traceworks.core.testing.FakeCommandRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;

/**
 * A register is kept per workflow key and per project: one audit package run on two projects in one home feeds two
 * registers, and a run sees only the register of its project. The two cases that were reproduced with one shared
 * register on 2026-11-20 are the first two tests.
 */
class RegisterPerProjectTest {

    /** A step that reads what the register knew, then the report. */
    static final Path AUDIT = Path.of("/pkgs/audit");
    /** Another workflow under the same register key. */
    static final Path OTHER = Path.of("/pkgs/other");

    private static final String METADATA = "\"metadata\": {\"audit\": {\"enabled\": true, \"key\": \"hello\","
            + " \"category\": \"example\", \"report_block\": \"report\"}}";

    private final AppFixture fixture = new AppFixture();

    private static final String AUDIT_JSON = "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + METADATA
            + ", \"blocks\": ["
            + "{\"type\": \"shell\", \"name\": \"known\", \"config\": {\"values\": {\"KNOWN\":"
            + " \"{{ audit_findings }}\", \"REPORT\": \"{{ previous_report }}\", \"ITEMS\": \"{{ audit_items }}\"},"
            + " \"command\": \"./known {{ register_dir }}\"}},"
            + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}";
    private static final String OTHER_JSON = "{\"id\": \"wf-other\", \"name\": \"Other\", " + METADATA
            + ", \"blocks\": ["
            + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}";

    RegisterPerProjectTest() {
        fixture.packages.put(AUDIT, AUDIT_JSON);
        fixture.packages.put(OTHER, OTHER_JSON);
        fixture.commands.on("./known", "read");
    }

    /** The next report states one long file per subject, and lists each subject as an item it judged. */
    private void report(String... subjects) {
        StringBuilder findings = new StringBuilder();
        StringBuilder items = new StringBuilder();
        for (String subject : subjects) {
            if (!findings.isEmpty()) {
                findings.append(",");
                items.append(",");
            }
            findings.append("{\"ruleKey\":\"long-file\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"LOW\",\"title\":\"").append(subject).append(" is long\",\"item\":\"")
                    .append(subject).append("\"}");
            items.append("{\"name\":\"").append(subject).append("\",\"digest\":\"d1\",\"judged\":true}");
        }
        fixture.commands.on("./report", "# Report of " + String.join(" and ", subjects) + "\n\n"
                + "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[" + findings
                + "],\"items\":[" + items + "]}\n-->\n");
    }

    private RunResult run(String project) {
        return fixture.services().start(request(AUDIT).inProject(project), null, null);
    }

    private RegisterState state(String project) {
        return fixture.services().findings().ofProject(project).state("hello");
    }

    private List<String> findings(String project) {
        return state(project).findings().stream().map(f -> f.subject() + ":" + f.status()).toList();
    }

    /** What the last run's first step got: its command, and the values of its placeholders. */
    private String handedToTheLastRun() {
        List<traceworks.core.port.CommandRunner.CommandRequest> known = fixture.commands.requests().stream()
                .filter(r -> r.command().startsWith("./known")).toList();
        return known.getLast().command() + "\n" + String.join("\n", known.getLast().values().values());
    }

    // ---- the two reproduced cases ----

    /** Reproduced 2026-11-20: the run on project B reported "1 new, 1 resolved", and A's finding was resolved. */
    @Test
    void theRunOnProjectB_resolvesNothingOfProjectA_andEachProjectHasARegisterOfItsOwn() {
        report("a-long.txt");
        RunResult onA = run("project-a");
        report("b-long.txt");

        RunResult onB = run("project-b");

        assertThat(onA.register().counts()).containsEntry(Change.Kind.NEW, 1);
        assertThat(onB.register().applied()).isTrue();
        assertThat(onB.register().counts()).as("nothing of project A is known to the run on B")
                .containsEntry(Change.Kind.NEW, 1).doesNotContainKey(Change.Kind.RESOLVED);
        assertThat(findings("project-a")).as("nothing changed in A").containsExactly("a-long.txt:OPEN");
        assertThat(findings("project-b")).containsExactly("b-long.txt:OPEN");
        assertThat(fixture.registers.keys()).containsExactly("hello");
        assertThat(fixture.registers.projects("hello")).containsExactly("project-a", "project-b");
    }

    /** Reproduced 2026-11-20: the finding of project D was DISMISSED, by a decision made for project C. */
    @Test
    void aDecisionInProjectC_doesNotCoverTheSameFindingInProjectD() {
        report("same-name.txt");
        run("project-c");
        Finding inC = state("project-c").findings().getFirst();
        fixture.services().findings().ofProject("project-c").dismiss("hello", inC.id(), "not ours");

        RunResult onD = run("project-d");

        assertThat(onD.register().counts()).containsEntry(Change.Kind.NEW, 1);
        Finding inD = state("project-d").findings().getFirst();
        assertThat(inD.status()).as("the decision was made for C").isEqualTo(FindingStatus.OPEN);
        assertThat(inD.disposition()).isNull();
        assertThat(inD.id()).as("the identity of a finding names no project: the same id in two registers")
                .isEqualTo(inC.id());
        assertThat(state("project-c").findings().getFirst().status()).isEqualTo(FindingStatus.DISMISSED);
    }

    // ---- no project, no audit run ----

    @Test
    void anAuditRunWithoutAProject_isRefused_andNothingIsWritten() {
        report("a-long.txt");

        assertThatThrownBy(() -> fixture.services().start(request(AUDIT).inProject(null), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register hello").hasMessageContaining("--project KEY")
                .as("the other way: the line that binds a key to the folder the run works in")
                .hasMessageContaining("\"projects\": {\"KEY\": \"/the/folder\"}")
                .hasMessageContaining("traceworks.json");

        assertThat(fixture.store.runIds()).as("no run folder").isEmpty();
        assertThat(fixture.ledger.starts()).as("no ledger entry").isZero();
        assertThat(fixture.ledger.prepared()).as("the ledger was not even asked").isZero();
        assertThat(fixture.registers.keys()).as("no register folder").isEmpty();
        assertThat(fixture.commands.requests()).as("no step ran").isEmpty();
    }

    /**
     * A run of a version before registers had projects that is resumed now: its record names no project, so its
     * report could feed no register. The resume is refused before anything runs: every remaining step, a model's
     * too, would work for a report nobody takes.
     */
    @Test
    void aResumeOfAnAuditRunWhoseRecordNamesNoProject_isRefused_andNoStepRuns() throws Exception {
        String parking = "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + METADATA + ", \"blocks\": ["
                + "{\"type\": \"approval\", \"name\": \"go\", \"config\": {\"question\": \"Go on?\","
                + " \"options\": [\"Yes\", \"No\"]}},"
                + "{\"type\": \"shell\", \"name\": \"known\", \"config\": {\"values\": {\"KNOWN\":"
                + " \"{{ audit_findings }}\"}, \"command\": \"./known\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}";
        Path pkg = Path.of("/pkgs/parking");
        // The record of that version: a parked run of this one, with the project taken out of its first line and
        // the chain of the lines after it made anew
        AppFixture source = new AppFixture();
        source.packages.put(pkg, parking);
        String id = source.services().start(request(pkg), null, null).runId();
        List<String> lines = new java.util.ArrayList<>();
        String previousOld = null;
        String previousNew = null;
        for (byte[] raw : source.run(id).log().readAll()) {
            String line = new String(raw, StandardCharsets.UTF_8);
            if (previousOld == null) {
                assertThat(line).contains("\"project\":\"shop\",");
                line = line.replace("\"project\":\"shop\",", "");
            } else {
                assertThat(line).contains("\"prev\":\"" + previousOld + "\"");
                line = line.replace("\"prev\":\"" + previousOld + "\"", "\"prev\":\"" + previousNew + "\"");
            }
            previousOld = EventChain.sha256Hex(raw);
            previousNew = EventChain.sha256Hex(line.getBytes(StandardCharsets.UTF_8));
            lines.add(line);
        }
        fixture.packages.put(pkg, parking);
        try (var created = fixture.store.create(id, fixture.clock.now())) {
            lines.forEach(line -> created.events().append(line.getBytes(StandardCharsets.UTF_8)));
            created.writeRegisterFiles(new java.util.LinkedHashMap<>(source.run(id).registerFolder()));
        }
        source.run(id).blobs().all().forEach(fixture.run(id).blobs()::put);
        report("a-long.txt");
        fixture.services().answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        int linesBefore = fixture.run(id).log().readAll().size();

        assertThatThrownBy(() -> fixture.services().resume(AppFixture.resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("Run " + id + " cannot resume")
                .hasMessageContaining("the record of the run names no project")
                .hasMessageContaining("its report could feed no register")
                .hasMessageContaining("the register hello");

        assertThat(fixture.commands.requests()).as("no step ran").isEmpty();
        assertThat(fixture.run(id).log().readAll()).as("nothing was recorded").hasSize(linesBefore);
        assertThat(fixture.registers.keys()).as("no register is written").isEmpty();
        assertThat(fixture.services().view(id).status()).as("the run is as it was").isEqualTo(RunStatus.PARKED);
    }

    /**
     * A resume takes the project of run.started: what the steps after it are handed comes from the register of
     * that project, and the register the run feeds at its end is the one it started with.
     */
    @Test
    void aResumedRun_readsAndFeedsTheRegisterOfTheProjectItStartedWith() {
        Path pkg = Path.of("/pkgs/parking");
        fixture.packages.put(pkg, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + METADATA + ", \"blocks\": ["
                + "{\"type\": \"approval\", \"name\": \"go\", \"config\": {\"question\": \"Go on?\","
                + " \"options\": [\"Yes\", \"No\"]}},"
                + "{\"type\": \"shell\", \"name\": \"known\", \"config\": {\"values\": {\"KNOWN\":"
                + " \"{{ audit_findings }}\", \"REPORT\": \"{{ previous_report }}\"}, \"command\": \"./known\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        report("a-long.txt");
        run("project-a");
        RunResult parked = fixture.services().start(request(pkg).inProject("project-a"), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        report("b-long.txt");
        run("project-b");
        int linesOfB = events("project-b").size();
        report("a-long.txt", "a-second.txt");
        fixture.services().answer(parked.runId(), new AnswerRequest("approval", 0, "Yes", null, null));

        RunResult resumed = fixture.services().resume(AppFixture.resumeOf(parked.runId()), null, null);

        assertThat(resumed.status()).as(String.valueOf(resumed.error())).isEqualTo(RunStatus.COMPLETED);
        assertThat(handedToTheLastRun()).as("the step after the resume is handed what the register of project A"
                + " knows, and nothing of B").contains("long-file | a-long.txt", "# Report of a-long.txt")
                .doesNotContain("b-long.txt").doesNotContain("(no known findings)");
        assertThat(resumed.register().project()).isEqualTo("project-a");
        assertThat(resumed.register().applied()).isTrue();
        assertThat(resumed.register().counts()).containsEntry(Change.Kind.NEW, 1).containsEntry(Change.Kind.SEEN, 1);
        assertThat(findings("project-a")).containsExactlyInAnyOrder("a-long.txt:OPEN", "a-second.txt:OPEN");
        assertThat(events("project-b")).as("the other project's register got no line").hasSize(linesOfB);
        assertThat(findings("project-b")).containsExactly("b-long.txt:OPEN");
        assertThat(fixture.ofType(parked.runId(), "run.resumed").getFirst().fields().has("project"))
                .as("the project is that of run.started, and no resume names another").isFalse();
    }

    // ---- a run sees only its project ----

    @Test
    void aRunSeesOnlyTheRegisterOfItsProject_inItsPlaceholdersItsFolderAndTheHeadItRecords() {
        report("a-long.txt");
        run("project-a");
        run("project-a");
        report("b-long.txt");
        run("project-b");

        RunResult secondOnB = run("project-b");

        String handed = handedToTheLastRun();
        assertThat(handed).as("audit_findings, previous_report and audit_items of project B alone")
                .contains("long-file | b-long.txt", "# Report of b-long.txt", "| b-long.txt")
                .doesNotContain("a-long.txt");
        var folder = fixture.run(secondOnB.runId()).registerFolder();
        assertThat(new String(folder.get("findings.jsonl"), StandardCharsets.UTF_8)).contains("b-long.txt")
                .doesNotContain("a-long.txt");
        assertThat(new String(folder.get("items.jsonl"), StandardCharsets.UTF_8)).contains("b-long.txt")
                .doesNotContain("a-long.txt");
        assertThat(new String(folder.get("previous-report.md"), StandardCharsets.UTF_8))
                .contains("# Report of b-long.txt").doesNotContain("a-long.txt");
        JsonNode recorded = fixture.ofType(secondOnB.runId(), "run.started").getFirst().fields().path("register");
        assertThat(recorded.path("key").asText()).isEqualTo("hello");
        assertThat(recorded.path("project").asText()).isEqualTo("project-b");
        List<byte[]> logOfB = fixture.registers.log("hello", "project-b").readAll();
        // The log of B when the second run on B started: its first report, without the lines the second one added
        RegisterEvent secondApplied = events("project-b").stream()
                .filter(e -> RegisterEvents.REPORT_APPLIED.equals(e.type())).toList().getLast();
        assertThat(recorded.path("seq").asInt()).isEqualTo(secondApplied.seq() - 1);
        assertThat(recorded.path("head").asText()).isEqualTo(EventChain.sha256Hex(logOfB.get(secondApplied.seq() - 1)));
    }

    /** The first run on a project knows nothing, whatever another project's register holds. */
    @Test
    void theFirstRunOnAProject_isHandedNothing_alsoWhenAnotherProjectHasARegister() {
        report("a-long.txt");
        run("project-a");

        RunResult onB = run("project-b");

        assertThat(handedToTheLastRun()).contains("(no known findings)", "(no previous report)", "(no known items)")
                .doesNotContain("a-long.txt");
        JsonNode recorded = fixture.ofType(onB.runId(), "run.started").getFirst().fields().path("register");
        assertThat(recorded.path("project").asText()).isEqualTo("project-b");
        assertThat(recorded.path("seq").isNull()).as("the register of B did not exist yet").isTrue();
        assertThat(recorded.path("head").isNull()).isTrue();
    }

    private List<RegisterEvent> events(String project) {
        try (RegisterStore.Opened opened = fixture.registers.open("hello", project)) {
            return new RegisterLog(opened, fixture.clock).read();
        }
    }

    /** A register that cannot be read refuses the runs of its own project, and of no other. */
    @Test
    void aDamagedRegisterOfOneProject_doesNotRefuseARunOnAnotherProject() {
        report("a-long.txt");
        run("project-a");
        run("project-b");
        fixture.registers.log("hello", "project-b").append("{\"seq\":9,\"garbage\":true}"
                .getBytes(StandardCharsets.UTF_8));

        RunResult onA = run("project-a");

        assertThat(onA.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(onA.register().applied()).isTrue();
        assertThatThrownBy(() -> run("project-b")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register hello of project-b cannot be read");
    }

    /**
     * One workflow per key is checked against the registers that can be read: the line a sibling's log begins
     * with. A sibling whose first line is no line, is not the one a register begins with, or cannot be read at all
     * makes no claim, refuses no run on another project, and is named by no message of that run.
     */
    @Test
    void aSiblingWhoseFirstLineCannotBeRead_makesNoClaim_andRefusesNoRunOnAnotherProject() {
        for (String damage : List.of("garbage", "not-created", "unreadable")) {
            AppFixture each = new AppFixture();
            each.packages.put(AUDIT, AUDIT_JSON);
            each.packages.put(OTHER, OTHER_JSON);
            each.commands.on("./known", "read");
            each.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                    + "\"findings\":[]}\n-->\n");
            each.services().start(request(AUDIT).inProject("project-a"), null, null);
            each.services().start(request(AUDIT).inProject("project-b"), null, null);
            var log = each.registers.log("hello", "project-b");
            List<byte[]> lines = log.readAll();
            switch (damage) {
                case "garbage" -> {
                    log.dropLast(lines.size());
                    log.append("not a line".getBytes(StandardCharsets.UTF_8));
                    lines.subList(1, lines.size()).forEach(log::append);
                }
                case "not-created" -> log.dropFirst(1);
                default -> each.registers.failReads("hello", "project-b",
                        new java.io.UncheckedIOException(new java.io.IOException("Permission denied")));
            }

            RunResult onA = each.services().start(request(AUDIT).inProject("project-a"), null, null);

            assertThat(onA.status()).as(damage).isEqualTo(RunStatus.COMPLETED);
            assertThat(onA.register().applied()).as(damage + ": " + onA.register().reason()).isTrue();
            assertThat(onA.register().name()).isEqualTo("hello of project-a");
            RunResult first = each.services().start(request(AUDIT).inProject("project-c"), null, null);
            assertThat(first.register().applied()).as(damage + ", a first run on a third project: "
                    + first.register().reason()).isTrue();
            assertThatThrownBy(() -> each.services().start(request(AUDIT).inProject("project-b"), null, null))
                    .as(damage + ": its own runs are refused, by its own name")
                    .isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("the register hello of project-b cannot be read");
            assertThatThrownBy(() -> each.services().start(request(OTHER).inProject("project-d"), null, null))
                    .as(damage + ": the registers that can be read still say whose the key is")
                    .isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("belongs to the workflow wf-audit")
                    .hasMessageNotContaining("project-b");
        }
    }

    // ---- the register names its project ----

    /**
     * A writer that was cut off in the first line of a new register leaves a torn tail, and the next writer records
     * that it discarded it before anything else: the register still gets its first line, which names its project.
     */
    @Test
    void aRegisterWhoseFirstWriterWasCutOff_stillGetsTheLineThatNamesItsProject() {
        fixture.registers.log("hello", "project-a").tearTail("{\"seq\":0,\"at\":".getBytes(StandardCharsets.UTF_8));
        report("a-long.txt");

        RunResult first = run("project-a");
        RunResult second = run("project-a");

        assertThat(first.register().applied()).as(String.valueOf(first.register().reason())).isTrue();
        assertThat(events("project-a")).extracting(RegisterEvent::type).startsWith(RegisterEvents.REPAIRED,
                RegisterEvents.CREATED);
        assertThat(events("project-a").get(1).text("project")).isEqualTo("project-a");
        assertThat(state("project-a").project()).isEqualTo("project-a");
        assertThat(second.register().applied()).as(String.valueOf(second.register().reason())).isTrue();
        assertThat(fixture.services().findings().ofProject("project-a").verify("hello").intact()).isTrue();
    }

    @Test
    void theFirstLineOfARegister_namesItsProject_andSoDoesItsState() {
        report("a-long.txt");

        run("project-a");

        RegisterEvent created = events("project-a").getFirst();
        assertThat(created.type()).isEqualTo(RegisterEvents.CREATED);
        assertThat(created.text("project")).isEqualTo("project-a");
        assertThat(created.register()).as("the chain is the key's, as before").isEqualTo("hello");
        assertThat(state("project-a").project()).isEqualTo("project-a");
        assertThat(new String(fixture.registers.stateFile("hello", "project-a"), StandardCharsets.UTF_8))
                .contains("\"project\":\"project-a\"");
    }

    @Test
    void theSummaryOfARun_namesTheProject() {
        report("a-long.txt");

        RunResult result = run("project-a");

        assertThat(result.register().key()).isEqualTo("hello");
        assertThat(result.register().project()).isEqualTo("project-a");
        assertThat(result.register().name()).isEqualTo("hello of project-a");
    }

    // ---- a copied folder is not the register of the project it was copied to ----

    /** The folder of project A, copied to where the register of project B would lie: its log names A. */
    private void copyTheRegisterOfAToB() {
        report("a-long.txt");
        run("project-a");
        fixture.registers.copy("hello", "project-a", "project-b");
    }

    @Test
    void aCopiedFolder_refusesTheNextRunOfTheProject_beforeItExists() {
        copyTheRegisterOfAToB();
        int lines = fixture.registers.log("hello", "project-b").readAll().size();

        assertThatThrownBy(() -> run("project-b")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register hello of project-b cannot be read")
                .hasMessageContaining("names the project project-a")
                .hasMessageContaining("a folder that was copied or renamed is not the register of project-b")
                .hasMessageContaining("'traceworks findings hello verify --project project-b'");

        assertThat(fixture.store.runIds()).as("refused before the run exists").hasSize(1);
        assertThat(fixture.registers.log("hello", "project-b").readAll()).as("no line").hasSize(lines);
    }

    /** The folder is copied while the run runs: its report is not applied, and the copy gets no line. */
    @Test
    void aCopiedFolder_takesNoReport_whenItAppearsWhileTheRunRuns() {
        Path racing = Path.of("/pkgs/racing");
        fixture.packages.put(racing, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + METADATA + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"meanwhile\", \"config\": {\"command\": \"./meanwhile\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        report("a-long.txt");
        run("project-a");
        int[] lines = new int[1];
        fixture.commands.on("./meanwhile", req -> {
            fixture.registers.copy("hello", "project-a", "project-b");
            lines[0] = fixture.registers.log("hello", "project-b").readAll().size();
            return FakeCommandRunner.ok("done");
        });

        RunResult result = fixture.services().start(request(racing).inProject("project-b"), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("the register hello of project-b could not be written")
                .contains("names the project project-a");
        assertThat(fixture.registers.log("hello", "project-b").readAll()).as("no line").hasSize(lines[0]);
    }

    /**
     * A register that cannot be used is a refusal with the reason and the command that says more, never a failure
     * of the program: at the command line the first is exit 6, the second an internal error.
     */
    /** The lines of a log that names another project are not handed out as those of this register either. */
    @Test
    void theLinesOfACopiedFolder_areRefused_asItsStateIs_alsoWithoutItsFirstLine() {
        copyTheRegisterOfAToB();
        Findings findings = fixture.services().findings().ofProject("project-b");

        assertUnusable(() -> findings.events("hello"), "names the project project-a");
        assertThat(fixture.services().findings().ofProject("project-a").events("hello")).as("its own lines")
                .isNotEmpty();

        fixture.registers.log("hello", "project-b").dropFirst(1);

        assertUnusable(() -> findings.events("hello"), "does not begin with the line that says whose it is");
    }

    @Test
    void aCopiedFolder_isRefusedByEveryFindingsCommand_andDoesNotVerify() {
        copyTheRegisterOfAToB();
        Findings findings = fixture.services().findings().ofProject("project-b");
        String id = state("project-a").findings().getFirst().id();
        int lines = fixture.registers.log("hello", "project-b").readAll().size();
        String copied = "names the project project-a";

        assertUnusable(() -> findings.state("hello"), copied);
        assertUnusable(() -> findings.dismiss("hello", id, "not ours"), copied);
        assertUnusable(() -> findings.accept("hello", id, "fine", null), copied);
        assertUnusable(() -> findings.reopen("hello", id, null), copied);
        assertUnusable(() -> findings.render("hello"), copied);
        Findings.Verified verified = findings.verified("hello");
        assertThat(verified.report().intact()).isFalse();
        assertThat(verified.report().problems()).anySatisfy(problem -> assertThat(problem).contains(copied)
                .contains("is not the register of project-b"));
        assertThat(fixture.registers.log("hello", "project-b").readAll()).as("nothing was written").hasSize(lines);
        assertThat(fixture.services().findings().ofProject("project-a").verify("hello").intact())
                .as("the register of A is as it was").isTrue();
    }

    /**
     * Reproduced 2026-11-21: the copy gets one more {@code register.created}, with the right {@code seq} and
     * {@code prev}, that names the project it was copied to. A log says once whose it is, so the copy stays what
     * it is: refused by a run, by every findings command and by both checks.
     */
    @Test
    void aCopiedFolderThatSaysASecondTimeWhoseItIs_isRefusedOnEveryPath() {
        copyTheRegisterOfAToB();
        try (RegisterStore.Opened opened = fixture.registers.openForWrite("hello", "project-b")) {
            new RegisterLog(opened, fixture.clock).append(RegisterEvents.CREATED, java.util.Map.of("workflow_id",
                    "wf-audit", "category", "example", "project", "project-b"));
        }
        int lines = fixture.registers.log("hello", "project-b").readAll().size();
        String again = "seq " + (lines - 1) + ": register.created stands after the line the log begins with";
        Findings findings = fixture.services().findings().ofProject("project-b");
        String id = state("project-a").findings().getFirst().id();

        assertThatThrownBy(() -> run("project-b")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register hello of project-b cannot be read").hasMessageContaining(again);
        assertUnusable(() -> findings.state("hello"), again);
        assertUnusable(() -> findings.events("hello"), again);
        assertUnusable(() -> findings.dismiss("hello", id, "not ours"), again);
        assertUnusable(() -> findings.render("hello"), again);
        assertThat(findings.verified("hello").report().problems()).anySatisfy(problem ->
                assertThat(problem).contains(again)).anySatisfy(problem ->
                assertThat(problem).contains("names the project project-a"));
        assertThat(fixture.services().ledgerReport().problems()).extracting(LedgerReport.Problem::text)
                .anySatisfy(problem -> assertThat(problem).contains(again));
        assertThat(fixture.registers.log("hello", "project-b").readAll()).as("nothing was written").hasSize(lines);
        assertThat(fixture.store.runIds()).as("refused before the run exists").hasSize(1);
    }

    private static void assertUnusable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'hello' of the project 'project-b' cannot be used: ")
                .hasMessageContaining(reason)
                .hasMessageContaining("'traceworks findings hello verify --project project-b' says what is wrong");
    }

    /** A line that is none, a blob that is gone and a log that cannot be read are refusals of the same kind. */
    @Test
    void aRegisterThatCannotBeReadOrReplayed_isRefusedByEveryFindingsCommand_withTheReason() {
        for (String damage : List.of("garbage", "blob", "unreadable")) {
            AppFixture each = new AppFixture();
            each.packages.put(AUDIT, AUDIT_JSON);
            each.commands.on("./known", "read");
            each.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                    + "\"findings\":[{\"ruleKey\":\"long-file\",\"subject\":\"a.txt\",\"severity\":\"LOW\","
                    + "\"title\":\"a.txt is long\"}]}\n-->\n");
            each.services().start(request(AUDIT).inProject("project-b"), null, null);
            Findings findings = each.services().findings().ofProject("project-b");
            String id = findings.state("hello").findings().getFirst().id();
            var log = each.registers.log("hello", "project-b");
            String reason;
            switch (damage) {
                case "garbage" -> {
                    log.append("not a line".getBytes(StandardCharsets.UTF_8));
                    reason = "";
                }
                case "blob" -> {
                    String blob = findings.events("hello").stream().map(e -> e.text("findings_blob"))
                            .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                    each.registers.blobs("hello").delete(blob);
                    reason = "the findings blob " + blob + " is deleted";
                }
                default -> {
                    each.registers.failReads("hello", "project-b",
                            new java.io.UncheckedIOException(new java.io.IOException("Permission denied")));
                    reason = "Permission denied";
                }
            }
            int lines = log.readAll().size();

            assertUnusable(() -> findings.state("hello"), reason);
            assertUnusable(() -> findings.dismiss("hello", id, "not ours"), reason);
            assertUnusable(() -> findings.accept("hello", id, "fine", null), reason);
            assertUnusable(() -> findings.reopen("hello", id, null), reason);
            assertUnusable(() -> findings.render("hello"), reason);
            if (!damage.equals("blob")) {
                assertUnusable(() -> findings.events("hello"), reason);
            }
            Findings.Verified verified = findings.verified("hello");
            assertThat(verified.report().intact()).as(damage).isFalse();
            assertThat(verified.chainHolds()).as(damage).isFalse();
            assertThat(verified.report().problems()).as(damage).isNotEmpty();
            each.registers.failReads("hello", "project-b", null);
            assertThat(log.readAll()).as(damage + ": nothing was written").hasSize(lines);
        }
    }

    /**
     * Reproduced 2026-11-21: the folder of project A copied to B, and the first line of the copy deleted. The log
     * names no project then, and was taken as B's with A's decisions. It is the register of no project.
     */
    private String copyTheRegisterOfAToBWithoutItsFirstLine() {
        report("a-long.txt");
        run("project-a");
        String id = state("project-a").findings().getFirst().id();
        fixture.services().findings().ofProject("project-a").dismiss("hello", id, "not ours");
        fixture.registers.copy("hello", "project-a", "project-b");
        fixture.registers.log("hello", "project-b").dropFirst(1);
        return id;
    }

    @Test
    void aCopiedFolderWithoutItsFirstLine_isTheRegisterOfNoProject_forARunAndForFindings() {
        copyTheRegisterOfAToBWithoutItsFirstLine();
        int lines = fixture.registers.log("hello", "project-b").readAll().size();
        Findings findings = fixture.services().findings().ofProject("project-b");
        String headless = "does not begin with the line that says whose it is";

        assertThatThrownBy(() -> findings.state("hello")).hasMessageContaining(headless);
        assertThatThrownBy(() -> run("project-b")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register hello of project-b cannot be read")
                .hasMessageContaining(headless)
                .hasMessageContaining("'traceworks findings hello verify --project project-b'");
        Findings.Verified verified = findings.verified("hello");
        assertThat(verified.report().intact()).isFalse();
        assertThat(verified.report().problems()).anySatisfy(problem -> assertThat(problem).contains(headless)
                .contains("is not the register of project-b"));

        assertThat(fixture.store.runIds()).as("refused before the run exists").hasSize(1);
        assertThat(fixture.registers.log("hello", "project-b").readAll()).as("no line").hasSize(lines);
        assertThat(fixture.ledger.registerAnchors("hello", "project-b")).as("and no anchor").isEmpty();
    }

    /** The same folder appears while the run runs: its report is not applied, and the log gets no first line. */
    @Test
    void aCopiedFolderWithoutItsFirstLine_takesNoReport_andGetsNoFirstLineAfterTheForeignOnes() {
        Path racing = Path.of("/pkgs/racing");
        fixture.packages.put(racing, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + METADATA + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"meanwhile\", \"config\": {\"command\": \"./meanwhile\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        int[] lines = new int[1];
        fixture.commands.on("./meanwhile", req -> {
            copyTheRegisterOfAToBWithoutItsFirstLine();
            lines[0] = fixture.registers.log("hello", "project-b").readAll().size();
            return FakeCommandRunner.ok("done");
        });
        report("a-long.txt");

        RunResult result = fixture.services().start(request(racing).inProject("project-b"), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("the register hello of project-b could not be written")
                .contains("does not begin with the line that says whose it is");
        assertThat(fixture.registers.log("hello", "project-b").readAll()).as("no line").hasSize(lines[0]);
        assertThat(fixture.ledger.registerAnchors("hello", "project-b")).as("and no anchor").isEmpty();
    }

    // ---- findings: the project of a key ----

    @Test
    void findingsOfAKeyWithOneProject_needNoProject_andWithTwoTheCallIsRefusedAndNamesThem() {
        report("a-long.txt");
        run("project-a");
        Findings findings = fixture.services().findings();

        assertThat(findings.projects("hello")).containsExactly("project-a");
        assertThat(findings.projectOf("hello")).isEqualTo("project-a");
        assertThat(findings.state("hello").findings()).hasSize(1);

        run("project-b");

        assertThat(findings.projects("hello")).containsExactly("project-a", "project-b");
        assertThatThrownBy(() -> findings.state("hello")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("has 2 projects").hasMessageContaining("project-a, project-b")
                .hasMessageContaining("--project KEY");
        String id = state("project-a").findings().getFirst().id();
        assertThatThrownBy(() -> findings.dismiss("hello", id, "not ours")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("--project KEY");
        assertThatThrownBy(() -> findings.verified("hello")).isInstanceOf(RunRefusedException.class);
        assertThat(findings.ofProject("project-b").state("hello").project()).isEqualTo("project-b");
        assertThat(state("project-a").findings().getFirst().status()).as("nothing was decided")
                .isEqualTo(FindingStatus.OPEN);
    }

    /**
     * A project is its folder: one whose log is gone still counts, so "the only project the key has" never becomes
     * another project without a word.
     */
    @Test
    void aProjectWhoseLogIsGone_stillCounts_soTheOnlyProjectOfAKeyIsNeverAnotherOne() {
        report("a-long.txt");
        run("project-a");
        run("project-b");
        var log = fixture.registers.log("hello", "project-a");
        log.dropLast(log.readAll().size());
        Findings findings = fixture.services().findings();

        assertThat(fixture.registers.projects("hello")).containsExactly("project-a", "project-b");
        assertThat(findings.projects("hello")).containsExactly("project-a", "project-b");
        assertThatThrownBy(() -> findings.state("hello")).as("not the findings of project-b")
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("has 2 projects")
                .hasMessageContaining("project-a, project-b");
    }

    @Test
    void findingsOfAProjectTheKeyDoesNotHave_areRefused_andAKeyThatIsNoneToo() {
        report("a-long.txt");
        run("project-a");
        Findings findings = fixture.services().findings();

        assertThatThrownBy(() -> findings.ofProject("project-z").state("hello"))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("No register 'hello' of the project 'project-z'")
                .hasMessageContaining("project-a");
        assertThatThrownBy(() -> findings.ofProject("../x")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("project key");
        assertThat(findings.projects("nothing")).isEmpty();
        assertThat(fixture.registers.projects("hello")).as("asking made no register").containsExactly("project-a");
    }

    // ---- comparing two runs ----

    @Test
    void twoRunsOfTwoProjects_doNotFeedTheSameRegister_soTheirFindingsAreNotCompared() {
        report("a-long.txt");
        RunResult onA = run("project-a");
        RunResult onB = run("project-b");
        RunResult againOnB = run("project-b");

        var across = fixture.services().compare(onA.runId(), onB.runId()).findings();
        var within = fixture.services().compare(onB.runId(), againOnB.runId()).findings();

        assertThat(across.note()).isEqualTo("the two runs do not feed the same register");
        assertThat(within.note()).as("two runs of one project are compared").isNull();
        assertThat(within.register()).isEqualTo("hello");
    }

    // ---- one workflow per key, across its projects ----

    @Test
    void aKeyBelongsToOneWorkflowAcrossAllItsProjects() {
        report("a-long.txt");
        run("project-a");
        int lines = events("project-a").size();

        assertThatThrownBy(() -> fixture.services().start(request(OTHER).inProject("project-b"), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("the register hello")
                .hasMessageContaining("belongs to the workflow wf-audit").hasMessageContaining("wf-other");

        assertThat(fixture.store.runIds()).as("refused before the run exists").hasSize(1);
        assertThat(fixture.registers.projects("hello")).as("no register of the other project")
                .containsExactly("project-a");
        assertThat(events("project-a")).hasSize(lines);
    }

    /**
     * The start check finds the key free, and while the run runs another workflow's run takes the key for another
     * project: at its end the run feeds nothing, and its project gets no register.
     */
    @Test
    void aReportOfAnotherWorkflow_feedsNothing_whenTheKeyWasTakenForAnotherProjectWhileItRan() {
        Path racing = Path.of("/pkgs/racing");
        fixture.packages.put(racing, "{\"id\": \"wf-other\", \"name\": \"Other\", " + METADATA + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"meanwhile\", \"config\": {\"command\": \"./meanwhile\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        report("a-long.txt");
        fixture.commands.on("./meanwhile", req -> {
            assertThat(run("project-a").register().applied()).isTrue();
            return FakeCommandRunner.ok("done");
        });

        RunResult result = fixture.services().start(request(racing).inProject("project-b"), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("belongs to the workflow wf-audit").contains("wf-other");
        assertThat(fixture.registers.projects("hello")).containsExactly("project-a");
    }
}
