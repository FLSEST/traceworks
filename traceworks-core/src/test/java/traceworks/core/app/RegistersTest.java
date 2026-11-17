package traceworks.core.app;

import traceworks.core.compat.EarlierNames;
import traceworks.core.model.RunStatus;
import traceworks.core.port.RegisterStore;
import traceworks.core.register.Change;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import traceworks.core.app.OperatorSettings;

import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

/** A completed audit run feeds its register at its end; the result says what changed. */
class RegistersTest {

    static final Path AUDIT = Path.of("/pkgs/audit");
    static final Path OTHER = Path.of("/pkgs/other");

    private final AppFixture fixture = new AppFixture();

    RegistersTest() {
        fixture.packages.put(AUDIT, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "measure", "config": {"command": "./measure"}},
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.packages.put(OTHER, """
                {"id": "wf-other", "name": "Other", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.packages.put(READER, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "use", "config": {"values": {"KNOWN": "{{ audit_findings }}",
                      "REPORT": "{{ previous_report }}"}, "command": "./use \\"$KNOWN\\" \\"$REPORT\\""}},
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("./measure", "## FACT SHEET hello\nFACT files: 3\n");
        fixture.packages.put(ITEM_READER, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "facts", "config": {"values": {"ITEMS": "{{ audit_items }}"},
                      "command": "./facts \\"$ITEMS\\""}},
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("./facts", "listed");
        fixture.commands.on("./use", "used");
        report("A.java", "B.java");
    }

    static final Path READER = Path.of("/pkgs/reader");
    static final Path ITEM_READER = Path.of("/pkgs/item-reader");
    static final Path CHECKED = Path.of("/pkgs/checked");

    private void report(String... subjects) {
        StringBuilder findings = new StringBuilder();
        for (String subject : subjects) {
            if (!findings.isEmpty()) findings.append(",");
            findings.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"Big ").append(subject)
                    .append("\",\"evidence\":\"files 3\"}");
        }
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[" + findings + "]}\n-->\n");
    }

    private RegisterState state() {
        try (RegisterStore.Opened opened = fixture.registers.open("hello")) {
            return traceworks.core.register.RegisterReplay.replay("hello",
                    new traceworks.core.register.RegisterLog(opened, fixture.clock).read(),
                    opened.blobs());
        }
    }

    private List<RegisterEvent> events() {
        try (RegisterStore.Opened opened = fixture.registers.open("hello")) {
            return new traceworks.core.register.RegisterLog(opened, fixture.clock).read();
        }
    }

    @Test
    void aCompletedRun_feedsTheRegister_andTheResultSaysWhatChanged() {
        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register()).isNotNull();
        assertThat(result.register().key()).isEqualTo("hello");
        assertThat(result.register().applied()).isTrue();
        assertThat(result.register().counts()).containsEntry(Change.Kind.NEW, 2);
        RegisterState state = state();
        assertThat(state.workflowId()).isEqualTo("wf-audit");
        assertThat(state.category()).isEqualTo("example");
        assertThat(state.findings()).hasSize(2).allSatisfy(f -> assertThat(f.status()).isEqualTo(FindingStatus.OPEN));
        assertThat(state.lastReport().runId()).isEqualTo(result.runId());
        assertThat(events()).extracting(RegisterEvent::type).containsExactly(RegisterEvents.CREATED,
                RegisterEvents.REQUIRES, RegisterEvents.REPORT_APPLIED, RegisterEvents.FINDING_NEW,
                RegisterEvents.FINDING_NEW);
        assertThat(events().get(2).text("run_head")).hasSize(64);
        assertThat(fixture.registers.stateFile("hello")).isNotNull();
    }

    /**
     * A report whose envelope has the earlier marker feeds the register like one with the marker of today: applied,
     * the same findings, and the next run with today's marker sees them as known.
     */
    @Test
    void aReportWithTheEarlierMarker_isAppliedLikeOneWithTheMarkerOfToday() {
        fixture.commands.on("./report", "# Report\n\n<!-- " + EarlierNames.AUDIT_ENVELOPE_WORD
                + ":audit-result:v1\n{\"snapshotComplete\":true,\"findings\":["
                + "{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"MEDIUM\",\"title\":\"Big A.java\","
                + "\"evidence\":\"files 3\"},"
                + "{\"ruleKey\":\"size\",\"subject\":\"B.java\",\"severity\":\"MEDIUM\",\"title\":\"Big B.java\","
                + "\"evidence\":\"files 3\"}]}\n-->\n");

        RunResult earlier = fixture.services().start(request(AUDIT), null, null);

        assertThat(earlier.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(earlier.register().applied()).isTrue();
        assertThat(earlier.register().counts()).containsEntry(Change.Kind.NEW, 2);
        assertThat(state().findings()).extracting(f -> f.subject() + ":" + f.status())
                .containsExactlyInAnyOrder("A.java:OPEN", "B.java:OPEN");
        assertThat(events()).extracting(RegisterEvent::type).containsExactly(RegisterEvents.CREATED,
                RegisterEvents.REQUIRES, RegisterEvents.REPORT_APPLIED, RegisterEvents.FINDING_NEW,
                RegisterEvents.FINDING_NEW);

        report("A.java", "B.java");
        RunResult today = fixture.services().start(request(AUDIT), null, null);

        assertThat(today.register().counts()).containsEntry(Change.Kind.SEEN, 2)
                .doesNotContainKey(Change.Kind.NEW);
    }

    @Test
    void aSecondRun_resolvesWhatIsAbsent_andSeesTheRest() {
        fixture.services().start(request(AUDIT), null, null);
        report("B.java", "C.java");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().counts()).containsEntry(Change.Kind.NEW, 1).containsEntry(Change.Kind.SEEN, 1)
                .containsEntry(Change.Kind.RESOLVED, 1);
        assertThat(state().findings()).extracting(f -> f.subject() + ":" + f.status())
                .containsExactlyInAnyOrder("A.java:RESOLVED", "B.java:OPEN", "C.java:OPEN");
    }

    @Test
    void aThirdRun_reopensAResolvedFinding_withARegression() {
        fixture.services().start(request(AUDIT), null, null);
        report("B.java");
        fixture.services().start(request(AUDIT), null, null);
        report("A.java", "B.java");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().counts()).containsEntry(Change.Kind.REOPENED, 1);
        assertThat(state().findings()).filteredOn(f -> f.subject().equals("A.java")).singleElement()
                .satisfies(f -> {
                    assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
                    assertThat(f.regressions()).isEqualTo(1);
                });
    }

    @Test
    void aRejectedReport_changesNoFinding() {
        fixture.services().start(request(AUDIT), null, null);
        fixture.commands.on("./report", "# Report\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":false,"
                + "\"findings\":[]}\n-->\n");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("snapshotComplete");
        assertThat(state().findings()).hasSize(2).allSatisfy(f -> assertThat(f.status()).isEqualTo(FindingStatus.OPEN));
        assertThat(state().lastReport().valid()).isFalse();
        assertThat(events().getLast().type()).isEqualTo(RegisterEvents.REPORT_REJECTED);
    }

    @Test
    void aFailedRun_isRejected_withItsStatus() {
        fixture.commands.on("./report", new traceworks.core.port.CommandRunner.CommandResult(3, "",
                "boom", 1, false));

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("FAILED");
        assertThat(state().findings()).isEmpty();
    }

    /** A checked step that failed leaves the run COMPLETED_WITH_ERRORS, and its report still counts. */
    @Test
    void aRunThatCompletedWithErrors_feedsTheRegister() {
        fixture.packages.put(CHECKED, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "check", "config": {"command": "./check", "checked_command": true,
                     "on_failure": "continue"}},
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("set -e -o pipefail\n./check", new traceworks.core.port.CommandRunner.CommandResult(1,
                "## FACT SHEET hello\nFACT files: 3\n", "red", 1, false));

        RunResult result = fixture.services().start(request(CHECKED), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
        assertThat(result.register().applied()).isTrue();
        assertThat(state().findings()).hasSize(2);
    }

    @Test
    void aRunAppliedTwice_isAppliedOnce() {
        RunResult first = fixture.services().start(request(AUDIT), null, null);
        int lines = events().size();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services()
                .resume(AppFixture.resumeOf(first.runId()), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("a finished run cannot be resumed");

        assertThat(events()).as("the completion fed nothing a second time").hasSize(lines);
    }

    /** A run whose report changed nothing leaves only its report.applied: a completion must find that one too. */
    @Test
    void aRunWhoseReportChangedNothing_isNotAppliedAgain_whenItsFinalisationIsCompleted() {
        fixture.services().start(request(AUDIT), null, null);
        RunResult second = fixture.services().start(request(AUDIT), null, null);
        assertThat(second.register().counts()).as("the same findings again").doesNotContainKey(Change.Kind.NEW);
        int lines = events().size();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services()
                .resume(AppFixture.resumeOf(second.runId()), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("a finished run cannot be resumed");

        assertThat(events()).as("the completion fed nothing a second time").hasSize(lines);
    }

    @Test
    void aRejectedRun_isNotRejectedAgain_whenItsFinalisationIsCompleted() {
        fixture.commands.on("./report", new traceworks.core.port.CommandRunner.CommandResult(3, "", "boom", 1, false));
        RunResult failed = fixture.services().start(request(AUDIT), null, null);
        assertThat(events().getLast().type()).isEqualTo(RegisterEvents.REPORT_REJECTED);
        int lines = events().size();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services()
                .resume(AppFixture.resumeOf(failed.runId()), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("a finished run cannot be resumed");

        assertThat(events()).as("the completion rejected nothing a second time").hasSize(lines);
    }

    /**
     * The start check finds the register free, and while the run runs another workflow's run takes it: at its end the
     * run's report is refused all the same, and the register stays the other workflow's.
     */
    @Test
    void aReportOfAnotherWorkflow_isRejectedAtTheEnd_whenTheRegisterWasTakenWhileItRan() {
        Path racing = Path.of("/pkgs/racing");
        fixture.packages.put(racing, """
                {"id": "wf-other", "name": "Other", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "meanwhile", "config": {"command": "./meanwhile"}},
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("./meanwhile", req -> {
            assertThat(fixture.services().start(request(AUDIT), null, null).register().applied()).isTrue();
            return traceworks.core.testing.FakeCommandRunner.ok("done");
        });

        RunResult result = fixture.services().start(request(racing), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("belongs to the workflow wf-audit, not to wf-other");
        assertThat(events().getLast().type()).isEqualTo(RegisterEvents.REPORT_REJECTED);
        assertThat(state().workflowId()).isEqualTo("wf-audit");
    }

    /** Review Focus 3: a run of another workflow under the same key must not write the register. */
    @Test
    void anotherWorkflowId_isRefusedAtTheStart_andWritesNothing() {
        fixture.services().start(request(AUDIT), null, null);
        int lines = events().size();
        withHook();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services().start(request(OTHER), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("hello")
                .hasMessageContaining("wf-audit").hasMessageContaining("wf-other");

        assertThat(events()).as("no line, not even a rejection").hasSize(lines);
        assertThat(fixture.hooks.calls()).isEmpty();
        assertThat(fixture.store.runIds()).as("refused before the run exists").hasSize(1);
        assertThat(state().workflowId()).isEqualTo("wf-audit");
    }

    /** A catalog with a fixed subject: the log replays with the catalog it was applied with, so verify holds. */
    @Test
    void aCatalogWithAFixedSubject_replaysWithIt_soVerifyHolds_andASecondRunChangesNothing() {
        fixture.packages.catalog(AUDIT, "{\"format\":1,\"rules\":{\"size\":{\"subject\":\"the sources\"}}}");
        report("A.java");

        RunResult first = fixture.services().start(request(AUDIT), null, null);
        RunResult second = fixture.services().start(request(AUDIT), null, null);

        assertThat(first.register().counts()).containsEntry(Change.Kind.NEW, 1);
        assertThat(second.register().counts()).containsEntry(Change.Kind.SEEN, 1).doesNotContainKey(Change.Kind.NEW)
                .doesNotContainKey(Change.Kind.RESOLVED);
        assertThat(state().findings()).singleElement().satisfies(f -> assertThat(f.subject()).isEqualTo("the sources"));
        assertThat(fixture.services().findings().verify("hello").intact()).isTrue();
        assertThat(events().get(2).text("catalog_blob")).hasSize(64);
    }

    /** A rule that left the catalog retires an accepted finding, live and in the replay alike. */
    @Test
    void aRuleThatLeftTheCatalog_retiresAnAcceptedFinding_andVerifyHolds() {
        fixture.packages.catalog(AUDIT, "{\"format\":1,\"rules\":{\"size\":{},\"other\":{}}}");
        fixture.services().start(request(AUDIT), null, null);
        fixture.services().findings().accept("hello", state().findings().getFirst().id(), "fine", null);
        fixture.packages.catalog(AUDIT, "{\"format\":1,\"rules\":{\"other\":{}}}");
        fixture.commands.on("./report",
                "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[]}\n-->\n");

        fixture.services().start(request(AUDIT), null, null);

        assertThat(state().findings()).allSatisfy(f -> assertThat(f.status()).isEqualTo(FindingStatus.RESOLVED));
        assertThat(fixture.services().findings().verify("hello").intact()).isTrue();
    }

    /** A register whose log a crash damaged refuses the next run before it exists, and names the way to check. */
    @Test
    void aDamagedRegister_refusesTheRun_beforeItExists() {
        fixture.services().start(request(AUDIT), null, null);
        try (RegisterStore.Opened opened = fixture.registers.openForWrite("hello")) {
            opened.events().append("{\"seq\":9,\"garbage\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services().start(request(AUDIT), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("register hello")
                .hasMessageContaining("findings hello verify");

        assertThat(fixture.store.runIds()).hasSize(1);
    }

    /** A torn tail of the log, left by a killed writer, is cut and recorded before the next line. */
    @Test
    void aTornTail_isCutAndRecorded_beforeTheNextLine() {
        fixture.services().start(request(AUDIT), null, null);
        fixture.registers.tearTail("hello", "{\"seq\":4,\"at\":\"2026".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().applied()).isTrue();
        assertThat(events()).extracting(RegisterEvent::type).contains("register.repaired");
        assertThat(events().stream().filter(e -> e.type().equals("register.repaired")).findFirst().orElseThrow()
                .fields().path("discarded_bytes").asInt()).isEqualTo(19);
        assertThat(fixture.services().findings().verify("hello").intact()).isTrue();
    }

    @Test
    void aRunWithoutAnAuditBlock_feedsNothing() {
        RunResult result = fixture.services().start(request(AppFixture.BUILD), null, null);

        assertThat(result.register()).isNull();
        assertThat(fixture.registers.keys()).isEmpty();
    }

    @Test
    void todoItemLines_feedWhenThereIsNoEnvelope() {
        fixture.commands.on("./report", "Findings:\ntodo_item: Split the big class | HIGH | arch | it does two things\n");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().applied()).isTrue();
        assertThat(state().findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleKey()).isEqualTo("todo-item");
            assertThat(f.subject()).isEqualTo("Split the big class");
        });
    }

    @Test
    void aRegisterFailure_doesNotChangeTheRunsExitCode() {
        fixture.registers.failWrites(new IllegalStateException("disk gone"));

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("disk gone");
        assertThat(fixture.store.runIds()).hasSize(1);
    }

    /** A register that could not take the report at the run's end takes it when the run's finalisation is completed. */
    @Test
    void aRunWhoseRegisterCouldNotBeWritten_feedsIt_whenItsFinalisationIsCompleted() {
        fixture.registers.failWrites(new IllegalStateException("disk gone"));
        RunResult result = fixture.services().start(request(AUDIT), null, null);
        assertThat(result.register().applied()).isFalse();
        fixture.registers.failWrites(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services()
                .resume(AppFixture.resumeOf(result.runId()), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("a finished run cannot be resumed");

        assertThat(state().findings()).hasSize(2);
    }

    /**
     * The state of a register is replayed from its log and the blobs its lines name. A blob that holds other content
     * than its name says is not what the line pointed to: nothing is decided on it and no report is applied to it,
     * and the refusal names the blob.
     */
    @Test
    void aChangedFindingsBlob_refusesADecision_andTheNextReport() {
        fixture.services().start(request(AUDIT), null, null);
        String id = state().findings().getFirst().id();
        String sha = events().get(2).text("findings_blob");
        Runnable change = () -> {
            try (RegisterStore.Opened opened = fixture.registers.open("hello")) {
                ((traceworks.core.testing.InMemoryBlobStore) opened.blobs()).putRaw(sha,
                        "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        int lines = events().size();

        // While the run runs, after its start check read the register as it was
        fixture.commands.on("./measure", request -> {
            change.run();
            return traceworks.core.testing.FakeCommandRunner.ok("## FACT SHEET hello\nFACT files: 3\n");
        });
        RunResult fed;
        try {
            fed = fixture.services().start(request(AUDIT), null, null);
        } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
            fed = null;
            assertThat(e).hasMessageContaining(sha);
        }
        assertThat(fed == null || fed.register() == null || !fed.register().applied()).as("no report applied")
                .isTrue();
        assertThat(events()).as("no line written on a register that cannot be replayed").hasSize(lines);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services().findings()
                        .dismiss("hello", id, "not ours"))
                .isInstanceOf(traceworks.core.port.BlobStore.ChangedBlobException.class).hasMessageContaining(sha);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services().start(request(AUDIT), null,
                        null))
                .as("a start reads the register first").isInstanceOf(RunRefusedException.class)
                .hasMessageContaining(sha);
        assertThat(events()).hasSize(lines);
    }

    /** A view does not die on a changed blob and does not show it as sound: it shows a mark in its place. */
    @Test
    void theContentOfAChangedBlob_isShownAsAMark_notAsContent() {
        String id = fixture.services().start(request(AUDIT), null, null).runId();
        var output = fixture.ofType(id, "step.output").getFirst().blob("output");
        fixture.run(id).blobs().putRaw(output.sha256(), "forged text".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String shown = new String(fixture.services().content(id, output.sha256()).orElseThrow(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(shown).contains("does not match its name", output.sha256()).doesNotContain("forged text");
    }

    /** A record changed since the run ended vouches for nothing: its report does not reach the register. */
    @Test
    void aRunWhoseRecordWasChanged_doesNotFeedItsRegister_whenItsFinalisationIsCompleted() {
        fixture.registers.failWrites(new IllegalStateException("disk gone"));
        RunResult result = fixture.services().start(request(AUDIT), null, null);
        fixture.registers.failWrites(null);
        String id = result.runId();
        var report = fixture.ofType(id, "step.output").getLast();
        String forged = "# Report\n\n<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[]}\n-->\n";
        fixture.run(id).blobs().putRaw(report.blob("output").sha256(),
                forged.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.services()
                .resume(AppFixture.resumeOf(id), null, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("a finished run cannot be resumed");

        assertThat(events()).as("not a line from the changed record").isEmpty();
    }

    /** The facts come from every step but the report block (decided 2026-11-11): a report cannot vouch for itself. */
    @Test
    void theFacts_comeFromTheOtherSteps_notFromTheReport() {
        fixture.commands.on("./report", "## FACT SHEET own\nFACT invented: 1\n<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"r\",\"subject\":\"s\","
                + "\"severity\":\"LOW\",\"title\":\"t\",\"evidence\":\"FACT invented: 1\"}]}\n-->\n");

        fixture.services().start(request(AUDIT), null, null);

        assertThat(events().get(2).fields().path("fact_names").toString()).contains("files").doesNotContain("invented");
        assertThat(state().findings().getFirst().unverifiedEvidence()).isTrue();
    }

    @Test
    void theRegistersPage_isWrittenAfterEveryReport_andAfterADecision() {
        fixture.services().start(request(AUDIT), null, null);
        String afterRun = new String(fixture.registers.viewFile("hello"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(afterRun).contains("<title>Register hello</title>", "<h2>OPEN (2)</h2>");

        String id = state().findings().getFirst().id();
        fixture.services().findings().accept("hello", id, "fine", null);

        assertThat(new String(fixture.registers.viewFile("hello"), java.nio.charset.StandardCharsets.UTF_8))
                .contains("<h2>ACCEPTED_RISK (1)</h2>");
    }

    /** The next run gets what the register knows, as data: the value of a shell variable, never shell code. */
    @Test
    void aSecondRun_seesTheFirstRunsFindings_andTheLastReport_asData() {
        fixture.services().start(request(AUDIT), null, null);

        fixture.services().start(request(READER), null, null);

        String command = fixture.commands.requests().stream().map(r -> r.command() + "\n" + String.join("\n", r.values().values()))
                .filter(c -> c.startsWith("./use")).findFirst().orElseThrow();
        assertThat(command).contains("<audit_findings>", "OPEN | MEDIUM | size | A.java | Big A.java | Evidence: files 3",
                "OPEN | MEDIUM | size | B.java |", "# Report").doesNotContain("traceworks:audit-result")
                .doesNotContain("{{ audit_findings }}");
    }

    @Test
    void theFirstRun_getsNoKnownFindings_andNoPreviousReport() {
        fixture.services().start(request(READER), null, null);

        String command = fixture.commands.requests().stream().map(r -> r.command() + "\n" + String.join("\n", r.values().values()))
                .filter(c -> c.startsWith("./use")).findFirst().orElseThrow();
        assertThat(command).contains("(no known findings)", "(no previous report)");
    }

    /** A rejected report in between does not take the last valid report away from the next run. */
    @Test
    void aRejectedReport_keepsTheLastValidReport_forTheNextRun() {
        fixture.services().start(request(AUDIT), null, null);
        fixture.commands.on("./report",
                "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":false,\"findings\":[]}\n-->\n");
        fixture.services().start(request(AUDIT), null, null);

        fixture.services().start(request(READER), null, null);

        String command = fixture.commands.requests().stream().map(r -> r.command() + "\n" + String.join("\n", r.values().values()))
                .filter(c -> c.startsWith("./use")).reduce((a, b) -> b).orElseThrow();
        assertThat(command).contains("# Report").doesNotContain("(no previous report)").contains("<audit_findings>");
        assertThat(state().lastReport().valid()).isFalse();
    }

    private void withHook() {
        fixture.settings = new OperatorSettings(true, null, List.of(), null, null,
                OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB, Map.of(), OperatorSettings.defaultRunOutputBudgetMib(
                        OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB), null,
                new OperatorSettings.Hooks(List.of("/opt/hooks/todos.sh", "--x"), 7));
    }

    @Test
    void theHook_getsThePayload_onANonEmptyDiff_andItsRunIsRecorded() {
        withHook();

        fixture.services().start(request(AUDIT), null, null);

        assertThat(fixture.hooks.calls()).singleElement().satisfies(call -> {
            assertThat(call.argv()).containsExactly("/opt/hooks/todos.sh", "--x");
            assertThat(call.payload()).contains("\"register\":\"hello\"", "\"new\":[", "A.java", "B.java",
                    "\"report_valid\":true");
            assertThat(call.workingDirectory()).isEqualTo("memory:hello");
            assertThat(call.timeout()).isEqualTo(java.time.Duration.ofSeconds(7));
        });
        RegisterEvent ran = events().getLast();
        assertThat(ran.type()).isEqualTo(RegisterEvents.HOOK_RAN);
        assertThat(ran.fields().path("exit_code").asInt()).isZero();
        assertThat(ran.fields().path("duration_ms").asLong()).isEqualTo(5);
    }

    @Test
    void noHook_onAnUnchangedRegister_norWithoutAConfiguredHook() {
        fixture.services().start(request(AUDIT), null, null);
        assertThat(fixture.hooks.calls()).as("no hook configured").isEmpty();
        withHook();

        fixture.services().start(request(AUDIT), null, null);

        assertThat(fixture.hooks.calls()).as("the same findings again: nothing changed").isEmpty();
    }

    @Test
    void theHookRuns_onARejectedReport_withReportValidFalse() {
        withHook();
        fixture.commands.on("./report",
                "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":false,\"findings\":[]}\n-->\n");

        fixture.services().start(request(AUDIT), null, null);

        assertThat(fixture.hooks.calls()).singleElement().satisfies(call ->
                assertThat(call.payload()).contains("\"report_valid\":false"));
    }

    @Test
    void aFailingHook_isRecorded_andLeavesTheRunsExitCode() {
        withHook();
        fixture.hooks.answering(new traceworks.core.port.HookRunner.Outcome(124, 7000, "slow\n"));

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isTrue();
        assertThat(result.register().hookExitCode()).isEqualTo(124);
        RegisterEvent ran = events().getLast();
        assertThat(ran.fields().path("exit_code").asInt()).isEqualTo(124);
        assertThat(ran.text("stderr_tail")).isEqualTo("slow\n");
    }

    // ---- items (docs/registers.md, "Items")

    /** A report of the judged rule {@code gap}: the findings of the named items, and the list of items. */
    private void reportWithItems(String findingsOfItems, String items, int expectedItems) {
        StringBuilder findings = new StringBuilder();
        for (String item : findingsOfItems.isEmpty() ? new String[0] : findingsOfItems.split(",")) {
            if (!findings.isEmpty()) findings.append(",");
            findings.append("{\"ruleKey\":\"gap\",\"subject\":\"").append(item).append("#1\",\"severity\":\"MEDIUM\","
                    + "\"title\":\"Gap\",\"evidence\":\"files 3\",\"item\":\"").append(item).append("\"}");
        }
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[" + findings + "],\"expectedItems\":" + expectedItems + ",\"items\":[" + items
                + "]}\n-->\n");
    }

    private static String item(String name, String digest, boolean judged) {
        return "{\"name\":\"" + name + "\",\"digest\":\"" + digest + "\",\"judged\":" + judged + "}";
    }

    private static final String JUDGED_CATALOG = "{\"format\":1,\"rules\":{\"gap\":{\"judged\":true}}}";

    @Test
    void aReportWithItems_isApplied_andTheOutcomeCountsThem() {
        fixture.packages.catalog(AUDIT, JUDGED_CATALOG);
        reportWithItems("p.A", item("p.A", "aa", true) + "," + item("p.B", "bb", true) + ","
                + item("p.C", "cc", false), 3);

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().applied()).isTrue();
        assertThat(result.register().items()).isEqualTo(new RegisterOutcome.ItemTally(3, 2, 1));
        assertThat(result.register().summary()).endsWith("; items 1 of 3 settled, 2 judged in this run");
        assertThat(state().items()).extracting(i -> i.name() + ":"
                        + traceworks.core.register.ItemState.of(i, state().findings()).word())
                .containsExactly("p.A:open", "p.B:clean", "p.C:not judged");
        assertThat(state().findings()).singleElement().extracting(f -> f.item()).isEqualTo("p.A");
        assertThat(fixture.services().findings().verify("hello").problems()).isEmpty();
    }

    /**
     * A call that failed is one item not judged, not a rejected report: the register takes the verdicts of the other
     * items, the item keeps the verdict it had, and its open finding stays open, since no run said that it is gone.
     */
    @Test
    void aReportWithAnItemNotJudged_isApplied_andItsOpenFindingStaysOpen() {
        fixture.packages.catalog(AUDIT, JUDGED_CATALOG);
        reportWithItems("p.A", item("p.A", "aa", true) + "," + item("p.B", "bb", true), 2);
        RunResult first = fixture.services().start(request(AUDIT), null, null);
        reportWithItems("", item("p.A", "aa", false) + "," + item("p.B", "b2", true), 2);

        RunResult second = fixture.services().start(request(AUDIT), null, null);

        assertThat(second.register().applied()).isTrue();
        assertThat(second.register().counts()).containsEntry(Change.Kind.NOT_MENTIONED, 1);
        assertThat(second.register().items()).isEqualTo(new RegisterOutcome.ItemTally(2, 1, 1));
        assertThat(state().findings()).singleElement().extracting(f -> f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(state().items().get(0).verdict().runId()).as("p.A keeps its verdict").isEqualTo(first.runId());
        assertThat(state().items().get(1).verdict().runId()).isEqualTo(second.runId());
    }

    /** An item whose every finding a person decided is settled, as a clean item is. */
    @Test
    void anItemWhoseFindingsAreDecided_countsAsSettled() {
        fixture.packages.catalog(AUDIT, JUDGED_CATALOG);
        reportWithItems("p.A", item("p.A", "aa", true) + "," + item("p.B", "bb", true), 2);
        fixture.services().start(request(AUDIT), null, null);
        fixture.services().findings().dismiss("hello", state().findings().getFirst().id(), "false positive");

        RunResult second = fixture.services().start(request(AUDIT), null, null);

        assertThat(second.register().items()).isEqualTo(new RegisterOutcome.ItemTally(2, 2, 2));
        assertThat(second.register().summary()).endsWith("; items 2 of 2 settled, 2 judged in this run");
    }

    @Test
    void aReportWithoutItems_hasNoItemCounts() {
        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().items()).isNull();
        assertThat(result.register().summary()).doesNotContain("items");
    }

    /** The next run gets the items of its register, as data: one row per item, quoted in a shell line. */
    @Test
    void theNextRun_getsTheItemsOfItsRegister_andTheFirstRunNone() {
        reportWithItems("p.A", item("p.A", "aa", true) + "," + item("p.B", "bb", true), 2);
        fixture.services().start(request(ITEM_READER), null, null);
        fixture.services().start(request(ITEM_READER), null, null);

        List<String> commands = fixture.commands.requests().stream().map(r -> r.command() + "\n" + String.join("\n", r.values().values()))
                .filter(c -> c.startsWith("./facts")).toList();
        assertThat(commands.get(0)).contains("(no known items)").doesNotContain("{{ audit_items }}");
        assertThat(commands.get(1)).contains("<audit_items>", "clean | bb | p.B", "open | aa | p.A");
    }

    /** A todo_item line beyond the limits of a finding rejects the report: the register keeps nothing of it. */
    @Test
    void aTodoItemLineBeyondTheLimitsOfAFinding_rejectsTheReport() {
        fixture.commands.on("./report", "todo_item: " + "x".repeat(2001) + " | LOW | t\n");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("todo_item line 1: the title is 2001 characters long");
        assertThat(events()).extracting(RegisterEvent::type).containsExactly(RegisterEvents.CREATED,
                RegisterEvents.REPORT_REJECTED);
        assertThat(fixture.services().findings().verify("hello").problems()).isEmpty();
    }

    private void reportOne(String subject, String severity, String evidence) {
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"" + subject + "\",\"severity\":\"" + severity
                + "\",\"title\":\"Big " + subject + "\",\"evidence\":\"" + evidence + "\"}]}\n-->\n");
    }

    /** A.java, stated as LOW and dismissed by a person. */
    private String dismissedAsLow() {
        reportOne("A.java", "LOW", "files 3");
        fixture.services().start(request(AUDIT), null, null);
        String id = state().findings().getFirst().id();
        fixture.services().findings().dismiss("hello", id, "false positive");
        return id;
    }

    /**
     * A dismissal covers the severity it was made on: stated as CRITICAL, the finding is open again, and the run's
     * summary, the log, the hook and the next run's findings all say so.
     */
    @Test
    void aDismissedFindingStatedWithAHigherSeverity_isOpenAgain_forTheSummaryTheLogTheHookAndTheNextRun() {
        withHook();
        String id = dismissedAsLow();
        reportOne("A.java", "CRITICAL", "files 9");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().counts()).containsEntry(Change.Kind.REOPENED, 1)
                .doesNotContainKey(Change.Kind.UNCHANGED_TRIAGED);
        assertThat(result.register().summary()).startsWith("0 new, 0 resolved, 1 reopened, 1 open now");
        assertThat(state().finding(id).status()).isEqualTo(FindingStatus.OPEN);
        RegisterEvent applied = events().stream().filter(e -> e.type().equals(RegisterEvents.REPORT_APPLIED))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(applied.text("decision_on_higher_severity")).isEqualTo("reopen");
        RegisterEvent reopened = events().stream().filter(e -> e.type().equals(RegisterEvents.FINDING_REOPENED))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(reopened.text("reason")).isEqualTo("higher_severity: LOW → CRITICAL");
        assertThat(fixture.hooks.calls()).as("one for the new finding, one for the reopened").hasSize(2);
        assertThat(fixture.hooks.calls()).last().satisfies(call -> assertThat(call.payload())
                .contains("\"reopened\":[{").contains("\"reason\":\"higher_severity: LOW → CRITICAL\"")
                .contains("\"status\":\"OPEN\"").contains("\"still_open\":[]"));

        fixture.services().start(request(READER), null, null);

        String command = fixture.commands.requests().stream().map(r -> r.command() + "\n" + String.join("\n", r.values().values()))
                .filter(c -> c.startsWith("./use")).reduce((a, b) -> b).orElseThrow();
        assertThat(command).contains("OPEN | CRITICAL | size | A.java | Big A.java | Evidence: files 9\n")
                .doesNotContain("DISMISSED | ");
        assertThat(fixture.services().findings().verify("hello").problems()).isEmpty();
    }

    /** The operator can keep decisions; then the register says which severity the dismissal was made on. */
    @Test
    void withKeep_theDismissalStays_andTheNextRunReadsWhichSeverityItWasMadeOn() {
        fixture.settings = fixture.settings.withDecisionOnHigherSeverity(
                traceworks.core.register.DecisionOnHigherSeverity.KEEP);
        String id = dismissedAsLow();
        reportOne("A.java", "CRITICAL", "files 9");

        RunResult result = fixture.services().start(request(AUDIT), null, null);

        assertThat(result.register().counts()).containsEntry(Change.Kind.UNCHANGED_TRIAGED, 1)
                .doesNotContainKey(Change.Kind.REOPENED);
        assertThat(state().finding(id).status()).isEqualTo(FindingStatus.DISMISSED);
        RegisterEvent applied = events().stream().filter(e -> e.type().equals(RegisterEvents.REPORT_APPLIED))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(applied.text("decision_on_higher_severity")).isEqualTo("keep");
        assertThat(new String(fixture.registers.stateFile("hello"), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"note\":\"false positive\",\"severity\":\"LOW\"");

        fixture.services().start(request(READER), null, null);

        String command = fixture.commands.requests().stream().map(r -> r.command() + "\n" + String.join("\n", r.values().values()))
                .filter(c -> c.startsWith("./use")).reduce((a, b) -> b).orElseThrow();
        assertThat(command).contains("DISMISSED | CRITICAL | size | A.java | Big A.java | Evidence: files 9"
                + " | Disposition: false positive | Decided on: LOW, not on CRITICAL\n");
        assertThat(fixture.services().findings().verify("hello").problems()).isEmpty();
    }
}
