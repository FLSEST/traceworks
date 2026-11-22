package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.RunStatus;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;
import traceworks.core.record.StartEntry;
import traceworks.core.testing.InMemoryLedger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;

/**
 * The ledger notes a run when it starts: before the first step, outside the run folder. So a run that never ended,
 * or whose folder was removed, is still named, and {@code verify} matches a record to its start entry.
 */
class LedgerStartTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path AUDIT = Path.of("/pkgs/audit");

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();

    private JsonNode startEntry(String id) throws Exception {
        return JSON.readTree(fx.ledger.findStart(id).orElseThrow());
    }

    @Test
    void aRun_getsAStartEntry_beforeItsFirstStep_thatNamesItsFirstLine() throws Exception {
        boolean[] notedBeforeTheStep = new boolean[1];
        fx.commands.on("./build", request -> {
            notedBeforeTheStep[0] = fx.ledger.starts() == 1;
            return traceworks.core.testing.FakeCommandRunner.ok("built");
        });

        String id = services.start(request(BUILD), null, null).runId();

        assertThat(notedBeforeTheStep[0]).as("the entry is there when the first step runs").isTrue();
        RecordedEvent started = fx.events(id).getFirst();
        JsonNode entry = startEntry(id);
        assertThat(entry.path("run_id").asText()).isEqualTo(id);
        assertThat(entry.path("run_started").asText()).isEqualTo(EventChain.sha256Hex(started.raw()))
                .as("the hash of the first line is the prev of the second").isEqualTo(fx.events(id).get(1).prev());
        assertThat(java.time.Instant.parse(entry.path("started_at").asText())).isEqualTo(started.at());
        assertThat(entry.path("workflow_id").asText()).isEqualTo("wf-build");
        assertThat(entry.path("package_digest").asText())
                .isEqualTo(started.fields().path("package").path("digest").asText());
        assertThat(entry.path("traceworks_version").asText()).isEqualTo(TraceworksVersion.current());
        assertThat(entry.has("register")).as("no audit").isFalse();
        assertThat(started.fields().path("ledger_start").asBoolean()).isTrue();
        assertThat(services.view(id).ledgerStart()).isTrue();
        assertThat(fx.ledger.find(id)).as("the end entry is another one").isPresent();
    }

    @Test
    void theStartEntryOfAnAudit_namesTheRegisterAndTheHeadTheRunRead() throws Exception {
        fx.packages.put(AUDIT, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fx.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");

        String first = services.start(request(AUDIT), null, null).runId();
        String second = services.start(request(AUDIT), null, null).runId();

        JsonNode before = startEntry(first).path("register");
        assertThat(before.path("key").asText()).isEqualTo("hello");
        assertThat(before.path("project").asText()).as("the register is that of the run's project")
                .isEqualTo(AppFixture.PROJECT);
        assertThat(before.path("seq").isNull()).as("no register yet").isTrue();
        List<String> fields = new java.util.ArrayList<>();
        startEntry(first).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("the fields of a start entry, in their order").containsExactly("run_id", "started_at",
                "run_started", "workflow_id", "package_digest", "project", "actor", "register", "traceworks_version");
        List<String> ofTheRegister = new java.util.ArrayList<>();
        before.fieldNames().forEachRemaining(ofTheRegister::add);
        assertThat(ofTheRegister).containsExactly("key", "project", "seq", "head");
        JsonNode read = startEntry(second).path("register");
        JsonNode recorded = fx.events(second).getFirst().fields().path("register");
        assertThat(read.path("seq").asInt()).isEqualTo(recorded.path("seq").asInt());
        assertThat(read.path("head").asText()).isEqualTo(recorded.path("head").asText()).hasSize(64);
    }

    // ---- the project of a run, and who started it ----

    private JsonNode manifest(String id) throws Exception {
        return JSON.readTree(fx.run(id).readManifest().orElseThrow());
    }

    /** The ledger is one for all runs of a home: its entries say which project each run worked on, and who started it. */
    @Test
    void theStartEntryAndTheEndEntry_nameTheProjectOfTheRun_andWhoStartedIt() throws Exception {
        String id = services.start(request(BUILD), null, null).runId();

        RecordedEvent started = fx.events(id).getFirst();
        assertThat(started.fields().path("actor").asText()).isEqualTo(AppFixture.ACTOR);
        for (JsonNode entry : List.of(startEntry(id), manifest(id), JSON.readTree(fx.ledger.find(id).orElseThrow()))) {
            assertThat(entry.path("project").asText()).isEqualTo(AppFixture.PROJECT);
            assertThat(entry.path("actor").asText()).as("exactly what run.started records")
                    .isEqualTo(started.fields().path("actor").asText());
        }
        assertThat(fx.ledger.find(id).orElseThrow()).as("the end entry is the manifest, byte for byte")
                .isEqualTo(fx.run(id).readManifest().orElseThrow());
        assertThat(services.verify(id).ok()).isTrue();
    }

    @Test
    void theEntriesOfARunWithoutAProject_holdANullProject_andTheActor() throws Exception {
        String id = services.start(request(BUILD).inProject(null), null, null).runId();

        for (JsonNode entry : List.of(startEntry(id), manifest(id))) {
            assertThat(entry.has("project")).as("the field is there").isTrue();
            assertThat(entry.path("project").isNull()).isTrue();
            assertThat(entry.path("actor").asText()).isEqualTo(AppFixture.ACTOR);
        }
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** The start entry as the ledger holds it, with one of its values replaced. */
    private void replaceInStartEntry(String id, String field, String value) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode entry =
                (com.fasterxml.jackson.databind.node.ObjectNode) startEntry(id);
        entry.put(field, value);
        fx.ledger.replaceStart(id, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));
    }

    @Test
    void verify_ofAStartEntryThatNamesAnotherProjectThanTheRecord_fails() throws Exception {
        String id = services.start(request(BUILD), null, null).runId();
        replaceInStartEntry(id, "project", "web");

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISMATCH).contains("the project web")
                .contains("the project " + AppFixture.PROJECT);
    }

    @Test
    void verify_ofAStartEntryThatNamesAProject_forARunThatNamedNone_fails() throws Exception {
        String id = services.start(request(BUILD).inProject(null), null, null).runId();
        replaceInStartEntry(id, "project", "web");

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISMATCH).contains("the project web")
                .contains("no project");
    }

    @Test
    void verify_ofAStartEntryThatNamesAnotherActorThanTheRecord_fails() throws Exception {
        String id = services.start(request(BUILD), null, null).runId();
        replaceInStartEntry(id, "actor", "mallory@elsewhere");

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISMATCH).contains("mallory@elsewhere")
                .contains(AppFixture.ACTOR);
    }

    /**
     * An entry of an earlier version names neither: it stays valid for a record of an earlier version, which does
     * not say that its entries name the starter ({@code ledger_starter}) and names no project.
     */
    @Test
    void aStartEntryOfAnEarlierVersion_withoutProjectAndActor_staysValid() throws Exception {
        String id = loadEarlier();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        assertThat(services.resume(resumeOf(id), null, null).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.events(id).getFirst().fields().has("ledger_starter")).as("a record of an earlier version")
                .isFalse();
        com.fasterxml.jackson.databind.node.ObjectNode entry =
                (com.fasterxml.jackson.databind.node.ObjectNode) startEntry(id);
        entry.remove(List.of("project", "actor"));
        fx.ledger.replaceStart(id, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));

        VerifyReport report = services.verify(id);

        assertThat(report.start()).startsWith(VerifyReport.OK);
        assertThat(report.ok()).isTrue();
        assertThat(StartEntry.parse(fx.ledger.findStart(id).orElseThrow()).orElseThrow().starter()).isNull();
    }

    // ---- the record says that its ledger entries name the starter ----

    /** The marker of run.started, in the way ledger_start marks the start entry. */
    @Test
    void runStarted_saysThatTheEntriesOfTheLedgerNameTheStarter() {
        String with = services.start(request(BUILD), null, null).runId();
        String without = services.start(request(BUILD).inProject(null), null, null).runId();

        for (String id : List.of(with, without)) {
            JsonNode started = fx.events(id).getFirst().fields();
            assertThat(started.path("ledger_start").asBoolean()).isTrue();
            assertThat(started.path("ledger_starter").isBoolean()).as("a marker, as ledger_start is").isTrue();
            assertThat(started.path("ledger_starter").asBoolean()).isTrue();
        }
    }

    /**
     * Reproduced 2026-11-21: for a run without a project, deleting "project" and "actor" from the start entry, the
     * end entry and the manifest still verified, since entries of an earlier version have neither. The record says
     * now that its entries name the starter, so their absence is found.
     */
    @Test
    void strippingProjectAndActorFromTheStartEntry_ofARunWithoutAProject_isFound() throws Exception {
        String id = services.start(request(BUILD).inProject(null), null, null).runId();
        com.fasterxml.jackson.databind.node.ObjectNode entry =
                (com.fasterxml.jackson.databind.node.ObjectNode) startEntry(id);
        entry.remove(List.of("project", "actor"));
        fx.ledger.replaceStart(id, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISMATCH)
                .contains("names neither the project of the run nor who started it")
                .contains("ledger_starter");
    }

    /** The actor alone, taken out of the start entry of a run without a project. */
    @Test
    void strippingTheActorFromTheStartEntry_ofARunWithoutAProject_isFound() throws Exception {
        String id = services.start(request(BUILD).inProject(null), null, null).runId();
        com.fasterxml.jackson.databind.node.ObjectNode entry =
                (com.fasterxml.jackson.databind.node.ObjectNode) startEntry(id);
        entry.remove("actor");
        fx.ledger.replaceStart(id, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISMATCH).contains(AppFixture.ACTOR);
    }

    @Test
    void strippingProjectAndActorFromTheManifestAndTheEndEntry_ofARunWithoutAProject_isFound() {
        String id = services.start(request(BUILD).inProject(null), null, null).runId();
        rewriteManifest(id, manifest -> manifest
                .replaceFirst("(?m)^ {2}\"project\" : (null|\"[^\"]*\"),\\R", "")
                .replaceFirst("(?m)^ {2}\"actor\" : \"[^\"]*\",\\R", ""));
        assertThat(new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8))
                .doesNotContain("\"project\"", "\"actor\"");

        VerifyReport report = services.verify(id);
        LedgerReport home = services.ledgerReport();

        assertThat(report.manifest()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(report.ok()).isFalse();
        assertThat(home.problems()).as("the check of the whole home finds it too")
                .anySatisfy(problem -> {
                    assertThat(problem.runId()).isEqualTo(id);
                    assertThat(problem.code()).isEqualTo("record-broken");
                    assertThat(problem.text()).contains("is not the manifest the record gives");
                });
    }

    /** A record that names a project was started by a version whose entry names it: one without it is not its entry. */
    @Test
    void aStartEntryWithoutProjectAndActor_forARecordThatNamesAProject_fails() throws Exception {
        String id = services.start(request(BUILD), null, null).runId();
        com.fasterxml.jackson.databind.node.ObjectNode entry =
                (com.fasterxml.jackson.databind.node.ObjectNode) startEntry(id);
        entry.remove(List.of("project", "actor"));
        fx.ledger.replaceStart(id, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISMATCH)
                .contains("names neither the project of the run nor who started it");
    }

    /**
     * Makes the record of {@code id} one that does not say that its entries name the starter: its first line
     * without the marker, and every line after it chained to the one before, as a version before the marker would
     * have written the same run.
     *
     * @return the SHA-256 of the first line as it is now
     */
    private String withoutTheMarker(String id) throws Exception {
        var log = fx.run(id).log();
        List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        String prev = null;
        for (byte[] line : lines) {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(line);
            if (prev == null) {
                assertThat(node.remove("ledger_starter")).as("the marker of this version").isNotNull();
            } else {
                node.put("prev", prev);
            }
            prev = log.append(traceworks.core.record.RecordJson.json(node));
        }
        return traceworks.core.record.EventChain.sha256Hex(log.readAll().getFirst());
    }

    /**
     * A record that names a project and does not say that its entries name the starter: the project is compared
     * all the same, as every field the entry and the first line share. An entry that names none is not the entry
     * of that record, and one that names the project of the record holds.
     */
    @Test
    void aStartEntryWithoutAProject_forARecordWithoutTheMarkerThatNamesAProject_fails() throws Exception {
        String id = services.start(request(BUILD), null, null).runId();
        String firstLine = withoutTheMarker(id);
        assertThat(fx.events(id).getFirst().fields().has("ledger_starter")).isFalse();
        assertThat(fx.events(id).getFirst().fields().path("project").asText()).isEqualTo(AppFixture.PROJECT);
        com.fasterxml.jackson.databind.node.ObjectNode entry =
                (com.fasterxml.jackson.databind.node.ObjectNode) startEntry(id);
        entry.put("run_started", firstLine);
        fx.ledger.replaceStart(id, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));

        assertThat(services.verify(id).start()).as("the entry names the project and the actor of the record")
                .startsWith(VerifyReport.OK);

        entry.remove(List.of("project", "actor"));
        fx.ledger.replaceStart(id, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));

        VerifyReport report = services.verify(id);

        assertThat(report.start()).isEqualTo(VerifyReport.MISMATCH + ": the start entry of the ledger names no"
                + " project, and run.started names the project " + AppFixture.PROJECT);
        assertThat(report.ok()).isFalse();
    }

    private void rewriteManifest(String id, java.util.function.UnaryOperator<String> change) {
        byte[] changed = change.apply(new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        fx.run(id).writeManifest(changed);
        fx.ledger.forgeEnd(id, "COMPLETED", changed);
    }

    /** The manifest is written again from the record and compared byte for byte: a changed project or actor shows. */
    @Test
    void aManifestWhoseProjectOrActorWasChanged_doesNotVerify() throws Exception {
        String id = services.start(request(BUILD), null, null).runId();
        String original = new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(original).contains("\"project\" : \"shop\"", "\"actor\" : \"operator@box\"");

        rewriteManifest(id, manifest -> manifest.replace("\"project\" : \"shop\"", "\"project\" : \"web\""));
        VerifyReport project = services.verify(id);
        rewriteManifest(id, manifest -> original.replace("\"actor\" : \"operator@box\"", "\"actor\" : \"mallory@x\""));
        VerifyReport actor = services.verify(id);

        assertThat(project.manifest()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(project.ok()).isFalse();
        assertThat(actor.manifest()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(actor.ok()).isFalse();
    }

    /**
     * A manifest of an earlier version names neither, and still verifies for a record of an earlier version, which
     * names no project and does not say that its entries name the starter. Not for a record that names a project.
     */
    @Test
    void aManifestOfAnEarlierVersion_withoutProjectAndActor_staysValid_butNotForARecordThatNamesAProject()
            throws Exception {
        String without = loadEarlier();
        services.cancel(without);
        String with = services.start(request(BUILD), null, null).runId();
        java.util.function.UnaryOperator<String> strip = manifest -> manifest
                .replaceFirst("(?m)^ {2}\"project\" : (null|\"[^\"]*\"),\\R", "")
                .replaceFirst("(?m)^ {2}\"actor\" : \"[^\"]*\",\\R", "");
        byte[] stripped = strip.apply(new String(fx.run(without).readManifest().orElseThrow(),
                StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        fx.run(without).writeManifest(stripped);
        fx.ledger.forgeEnd(without, "CANCELLED", stripped);
        rewriteManifest(with, strip);
        assertThat(new String(stripped, StandardCharsets.UTF_8)).doesNotContain("\"project\"", "\"actor\"");
        assertThat(new String(fx.run(with).readManifest().orElseThrow(), StandardCharsets.UTF_8))
                .doesNotContain("\"project\"", "\"actor\"");

        VerifyReport earlier = services.verify(without);
        VerifyReport ofAProject = services.verify(with);

        assertThat(earlier.manifest()).isEqualTo(VerifyReport.OK);
        assertThat(earlier.ledger()).isEqualTo(VerifyReport.OK);
        assertThat(ofAProject.manifest()).as("the record names a project").isEqualTo(VerifyReport.MISMATCH);
        assertThat(ofAProject.ok()).isFalse();
    }

    /** A record of an earlier version that this version ends: its manifest names no project, and who started it. */
    @Test
    void aRunOfAnEarlierVersionThatEndsNow_getsEntriesWithANullProject() throws Exception {
        String id = loadEarlier();
        services.cancel(id);

        JsonNode manifest = manifest(id);
        assertThat(manifest.has("project")).isTrue();
        assertThat(manifest.path("project").isNull()).isTrue();
        assertThat(manifest.path("actor").asText())
                .isEqualTo(fx.events(id).getFirst().fields().path("actor").asText());
        assertThat(services.verify(id).manifest()).isEqualTo(VerifyReport.OK);
    }

    /** Fail closed: without the entry no step runs. The run ends FAILED, and the start is refused. */
    @Test
    void whenTheStartCannotBeNoted_noStepRuns_andTheRunEndsFailed() {
        fx.ledger.failStarts(new IllegalStateException("git commit failed with exit code 128"));

        assertThatThrownBy(() -> services.start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("could not note the start")
                .hasMessageContaining("no step ran")
                .hasMessageContaining("git commit failed with exit code 128");

        assertThat(fx.commands.commands()).isEmpty();
        String id = fx.store.runIds().getFirst();
        assertThat(fx.types(id)).containsExactly("run.started", "run.finished");
        RunView view = services.view(id);
        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fx.text(id, fx.events(id).getLast(), "error")).contains("could not note the start");
        assertThat(fx.ledger.findStart(id)).isEmpty();
        VerifyReport report = services.verify(id);
        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISSING);
    }

    /** The ledger works again: a resume of the run that ended for it writes the start entry, and adds no line. */
    @Test
    void aRunThatEndedBecauseItsStartWasNotNoted_isCompletedByAResume() {
        fx.ledger.failStarts(new IllegalStateException("no git"));
        assertThatThrownBy(() -> services.start(request(BUILD), null, null)).isInstanceOf(RunRefusedException.class);
        String id = fx.store.runIds().getFirst();
        int lines = fx.events(id).size();
        fx.ledger.failStarts(null);

        RunResult completed = services.resume(resumeOf(id), null, null);

        assertThat(completed.completed()).containsExactly("start entry");
        assertThat(fx.events(id)).hasSize(lines);
        assertThat(fx.commands.commands()).isEmpty();
        assertThat(services.verify(id).ok()).isTrue();
    }

    @Test
    void verify_withAMatchingStartEntry_saysSo() {
        String id = services.start(request(BUILD), null, null).runId();

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isTrue();
        assertThat(report.start()).startsWith(VerifyReport.OK).contains("the ledger noted the start")
                .contains(EventChain.sha256Hex(fx.events(id).getFirst().raw()).substring(0, 12));
    }

    @Test
    void verify_ofARunWhoseStartEntryIsGone_fails() {
        String id = services.start(request(BUILD), null, null).runId();
        fx.ledger.removeStart(id);

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISSING).contains("holds no start entry");
    }

    /** Another record under the same run id: the entry names the first line of the record that started. */
    @Test
    void verify_ofARecordThatIsNotTheOneThatStarted_fails() {
        String id = services.start(request(DEPLOY), null, null).runId();
        StartEntry noted = StartEntry.parse(fx.ledger.findStart(id).orElseThrow()).orElseThrow();
        fx.ledger.replaceStart(id, new StartEntry(id, noted.startedAt(), "f".repeat(64), noted.workflowId(),
                noted.packageDigest(), null, noted.version()).toJson());

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.start()).startsWith(VerifyReport.MISMATCH).contains("f".repeat(64))
                .contains(noted.runStarted());
    }

    /** A recorded run of a version before start entries: it has none, and that is said, not failed. */
    @Test
    void verify_ofARunOfAnEarlierVersion_saysThatItHasNoStartEntry_andHolds() throws Exception {
        String id = loadEarlier();

        VerifyReport report = services.verify(id);

        assertThat(report.start()).startsWith(VerifyReport.NONE)
                .contains("recorded by a version that noted no start", "0.1.0-SNAPSHOT");
        assertThat(report.ok()).isTrue();
        assertThat(services.view(id).ledgerStart()).isFalse();
    }

    /** A resume is the same run: it gets no second start entry. */
    @Test
    void aResume_addsNoStartEntry() {
        String id = services.start(request(DEPLOY), null, null).runId();
        byte[] entry = fx.ledger.findStart(id).orElseThrow();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));

        assertThat(services.resume(resumeOf(id), null, null).status()).isEqualTo(RunStatus.COMPLETED);

        assertThat(fx.ledger.starts()).isEqualTo(1);
        assertThat(fx.ledger.findStart(id)).hasValue(entry);
        assertThat(services.verify(id).ok()).isTrue();
    }

    /**
     * No step of this version runs in a run the ledger does not name: a run of an earlier version that is resumed
     * now gets its start entry at the resume, from the first line of its record, and verifies with it.
     */
    @Test
    void aResumedRunOfAnEarlierVersion_getsItsStartEntryAtTheResume() throws Exception {
        String earlier = loadEarlier();
        services.answer(earlier, new AnswerRequest("approval", 1, "Yes", null, null));

        assertThat(services.resume(resumeOf(earlier), null, null).status()).isEqualTo(RunStatus.COMPLETED);

        assertThat(StartEntry.parse(fx.ledger.findStart(earlier).orElseThrow()).orElseThrow().runStarted())
                .isEqualTo(EventChain.sha256Hex(fx.events(earlier).getFirst().raw()));
        assertThat(services.verify(earlier).start()).startsWith(VerifyReport.OK);
        assertThat(services.verify(earlier).ok()).isTrue();
    }

    /**
     * A run whose process was killed between run.started and the commit, or whose entry was taken out: the resume
     * writes the entry before it goes on, and does not go on when the ledger cannot take it.
     */
    @Test
    void aResumeOfARunWithoutItsStartEntry_writesItFirst_orDoesNotGoOn() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.ledger.startOver();
        int lines = fx.events(id).size();
        fx.ledger.failStarts(new IllegalStateException("no git"));

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("holds no start entry").hasMessageContaining("no git");
        assertThat(fx.events(id)).as("nothing was recorded").hasSize(lines);
        assertThat(fx.commands.commands()).doesNotContain("./done");

        fx.ledger.failStarts(null);
        assertThat(services.resume(resumeOf(id), null, null).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ledger.findStart(id)).isPresent();
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** A child run lives in its parent's record: it has no folder, no end entry and no start entry of its own. */
    @Test
    void aChildRun_getsNoEntryOfItsOwn() {
        Path parent = Path.of("/pkg/family");
        fx.packages.put(parent, """
                {"id": "wf-family", "name": "Family", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./child"}}
                ]}""");
        fx.packages.put(parent.resolve("child"), """
                {"id": "wf-family-child", "name": "Child", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}}
                ]}""");

        String id = services.start(request(parent), null, null).runId();

        assertThat(services.view(id).children()).isNotEmpty();
        assertThat(fx.ledger.runs().runs()).extracting(traceworks.core.port.LedgerRuns.Run::runId)
                .containsExactly(id);
    }

    @Test
    void theLedgerReport_namesTheStartsWithoutAnEnd_withWhatTheirFolderSays() {
        String finished = services.start(request(BUILD), null, null).runId();
        String parked = services.start(request(DEPLOY), null, null).runId();
        fx.store.failEventWritesAfter(3);
        assertThatThrownBy(() -> services.start(request(BUILD), null, null)).isInstanceOf(EventLogException.class);
        fx.store.failEventWritesAfter(-1);
        String interrupted = fx.store.runIds().getFirst();
        String gone = services.start(request(DEPLOY), null, null).runId();
        fx.store.forget(gone);

        LedgerReport report = services.ledgerReport();

        assertThat(report.started()).isEqualTo(4);
        assertThat(report.ended()).isEqualTo(1);
        assertThat(report.open()).extracting(LedgerReport.Open::runId, LedgerReport.Open::folder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(parked, "PARKED"),
                        org.assertj.core.groups.Tuple.tuple(interrupted, "INTERRUPTED"),
                        org.assertj.core.groups.Tuple.tuple(gone, LedgerReport.GONE));
        assertThat(report.open()).allSatisfy(run -> {
            assertThat(run.startedAt()).isNotNull();
            assertThat(run.workflowId()).startsWith("wf-");
        });
        assertThat(report.endedGone()).isEmpty();
        assertThat(report.problems()).extracting(LedgerReport.Problem::runId, LedgerReport.Problem::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(gone, "lost"));
        assertThat(report.ok()).as("a run began, and nothing says what became of it").isFalse();
        assertThat(finished).isNotIn(report.open().stream().map(LedgerReport.Open::runId).toList());
    }

    @Test
    void theLedgerReport_namesAFinishedRunWhoseFolderIsGone_withoutFailing_andAnEntryThatWasRemoved() {
        String kept = services.start(request(BUILD), null, null).runId();
        String gone = services.start(request(BUILD), null, null).runId();
        fx.store.forget(gone);

        LedgerReport report = services.ledgerReport();

        assertThat(report.open()).isEmpty();
        assertThat(report.endedGone()).containsExactly(new LedgerReport.Named(gone, "COMPLETED"));
        assertThat(report.ok()).isTrue();

        fx.ledger.removeStart(kept);

        LedgerReport after = services.ledgerReport();
        assertThat(after.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.runId()).isEqualTo(kept);
            assertThat(problem.code()).isEqualTo("entry-changed");
        });
        assertThat(after.started()).as("the history still names the start").isEqualTo(2);
        assertThat(after.ok()).isFalse();
    }

    /** Two runs that start at the same time both get their entry. */
    @Test
    void twoRunsStartingAtOnce_bothGetTheirEntry() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> runs = List.of(
                    pool.submit(() -> { go.await(); return services.start(request(BUILD), null, null).runId(); }),
                    pool.submit(() -> { go.await(); return services.start(request(BUILD), null, null).runId(); }));
            go.countDown();
            for (Future<String> run : runs) {
                String id = run.get();
                assertThat(fx.ledger.findStart(id)).isPresent();
                assertThat(services.verify(id).ok()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(fx.ledger.starts()).isEqualTo(2);
    }

    /**
     * The recorded run of an earlier version ({@code deploy-parked-checkpoint-v1.json}), parked at its approval. Its
     * id is the first one the fixture gives out, so a test loads it before it starts a run.
     */
    private String loadEarlier() throws Exception {
        JsonNode fixture;
        try (InputStream in = LedgerStartTest.class.getResourceAsStream("/records/deploy-parked-checkpoint-v1.json")) {
            fixture = JSON.readTree(in);
        }
        String id = fixture.get("run_id").asText();
        try (var created = fx.store.create(id, fx.clock.now())) {
            fixture.get("lines").forEach(line ->
                    created.events().append(line.asText().getBytes(StandardCharsets.UTF_8)));
        }
        var run = fx.run(id);
        fixture.get("blobs").properties().forEach(blob ->
                run.blobs().put(blob.getValue().asText().getBytes(StandardCharsets.UTF_8)));
        RecordedEvent started = fx.events(id).getFirst();
        fx.packages.put(Path.of(started.fields().path("package").path("dir").asText()),
                fx.text(id, started, "definition"));
        return id;
    }
}
