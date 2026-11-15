package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.RunEvent.ApprovalRequested;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.OutputRecorded;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import traceworks.core.testing.InMemoryRunStore;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recorder writes no secret value: every string of a line, nested ones included, and every blob go through its
 * {@link Redactor} before they are hashed and written, so the record still verifies.
 */
class RunRecorderRedactionTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";
    private static final String SECRET = "s3cr3t-d3ploy-t0ken";
    private static final String REDACTED = "[redacted:DEPLOY_TOKEN]";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final Clock clock = () -> Instant.parse("2026-11-04T12:00:00Z");
    private final Redactor redactor = new Redactor(Map.of("DEPLOY_TOKEN", SECRET));
    private final RunRecorder recorder = RunRecorder.start(log, blobs, clock, RUN, definition()).redacting(redactor);

    private static WorkflowDefinition definition() {
        BlockDefinition shell = BlockDefinition.builder().type("shell").name("deploy")
                .config(Map.of("command", "./deploy", "secrets", List.of("DEPLOY_TOKEN"))).build();
        return new WorkflowDefinition("wf", "Wf", null, List.of(shell), null, null, null, null, null, null, null,
                null, null);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private List<JsonNode> lines() throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (byte[] line : log.readAll()) {
            lines.add(JSON.readTree(line));
        }
        return lines;
    }

    private String text(JsonNode ref) {
        return new String(blobs.get(ref.get("sha256").asText()).orElseThrow(), StandardCharsets.UTF_8);
    }

    /** No line and no blob holds the secret. */
    private void assertNoSecretInTheRecord() {
        for (byte[] line : log.readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain(SECRET);
        }
        for (byte[] blob : blobs.all()) {
            assertThat(new String(blob, StandardCharsets.UTF_8)).doesNotContain(SECRET);
        }
    }

    @Test
    void everyStringOfALine_nestedOnesIncluded_isRedacted() throws Exception {
        recorder.emit(new StepStarted(0, "deploy " + SECRET, "shell", 0, false));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("list", List.of("one " + SECRET, 2));
        nested.put(SECRET, "as a key");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("actor", "flo " + SECRET);
        values.put("nested", nested);
        recorder.record("run.resumed", values);

        List<JsonNode> lines = lines();
        assertThat(lines.get(0).get("name").asText()).isEqualTo("deploy " + REDACTED);
        assertThat(lines.get(1).get("actor").asText()).isEqualTo("flo " + REDACTED);
        assertThat(lines.get(1).get("nested").get("list").get(0).asText()).isEqualTo("one " + REDACTED);
        assertThat(lines.get(1).get("nested").has(REDACTED)).isTrue();
        assertNoSecretInTheRecord();
    }

    @Test
    void everyBlob_isRedactedBeforeItIsHashed_soTheRecordVerifies() throws Exception {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("note", "with " + SECRET);
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                bytes("{\"id\":\"wf\",\"comment\":\"" + SECRET + "\"}"), inputs, TriggerSource.MANUAL, "flo@box",
                "0.1.0", "host", null, "/ws"));
        recorder.emit(new StepStarted(0, "deploy", "shell", 0, false));
        recorder.emit(new OutputRecorded(0, "deploy", "the token is " + SECRET + "\n"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exitCode", 1);
        metadata.put("failureSummary", "failed with \"" + SECRET + "\"");
        recorder.emit(new StepFinished(0, "deploy", "shell", StepStatus.FAILED, "echo " + SECRET, 3, metadata));
        recorder.emit(new RunFinished(RunStatus.FAILED, Map.of(), "Step 0 (shell) failed: " + SECRET));

        List<JsonNode> lines = lines();
        assertThat(text(lines.get(0).get("inputs"))).contains("with " + REDACTED);
        assertThat(text(lines.get(0).get("definition"))).contains(REDACTED);
        assertThat(text(lines.get(2).get("output"))).isEqualTo("the token is " + REDACTED + "\n");
        assertThat(JSON.readTree(text(lines.get(3).get("metadata"))).get("failureSummary").asText())
                .isEqualTo("failed with \"" + REDACTED + "\"");
        assertThat(text(lines.get(4).get("error"))).isEqualTo("Step 0 (shell) failed: " + REDACTED);
        assertNoSecretInTheRecord();
        assertThat(ChainVerifier.verify(RUN, log.readAll(), blobs).problems()).isEmpty();
    }

    @Test
    void theCheckpointBlob_holdsTheReplacement_andStaysASnapshot() throws Exception {
        RunSnapshot snapshot = new RunSnapshot(new ContextState(Map.of("note", "say \"" + SECRET + "\""), null,
                Map.of("deploy", "out " + SECRET), "out " + SECRET, null, 1, Map.of(), Map.of()), List.of(),
                Map.of());

        recorder.emit(new Checkpoint(1, snapshot));

        byte[] state = blobs.get(lines().get(0).get("state").get("sha256").asText()).orElseThrow();
        RunSnapshot read = RunSnapshotJson.read(state, new SnapshotTexts(blobs));
        assertThat(read.context().previousOutput()).isEqualTo("out " + REDACTED);
        assertThat(read.context().blockOutputs()).containsEntry("deploy", "out " + REDACTED);
        assertThat(read.context().variables()).containsEntry("note", "say \"" + REDACTED + "\"");
        assertNoSecretInTheRecord();
    }

    /**
     * A step's output is a blob of its own that the state refers to, redacted as the {@code step.output} blob is: the
     * two are the same blob, and a resume reads the replacement.
     */
    @Test
    void aLargeOutputTheCheckpointRefersTo_isRedactedInItsBlob() throws Exception {
        String output = "x".repeat(150_000) + " the token is " + SECRET + " " + "y".repeat(150_000);
        String redacted = "x".repeat(150_000) + " the token is " + REDACTED + " " + "y".repeat(150_000);
        StepResult step = StepResult.builder().id("step-0").stepIndex(0).blockName("deploy").blockType("shell")
                .status(StepStatus.COMPLETED).input("./deploy").output(output).build();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("step_0_output", output);
        recorder.emit(new OutputRecorded(0, "deploy", output));
        recorder.emit(new Checkpoint(1, new RunSnapshot(new ContextState(variables, null, Map.of("deploy", output),
                output, null, 1, Map.of(), Map.of()), List.of(step), Map.of())));

        List<JsonNode> lines = lines();
        JsonNode reference = lines.get(0).get("output");
        assertThat(text(reference)).isEqualTo(redacted);
        byte[] state = blobs.get(lines.get(1).get("state").get("sha256").asText()).orElseThrow();
        assertThat(JSON.readTree(state).at("/steps/0/output")).as("the step.output blob").isEqualTo(reference);
        RunSnapshot read = RunSnapshotJson.read(state, new SnapshotTexts(blobs));
        assertThat(read.steps().getFirst().getOutput()).isEqualTo(redacted);
        assertThat(read.context().variables()).containsEntry("step_0_output", redacted);
        assertNoSecretInTheRecord();
        assertThat(ChainVerifier.verify(RUN, log.readAll(), blobs).problems()).isEmpty();
    }

    @Test
    void aRecordedCommand_namesItsEnvironment_neverItsValues() throws Exception {
        FakeCommandRunner runner = new FakeCommandRunner().on("./deploy", "deployed with " + SECRET);
        CommandRunner recording = recorder.recording(runner);
        recorder.emit(new StepStarted(0, "deploy", "shell", 0, false));

        CommandRunner.CommandResult result = recording.run(new CommandRequest("./deploy --token-from-env", "/ws",
                10, Map.of("DEPLOY_TOKEN", SECRET), null, List.of(), false));

        assertThat(result.stdout()).as("the step itself sees the real output").contains(SECRET);
        JsonNode command = lines().get(1);
        assertThat(command.get("type").asText()).isEqualTo("step.command");
        assertThat(command.get("env_names")).extracting(JsonNode::asText).containsExactly("DEPLOY_TOKEN");
        assertNoSecretInTheRecord();
    }

    @Test
    void theEventsOfAChildRun_areRedactedToo() throws Exception {
        recorder.emit(new ChildRunEvent("child-1", new OutputRecorded(0, "inner", SECRET)));

        assertThat(text(lines().get(0).get("output"))).isEqualTo(REDACTED);
        assertNoSecretInTheRecord();
    }

    /** A human-input field may be called sha256; only a blob reference keeps its hash unredacted. */
    @Test
    void aHumanInputFieldNamedSha256_isRedacted() throws Exception {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", "sha256");
        field.put("sha256", SECRET);
        recorder.emit(new ApprovalRequested(0, "b1", "human-input", "Which hash?", null, null, "form", null, false,
                null, "hash", List.of(field)));
        recorder.record("run.resumed", Map.of("sha256", SECRET));

        assertThat(lines().get(0).get("fields").get(0).get("sha256").asText()).isEqualTo(REDACTED);
        assertThat(lines().get(1).get("sha256").asText()).isEqualTo(REDACTED);
        assertNoSecretInTheRecord();
    }

    @Test
    void listeners_seeTheRedactedLine() {
        List<RecordedEvent> seen = new ArrayList<>();
        recorder.onAppend(seen::add);

        recorder.emit(new StepStarted(0, "deploy " + SECRET, "shell", 0, false));

        assertThat(seen).singleElement().satisfies(e -> assertThat(new String(e.raw(), StandardCharsets.UTF_8))
                .doesNotContain(SECRET).contains(REDACTED));
    }

    @Test
    void aBlobReference_keepsItsHash_evenWhenTheHashHoldsAValue() throws Exception {
        String content = "plain output";
        String sha = EventChain.sha256Hex(bytes(content));
        Redactor hashPart = new Redactor(Map.of("HEX", sha.substring(10, 30)));
        RunRecorder hashed = RunRecorder.start(log, blobs, clock, RUN, definition()).redacting(hashPart);

        hashed.emit(new OutputRecorded(0, "deploy", content));

        assertThat(lines().get(0).get("output").get("sha256").asText()).isEqualTo(sha);
        assertThat(ChainVerifier.verify(RUN, log.readAll(), blobs).problems()).isEmpty();
    }

    @Test
    void scratchFileWithTheSecret_isListedWithoutBlob_reasonContainsASecret() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("out.txt", bytes("token=" + SECRET + "\n"));
        scratch.put("named-" + SECRET + ".txt", bytes("harmless"));
        scratch.put("ok.txt", bytes("fine"));
        recorder.withScratch(scratch);

        recorder.emit(new StepStarted(0, "deploy", "shell", 0, false));
        recorder.emit(new StepFinished(0, "deploy", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        JsonNode files = lines().stream().filter(l -> "step.files".equals(l.get("type").asText())).findFirst()
                .orElseThrow().get("files");
        assertThat(files).extracting(f -> f.get("path").asText())
                .containsExactly("named-" + REDACTED + ".txt", "ok.txt", "out.txt");
        JsonNode secretFile = files.get(2);
        assertThat(secretFile.get("blob").isNull()).isTrue();
        assertThat(secretFile.get("reason").asText()).isEqualTo("contains a secret");
        assertThat(secretFile.get("sha256").isNull()).as("a hash of a short secret can be guessed").isTrue();
        assertThat(secretFile.get("size").asLong()).isEqualTo(("token=" + SECRET + "\n").length());
        assertThat(text(files.get(1).get("blob"))).isEqualTo("fine");
        assertNoSecretInTheRecord();
    }

    @Test
    void aFileThatStillHoldsTheSecret_isNotRecordedAgain() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("out.txt", bytes(SECRET));
        recorder.withScratch(scratch);

        for (int step = 0; step < 2; step++) {
            recorder.emit(new StepStarted(step, "deploy", "shell", 0, false));
            recorder.emit(new StepFinished(step, "deploy", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
        }

        assertThat(lines().stream().filter(l -> "step.files".equals(l.get("type").asText()))).hasSize(1);
    }

    /**
     * A recorder that goes on after a park takes over the last listing, whose paths are redacted. A file whose name
     * holds the secret and that did not change is then neither listed again nor taken for removed: the record has
     * nothing new to say about it.
     */
    @Test
    void afterAResume_anUnchangedFileWhoseNameHoldsTheSecret_isNeitherListedAgainNorRemoved() throws Exception {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("out-" + SECRET + ".txt", bytes("harmless"));
        recorder.withScratch(scratch);
        recorder.emit(new StepStarted(0, "deploy", "shell", 0, false));
        recorder.emit(new StepFinished(0, "deploy", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
        List<RecordedEvent> existing = log.readAll().stream().map(RecordedEvent::parse).toList();

        RunRecorder again = RunRecorder.continueAfter(log, blobs, clock, RUN, definition(), existing)
                .redacting(redactor);
        again.withScratch(scratch);
        again.emit(new StepStarted(1, "deploy", "shell", 0, false));
        again.emit(new StepFinished(1, "deploy", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
        scratch.put("new.txt", bytes("new"));
        again.emit(new StepStarted(2, "deploy", "shell", 0, false));
        again.emit(new StepFinished(2, "deploy", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        List<JsonNode> files = lines().stream().filter(l -> "step.files".equals(l.get("type").asText())).toList();
        assertThat(files).as("step 1 changed nothing").extracting(f -> f.get("step").asInt()).containsExactly(0, 2);
        assertThat(files.getLast().get("files")).extracting(f -> f.get("path").asText())
                .containsExactly("new.txt", "out-" + REDACTED + ".txt");
        assertThat(files.getLast().get("removed")).isEmpty();
        assertNoSecretInTheRecord();
    }
}
