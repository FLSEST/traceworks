package traceworks.core.app;

import traceworks.core.compare.RunComparison;
import traceworks.core.compare.RunComparison.AiCall;
import traceworks.core.compare.RunComparison.Field;
import traceworks.core.compare.RunComparison.Finding;
import traceworks.core.compare.RunComparison.Output;
import traceworks.core.compare.RunComparison.Step;
import traceworks.core.model.RunStatus;
import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.WORKSPACE;
import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The use case behind {@code traceworks diff}: two finished runs that verify, compared from their records. */
class ComparingTest {

    private static final Path AUDIT = Path.of("/pkgs/audit");
    private static final Path REVIEW = Path.of("/pkg/review");

    private final AppFixture fx = new AppFixture();

    private String run(Path pkg, Map<String, String> inputs) {
        RunResult result = fx.services().start(new StartRequest(pkg, inputs, WORKSPACE, false, false)
                .inProject(AppFixture.PROJECT), null, null);
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(RunStatus.COMPLETED);
        return result.runId();
    }

    private static Field field(RunComparison comparison, String name) {
        return comparison.header().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void twoRunsWithTheSameInputs_areReproduced_andPrefixesNameThem() {
        String a = run(BUILD, Map.of("version", "1.2"));
        String b = run(BUILD, Map.of("version", "1.2"));

        RunComparison comparison = fx.services().compare(a.substring(0, 36), b);

        assertThat(comparison.runA()).isEqualTo(a);
        assertThat(comparison.runB()).isEqualTo(b);
        assertThat(comparison.reproduced()).isTrue();
        assertThat(comparison.header()).allMatch(Field::same);
        assertThat(field(comparison, "Traceworks version").a()).isEqualTo(TraceworksVersion.current());
        assertThat(field(comparison, "execution").a()).isEqualTo("host");
        assertThat(field(comparison, "package digest").a()).isNotNull();
        assertThat(comparison.steps()).extracting(Step::label).containsExactly("build", "test");
        assertThat(comparison.findings()).isNull();
    }

    @Test
    void anotherInput_andAnotherOutput_failTheCheck() {
        String a = run(BUILD, Map.of("version", "1.2"));
        fx.commands.on("./test", "tested otherwise");
        String b = run(BUILD, Map.of("version", "1.3"));

        RunComparison comparison = fx.services().compare(a, b);

        assertThat(comparison.differences()).containsExactly("the inputs differ", "step test: the output differs");
        assertThat(comparison.inputs()).containsExactly(
                new RunComparison.Input("version", false, false, "\"1.2\"", "\"1.3\""));
    }

    /** A deterministic step whose output was cut at the bound in one run only did not run the same way. */
    @Test
    void anOutputCutInOneRunOnly_failsTheCheck_andInBothItDoesNot() {
        String a = run(BUILD, Map.of());
        fx.commands.on("./test", traceworks.core.testing.FakeCommandRunner.ok("tested").withOutputCut(
                new traceworks.core.port.CommandRunner.OutputCut(java.util.List.of("stdout"), 5000)));
        String b = run(BUILD, Map.of());
        String c = run(BUILD, Map.of());

        assertThat(fx.services().compare(a, b).differences()).containsExactly(
                "step test: the output was cut in run B (command 1: stdout after 5000 ms), not in run A");
        assertThat(fx.services().compare(b, a).differences()).containsExactly(
                "step test: the output was cut in run A (command 1: stdout after 5000 ms), not in run B");
        assertThat(fx.services().compare(b, c).reproduced()).as("cut the same way in both").isTrue();
    }

    @Test
    void aRunThatHasNotFinished_isRefused_withItsStatus() {
        String finished = run(BUILD, Map.of());
        String parked = fx.services().start(request(DEPLOY), null, null).runId();

        assertThatThrownBy(() -> fx.services().compare(finished, parked))
                .isExactlyInstanceOf(RunRefusedException.class)
                .hasMessageContaining(parked).hasMessageContaining("has not finished (PARKED)");
    }

    @Test
    void aRunWhoseRecordDoesNotVerify_isDamage_notADifference() {
        String a = run(BUILD, Map.of());
        String b = run(BUILD, Map.of());
        fx.run(b).log().tearTail("{\"seq\":99}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fx.services().compare(a, b)).isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining(b).hasMessageContaining("does not verify").hasMessageContaining("traceworks verify");
    }

    @Test
    void aRunWithDeletedInputs_isComparedByItsDigest() {
        String a = run(BUILD, Map.of("version", "1"));
        String b = run(BUILD, Map.of("version", "1"));
        fx.run(a).blobs().delete(fx.events(a).getFirst().blob("inputs").sha256());

        RunComparison comparison = fx.services().compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        assertThat(comparison.inputs()).isEmpty();
        assertThat(comparison.notes()).singleElement().asString().contains(a, "deleted");
    }

    /** An AI step: the same setup may answer differently; the calls carry what the record has of each. */
    @Test
    void anAiStepThatAnswersDifferently_isReported_andTheRunsAreStillReproduced() {
        fx.settings = OperatorSettings.defaults();
        fx.withClaude();
        fx.packages.put(REVIEW, """
                {"id": "wf-review", "name": "Review", "requires": ["ai.tools"], "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "simple", "name": "review",
                   "config": {"command": "Review: {{ block.build.output }}", "tools": "read"}}
                ]}""");
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "built", "", 1, false));
        fx.claude.answers("looks fine", "looks bad");
        String a = run(REVIEW, Map.of());
        String b = run(REVIEW, Map.of());

        RunComparison comparison = fx.services().compare(a, b);

        assertThat(comparison.reproduced()).as(comparison.differences().toString()).isTrue();
        Step review = comparison.steps().get(1);
        assertThat(review.ai()).isTrue();
        assertThat(review.output()).isEqualTo(Output.DIFFERS);
        AiCall call = review.calls().getFirst();
        assertThat(call.a().provider()).isEqualTo("CLAUDE");
        assertThat(call.a().tools()).isEqualTo("read");
        assertThat(call.promptSame()).isTrue();
        assertThat(call.output()).isEqualTo(Output.DIFFERS);
        assertThat(field(comparison, "image " + AppFixture.CLAUDE_TAG)).isEqualTo(
                new Field("image " + AppFixture.CLAUDE_TAG, AppFixture.CLAUDE_ID, AppFixture.CLAUDE_ID));
        assertThat(field(comparison, "ai_network").a()).isEqualTo("restricted");
    }

