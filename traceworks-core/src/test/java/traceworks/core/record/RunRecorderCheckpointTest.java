package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.engine.RunOptions;
import traceworks.core.engine.WorkflowEngine;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BlobRef;
import traceworks.core.port.BlobStore;
import traceworks.core.port.Clock;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A checkpoint stores each text of the run once: a text whose very string the checkpoint before stored keeps its
 * reference and is neither encoded, redacted, hashed nor stored again, so the cost of a checkpoint grows with what
 * changed since the one before, not with everything the run holds.
 */
class RunRecorderCheckpointTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";
    private static final String SECRET = "s3cr3t-d3ploy-t0ken";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final CountingBlobStore blobs = new CountingBlobStore();
    private final Clock clock = () -> Instant.parse("2026-11-03T12:00:00Z");
    private final RunRecorder recorder = RunRecorder.start(log, blobs, clock, RUN, definition());

    /** A second checkpoint of the same state stores its state blob and nothing else. */
    @Test
    void aSecondCheckpointOfAnUnchangedState_storesNoTextAgain() throws Exception {
        String output = "built\n".repeat(10_000);
        StepResult step = step(0, "make build", output);
        recorder.emit(new Checkpoint(1, snapshot(variables(output, "1.2"), output, List.of(step))));
        assertThat(blobs.stored()).as("the first checkpoint stores its texts").contains(output, "make build", "1.2");
        blobs.clear();

        recorder.emit(new Checkpoint(1, snapshot(variables(output, "1.2"), output, List.of(step))));

        assertThat(blobs.stored()).as("only the state").singleElement()
                .satisfies(state -> assertThat(state).startsWith("{\"format\":" + RunSnapshotJson.FORMAT));
        assertThat(states().get(1)).as("the same state").isEqualTo(states().get(0));
    }

    /**
     * A checkpoint stores what changed since the one before: a new step's texts and a variable's new text. A text that
     * is equal to one stored before but another string is stored again: a checkpoint knows a text by its string, not
     * by comparing it.
     */
    @Test
    void aChangedText_isStored_andOnlyIt() throws Exception {
        String output = "built\n".repeat(10_000);
        StepResult build = step(0, "make build", output);
        recorder.emit(new Checkpoint(1, snapshot(variables(output, "1.2"), output, List.of(build))));
        blobs.clear();
        String tested = "tested\n".repeat(10_000);
        StepResult test = step(1, "make test", tested);
        String copy = new String(output.toCharArray());

        Map<String, Object> variables = variables(output, "1.3");
        variables.put("step_1_output", tested);
        variables.put("copy", copy);
        recorder.emit(new Checkpoint(2, snapshot(variables, tested, List.of(build, test))));

        List<String> stored = blobs.stored();
        assertThat(stored).hasSize(5).contains("make test", tested, "1.3", copy);
        assertThat(stored.stream().filter(output::equals)).as("the copy, not the output the step holds").hasSize(1);
        JsonNode state = JSON.readTree(states().get(1));
        assertThat(text(state.at("/context/text_variables/version"))).isEqualTo("1.3");
        assertThat(state.at("/steps/0/output")).isEqualTo(state.at("/context/text_variables/copy"));
        assertThat(RunSnapshotJson.read(states().get(1), new SnapshotTexts(blobs.store)).context().variables())
                .containsEntry("version", "1.3").containsEntry("step_1_output", tested);
    }

    /**
     * A reference names the bytes the redactor in force made of a text, so a new redactor forgets what the checkpoints
     * before stored: the next checkpoint stores the text again, redacted.
     */
    @Test
    void aTextStoredUnderARedactor_isStoredAgain_afterTheRedactorChanges() throws Exception {
        String note = "deployed with " + SECRET;
        RunSnapshot snapshot = snapshot(variables("out", note), "out", List.of());
        recorder.emit(new Checkpoint(1, snapshot));
        blobs.clear();

        recorder.redacting(new Redactor(Map.of("DEPLOY_TOKEN", SECRET)));
        recorder.emit(new Checkpoint(1, snapshot));

        assertThat(blobs.stored()).contains("deployed with [redacted:DEPLOY_TOKEN]", "out");
        JsonNode state = JSON.readTree(states().get(1));
        assertThat(text(state.at("/context/text_variables/version")))
                .isEqualTo("deployed with [redacted:DEPLOY_TOKEN]");
        assertThat(state.at("/context/text_variables/version")).as("another blob than before")
                .isNotEqualTo(JSON.readTree(states().get(0)).at("/context/text_variables/version"));
    }

    /**
     * The checkpoints of a run the engine runs store each text once: the run holds the same string for a text from one
     * checkpoint to the next, for the steps' inputs and outputs, the block outputs, the previous output and the
     * variables (an input, the run variables, a {@code foreach} item, {@code step_<n>_output}).
     */
    @Test
    void theCheckpointsOfARun_storeEachTextOnce() {
        BlockDefinition each = BlockDefinition.builder().type("foreach").name("each").config(new HashMap<>(Map.of(
                "items", List.of("alpha", "beta"), "execution_mode", "shell", "command", "./show {{ item }}")))
                .build();
        List<BlockDefinition> blocks = new ArrayList<>(List.of(each));
        FakeCommandRunner commands = new FakeCommandRunner().on("./show ", "shown");
        for (int i = 0; i < 4; i++) {
            blocks.add(BlockDefinition.builder().type("shell").name("s" + i)
                    .config(new HashMap<>(Map.of("command", "./s" + i))).build());
            commands.on("./s" + i, ("output of step " + i + "\n").repeat(1_000));
        }
        WorkflowDefinition workflow = new WorkflowDefinition("wf", "Wf", null, blocks, null, null, null, null,
                Map.of(), Map.of(), null, null, null);
        RunRecorder run = RunRecorder.start(log, blobs, clock, RUN, workflow);
        List<List<String>> perCheckpoint = new ArrayList<>();
        var outcome = new WorkflowEngine(CoreBlocks.registry(null)).run(workflow, Map.of("version", "1.2"),
                RunOptions.builder().runId(RUN).commandRunner(commands).clock(clock).workingDirectory("/work")
                        .checkpoints(true).events(event -> {
                            blobs.clear();
                            run.emit(event);
                            if (event instanceof Checkpoint) {
                                perCheckpoint.add(blobs.stored());
                            }
                        }).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(perCheckpoint).hasSize(blocks.size() + 1);
        List<String> texts = new ArrayList<>();
        perCheckpoint.forEach(stored -> texts.addAll(stored.subList(0, stored.size() - 1)));   // the last: the state
        assertThat(texts).as("no text twice").doesNotHaveDuplicates().contains("1.2", "beta", "[alpha]: shown\n"
                + "[beta]: shown", ("output of step 3\n").repeat(1_000).strip());
        assertThat(perCheckpoint.getLast()).as("the last step's input and output, and the state").hasSize(3);
    }

    private List<byte[]> states() throws Exception {
        List<byte[]> states = new ArrayList<>();
        for (byte[] line : log.readAll()) {
            JsonNode node = JSON.readTree(line);
            if (node.get("type").asText().equals(RecordTypes.RUN_CHECKPOINT)) {
                states.add(blobs.store.get(node.at("/state/sha256").asText()).orElseThrow());
            }
        }
        return states;
    }

    private String text(JsonNode reference) {
        return new String(blobs.store.get(reference.get("sha256").asText()).orElseThrow(), StandardCharsets.UTF_8);
    }

    private static StepResult step(int index, String input, String output) {
        return StepResult.builder().id("step-" + index).stepIndex(index).blockName("b" + index).blockType("shell")
                .status(StepStatus.COMPLETED).input(input).output(output).metadata(Map.of()).build();
    }

    /** The variables of a run after its first step: that step's output and a {@code version}. */
    private static Map<String, Object> variables(String output, String version) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("step_0_output", output);
        variables.put("version", version);
        variables.put("retries", 2);
        return variables;
    }

    /** A state as the engine takes it at each checkpoint: new maps, holding the strings the run holds. */
    private static RunSnapshot snapshot(Map<String, Object> variables, String lastOutput, List<StepResult> steps) {
        Map<String, String> blockOutputs = new LinkedHashMap<>();
        steps.forEach(step -> blockOutputs.put(step.getBlockName(), step.getOutput()));
        return new RunSnapshot(new ContextState(variables, null, blockOutputs, lastOutput, null, steps.size(),
                Map.of(), Map.of()), steps, Map.of("version", "1.2"));
    }

    private static WorkflowDefinition definition() {
        return new WorkflowDefinition("wf", "Wf", null, List.of(), null, null, null, null, null, null, null, null,
                null);
    }

    /** Keeps the blobs in memory and the content of every {@code put} since {@link #clear}, in order. */
    private static final class CountingBlobStore implements BlobStore {
        final InMemoryBlobStore store = new InMemoryBlobStore();
        private final List<String> stored = new ArrayList<>();

        @Override
        public synchronized BlobRef put(byte[] content) {
            stored.add(new String(content, StandardCharsets.UTF_8));
            return store.put(content);
        }

        @Override
        public java.util.Optional<byte[]> getUnverified(String sha256) {
            return store.getUnverified(sha256);
        }

        synchronized List<String> stored() {
            return List.copyOf(stored);
        }

        synchronized void clear() {
            stored.clear();
        }
    }
}
