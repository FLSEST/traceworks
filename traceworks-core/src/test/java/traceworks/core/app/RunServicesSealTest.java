package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordTypes;
import traceworks.core.seal.Seal;
import traceworks.core.testing.JavaSshSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Run admission and resume with seals: what runs, what is refused, and what is recorded of both. */
class RunServicesSealTest {

    private static final Path MAIN = Path.of("/pkg/main");
    private static final Path LIB = Path.of("/lib/tools");

    private final AppFixture fx = new AppFixture().allowSigner();

    RunServicesSealTest() {
        putMainAndLib(fx);
    }

    /** MAIN, which calls LIB. */
    private static void putMainAndLib(AppFixture fixture) {
        fixture.packages.put(MAIN, """
                {"id": "wf-main", "name": "Main", "blocks": [
                  {"type": "sub-workflow", "name": "tools", "config": {"workflow_id": "../../lib/tools"}}
                ]}""");
        fixture.packages.put(LIB, """
                {"id": "wf-tools", "name": "Tools", "blocks": [
                  {"type": "shell", "name": "t", "config": {"command": "./build"}}
                ]}""");
    }

    private RunServices services() {
        return fx.services();
    }

    private void require(SealSettings.Require require) {
        fx.settings = fx.settings.withSeals(new SealSettings(require, false, null, null));
    }

    private static StartRequest attended(Path pkg) {
        return new StartRequest(pkg, Map.of("version", "1.2"), AppFixture.WORKSPACE, false, false, null, null, null,
                true);
    }

    /** Asserts the start is refused with a text, that no run exists, and that the last log line records it. */
    private void assertRefused(StartRequest request, String reason, String... texts) {
        int runs = fx.store.runIds().size();
        int commands = fx.commands.commands().size();
        assertThatThrownBy(() -> services().start(request, null, null)).isInstanceOf(RunRefusedException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains(texts));
        assertThat(fx.store.runIds()).hasSize(runs);
        assertThat(fx.commands.commands()).hasSize(commands);
        assertThat(fx.sealStore.lines().getLast()).contains("\"type\":\"REFUSED\"")
                .contains("\"reason\":\"" + reason + "\"").contains("\"action\":\"run\"")
                .contains("\"actor\":\"" + AppFixture.ACTOR + "\"");
    }

    private JsonNode sealOfStart(String runId) {
        return fx.ofType(runId, RecordTypes.RUN_STARTED).getFirst().fields().path("seal");
    }

    // ---- start ----

