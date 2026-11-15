package traceworks.core.app;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

/** What {@code verify} says about the bytes of a log after its last line feed (review C, L8). */
class VerificationTest {

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();

    /** No writer appends after run.finished: bytes there are tampering or damage, and verify says so. */
    @Test
    void bytesAfterTheLastLineOfAFinishedRun_failTheCheck() {
        String id = services.start(request(BUILD), null, null).runId();
        fx.run(id).log().tearTail("{\"seq\":99,\"type\":\"run.finished\"}".getBytes(StandardCharsets.UTF_8));

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.chain().intact()).isFalse();
        assertThat(report.chain().problems()).anySatisfy(problem ->
                assertThat(problem).contains("32 bytes after the last line feed", "after run.finished"));
    }

    /** A parked or interrupted run's torn last line is what a killed writer leaves; its next writer records it. */
    @Test
    void aTornLastLineOfAnUnfinishedRun_isNoFailure() {
        String id = services.start(request(DEPLOY), null, null).runId();
        fx.run(id).log().tearTail("{\"seq\":".getBytes(StandardCharsets.UTF_8));

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isTrue();
        assertThat(report.chain().intact()).isTrue();
    }

    /**
     * A {@code run.finished} of the run's own id that names a parent ended the run for the view, but not for the
     * check that wants a ledger entry: a forged record with a manifest to match and no ledger entry passed.
     */
    @Test
    void aRunFinishedThatNamesAParent_doesNotEscapeTheLedger() {
        String id = services.start(request(BUILD), null, null).runId();
        var run = fx.run(id);
        java.util.List<byte[]> lines = run.log().readAll();
        String last = new String(lines.getLast(), StandardCharsets.UTF_8);
        assertThat(last).contains("\"type\":\"run.finished\"").doesNotContain("\"parent\"");
        byte[] forged = last.replaceFirst("\\{", "{\"parent\":\"x\",").getBytes(StandardCharsets.UTF_8);
        run.log().dropLast(1);
        run.log().append(forged);
        // The forger writes the manifest too: the one of the run, with the new head
        String manifest = new String(run.readManifest().orElseThrow(), StandardCharsets.UTF_8);
        String oldHead = traceworks.core.record.EventChain.sha256Hex(lines.getLast());
        assertThat(manifest).contains(oldHead);
        run.writeManifest(manifest.replace(oldHead,
                traceworks.core.record.EventChain.sha256Hex(forged)).getBytes(StandardCharsets.UTF_8));

        VerifyReport report = fx.services(new traceworks.core.testing.InMemoryLedger()).verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.ledger()).isEqualTo(VerifyReport.MISSING);
        assertThat(report.chain().problems()).anySatisfy(problem ->
                assertThat(problem).contains("names a parent"));
    }

    /** A run that is its own parent has no view; it is a damaged record, not a stack that overflows. */
    @Test
    void aRunThatIsItsOwnParent_isADamagedRecord() {
        String id = "00000000-0000-0000-0000-00000000abcd";
        String line = "{\"seq\":0,\"at\":\"2026-11-12T10:00:00Z\",\"type\":\"run.started\",\"run\":\"" + id
                + "\",\"parent\":\"" + id + "\",\"prev\":\""
                + traceworks.core.record.EventChain.genesis(id)
                + "\",\"workflow\":{\"id\":\"w\",\"name\":\"w\"},\"package\":{},\"actor\":\"a\","
                + "\"trigger\":\"t\",\"execution\":\"container\"}";
        var created = fx.store.create(id, java.time.Instant.parse("2026-11-12T10:00:00Z"));
        created.events().append(line.getBytes(StandardCharsets.UTF_8));
        created.writeManifest("{}".getBytes(StandardCharsets.UTF_8));
        created.close();

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.manifest()).isEqualTo(VerifyReport.MISMATCH);
    }

    private String manifestOf(String id) {
        return new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8);
    }

    /** Which version wrote the manifest decides how the record gives it: one without it gives none. */
    @Test
    void aManifestWithoutTheVersionThatWroteIt_isAMismatch() {
        String id = services.start(request(BUILD), null, null).runId();
        String forged = manifestOf(id).replaceFirst(",\\s*\"traceworks_version\"\\s*:\\s*\"[^\"]*\"", "");
        assertThat(forged).doesNotContain("traceworks_version");
        fx.run(id).writeManifest(forged.getBytes(StandardCharsets.UTF_8));

        VerifyReport report = services.verify(id);

        assertThat(report.manifest()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(report.ok()).isFalse();
    }

    /** A run folder renamed after another run: one problem says so, and nothing is checked against the wrong run. */
    @Test
    void aRunFolderThatHoldsAnotherRun_isOneProblem() {
        String other = services.start(request(BUILD), null, null).runId();
        String id = services.start(request(BUILD), null, null).runId();
        var log = fx.run(id).log();
        log.dropLast(log.readAll().size());
        fx.run(other).log().readAll().forEach(log::append);

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.chain().problems()).containsExactly("run folder " + id + " holds run " + other);
        assertThat(report.manifest()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.ledger()).isEqualTo(VerifyReport.NOT_CHECKED);
    }

    /** A first line that cannot be read names no run: the chain report names the line, no renamed folder. */
    @Test
    void aFirstLineThatCannotBeRead_isNoRenamedFolder() {
        String id = services.start(request(BUILD), null, null).runId();
        var log = fx.run(id).log();
        java.util.List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        log.append("not a line of a record".getBytes(StandardCharsets.UTF_8));
        lines.subList(1, lines.size()).forEach(log::append);

        VerifyReport report = services.verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.chain().problems()).isNotEmpty().noneMatch(problem -> problem.contains("holds run"));
    }

    /** Without the manifest, the ledger's copy is checked against the record itself. */
    @Test
    void aFinishedRunWithoutItsManifest_hasItsLedgerCopyCheckedAgainstTheRecord() {
        String id = services.start(request(BUILD), null, null).runId();
        fx.run(id).removeManifest();

        VerifyReport report = services.verify(id);

        assertThat(report.manifest()).isEqualTo(VerifyReport.MISSING);
        assertThat(report.ledger()).isEqualTo(VerifyReport.OK);
        assertThat(report.ok()).isFalse();
    }

    /**
     * A deleted metadata blob hides the model it named, so the manifest's models are taken as far as they are
     * possible: the model that was there holds, a model too many does not.
     */
    @Test
    void aDeletedMetadataBlob_letsTheManifestKeepItsModel_butNotOneTooMany() throws java.io.IOException {
        fx.settings = OperatorSettings.defaults();
        fx.withClaude();
        fx.packages.put(java.nio.file.Path.of("/pkg/ask"), """
                {"id": "wf-ask", "name": "Ask", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi"}}
                ]}""");
        fx.claude.answers("hi");
        RunServices withAi = fx.services();
        String id = withAi.start(request(java.nio.file.Path.of("/pkg/ask")), null, null).runId();
        String manifest = manifestOf(id);
        var metadata = fx.ofType(id, "step.finished").getFirst().blob("metadata");
        String model = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(
                fx.run(id).blobs().get(metadata.sha256()).orElseThrow()).path("model").asText();
        assertThat(manifest).as("the manifest names the step's model").contains("\"" + model + "\"");
        fx.run(id).blobs().delete(metadata.sha256());

        assertThat(withAi.verify(id).manifest()).as("the model the record no longer shows").isEqualTo(VerifyReport.OK);

        fx.run(id).writeManifest(manifest.replace("\"" + model + "\"", "\"" + model + "\", \"zz-invented\"")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(withAi.verify(id).manifest()).as("one model more than blobs are deleted")
                .isEqualTo(VerifyReport.MISMATCH);
    }

    /** The models of a manifest are names: with a metadata blob deleted, an entry that is no text is still refused. */
    @Test
    void aDeletedMetadataBlob_doesNotLetTheManifestNameAModelThatIsNoText() throws java.io.IOException {
        fx.settings = OperatorSettings.defaults();
        fx.withClaude();
        fx.packages.put(java.nio.file.Path.of("/pkg/ask"), """
                {"id": "wf-ask", "name": "Ask", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi"}}
                ]}""");
        fx.claude.answers("hi");
        RunServices withAi = fx.services();
        String id = withAi.start(request(java.nio.file.Path.of("/pkg/ask")), null, null).runId();
        String manifest = manifestOf(id);
        var metadata = fx.ofType(id, "step.finished").getFirst().blob("metadata");
        String model = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(
                fx.run(id).blobs().get(metadata.sha256()).orElseThrow()).path("model").asText();
        assertThat(manifest).contains("\"" + model + "\"");
        fx.run(id).blobs().delete(metadata.sha256());
        assertThat(withAi.verify(id).manifest()).isEqualTo(VerifyReport.OK);

        for (String noText : new String[] {"7", "null", "{\"name\": \"" + model + "\"}"}) {
            fx.run(id).writeManifest(manifest.replace("\"" + model + "\"", noText).getBytes(StandardCharsets.UTF_8));

            VerifyReport report = withAi.verify(id);

            assertThat(report.manifest()).as("the model %s", noText).isEqualTo(VerifyReport.MISMATCH);
            assertThat(report.ok()).isFalse();
        }
    }

    // ---- the seal the record holds ----

    /** A sealed run that waits for its approval: no manifest and no ledger entry yet, so only the seal can fail it. */
    private static String sealedRunThatWaits(AppFixture sealed) {
        sealed.services().seals().seal(DEPLOY, null);
        return sealed.services().start(request(DEPLOY), null, null).runId();
    }

    /** Rewrites the run's {@code run.started} and chains the lines after it anew, as someone who can write would. */
    private static void rewriteStart(AppFixture fixture, String id, String from, String to) {
        var log = fixture.run(id).log();
        java.util.List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        String previous = null;
        for (byte[] bytes : lines) {
            String line = new String(bytes, StandardCharsets.UTF_8);
            if (previous == null) {
                assertThat(line).contains(from);
                line = line.replace(from, to);
            } else {
                line = line.replaceFirst("\"prev\":\"[0-9a-f]{64}\"", "\"prev\":\"" + previous + "\"");
            }
            previous = log.append(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The chain holds, and nothing else is checked yet: the seal's state alone decides. */
    private static void assertOnlyTheSealFails(VerifyReport report, String... sealTexts) {
        assertThat(report.chain().intact()).as(report.chain().problems().toString()).isTrue();
        assertThat(report.manifest()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.ledger()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.seal()).contains(sealTexts);
        assertThat(report.ok()).isFalse();
    }

    @Test
    void aSealedRunThatWaits_verifies_withItsSealOk() {
        AppFixture sealed = new AppFixture().allowSigner();
        String id = sealedRunThatWaits(sealed);

        VerifyReport report = sealed.services().verify(id);

        assertThat(report.manifest()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.ledger()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.seal()).startsWith(VerifyReport.OK).contains(AppFixture.ACTOR);
        assertThat(report.ok()).isTrue();
    }

    /** The signature covers the fields of the seal: a record that names another signer fails, with its chain whole. */
    @Test
    void aRecordedSealWhoseFieldsWereChanged_failsTheCheck_alsoWhenTheChainHolds() {
        AppFixture sealed = new AppFixture().allowSigner();
        String id = sealedRunThatWaits(sealed);
        rewriteStart(sealed, id, "\"signer\":\"" + AppFixture.ACTOR + "\"", "\"signer\":\"eve@box\"");

        assertOnlyTheSealFails(sealed.services().verify(id), "not validly signed", "does not match");
    }

    /** A valid signature, by another key than the one the record names. */
    @Test
    void aRecordedSealThatNamesAnotherKeyThanTheOneThatSigned_failsTheCheck() {
        AppFixture sealed = new AppFixture().allowSigner();
        String id = sealedRunThatWaits(sealed);
        rewriteStart(sealed, id, "\"key\":\"SHA256:", "\"key\":\"SHA256:x");

        assertOnlyTheSealFails(sealed.services().verify(id), "names the key SHA256:x",
                "but its signature was made with SHA256:");
    }

    /** A signature that is no SSH signature is not taken for no seal. */
    @Test
    void aRecordedSealWhoseSignatureCannotBeRead_failsTheCheck() {
        AppFixture sealed = new AppFixture().allowSigner();
        String id = sealedRunThatWaits(sealed);
        rewriteStart(sealed, id, "-----BEGIN SSH SIGNATURE-----", "-----BEGIN NO SIGNATURE-----");

        assertOnlyTheSealFails(sealed.services().verify(id), "the recorded seal cannot be read",
                "Not an SSH signature");
    }

    /** A record without a line has no view and so no seal to check: that alone is no failure. */
    @Test
    void aRunWithoutAnyEvent_hasNoSealToCheck_andThatIsNoFailure() {
        String id = "00000000-0000-0000-0000-00000000abce";
        fx.store.create(id, java.time.Instant.parse("2026-11-12T10:00:00Z")).close();

        VerifyReport report = services.verify(id);

        assertThat(report.seal()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.chain().intact()).as(report.chain().problems().toString()).isTrue();
        assertThat(report.ok()).isTrue();
    }
}
