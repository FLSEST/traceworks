package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.record.RunSnapshotJson;
import traceworks.core.model.RunStatus;
import traceworks.core.port.BlobRef;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryRunStore.InMemoryStoredRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checkpoints that refer to the texts of their steps by blob (docs/events.md, "run.checkpoint"): the run folder grows
 * with the outputs, not with their square; a resume reads every text back and refuses one that is deleted or changed;
 * a cancel refuses a changed one and ends the run without a gate when one is deleted; verify checks them; and a run
 * whose checkpoint is of the first or the second format still goes on.
 */
class RunServicesCheckpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Five shell steps of 300 KB output each, the scenario of the TODO. */
    private static final Path FIVE = Path.of("/pkg/five");
    /** {@link #FIVE}, then an approval that parks the run, then a step that uses two of the outputs. */
    private static final Path PARKS = Path.of("/pkg/five-parks");
    /** A step whose large output holds a secret, an approval that parks, then a step that uses the output. */
    private static final Path SECRET_PARKS = Path.of("/pkg/secret-parks");
    private static final int STEPS = 5;
    private static final int OUTPUT_SIZE = 300 * 1024;
    private static final String SECRET = "s3cr3t-d3ploy-t0ken";
    private static final String REDACTED = "[redacted:DEPLOY_TOKEN]";

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();

    RunServicesCheckpointTest() {
        StringBuilder shells = new StringBuilder();
        for (int i = 0; i < STEPS; i++) {
            shells.append(i == 0 ? "" : ",\n").append("{\"type\": \"shell\", \"name\": \"s").append(i)
                    .append("\", \"config\": {\"command\": \"./s").append(i).append("\"}}");
            fx.commands.on("./s" + i, output(i));
        }
        fx.packages.put(FIVE, "{\"id\": \"wf-five\", \"name\": \"Five\", \"blocks\": [" + shells + "]}");
        fx.packages.put(PARKS, "{\"id\": \"wf-five-parks\", \"name\": \"Five parks\", \"blocks\": [" + shells + """
                ,
                  {"type": "approval", "name": "publish", "config": {"question": "Publish?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "report", "config": {"command": "./report {{ block.s0.output }}|{{ step_4_output }}"}}
                ]}""");
        fx.packages.put(SECRET_PARKS, """
                {"id": "wf-secret-parks", "name": "Secret parks", "blocks": [
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"]}},
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "report", "config": {"command": "./report {{ block.deploy.output }}"}}
                ]}""");
        fx.commands.on("./report", "reported");
    }

    @AfterEach
    void noThreadOutlivesAUseCase() {
        assertThat(AppFixture.liveServiceThreads()).isEmpty();
    }

    /** The output of step {@code i}: 300 KB of its own, without whitespace at its edges, which the shell trims. */
    private static String output(int i) {
        String line = "line of step " + i + " of the output\n";
        return line.repeat(OUTPUT_SIZE / line.length()) + "end of step " + i;
    }

    private long blobBytes(String runId) {
        return fx.run(runId).blobs().all().stream().mapToLong(blob -> blob.length).sum();
    }

    /**
     * Each checkpoint refers to the outputs before it instead of holding them: the blobs of the run are its outputs
     * once, plus a small state per checkpoint. Before, every checkpoint held every output before it four times (step,
     * block output, {@code step_<n>_output}, previous output): 18.5 MB of blobs for 1.6 MB of output.
     */
    @Test
    void theBlobsOfARun_growWithItsOutputs_notWithTheirSquare() {
        String id = services.start(request(FIVE), null, null).runId();

        long outputs = fx.ofType(id, "step.output").stream().mapToLong(e -> e.blob("output").size()).sum();
        assertThat(outputs).isGreaterThanOrEqualTo((long) STEPS * OUTPUT_SIZE);
        assertThat(blobBytes(id)).isLessThan(2 * outputs + 64 * 1024);
        assertThat(fx.ofType(id, "run.checkpoint")).hasSize(STEPS + 1)
                .allSatisfy(checkpoint -> assertThat(checkpoint.blob("state").size()).isLessThan(16 * 1024));
    }

    /**
     * Every text of the steps a checkpoint refers to (their inputs and outputs and the copies the context keeps of an
     * output) is a blob that {@code step.output} or {@code step.finished} names already; the other text variables,
     * such as the input {@code version}, are blobs of their own.
     */
    @Test
    void aCheckpoint_refersToTheBlobsTheStepEventsStored() {
        String id = services.start(request(FIVE), null, null).runId();
        Set<BlobRef> named = new HashSet<>();
        fx.ofType(id, "step.output").forEach(e -> named.add(e.blob("output")));
        fx.ofType(id, "step.finished").forEach(e -> named.add(e.blob("input")));

        List<RunSnapshotJson.Reference> references = RunSnapshotJson.references(state(id));

        List<RunSnapshotJson.Reference> ofTheSteps = references.stream()
                .filter(reference -> !reference.field().startsWith("context.text_variables.")
                        || reference.field().matches("context\\.text_variables\\.step_\\d+_output"))
                .toList();
        assertThat(ofTheSteps).hasSize(STEPS * 4 + 1);
        assertThat(ofTheSteps).extracting(RunSnapshotJson.Reference::blob).allMatch(named::contains);
        assertThat(references).extracting(RunSnapshotJson.Reference::field)
                .contains("context.text_variables.version", "context.text_variables.workflow_run_id");
    }

    /** A parked run with large outputs resumes from its checkpoint, and the steps after it see the outputs again. */
    @Test
    void resume_fromACheckpointThatRefersToItsTexts_givesTheStepsTheirOutputs() {
        String id = services.start(request(PARKS), null, null).runId();
        assertThat(services.view(id).status()).isEqualTo(RunStatus.PARKED);
        services.answer(id, new AnswerRequest("approval", STEPS, "Yes", null, null));

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.requests().getLast().command()).isEqualTo("./report " + output(0) + "|" + output(4));
        assertThat(services.verify(id).ok()).isTrue();
    }

    /**
     * Deleting an output removes its text from the whole record, the checkpoints included. Verify reports the
     * deleted blob, and a resume, which needs it, is refused as for a deleted state (exit 6, not as damage, which
     * verify would not find), naming the text, and records nothing.
     */
    @Test
    void aDeletedOutput_isGoneFromTheRecord_andRefusesResume() {
        String id = services.start(request(PARKS), null, null).runId();
        services.answer(id, new AnswerRequest("approval", STEPS, "Yes", null, null));
        String output = fx.ofType(id, "step.output").get(2).blob("output").sha256();
        fx.run(id).blobs().delete(output);
        int recorded = fx.events(id).size();

        assertThat(fx.run(id).blobs().all()).noneMatch(blob ->
                new String(blob, StandardCharsets.UTF_8).contains("end of step 2"));
        VerifyReport report = services.verify(id);
        assertThat(report.chain().intact()).isTrue();
        assertThat(report.chain().deletedBlobs()).containsExactly(output);
        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isExactlyInstanceOf(RunRefusedException.class)
                .hasMessage("The text steps[2].output of the checkpoint state of run " + id + " is deleted (blob "
                        + output + ")");
        assertThat(fx.events(id)).hasSize(recorded);
        assertThat(fx.commands.commands()).doesNotContain("./report");
        assertThat(fx.run(id).writerAlive()).isFalse();
    }

    /**
     * A parked run whose output was deleted still cancels, and nothing else has to be deleted: as with a deleted
     * state, the run ends without a gate, since the gate is in the output (here a YELLOW one).
     */
    @Test
    void aDeletedOutput_letsACancelEndTheRun_withoutTheGateItHeld() {
        String id = services.start(request(AppFixture.GATE), null, null).runId();
        String output = fx.ofType(id, "step.output").getFirst().blob("output").sha256();
        assertThat(fx.text(id, fx.ofType(id, "step.output").getFirst(), "output")).contains("gate_status: YELLOW");
        fx.run(id).blobs().delete(output);

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled.gate()).isEmpty();
        RecordedEvent finished = fx.ofType(id, "run.finished").getFirst();
        assertThat(finished.fields().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(finished.fields().path("gate").isNull()).isTrue();
        VerifyReport report = services.verify(id);
        assertThat(report.ok()).isTrue();
        assertThat(report.chain().deletedBlobs()).containsExactly(output);
    }

    /**
     * A text of the checkpoint with other content refuses the cancel, as a changed state does, and records nothing;
     * also when another text was deleted, since every text is checked first.
     */
    @Test
    void aChangedOutput_refusesTheCancel_alsoBesideADeletedOne() {
        String id = services.start(request(PARKS), null, null).runId();
        fx.run(id).blobs().delete(fx.ofType(id, "step.output").get(1).blob("output").sha256());
        String changed = fx.ofType(id, "step.output").get(3).blob("output").sha256();
        fx.run(id).blobs().putRaw(changed, "forged".getBytes(StandardCharsets.UTF_8));
        int recorded = fx.events(id).size();

        assertThatThrownBy(() -> services.cancel(id))
                .isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining("text steps[3].output of the checkpoint state")
                .hasMessageContaining("has other content")
                .hasMessageContaining(changed)
                .hasMessageContaining("traceworks verify " + id);
        assertThat(fx.events(id)).hasSize(recorded);
        assertThat(fx.ofType(id, "run.finished")).isEmpty();
        assertThat(fx.run(id).writerAlive()).isFalse();
    }

    @Test
    void aChangedOutput_refusesResume_andVerifyReportsIt() {
        String id = services.start(request(PARKS), null, null).runId();
        services.answer(id, new AnswerRequest("approval", STEPS, "Yes", null, null));
        String output = fx.ofType(id, "step.output").get(2).blob("output").sha256();
        fx.run(id).blobs().putRaw(output, "forged".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining("has other content")
                .hasMessageContaining(output)
                .hasMessageContaining("traceworks verify " + id);
        assertThat(fx.ofType(id, "run.resumed")).isEmpty();
        assertThat(services.verify(id).chain().problems())
                .anyMatch(p -> p.contains(output) && p.contains("state.steps[2].output"));
    }

    /**
     * A secret in a large output is redacted in its blob, the one {@code step.output} and the checkpoint share; the
     * step after the resume reads the replacement, as it read it in the state before.
     */
    @Test
    void aSecretInALargeOutput_isRedactedInTheBlobTheCheckpointRefersTo() throws Exception {
        fx.credentials.put("DEPLOY_TOKEN", SECRET);
        String half = "x".repeat(OUTPUT_SIZE / 2);
        fx.commands.on("./deploy", (CommandRequest request) -> FakeCommandRunner.ok(half + " deployed with "
                + request.environment().get("DEPLOY_TOKEN") + " " + half));
        String id = services.start(request(SECRET_PARKS), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));

        RecordedEvent output = fx.ofType(id, "step.output").getFirst();
        String redacted = half + " deployed with " + REDACTED + " " + half;
        assertThat(fx.text(id, output, "output")).isEqualTo(redacted);
        JsonNode state = JSON.readTree(state(id));
        assertThat(state.at("/steps/0/output/sha256").asText()).isEqualTo(output.blob("output").sha256());
        assertThat(state.at("/context/block_outputs/deploy/sha256").asText())
                .isEqualTo(output.blob("output").sha256());

        assertThat(services.resume(resumeOf(id), null, null).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.requests().getLast().command()).isEqualTo("./report " + redacted);
        for (byte[] blob : fx.run(id).blobs().all()) {
            assertThat(new String(blob, StandardCharsets.UTF_8)).doesNotContain(SECRET);
        }
    }

    /**
     * A run parked before checkpoints referred to their texts: its record, as that version wrote it
     * ({@code deploy-parked-checkpoint-v1.json}), holds a checkpoint state with the texts inside. It still verifies,
     * resumes and cancels.
     */
    @Test
    void aRunParkedWithAFirstFormatCheckpoint_resumes() throws Exception {
        String id = load("/records/deploy-parked-checkpoint-v1.json");
        assertThat(RunSnapshotJson.references(state(id))).as("the first format").isEmpty();
        assertThat(services.verify(id).chain().intact()).isTrue();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands()).containsExactly("./done");
        assertThat(services.verify(id).ok()).isTrue();
        assertThat(RunSnapshotJson.references(state(id))).as("the checkpoints after the resume: this format")
                .isNotEmpty();
    }

    @Test
    void aRunParkedWithAFirstFormatCheckpoint_cancels() throws Exception {
        String id = load("/records/deploy-parked-checkpoint-v1.json");

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(services.verify(id).ok()).isTrue();
    }

    /**
     * A run parked by the version before this format: its record ({@code items-parked-checkpoint-v2.json}) holds a
     * checkpoint state of the second format, whose variables are inline but for {@code step_0_output} and whose step
     * carries a {@code context_delta}. It still verifies and resumes. Such a checkpoint does not say what a model
     * wrote, so the resumed run holds every output and variable of it as text a model may have written: the step
     * after the resume, whose shell line names them, is refused and nothing runs. The checkpoint the resume writes
     * is of this format.
     */
    @Test
    void aRunParkedWithASecondFormatCheckpoint_resumes_withEveryOutputAndVariableProtected() throws Exception {
        String id = load("/records/items-parked-checkpoint-v2.json");
        fx.commands.on("./done ", "done");
        assertThat(JSON.readTree(state(id)).get("format").asInt()).isEqualTo(2);
        assertThat(RunSnapshotJson.references(state(id))).extracting(RunSnapshotJson.Reference::field)
                .contains("context.step_output_variables.step_0_output");
        assertThat(services.verify(id).ok()).isTrue();
        services.answer(id, new AnswerRequest("approval", 3, "Yes", null, null));

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(resumed.error()).contains("shell line refused, nothing ran: {{ item }}");
        assertThat(fx.commands.commands()).isEmpty();
        assertThat(services.verify(id).ok()).isTrue();
        assertThat(JSON.readTree(state(id)).get("format").asInt()).as("the checkpoints after the resume")
                .isEqualTo(RunSnapshotJson.FORMAT);
    }

    @Test
    void aRunParkedWithASecondFormatCheckpoint_cancels() throws Exception {
        String id = load("/records/items-parked-checkpoint-v2.json");

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** The state blob of the run's last checkpoint. */
    private byte[] state(String runId) {
        return fx.run(runId).blobs().get(fx.ofType(runId, "run.checkpoint").getLast().blob("state").sha256())
                .orElseThrow();
    }

    /**
     * Puts a run record from a fixture into the store: its lines and blobs as they were written, and its package as
     * its recorded definition, so the package digest is the one the record names.
     *
     * @return the run's id
     */
    private String load(String resource) throws Exception {
        JsonNode fixture;
        try (InputStream in = RunServicesCheckpointTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            fixture = JSON.readTree(in);
        }
        String id = fixture.get("run_id").asText();
        try (var created = fx.store.create(id, fx.clock.now())) {
            fixture.get("lines").forEach(line ->
                    created.events().append(line.asText().getBytes(StandardCharsets.UTF_8)));
        }
        InMemoryStoredRun run = fx.run(id);
        fixture.get("blobs").properties().forEach(blob -> {
            byte[] content = blob.getValue().asText().getBytes(StandardCharsets.UTF_8);
            assertThat(EventChain.sha256Hex(content)).isEqualTo(blob.getKey());
            run.blobs().put(content);
        });
        RecordedEvent started = fx.events(id).getFirst();
        fx.packages.put(Path.of(started.fields().path("package").path("dir").asText()),
                fx.text(id, started, "definition"));
        return id;
    }
}
