package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import traceworks.core.compare.RunComparison;
import traceworks.core.model.RunStatus;
import traceworks.core.record.EventChain;
import traceworks.core.record.Manifest;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunStarter;
import traceworks.core.record.RunView;
import traceworks.core.testing.FakeCommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;

/**
 * The packages a run calls: a child run of a workflow from another package than the run's leaves a
 * {@code run.package} line, the manifest (and so the end entry of the ledger) lists every such package with its
 * digest, {@code verify} checks the list, and {@code diff} compares it. The package digest of a run covers the
 * package it was started with only, so without this two runs whose called workflow changed looked the same.
 */
class CalledPackagesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One block, which calls {@link #CALLEE}. */
    private static final Path CALLER = Path.of("/pkgs/caller");
    private static final Path CALLEE = Path.of("/pkgs/callee");
    private static final Path THIRD = Path.of("/pkgs/third");

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();

    CalledPackagesTest() {
        fx.packages.put(CALLER, """
                {"id": "wf-caller", "name": "Caller", "blocks": [
                  {"type": "sub-workflow", "name": "call", "config": {"workflow_id": "../callee"}}
                ]}""");
        callee("echo same-output");
        fx.commands.on("echo same-output", "same-output").on("printf same-output", "same-output")
                .on("./third", "third");
    }

    /** The called package with one shell step that runs {@code command}; its digest is the hash of this text. */
    private String callee(String command) {
        String json = """
                {"id": "wf-callee", "name": "Callee", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "%s"}}
                ]}""".formatted(command);
        fx.packages.put(CALLEE, json);
        return digest(json);
    }

    private static String digest(String workflowJson) {
        return EventChain.sha256Hex(workflowJson.getBytes(StandardCharsets.UTF_8));
    }

    private String run(Path pkg) {
        RunResult result = services.start(request(pkg), null, null);
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(RunStatus.COMPLETED);
        return result.runId();
    }

    private JsonNode manifest(String id) throws IOException {
        return JSON.readTree(fx.run(id).readManifest().orElseThrow());
    }

    /** The {@code called_packages} of the manifest, each as {@code <workflow id> <package digest>}. */
    private List<String> called(String id) throws IOException {
        List<String> called = new ArrayList<>();
        for (JsonNode entry : manifest(id).path("called_packages")) {
            assertThat(entry.fieldNames()).toIterable().containsExactly("workflow_id", "package_digest");
            called.add(entry.path("workflow_id").asText() + " " + entry.path("package_digest").asText());
        }
        return called;
    }

    // ---- the reproduced case ----

    /**
     * The defect: the called workflow runs another command with the same output. The package digest of both runs
     * is the same, and so was everything {@code diff} compared.
     */
    @Test
    void aCalledPackageThatChangedBetweenTwoRuns_showsInTheManifests_andDiffNamesIt() throws IOException {
        String before = callee("echo same-output");
        String a = run(CALLER);
        String after = callee("printf same-output");
        String b = run(CALLER);

        assertThat(manifest(a).path("package_digest")).isEqualTo(manifest(b).path("package_digest"));
        assertThat(called(a)).containsExactly("wf-callee " + before);
        assertThat(called(b)).containsExactly("wf-callee " + after);
        assertThat(fx.ledger.find(b).orElseThrow()).as("the end entry of the ledger is the manifest")
                .isEqualTo(fx.run(b).readManifest().orElseThrow());
        assertThat(services.verify(a).ok()).isTrue();
        assertThat(services.verify(b).ok()).isTrue();

        RunComparison comparison = services.compare(a, b);

        assertThat(comparison.reproduced()).isFalse();
        String callByCall = "step call: the called package differs (" + before + " in run A, " + after
                + " in run B)";
        assertThat(comparison.differences()).as("by the list of the run, and by the call")
                .containsExactly("a called package differs: wf-callee", callByCall);
        assertThat(comparison.header()).filteredOn(field -> field.name().equals("called package wf-callee"))
                .containsExactly(new RunComparison.Field("called package wf-callee", before, after));
        assertThat(comparison.notes()).isEmpty();
        assertThat(services.compare(a, run(CALLER)).differences()).as("b against a run of the same packages")
                .containsExactly("a called package differs: wf-callee", callByCall);
        callee("echo same-output");
        assertThat(services.compare(a, run(CALLER)).reproduced()).as("the called package as it was").isTrue();
    }

