package traceworks.core.record;

import traceworks.core.port.AiNetwork;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ContextState;
import traceworks.core.model.GateStatus;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BlobRef;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.*;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import traceworks.core.testing.InMemoryRunStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RunRecorderTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final Clock clock = () -> Instant.parse("2026-11-03T12:00:00.123456789Z");
    private final RunRecorder recorder = RunRecorder.start(log, blobs, clock, RUN, definition());

    private static WorkflowDefinition definition() {
        BlockDefinition shell = BlockDefinition.builder().type("shell").name("build")
                .config(Map.of("command", "echo {{ ticket }} {{ name }}",
                        "env", Map.of("A", "{{ token }}"), "list", List.of("{{ ticket }}", "plain")))
                .build();
        return new WorkflowDefinition("wf", "Wf", null, List.of(shell), null, null, null, null, null, null, null,
                null, null);
    }

    private List<JsonNode> lines() throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (byte[] line : log.readAll()) {
            result.add(JSON.readTree(line));
        }
        return result;
    }

    private String text(JsonNode blobNode) {
        return new String(blobs.get(blobNode.get("sha256").asText()).orElseThrow(), StandardCharsets.UTF_8);
    }

    @Test
    void firstEvent_chainsToTheGenesis() throws Exception {
        recorder.emit(new StepWaiting(0, true));

        assertThat(lines().get(0).get("prev").asText()).isEqualTo(EventChain.genesis(RUN));
        assertThat(lines().get(0).get("seq").asInt()).isZero();
    }

    @Test
    void everyEvent_chainsToThePreviousLine() throws Exception {
        recorder.emit(new StepWaiting(0, true));
        recorder.emit(new StepWaiting(0, false));
        recorder.emit(new RunStatusChanged(RunStatus.RUNNING));

        List<byte[]> raw = log.readAll();
        List<JsonNode> nodes = lines();
        for (int i = 1; i < raw.size(); i++) {
            assertThat(nodes.get(i).get("prev").asText()).isEqualTo(EventChain.sha256Hex(raw.get(i - 1)));
        }
        assertThat(recorder.head()).isEqualTo(EventChain.sha256Hex(raw.get(2)));
        assertThat(recorder.nextSeq()).isEqualTo(3);
    }

    @Test
    void at_hasExactlyThreeFractionDigits() throws Exception {
        recorder.emit(new StepWaiting(0, true));

        assertThat(lines().get(0).get("at").asText()).isEqualTo("2026-11-03T12:00:00.123Z");
        Clock whole = () -> Instant.parse("2026-11-03T12:00:00Z");
        InMemoryEventLog otherLog = new InMemoryEventLog();
        RunRecorder.start(otherLog, blobs, whole, RUN, definition()).emit(new StepWaiting(0, true));
        assertThat(JSON.readTree(otherLog.readAll().get(0)).get("at").asText()).isEqualTo("2026-11-03T12:00:00.000Z");
    }

    @Test
    void fieldOrder_isFixed() {
        recorder.emit(new OutputRecorded(2, "build", "text"));

        String line = new String(log.readAll().get(0), StandardCharsets.UTF_8);
        assertThat(line).matches("\\{\"seq\":0,\"at\":\"[^\"]+\",\"type\":\"step\\.output\",\"run\":\"" + RUN
                + "\",\"prev\":\"[0-9a-f]{64}\",\"step\":2,\"name\":\"build\",\"output\":\\{\"sha256\":.*");
    }

    @Test
    void content_isABlob_andNeverInline() throws Exception {
        recorder.emit(new OutputRecorded(0, "build", "PERSONAL-42"));

        for (byte[] line : log.readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain("PERSONAL-42");
        }
        JsonNode output = lines().get(0).get("output");
        assertThat(output.get("size").asInt()).isEqualTo(11);
        assertThat(text(output)).isEqualTo("PERSONAL-42");
        BlobRef ref = new BlobRef(output.get("sha256").asText(), 11);
        assertThat(blobs.get(ref.sha256())).isPresent();
    }

    @Test
    void nullContent_isJsonNull() throws Exception {
        recorder.emit(new GateReported(0, GateStatus.RED, null, null));
        recorder.emit(new ApprovalRequested(0, "b", "approval", "Ok?", null, null, null, null, true, null, null,
                null));

        List<JsonNode> nodes = lines();
        assertThat(nodes.get(0).get("description").isNull()).isTrue();
        assertThat(nodes.get(0).get("category").isNull()).isTrue();
        assertThat(nodes.get(1).get("default").isNull()).isTrue();
        assertThat(blobs.size()).isEqualTo(1);
    }

    @Test
    void stepStarted_listsTheUsedPlaceholders_sortedAndDistinct() throws Exception {
        recorder.emit(new StepStarted(0, "build", "shell", 0, false));
        recorder.emit(new StepStarted(1, "ask", "agentic", 0, true));

        List<JsonNode> nodes = lines();
        assertThat(nodes.get(0).get("type").asText()).isEqualTo("step.started");
        assertThat(JSON.convertValue(nodes.get(0).get("uses"), List.class)).containsExactly("name", "ticket", "token");
        assertThat(nodes.get(0).get("mode").asText()).isEqualTo("deterministic");
        assertThat(nodes.get(0).get("block_type").asText()).isEqualTo("shell");
        assertThat(nodes.get(0).get("position").asInt()).isZero();
        assertThat(nodes.get(1).get("mode").asText()).isEqualTo("ai");
    }

    @Test
    void childEvents_carryTheChildRunAndTheParent() throws Exception {
        recorder.emit(new ChildRunEvent("child-1", new StepStarted(0, "x", "shell", 0, false)));
        recorder.emit(new ChildRunEvent("child-1", new ChildRunEvent("grand-1",
                new OutputRecorded(0, "y", "o"))));
        recorder.emit(new OutputRecorded(0, "z", "o"));

        List<JsonNode> nodes = lines();
        assertThat(nodes.get(0).get("run").asText()).isEqualTo("child-1");
        assertThat(nodes.get(0).get("parent").asText()).isEqualTo(RUN);
        assertThat(nodes.get(0).get("uses")).isEmpty();
        assertThat(nodes.get(1).get("run").asText()).isEqualTo("grand-1");
        assertThat(nodes.get(1).get("parent").asText()).isEqualTo("child-1");
        assertThat(nodes.get(2).has("parent")).isFalse();
        assertThat(nodes.get(2).get("run").asText()).isEqualTo(RUN);
    }

    @Test
    void commands_areAttributedToTheRunningStep() throws Exception {
        FakeCommandRunner fake = new FakeCommandRunner().otherwise(FakeCommandRunner.exit(3, "x"));
        CommandRunner runner = recorder.recording(fake);
        CommandRequest request = new CommandRequest("make {{ secret }}", "/work", 30, Map.of("B", "1", "A", "2"),
                "img", List.of(new CommandRunner.Mount("/h", "/c", true)), false);

        runner.run(request);
        recorder.emit(new StepStarted(0, "build", "shell", 0, false));
        runner.run(request);
        recorder.emit(new ForEachItemStarted(0, 4, "it", 5));
        runner.run(request);
        recorder.emit(new ChildRunEvent("child-1", new StepStarted(7, "inner", "shell", 0, false)));
        runner.run(request);
        recorder.emit(new ChildRunEvent("child-1", new StepFinished(7, "inner", "shell", StepStatus.COMPLETED,
                "i", 1, null)));
        recorder.emit(new ForEachItemFinished(0, 4, "it", 5, StepStatus.COMPLETED, "o", "child-1"));
        runner.run(request);
        recorder.emit(new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "i", 1, null));
        runner.run(request);

        List<JsonNode> commands = lines().stream().filter(n -> n.get("type").asText().equals("step.command"))
                .toList();
        assertThat(commands).hasSize(6);
        assertThat(commands.get(0).get("step").isNull()).isTrue();
        assertThat(commands.get(0).get("item").isNull()).isTrue();
        assertThat(commands.get(1).get("step").asInt()).isZero();
        assertThat(commands.get(1).get("item").isNull()).isTrue();
        assertThat(commands.get(2).get("step").asInt()).isZero();
        assertThat(commands.get(2).get("item").asInt()).isEqualTo(4);
        assertThat(commands.get(3).get("step").asInt()).isEqualTo(7);
        assertThat(commands.get(3).get("run").asText()).isEqualTo("child-1");
        assertThat(commands.get(3).get("parent").asText()).isEqualTo(RUN);
        assertThat(commands.get(3).get("item").isNull()).isTrue();
        assertThat(commands.get(4).get("step").asInt()).isZero();
        assertThat(commands.get(4).get("item").isNull()).isTrue();
        assertThat(commands.get(4).get("run").asText()).isEqualTo(RUN);
        assertThat(commands.get(5).get("step").isNull()).isTrue();

        JsonNode first = commands.get(0);
        assertThat(text(first.get("command"))).isEqualTo("make {{ secret }}");
        assertThat(first.get("working_dir").asText()).isEqualTo("/work");
        assertThat(first.get("image").asText()).isEqualTo("img");
        assertThat(first.get("network").asText()).isEqualTo("none");
        assertThat(first.get("mounts").get(0).get("host").asText()).isEqualTo("/h");
        assertThat(first.get("mounts").get(0).get("container").asText()).isEqualTo("/c");
        assertThat(first.get("mounts").get(0).get("read_only").asBoolean()).isTrue();
        assertThat(JSON.convertValue(first.get("env_names"), List.class)).containsExactly("A", "B");
        assertThat(first.get("image_id").isNull()).as("a runner without a plan resolves no image").isTrue();
        assertThat(first.get("host").asBoolean()).as("a request with an image").isFalse();

        JsonNode finished = lines().stream().filter(n -> n.get("type").asText().equals("step.command.finished"))
                .findFirst().orElseThrow();
        assertThat(finished.get("exit_code").asInt()).isEqualTo(3);
        assertThat(finished.get("timed_out").asBoolean()).isFalse();
        assertThat(finished.get("duration_ms").asLong()).isEqualTo(1);
        assertThat(finished.get("step").isNull()).isTrue();
    }

    @Test
    void entrypointCommand_isRecordedAsItsArguments() throws Exception {
        CommandRunner runner = recorder.recording(new FakeCommandRunner().otherwise(FakeCommandRunner.ok("")));

        runner.run(CommandRequest.entrypoint("img", List.of("--a", "b c"), "/w", 5, null, null, true));

        JsonNode command = lines().get(0);
        assertThat(JSON.readTree(text(command.get("command")))).isEqualTo(JSON.readTree("[\"--a\",\"b c\"]"));
        assertThat(command.get("network").asText()).isEqualTo("open");
    }

    @Test
    void stepFinished_recordsStatusDurationInputAndMetadata() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exitCode", 2);
        recorder.emit(new StepFinished(3, "build", "shell", StepStatus.FAILED, "echo hi", 1234, metadata));

        JsonNode node = lines().get(0);
        assertThat(node.get("type").asText()).isEqualTo("step.finished");
        assertThat(node.get("step").asInt()).isEqualTo(3);
        assertThat(node.get("name").asText()).isEqualTo("build");
        assertThat(node.get("block_type").asText()).isEqualTo("shell");
        assertThat(node.get("status").asText()).isEqualTo("FAILED");
        assertThat(node.get("duration_ms").asLong()).isEqualTo(1234);
        assertThat(text(node.get("input"))).isEqualTo("echo hi");
        assertThat(JSON.readTree(text(node.get("metadata"))).get("exitCode").asInt()).isEqualTo(2);
    }

    @Test
    void checkpoint_storesTheSnapshot() throws Exception {
        RunSnapshot snapshot = new RunSnapshot(new ContextState(Map.of("a", "b"), null, Map.of("s", "out"), "out",
                null, 2, Map.of(), Map.of()), List.of(), Map.of("k", "v"));

        recorder.emit(new Checkpoint(1, snapshot));

        JsonNode node = lines().get(0);
        assertThat(node.get("type").asText()).isEqualTo("run.checkpoint");
        assertThat(node.get("next_position").asInt()).isEqualTo(1);
        byte[] state = blobs.get(node.get("state").get("sha256").asText()).orElseThrow();
        SnapshotTexts texts = new SnapshotTexts(blobs);
        assertThat(state).isEqualTo(RunSnapshotJson.write(snapshot, texts));
        assertThat(RunSnapshotJson.write(RunSnapshotJson.read(state, texts), texts)).isEqualTo(state);
    }

    /**
     * The state refers to the texts of its steps by their blobs: the blobs {@code step.output} and {@code step.finished}
     * stored for the same text, so a checkpoint adds no copy of an output.
     */
    @Test
    void checkpoint_refersToTheBlobsTheStepsEventsStored() throws Exception {
        String output = "built\n".repeat(10_000);
        StepResult step = StepResult.builder().id("step-0").stepIndex(0).blockName("build").blockType("shell")
                .status(StepStatus.COMPLETED).input("make build").output(output).metadata(Map.of()).build();
        recorder.emit(new OutputRecorded(0, "build", output));
        recorder.emit(new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "make build", 5, Map.of()));
        int stored = blobs.size();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("step_0_output", output);
        recorder.emit(new Checkpoint(1, new RunSnapshot(new ContextState(variables, null, Map.of("build", output),
                output, null, 1, Map.of(), Map.of()), List.of(step), Map.of())));

        List<JsonNode> lines = lines();
        JsonNode state = JSON.readTree(blobs.get(lines.get(2).get("state").get("sha256").asText()).orElseThrow());
        assertThat(state.get("format").asInt()).isEqualTo(RunSnapshotJson.FORMAT);
        JsonNode outputRef = lines.get(0).get("output");
        assertThat(state.at("/steps/0/output")).isEqualTo(outputRef);
        assertThat(state.at("/steps/0/input")).isEqualTo(lines.get(1).get("input"));
        assertThat(state.at("/context/block_outputs/build")).isEqualTo(outputRef);
        assertThat(state.at("/context/previous_output")).isEqualTo(outputRef);
        assertThat(state.at("/context/text_variables/step_0_output")).isEqualTo(outputRef);
        assertThat(blobs.size()).as("the state is the only new blob").isEqualTo(stored + 1);
    }

    @Test
    void runFinished_putsStatusAndCategoryInline_andTheRestInBlobs() throws Exception {
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("gate_status", "RED");
        gate.put("gate_category", "build");
        gate.put("gate_description", "SECRET-TEXT");
        gate.put("gate_summary", Map.of("items", List.of(Map.of("description", "SECRET-TEXT"))));
        recorder.emit(new RunFinished(RunStatus.FAILED, gate, "boom"));

        String raw = new String(log.readAll().get(0), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("SECRET-TEXT").doesNotContain("boom");
        JsonNode node = lines().get(0);
        assertThat(node.get("status").asText()).isEqualTo("FAILED");
        assertThat(node.get("gate").get("status").asText()).isEqualTo("RED");
        assertThat(node.get("gate").get("category").asText()).isEqualTo("build");
        assertThat(text(node.get("gate_description"))).isEqualTo("SECRET-TEXT");
        assertThat(text(node.get("gate_summary"))).contains("SECRET-TEXT");
        assertThat(text(node.get("error"))).isEqualTo("boom");
    }

    @Test
    void runFinished_withoutGate_hasNullGateAndError() throws Exception {
        recorder.emit(new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        JsonNode node = lines().get(0);
        assertThat(node.get("gate").isNull()).isTrue();
        assertThat(node.get("gate_description").isNull()).isTrue();
        assertThat(node.get("gate_summary").isNull()).isTrue();
        assertThat(node.get("error").isNull()).isTrue();
    }

    @Test
    void runStarted_recordsTheStart() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", "abc", true,
                "{\"id\":\"wf\"}".getBytes(StandardCharsets.UTF_8), Map.of("who", "SECRET-IN"),
                TriggerSource.MANUAL, "alex", "0.1.0", "container", new RunStart.Runtime("podman", "5.4.1"), "/ws"));

        JsonNode node = lines().get(0);
        assertThat(node.get("type").asText()).isEqualTo("run.started");
        assertThat(node.get("workflow").get("id").asText()).isEqualTo("wf");
        assertThat(node.get("workflow").get("name").asText()).isEqualTo("Wf");
        assertThat(node.get("package").get("dir").asText()).isEqualTo("/pkg");
        assertThat(node.get("package").get("digest").asText()).isEqualTo("digest");
        assertThat(node.get("package").get("git_commit").asText()).isEqualTo("abc");
        assertThat(node.get("package").get("git_dirty").asBoolean()).isTrue();
        assertThat(text(node.get("definition"))).isEqualTo("{\"id\":\"wf\"}");
        assertThat(text(node.get("inputs"))).contains("SECRET-IN");
        assertThat(node.get("trigger").asText()).isEqualTo("MANUAL");
        assertThat(node.get("actor").asText()).isEqualTo("alex");
        assertThat(node.get("version").asText()).isEqualTo("0.1.0");
        assertThat(node.get("execution").asText()).isEqualTo("container");
        assertThat(node.get("container_runtime").get("name").asText()).isEqualTo("podman");
        assertThat(node.get("container_runtime").get("version").asText()).isEqualTo("5.4.1");
        assertThat(node.get("output_limit_mib").asInt()).as("the default").isEqualTo(16);
        assertThat(node.get("workspace").asText()).isEqualTo("/ws");
        assertThat(node.has("workspace_source")).as("only with --source").isFalse();
        assertThat(node.has("workspace_source_sha")).isFalse();
        assertThat(new String(log.readAll().get(0), StandardCharsets.UTF_8)).doesNotContain("SECRET-IN");
    }

    /** The output limit the operator set, after the container runtime: the facts of where the commands run. */
    @Test
    void runStarted_recordsTheOutputLimit() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 4, "/ws", null, null));

        JsonNode node = lines().get(0);
        assertThat(node.get("output_limit_mib").isInt()).isTrue();
        assertThat(node.get("output_limit_mib").asInt()).isEqualTo(4);
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsSubsequence("container_runtime", "output_limit_mib", "workspace");
    }

    /** The output budget in force, right after the output limit: the number, also when it is the default. */
    @Test
    void runStarted_recordsTheOutputBudget_afterTheOutputLimit() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 4, Map.of(), "/ws", null, null, 40));
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 64, "/ws", null, null));

        JsonNode node = lines().get(0);
        assertThat(node.get("run_output_budget_mib").isInt()).isTrue();
        assertThat(node.get("run_output_budget_mib").asInt()).isEqualTo(40);
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsSubsequence("output_limit_mib", "run_output_budget_mib", "ai_images", "workspace");
        assertThat(lines().get(1).get("run_output_budget_mib").asInt()).as("the default for 64 MiB").isEqualTo(512);
    }

    /** The operator's replacements of the AI images, after the output limit; an empty object without any. */
    @Test
    void runStarted_recordsTheAiImages_emptyWithoutAny() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 16, Map.of("pi", "pi:2", "claude", "claude:7"), "/ws", null, null));
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 4, "/ws", null, null));

        JsonNode node = lines().get(0);
        assertThat(node.get("ai_images").get("claude").asText()).isEqualTo("claude:7");
        assertThat(node.get("ai_images").get("pi").asText()).isEqualTo("pi:2");
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsSubsequence("output_limit_mib", "ai_images", "workspace");
        assertThat(lines().get(1).get("ai_images").isObject()).isTrue();
        assertThat(lines().get(1).get("ai_images").size()).isZero();
    }

    /** The line is hashed into the chain, so the same start writes the same bytes: the images sorted by CLI. */
    @Test
    void runStarted_recordsTheAiImagesSortedByCli() throws Exception {
        Map<String, String> aiImages = new java.util.LinkedHashMap<>();
        for (String cli : List.of("zeta", "pi", "mid", "claude", "yak", "beta", "xi", "gamma", "nu", "delta")) {
            aiImages.put(cli, cli + ":1");
        }
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 16, aiImages, "/ws", null, null));

        List<String> written = new java.util.ArrayList<>();
        lines().get(0).get("ai_images").fieldNames().forEachRemaining(written::add);
        assertThat(written).containsExactly("beta", "claude", "delta", "gamma", "mid", "nu", "pi", "xi", "yak",
                "zeta");
    }

    /** Where the AI containers can connect, after the AI images: the mode and the operator's hosts by CLI. */
    @Test
    void runStarted_recordsTheAiNetworkAndTheOperatorsHosts() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 16, Map.of(), "/ws", null, null, 128, null, List.of(), new AiNetwork(false,
                Map.of("pi", List.of("llm.corp.example:443"), "claude", List.of("b.example:443", "a.example:443")))));
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 16, Map.of(), "/ws", null, null, 128, null, List.of(), new AiNetwork(true, Map.of())));
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, 4, "/ws", null, null));

        JsonNode node = lines().get(0);
        assertThat(node.get("ai_network").asText()).isEqualTo("restricted");
        assertThat(node.get("ai_hosts")).isEqualTo(JSON.readTree(
                "{\"claude\":[\"a.example:443\",\"b.example:443\"],\"pi\":[\"llm.corp.example:443\"]}"));
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsSubsequence("ai_images", "ai_network", "ai_hosts", "images", "workspace");
        assertThat(lines().get(1).get("ai_network").asText()).isEqualTo("open");
        assertThat(lines().get(1).get("ai_hosts").isObject()).isTrue();
        assertThat(lines().get(1).get("ai_hosts")).isEmpty();
        assertThat(lines().get(2).get("ai_network").asText()).as("the default").isEqualTo("restricted");
    }

    /** The source of a run started with {@code --source} and the commit its checkout is at; nothing without one. */
    @Test
    void runStarted_withASource_recordsItAndItsSha() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "container",
                null, "/home/workspaces/run-1",
                "https://host/repo", "4b825dc642cb6eb9a060e54bf8d69288fbee4904"));

        JsonNode node = lines().get(0);
        assertThat(node.get("workspace").asText()).isEqualTo("/home/workspaces/run-1");
        assertThat(node.get("workspace_source").asText()).isEqualTo("https://host/repo");
        assertThat(node.get("workspace_source_sha").asText()).isEqualTo("4b825dc642cb6eb9a060e54bf8d69288fbee4904");
    }

    @Test
    void runStarted_ofAHostRunWithoutARuntime_recordsANullRuntime() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest", null, null, "{}".getBytes(StandardCharsets.UTF_8),
                Map.of(), TriggerSource.MANUAL, "alex", "0.1.0", "host", null, "/ws"));

        JsonNode node = lines().get(0);
        assertThat(node.get("execution").asText()).isEqualTo("host");
        assertThat(node.has("container_runtime")).isTrue();
        assertThat(node.get("container_runtime").isNull()).isTrue();
    }

    @Test
    void theRemainingEvents_mapToTheirFields() throws Exception {
        recorder.emit(new StepWaiting(1, true));
        recorder.emit(new ApprovalRequested(1, "blk", "approval", "Q?", List.of("Yes", "No"), List.of("No"),
                "text", "dflt", true, "\\d+", "var", List.of(Map.of("name", "f")), true, true));
        recorder.emit(new ForEachItemStarted(1, 0, "item-0", 3));
        recorder.emit(new ForEachItemFinished(1, 0, "item-0", 3, StepStatus.COMPLETED, "out", "sub-1"));
        recorder.emit(new AgenticProgress(1, "tool_call", Map.of("tool", "x")));
        recorder.emit(new RunStatusChanged(RunStatus.PAUSED));
        recorder.emit(new RunParked(1, "approval"));

        List<JsonNode> n = lines();
        assertThat(n.get(0).get("waiting").asBoolean()).isTrue();
        JsonNode ask = n.get(1);
        assertThat(ask.get("type").asText()).isEqualTo("approval.requested");
        assertThat(ask.get("kind").asText()).isEqualTo("approval");
        assertThat(ask.get("block_id").asText()).isEqualTo("blk");
        assertThat(text(ask.get("question"))).isEqualTo("Q?");
        assertThat(JSON.convertValue(ask.get("options"), List.class)).containsExactly("Yes", "No");
        assertThat(JSON.convertValue(ask.get("reject_options"), List.class)).containsExactly("No");
        assertThat(ask.get("input_type").asText()).isEqualTo("text");
        assertThat(text(ask.get("default"))).isEqualTo("dflt");
        assertThat(ask.get("required").asBoolean()).isTrue();
        assertThat(ask.get("pattern").asText()).isEqualTo("\\d+");
        assertThat(ask.get("variable").asText()).isEqualTo("var");
        assertThat(ask.get("fields").get(0).get("name").asText()).isEqualTo("f");
        assertThat(ask.get("parks").asBoolean()).isTrue();
        assertThat(ask.get("preset").asBoolean()).isTrue();
        List<String> names = new java.util.ArrayList<>();
        ask.fieldNames().forEachRemaining(names::add);
        assertThat(names).endsWith("fields", "parks", "preset");
        assertThat(n.get(2).get("type").asText()).isEqualTo("foreach.item.started");
        assertThat(n.get(2).get("item_index").asInt()).isZero();
        assertThat(n.get(2).get("total").asInt()).isEqualTo(3);
        assertThat(text(n.get(2).get("item"))).isEqualTo("item-0");
        assertThat(n.get(3).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(text(n.get(3).get("output"))).isEqualTo("out");
        assertThat(n.get(3).get("sub_run").asText()).isEqualTo("sub-1");
        assertThat(n.get(4).get("phase").asText()).isEqualTo("tool_call");
        assertThat(text(n.get(4).get("data"))).contains("tool");
        assertThat(n.get(5).get("status").asText()).isEqualTo("PAUSED");
        assertThat(n.get(6).get("type").asText()).isEqualTo("run.parked");
        assertThat(n.get(6).get("kind").asText()).isEqualTo("approval");
        assertThat(n.get(6).get("step").asInt()).isEqualTo(1);
    }

    @Test
    void record_writesUseCaseEvents_withBytesAsBlobs() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor", "alex");
        fields.put("from_checkpoint", 4);
        fields.put("discarded", "TORN".getBytes(StandardCharsets.UTF_8));
        fields.put("answer", null);
        recorder.record("run.recovered", fields);

        JsonNode node = lines().get(0);
        assertThat(node.get("type").asText()).isEqualTo("run.recovered");
        assertThat(node.get("run").asText()).isEqualTo(RUN);
        assertThat(node.get("actor").asText()).isEqualTo("alex");
        assertThat(node.get("from_checkpoint").asInt()).isEqualTo(4);
        assertThat(text(node.get("discarded"))).isEqualTo("TORN");
        assertThat(node.get("answer").isNull()).isTrue();
    }

    @Test
    void answerAttribution_appliesToTheNextAnswerOnly() throws Exception {
        recorder.attributeNextAnswer("alex", "cli", "req-1", "looks fine");
        recorder.emit(new ApprovalAnswered(1, "approval", "Yes"));
        recorder.emit(new ApprovalAnswered(1, "approval", "No"));

        List<JsonNode> n = lines();
        assertThat(n.get(0).get("type").asText()).isEqualTo("approval.answered");
        assertThat(n.get(0).get("kind").asText()).isEqualTo("approval");
        assertThat(text(n.get(0).get("answer"))).isEqualTo("Yes");
        assertThat(n.get(0).get("actor").asText()).isEqualTo("alex");
        assertThat(n.get(0).get("source").asText()).isEqualTo("cli");
        assertThat(n.get(0).get("request").asText()).isEqualTo("req-1");
        assertThat(text(n.get(0).get("comment"))).isEqualTo("looks fine");
        assertThat(n.get(1).get("actor").isNull()).isTrue();
        assertThat(n.get(1).get("source").isNull()).isTrue();
        assertThat(n.get(1).get("request").isNull()).isTrue();
        assertThat(n.get(1).get("comment").isNull()).isTrue();
    }

    /** The answer names the agent beside the actor only when one was given: no agent is no field, not a null. */
    @Test
    void answerAttribution_namesTheAgent_onlyWhenOneWasGiven() throws Exception {
        recorder.attributeNextAnswer("alex", "cli", "req-1", null, "claude-code");
        recorder.emit(new ApprovalAnswered(1, "approval", "Yes"));
        recorder.attributeNextAnswer("alex", "cli", "req-2", null, null);
        recorder.emit(new ApprovalAnswered(2, "approval", "Yes"));
        recorder.emit(new ApprovalAnswered(3, "approval", "Yes"));

        List<JsonNode> n = lines();
        assertThat(n.get(0).get("actor").asText()).isEqualTo("alex");
        assertThat(n.get(0).get("agent").asText()).isEqualTo("claude-code");
        assertThat(n.get(1).get("actor").asText()).isEqualTo("alex");
        assertThat(n.get(1).has("agent")).as("answered by the actor alone").isFalse();
        assertThat(n.get(2).has("agent")).as("a default nobody gave").isFalse();
    }

    @Test
    void clearAnswerAttribution_leavesTheNextAnswerUnattributed() throws Exception {
        recorder.attributeNextAnswer("alex", "inbox", "req-1", null);
        recorder.clearAnswerAttribution();
        recorder.emit(new ApprovalAnswered(1, "approval", "Yes"));

        JsonNode answered = lines().get(0);
        assertThat(answered.get("actor").isNull()).isTrue();
        assertThat(answered.get("source").isNull()).isTrue();
        assertThat(answered.get("request").isNull()).isTrue();
    }

    @Test
    void afterTheRunFinished_nothingIsAppended() throws Exception {
        recorder.emit(new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        boolean recorded = recorder.record("request.refused", Map.of("reason", "too late"));

        assertThat(recorded).isFalse();
        assertThat(lines()).extracting(n -> n.get("type").asText()).containsExactly("run.finished");
        assertThat(catchThrowable(() -> recorder.emit(new StepWaiting(0, true))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has ended");
    }

    /** A request after the record was stopped (the process is going down) is not recorded, and nothing throws. */
    @Test
    void afterTheRecordWasStopped_aRequestIsNotRecorded() throws Exception {
        int before = lines().size();
        recorder.stop();

        assertThat(recorder.record("run.cancel_requested", Map.of("actor", "alex"))).isFalse();
        assertThat(lines()).hasSize(before);
    }

    @Test
    void afterTheRunParked_nothingIsAppended_butAChildsEndEndsNothing() throws Exception {
        recorder.emit(new ChildRunEvent("child-1", new RunFinished(RunStatus.COMPLETED, Map.of(), null)));
        assertThat(recorder.record("run.cancel_requested", Map.of("actor", "alex"))).isTrue();

        recorder.emit(new RunParked(1, "approval"));

        assertThat(recorder.record("run.cancel_requested", Map.of("actor", "alex"))).isFalse();
        assertThat(lines()).extracting(n -> n.get("type").asText())
                .containsExactly("run.finished", "run.cancel_requested", "run.parked");
    }

    @Test
    void continueAfter_appendsWithTheNextSeqAndPrev() throws Exception {
        recorder.emit(new StepWaiting(0, true));
        recorder.emit(new StepWaiting(0, false));
        List<RecordedEvent> existing = log.readAll().stream().map(RecordedEvent::parse).toList();

        RunRecorder again = RunRecorder.continueAfter(log, blobs, clock, RUN, definition(), existing);
        assertThat(again.nextSeq()).isEqualTo(2);
        assertThat(again.head()).isEqualTo(EventChain.sha256Hex(log.readAll().get(1)));
        again.emit(new RunStatusChanged(RunStatus.RUNNING));

        JsonNode last = lines().get(2);
        assertThat(last.get("seq").asInt()).isEqualTo(2);
        assertThat(last.get("prev").asText()).isEqualTo(EventChain.sha256Hex(log.readAll().get(1)));
    }

    @Test
    void recordedEvent_parsesALine() {
        recorder.emit(new ChildRunEvent("c", new OutputRecorded(5, "n", "o")));

        RecordedEvent event = RecordedEvent.parse(log.readAll().get(0));

        assertThat(event.seq()).isZero();
        assertThat(event.at()).isEqualTo(Instant.parse("2026-11-03T12:00:00.123Z"));
        assertThat(event.type()).isEqualTo("step.output");
        assertThat(event.run()).isEqualTo("c");
        assertThat(event.parent()).isEqualTo(RUN);
        assertThat(event.prev()).isEqualTo(EventChain.genesis(RUN));
        assertThat(event.step()).isEqualTo(5);
        assertThat(event.blob("output").size()).isEqualTo(1);
        assertThat(event.blob("output").sha256()).isEqualTo(EventChain.sha256Hex("o".getBytes(StandardCharsets.UTF_8)));
        assertThat(event.fields().has("seq")).isFalse();
        assertThat(event.raw()).isEqualTo(log.readAll().get(0));
    }

    @Test
    void concurrentWrites_keepTheChainIntact() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        Runnable writer = () -> {
            try {
                go.await();
                for (int i = 0; i < 500; i++) {
                    recorder.emit(new StepWaiting(i, true));
                }
            } catch (Throwable t) {
                failures.incrementAndGet();
            }
        };
        Thread a = new Thread(writer);
        Thread b = new Thread(writer);
        a.start();
        b.start();
        go.countDown();
        a.join();
        b.join();

        assertThat(failures.get()).isZero();
        List<byte[]> raw = log.readAll();
        assertThat(raw).hasSize(1000);
        String prev = EventChain.genesis(RUN);
        for (int i = 0; i < raw.size(); i++) {
            JsonNode node = JSON.readTree(raw.get(i));
            assertThat(node.get("seq").asInt()).isEqualTo(i);
            assertThat(node.get("prev").asText()).isEqualTo(prev);
            prev = EventChain.sha256Hex(raw.get(i));
        }
    }

    @Test
    void onAppend_seesEveryEventInOrder() {
        List<RecordedEvent> seen = new ArrayList<>();
        recorder.onAppend(seen::add);

        recorder.emit(new StepWaiting(0, true));
        recorder.record("run.cancel_requested", Map.of("actor", "alex"));
        recorder.emit(new RunStatusChanged(RunStatus.CANCELLED));

        assertThat(seen).extracting(RecordedEvent::seq).containsExactly(0, 1, 2);
        assertThat(seen).extracting(RecordedEvent::type)
                .containsExactly("step.waiting", "run.cancel_requested", "run.status");
    }

    /** A listener that throws neither stops the record nor the listeners after it, nor the step bookkeeping. */
    @Test
    void aThrowingListener_breaksNothing() throws Exception {
        List<RecordedEvent> seen = new ArrayList<>();
        recorder.onAppend(event -> {
            throw new IllegalStateException("listener broke");
        });
        recorder.onAppend(seen::add);
        CommandRunner commands = recorder.recording(new FakeCommandRunner().on("echo", "hi"));

        recorder.emit(new StepStarted(0, "build", "shell", 0, false));
        commands.run(new CommandRequest("echo hi", "/ws", 10, Map.of(), null, List.of(), false));

        assertThat(seen).extracting(RecordedEvent::type)
                .containsExactly("step.started", "step.command", "step.command.finished");
        assertThat(seen.get(1).step()).as("the step was tracked although a listener threw").isEqualTo(0);
        assertThat(lines()).hasSize(3);
    }

    // ---- step.files ----

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void finishStep(int index) {
        recorder.emit(new StepStarted(index, "build", "shell", 0, false));
        recorder.emit(new StepFinished(index, "build", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
    }

    private List<JsonNode> filesEvents() throws Exception {
        return lines().stream().filter(l -> "step.files".equals(l.get("type").asText())).toList();
    }

    @Test
    void stepThatWritesFiles_recordsStepFiles_withBlobsForNewAndChangedFiles() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        recorder.withScratch(scratch);

        scratch.put("out/a.txt", bytes("one"));
        finishStep(0);
        List<JsonNode> first = filesEvents();
        assertThat(first).hasSize(1);
        JsonNode a = first.get(0).get("files").get(0);
        assertThat(first.get(0).get("step").asInt()).isEqualTo(0);
        assertThat(a.get("path").asText()).isEqualTo("out/a.txt");
        assertThat(a.get("size").asInt()).isEqualTo(3);
        assertThat(text(a.get("blob"))).isEqualTo("one");
        assertThat(a.get("blob").get("sha256").asText()).isEqualTo(a.get("sha256").asText());
        assertThat(first.get(0).get("truncated").asBoolean()).isFalse();

        finishStep(1);
        assertThat(filesEvents()).as("an unchanged directory records nothing").hasSize(1);

        scratch.put("out/a.txt", bytes("two"));
        scratch.put("b.txt", bytes("b"));
        finishStep(2);
        List<JsonNode> second = filesEvents();
        assertThat(second).hasSize(2);
        assertThat(second.get(1).get("files")).extracting(f -> f.get("path").asText())
                .containsExactly("b.txt", "out/a.txt");
        assertThat(text(second.get(1).get("files").get(1).get("blob"))).isEqualTo("two");

        scratch.remove("b.txt");
        finishStep(3);
        JsonNode third = filesEvents().get(2);
        assertThat(third.get("removed")).extracting(JsonNode::asText).containsExactly("b.txt");
        assertThat(third.get("files")).hasSize(1);
    }

    @Test
    void withoutAScratchDirectory_nothingIsListed() throws Exception {
        finishStep(0);
        assertThat(filesEvents()).isEmpty();
    }

    @Test
    void symlinkInScratch_isListedWithItsTarget_andNeverFollowed() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.putLink("x", "../events.jsonl");
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode event = filesEvents().get(0);
        assertThat(event.get("files")).isEmpty();
        assertThat(event.get("links").get(0).get("path").asText()).isEqualTo("x");
        assertThat(event.get("links").get(0).get("target").asText()).isEqualTo("../events.jsonl");
        assertThat(scratch.listScratch()).singleElement().satisfies(e -> assertThat(e.isLink()).isTrue());
    }

    @Test
    void fileOver16MiB_isListedWithoutBlob_reasonTooLarge() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("big.bin", new byte[16 * 1024 * 1024 + 1]);
        scratch.put("limit.bin", new byte[16 * 1024 * 1024]);
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode files = filesEvents().get(0).get("files");
        assertThat(files.get(0).get("path").asText()).isEqualTo("big.bin");
        assertThat(files.get(0).get("blob").isNull()).isTrue();
        assertThat(files.get(0).get("reason").asText()).isEqualTo("too large");
        assertThat(files.get(0).get("size").asLong()).isEqualTo(16L * 1024 * 1024 + 1);
        assertThat(files.get(1).get("blob").isObject()).as("exactly 16 MiB is stored").isTrue();
    }

    @Test
    void moreThan10000Entries_isTruncated() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        for (int i = 0; i < 10_050; i++) {
            scratch.put(String.format("f%05d", i), new byte[0]);
        }
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode event = filesEvents().get(0);
        assertThat(event.get("files")).hasSize(10_000);
        assertThat(event.get("truncated").asBoolean()).isTrue();
        assertThat(event.get("files").get(9_999).get("path").asText()).isEqualTo("f09999");
    }

    @Test
    void aFileThatContainsASecret_isListedWithoutBlob() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("key", bytes("hunter22"));
        scratch.put("ok", bytes("fine"));
        recorder.redacting(new Redactor(Map.of("PASSWORD", "hunter22"))).withScratch(scratch);

        finishStep(0);

        JsonNode files = filesEvents().get(0).get("files");
        assertThat(files.get(0).get("blob").isNull()).isTrue();
        assertThat(files.get(0).get("reason").asText()).isEqualTo("contains a secret");
        assertThat(files.get(1).get("blob").isObject()).isTrue();
    }

    @Test
    void aRecorderThatGoesOn_knowsTheLastListing() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("a", bytes("one"));
        recorder.withScratch(scratch);
        finishStep(0);
        List<RecordedEvent> existing = lines().stream().map(l -> RecordedEvent.parse(Blobs.json(l))).toList();

        RunRecorder again = RunRecorder.continueAfter(log, blobs, clock, RUN, definition(), existing)
                .withScratch(scratch);
        again.emit(new StepStarted(1, "build", "shell", 0, false));
        again.emit(new StepFinished(1, "build", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        assertThat(filesEvents()).hasSize(1);
    }

    /**
     * A recorder that goes on takes over the links and the unreadable entries of the last listing too: an unchanged
     * directory records nothing, and the next change is recorded against that listing.
     */
    @Test
    void aRecorderThatGoesOn_knowsTheLinksAndUnreadableEntriesOfTheLastListing() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("a", bytes("one"));
        scratch.putLink("l", "a");
        scratch.putUnreadable("d", "permission denied");
        recorder.withScratch(scratch);
        finishStep(0);
        List<RecordedEvent> existing = lines().stream().map(l -> RecordedEvent.parse(Blobs.json(l))).toList();
        RunRecorder again = RunRecorder.continueAfter(log, blobs, clock, RUN, definition(), existing)
                .withScratch(scratch);

        again.emit(new StepStarted(1, "build", "shell", 0, false));
        again.emit(new StepFinished(1, "build", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
        assertThat(filesEvents()).as("nothing changed since the last listing").hasSize(1);

        scratch.put("b", bytes("two"));
        again.emit(new StepStarted(2, "build", "shell", 0, false));
        again.emit(new StepFinished(2, "build", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
        JsonNode second = filesEvents().get(1);
        assertThat(second.get("files")).extracting(f -> f.get("path").asText()).containsExactly("a", "b");
        assertThat(second.get("links")).extracting(l -> l.get("path").asText()).containsExactly("l");
        assertThat(second.get("unreadable")).extracting(u -> u.get("path").asText()).containsExactly("d");
        assertThat(second.get("removed")).isEmpty();
    }

    @Test
    void exactly10000Entries_isNoCutListing() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        for (int i = 0; i < 10_000; i++) {
            scratch.put(String.format("f%05d", i), new byte[0]);
        }
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode event = filesEvents().get(0);
        assertThat(event.get("files")).hasSize(10_000);
        assertThat(event.get("truncated").asBoolean()).isFalse();
    }

    /**
     * Only what lies below an unreadable directory is not known to be gone; a path beside it, even one whose name
     * starts like it, that is no longer listed is removed. The unreadable root covers everything.
     */
    @Test
    void anUnreadableDirectory_coversWhatLiesBelowIt_andNothingBesideIt() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("d/f", bytes("x"));
        scratch.put("dx", bytes("y"));
        scratch.put("e", bytes("z"));
        recorder.withScratch(scratch);
        finishStep(0);
        scratch.remove("d/f");
        scratch.remove("dx");
        scratch.remove("e");
        scratch.putUnreadable("d", "permission denied");

        finishStep(1);
        assertThat(filesEvents().get(1).get("removed")).extracting(JsonNode::asText).containsExactly("dx", "e");

        scratch.put("g", bytes("w"));
        finishStep(2);
        scratch.remove("g");
        scratch.remove("d");
        scratch.putUnreadable("", "permission denied");
        finishStep(3);
        assertThat(filesEvents().get(3).get("removed")).as("the root covers everything").isEmpty();
    }

    // ---- step.files, fix round 1 ----

    private static String sha(String text) {
        return EventChain.sha256Hex(bytes(text));
    }

    /** A scratch directory whose listing and reads a test scripts, to model a file that changes meanwhile. */
    private static final class ScriptedScratch implements traceworks.core.port.ScratchDirectory {
        traceworks.core.port.ScratchListing listing;
        byte[] content = new byte[0];
        long lastMax = -1;
        /** Whether a read fails, as it does for a file that changed or went away after the listing. */
        boolean readFails;

        @Override
        public traceworks.core.port.ScratchListing list(
                traceworks.core.port.ScratchListing.Known known) {
            return listing;
        }

        @Override
        public byte[] readScratch(String path, long maxBytes) {
            if (readFails) throw new IllegalStateException("changed while it was read");
            lastMax = maxBytes;
            return java.util.Arrays.copyOf(content, (int) Math.min(content.length, maxBytes));
        }
    }

    @Test
    void unreadableEntries_areRecorded_inTheFieldOrder_andAreNotReportedRemoved() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("d/f", bytes("x"));
        recorder.withScratch(scratch);
        finishStep(0);
        scratch.remove("d/f");
        scratch.putUnreadable("d/f", "permission denied");

        finishStep(1);

        JsonNode event = filesEvents().get(1);
        assertThat(event.get("unreadable")).hasSize(1);
        assertThat(event.get("unreadable").get(0).get("path").asText()).isEqualTo("d/f");
        assertThat(event.get("unreadable").get(0).get("reason").asText()).isEqualTo("permission denied");
        assertThat(event.get("removed")).isEmpty();
        assertThat(event.get("files")).isEmpty();
        assertThat(java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(event.fieldNames(), 0), false).toList())
                .containsSubsequence("step", "files", "links", "unreadable", "removed", "truncated");
    }

    /** A file that cannot be read when the step is recorded is listed as unreadable, not as a file without content. */
    @Test
    void aFileThatCannotBeReadWhenRecorded_isUnreadable_notAFile() throws Exception {
        ScriptedScratch scratch = new ScriptedScratch();
        scratch.listing = new traceworks.core.port.ScratchListing(List.of(
                new traceworks.core.port.ScratchEntry("gone", 3, null, null, 5, null)), List.of(),
                false);
        scratch.readFails = true;
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode event = filesEvents().get(0);
        assertThat(event.get("files")).isEmpty();
        assertThat(event.get("unreadable")).singleElement().satisfies(u -> {
            assertThat(u.get("path").asText()).isEqualTo("gone");
            assertThat(u.get("reason").asText()).isEqualTo("unreadable when recorded");
        });
    }

    /** The unreadable entries are in path order, a file that could not be read when recorded among them. */
    @Test
    void unreadableEntries_areInPathOrder_withTheFilesThatCouldNotBeRead() throws Exception {
        ScriptedScratch scratch = new ScriptedScratch();
        scratch.listing = new traceworks.core.port.ScratchListing(List.of(
                new traceworks.core.port.ScratchEntry("a", 3, null, null, 5, null)),
                List.of(new traceworks.core.port.ScratchListing.Unreadable("z", "permission denied")), false);
        scratch.readFails = true;
        recorder.withScratch(scratch);

        finishStep(0);

        assertThat(filesEvents().get(0).get("unreadable")).extracting(u -> u.get("path").asText())
                .containsExactly("a", "z");
    }

    @Test
    void fileOver1GiB_isListedWithoutHash_reasonTooLargeToHash() throws Exception {
        ScriptedScratch scratch = new ScriptedScratch();
        scratch.listing = new traceworks.core.port.ScratchListing(List.of(
                new traceworks.core.port.ScratchEntry("huge", 2L << 30, null, null, 5,
                        "too large to hash")), List.of(), false);
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode file = filesEvents().get(0).get("files").get(0);
        assertThat(file.get("size").asLong()).isEqualTo(2L << 30);
        assertThat(file.get("sha256").isNull()).isTrue();
        assertThat(file.get("blob").isNull()).isTrue();
        assertThat(file.get("reason").asText()).isEqualTo("too large to hash");
    }

    @Test
    void aFileWithTheSameSizeAndTime_isNotReadAgain() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("a", bytes("one"));
        recorder.withScratch(scratch);
        finishStep(0);
        assertThat(scratch.reads()).isEqualTo(1);

        scratch.put("b", bytes("two"));
        finishStep(1);

        assertThat(scratch.reads()).as("only b was read").isEqualTo(2);
        JsonNode files = filesEvents().get(1).get("files");
        assertThat(files.get(0).get("blob").get("sha256").asText()).isEqualTo(sha("one"));
        assertThat(files.get(0).get("sha256").asText()).isEqualTo(sha("one"));
    }

    @Test
    void aTruncatedListing_reportsNoRemoved() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        for (int i = 0; i < 10_050; i++) {
            scratch.put(String.format("f%05d", i), new byte[0]);
        }
        recorder.withScratch(scratch);
        finishStep(0);
        scratch.remove("f00000");

        finishStep(1);

        JsonNode second = filesEvents().get(1);
        assertThat(second.get("truncated").asBoolean()).isTrue();
        assertThat(second.get("removed")).isEmpty();
    }

    @Test
    void sizeHashAndBlob_comeFromTheSameRead_whenTheFileChangedAfterTheListing() throws Exception {
        ScriptedScratch scratch = new ScriptedScratch();
        scratch.listing = new traceworks.core.port.ScratchListing(List.of(
                new traceworks.core.port.ScratchEntry("a", 10, sha("listed-ten"), null, 5, null)),
                List.of(), false);
        scratch.content = bytes("grown to something much longer");
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode file = filesEvents().get(0).get("files").get(0);
        assertThat(scratch.lastMax).as("the read stops at the size the listing saw").isEqualTo(10);
        assertThat(text(file.get("blob"))).isEqualTo("grown to s");
        assertThat(file.get("size").asLong()).isEqualTo(10);
        assertThat(file.get("sha256").asText()).isEqualTo(sha("grown to s"))
                .isEqualTo(file.get("blob").get("sha256").asText());
    }

    @Test
    void aFileThatShrinksAfterTheListing_isRecordedWithWhatWasRead() throws Exception {
        ScriptedScratch scratch = new ScriptedScratch();
        scratch.listing = new traceworks.core.port.ScratchListing(List.of(
                new traceworks.core.port.ScratchEntry("a", 10, sha("listed-ten"), null, 5, null)),
                List.of(), false);
        scratch.content = bytes("abc");
        recorder.withScratch(scratch);

        finishStep(0);

        JsonNode file = filesEvents().get(0).get("files").get(0);
        assertThat(file.get("size").asLong()).isEqualTo(3);
        assertThat(file.get("sha256").asText()).isEqualTo(sha("abc"));
    }

    /**
     * A scratch directory with files whose modification time a test sets. Like a real one, its listing hashes a
     * file only when the recorder does not know a hash for its size and modification time.
     */
    private static final class TimedScratch implements traceworks.core.port.ScratchDirectory {
        private final java.util.TreeMap<String, byte[]> files = new java.util.TreeMap<>();
        private final java.util.TreeMap<String, Long> modified = new java.util.TreeMap<>();
        /** How many times a listing hashed a file because the recorder knew no hash for it. */
        int hashed;
        /** How many times a file was read for its blob. */
        int reads;

        void put(String path, String content, long modifiedAt) {
            files.put(path, bytes(content));
            modified.put(path, modifiedAt);
        }

        @Override
        public traceworks.core.port.ScratchListing list(traceworks.core.port.ScratchListing.Known known) {
            List<traceworks.core.port.ScratchEntry> entries = new java.util.ArrayList<>();
            files.forEach((path, content) -> {
                String sha = known.sha256(path, content.length, modified.get(path));
                if (sha == null) {
                    hashed++;
                    sha = EventChain.sha256Hex(content);
                }
                entries.add(new traceworks.core.port.ScratchEntry(path, content.length, sha, null,
                        modified.get(path), null));
            });
            return new traceworks.core.port.ScratchListing(entries, List.of(), false);
        }

        @Override
        public byte[] readScratch(String path, long maxBytes) {
            reads++;
            byte[] content = files.get(path);
            return java.util.Arrays.copyOf(content, (int) Math.min(content.length, maxBytes));
        }
    }

    /** Size and modification time as the last listing saw them: the file keeps its hash and is not read again. */
    @Test
    void anUntouchedFile_keepsItsHash_withoutBeingHashedOrReadAgain() throws Exception {
        TimedScratch scratch = new TimedScratch();
        scratch.put("a", "one", 5);
        scratch.put("b", "other", 7);
        recorder.withScratch(scratch);
        finishStep(0);
        assertThat(scratch.hashed).isEqualTo(2);
        assertThat(scratch.reads).isEqualTo(2);

        finishStep(1);

        assertThat(scratch.hashed).as("no file is hashed again").isEqualTo(2);
        assertThat(scratch.reads).as("no file is read again").isEqualTo(2);
        assertThat(filesEvents()).as("an unchanged directory records nothing").hasSize(1);
    }

    /** The same size says nothing of the content: another modification time has the file hashed and recorded anew. */
    @Test
    void aFileRewrittenWithTheSameSize_isHashedAgain_andRecordedWithItsNewHash() throws Exception {
        TimedScratch scratch = new TimedScratch();
        scratch.put("a", "one", 5);
        recorder.withScratch(scratch);
        finishStep(0);

        scratch.put("a", "two", 6);
        finishStep(1);

        assertThat(scratch.hashed).isEqualTo(2);
        assertThat(filesEvents()).hasSize(2);
        JsonNode file = filesEvents().get(1).get("files").get(0);
        assertThat(file.get("sha256").asText()).isEqualTo(sha("two")).isNotEqualTo(sha("one"));
        assertThat(text(file.get("blob"))).isEqualTo("two");
    }

    /** A step can set a file's time back: another size alone has the file hashed and recorded anew. */
    @Test
    void aFileRewrittenWithAnotherSizeAndTheSameTime_isHashedAgain_andRecordedWithItsNewHash() throws Exception {
        TimedScratch scratch = new TimedScratch();
        scratch.put("a", "one", 5);
        recorder.withScratch(scratch);
        finishStep(0);

        scratch.put("a", "three", 5);
        finishStep(1);

        assertThat(scratch.hashed).as("the earlier hash is not handed out for another size").isEqualTo(2);
        assertThat(filesEvents()).hasSize(2);
        JsonNode file = filesEvents().get(1).get("files").get(0);
        assertThat(file.get("size").asLong()).isEqualTo(5);
        assertThat(file.get("sha256").asText()).isEqualTo(sha("three"));
        assertThat(text(file.get("blob"))).isEqualTo("three");
    }

    @Test
    void stepFinishedInAChildRun_recordsStepFilesForTheChildRun() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("c.txt", bytes("child"));
        recorder.withScratch(scratch);
        String child = "00000000-0000-0000-0000-0000000000c1";

        recorder.emit(new ChildRunEvent(child, new StepStarted(0, "build", "shell", 0, false)));
        recorder.emit(new ChildRunEvent(child, new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "i", 5,
                Map.of())));

        JsonNode event = filesEvents().get(0);
        assertThat(event.get("run").asText()).isEqualTo(child);
        assertThat(event.get("parent").asText()).isEqualTo(RUN);
        assertThat(event.get("step").asInt()).isEqualTo(0);
        assertThat(event.get("files").get(0).get("path").asText()).isEqualTo("c.txt");
    }
}
