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

    /**
     * One added commit with runs/<id>.json: the end entry is parsed and must agree with its commit and its start
     * entry. One that does not is a failure of its own, and the run is still started, never ended, folder gone.
     */
    @Test
    void anEndEntryThatIsNoManifestOfTheRun_orDisagreesWithItsCommitOrItsStart_fails_andTheRunStaysLost()
            throws Exception {
        String id = parked();
        String runStarted = EventChain.sha256Hex(fx.events(id).getFirst().raw());
        fx.store.forget(id);

        fx.ledger.forgeEnd(id, "COMPLETED", "anything".getBytes(StandardCharsets.UTF_8));
        LedgerReport report = services.ledgerReport();
        assertThat(codes(report)).as("every problem of the run, not the first one")
                .containsExactlyInAnyOrder(tuple(id, "entry-invalid"), tuple(id, "lost"));
        assertThat(report.problems()).anySatisfy(problem ->
                assertThat(problem.text()).contains("no manifest of this run"));
        assertThat(report.open()).extracting(LedgerReport.Open::folder).containsExactly(LedgerReport.GONE);
        assertThat(report.endedGone()).isEmpty();

        ObjectNode manifest = JSON.createObjectNode().put("run_id", id).put("status", "FAILED");
        fx.ledger.forgeEnd(id, "COMPLETED", JSON.writeValueAsBytes(manifest));
        assertThat(services.ledgerReport().problems()).anySatisfy(problem ->
                assertThat(problem.text()).contains(") says FAILED", "its commit says COMPLETED"));

        manifest.put("status", "COMPLETED");
        fx.ledger.forgeEnd(id, "COMPLETED", JSON.writeValueAsBytes(manifest));
        assertThat(services.ledgerReport().problems()).anySatisfy(problem ->
                assertThat(problem.text()).contains("does not agree with the start entry", runStarted));
        assertThat(codes(services.ledgerReport())).contains(tuple(id, "lost"));
    }

    /**
     * What nothing in this version finds: the ledger is not signed, and the start entry says which line to name. An
     * end entry made by hand that is a manifest of the run with the right status and line moves a lost run to ended.
     */
    @Test
    void aWellFormedEndEntryMadeByHand_isNotFound_andTheRunReadsAsEnded() throws Exception {
        String id = parked();
        ObjectNode manifest = JSON.createObjectNode().put("run_id", id).put("status", "COMPLETED")
                .put("run_started", EventChain.sha256Hex(fx.events(id).getFirst().raw()));
        fx.store.forget(id);

        fx.ledger.forgeEnd(id, "COMPLETED", JSON.writeValueAsBytes(manifest));

        LedgerReport forged = services.ledgerReport();
        assertThat(forged.ok()).isTrue();
        assertThat(forged.endedGone()).containsExactly(new LedgerReport.Named(id, "COMPLETED"));
    }

    /**
     * No history is rewritten: a later, ordinary commit puts another manifest over the end entry, after the record
     * was rewritten to match. The entry is no longer what its own commit added, and both checks say so.
     */
    @Test
    void anEndEntryChangedByALaterCommit_failsTheCheckOfTheHome_andOfTheRun() {
        String id = finished();
        byte[] manifest = fx.run(id).readManifest().orElseThrow();
        assertThat(services.verify(id).ok()).isTrue();

        fx.ledger.changeEnd(id, manifest, "4f1d2c3b4a59");

        LedgerReport report = services.ledgerReport();
        assertThat(codes(report)).containsExactly(tuple(id, "entry-changed"));
        assertThat(report.problems().getFirst().text()).contains("commit 4f1d2c3b4a59", "runs/" + id + ".json");
        VerifyReport verify = services.verify(id);
        assertThat(verify.ok()).isFalse();
        assertThat(verify.ledger()).startsWith("changed").contains("commit 4f1d2c3b4a59", "runs/" + id + ".json");
    }

    /** The manifest in the run folder must be the end entry, byte for byte: the entry is a copy of it. */
    @Test
    void aManifestInTheRunFolderThatIsNotTheEndEntry_failsTheCheckOfTheHome() {
        String id = finished();
        String manifest = new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(services.ledgerReport().ok()).isTrue();

        fx.run(id).writeManifest(manifest.replace("\"status\" : \"COMPLETED\"", "\"status\" :  \"COMPLETED\"")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(codes(services.ledgerReport())).containsExactly(tuple(id, "manifest-differs"));

        fx.run(id).removeManifest();
        assertThat(codes(services.ledgerReport())).containsExactly(tuple(id, "manifest-differs"));
    }

    /**
     * A run whose process is alive between run.started and the commit of its start entry (it waits for the lock),
     * or between run.finished and its end entry, is in flight: listed as that, and no failure. Without a live
     * writer the same record is a problem.
     */
    @Test
    void aRunInFlight_beforeItsStartEntry_orBeforeItsEndEntry_isListedAndDoesNotFail() {
        String starting = parked();
        String ending = finished();
        byte[] start = fx.ledger.findStart(ending).orElseThrow();
        fx.ledger.startOver();
        fx.ledger.replaceStart(ending, start);
        fx.run(starting).simulateOtherWriter(true);
        fx.run(ending).simulateOtherWriter(true);

        LedgerReport flying = services.ledgerReport();

        assertThat(flying.problems()).isEmpty();
        assertThat(flying.ok()).isTrue();
        assertThat(flying.inFlight()).extracting(LedgerReport.Named::runId, LedgerReport.Named::detail)
                .containsExactlyInAnyOrder(tuple(starting, "its start entry is not written yet (run.started 0 s ago)"),
                        tuple(ending, "its end entry is not written yet (run.finished 0 s ago)"));

        fx.run(starting).simulateWriterDied();
        fx.run(ending).simulateWriterDied();

        LedgerReport dead = services.ledgerReport();
        assertThat(dead.inFlight()).isEmpty();
        assertThat(codes(dead)).containsExactlyInAnyOrder(tuple(starting, "no-start-entry"),
                tuple(ending, "no-end-entry"));
    }

    private static traceworks.core.port.Clock later(AppFixture fx, java.time.Duration by) {
        java.time.Instant then = fx.clock.now().plus(by);
        return () -> then;
    }

    /**
     * Holding the lock of a run is not writing its entry: a process that keeps the lock (a sleep will do) keeps a
     * run in flight only as long as an entry may take. Past that the missing entry fails the check, and says that a
     * process holds the lock.
     */
    @Test
    void aLockHeldPastTheBound_doesNotKeepARunInFlight() {
        String starting = parked();
        String ending = finished();
        byte[] start = fx.ledger.findStart(ending).orElseThrow();
        fx.ledger.startOver();
        fx.ledger.replaceStart(ending, start);
        fx.run(starting).simulateOtherWriter(true);
        fx.run(ending).simulateOtherWriter(true);

        LedgerReport soon = LedgerReport.of(fx.ledger, fx.store, later(fx, java.time.Duration.ofSeconds(90)), true);
        assertThat(soon.ok()).isTrue();
        assertThat(soon.inFlight()).extracting(LedgerReport.Named::detail).allSatisfy(detail ->
                assertThat(detail).containsPattern("is not written yet \\(run\\.(started|finished) 9\\d s ago\\)"));

        LedgerReport late = LedgerReport.of(fx.ledger, fx.store, later(fx, LedgerReport.IN_FLIGHT.plusSeconds(60)), true);
        assertThat(late.inFlight()).isEmpty();
        assertThat(codes(late)).containsExactlyInAnyOrder(tuple(starting, "no-start-entry"),
                tuple(ending, "no-end-entry"));
        assertThat(late.problems()).allSatisfy(problem -> assertThat(problem.text())
                .contains("a process holds the lock of the run", "6 min old"));
    }

    /** The sentence of the one problem of {@code code} the check reports of the run. */
    private String said(String id, String code) {
        return services.ledgerReport().problems().stream()
                .filter(problem -> problem.runId().equals(id) && problem.code().equals(code))
                .map(LedgerReport.Problem::text).reduce((a, b) -> b).orElseThrow(() -> new AssertionError(
                        "no " + code + " of " + id + " in " + services.ledgerReport().problems()));
    }

    private void record(String id, String... lines) {
        if (fx.store.runIds().contains(id)) {
            fx.store.forget(id);
        }
        try (var again = fx.store.create(id, fx.clock.now())) {
            for (String line : lines) {
                again.events().append(line.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * An acknowledgement covers a problem by its kind and its sentence, so the sentence of every kind must hold what
     * tells one case from another: for each kind, the same run with other content says something else.
     */
    @Test
    void theSentenceOfEveryKindOfProblem_changesWhenItsContentDoes() throws Exception {
        java.util.Map<String, String[]> said = new java.util.LinkedHashMap<>();

        // entry-invalid: an end entry that is no manifest, one with another status, a start entry that is none
        String forged = parked();
        fx.ledger.forgeEnd(forged, "COMPLETED", "anything".getBytes(StandardCharsets.UTF_8));
        String first = said(forged, "entry-invalid");
        fx.ledger.forgeEnd(forged, "COMPLETED", "something else".getBytes(StandardCharsets.UTF_8));
        said.put("entry-invalid: no manifest", new String[] {first, said(forged, "entry-invalid")});
        ObjectNode manifest = JSON.createObjectNode().put("run_id", forged).put("status", "FAILED");
        fx.ledger.forgeEnd(forged, "COMPLETED", JSON.writeValueAsBytes(manifest));
        first = said(forged, "entry-invalid");
        fx.ledger.forgeEnd(forged, "COMPLETED", JSON.writeValueAsBytes(manifest.put("gate", "GREEN")));
        said.put("entry-invalid: status", new String[] {first, said(forged, "entry-invalid")});
        String line = EventChain.sha256Hex(fx.events(forged).getFirst().raw());
        manifest.put("status", "COMPLETED").put("run_started", "0".repeat(64));
        fx.ledger.forgeEnd(forged, "COMPLETED", JSON.writeValueAsBytes(manifest));
        first = said(forged, "entry-invalid");
        fx.ledger.forgeEnd(forged, "COMPLETED", JSON.writeValueAsBytes(manifest.put("gate", "RED")));
        said.put("entry-invalid: start", new String[] {first, said(forged, "entry-invalid")});
        assertThat(first).contains(line);
        String noStart = parked();
        fx.ledger.replaceStart(noStart, "garbage".getBytes(StandardCharsets.UTF_8));
        first = said(noStart, "entry-invalid");
        fx.ledger.replaceStart(noStart, "other garbage".getBytes(StandardCharsets.UTF_8));
        said.put("entry-invalid: start entry", new String[] {first, said(noStart, "entry-invalid")});

        // entry-changed: another commit
        String changed = finished();
        byte[] end = fx.run(changed).readManifest().orElseThrow();
        fx.ledger.changeEnd(changed, end, "aaaaaaaaaaaa");
        first = said(changed, "entry-changed");
        fx.ledger.changeEnd(changed, end, "bbbbbbbbbbbb");
        said.put("entry-changed", new String[] {first, said(changed, "entry-changed")});

        // manifest-differs: another manifest in the folder
        String differs = finished();
        String text = new String(fx.run(differs).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        fx.run(differs).writeManifest(text.replace("COMPLETED", "FAILED").getBytes(StandardCharsets.UTF_8));
        first = said(differs, "manifest-differs");
        fx.run(differs).writeManifest(text.replace("COMPLETED", "CANCELLED").getBytes(StandardCharsets.UTF_8));
        said.put("manifest-differs", new String[] {first, said(differs, "manifest-differs")});

        // lost: the start entry names another first line
        String lost = parked();
        traceworks.core.record.StartEntry noted = traceworks.core.record.StartEntry.parse(
                fx.ledger.findStart(lost).orElseThrow()).orElseThrow();
        fx.store.forget(lost);
        first = said(lost, "lost");
        fx.ledger.replaceStart(lost, new traceworks.core.record.StartEntry(lost, noted.startedAt(), "f".repeat(64),
                noted.workflowId(), noted.packageDigest(), null, noted.version()).toJson());
        said.put("lost", new String[] {first, said(lost, "lost")});

        // other-record and unreadable: another first line in the folder
        String other = parked();
        String started = new String(fx.events(other).getFirst().raw(), StandardCharsets.UTF_8);
        record(other, started.replace("\"actor\":\"" + AppFixture.ACTOR + "\"", "\"actor\":\"someone\""));
        first = said(other, "other-record");
        record(other, started.replace("\"actor\":\"" + AppFixture.ACTOR + "\"", "\"actor\":\"someone else\""));
        said.put("other-record", new String[] {first, said(other, "other-record")});
        record(other, "no event");
        first = said(other, "unreadable");
        record(other, "no event either");
        said.put("unreadable", new String[] {first, said(other, "unreadable")});

        // no-start-entry and no-end-entry: another record in a folder the ledger does not know
        String unknown = "11111111-2222-3333-4444-555555555555";
        String its = started.replace(other, unknown);
        String finish = new String(fx.events(changed).getLast().raw(), StandardCharsets.UTF_8).replace(changed, unknown);
        record(unknown, its, finish);
        String firstStart = said(unknown, "no-start-entry");
        String firstEnd = said(unknown, "no-end-entry");
        record(unknown, its.replace("\"actor\":\"" + AppFixture.ACTOR + "\"", "\"actor\":\"someone\""),
                finish.replace("\"status\":\"COMPLETED\"", "\"status\":\"FAILED\""));
        said.put("no-start-entry", new String[] {firstStart, said(unknown, "no-start-entry")});
        said.put("no-end-entry", new String[] {firstEnd, said(unknown, "no-end-entry")});

        assertThat(said.keySet().stream().map(kind -> kind.replaceFirst(":.*", "")).distinct())
                .as("every kind of problem the check knows").containsExactlyInAnyOrder("entry-invalid",
                        "entry-changed", "manifest-differs", "lost", "other-record", "unreadable", "no-start-entry",
                        "no-end-entry");
        said.forEach((kind, sentences) -> assertThat(sentences[1]).as(kind).isNotEqualTo(sentences[0]));
    }

    /** The last line of the record of {@code id} with another status, the rest and the manifest left alone. */
    private void forgeRecord(String id, String status) {
        String last = new String(fx.events(id).getLast().raw(), StandardCharsets.UTF_8);
        fx.run(id).log().replaceLast(last.replaceFirst("\"status\":\"[A-Z_]+\"", "\"status\":\"" + status + "\"")
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A record rewritten while its manifest is left alone: the check of the home verifies the record of every ended
     * run against its end entry, so it does not take the manifest's word. With --quick it reads the first line and
     * the manifest only, and says so.
     */
    @Test
    void aRecordForgedWhileItsManifestIsLeftAlone_failsTheCheckOfTheHome_unlessQuick() {
        String id = finished();
        assertThat(services.ledgerReport().ok()).isTrue();
        assertThat(services.ledgerReport().records()).isTrue();

        forgeRecord(id, "FAILED");

        LedgerReport report = services.ledgerReport();
        assertThat(codes(report)).containsExactly(tuple(id, "record-broken"));
        String first = report.problems().getFirst().text();
        assertThat(first).contains("is not the manifest the record gives", "traceworks verify " + id);
        forgeRecord(id, "CANCELLED");
        assertThat(services.ledgerReport().problems().getFirst().text()).as("another forgery, another sentence")
                .isNotEqualTo(first);

        LedgerReport quick = services.ledgerReport(false);
        assertThat(quick.ok()).isTrue();
        assertThat(quick.records()).isFalse();
    }

    /**
     * An entry no hash covers is not judged, and the checks say so instead of a plain OK: the home's check names
     * the paths, and the run's check says it on its ledger line. Neither fails: the next run pins the entry.
     */
    @Test
    void anEntryNoHashCovers_isNamedAsNotJudged_byBothChecks() {
        String id = finished();
        fx.ledger.endWithoutHash(id);

        LedgerReport home = services.ledgerReport();
        assertThat(home.ok()).isTrue();
        assertThat(home.unjudged()).containsExactly("runs/" + id + ".json");
        VerifyReport run = services.verify(id);
        assertThat(run.ok()).isTrue();
        assertThat(run.ledgerReason()).startsWith("not judged: no hash covers runs/" + id + ".json");
        assertThat(services.verify(finished()).ledgerReason()).isNull();
    }

    /** A problem of the anchors of a register is listed under registers.<key>, and acknowledged like one of a run. */
    @Test
    void aProblemOfTheAnchorsOfARegister_isListedAndAcknowledgedLikeOneOfARun() {
        fx.ledger.anchorProblem("hello", "commit 4f1d2c3b4a59 (\"register hello seq 1\") names registers/hello.jsonl"
                + " without a SHA-256");

        LedgerReport report = services.ledgerReport();
        assertThat(codes(report)).containsExactly(tuple("registers.hello", "entry-changed"));
        assertThat(report.open()).isEmpty();

        LedgerReport after = services.acknowledge("registers.hello", "one run of an older jar on 2026-11-16");
        assertThat(after.ok()).isTrue();
        assertThat(after.acknowledged()).singleElement().satisfies(ack ->
                assertThat(ack.said()).contains("commit 4f1d2c3b4a59"));
    }

    /** A folder that was just created and has no line yet, with its process alive: in flight too. */
    @Test
    void aRunFolderWithoutALineYet_whoseProcessIsAlive_isInFlight() {
        String id = "11111111-2222-3333-4444-555555555555";
        var created = fx.store.create(id, fx.clock.now());

        LedgerReport report = services.ledgerReport();
        assertThat(report.ok()).isTrue();
        assertThat(report.inFlight()).containsExactly(new LedgerReport.Named(id, "its record has no line yet"));

        created.close();
        assertThat(codes(services.ledgerReport())).containsExactly(tuple(id, "unreadable"));
    }

    /**
     * An acknowledgement covers the problems it was made on, by what the check said: a problem of the run that
     * comes later is open again, also one of the same kind with another content.
     */
    @Test
    void anAcknowledgement_coversWhatWasSaid_andALaterProblemOfTheSameKindIsOpenAgain() {
        String id = finished();
        byte[] manifest = fx.run(id).readManifest().orElseThrow();
        fx.ledger.changeEnd(id, manifest, "aaaaaaaaaaaa");

        LedgerReport acknowledged = services.acknowledge(id, "a test commit of mine, the entry is the same bytes");
        assertThat(acknowledged.ok()).isTrue();

        fx.ledger.changeEnd(id, manifest, "bbbbbbbbbbbb");

        LedgerReport again = services.ledgerReport();
        assertThat(again.ok()).as("another commit touched the entry").isFalse();
        assertThat(again.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("entry-changed");
            assertThat(problem.text()).contains("bbbbbbbbbbbb");
        });
        assertThat(again.acknowledged()).singleElement().satisfies(run -> {
            assertThat(run.said()).contains("aaaaaaaaaaaa");
            assertThat(run.covers()).isTrue();
        });
    }

    /** One call acknowledges every problem the check reports of the run then, each by what was said. */
    @Test
    void anAcknowledgementOfARunWithTwoProblems_namesBoth() throws Exception {
        String id = parked();
        fx.store.forget(id);
        fx.ledger.forgeEnd(id, "COMPLETED", "anything".getBytes(StandardCharsets.UTF_8));
        assertThat(services.ledgerReport().problems()).hasSize(2);

        LedgerReport after = services.acknowledge(id, "both are from a test of the ledger");

        assertThat(after.ok()).isTrue();
        assertThat(after.acknowledged()).extracting(LedgerReport.Acknowledged::problem)
                .containsExactlyInAnyOrder("entry-invalid", "lost");
        JsonNode held = JSON.readTree(fx.ledger.runs().runs().getFirst().acks());
        assertThat(held.path("problems")).hasSize(2);
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

        assertThat(codes(report)).containsExactly(tuple(id, "entry-changed"), tuple(id, "lost"));
        assertThat(report.problems().getFirst().text()).contains("a later commit removed it, or the commit added none");

        LedgerReport after = services.acknowledge(id, "a commit made by hand while testing the ledger");
        assertThat(after.ok()).isTrue();
        assertThat(after.acknowledged()).hasSize(2).allSatisfy(run -> assertThat(run.covers()).isTrue());
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
                tuple(finished, "no-start-entry"), tuple(finished, "no-end-entry"));
        assertThat(report.problems()).filteredOn(problem -> problem.code().equals("no-start-entry"))
                .allSatisfy(problem -> assertThat(problem.text()).contains("the record says the ledger noted the start"));
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
            assertThat(run.said()).startsWith("started, never ended, and the folder is gone (the ledger noted"
                    + " run.started ");
            assertThat(run.covers()).isTrue();
        });
        JsonNode held = JSON.readTree(fx.ledger.runs().runs().getFirst().acks());
        assertThat(held.path("run_id").asText()).isEqualTo(id);
        assertThat(held.path("actor").asText()).isEqualTo(AppFixture.ACTOR);
        assertThat(held.path("problems").get(0).path("problem").asText()).isEqualTo("lost");

        // Another record appears under the id: that is not what was acknowledged
        try (var again = fx.store.create(id, fx.clock.now())) {
            again.events().append(line.replace("\"actor\":\"" + AppFixture.ACTOR + "\"", "\"actor\":\"someone\"")
                    .getBytes(StandardCharsets.UTF_8));
        }
        LedgerReport other = services.ledgerReport();
        assertThat(codes(other)).containsExactly(tuple(id, "other-record"));
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
