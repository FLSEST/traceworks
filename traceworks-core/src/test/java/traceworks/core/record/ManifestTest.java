package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.model.GateStatus;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.RunEvent.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestTest {

    private final RecordFixture fx = new RecordFixture().started();

    private void finishedRun() {
        fx.emit(new StepStarted(0, "ask", "ai", 0, true),
                new StepFinished(0, "ask", "ai", StepStatus.COMPLETED, "p", 1, Map.of("model", "zeta-1")),
                new StepStarted(1, "ask2", "ai", 0, true),
                new StepFinished(1, "ask2", "ai", StepStatus.COMPLETED, "p", 1, Map.of("model", "alpha-2")),
                new StepStarted(2, "ask3", "ai", 0, true),
                new StepFinished(2, "ask3", "ai", StepStatus.COMPLETED, "p", 1, Map.of("model", "alpha-2")),
                new GateReported(2, GateStatus.RED, "security", "bad"),
                new RunFinished(RunStatus.COMPLETED_WITH_ERRORS, Map.of("gate_status", "RED",
                        "gate_category", "security"), null));
    }

    private Manifest manifest() {
        return Manifest.of(RunView.of(fx.events(), false), fx.blobs, "0.1.0");
    }

    private JsonNode json() throws Exception {
        return new ObjectMapper().readTree(manifest().toJson());
    }

    @Test
    void manifest_isDeterministic() {
        finishedRun();

        assertThat(manifest().toJson()).isEqualTo(manifest().toJson());
        assertThat(new String(manifest().toJson(), StandardCharsets.UTF_8)).endsWith("}\n");
    }

    @Test
    void fieldOrder_isFixed() throws Exception {
        finishedRun();

        List<String> names = new ArrayList<>();
        Iterator<String> it = json().fieldNames();
        it.forEachRemaining(names::add);

        assertThat(names).containsExactly("run_id", "workflow", "project", "actor", "package_digest", "seal",
                "inputs_digest",
                "execution", "images", "models", "tool_versions", "events", "head", "run_started", "status", "gate", "started_at",
                "finished_at", "traceworks_version", "signature");
    }

    @Test
    void theFixedValues() throws Exception {
        finishedRun();

        JsonNode m = json();

        assertThat(m.get("run_id").asText()).isEqualTo(RecordFixture.RUN);
        assertThat(m.get("workflow").get("id").asText()).isEqualTo("wf");
        assertThat(m.get("workflow").get("name").asText()).isEqualTo("Wf");
        assertThat(m.get("package_digest").asText()).isEqualTo("digest-1");
        assertThat(m.get("seal").asText()).isEqualTo("unsealed");
        assertThat(m.get("execution").asText()).isEqualTo("host");
        assertThat(m.get("images").isArray()).isTrue();
        assertThat(m.get("images")).isEmpty();
        assertThat(m.get("tool_versions").isObject()).isTrue();
        assertThat(m.get("signature").isNull()).isTrue();
        assertThat(m.get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(m.get("gate").get("status").asText()).isEqualTo("RED");
        assertThat(m.get("gate").get("category").asText()).isEqualTo("security");
        assertThat(m.get("events").asInt()).isEqualTo(fx.lines().size());
        assertThat(m.get("head").asText()).isEqualTo(fx.recorder.head());
        assertThat(m.get("traceworks_version").asText()).isEqualTo("0.1.0");
        assertThat(m.get("started_at").asText()).matches("2026-11-03T12:00:00\\.\\d{3}Z");
        assertThat(m.get("finished_at").asText()).matches("2026-11-03T12:00:\\d\\d\\.\\d{3}Z");
    }

    private static PackageCalled called(String workflowId, String digest) {
        return new PackageCalled(workflowId, "Name", "../" + workflowId, "/pkgs/" + workflowId, digest,
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The package digest covers the run's own package only: the manifest lists the package of every child run of
     * another package, at every depth, each workflow id and digest once, sorted, right after the package digest.
     */
    @Test
    void calledPackages_areCollectedFromTheChildRuns_eachOnce_sorted() throws Exception {
        fx.emit(new StepStarted(0, "p", "foreach", 0, false),
                new ChildRunEvent("c1", called("wf-tools", "digest-b")),
                new ChildRunEvent("c1", new ChildRunEvent("g1", called("wf-deep", "digest-z"))),
                new ChildRunEvent("c2", called("wf-tools", "digest-b")),
                new ChildRunEvent("c3", called("wf-tools", "digest-a")),
                new ChildRunEvent("c4", new RunFinished(RunStatus.COMPLETED, Map.of(), null)),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        assertThat(manifest().calledPackages()).containsExactly(
                new Manifest.CalledPackage("wf-deep", "digest-z"), new Manifest.CalledPackage("wf-tools", "digest-a"),
                new Manifest.CalledPackage("wf-tools", "digest-b"));
        assertThat(json().get("called_packages").toString()).isEqualTo(
                "[{\"workflow_id\":\"wf-deep\",\"package_digest\":\"digest-z\"},"
                        + "{\"workflow_id\":\"wf-tools\",\"package_digest\":\"digest-a\"},"
                        + "{\"workflow_id\":\"wf-tools\",\"package_digest\":\"digest-b\"}]");
        List<String> names = new ArrayList<>();
        json().fieldNames().forEachRemaining(names::add);
        assertThat(names).containsSubsequence("package_digest", "called_packages", "seal");
        assertThat(manifest().withModels(List.of("m")).calledPackages()).hasSize(3);
        assertThat(manifest().withoutRunStarted().calledPackages()).hasSize(3);
        assertThat(manifest().withoutStarter().calledPackages()).hasSize(3);
    }

    /** A run whose children are all of its own package has no such field: its manifest keeps its shape. */
    @Test
    void aRunThatCalledNoOtherPackage_hasNoCalledPackagesField() throws Exception {
        fx.emit(new StepStarted(0, "p", "sub-workflow", 0, false),
                new ChildRunEvent("c", new RunFinished(RunStatus.COMPLETED, Map.of(), null)),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        assertThat(manifest().calledPackages()).isEmpty();
        assertThat(json().has("called_packages")).isFalse();
    }

    @Test
    void runWithoutGate_hasAnEmptyModelListAndNullGate() throws Exception {
        fx.emit(new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        JsonNode m = json();

        assertThat(m.get("gate").isNull()).isTrue();
        assertThat(m.get("models").isArray()).isTrue();
        assertThat(m.get("models")).isEmpty();
    }

    @Test
    void inputsDigest_isTheInputsBlobHash() throws Exception {
        finishedRun();
        String inputsSha = RecordedEvent.parse(fx.lines().getFirst()).blob("inputs").sha256();

        assertThat(json().get("inputs_digest").asText()).isEqualTo(inputsSha);
    }

    @Test
    void models_areCollectedFromStepMetadata() throws Exception {
        finishedRun();

        assertThat(manifest().models()).containsExactly("alpha-2", "zeta-1");
        assertThat(json().get("models")).extracting(JsonNode::asText).containsExactly("alpha-2", "zeta-1");
    }

    @Test
    void models_ofChildRunsCount_andADeletedMetadataBlobContributesNone() {
        fx.emit(new StepStarted(0, "p", "sub-workflow", 0, false),
                new ChildRunEvent("c", new StepStarted(0, "i", "ai", 0, true)),
                new ChildRunEvent("c", new StepFinished(0, "i", "ai", StepStatus.COMPLETED, "p", 1,
                        Map.of("model", "child-model"))),
                new StepFinished(0, "p", "sub-workflow", StepStatus.COMPLETED, "i", 1, Map.of("model", "gone")),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));
        RunView view = RunView.of(fx.events(), false);
        fx.blobs.delete(view.steps().getFirst().metadata().sha256());

        assertThat(Manifest.of(view, fx.blobs, "0.1.0").models()).containsExactly("child-model");
    }

    /** A deleted metadata blob of a child run counts as one, so the model it named may still be written. */
    @Test
    void aDeletedMetadataBlobOfAChildRun_countsAsDeleted() {
        fx.emit(new StepStarted(0, "p", "sub-workflow", 0, false),
                new ChildRunEvent("c", new StepStarted(0, "i", "ai", 0, true)),
                new ChildRunEvent("c", new StepFinished(0, "i", "ai", StepStatus.COMPLETED, "p", 1,
                        Map.of("model", "child-model"))),
                new StepFinished(0, "p", "sub-workflow", StepStatus.COMPLETED, "i", 1, Map.of("model", "parent")),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));
        RunView view = RunView.of(fx.events(), false);
        fx.blobs.delete(view.children().get("c").steps().getFirst().metadata().sha256());

        assertThat(Manifest.metadataDeleted(view, fx.blobs)).isTrue();
        assertThat(Manifest.modelsPossible(List.of("child-model", "parent"), view, fx.blobs)).isTrue();
    }

    @Test
    void manifest_listsEachImageOnce() throws Exception {
        CommandRunner byImage = new CommandRunner() {
            @Override
            public CommandResult run(CommandRequest request) {
                return plan(request).execution().get();
            }

            @Override
            public CommandPlan plan(CommandRequest request) {
                String image = request.image() == null ? "shell" : request.image();
                String id = image.equals("missing:1") ? null : "sha256:" + image.length() + "0".repeat(63);
                return new CommandPlan(new CommandFacts("/workspace", image, id, List.of(), false, List.of(), false),
                        () -> new CommandResult(id == null ? 125 : 0, "", "", 1, false));
            }
        };
        CommandRunner commands = fx.recorder.recording(byImage);
        fx.emit(new StepStarted(0, "a", "shell", 0, false));
        commands.run(new CommandRequest("x", "/w", 10, null, null, List.of(), false));
        commands.run(new CommandRequest("x", "/w", 10, null, "alpine:3", List.of(), false));
        commands.run(new CommandRequest("x", "/w", 10, null, null, List.of(), false));
        commands.run(new CommandRequest("x", "/w", 10, null, "missing:1", List.of(), false));
        fx.emit(new StepFinished(0, "a", "shell", StepStatus.COMPLETED, "", 1, Map.of()),
                new StepStarted(1, "p", "sub-workflow", 1, false),
                new ChildRunEvent("c", new StepStarted(0, "i", "shell", 0, false)));
        commands.run(new CommandRequest("x", "/w", 10, null, "./images/tool", List.of(), false));
        commands.run(new CommandRequest("x", "/w", 10, null, "alpine:3", List.of(), false));
        fx.emit(new ChildRunEvent("c", new StepFinished(0, "i", "shell", StepStatus.COMPLETED, "", 1, Map.of())),
                new StepFinished(1, "p", "sub-workflow", StepStatus.COMPLETED, "", 1, Map.of()),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        assertThat(manifest().images()).containsExactly(
                new Manifest.Image("./images/tool", "sha256:13" + "0".repeat(63)),
                new Manifest.Image("alpine:3", "sha256:8" + "0".repeat(63)),
                new Manifest.Image("shell", "sha256:5" + "0".repeat(63)));
        JsonNode images = json().get("images");
        assertThat(images).hasSize(3);
        assertThat(images.get(0).get("reference").asText()).isEqualTo("./images/tool");
        assertThat(images.get(0).get("id").asText()).isEqualTo("sha256:13" + "0".repeat(63));
        assertThat(new ArrayList<String>() {{ images.get(0).fieldNames().forEachRemaining(this::add); }})
                .containsExactly("reference", "id");
    }

    @Test
    void execution_isTheOneRunStartedRecords() throws Exception {
        RecordFixture container = new RecordFixture().started("container");
        container.emit(new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        JsonNode m = new ObjectMapper().readTree(Manifest.of(RunView.of(container.events(), false), container.blobs,
                "0.1.0").toJson());

        assertThat(m.get("execution").asText()).isEqualTo("container");
        assertThat(RunView.of(container.events(), false).execution()).isEqualTo("container");
    }

    @Test
    void parkedRun_hasNoManifest() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false), new RunParked(0, "approval"));
        RunView view = RunView.of(fx.events(), false);

        assertThatThrownBy(() -> Manifest.of(view, fx.blobs, "0.1.0")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void interruptedRun_hasNoManifest() {
        fx.emit(new StepStarted(0, "x", "shell", 0, false));
        RunView view = RunView.of(fx.events(), false);

        assertThatThrownBy(() -> Manifest.of(view, fx.blobs, "0.1.0")).isInstanceOf(IllegalStateException.class);
    }

    /** Three AI steps (zeta-1, alpha-2, alpha-2); the metadata blob of the first one is deleted. */
    private RunView withTheFirstMetadataDeleted() {
        finishedRun();
        RunView view = RunView.of(fx.events(), false);
        fx.blobs.delete(view.steps().getFirst().metadata().sha256());
        return view;
    }

    @Test
    void afterADeletedMetadataBlob_theWrittenModelsMayNameOneMoreModelThanTheRecordStillTells() {
        RunView view = withTheFirstMetadataDeleted();

        assertThat(Manifest.models(view, fx.blobs)).containsExactly("alpha-2");
        assertThat(Manifest.modelsPossible(List.of("alpha-2", "zeta-1"), view, fx.blobs)).isTrue();
        assertThat(Manifest.modelsPossible(List.of("alpha-2"), view, fx.blobs)).isTrue();
    }

    @Test
    void afterADeletedMetadataBlob_theWrittenModelsMustStillNameEveryModelTheRemainingBlobsName() {
        RunView view = withTheFirstMetadataDeleted();

        assertThat(Manifest.modelsPossible(List.of("some-other-model"), view, fx.blobs)).isFalse();
        assertThat(Manifest.modelsPossible(List.of(), view, fx.blobs)).isFalse();
    }

    @Test
    void afterADeletedMetadataBlob_theWrittenModelsNameNoMoreModelsThanBlobsAreDeleted_andStaySortedAndDistinct() {
        RunView view = withTheFirstMetadataDeleted();

        assertThat(Manifest.modelsPossible(List.of("alpha-2", "beta-9", "zeta-1"), view, fx.blobs)).isFalse();
        assertThat(Manifest.modelsPossible(List.of("zeta-1", "alpha-2"), view, fx.blobs)).isFalse();
        assertThat(Manifest.modelsPossible(List.of("alpha-2", "alpha-2"), view, fx.blobs)).isFalse();
    }

    @Test
    void withEveryMetadataBlobThere_onlyTheModelsOfTheRecordArePossible() {
        finishedRun();
        RunView view = RunView.of(fx.events(), false);

        assertThat(Manifest.modelsPossible(List.of("alpha-2", "zeta-1"), view, fx.blobs)).isTrue();
        assertThat(Manifest.modelsPossible(List.of("alpha-2", "beta-9", "zeta-1"), view, fx.blobs)).isFalse();
    }
}
