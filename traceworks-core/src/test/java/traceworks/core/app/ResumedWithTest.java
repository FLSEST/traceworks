package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import traceworks.core.compare.RunComparison;
import traceworks.core.model.RunStatus;
import traceworks.core.record.EventChain;
import traceworks.core.record.RunView;
import traceworks.core.testing.FakeCommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static traceworks.core.app.AppFixture.request;

/**
 * A resume with a changed package is in the manifest and in {@code diff}. Reproduced 2026-11-21: two parked runs
 * of one package, the package changed before the second resume ({@code --allow-changed-package}): both manifests
 * named the package digest of the start alone, and {@code diff} said "same". The manifest lists the package digests
 * the run was resumed with while the package was not the one it started with ({@code resumed_with}), {@code verify}
 * checks the list, and {@code diff} compares it.
 */
class ResumedWithTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PARKED = Path.of("/pkgs/parked");

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();

    ResumedWithTest() {
        fx.commands.on("echo hi", "hi").on("echo same-output # v1", "same-output")
                .on("echo same-output # v2", "same-output").on("echo same-output # v3", "same-output");
        fx.packages.put(PARKED, parking("v1"));
    }

    /** A step, a question, and a last step whose command names a version and prints the same. */
    private static String parking(String version) {
        return """
                {"id": "wf-parked", "name": "Parked", "blocks": [
                  {"type": "shell", "name": "greet", "config": {"command": "echo hi"}},
                  {"type": "approval", "name": "publish", "config": {"question": "Go on?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "done", "config": {"command": "echo same-output # %s"}}
                ]}""".formatted(version);
    }

    private static String digest(String workflowJson) {
        return EventChain.sha256Hex(workflowJson.getBytes(StandardCharsets.UTF_8));
    }

    /** One run that parks at its question and is resumed, with the package as {@code atResume} has it by then. */
    private String parkedAndResumed(String atResume) {
        fx.packages.put(PARKED, parking("v1"));
        RunResult parked = services.start(request(PARKED), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        String id = parked.runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.packages.put(PARKED, parking(atResume));
        RunResult resumed = services.resume(new ResumeRequest(id, false, !atResume.equals("v1")), null, null);
        assertThat(resumed.status()).as(String.valueOf(resumed.error())).isEqualTo(RunStatus.COMPLETED);
        return id;
    }

    private JsonNode manifest(String id) throws IOException {
        return JSON.readTree(fx.run(id).readManifest().orElseThrow());
    }

    private List<String> resumedWith(String id) throws IOException {
        List<String> digests = new ArrayList<>();
        manifest(id).path("resumed_with").forEach(digest -> digests.add(digest.asText()));
        return digests;
    }

    // ---- the reproduced case ----

    @Test
    void aRunResumedWithAChangedPackage_namesTheDigestInItsManifest_andDiffSaysThatItDiffers() throws IOException {
        String a = parkedAndResumed("v1");
        String b = parkedAndResumed("v2");

        assertThat(manifest(a).path("package_digest").asText()).as("both started with the same package")
                .isEqualTo(manifest(b).path("package_digest").asText()).isEqualTo(digest(parking("v1")));
        assertThat(manifest(a).has("resumed_with")).as("resumed with the package it started with: no field, and"
                + " the manifest keeps its shape").isFalse();
        assertThat(resumedWith(b)).containsExactly(digest(parking("v2")));
        assertThat(fx.ledger.find(b)).as("the end entry of the ledger is the manifest").contains(
                fx.run(b).readManifest().orElseThrow());
        for (String id : List.of(a, b)) {
            assertThat(fx.ofType(id, "run.started").getFirst().fields().path("records")).extracting(JsonNode::asText)
                    .contains("resumed_with");
            assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("records")).extracting(JsonNode::asText)
                    .contains("resumed_with");
            VerifyReport report = services.verify(id);
            assertThat(report.ok()).as(report.manifest() + " " + report.ledger()).isTrue();
        }
        assertThat(services.view(a).resumedWith()).isEmpty();
        assertThat(services.view(b).resumedWith()).containsExactly(digest(parking("v2")));

        RunComparison comparison = services.compare(a, b);

        assertThat(comparison.reproduced()).isFalse();
        assertThat(comparison.differences()).containsExactly("the package changed at a resume: run A went on with the"
                + " package it started with, run B with " + digest(parking("v2")));
        assertThat(comparison.header()).filteredOn(field -> field.name().equals("resumed with")).singleElement()
                .satisfies(field -> {
                    assertThat(field.a()).isNull();
                    assertThat(field.b()).isEqualTo(digest(parking("v2")));
                });
        assertThat(services.compare(b, a).differences()).containsExactly("the package changed at a resume: run A went"
                + " on with " + digest(parking("v2")) + ", run B with the package it started with");
    }

    @Test
    void twoRunsResumedWithTheSameChangedPackage_areTheSame_andWithAnotherOneTheyDiffer() {
        String b = parkedAndResumed("v2");
        String again = parkedAndResumed("v2");
        String other = parkedAndResumed("v3");

        assertThat(services.compare(b, again).reproduced()).as(services.compare(b, again).differences().toString())
                .isTrue();
        assertThat(services.compare(b, other).differences()).containsExactly("the package changed at a resume: run A"
                + " went on with " + digest(parking("v2")) + ", run B with " + digest(parking("v3")));
    }

    /** A run that was not resumed, and one resumed with its own package, have no such field in the header. */
    @Test
    void runsThatWereNotResumedWithAChangedPackage_haveNoSuchFieldInDiff() {
        String a = parkedAndResumed("v1");
        String again = parkedAndResumed("v1");

        RunComparison comparison = services.compare(a, again);

        assertThat(comparison.reproduced()).as(comparison.differences().toString()).isTrue();
        assertThat(comparison.header()).noneMatch(field -> field.name().equals("resumed with"));
    }

    // ---- how often a run was resumed is no difference ----

    /** A step, two questions, and a last step whose command names a version and prints the same. */
    private static String askingTwice(String version) {
        return """
                {"id": "wf-twice", "name": "Twice", "blocks": [
                  {"type": "shell", "name": "greet", "config": {"command": "echo hi"}},
                  {"type": "approval", "name": "first", "config": {"question": "Go on?", "options": ["Yes", "No"]}},
                  {"type": "approval", "name": "second", "config": {"question": "And on?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "done", "config": {"command": "echo same-output # %s"}}
                ]}""".formatted(version);
    }

    /**
     * One run of the package that asks twice: it is resumed after each answer, with the package as
     * {@code atFirst} and {@code atSecond} have it by then.
     */
    private String resumedTwice(String atFirst, String atSecond) {
        Path twice = Path.of("/pkgs/twice");
        fx.packages.put(twice, askingTwice("v1"));
        String id = services.start(request(twice), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.packages.put(twice, askingTwice(atFirst));
        assertThat(services.resume(new ResumeRequest(id, false, !atFirst.equals("v1")), null, null).status())
                .isEqualTo(RunStatus.PARKED);
        services.answer(id, new AnswerRequest("approval", 2, "Yes", null, null));
        fx.packages.put(twice, askingTwice(atSecond));
        RunResult done = services.resume(new ResumeRequest(id, false, !atSecond.equals("v1")), null, null);
        assertThat(done.status()).as(String.valueOf(done.error())).isEqualTo(RunStatus.COMPLETED);
        return id;
    }

    /**
     * Reproduced 2026-11-21: a run resumed twice with the same changed package recorded {@code [P2, P2]}, and
     * differed from a run that went on with P2 once. The list is the packages a run went on with, without
     * repeats: how often it was paused and resumed is no difference.
     */
    @Test
    void aRunResumedTwiceWithTheSameChangedPackage_namesItOnce_andIsTheSameAsOneThatWentOnWithItOnce()
            throws IOException {
        String p2 = digest(askingTwice("v2"));
        String twiceWithIt = resumedTwice("v2", "v2");
        String onceWithIt = resumedTwice("v1", "v2");

        assertThat(resumedWith(twiceWithIt)).as("resumed twice with the same changed package").containsExactly(p2);
        assertThat(resumedWith(onceWithIt)).containsExactly(p2);
        assertThat(services.view(twiceWithIt).resumedWith()).containsExactly(p2);
        RunComparison comparison = services.compare(twiceWithIt, onceWithIt);
        assertThat(comparison.differences()).noneMatch(difference -> difference.contains("at a resume"));
        assertThat(comparison.reproduced()).as(comparison.differences().toString()).isTrue();
        for (String id : List.of(twiceWithIt, onceWithIt)) {
            assertThat(services.verify(id).ok()).isTrue();
        }
    }

    /** A run that went on with another package and then with the one of its start again names both, in order. */
    @Test
    void aRunThatWentBackToThePackageOfItsStart_namesBothPackages_inOrder() throws IOException {
        String p1 = digest(askingTwice("v1"));
        String p2 = digest(askingTwice("v2"));
        String thereAndBack = resumedTwice("v2", "v1");
        String stayed = resumedTwice("v2", "v2");

        assertThat(resumedWith(thereAndBack)).containsExactly(p2, p1);
        assertThat(services.verify(thereAndBack).ok()).isTrue();
        assertThat(services.compare(thereAndBack, stayed).differences()).containsExactly("the package changed at a"
                + " resume: run A went on with " + p2 + ", " + p1 + ", run B with " + p2);
        assertThat(resumedWith(resumedTwice("v1", "v1"))).as("only ever the package of its start").isEmpty();
    }

    // ---- verify ----

    @Test
    void aManifestWhoseResumedWithWasChangedOrRemoved_doesNotVerify() throws IOException {
        String b = parkedAndResumed("v2");
        var run = fx.run(b);
        String original = new String(run.readManifest().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(original).contains("\"resumed_with\" : [ \"" + digest(parking("v2")) + "\" ],");
        String without = original.replaceFirst("  \"resumed_with\" : \\[.*?\\],\\R", "");
        assertThat(without).isNotEqualTo(original);

        for (String changed : List.of(without, original.replace(digest(parking("v2")), "0".repeat(64)))) {
            run.writeManifest(changed.getBytes(StandardCharsets.UTF_8));

            VerifyReport report = services.verify(b);

            assertThat(report.ok()).isFalse();
            assertThat(report.manifest()).isEqualTo(VerifyReport.MISMATCH);
        }
        run.writeManifest(original.getBytes(StandardCharsets.UTF_8));
        assertThat(services.verify(b).ok()).isTrue();

        String a = parkedAndResumed("v1");
        String ofA = new String(fx.run(a).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        fx.run(a).writeManifest(ofA.replace("  \"seal\" :", "  \"resumed_with\" : [ \"" + "0".repeat(64)
                + "\" ],\n  \"seal\" :").getBytes(StandardCharsets.UTF_8));
        assertThat(services.verify(a).ok()).as("a list the record does not give").isFalse();
    }

    // ---- a record of an earlier version ----

    /**
     * The record of {@code id} as a version before this one wrote it: without {@code resumed_with} in
     * {@code records}, chained anew, with the manifest and the entries of the ledger such a version wrote for it.
     * Its {@code run.resumed} named a changed package then already.
     */
    private void asAnEarlierVersionWroteIt(String id) throws IOException {
        var run = fx.run(id);
        List<byte[]> lines = run.log().readAll();
        String manifest = new String(run.readManifest().orElseThrow(), StandardCharsets.UTF_8);
        String start = new String(fx.ledger.findStart(id).orElseThrow(), StandardCharsets.UTF_8);
        String oldStart = EventChain.sha256Hex(lines.getFirst());
        String oldHead = EventChain.sha256Hex(lines.getLast());
        run.log().dropLast(lines.size());
        String prev = EventChain.genesis(id);
        String newStart = null;
        for (byte[] line : lines) {
            ObjectNode node = (ObjectNode) JSON.readTree(line);
            if (node.path("records").isArray()) {
                ArrayNode records = JSON.createArrayNode();
                node.path("records").forEach(name -> {
                    if (!"resumed_with".equals(name.asText())) {
                        records.add(name);
                    }
                });
                node.set("records", records);
            }
            node.put("prev", prev);
            prev = run.log().append(JSON.writeValueAsBytes(node));
            newStart = newStart == null ? prev : newStart;
        }
        assertThat(manifest).contains(oldHead, oldStart);
        String rewritten = manifest.replaceFirst("  \"resumed_with\" : \\[.*?\\],\\R", "")
                .replace(oldHead, prev).replace(oldStart, newStart);
        run.writeManifest(rewritten.getBytes(StandardCharsets.UTF_8));
        fx.ledger.replaceStart(id, start.replace(oldStart, newStart).getBytes(StandardCharsets.UTF_8));
        fx.ledger.forgeEnd(id, "COMPLETED", rewritten.getBytes(StandardCharsets.UTF_8));
    }

    /** The field of {@code run.resumed} is older than the manifest's: verify asks nothing new, and diff still reads it. */
    @Test
    void aRecordOfAnEarlierVersion_verifiesWithoutTheField_andDiffReadsItsResumeAllTheSame() throws IOException {
        String old = parkedAndResumed("v2");
        asAnEarlierVersionWroteIt(old);
        String now = parkedAndResumed("v1");

        assertThat(manifest(old).has("resumed_with")).isFalse();
        RunView view = services.view(old);
        assertThat(view.recordsResumedWith()).isFalse();
        assertThat(view.resumedWith()).as("its run.resumed says it").containsExactly(digest(parking("v2")));
        VerifyReport report = services.verify(old);
        assertThat(report.ok()).as(report.manifest() + " " + report.ledger() + " " + report.start() + " "
                + report.chain().problems()).isTrue();

        RunComparison comparison = services.compare(now, old);

        assertThat(comparison.reproduced()).isFalse();
        assertThat(comparison.differences()).containsExactly("the package changed at a resume: run A went on with the"
                + " package it started with, run B with " + digest(parking("v2")));
    }

    // ---- a child of the run's own package after such a resume ----

    /** A first step, a question, then the package itself once more as a child, then a last step. */
    private static String selfCalling(String say) {
        return """
                {"id": "wf-self", "name": "Self", "blocks": [
                  {"type": "shell", "name": "mark", "config": {"command": "./mark"}},
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"],
                     "run_if": "./outer"}},
                  {"type": "sub-workflow", "name": "again", "config": {"workflow_id": ".", "run_if": "./outer"}},
                  {"type": "shell", "name": "say", "config": {"command": "%s"}}
                ]}""".formatted(say);
    }

    /**
     * A run that calls its own package as a child is not resumed with a changed package: the child would be loaded
     * from the package as it is then, beside the definition recorded at the start. So no record holds such a
     * resume, and {@code resumed_with} never has to cover a child of the run's own package
     * ({@code ResumeOwnPackageChildTest} has every case of the refusal).
     */
    @Test
    void aRunThatCallsItsOwnPackage_isNotResumedWithAChangedPackage_soNoRecordHoldsSuchAResume() {
        Path self = Path.of("/pkgs/self");
        fx.packages.put(self, selfCalling("./one"));
        fx.commands.on("./one", "said").on("./two", "said");
        // Only the run itself asks and calls: its child, the second to run the first step, skips both blocks
        AtomicInteger started = new AtomicInteger();
        fx.commands.on("./mark", req -> FakeCommandRunner.ok("run " + started.incrementAndGet()));
        fx.commands.on("./outer", req -> FakeCommandRunner.exit(started.get() == 1 ? 0 : 1, ""));
        RunResult parked = services.start(request(self), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        String id = parked.runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.packages.put(self, selfCalling("./two"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> services.resume(new ResumeRequest(id, false, true), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("its block 'again' runs the run's own package as a child workflow");

        assertThat(fx.types(id)).doesNotContain("run.resumed", "run.package");
        assertThat(services.view(id).resumedWith()).isEmpty();
        assertThat(fx.commands.commands()).doesNotContain("./two");
    }
}
