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
        assertThat(before.path("seq").isNull()).as("no register yet").isTrue();
        JsonNode read = startEntry(second).path("register");
        JsonNode recorded = fx.events(second).getFirst().fields().path("register");
        assertThat(read.path("seq").asInt()).isEqualTo(recorded.path("seq").asInt());
        assertThat(read.path("head").asText()).isEqualTo(recorded.path("head").asText()).hasSize(64);
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