    /** What the record says of the called package: what run.started says of the run's own, for the child run. */
    @Test
    void theChildRunOfAnotherPackage_startsWithALineThatNamesThePackage() throws IOException {
        String digest = callee("echo same-output");
        String id = run(CALLER);

        assertThat(fx.ofType(id, "run.package")).hasSize(1);
        RecordedEvent line = fx.ofType(id, "run.package").getFirst();
        String child = line.run();

        assertThat(line.parent()).isEqualTo(id);
        assertThat(child).isNotEqualTo(id);
        assertThat(fx.events(id).stream().filter(e -> e.run().equals(child)).findFirst().orElseThrow().seq())
                .as("the first line of the child run").isEqualTo(line.seq());
        JsonNode fields = line.fields();
        assertThat(fields.path("workflow").path("id").asText()).isEqualTo("wf-callee");
        assertThat(fields.path("workflow").path("name").asText()).isEqualTo("Callee");
        assertThat(fields.path("reference").asText()).isEqualTo("../callee");
        assertThat(fields.path("package").path("dir").asText()).isEqualTo(CALLEE.toString());
        assertThat(fields.path("package").path("digest").asText()).isEqualTo(digest);
        assertThat(digest(fx.text(id, line, "definition"))).as("the workflow.json as a blob").isEqualTo(digest);
        assertThat(fx.ofType(id, "run.started").getFirst().fields().path("records")).extracting(JsonNode::asText)
                .contains("called_packages");
        RunView view = services.view(id);
        assertThat(view.recordsCalledPackages()).isTrue();
        assertThat(view.children().get(child).packageCall().packageDigest()).isEqualTo(digest);
        assertThat(view.packageCall()).as("the run itself was called by nobody").isNull();
    }

    // ---- a child of the run's own package ----

