package traceworks.core.app;

import traceworks.core.port.RegisterAnchor;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.register.RegisterEvents;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every write of a register anchors its head in the ledger, and {@code findings <key> verify} compares: a changed
 * last line and a cut end are found, which the register's own chain cannot find.
 */
class RegisterAnchorTest {

    private static final Path AUDIT = Path.of("/pkgs/audit");

    private final AppFixture fixture = new AppFixture();

    RegisterAnchorTest() {
        fixture.packages.put(AUDIT, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"MEDIUM\","
                + "\"title\":\"Big A.java\"}]}\n-->\n");
    }

    private List<byte[]> lines() {
        try (RegisterStore.Opened opened = fixture.registers.open("hello")) {
            return opened.events().readAll();
        }
    }

    private RegisterAnchor headOfTheLog() {
        List<byte[]> lines = lines();
        return new RegisterAnchor(lines.size() - 1, EventChain.sha256Hex(lines.getLast()));
    }

    @Test
    void aRun_anchorsTheHeadOfItsRegister_andSoDoesADecision() {
        fixture.services().start(request(AUDIT), null, null);

        assertThat(fixture.ledger.registerAnchors("hello")).last().isEqualTo(headOfTheLog());

        Findings findings = fixture.services().findings();
        findings.dismiss("hello", findings.state("hello").findings().getFirst().id(), "not ours");

        assertThat(fixture.ledger.registerAnchors("hello")).hasSize(2).last().isEqualTo(headOfTheLog());
        assertThat(findings.unanchored()).isEmpty();
        Findings.Verified verified = findings.verified("hello");
        assertThat(verified.report().problems()).isEmpty();
        assertThat(verified.anchor()).startsWith("ok").contains("seq " + (lines().size() - 1));
    }

    /** The last line of a fresh register is a finding.new, which names no state: the chain alone lets it change. */
    @Test
    void aChangedLastLine_isFound() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String last = new String(lines().getLast(), StandardCharsets.UTF_8);
        assertThat(last).contains(RegisterEvents.FINDING_NEW).contains("Big A.java");

        fixture.registers.log("hello").replaceLast(last.replace("Big A.java", "Fine A.java")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(findings.verify("hello").intact()).isFalse();
        assertThat(findings.verify("hello").problems()).singleElement().asString()
                .contains("seq " + (lines().size() - 1)).contains("ledger").contains("changed");
    }

    @Test
    void aCutEnd_isFound_alsoAfterARender_andAfterALaterWrite() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        findings.dismiss("hello", id, "not ours");
        int before = lines().size();

        fixture.registers.log("hello").dropLast(1);
        findings.render("hello");

        assertThat(findings.verify("hello").intact()).isFalse();
        assertThat(findings.verify("hello").problems()).singleElement().asString()
                .contains("seq " + (before - 1)).contains("ledger").contains("cut");

        // A write after the cut puts another line where the anchored one was: the ledger still holds the old anchor
        findings.accept("hello", id, "a risk we take", null);

