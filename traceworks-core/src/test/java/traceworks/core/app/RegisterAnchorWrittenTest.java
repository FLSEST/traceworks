package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.port.RegisterAnchor;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.register.RegisterEvents;
import traceworks.core.testing.EarlierRegister;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static traceworks.core.app.AppFixture.request;

/**
 * The anchor of a register says what was written: the lines the log gained since the anchor before it, by type and
 * count, the run they name, and who decided which finding, through which agent. It is derived from the log when the
 * head is anchored, whoever wrote, and {@code verify} derives the same again and reports every difference.
 */
class RegisterAnchorWrittenTest {

    private static final Path AUDIT = Path.of("/pkgs/audit");
    private static final String ONE_FINDING = "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
            + "\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"MEDIUM\","
            + "\"title\":\"Big A.java\"}]}\n-->\n";

    private final AppFixture fixture = new AppFixture();

    RegisterAnchorWrittenTest() {
        fixture.packages.put(AUDIT, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("./report", ONE_FINDING);
    }

    private List<RegisterAnchor> anchors() {
        return fixture.ledger.registerAnchors("hello", AppFixture.PROJECT);
    }

    private RegisterAnchor.Written lastWritten() {
        return anchors().getLast().written();
    }

    private List<byte[]> lines() {
        try (RegisterStore.Opened opened = fixture.registers.open("hello", AppFixture.PROJECT)) {
            return opened.events().readAll();
        }
    }

    private void lastAnchorIsTheHeadOfTheLog() {
        List<byte[]> lines = lines();
        assertThat(anchors().getLast().seq()).isEqualTo(lines.size() - 1);
        assertThat(anchors().getLast().head()).isEqualTo(EventChain.sha256Hex(lines.getLast()));
    }

    private void withAHook() {
        fixture.settings = new OperatorSettings(true, null, List.of(), null, null,
                OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB, Map.of(),
                OperatorSettings.defaultRunOutputBudgetMib(OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB), null,
                new OperatorSettings.Hooks(List.of("/opt/hooks/todos.sh"), 7));
    }

    // ---- what a run writes ----

    /** The first report of a register: the line that begins it, the feature it requires, the report, the finding. */
    @Test
    void theFirstReportOfARegister_saysItsLinesByTypeAndCount_andItsRun_andNoActor() {
        RunResult first = fixture.services().start(request(AUDIT), null, null);

        assertThat(anchors()).hasSize(1);
        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.new", 1),
                Map.entry("register.created", 1), Map.entry("register.requires", 1), Map.entry("report.applied", 1));
        assertThat(lastWritten().runs()).containsExactly(first.runId());
        assertThat(lastWritten().actors()).isEmpty();
        assertThat(lastWritten().agents()).isEmpty();
        assertThat(lastWritten().decided()).isEmpty();
        lastAnchorIsTheHeadOfTheLog();
    }