    /**
     * A workflow that calls itself once: its child is of the run's own package, which the package digest covers.
     * The record and the manifest are what they were.
     */
    @Test
    void aChildOfTheRunsOwnPackage_leavesNoLine_andNoFieldInTheManifest() throws IOException {
        Path self = Path.of("/pkgs/self");
        fx.packages.put(self, """
                {"id": "wf-self", "name": "Self", "blocks": [
                  {"type": "sub-workflow", "name": "again", "config": {"workflow_id": ".", "run_if": "./first"}},
                  {"type": "shell", "name": "say", "config": {"command": "./third"}}
                ]}""");
        AtomicInteger asked = new AtomicInteger();
        fx.commands.on("./first", req -> FakeCommandRunner.exit(asked.getAndIncrement() == 0 ? 0 : 1, ""));

        String id = run(self);

        assertThat(services.view(id).children()).as("the workflow ran as its own child").hasSize(1);
        assertThat(fx.types(id)).doesNotContain("run.package");
        assertThat(manifest(id).fieldNames()).toIterable().containsExactly("run_id", "workflow", "project", "actor",
                "package_digest", "seal", "inputs_digest", "execution", "images", "models", "tool_versions", "events",
                "head", "run_started", "status", "gate", "started_at", "finished_at", "traceworks_version",
                "signature");
        RunView view = services.view(id);
        Manifest asBefore = new Manifest(id, "wf-self", "Self", view.packageDigest(), view.inputs().sha256(), "host",
                List.of(), List.of(), view.eventCount(), view.head(), RunStatus.COMPLETED, null, view.startedAt(),
                view.finishedAt(), TraceworksVersion.current(), null, view.runStarted(),
                new RunStarter(AppFixture.PROJECT, AppFixture.ACTOR));
        assertThat(new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8))
                .as("byte for byte the manifest of a run without a list of called packages")
                .isEqualTo(new String(asBefore.toJson(), StandardCharsets.UTF_8));
        assertThat(services.verify(id).ok()).isTrue();
        assertThat(services.compare(id, run(self)).header()).noneMatch(f -> f.name().startsWith("called package"));
    }

    /** A workflow that runs {@code ./rewrite}, then calls its own package once, then runs {@code say}. */
    private static String selfCalling(String say) {
        return """
                {"id": "wf-self", "name": "Self", "blocks": [
                  {"type": "shell", "name": "rewrite", "config": {"command": "./rewrite"}},
                  {"type": "sub-workflow", "name": "again", "config": {"workflow_id": ".", "run_if": "./first"}},
                  {"type": "shell", "name": "say", "config": {"command": "%s"}}
                ]}""".formatted(say);
    }

    /** One run of {@link #selfCalling} whose first step rewrites the package's workflow to say {@code after}. */
    private String runThatRewritesItself(Path self, String after) {
        fx.packages.put(self, selfCalling("./third"));
        AtomicInteger asked = new AtomicInteger();
        fx.commands.on("./first", req -> FakeCommandRunner.exit(asked.getAndIncrement() == 0 ? 0 : 1, ""));
        fx.commands.on("./rewrite", req -> {
            fx.packages.put(self, selfCalling(after));
            return FakeCommandRunner.ok("rewritten");
        });
        return run(self);
    }

    /**
     * Reproduced 2026-11-21: step 0 of a package runs while its {@code workflow.json} is rewritten, and step 1
     * calls the package itself ({@code workflow_id: "."}). The child ran the rewritten workflow, the record had no
     * {@code run.package}, the manifest no {@code called_packages}, and {@code diff} of two such runs said same. A
     * child is named whenever what was loaded for it is not what {@code run.started} names.
     */
    @Test
    void aChildOfTheRunsOwnPackageThatChangedWhileTheRunRan_isNamedLikeACalledPackage() throws IOException {
        Path self = Path.of("/pkgs/self");
        fx.commands.on("./other", "third").on("./another", "third");

        String a = runThatRewritesItself(self, "./other");

        String started = digest(selfCalling("./third"));
        assertThat(manifest(a).path("package_digest").asText()).isEqualTo(started);
        assertThat(fx.commands.commands()).as("the child ran the workflow as it was rewritten").contains("./other");
        assertThat(fx.ofType(a, "run.package")).hasSize(1);
        JsonNode line = fx.ofType(a, "run.package").getFirst().fields();
        assertThat(line.path("workflow").path("id").asText()).isEqualTo("wf-self");
        assertThat(line.path("reference").asText()).isEqualTo(".");
        assertThat(line.path("package").path("dir").asText()).isEqualTo(self.toString());
        assertThat(line.path("package").path("digest").asText()).isEqualTo(digest(selfCalling("./other")))
                .isNotEqualTo(started);
        assertThat(called(a)).containsExactly("wf-self " + digest(selfCalling("./other")));
        assertThat(services.verify(a).ok()).isTrue();

        String b = runThatRewritesItself(self, "./another");

        assertThat(manifest(b).path("package_digest").asText()).as("both runs started with the same package")
                .isEqualTo(started);
        RunComparison comparison = services.compare(a, b);
        assertThat(comparison.reproduced()).isFalse();
        assertThat(comparison.differences()).contains("a called package differs: wf-self");
        assertThat(services.compare(a, runThatRewritesItself(self, "./other")).reproduced())
                .as("rewritten the same way").isTrue();
    }

    // ---- foreach ----

    private void loopOverTheCallee() {
        fx.packages.put(CALLER, """
                {"id": "wf-caller", "name": "Caller", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"items": ["a", "b", "c"], "workflow_id": "../callee"}}
                ]}""");
    }

    @Test
    void aLoopWhoseItemsCallAnotherPackage_namesThePackageOnce() throws IOException {
        loopOverTheCallee();
        String digest = callee("echo same-output");

        String id = run(CALLER);

        assertThat(fx.ofType(id, "run.package")).as("one line per child run").hasSize(3);
        assertThat(called(id)).containsExactly("wf-callee " + digest);
    }

    /** Each call loads the package anew: one that changes while the run goes on shows as what each child ran. */
    @Test
    void aPackageThatChangesBetweenTwoItems_isNamedTwice() throws IOException {
        loopOverTheCallee();
        String first = callee("echo same-output");
        String[] second = new String[1];
        AtomicInteger calls = new AtomicInteger();
        fx.commands.on("echo same-output", req -> {
            if (calls.incrementAndGet() == 1) {
                second[0] = callee("printf same-output");
            }
            return FakeCommandRunner.ok("same-output");
        });

        String id = run(CALLER);

        assertThat(fx.commands.commands()).containsExactly("echo same-output", "printf same-output",
                "printf same-output");
        assertThat(called(id)).containsExactlyInAnyOrder("wf-callee " + first, "wf-callee " + second[0]);
        assertThat(called(id)).as("sorted by workflow id, then by digest").isSorted();
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** One run of the loop over the callee, whose package changes after the child of item {@code after} ran. */
    private String loopWhoseCalleeChangesAfterItem(int after) {
        loopOverTheCallee();
        callee("echo same-output");
        AtomicInteger calls = new AtomicInteger();
        fx.commands.on("echo same-output", req -> {
            if (calls.incrementAndGet() == after + 1) {
                callee("printf same-output");
            }
            return FakeCommandRunner.ok("same-output");
        });
        return run(CALLER);
    }

    /**
     * Reproduced 2026-11-21: a loop over three items; in run A the items 0 and 1 ran the called package as it was
     * and item 2 as it was changed, in run B item 0 and then the items 1 and 2. Both runs called the same two
     * digests, so their lists are equal, and {@code diff} said same. It compares the called packages call by call.
     */
    @Test
    void twoRunsThatCalledTheSamePackagesInAnotherOrder_differCallByCall() throws IOException {
        String first = digest("""
                {"id": "wf-callee", "name": "Callee", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "echo same-output"}}
                ]}""");
        String a = loopWhoseCalleeChangesAfterItem(1);
        String b = loopWhoseCalleeChangesAfterItem(0);

        assertThat(called(a)).as("the same set of digests").isEqualTo(called(b)).hasSize(2);

        RunComparison comparison = services.compare(a, b);

        assertThat(comparison.reproduced()).isFalse();
        assertThat(comparison.differences()).singleElement().asString()
                .startsWith("step each [item 1]: the called package differs (" + first + " in run A, ")
                .endsWith(" in run B)");
        assertThat(services.compare(a, loopWhoseCalleeChangesAfterItem(1)).reproduced())
                .as("changed after the same item").isTrue();
    }

    // ---- a child of a child ----

    @Test
    void aChildThatCallsAChildOfAThirdPackage_namesBoth() throws IOException {
        String calleeJson = """
                {"id": "wf-callee", "name": "Callee", "blocks": [
                  {"type": "sub-workflow", "name": "on", "config": {"workflow_id": "../third"}}
                ]}""";
        String thirdJson = """
                {"id": "wf-third", "name": "Third", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "./third"}}
                ]}""";
        fx.packages.put(CALLEE, calleeJson);
        fx.packages.put(THIRD, thirdJson);

        String id = run(CALLER);

        assertThat(called(id)).containsExactly("wf-callee " + digest(calleeJson), "wf-third " + digest(thirdJson));
        List<RecordedEvent> lines = fx.ofType(id, "run.package");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).parent()).as("the grandchild names the child").isEqualTo(lines.get(0).run());
        assertThat(lines.get(1).fields().path("reference").asText()).isEqualTo("../third");
    }

    // ---- a parked and resumed run ----

    @Test
    void aParkedAndResumedRun_namesThePackagesCalledBeforeAndAfterTheResume() throws IOException {
        fx.packages.put(CALLER, """
                {"id": "wf-caller", "name": "Caller", "blocks": [
                  {"type": "sub-workflow", "name": "before", "config": {"workflow_id": "../callee"}},
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"]}},
                  {"type": "sub-workflow", "name": "after", "config": {"workflow_id": "../third"}}
                ]}""");
        String calleeDigest = callee("echo same-output");
        String thirdJson = """
                {"id": "wf-third", "name": "Third", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "./third"}}
                ]}""";
        fx.packages.put(THIRD, thirdJson);

        RunResult parked = services.start(request(CALLER), null, null);
        String id = parked.runId();
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        assertThat(fx.ofType(id, "run.package")).hasSize(1);
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        assertThat(services.resume(resumeOf(id), null, null).status()).isEqualTo(RunStatus.COMPLETED);

        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("records")).extracting(JsonNode::asText)
                .contains("called_packages");
        assertThat(services.view(id).recordsCalledPackages()).isTrue();
        assertThat(called(id)).containsExactly("wf-callee " + calleeDigest, "wf-third " + digest(thirdJson));
        assertThat(services.verify(id).ok()).isTrue();
    }

    // ---- verify ----

    @Test
    void aManifestWhoseListWasChanged_orThatLacksACalledPackage_doesNotVerify() throws IOException {
        String digest = callee("echo same-output");
        String id = run(CALLER);
        String original = new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(services.verify(id).manifest()).isEqualTo(VerifyReport.OK);
        assertThat(services.verify(id).ledger()).isEqualTo(VerifyReport.OK);

        String otherDigest = original.replace(digest, "0".repeat(64));
        assertThat(otherDigest).isNotEqualTo(original);
        fx.run(id).writeManifest(otherDigest.getBytes(StandardCharsets.UTF_8));
        VerifyReport changed = services.verify(id);
        assertThat(changed.manifest()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(changed.ledger()).as("the end entry is compared with the manifest, as before")
                .isEqualTo(VerifyReport.MISMATCH);
        assertThat(changed.ok()).isFalse();

        String without = original.replaceFirst("(?s)  \"called_packages\" : \\[.*?\\],\\R", "");
        assertThat(without).doesNotContain("called_packages").contains("\"package_digest\"");
        fx.run(id).writeManifest(without.getBytes(StandardCharsets.UTF_8));
        // The forger writes the end entry of the ledger too: the manifest is still not the one the record gives
        fx.ledger.forgeEnd(id, "COMPLETED", without.getBytes(StandardCharsets.UTF_8));
        VerifyReport lacking = services.verify(id);
        assertThat(lacking.manifest()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(lacking.ok()).isFalse();

        fx.run(id).writeManifest(original.getBytes(StandardCharsets.UTF_8));
        fx.ledger.forgeEnd(id, "COMPLETED", original.getBytes(StandardCharsets.UTF_8));
        assertThat(services.verify(id).ok()).as("the manifest the run wrote").isTrue();
    }

    // ---- a record of an earlier version ----

    /**
     * The record of {@code id} as a version before this one wrote it: without the {@code run.package} lines and
     * without {@code called_packages} in {@code records}, chained anew, with the manifest and the entries of the
     * ledger such a version wrote for it.
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
        int seq = 0;
        for (byte[] line : lines) {
            ObjectNode node = (ObjectNode) JSON.readTree(line);
            if ("run.package".equals(node.path("type").asText())) {
                continue;
            }
            if (node.path("records").isArray()) {
                ArrayNode records = JSON.createArrayNode();
                node.path("records").forEach(name -> {
                    if (!"called_packages".equals(name.asText())) {
                        records.add(name);
                    }
                });
                node.set("records", records);
            }
            node.put("seq", seq++);
            node.put("prev", prev);
            prev = run.log().append(JSON.writeValueAsBytes(node));
            newStart = newStart == null ? prev : newStart;
        }
        assertThat(manifest).contains(oldHead, oldStart, "\"events\" : " + lines.size() + ",");
        String rewritten = manifest.replaceFirst("(?s)  \"called_packages\" : \\[.*?\\],\\R", "")
                .replace(oldHead, prev).replace(oldStart, newStart)
                .replace("\"events\" : " + lines.size() + ",", "\"events\" : " + seq + ",");
        run.writeManifest(rewritten.getBytes(StandardCharsets.UTF_8));
        fx.ledger.replaceStart(id, start.replace(oldStart, newStart).getBytes(StandardCharsets.UTF_8));
        fx.ledger.forgeEnd(id, "COMPLETED", rewritten.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRecordOfAnEarlierVersion_isReadAndVerifies_andDiffSaysThatItCannotCompareTheCalledPackages()
            throws IOException {
        String old = run(CALLER);
        asAnEarlierVersionWroteIt(old);
        callee("printf same-output");
        String now = run(CALLER);

        RunView view = services.view(old);
        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(view.children()).hasSize(1);
        assertThat(view.recordsCalledPackages()).isFalse();
        assertThat(view.packageCalls()).isEmpty();
        assertThat(manifest(old).has("called_packages")).isFalse();
        VerifyReport report = services.verify(old);
        assertThat(report.ok()).as(report.manifest() + " " + report.ledger() + " " + report.start()
                + " " + report.chain().problems()).isTrue();

        for (RunComparison comparison : List.of(services.compare(old, now), services.compare(now, old))) {
            assertThat(comparison.reproduced()).as(comparison.differences().toString()).isTrue();
            assertThat(comparison.notes()).containsExactly("run " + old + " is of a version that does not record"
                    + " the packages a run calls: the called packages of the two runs are not compared");
            assertThat(comparison.header()).noneMatch(field -> field.name().startsWith("called package"));
        }
    }

    // ---- a sealed run ----

    /** The seal covers the called package as before; the manifest names it beside the seal. */
    @Test
    void aSealedRunThatCallsAnotherPackage_isCheckedAsBefore_andItsManifestNamesThePackage() throws IOException {
        fx.allowSigner();
        String digest = callee("echo same-output");
        Seals.Sealed sealed = services.seals().seal(CALLER, null);

        String id = run(CALLER);

        JsonNode manifest = manifest(id);
        assertThat(manifest.path("seal").asText()).isEqualTo("sealed");
        assertThat(manifest.path("seal_digest").asText()).isEqualTo(sealed.seal().digest());
        assertThat(called(id)).containsExactly("wf-callee " + digest);
        VerifyReport report = services.verify(id);
        assertThat(report.ok()).isTrue();
        assertThat(report.seal()).startsWith("ok");

        callee("printf same-output");
        int runs = fx.store.runIds().size();
        assertThatThrownBy(() -> services.start(request(CALLER), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("changed since it was sealed");
        assertThat(fx.store.runIds()).as("the seal still refuses a called package that changed").hasSize(runs);
    }
}