        assertThat(lines()).hasSize(before);
        assertThat(findings.verify("hello").intact()).isFalse();
        assertThat(findings.verify("hello").problems()).singleElement().asString().contains("seq " + (before - 1));
    }

    /** A register from before anchors existed: verify says so and holds, and the next write anchors it. */
    @Test
    void aRegisterWithoutAnAnchor_verifies_saysSo_andTheNextWriteAnchorsIt() {
        fixture.services().start(request(AUDIT), null, null);
        fixture.ledger.forgetAnchors("hello");
        Findings findings = fixture.services().findings();

        Findings.Verified verified = findings.verified("hello");

        assertThat(verified.report().intact()).isTrue();
        assertThat(verified.anchor()).startsWith("none").contains("the next write anchors it");

        findings.dismiss("hello", findings.state("hello").findings().getFirst().id(), "not ours");

        assertThat(fixture.ledger.registerAnchors("hello")).containsExactly(headOfTheLog());
        assertThat(findings.verified("hello").anchor()).startsWith("ok");
    }

    /** The register's write is kept when the ledger cannot take the anchor; the summary and verify say so. */
    @Test
    void aLedgerThatCannotBeWritten_keepsTheWrite_andSaysSo() {
        fixture.services().start(request(AUDIT), null, null);
        int anchored = lines().size() - 1;
        fixture.ledger.failAnchors(new IllegalStateException("git commit failed with exit code 128"));
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().applied()).isTrue();
        assertThat(result.register().summary()).contains("1 resolved")
                .contains("not anchored in the ledger (git commit failed with exit code 128)");
        Findings findings = fixture.services().findings();
        assertThat(findings.state("hello").findings().getFirst().status().name()).isEqualTo("RESOLVED");
        Findings.Verified verified = findings.verified("hello");
        assertThat(verified.report().intact()).isTrue();
        assertThat(verified.anchor()).startsWith("ok up to seq " + anchored)
                .contains("seq " + (anchored + 1) + " to " + (lines().size() - 1) + " are not anchored");

        // A decision while the ledger still fails is recorded, and says that it is not anchored
        String id = findings.state("hello").findings().getFirst().id();
        findings.reopen("hello", id, "look again");
        assertThat(findings.unanchored()).hasValueSatisfying(why -> assertThat(why).contains("git commit failed"));
        assertThat(findings.state("hello").finding(id).status().name()).isEqualTo("OPEN");

        // With the ledger back, a render anchors what is there
        fixture.ledger.failAnchors(null);
        findings.render("hello");
        assertThat(findings.unanchored()).isEmpty();
        assertThat(fixture.ledger.registerAnchors("hello")).last().isEqualTo(headOfTheLog());
        assertThat(findings.verified("hello").anchor()).startsWith("ok (");
    }

    @Test
    void aRejectedReport_isAnchoredToo() {
        fixture.commands.on("./report", "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":false,"
                + "\"findings\":[]}\n-->\n");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().applied()).isFalse();
        assertThat(fixture.ledger.registerAnchors("hello")).containsExactly(headOfTheLog());
    }

    @Test
    void theHooksLine_isAnchoredToo() {
        fixture.settings = new OperatorSettings(true, null, List.of(), null, null,
                OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB, java.util.Map.of(),
                OperatorSettings.defaultRunOutputBudgetMib(OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB), null,
                new OperatorSettings.Hooks(List.of("/opt/hooks/todos.sh"), 7));

        fixture.services().start(request(AUDIT), null, null);

        assertThat(new String(lines().getLast(), StandardCharsets.UTF_8)).contains(RegisterEvents.HOOK_RAN);
        assertThat(fixture.ledger.registerAnchors("hello")).hasSize(2).last().isEqualTo(headOfTheLog());
    }

    /** A ledger that cannot be read is not a register that holds: verify fails and names the reason. */
    @Test
    void aLedgerThatCannotBeRead_failsVerify() {
        traceworks.core.testing.InMemoryRegisterStore store = fixture.registers;
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = new Findings(store, fixture.clock, "tester", new traceworks.core.port.Ledger() {
            @Override
            public void prepare() {
            }

            @Override
            public String record(String runId, String status, byte[] manifestJson) {
                return "x";
            }

            @Override
            public java.util.Optional<byte[]> find(String runId) {
                return java.util.Optional.empty();
            }

            @Override
            public String recordStart(String runId, byte[] startJson) {
                return fixture.ledger.recordStart(runId, startJson);
            }

            @Override
            public java.util.Optional<byte[]> findStart(String runId) {
                return fixture.ledger.findStart(runId);
            }

            @Override
            public traceworks.core.port.LedgerRuns runs() {
                return fixture.ledger.runs();
            }

            @Override
            public void acknowledge(String runId, byte[] line) {
                fixture.ledger.acknowledge(runId, line);
            }

            @Override
            public List<RegisterAnchor> registerAnchors(String key) {
                throw new IllegalStateException("git show failed: no git");
            }
        });

        assertThat(findings.verify("hello").intact()).isFalse();
        assertThat(findings.verify("hello").problems()).singleElement().asString()
                .contains("the ledger could not be read").contains("no git");
    }

    /** A ledger that hands the anchors on, and lets a writer in at the moment it is asked for them. */
    private traceworks.core.port.Ledger askedWhile(Runnable writer) {
        boolean[] done = {false};
        return new traceworks.core.port.Ledger() {
            @Override
            public void prepare() {
            }

            @Override
            public String record(String runId, String status, byte[] manifestJson) {
                return fixture.ledger.record(runId, status, manifestJson);
            }

            @Override
            public java.util.Optional<byte[]> find(String runId) {
                return fixture.ledger.find(runId);
            }

            @Override
            public String recordStart(String runId, byte[] startJson) {
                return fixture.ledger.recordStart(runId, startJson);
            }

            @Override
            public java.util.Optional<byte[]> findStart(String runId) {
                return fixture.ledger.findStart(runId);
            }

            @Override
            public traceworks.core.port.LedgerRuns runs() {
                return fixture.ledger.runs();
            }

            @Override
            public void acknowledge(String runId, byte[] line) {
                fixture.ledger.acknowledge(runId, line);
            }

            @Override
            public void anchorRegister(String key, int seq, String head) {
                fixture.ledger.anchorRegister(key, seq, head);
            }

            @Override
            public List<RegisterAnchor> registerAnchors(String key) {
                if (!done[0]) {
                    done[0] = true;
                    writer.run();
                }
                return fixture.ledger.registerAnchors(key);
            }
        };
    }

    /**
     * A reader takes no lock. A register is written before its anchor, so the anchors are read first and the lines
     * after: a write in between then leaves lines the anchors read do not cover, never an anchor beyond the lines
     * read, which would read as a cut end.
     */
    @Test
    void verify_whileAWriterIsAtWork_reportsNoCutEnd() {
        fixture.services().start(request(AUDIT), null, null);
        Findings writer = fixture.services().findings();
        String id = writer.state("hello").findings().getFirst().id();
        Findings reader = new Findings(fixture.registers, fixture.clock, "tester",
                askedWhile(() -> writer.dismiss("hello", id, "not ours")));

        Findings.Verified verified = reader.verified("hello");

        assertThat(verified.report().problems()).isEmpty();
        assertThat(verified.report().intact()).isTrue();
    }

    /** A line appended to the log the way the register chains its own, as someone who can write the folder could. */
    private void appendForgedLine() {
        List<byte[]> lines = lines();
        String line = "{\"seq\":" + lines.size() + ",\"at\":\"2026-11-14T00:00:00.000Z\",\"type\":\"hook.ran\","
                + "\"register\":\"hello\",\"prev\":\"" + EventChain.sha256Hex(lines.getLast()) + "\",\"run_id\":\"x\","
                + "\"exit_code\":0,\"duration_ms\":1,\"stderr_tail\":\"\"}"
                + (lines.getLast()[lines.getLast().length - 1] == '\n' ? "\n" : "");
        fixture.registers.log("hello").append(line.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Lines after the last anchor are unproven: a writer that was cut off before the anchor leaves them, and so does
     * someone who appends to the log. verify holds and says so; the next write goes on and anchors, but it records
     * in the log which lines it found without an anchor, and says so.
     */
    @Test
    void linesAppendedAfterTheLastAnchor_areNamedByVerify_andRecordedAndReportedByTheNextWrite() {
        fixture.services().start(request(AUDIT), null, null);
        int anchored = lines().size() - 1;
        appendForgedLine();
        Findings findings = fixture.services().findings();

        Findings.Verified before = findings.verified("hello");
        assertThat(before.report().intact()).isTrue();
        assertThat(before.anchor()).startsWith("ok up to seq " + anchored + "; seq " + (anchored + 1)
                + " is not anchored").contains("unproven");

        findings.dismiss("hello", findings.state("hello").findings().getFirst().id(), "not ours");

        assertThat(findings.anchorNote()).hasValueSatisfying(note -> assertThat(note)
                .contains("seq " + (anchored + 1) + " was not anchored before this write"));
        List<traceworks.core.register.RegisterEvent> events = findings.events("hello");
        traceworks.core.register.RegisterEvent found = events.get(anchored + 2);
        assertThat(found.type()).isEqualTo(RegisterEvents.UNANCHORED);
        assertThat(found.fields().get("first_seq").asInt()).isEqualTo(anchored + 1);
        assertThat(found.fields().get("last_seq").asInt()).isEqualTo(anchored + 1);
        assertThat(events.getLast().type()).isEqualTo(RegisterEvents.FINDING_DISMISSED);
        assertThat(findings.verified("hello").anchor()).startsWith("ok (");
        assertThat(findings.verify("hello").problems()).isEmpty();

        // The next write finds every line anchored and says nothing
        findings.reopen("hello", findings.state("hello").findings().getFirst().id(), "again");
        assertThat(findings.anchorNote()).isEmpty();
    }

    /** A run says it in its summary line of the register, and so does a render. */
    @Test
    void aRunAndARender_thatFindUnanchoredLines_recordAndSayIt() {
        fixture.services().start(request(AUDIT), null, null);
        int anchored = lines().size() - 1;
        appendForgedLine();

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().applied()).isTrue();
        assertThat(result.register().summary()).contains("seq " + (anchored + 1) + " was not anchored before this write");
        assertThat(fixture.services().findings().events("hello")).extracting(e -> e.type())
                .contains(RegisterEvents.UNANCHORED);

        appendForgedLine();
        Findings findings = fixture.services().findings();
        findings.render("hello");
        assertThat(findings.anchorNote()).hasValueSatisfying(note -> assertThat(note).contains("was not anchored"));
        assertThat(findings.verified("hello").anchor()).startsWith("ok (");
    }

    /** A write that finds the log not matching its anchors says so too; verify names the place. */
    @Test
    void aWriteThatFindsACutLog_saysThatTheRegisterDoesNotMatchItsAnchors() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        findings.dismiss("hello", id, "not ours");
        fixture.registers.log("hello").dropLast(1);

        findings.accept("hello", id, "a risk we take", null);

        assertThat(findings.anchorNote()).hasValueSatisfying(note -> assertThat(note)
                .contains("does not match its anchors in the ledger").contains("findings hello verify"));
    }

    /** Whether the chain holds is said by itself, whatever the anchors say and whatever a problem's text holds. */
    @Test
    void verified_saysWhetherTheChainHoldsByItself() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        assertThat(findings.verified("hello").chainHolds()).isTrue();

        String last = new String(lines().getLast(), StandardCharsets.UTF_8);
        fixture.registers.log("hello").replaceLast(last.replace("Big A.java", "Fine A.java")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(findings.verified("hello").chainHolds()).as("only the anchor finds it").isTrue();
        assertThat(findings.verified("hello").report().intact()).isFalse();

        fixture.registers.log("hello").replaceLast(last.replace("\"register\":\"hello\"", "\"register\":\"ledger\"")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(findings.verified("hello").chainHolds()).isFalse();
    }

    /**
     * One line per gap. While the ledger reads and cannot be written, every write finds the same lines without an
     * anchor: the first that finds them records the gap, the later ones say that it is still open and add no line.
     * A hook's line right after a report whose anchor just failed records none either: that failure was reported.
     */
    @Test
    void aGapThatStaysOpen_isRecordedOnce_howeverManyWritesFindIt() {
        fixture.services().start(request(AUDIT), null, null);
        int anchored = lines().size() - 1;
        fixture.ledger.failAnchors(new IllegalStateException("git commit failed"));
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();

        findings.dismiss("hello", id, "not ours");
        assertThat(findings.anchorNote()).as("nothing was unanchored before the first write").isEmpty();
        findings.reopen("hello", id, "again");
        assertThat(findings.anchorNote()).hasValueSatisfying(note -> assertThat(note)
                .contains("seq " + (anchored + 1) + " was not anchored before this write"));
        for (int i = 0; i < 3; i++) {
            findings.render("hello");
            assertThat(findings.anchorNote()).hasValueSatisfying(note -> assertThat(note)
                    .contains("still not anchored").contains("recorded at seq " + (anchored + 2))
                    .doesNotContain("taken unproven"));
        }

        assertThat(findings.events("hello")).filteredOn(e -> e.type().equals(RegisterEvents.UNANCHORED)).hasSize(1);

        // Once the ledger takes an anchor the gap is closed, and a later one is a new gap with a line of its own
        fixture.ledger.failAnchors(null);
        findings.render("hello");
        findings.render("hello");
        assertThat(findings.anchorNote()).isEmpty();
        appendForgedLine();
        findings.render("hello");
        assertThat(findings.events("hello")).filteredOn(e -> e.type().equals(RegisterEvents.UNANCHORED)).hasSize(2);
    }

    @Test
    void theHooksLine_afterAReportWhoseAnchorJustFailed_recordsNoGap() {
        fixture.settings = new OperatorSettings(true, null, List.of(), null, null,
                OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB, java.util.Map.of(),
                OperatorSettings.defaultRunOutputBudgetMib(OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB), null,
                new OperatorSettings.Hooks(List.of("/opt/hooks/todos.sh"), 7));
        fixture.services().start(request(AUDIT), null, null);
        fixture.ledger.failAnchors(new IllegalStateException("git commit failed"));
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().hookExitCode()).as("the hook ran: a finding was resolved").isNotNull();
        assertThat(result.register().summary()).contains("not anchored in the ledger (git commit failed)")
                .doesNotContain("taken unproven");
        assertThat(fixture.services().findings().events("hello")).extracting(e -> e.type())
                .doesNotContain(RegisterEvents.UNANCHORED);
    }
}
