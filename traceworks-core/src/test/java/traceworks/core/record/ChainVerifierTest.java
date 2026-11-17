package traceworks.core.record;

import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;
import traceworks.core.port.RunEvent.*;
import traceworks.core.testing.InMemoryRunStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainVerifierTest {

    /** What follows "has other content": the SHA-256 of what the blob holds now. */
    private static final String NOW_HOLDS = " \\(SHA-256 [0-9a-f]{64}\\)";


    private final RecordFixture fx = new RecordFixture().started();

    private List<byte[]> intactRecord() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false), new OutputRecorded(0, "build", "built"),
                new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "echo", 1, Map.of()),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));
        return fx.lines();
    }

    private ChainReport verify(List<byte[]> lines) {
        return ChainVerifier.verify(RecordFixture.RUN, lines, fx.blobs);
    }

    private static byte[] replace(byte[] line, String from, String to) {
        return new String(line, StandardCharsets.UTF_8).replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void intactRun_hasNoProblems() {
        List<byte[]> lines = intactRecord();

        ChainReport report = verify(lines);

        assertThat(report.intact()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.deletedBlobs()).isEmpty();
        assertThat(report.events()).isEqualTo(lines.size());
        assertThat(report.head()).isEqualTo(fx.recorder.head());
    }

    @Test
    void flippedByte_isAProblemAtItsSeq() {
        List<byte[]> lines = new ArrayList<>(intactRecord());
        lines.set(2, replace(lines.get(2), "\"build\"", "\"buile\""));

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).containsExactly(
                "seq 3: prev does not match the hash of seq 2, so the line before was changed or removed");
    }

    @Test
    void flippedByteInTheLastLine_changesTheHead() {
        List<byte[]> lines = new ArrayList<>(intactRecord());
        lines.set(lines.size() - 1, replace(lines.getLast(), "COMPLETED", "FAILED"));

        assertThat(verify(lines).head()).isNotEqualTo(fx.recorder.head());
    }

    @Test
    void removedLine_isASeqGap() {
        List<byte[]> lines = new ArrayList<>(intactRecord());
        lines.remove(2);

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.startsWith("seq 3: expected seq 2"));
        assertThat(report.problems()).hasSize(2);
    }

    @Test
    void alteredBlob_isAProblem() {
        List<byte[]> lines = intactRecord();
        BlobRef output = RecordedEvent.parse(lines.get(2)).blob("output");
        fx.blobs.delete(output.sha256());
        // a different content stored under the name of the first
        fx.blobs.putRaw(output.sha256(), "tampered".getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).singleElement().asString().matches(java.util.regex.Pattern.quote(
                "seq 2: blob " + output.sha256() + " of output has other content") + NOW_HOLDS);
    }

    /** A step.files line lists the scratch files in an array, each with its blob reference nested inside. */
    @Test
    void alteredBlobOfAScratchFile_isAProblem_andAMissingOneIsDeleted() {
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        fx.recorder.withScratch(scratch);
        scratch.put("out/a.txt", "one".getBytes(StandardCharsets.UTF_8));
        scratch.put("out/b.txt", "two".getBytes(StandardCharsets.UTF_8));
        List<byte[]> lines = intactRecord();
        RecordedEvent files = lines.stream().map(RecordedEvent::parse)
                .filter(e -> RecordTypes.STEP_FILES.equals(e.type())).findFirst().orElseThrow();
        String a = files.fields().get("files").get(0).get("blob").get("sha256").asText();
        String b = files.fields().get("files").get(1).get("blob").get("sha256").asText();
        fx.blobs.delete(a);
        fx.blobs.putRaw(a, "tampered".getBytes(StandardCharsets.UTF_8));
        fx.blobs.delete(b);

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).singleElement().asString().matches(java.util.regex.Pattern.quote(
                "seq " + files.seq() + ": blob " + a + " of files[0].blob has other content") + NOW_HOLDS);
        assertThat(report.deletedBlobs()).containsExactly(b);
    }

    /**
     * A form field of a workflow's own that only looks like a blob reference is inline data: verify does not look it
     * up, so it is neither a deleted blob nor a problem.
     */
    @Test
    void inlineDataThatLooksLikeABlobReference_isNotTakenForOne() {
        Map<String, Object> field = Map.of("name", "id", "default", Map.of("sha256", "a".repeat(64), "size", 3));
        fx.emit(new StepStarted(0, "ask", "human-input", 0, false),
                new ApprovalRequested(0, "ask", "human-input", "Which?", List.of(), List.of(), "text", null, false,
                        null, "id", List.of(field)));

        ChainReport report = verify(fx.lines());

        assertThat(report.intact()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.deletedBlobs()).isEmpty();
    }

    @Test
    void missingBlob_isDeleted_notAProblem() {
        List<byte[]> lines = intactRecord();
        BlobRef output = RecordedEvent.parse(lines.get(2)).blob("output");
        fx.blobs.delete(output.sha256());

        ChainReport report = verify(lines);

        assertThat(report.intact()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.deletedBlobs()).containsExactly(output.sha256());
    }

    /**
     * The texts a checkpoint's state refers to are checked like the blobs of a line: a deleted one is reported, one
     * with other content is a problem at the checkpoint's seq, named by its place in the state.
     */
    @Test
    void aTextTheCheckpointRefersTo_isCheckedLikeTheBlobOfALine() {
        List<byte[]> lines = withCheckpoint("only in the state");
        RecordedEvent checkpoint = RecordedEvent.parse(lines.getLast());
        String previous = sha("only in the state");
        String output = RecordedEvent.parse(lines.get(2)).blob("output").sha256();
        fx.blobs.delete(previous);
        fx.blobs.putRaw(previous, "tampered".getBytes(StandardCharsets.UTF_8));
        fx.blobs.delete(output);

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).singleElement().asString().matches(java.util.regex.Pattern.quote(
                "seq " + checkpoint.seq() + ": blob " + previous
                + " of state.context.previous_output has other content") + NOW_HOLDS);
        assertThat(report.deletedBlobs()).as("the output, once for the line and the state").containsExactly(output);
    }

    @Test
    void aCheckpointWithItsTexts_isIntact() {
        ChainReport report = verify(withCheckpoint("ok"));

        assertThat(report.intact()).isTrue();
        assertThat(report.deletedBlobs()).isEmpty();
    }

    /** A state that is gone or changed is reported itself; what it refers to cannot be told and is not looked at. */
    @Test
    void aDeletedState_isReported_withoutItsTexts() {
        List<byte[]> lines = withCheckpoint("only in the state");
        BlobRef state = RecordedEvent.parse(lines.getLast()).blob("state");
        fx.blobs.delete(state.sha256());
        fx.blobs.delete(sha("only in the state"));

        ChainReport report = verify(lines);

        assertThat(report.intact()).isTrue();
        assertThat(report.deletedBlobs()).containsExactly(state.sha256());
    }

    /** A state as the first format wrote it embeds its texts: verify finds no reference in it. */
    @Test
    void aCheckpointOfTheFirstFormat_refersToNoBlob() throws Exception {
        byte[] first;
        try (var in = ChainVerifierTest.class.getResourceAsStream("/snapshots/every-field-v1.json")) {
            first = in.readAllBytes();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("next_position", 1);
        fields.put("state", first);
        fx.recorder.record(RecordTypes.RUN_CHECKPOINT, fields);

        ChainReport report = verify(fx.lines());

        assertThat(report.intact()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.deletedBlobs()).isEmpty();
    }

    @Test
    void aCheckpointStateThatIsNoSnapshot_isAProblem() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("next_position", 1);
        fields.put("state", "{\"format\":2}".getBytes(StandardCharsets.UTF_8));
        fx.recorder.record(RecordTypes.RUN_CHECKPOINT, fields);
        RecordedEvent checkpoint = RecordedEvent.parse(fx.lines().getLast());

        ChainReport report = verify(fx.lines());

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).containsExactly("seq " + checkpoint.seq() + ": the checkpoint state (blob "
                + checkpoint.blob("state").sha256() + ") cannot be read: Not a run snapshot: 'steps' is missing");
    }

    /** A step that output {@code built}, then a checkpoint whose previous output is {@code previous}. */
    private List<byte[]> withCheckpoint(String previous) {
        StepResult build = StepResult.builder().id("step-0").stepIndex(0).blockName("build").blockType("shell")
                .status(StepStatus.COMPLETED).input("echo").output("built").build();
        fx.emit(new StepStarted(0, "build", "shell", 0, false), new OutputRecorded(0, "build", "built"),
                new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "echo", 1, Map.of()),
                new Checkpoint(1, new RunSnapshot(new ContextState(Map.of(), null, Map.of("build", "built"),
                        previous, null, 1, Map.of(), Map.of()), List.of(build), Map.of())));
        return fx.lines();
    }

    private static String sha(String text) {
        return EventChain.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void eventsOfAnotherRun_failTheGenesis() {
        List<byte[]> lines = intactRecord();

        ChainReport report = ChainVerifier.verify("00000000-0000-0000-0000-000000000002", lines, fx.blobs);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems().getFirst())
                .startsWith("seq 0: prev does not match the genesis of the run");
        assertThat(report.problems()).anyMatch(p -> p.contains("belongs to run " + RecordFixture.RUN));
    }

    @Test
    void unparsableLine_isAProblem() {
        List<byte[]> lines = new ArrayList<>(intactRecord());
        lines.set(1, "{not json".getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems().getFirst()).startsWith("line 1 (after seq 0): Not an event");
        assertThat(report.problems()).anyMatch(p -> p.startsWith("seq 2: prev does not match"));
    }

    @Test
    void childEvents_areNotMistakenForAnotherRun() {
        fx.emit(new ChildRunEvent("c", new StepStarted(0, "x", "shell", 0, false)));

        assertThat(verify(fx.lines()).intact()).isTrue();
    }

    /** The run's own events name no parent; one that does is not the recorder's. */
    @Test
    void anEventOfTheRunItselfThatNamesAParent_isAProblem() {
        List<byte[]> lines = new ArrayList<>(intactRecord());
        int last = lines.size() - 1;
        lines.set(last, replace(lines.get(last), "{\"seq\"", "{\"parent\":\"x\",\"seq\""));

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(problem -> assertThat(problem).contains("names a parent"));
    }
}