    /** The anchor covers the lines since the anchor before it: the second report's, and none of the first's. */
    @Test
    void aLaterReport_saysOnlyTheLinesSinceTheAnchorBeforeIt() {
        fixture.services().start(request(AUDIT), null, null);
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");

        RunResult second = fixture.services().start(request(AUDIT), null, null);

        assertThat(anchors()).hasSize(2);
        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.resolved", 1),
                Map.entry("report.applied", 1));
        assertThat(lastWritten().runs()).containsExactly(second.runId());
        assertThat(lastWritten().actors()).isEmpty();
    }

    @Test
    void aRejectedReport_saysItsLineAndItsRun() {
        fixture.commands.on("./report", "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":false,"
                + "\"findings\":[]}\n-->\n");

        RunResult run = fixture.services().start(request(AUDIT), null, null);

        assertThat(run.register().applied()).isFalse();
        assertThat(lastWritten().lines()).containsExactly(Map.entry("register.created", 1),
                Map.entry("report.rejected", 1));
        assertThat(lastWritten().runs()).containsExactly(run.runId());
        assertThat(lastWritten().actors()).isEmpty();
    }

    /** The hook's line is a write of its own, with an anchor of its own that names the run it ran for. */
    @Test
    void theHooksLine_hasAnAnchorOfItsOwn_thatSaysItAndItsRun() {
        withAHook();

        RunResult run = fixture.services().start(request(AUDIT), null, null);

        assertThat(anchors()).hasSize(2);
        assertThat(lastWritten().lines()).containsExactly(Map.entry("hook.ran", 1));
        assertThat(lastWritten().runs()).containsExactly(run.runId());
        assertThat(anchors().getFirst().written().lines()).doesNotContainKey("hook.ran");
    }

    /**
     * Reproduced 2026-11-21: a hand-appended {@code report.rejected} with a free text as its run, and a
     * {@code finding.dismissed} with free texts as actor and agent. The next write takes the lines as they are and
     * anchors the head; the anchor says a marker in the place of each such value, and verify computes the same.
     */
    @Test
    void aHandAppendedLineWithFreeText_putsAMarkerIntoTheAnchor_andNoneOfItsText() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        String text = "Mallory was here, and says so";
        try (traceworks.core.port.RegisterStore.Opened opened = fixture.registers.openForWrite("hello",
                AppFixture.PROJECT)) {
            traceworks.core.register.RegisterLog log = new traceworks.core.register.RegisterLog(opened,
                    fixture.clock);
            log.append(RegisterEvents.REPORT_REJECTED, Map.of("run_id", text, "reason", "none"));
            Map<String, Object> dismissed = new java.util.LinkedHashMap<>();
            dismissed.put("id", id);
            dismissed.put("note", "by hand");
            dismissed.put("actor", text + (char) 7);   // a control character: no actor has one
            dismissed.put("agent", "<b>" + text + "</b>");
            log.append(RegisterEvents.FINDING_DISMISSED, dismissed);
        }

        findings.render("hello");

        String marker = traceworks.core.register.RegisterAnchors.MALFORMED;
        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.dismissed", 1),
                Map.entry("register.unanchored", 1), Map.entry("report.rejected", 1));
        assertThat(lastWritten().runs()).containsExactly(marker);
        assertThat(lastWritten().actors()).containsExactly(marker);
        assertThat(lastWritten().agents()).containsExactly(marker);
        assertThat(lastWritten().decided()).containsExactly(id);
        assertThat(anchors().toString()).doesNotContain("Mallory");
        lastAnchorIsTheHeadOfTheLog();
        Findings.Verified verified = findings.verified("hello");
        assertThat(verified.anchor()).as("verify computes the same of the lines").startsWith("ok (");
    }

    /**
     * Reproduced 2026-11-21 (the actor {@code Zoë Brontë <zoe@example.org>} was {@code (malformed)} in the
     * ledger): the actor of a decision stands in its anchor as the home names it, and verify computes the same.
     */
    @Test
    void anActorWithSpacesAndLettersBeyondAscii_standsInTheAnchorAsItIs() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = new Findings(fixture.registers, fixture.clock, "Zoë Brontë <zoe@example.org>",
                fixture.ledger);
        String id = findings.state("hello").findings().getFirst().id();

        findings.dismiss("hello", id, "fine");

        assertThat(lastWritten().actors()).containsExactly("Zoë Brontë <zoe@example.org>");
        assertThat(findings.verified("hello").anchor()).startsWith("ok (");
    }

    // ---- a log that was cut, or replaced: the anchor says what was written, never nothing ----

    /**
     * Reproduced 2026-11-21: a register from before whose last line was cut, then assigned. The ledger anchored
     * the line that is gone at the sequence number the assignment took, and the anchor of the assignment said
     * {@code "lines":{}}. It covers the lines since the last anchor the log still agrees with.
     */
    @Test
    void theAnchorOfAWriteAfterACut_coversTheLinesSinceTheLastAnchorTheLogAgreesWith() {
        fixture.registers.putFromBefore(EarlierRegister.KEY, EarlierRegister.lines(), EarlierRegister.blobs(), null);
        int last = EarlierRegister.lastSeq();
        fixture.ledger.anchorEarlier(EarlierRegister.KEY, last - 1,
                EventChain.sha256Hex(EarlierRegister.lines().get(last - 1)));
        fixture.ledger.anchorEarlier(EarlierRegister.KEY, last, EarlierRegister.head());
        fixture.registers.logFromBefore(EarlierRegister.KEY).dropLast(1);

        fixture.services().findings().assign(EarlierRegister.KEY, "shop");

        RegisterAnchor own = fixture.ledger.registerAnchors(EarlierRegister.KEY, "shop").getLast();
        assertThat(own.seq()).as("the assignment took the place of the line that was cut").isEqualTo(last);
        assertThat(own.written().lines()).containsExactly(Map.entry("register.assigned", 1));
        assertThat(own.written().actors()).containsExactly(AppFixture.ACTOR);
        Findings.Verified verified = fixture.services().findings().verified(EarlierRegister.KEY);
        assertThat(verified.report().problems()).as("the anchor of the line that is gone, and nothing about what"
                + " the anchor of the assignment says").singleElement().asString()
                .startsWith("seq " + last + ": the line is not the one the ledger anchors");
    }

    /** A write that adds no line to a log that was cut back to a line the ledger anchors adds no anchor of no line. */
    @Test
    void aWriteThatAddsNoLine_toALogCutBackToAnAnchoredLine_addsNoAnchorThatSaysNothing() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        findings.dismiss("hello", findings.state("hello").findings().getFirst().id(), "not ours");
        List<RegisterAnchor> before = anchors();
        assertThat(before).hasSize(2);
        fixture.registers.log("hello").dropLast(1);

        fixture.services().findings().render("hello");

        assertThat(anchors()).as("the head is a line the ledger anchors already").isEqualTo(before);
        assertThat(anchors()).noneMatch(anchor -> anchor.written().lines().isEmpty());
    }

    /** A torn tail that the next writer discards is a line of that write, and the anchor counts it. */
    @Test
    void aRepair_isCountedWithTheWriteThatMadeIt() {
        fixture.services().start(request(AUDIT), null, null);
        fixture.registers.tearTail("hello", "{\"seq\":9,\"at\"".getBytes(StandardCharsets.UTF_8));
        Findings findings = fixture.services().findings();

        findings.dismiss("hello", findings.state("hello").findings().getFirst().id(), "not ours");

        assertThat(lastWritten().lines()).containsEntry("register.repaired", 1).containsEntry("finding.dismissed", 1);
        assertThat(lastWritten().decided()).hasSize(1);
    }

    // ---- what a person decides ----

    @Test
    void aDecision_saysTheActor_theFinding_andItsType_andNoRun() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();

        findings.dismiss("hello", id, "not ours");

        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.dismissed", 1));
        assertThat(lastWritten().actors()).containsExactly(AppFixture.ACTOR);
        assertThat(lastWritten().agents()).isEmpty();
        assertThat(lastWritten().decided()).containsExactly(id);
        assertThat(lastWritten().runs()).isEmpty();

        findings.reopen("hello", id, "look again");

        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.reopened", 1));
        assertThat(lastWritten().actors()).containsExactly(AppFixture.ACTOR);
        assertThat(lastWritten().decided()).containsExactly(id);
        assertThat(lastWritten().runs()).isEmpty();

        findings.accept("hello", id, "a risk we take", null);

        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.accepted", 1));
        assertThat(lastWritten().actors()).containsExactly(AppFixture.ACTOR);
        assertThat(lastWritten().agents()).isEmpty();
        assertThat(lastWritten().decided()).containsExactly(id);
        lastAnchorIsTheHeadOfTheLog();
    }

    @Test
    void aDecisionThroughAnAgent_saysTheAgentBesideTheActor() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();

        findings.accept("hello", id, "a risk we take", null, "claude-code");

        // The first decision of an agent is preceded by the line that says the register holds one
        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.accepted", 1),
                Map.entry("register.requires", 1));
        assertThat(lastWritten().actors()).containsExactly(AppFixture.ACTOR);
        assertThat(lastWritten().agents()).containsExactly("claude-code");
        assertThat(lastWritten().decided()).containsExactly(id);
        assertThat(lastWritten().runs()).isEmpty();

        findings.reopen("hello", id, "look again", "codex");

        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.reopened", 1));
        assertThat(lastWritten().agents()).containsExactly("codex");
        assertThat(lastWritten().decided()).containsExactly(id);

        findings.dismiss("hello", id, "not ours", "claude-code");

        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.dismissed", 1));
        assertThat(lastWritten().actors()).containsExactly(AppFixture.ACTOR);
        assertThat(lastWritten().agents()).containsExactly("claude-code");
        assertThat(lastWritten().decided()).containsExactly(id);
    }

    // ---- a register from before registers had projects ----

    private void anEarlierRegister(boolean anchored) {
        fixture.registers.putFromBefore(EarlierRegister.KEY, EarlierRegister.lines(), EarlierRegister.blobs(),
                EarlierRegister.state());
        if (anchored) {
            fixture.ledger.anchorEarlier(EarlierRegister.KEY, EarlierRegister.lastSeq(), EarlierRegister.head());
        }
    }

    /** The anchors an earlier version wrote come before it: the first anchor under the project covers what is new. */
    @Test
    void theAssignmentOfARegisterFromBefore_saysItsOneLineAndWhoAssigned() {
        anEarlierRegister(true);

        fixture.services().findings().assign(EarlierRegister.KEY, "shop");

        List<RegisterAnchor> own = fixture.ledger.registerAnchors(EarlierRegister.KEY, "shop");
        assertThat(own).hasSize(1);
        assertThat(own.getFirst().seq()).isEqualTo(EarlierRegister.lastSeq() + 1);
        assertThat(own.getFirst().written().lines()).containsExactly(Map.entry("register.assigned", 1));
        assertThat(own.getFirst().written().actors()).containsExactly(AppFixture.ACTOR);
        assertThat(own.getFirst().written().runs()).isEmpty();
        assertThat(own.getFirst().written().decided()).isEmpty();
        assertThat(fixture.ledger.earlierRegisterAnchors(EarlierRegister.KEY)).as("as that version left them")
                .containsExactly(new RegisterAnchor(EarlierRegister.lastSeq(), EarlierRegister.head()));
        Findings.Verified verified = fixture.services().findings().verified(EarlierRegister.KEY);
        assertThat(verified.report().problems()).isEmpty();
        assertThat(verified.anchor()).startsWith("ok (");
    }

    /** The first anchor of a register covers all its lines: here the whole log of a register nothing anchored. */
    @Test
    void theFirstAnchorOfARegisterTheLedgerNeverAnchored_coversAllItsLines() {
        anEarlierRegister(false);

        fixture.services().findings().assign(EarlierRegister.KEY, "shop");

        RegisterAnchor.Written written = fixture.ledger.registerAnchors(EarlierRegister.KEY, "shop").getFirst()
                .written();
        int counted = written.lines().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(counted).as("every line of the log, the assignment included")
                .isEqualTo(EarlierRegister.lines().size() + 1);
        assertThat(written.lines()).containsEntry("register.created", 1).containsEntry("register.assigned", 1);
        assertThat(written.actors()).contains(AppFixture.ACTOR);
    }

    // ---- an anchor that failed, and a head that is anchored already ----

    @Test
    void anAnchorAfterOneThatFailed_coversTheLinesOfBothWrites() {
        RunResult first = fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        fixture.ledger.failAnchors(new IllegalStateException("git commit failed with exit code 128"));
        findings.dismiss("hello", id, "not ours");
        assertThat(findings.unanchored()).isPresent();
        assertThat(anchors()).hasSize(1);
        fixture.ledger.failAnchors(null);

        RunResult second = fixture.services().start(request(AUDIT), null, null);

        assertThat(anchors()).hasSize(2);
        // the decision whose anchor failed, the note that its line was found without an anchor, and the report
        assertThat(lastWritten().lines()).containsExactly(Map.entry("finding.dismissed", 1),
                Map.entry("register.unanchored", 1), Map.entry("report.applied", 1));
        assertThat(lastWritten().runs()).containsExactly(second.runId()).doesNotContain(first.runId());
        assertThat(lastWritten().actors()).containsExactly(AppFixture.ACTOR);
        assertThat(lastWritten().decided()).containsExactly(id);
        lastAnchorIsTheHeadOfTheLog();
    }

    @Test
    void twoWritesThatEndAtTheSameHead_areNotAnchoredTwice() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        List<RegisterAnchor> before = anchors();

        findings.render("hello");
        findings.render("hello");

        assertThat(findings.unanchored()).isEmpty();
        assertThat(anchors()).isEqualTo(before);
        assertThat(lines().stream().map(line -> new String(line, StandardCharsets.UTF_8)))
                .noneMatch(line -> line.contains(RegisterEvents.UNANCHORED));
    }

    // ---- verify derives the same from the log again, and reports every difference ----

    private static final String NAME = traceworks.core.port.Ledger.registerName("hello", AppFixture.PROJECT);

    /** Puts another anchor line in the place of the one at {@code index}: the same seq and head, and what {@code says}. */
    private void theAnchorSays(int index, RegisterAnchor.Written says) {
        RegisterAnchor held = anchors().get(index);
        fixture.ledger.replaceAnchor("hello", AppFixture.PROJECT, index,
                new RegisterAnchor(held.seq(), held.head(), says));
    }

    /**
     * The one problem verify reports of the register, which the check of the whole home lists too, followed by the
     * state of the register's log: an acknowledgement there covers that state only.
     */
    private String theOneProblem() {
        Findings.Verified verified = fixture.services().findings().verified("hello");
        assertThat(verified.report().intact()).isFalse();
        assertThat(verified.anchor()).isEqualTo("NOT ok");
        assertThat(verified.chainHolds()).as("the register holds by itself: it is the ledger that says otherwise")
                .isTrue();
        assertThat(verified.report().problems()).hasSize(1);
        String problem = verified.report().problems().getFirst();
        assertThat(fixture.services().ledgerReport().problems()).filteredOn(p -> p.runId().equals(NAME))
                .singleElement().satisfies(listed -> {
                    assertThat(listed.code()).isEqualTo("register-broken");
                    assertThat(listed.text()).startsWith(problem + " (the register's log ends with the line ");
                });
        return problem;
    }

    /** A run's report, then a decision through an agent: two anchors, and verify holds. */
    private String aReportAndADecision() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        findings.dismiss("hello", id, "not ours", "claude-code");
        assertThat(anchors()).hasSize(2);
        assertThat(findings.verified("hello").report().problems()).isEmpty();
        assertThat(fixture.services().ledgerReport().problems()).isEmpty();
        return id;
    }

    @Test
    void verify_findsAChangedCount_andSaysWhichAnchor_whichField_andWhatTheLogSays() {
        aReportAndADecision();
        RegisterAnchor.Written was = anchors().getFirst().written();
        java.util.SortedMap<String, Integer> lines = new java.util.TreeMap<>(was.lines());
        lines.put("finding.new", 2);

        theAnchorSays(0, new RegisterAnchor.Written(lines, was.runs(), was.actors(), was.agents(), was.decided()));

        assertThat(theOneProblem()).startsWith("seq " + anchors().getFirst().seq() + ": the anchor in the ledger says"
                + " lines finding.new 2, register.created 1, register.requires 1, report.applied 1, and the lines it"
                + " covers (seq 0 to " + anchors().getFirst().seq() + ") give finding.new 1, register.created 1,"
                + " register.requires 1, report.applied 1");
    }

    @Test
    void verify_findsATypeThatIsNotInTheLog_andOneTheAnchorLeavesOut() {
        aReportAndADecision();
        RegisterAnchor.Written was = lastWritten();
        java.util.SortedMap<String, Integer> lines = new java.util.TreeMap<>(was.lines());
        lines.remove("register.requires");
        lines.put("finding.accepted", 1);

        theAnchorSays(1, new RegisterAnchor.Written(lines, was.runs(), was.actors(), was.agents(), was.decided()));

        assertThat(theOneProblem()).contains("says lines finding.accepted 1, finding.dismissed 1, and the lines it"
                + " covers").contains("give finding.dismissed 1, register.requires 1");
    }

    @Test
    void verify_findsAChangedRun() {
        aReportAndADecision();
        RegisterAnchor.Written was = anchors().getFirst().written();
        String run = was.runs().getFirst();

        theAnchorSays(0, new RegisterAnchor.Written(was.lines(), List.of("another-run"), was.actors(), was.agents(),
                was.decided()));

        assertThat(theOneProblem()).startsWith("seq " + anchors().getFirst().seq() + ": the anchor in the ledger says"
                + " run another-run, and the lines it covers (seq 0 to " + anchors().getFirst().seq() + ") give run "
                + run);

        theAnchorSays(0, new RegisterAnchor.Written(was.lines(), List.of(), was.actors(), was.agents(),
                was.decided()));

        assertThat(theOneProblem()).contains("says run none, and the lines it covers").contains("give run " + run);
    }

    @Test
    void verify_findsAChangedActor_agent_andFinding() {
        String id = aReportAndADecision();
        RegisterAnchor.Written was = lastWritten();
        String covers = "the lines it covers (seq " + (anchors().getFirst().seq() + 1) + " to "
                + anchors().getLast().seq() + ")";

        theAnchorSays(1, new RegisterAnchor.Written(was.lines(), was.runs(), List.of("mallory@box"), was.agents(),
                was.decided()));
        assertThat(theOneProblem()).startsWith("seq " + anchors().getLast().seq() + ": the anchor in the ledger says"
                + " actor mallory@box, and " + covers + " give actor " + AppFixture.ACTOR);

        theAnchorSays(1, new RegisterAnchor.Written(was.lines(), was.runs(), was.actors(), List.of(), was.decided()));
        assertThat(theOneProblem()).contains("says agent none, and " + covers + " give agent claude-code");

        theAnchorSays(1, new RegisterAnchor.Written(was.lines(), was.runs(), was.actors(), was.agents(),
                List.of("f".repeat(64))));
        assertThat(theOneProblem()).contains("says decided " + "f".repeat(64) + ", and " + covers + " give decided "
                + id);

        theAnchorSays(1, new RegisterAnchor.Written(was.lines(), List.of("a-run"), was.actors(), was.agents(),
                was.decided()));
        assertThat(theOneProblem()).contains("says run a-run, and " + covers + " give run none");
    }

    /** Every difference is a problem of its own: an anchor that is wrong in three fields is reported three times. */
    @Test
    void verify_reportsEachDifference() {
        aReportAndADecision();
        RegisterAnchor.Written was = lastWritten();

        theAnchorSays(1, new RegisterAnchor.Written(was.lines(), List.of("a-run"), List.of("mallory@box"),
                was.agents(), List.of()));

        List<String> problems = fixture.services().findings().verified("hello").report().problems();
        assertThat(problems).hasSize(3);
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("says run a-run"));
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("says actor mallory@box"));
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("says decided none"));
    }

    /** A line with seq and head alone among the anchors of a project is not what this version writes. */
    @Test
    void verify_findsAnAnchorOfAProjectThatDoesNotSayWhatWasWritten() {
        aReportAndADecision();

        theAnchorSays(1, null);

        assertThat(theOneProblem()).startsWith("seq " + anchors().getLast().seq() + ": the anchor in the ledger does"
                + " not say what was written (it has no lines)").contains("this version writes no such anchor");
    }
}