    private void auditPackage() {
        fx.packages.put(AUDIT, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "measure", "config": {"command": "./measure"}},
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fx.commands.on("./measure", "## FACT SHEET hello\nFACT files: 3\n");
    }

    private void report(String... subjects) {
        StringBuilder findings = new StringBuilder();
        for (String subject : subjects) {
            if (!findings.isEmpty()) {
                findings.append(",");
            }
            findings.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"Big ").append(subject)
                    .append("\",\"evidence\":\"files 3\"}");
        }
        fx.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[" + findings + "]}\n-->\n");
    }

    /** Two audit runs: what the register held after each, compared: new, resolved and reopened findings. */
    @Test
    void twoAuditRuns_nameTheFindingsNewResolvedAndReopenedBetweenThem() {
        auditPackage();
        report("A.java", "B.java");
        String first = run(AUDIT, Map.of());
        report("B.java", "C.java");
        String second = run(AUDIT, Map.of());
        report("A.java", "B.java", "C.java");
        String third = run(AUDIT, Map.of());

        RunComparison.FindingsDelta delta = fx.services().compare(first, second).findings();
        RunComparison.FindingsDelta later = fx.services().compare(second, third).findings();

        assertThat(delta.register()).isEqualTo("hello");
        assertThat(delta.note()).isNull();
        assertThat(delta.added()).extracting(Finding::subject).containsExactly("C.java");
        assertThat(delta.added().getFirst().severity()).isEqualTo("MEDIUM");
        assertThat(delta.added().getFirst().title()).isEqualTo("Big C.java");
        assertThat(delta.added().getFirst().id()).hasSize(64);
        assertThat(delta.resolved()).extracting(Finding::subject).containsExactly("A.java");
        assertThat(delta.reopened()).isEmpty();
        assertThat(later.added()).isEmpty();
        assertThat(later.resolved()).isEmpty();
        assertThat(later.reopened()).extracting(Finding::subject).containsExactly("A.java");
    }

    /** The register did not take a run's report: there is no state of it to compare, and the comparison says why. */
    @Test
    void anAuditRunTheRegisterDidNotApply_hasNoFindingsToCompare_andANoteSaysWhy() {
        auditPackage();
        report("A.java");
        String first = run(AUDIT, Map.of());
        fx.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{not json\n-->\n");
        String second = fx.services().start(new StartRequest(AUDIT, Map.of(), WORKSPACE, false, false)
                .inProject(AppFixture.PROJECT), null, null).runId();

        RunComparison.FindingsDelta delta = fx.services().compare(first, second).findings();

        assertThat(delta.register()).isEqualTo("hello");
        assertThat(delta.note()).contains(second, "did not apply");
        assertThat(delta.added()).isEmpty();
    }

    /** A register that cannot be listed or read is a note of the comparison, never a failure of the program. */
    @Test
    void aRegisterThatCannotBeListedOrRead_isANoteOfTheComparison() {
        auditPackage();
        report("A.java");
        String first = run(AUDIT, Map.of());
        String second = run(AUDIT, Map.of());
        fx.registers.failProjects("hello", new java.io.UncheckedIOException("Cannot list the registers in"
                + " /home/registers/hello: Permission denied", new java.io.IOException("Permission denied")));

        RunComparison.FindingsDelta unlisted = fx.services().compare(first, second).findings();

        assertThat(unlisted.note()).contains("cannot be read").contains("Permission denied");
        fx.registers.failProjects("hello", null);
        fx.registers.failReads("hello", AppFixture.PROJECT, new java.io.UncheckedIOException("Cannot read"
                + " events.jsonl: Permission denied", new java.io.IOException("Permission denied")));

        RunComparison.FindingsDelta unread = fx.services().compare(first, second).findings();

        assertThat(unread.note()).contains("cannot be read").contains("Permission denied");
    }

    /**
     * A finished audit run of a version before registers were kept per project, in this home: the run {@code id}
     * of {@code source} with the project (and what says that the ledger names it) taken out of its first line and
     * the chain of the lines after it made anew. Its finalisation is written by this version, from that record.
     */
    private String earlier(AppFixture source, String id) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var created = fx.store.create(id, fx.clock.now())) {
            String previous = null;
            for (byte[] raw : source.run(id).log().readAll()) {
                var line = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(raw);
                if (previous == null) {
                    assertThat(line.path("project").asText()).isEqualTo(AppFixture.PROJECT);
                    line.remove(java.util.List.of("project", "ledger_starter"));
                    ((com.fasterxml.jackson.databind.node.ObjectNode) line.path("register")).remove("project");
                } else {
                    line.put("prev", previous);
                }
                previous = created.events().append(traceworks.core.record.RecordJson.json(line));
            }
            created.writeRegisterFiles(new java.util.LinkedHashMap<>(source.run(id).registerFolder()));
        }
        source.run(id).blobs().all().forEach(fx.run(id).blobs()::put);
        fx.services().resume(AppFixture.resumeOf(id), null, null);
        assertThat(fx.registers.keys()).as("ended now, it feeds no register of this version").isEmpty();
        assertThat(fx.services().verify(id).ok()).as(fx.services().verify(id).toString()).isTrue();
        return id;
    }

    /**
     * Two audit runs whose records name no project are from before registers were kept per project. Their findings
     * are not compared, and nothing says that they fed no register: the register of that version took their reports.
     */
    @Test
    void twoAuditRunsWhoseRecordsNameNoProject_areFromBeforeRegistersPerProject_notRunsThatFedNoRegister()
            throws Exception {
        AppFixture source = new AppFixture();
        String pkg = """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""";
        source.packages.put(AUDIT, pkg);
        fx.packages.put(AUDIT, pkg);
        source.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");
        String first = source.services().start(request(AUDIT), null, null).runId();
        String second = source.services().start(request(AUDIT), null, null).runId();

        RunComparison.FindingsDelta delta = fx.services().compare(earlier(source, first), earlier(source, second))
                .findings();

        assertThat(delta.register()).isEqualTo("hello");
        assertThat(delta.note()).contains("from before registers were kept per project")
                .contains("their findings are not compared").doesNotContain("fed no register");
        assertThat(delta.added()).isEmpty();
        assertThat(delta.resolved()).isEmpty();
    }
}