    @Test
    void aSealedPackage_runs_andTheRecordNamesItsSeal() {
        Seals.Sealed sealed = services().seals().seal(BUILD, null);

        RunResult result = services().start(request(BUILD), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        JsonNode seal = sealOfStart(result.runId());
        assertThat(seal.path("digest").asText()).isEqualTo(sealed.seal().digest());
        assertThat(seal.path("signer").asText()).isEqualTo(AppFixture.ACTOR);
        assertThat(seal.path("key").asText()).isEqualTo(sealed.key());
        assertThat(seal.path("principals").get(0).asText()).isEqualTo(AppFixture.ACTOR);
        assertThat(seal.path("signature").asText()).isEqualTo(sealed.seal().signature());
        assertThat(services().view(result.runId()).seal().digest()).isEqualTo(sealed.seal().digest());
        String manifest = new String(fx.run(result.runId()).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(manifest).contains("\"seal\" : \"sealed\"")
                .contains("\"seal_digest\" : \"" + sealed.seal().digest() + "\"")
                .contains("\"seal_signer\" : \"" + AppFixture.ACTOR + "\"")
                .contains("\"seal_key\" : \"" + sealed.key() + "\"");
        assertThat(services().verify(result.runId()).ok()).isTrue();
        assertThat(services().verify(result.runId()).seal()).startsWith("ok").contains(AppFixture.ACTOR);
        assertThat(fx.sealStore.lines()).hasSize(1);
    }

    @Test
    void anUnsealedPackage_runsAsBefore_andTheRecordSaysUnsealed() {
        RunResult result = services().start(request(BUILD), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(sealOfStart(result.runId()).isNull()).isTrue();
        assertThat(services().view(result.runId()).seal()).isNull();
        String manifest = new String(fx.run(result.runId()).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(manifest).contains("\"seal\" : \"unsealed\"").doesNotContain("seal_digest");
        assertThat(services().verify(result.runId()).seal()).isEqualTo("unsealed");
        assertThat(fx.sealStore.lines()).isEmpty();
    }

    @Test
    void aSealedPackageWhoseScriptChanged_isRefused() {
        String digest = services().seals().seal(BUILD, null).seal().digest();
        fx.packages.script(BUILD, "rm -rf /");

        assertRefused(request(BUILD), "changed", "/pkg/build", "changed since it was sealed", digest,
                "traceworks seal");
        assertThat(fx.sealStore.lines().getLast()).contains("\"sealed_digest\":\"" + digest + "\"")
                .contains("\"workflow_id\":\"wf-build\"").contains("\"package_dir\":\"/pkg/build\"");
    }

    @Test
    void aSealedPackageWhoseDefinitionChanged_isRefused() {
        services().seals().seal(BUILD, null);
        fx.packages.put(BUILD, """
                {"id": "wf-build", "name": "Build", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./done"}}]}""");

        assertRefused(request(BUILD), "changed", "changed since it was sealed");
    }

    @Test
    void aSealedPackageWhoseChildPackageChanged_isRefused() {
        services().seals().seal(MAIN, null);
        assertThat(services().start(request(MAIN), null, null).status()).isEqualTo(RunStatus.COMPLETED);
        fx.packages.script(LIB, "echo changed");

        assertRefused(request(MAIN), "changed", "a workflow it calls");
    }

    @Test
    void aSealedPackageWhoseChildIsGone_isRefused() {
        services().seals().seal(MAIN, null);
        fx.packages.remove(LIB);

        assertRefused(request(MAIN), "not-sealable", "'tools'", "../../lib/tools");
    }

    @Test
    void aPackageTheHomeHoldsAsSealed_isRefusedWithoutItsSealFile() {
        services().seals().seal(BUILD, null);
        fx.sealStore.removeSeal(BUILD);

        assertRefused(request(BUILD), "seal-missing", "sealed in this home", "traceworks.seal", "traceworks unseal");
    }

    @Test
    void aCopyOfASealedWorkflowInAnotherDirectory_isRefusedWithoutASeal() {
        services().seals().seal(BUILD, null);
        Path copy = Path.of("/elsewhere/build");
        fx.packages.put(copy, """
                {"id": "wf-build", "name": "Build", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./done"}}]}""");

        assertRefused(request(copy), "seal-missing", "wf-build");
    }

    @Test
    void aSealByAKeyTheHomeDoesNotAllow_isRefused() {
        JavaSshSigner stranger = new JavaSshSigner();
        String digest = SealClosure.of(fx.packages.load(BUILD), List.of(), fx.packages).digest();
        Instant at = Instant.parse("2026-11-13T10:00:00Z");
        String signature = traceworks.core.seal.SshSignature.parse(
                stranger.sign(Seal.payload("wf-build", digest, "mallory", at), "traceworks", null)).armored();
        fx.sealStore.writeSeal(BUILD, new Seal("wf-build", digest, "mallory", at, signature).toJson());

        assertRefused(request(BUILD), "signer-not-allowed", "memory:allowed_signers", "mallory", "SHA256:");
    }

    @Test
    void aSeal_isRefused_whenTheAllowedSignersAreGone_orCannotBeRead_orNameTheKeyForAnotherUse() {
        services().seals().seal(BUILD, null);

        fx.sealStore.allow(null);
        assertRefused(request(BUILD), "signer-not-allowed", "memory:allowed_signers");

        fx.sealStore.allow(AppFixture.ACTOR + " namespaces=\"git\" " + fx.signer.publicKey() + "\n");
        assertRefused(request(BUILD), "signer-not-allowed", "namespaces");

        fx.sealStore.allow(AppFixture.ACTOR + " valid-before=\"20300101\" " + fx.signer.publicKey() + "\n");
        assertRefused(request(BUILD), "signer-not-allowed", "valid-before");

        fx.sealStore.signersFailure = new java.io.UncheckedIOException(new java.io.IOException("permission denied"));
        assertRefused(request(BUILD), "signers-unreadable", "permission denied");
    }

    @Test
    void aSealFileThatIsNoSeal_isRefused_notTakenForUnsealed() {
        fx.sealStore.writeSeal(BUILD, "{}".getBytes(StandardCharsets.UTF_8));
        assertRefused(request(BUILD), "seal-unreadable", "/pkg/build", "traceworks.seal");

        fx.sealStore.writeSeal(BUILD, new byte[0]);
        assertRefused(request(BUILD), "seal-unreadable", "traceworks.seal");
    }

    @Test
    void theSealOfAnotherWorkflow_isRefused() {
        services().seals().seal(DEPLOY, null);
        fx.sealStore.writeSeal(BUILD, fx.sealStore.seal(DEPLOY));

        assertRefused(request(BUILD), "seal-unreadable", "wf-deploy", "wf-build");
    }

    @Test
    void aSealWhoseSignatureCoversOtherFields_isRefused() {
        Seal real = services().seals().seal(BUILD, null).seal();
        fx.packages.script(BUILD, "echo changed");
        String now = SealClosure.of(fx.packages.load(BUILD), List.of(), fx.packages).digest();
        // The digest of the changed package, under the signature made for the sealed one
        fx.sealStore.writeSeal(BUILD, new Seal("wf-build", now, real.signer(), real.sealedAt(), real.signature()).toJson());

        assertRefused(request(BUILD), "signature-invalid", "does not match");
    }

    /** The seal file is not part of the digest, so it may hold nothing the signature does not fix. */
    @Test
    void aSealWhoseSignatureTextIsSpacedOtherwise_isRefused() {
        Seal real = services().seals().seal(BUILD, null).seal();
        String spaced = real.signature().replaceFirst("\n", "\n \n\t\n");
        fx.sealStore.writeSeal(BUILD, new Seal(real.workflowId(), real.digest(), real.signer(), real.sealedAt(),
                spaced).toJson());

        assertRefused(request(BUILD), "signature-invalid", "not written as ssh-keygen writes it");
    }

    @Test
    void aSealLogThatDoesNotHold_refusesEveryRun_andIsNotWrittenTo() {
        services().seals().seal(BUILD, null);
        services().seals().seal(DEPLOY, null);
        List<String> lines = fx.sealStore.lines();

        // a changed line
        fx.sealStore.rewrite(List.of(lines.get(0).replace("flo@box", "eve@box"), lines.get(1)));
        assertThatThrownBy(() -> services().start(request(BUILD), null, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("seal log").hasMessageContaining("memory:seals")
                .hasMessageContaining("changed or removed");
        assertThat(fx.sealStore.lines()).hasSize(2);

        // a line cut from the start, also for a package the log never named
        fx.sealStore.rewrite(List.of(lines.get(1)));
        assertThatThrownBy(() -> services().start(request(AppFixture.GATE), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("seal log");

        // a line of a type this version does not know
        fx.sealStore.rewrite(lines);
        try (var log = fx.sealStore.openLogForWrite()) {
            traceworks.core.seal.SealLog.forWriting(log.events(), fx.clock)
                    .append("PARDONED", Map.of("workflow_id", "wf-build"));
        }
        assertThatThrownBy(() -> services().start(request(BUILD), null, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("PARDONED");

        // a log that cannot be read
        fx.sealStore.rewrite(lines);
        fx.sealStore.readFailure = new java.io.UncheckedIOException(new java.io.IOException("permission denied"));
        assertThatThrownBy(() -> services().start(request(BUILD), null, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("seal log").hasMessageContaining("permission denied");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.commands.commands()).isEmpty();
    }

    @Test
    void aSealMadeInOneHome_holdsInAnotherThatAllowsItsKey_andNotInOneThatDoesNot() {
        services().seals().seal(BUILD, null);
        byte[] seal = fx.sealStore.seal(BUILD);
        AppFixture allowing = new AppFixture();
        allowing.sealStore.allow(fx.signer.allowedLine("release@example.org"));
        allowing.sealStore.writeSeal(BUILD, seal);
        AppFixture strict = new AppFixture();
        strict.sealStore.writeSeal(BUILD, seal);

        RunResult there = allowing.services().start(request(BUILD), null, null);

        assertThat(there.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(allowing.ofType(there.runId(), RecordTypes.RUN_STARTED).getFirst().fields().path("seal")
                .path("principals").get(0).asText()).isEqualTo("release@example.org");
        // the home had never seen the workflow: it takes the seal into its log, once
        assertThat(allowing.sealStore.lines()).singleElement().asString().contains("\"type\":\"SEALED\"")
                .contains("\"adopted\":true").contains("\"workflow_id\":\"wf-build\"")
                .contains("\"signer\":\"flo@box\"");
        assertThat(allowing.services().start(request(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(allowing.sealStore.lines()).hasSize(1);
        assertThatThrownBy(() -> strict.services().start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("does not allow");
        assertThat(strict.sealStore.lines()).singleElement().asString().contains("signer-not-allowed");
    }

    @Test
    void requireSealAlways_refusesAnUnsealedPackage_whoeverAsks() {
        require(SealSettings.Require.ALWAYS);

        assertRefused(request(BUILD), "unsealed", "require_seal", "always", "traceworks seal /pkg/build");
        assertRefused(attended(BUILD), "unsealed", "require_seal");

        services().seals().seal(BUILD, null);
        assertThat(services().start(request(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void requireSealUnattended_refusesAnUnsealedPackage_onlyWhenNobodyIsThere() {
        require(SealSettings.Require.UNATTENDED);

        assertRefused(request(BUILD), "unsealed", "require_seal", "unattended", "--allow-unsealed");
        assertThat(services().start(attended(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void anAttendedRun_doesNotGetASealedPackageThatChangedPastItsSeal() {
        services().seals().seal(BUILD, null);
        fx.packages.script(BUILD, "echo changed");

        assertRefused(attended(BUILD), "changed", "changed since it was sealed");
    }

    @Test
    void aRefusedRunWithASource_clonesNothing() {
        services().seals().seal(BUILD, null);
        fx.packages.script(BUILD, "echo changed");

        assertRefused(AppFixture.sourced(BUILD, "/repos/app", null), "changed", "changed since it was sealed");
        assertThat(fx.sources.calls()).isEmpty();
    }

    @Test
    void aRefusalThatCannotBeRecorded_stillRefuses_andSaysSo() {
        services().seals().seal(BUILD, null);
        fx.packages.script(BUILD, "echo changed");
        fx.sealStore.log().failAfter(1);

        assertThatThrownBy(() -> services().start(request(BUILD), null, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("changed since it was sealed").hasMessageContaining("could not be recorded");
        assertThat(fx.store.runIds()).isEmpty();
    }

    // ---- check ----

    @Test
    void check_warnsWhenASealedPackageChanged_andIsSilentOtherwise() {
        assertThat(services().check(BUILD)).noneMatch(f -> f.rule().startsWith("seal"));
        services().seals().seal(BUILD, null);
        assertThat(services().check(BUILD)).noneMatch(f -> f.rule().startsWith("seal"));
        fx.packages.script(BUILD, "echo changed");

        List<AnalyzerFinding> findings = services().check(BUILD);

        assertThat(findings).filteredOn(f -> f.rule().equals("sealed-package-changed")).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.WARNING);
                    assertThat(f.detail()).contains("changed since it was sealed");
                });
        // check records nothing
        assertThat(fx.sealStore.lines()).hasSize(1);
    }

    @Test
    void check_namesASealThatDoesNotHoldForAnotherReason() {
        services().seals().seal(BUILD, null);
        fx.sealStore.allow(null);

        assertThat(services().check(BUILD)).filteredOn(f -> f.rule().equals("seal-invalid")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("does not allow"));
    }

    // ---- resume ----

    private String parked(Path pkg) {
        RunResult result = services().start(request(pkg), null, null);
        assertThat(result.status()).isEqualTo(RunStatus.PARKED);
        services().answer(result.runId(), new AnswerRequest("approval", 1, "Yes", null, null));
        return result.runId();
    }

    private void assertResumeRefused(String id, boolean allowChanged, String reason, String... texts) {
        List<String> commands = new ArrayList<>(fx.commands.commands());
        assertThatThrownBy(() -> services().resume(new ResumeRequest(id, false, allowChanged), null, null))
                .isInstanceOf(RunRefusedException.class).satisfies(e -> assertThat(e.getMessage()).contains(texts));
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
        assertThat(fx.commands.commands()).isEqualTo(commands);
        assertThat(fx.sealStore.lines().getLast()).contains("\"type\":\"REFUSED\"")
                .contains("\"reason\":\"" + reason + "\"").contains("\"action\":\"resume\"")
                .contains("\"run_id\":\"" + id + "\"");
    }

    @Test
    void aSealedRun_resumes_whileItsPackageIsAsSealed() {
        services().seals().seal(DEPLOY, null);
        String id = parked(DEPLOY);

        RunResult resumed = services().resume(AppFixture.resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(services().verify(id).ok()).isTrue();
    }

    @Test
    void aSealedRun_doesNotResume_afterItsPackageChanged_alsoWithTheFlag() {
        services().seals().seal(DEPLOY, null);
        String id = parked(DEPLOY);
        fx.packages.script(DEPLOY, "echo changed");

        assertResumeRefused(id, false, "changed", "changed since it was sealed");
        assertResumeRefused(id, true, "changed", "changed since it was sealed");
    }

    @Test
    void aSealedRun_doesNotResume_afterItsPackageWasUnsealed_orSealedAgainChanged() {
        String first = services().seals().seal(DEPLOY, null).seal().digest();
        String id = parked(DEPLOY);

        services().seals().unseal(DEPLOY, null);
        assertResumeRefused(id, false, "seal-differs-from-start", "started with a sealed package", first,
                "traceworks cancel " + id);

        fx.packages.script(DEPLOY, "echo changed");
        services().seals().seal(DEPLOY, null);
        assertResumeRefused(id, true, "seal-differs-from-start", "started with a sealed package");
    }

    @Test
    void anUnsealedRun_resumes_whenItsUnchangedPackageWasSealedMeanwhile() {
        String id = parked(DEPLOY);
        services().seals().seal(DEPLOY, null);

        assertThat(services().resume(AppFixture.resumeOf(id), null, null).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void anUnsealedRun_doesNotResume_whenItsPackageChangedAndIsSealedNow() {
        String id = parked(DEPLOY);
        fx.packages.script(DEPLOY, "echo changed");
        services().seals().seal(DEPLOY, null);

        assertResumeRefused(id, true, "sealed-since-start", "recorded at its start", "traceworks cancel " + id);
    }

    @Test
    void aResume_obeysRequireSeal() {
        String id = parked(DEPLOY);
        require(SealSettings.Require.ALWAYS);

        assertResumeRefused(id, false, "unsealed", "require_seal");

        require(SealSettings.Require.UNATTENDED);
        assertResumeRefused(id, false, "unsealed", "--allow-unsealed");
        RunResult resumed = services().resume(new ResumeRequest(id, false, false, true), null, null);
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aRecordedSealThatWasChanged_failsTheVerification() {
        services().seals().seal(BUILD, null);
        String id = services().start(request(BUILD), null, null).runId();
        var log = fx.run(id).log();
        List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        for (byte[] line : lines) {
            log.append(new String(line, StandardCharsets.UTF_8).replace("\"signer\":\"flo@box\"", "\"signer\":\"eve@box\"")
                    .getBytes(StandardCharsets.UTF_8));
        }

        VerifyReport report = services().verify(id);

        assertThat(report.ok()).isFalse();
        assertThat(report.seal()).contains("does not match");
    }

    // ---- an older seal put back ----

    /** The files of an earlier seal, kept and copied back: a valid signature, but not the seal this home holds. */
    @Test
    void anEarlierSealPutBack_isRefused_withoutAnyKey() {
        Seal first = services().seals().seal(BUILD, null).seal();
        byte[] firstFile = fx.sealStore.seal(BUILD);
        fx.packages.script(BUILD, "echo version 2");
        Seal second = services().seals().seal(BUILD, null).seal();
        assertThat(services().start(request(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);

        fx.packages.script(BUILD, null);
        fx.sealStore.writeSeal(BUILD, firstFile);

        assertRefused(request(BUILD), "seal-superseded", "older", first.digest(), second.digest(), "traceworks seal");
        assertThat(services().seals().verify(BUILD).state()).isEqualTo(SealStatus.State.REFUSED);
        assertThat(services().check(BUILD)).anyMatch(f -> f.rule().equals("seal-invalid"));
    }

    @Test
    void theSealOfAnUnsealedPackagePutBack_isRefused() {
        services().seals().seal(BUILD, null);
        byte[] file = fx.sealStore.seal(BUILD);
        services().seals().unseal(BUILD, null);
        assertThat(services().start(request(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);

        fx.sealStore.writeSeal(BUILD, file);

        assertRefused(request(BUILD), "seal-superseded", "older", "unsealed", "traceworks seal");
    }

    /** A newer seal made elsewhere: taken, written into the log with its own signer and time, and known from then on. */
    @Test
    void aNewerSealFromElsewhere_isAdoptedOnce_andThenKnown_andTheOneBeforeItIsThenRefused() {
        Seal local = services().seals().seal(BUILD, null).seal();
        byte[] localFile = fx.sealStore.seal(BUILD);
        fx.packages.script(BUILD, "echo version 2");
        String digest = SealClosure.of(fx.packages.load(BUILD), List.of(), fx.packages).digest();
        Instant later = fx.clock.now().plusSeconds(3600);
        String signature = traceworks.core.seal.SshSignature.parse(
                fx.signer.sign(Seal.payload("wf-build", digest, "release-machine", later), "traceworks", null)).armored();
        fx.sealStore.writeSeal(BUILD, new Seal("wf-build", digest, "release-machine", later, signature).toJson());

        // judging records nothing
        assertThat(services().seals().verify(BUILD).state()).isEqualTo(SealStatus.State.SEALED);
        assertThat(fx.sealStore.lines()).hasSize(1);

        assertThat(services().start(request(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.sealStore.lines()).hasSize(2);
        assertThat(fx.sealStore.lines().get(1)).contains("\"type\":\"SEALED\"").contains("\"adopted\":true")
                .contains("\"digest\":\"" + digest + "\"").contains("\"signer\":\"release-machine\"")
                .contains("\"sealed_at\":\"" + later + "\"").contains("\"actor\":\"" + AppFixture.ACTOR + "\"");
        assertThat(services().start(request(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.sealStore.lines()).hasSize(2);
        assertThat(services().seals().log().sealed().get("wf-build").digest()).isEqualTo(digest);

        fx.packages.script(BUILD, null);
        fx.sealStore.writeSeal(BUILD, localFile);
        assertRefused(request(BUILD), "seal-superseded", local.digest());
    }

    @Test
    void aSealFromElsewhere_thatCannotBeWrittenIntoTheLog_startsNoRun() {
        services().seals().seal(BUILD, null);
        byte[] seal = fx.sealStore.seal(BUILD);
        AppFixture other = new AppFixture();
        other.sealStore.allow(fx.signer.allowedLine("release@example.org"));
        other.sealStore.writeSeal(BUILD, seal);
        other.sealStore.log().failAfter(0);

        assertThatThrownBy(() -> other.services().start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("seal log");
        assertThat(other.store.runIds()).isEmpty();
    }

    // ---- sealed children ----

    /** A sealed package does not lose its seal by being called from a package that has none. */
    @Test
    void aSealedChildPackageThatChanged_refusesTheRunOfItsUnsealedParent() {
        services().seals().seal(LIB, null);
        assertThat(services().start(request(MAIN), null, null).status()).isEqualTo(RunStatus.COMPLETED);

        fx.packages.script(LIB, "echo changed");

        assertRefused(request(MAIN), "changed", "/lib/tools", "changed since it was sealed", "called by");
        assertThat(services().seals().verify(MAIN).state()).isEqualTo(SealStatus.State.REFUSED);
    }

    /** A called package that carries a seal from another home is judged as well, though this home never held it. */
    @Test
    void aChildSealedInAnotherHome_whoseSealDoesNotHoldHere_refusesTheRunOfItsParent() {
        services().seals().seal(LIB, null);
        AppFixture strict = new AppFixture();
        putMainAndLib(strict);
        strict.sealStore.writeSeal(LIB, fx.sealStore.seal(LIB));

        assertThatThrownBy(() -> strict.services().start(request(MAIN), null, null))
                .isInstanceOf(RunRefusedException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("/lib/tools", "called by", "does not allow"));
        assertThat(strict.store.runIds()).isEmpty();
        assertThat(strict.commands.commands()).isEmpty();
    }

    @Test
    void aChildTheHomeHoldsAsSealed_isRefusedWithoutItsSealFile_alsoUnderAnUnsealedParent() {
        services().seals().seal(LIB, null);
        fx.sealStore.removeSeal(LIB);

        assertRefused(request(MAIN), "seal-missing", "wf-tools", "called by");
    }

    /**
     * A child under the caller's own .git/ is what a seal of the caller cannot cover. It is a called package all the
     * same: a changed copy of a sealed workflow there runs under no seal, and so does a sealed package only it calls.
     */
    @Test
    void aChildUnderAnUnsealedParentsGitDirectory_keepsItsSeal_andSoDoesWhatItCalls() {
        services().seals().seal(LIB, null);
        Path front = Path.of("/pkg/front");
        fx.packages.put(front, """
                {"id": "wf-front", "name": "Front", "blocks": [
                  {"type": "sub-workflow", "name": "copy", "config": {"workflow_id": "./.git/copy"}}
                ]}""");
        fx.packages.put(front.resolve(".git/copy"), """
                {"id": "wf-tools", "name": "Tools", "blocks": [
                  {"type": "shell", "name": "t", "config": {"command": "echo changed"}}
                ]}""");

        assertRefused(request(front), "seal-missing", "wf-tools", "/pkg/front/.git/copy", "called by");

        Path relay = Path.of("/pkg/relay");
        fx.packages.put(relay, """
                {"id": "wf-relay", "name": "Relay", "blocks": [
                  {"type": "sub-workflow", "name": "on", "config": {"workflow_id": "./.GIT/on"}}
                ]}""");
        fx.packages.put(relay.resolve(".GIT/on"), """
                {"id": "wf-on", "name": "On", "blocks": [
                  {"type": "sub-workflow", "name": "tools", "config": {"workflow_id": "../../../../lib/tools"}}
                ]}""");
        fx.packages.script(LIB, "echo changed");

        assertRefused(request(relay), "changed", "/lib/tools", "changed since it was sealed", "called by");
    }

    // ---- the seal log does not grow with a refusal that repeats ----

    @Test
    void aRefusalThatRepeats_isRecordedOnce_andAnotherOneAgain() {
        services().seals().seal(BUILD, null);
        fx.packages.script(BUILD, "echo changed");
        for (int i = 0; i < 5; i++) {
            assertRefused(request(BUILD), "changed", "changed since it was sealed");
        }
        assertThat(fx.sealStore.lines()).hasSize(2);

        fx.packages.script(BUILD, "echo changed again");
        assertRefused(request(BUILD), "changed", "changed since it was sealed");
        assertThat(fx.sealStore.lines()).hasSize(3);
        fx.sealStore.allow(null);
        fx.packages.script(BUILD, null);
        assertRefused(request(BUILD), "signer-not-allowed", "does not allow");
        assertThat(fx.sealStore.lines()).hasSize(4);
    }

    @Test
    void theDetailOfARecordedRefusal_isBounded() {
        Path loose = Path.of("/pkg/loose");
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            blocks.append(i == 0 ? "" : ",").append("{\"type\": \"shell\", \"name\": \"step-with-a-long-name-").append(i)
                    .append("\", \"config\": {\"command\": \"x\", \"container_image\": \"registry.example/image-")
                    .append(i).append(":latest\"}}");
        }
        fx.packages.put(loose, "{\"id\": \"wf-loose\", \"name\": \"Loose\", \"blocks\": [" + blocks + "]}");
        fx.sealStore.writeSeal(loose, new Seal("wf-loose", "a".repeat(64), "x", Instant.parse("2026-11-13T10:00:00Z"),
                "sig").toJson());

        assertThatThrownBy(() -> services().start(request(loose), null, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image-199");
        assertThat(fx.sealStore.lines().getLast().length()).isLessThan(3000);
        assertThat(fx.sealStore.lines().getLast()).contains("\"reason\":\"not-sealable\"").contains("…");
    }

    /** A run recorded before seals existed: its run.started has no seal field at all. */
    @Test
    void aRecordWithoutASealField_verifiesAsUnsealed() {
        String id = services().start(request(BUILD), null, null).runId();
        var log = fx.run(id).log();
        List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        String previous = null;
        for (byte[] bytes : lines) {
            String line = new String(bytes, StandardCharsets.UTF_8);
            if (previous == null) {
                assertThat(line).contains("\"seal\":null,");
                line = line.replace("\"seal\":null,", "");
            } else {
                line = line.replaceFirst("\"prev\":\"[0-9a-f]{64}\"", "\"prev\":\"" + previous + "\"");
            }
            previous = log.append(line.getBytes(StandardCharsets.UTF_8));
        }

        VerifyReport report = services().verify(id);

        assertThat(report.chain().intact()).as(report.chain().problems().toString()).isTrue();
        assertThat(report.seal()).isEqualTo("unsealed");
        assertThat(services().view(id).seal()).isNull();
    }
}
