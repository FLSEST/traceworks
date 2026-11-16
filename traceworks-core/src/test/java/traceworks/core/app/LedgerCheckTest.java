package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordedEvent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.request;

/**
 * The check of the ledger against the runs folder ({@link LedgerReport}) and the ways around "started, never ended,
 * folder gone" that need no rewritten history: a folder made again, a forged end entry, a commit without its entry,
 * a ledger that was started over; and the acknowledgement that settles a false alarm on the record.
 */
class LedgerCheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();

    private String parked() {
        return services.start(request(DEPLOY), null, null).runId();
    }

    private String finished() {
        return services.start(request(BUILD), null, null).runId();
    }

    private static List<org.assertj.core.groups.Tuple> codes(LedgerReport report) {
        return report.problems().stream().map(problem -> tuple(problem.runId(), problem.code())).toList();
    }

    /** rm -rf the folder and mkdir it again: a folder without a record is no better than no folder. */
    @Test
    void aFolderMadeAgainWithoutARecord_failsLikeAFolderThatIsGone() {
        String id = parked();
        fx.store.forget(id);
        fx.store.create(id, fx.clock.now()).close();

        LedgerReport report = services.ledgerReport();

        assertThat(report.open()).extracting(LedgerReport.Open::folder).containsExactly(LedgerReport.UNREADABLE);
        assertThat(codes(report)).containsExactly(tuple(id, "unreadable"));
        assertThat(report.ok()).isFalse();
    }

    /** A folder with some other record under the same id: the start entry names the first line that started. */
    @Test
    void aFolderWithAnotherRecordUnderTheSameId_fails() {
        String id = parked();
        String line = new String(fx.events(id).getFirst().raw(), StandardCharsets.UTF_8);
        fx.store.forget(id);
        try (var again = fx.store.create(id, fx.clock.now())) {
            again.events().append(line.replace("\"actor\":\"" + AppFixture.ACTOR + "\"", "\"actor\":\"someone\"")
                    .getBytes(StandardCharsets.UTF_8));
        }

        LedgerReport report = services.ledgerReport();

        assertThat(report.open()).extracting(LedgerReport.Open::folder).containsExactly(LedgerReport.OTHER_RECORD);
        assertThat(codes(report)).containsExactly(tuple(id, "other-record"));
    }

    /**
     * A record cut to its first line cannot be told from a crash right after the start: it reads INTERRUPTED, and its
     * own verify holds. What stays is that the run is listed as started and not ended, for good.
     */
    @Test
    void aRecordCutToItsFirstLine_readsAsACrash_andStaysListedAsNotEnded() {
        String id = parked();
        fx.run(id).log().dropLast(fx.events(id).size() - 1);

        LedgerReport report = services.ledgerReport();

        assertThat(report.open()).extracting(LedgerReport.Open::runId, LedgerReport.Open::folder)
                .containsExactly(tuple(id, "INTERRUPTED"));
        assertThat(report.ok()).as("nothing tells it from a crash").isTrue();
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** One added commit with runs/<id>.json: the end entry is parsed and must agree with its commit and its start. */
    @Test
    void aForgedEndEntry_doesNotMoveALostRunToEnded() throws Exception {
        String id = parked();
        String runStarted = EventChain.sha256Hex(fx.events(id).getFirst().raw());
        fx.store.forget(id);

        fx.ledger.forgeEnd(id, "COMPLETED", "anything".getBytes(StandardCharsets.UTF_8));
        assertThat(services.ledgerReport().problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("entry-invalid");
            assertThat(problem.text()).contains("no manifest of this run");
        });

        ObjectNode manifest = JSON.createObjectNode().put("run_id", id).put("status", "FAILED");
        fx.ledger.forgeEnd(id, "COMPLETED", JSON.writeValueAsBytes(manifest));
        assertThat(services.ledgerReport().problems()).singleElement().satisfies(problem ->
                assertThat(problem.text()).contains("the end entry says FAILED", "its commit says COMPLETED"));

        manifest.put("status", "COMPLETED");
        fx.ledger.forgeEnd(id, "COMPLETED", JSON.writeValueAsBytes(manifest));
        assertThat(services.ledgerReport().problems()).singleElement().satisfies(problem ->
                assertThat(problem.text()).contains("does not agree with the start entry", runStarted));

        // What nothing in this version catches: the ledger is not signed, and the start entry says which line to name
        manifest.put("run_started", runStarted);
        fx.ledger.forgeEnd(id, "COMPLETED", JSON.writeValueAsBytes(manifest));
        LedgerReport forged = services.ledgerReport();
        assertThat(forged.ok()).isTrue();
        assertThat(forged.endedGone()).containsExactly(new LedgerReport.Named(id, "COMPLETED"));
    }

    /** The manifest names the run.started line from now on, which is what ties an end entry to its start entry. */
    @Test
    void theManifestNamesTheRunStartedLine_andOneWithoutItDoesNotVerifyForARunTheLedgerSawStart() throws Exception {
        String id = finished();
        JsonNode manifest = JSON.readTree(fx.run(id).readManifest().orElseThrow());
        assertThat(manifest.path("run_started").asText())
                .isEqualTo(EventChain.sha256Hex(fx.events(id).getFirst().raw()));
        assertThat(services.ledgerReport().ok()).isTrue();

        String without = new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8)
                .replaceFirst("(?m)^ {2}\"run_started\" : \"[0-9a-f]{64}\",\\R", "");
        assertThat(without).doesNotContain("run_started");
        fx.run(id).writeManifest(without.getBytes(StandardCharsets.UTF_8));

        assertThat(services.verify(id).manifest()).isEqualTo(VerifyReport.MISMATCH);
    }

    /** A manifest an earlier version wrote has no run_started, and still verifies. */
    @Test
    void aManifestOfAnEarlierVersion_withoutTheRunStartedLine_stillVerifies() throws Exception {
        String id = loadEarlier();
        services.cancel(id);
        String without = new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8)
                .replaceFirst("(?m)^ {2}\"run_started\" : \"[0-9a-f]{64}\",\\R", "");
        assertThat(without).doesNotContain("run_started");
        fx.run(id).writeManifest(without.getBytes(StandardCharsets.UTF_8));
        fx.ledger.forgeEnd(id, "CANCELLED", without.getBytes(StandardCharsets.UTF_8));

        VerifyReport report = services.verify(id);

        assertThat(report.manifest()).isEqualTo(VerifyReport.OK);
        assertThat(report.ledger()).isEqualTo(VerifyReport.OK);
        assertThat(report.ok()).isTrue();
        LedgerReport home = services.ledgerReport();
        assertThat(home.ok()).isTrue();
        assertThat(home.earlier()).isEqualTo(1);
    }

    /** An empty commit 'run <id> STARTED': the ledger names a run it holds nothing of. A failure one can acknowledge. */
    @Test
    void aCommitThatNamesAStartAndAddsNoEntry_isAFailure_untilItIsAcknowledged() {
        String id = "11111111-2222-3333-4444-555555555555";
        fx.ledger.nameStart(id);

        LedgerReport report = services.ledgerReport();

        assertThat(codes(report)).containsExactly(tuple(id, "entry-removed"));
        assertThat(report.problems().getFirst().text()).contains("a later commit removed it, or the commit added none");

        LedgerReport after = services.acknowledge(id, "a commit made by hand while testing the ledger");
        assertThat(after.ok()).isTrue();
        assertThat(after.acknowledged()).singleElement().satisfies(run -> assertThat(run.covers()).isTrue());
    }

    /**
     * The ledger was deleted and made again, or another ledger_dir was in force for a run: the check walks the run
     * folders too. A record that says its start was noted must have its entry, a finished one its end entry, and a
     * run of an earlier version is counted as one.
     */
    @Test
    void aLedgerThatWasStartedOver_isFoundFromTheRunFolders() throws Exception {
        String earlier = loadEarlier();
        String parked = parked();
        String finished = finished();
        fx.ledger.startOver();

        LedgerReport report = services.ledgerReport();

        assertThat(report.started()).isZero();
        assertThat(codes(report)).containsExactlyInAnyOrder(tuple(parked, "no-start-entry"),
                tuple(finished, "no-start-entry"));
        assertThat(report.problems()).allSatisfy(problem ->
                assertThat(problem.text()).contains("the record says the ledger noted the start"));
        assertThat(report.earlier()).isEqualTo(1);
        assertThat(report.earlierOpen()).containsExactly(new LedgerReport.Named(earlier, "PARKED"));
        assertThat(report.ok()).isFalse();
    }

    /** A record that has its end and no end entry, as the per-run check says: found for the whole home too. */
    @Test
    void aFinishedRunWithoutItsEndEntry_isAFailure() {
        String id = finished();
        byte[] start = fx.ledger.findStart(id).orElseThrow();
        fx.ledger.startOver();
        fx.ledger.replaceStart(id, start);

        LedgerReport report = services.ledgerReport();

        assertThat(codes(report)).containsExactly(tuple(id, "no-end-entry"));
        assertThat(report.problems().getFirst().text()).contains("COMPLETED", "traceworks resume " + id);
        assertThat(report.open()).extracting(LedgerReport.Open::folder).containsExactly("COMPLETED");
    }

    @Test
    void anAcknowledgement_needsANote_andARunTheCheckReports() {
        String healthy = parked();
        String lost = parked();
        String alsoLost = parked();
        fx.store.forget(lost);
        fx.store.forget(alsoLost);

        assertThatThrownBy(() -> services.acknowledge(lost, "  ")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("needs a note");
        assertThatThrownBy(() -> services.acknowledge(healthy, "why not")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("reports nothing of a run");
        assertThatThrownBy(() -> services.acknowledge("0000", "all of them"))
                .as("one run at a time").isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the start of 2 runs");
        assertThat(services.ledgerReport().acknowledged()).isEmpty();
        assertThat(services.ledgerReport().ok()).isFalse();
    }

    /** Who, when, what the check said and the note: in the ledger, and listed for good. */
    @Test
    void anAcknowledgedRun_isListedWithItsNote_andNoLongerFails_butAnotherProblemOfItDoes() throws Exception {
        String id = parked();
        String line = new String(fx.events(id).getFirst().raw(), StandardCharsets.UTF_8);
        fx.store.forget(id);

        LedgerReport after = services.acknowledge(id, "deleted by hand on 2026-11-16, the disk was full");

        assertThat(after.ok()).isTrue();
        assertThat(after.problems()).isEmpty();
        assertThat(after.open()).as("still started and not ended").extracting(LedgerReport.Open::folder)
                .containsExactly(LedgerReport.GONE);
        assertThat(after.acknowledged()).singleElement().satisfies(run -> {
            assertThat(run.runId()).isEqualTo(id);
            assertThat(run.actor()).isEqualTo(AppFixture.ACTOR);
            assertThat(java.time.Instant.parse(run.at())).isNotNull();
            assertThat(run.note()).isEqualTo("deleted by hand on 2026-11-16, the disk was full");
            assertThat(run.problem()).isEqualTo("lost");
            assertThat(run.said()).isEqualTo("started, never ended, and the folder is gone");
            assertThat(run.covers()).isTrue();
        });
        JsonNode held = JSON.readTree(fx.ledger.runs().runs().getFirst().acks());
        assertThat(held.path("run_id").asText()).isEqualTo(id);
        assertThat(held.path("actor").asText()).isEqualTo(AppFixture.ACTOR);
        assertThat(held.path("problem").asText()).isEqualTo("lost");

        // Another record appears under the id: that is not what was acknowledged
        try (var again = fx.store.create(id, fx.clock.now())) {
            again.events().append(line.replace("\"actor\":\"" + AppFixture.ACTOR + "\"", "\"actor\":\"someone\"")
                    .getBytes(StandardCharsets.UTF_8));
        }
        LedgerReport other = services.ledgerReport();
        assertThat(codes(other)).containsExactly(tuple(id, "other-record"));
        assertThat(other.problems().getFirst().text()).contains("acknowledged for another reason: lost");
        assertThat(other.acknowledged()).singleElement().satisfies(run -> assertThat(run.covers()).isFalse());
    }

    /** A ledger that cannot be relied on fails the check as a whole. */
    @Test
    void aLedgerThatCannotBeReliedOn_failsTheCheck() {
        parked();
        fx.ledger.failReads(new IllegalStateException("it has the replace ref refs/replace/abc"));

        assertThatThrownBy(services::ledgerReport).isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining("refs/replace/abc");
    }

    /** The recorded run of an earlier version, parked at its approval; its id is the first the fixture gives out. */
    private String loadEarlier() throws Exception {
        JsonNode fixture;
        try (InputStream in = LedgerCheckTest.class.getResourceAsStream("/records/deploy-parked-checkpoint-v1.json")) {
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
        assertThat(fx.ids.newRunId()).as("the fixture's own id is taken").isEqualTo(id);
        return id;
    }
}
