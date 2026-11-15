package traceworks.core.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.record.RunSnapshotJson;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BlobRef;
import traceworks.core.port.Clock;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.StoredRun;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reading the record of a stored run, and going on writing it after the process that wrote it has ended. */
final class RunRecords {

    /** Reads the inputs a run recorded; a recorded {@code workflow.json} is read by {@code WorkflowJson}. */
    private static final ObjectMapper DEFINITIONS = new ObjectMapper();

    /**
     * The definition of a recorder that records use-case events only ({@code approval.answered}, {@code run.finished}
     * of a cancel): a recorder reads its definition only for the variables a starting step uses, so these need none,
     * and still work when the recorded definition was deleted.
     */
    static final WorkflowDefinition RECORDING_ONLY = new WorkflowDefinition(null, null, null, List.of(), null, null,
            null, null, null, null, null, null, null);

    private static final Set<RunStatus> FINAL =
            Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS, RunStatus.FAILED, RunStatus.CANCELLED);

    private RunRecords() {
    }

    static boolean isFinal(RunStatus status) {
        return FINAL.contains(status);
    }

    /**
     * What the events say about the run.
     *
     * @throws RunRefusedException    if the run has no event yet
     * @throws RecordDamagedException if the events do not make a record
     */
    static RunView view(String runId, List<RecordedEvent> events, boolean writerAlive) {
        if (events.isEmpty()) {
            throw new RunRefusedException("Run " + runId + " has no record yet");
        }
        try {
            return RunView.of(events, writerAlive);
        } catch (IllegalArgumentException e) {   // an event, but not one this record could have; other exceptions are bugs
            throw new RecordDamagedException("The record of run " + runId + " cannot be read: " + e.getMessage()
                    + RecordDamagedException.seeVerify(runId));
        }
    }

    /**
     * The complete lines of the log, parsed.
     *
     * @throws RecordDamagedException if a line is not an event
     */
    static List<RecordedEvent> events(StoredRun run) {
        return parse(run.runId(), run.events().readAll());
    }

    /**
     * Lines of a run's log, parsed.
     *
     * @throws RecordDamagedException if a line is not an event
     */
    static List<RecordedEvent> parse(String runId, List<byte[]> lines) {
        List<RecordedEvent> events = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            try {
                events.add(RecordedEvent.parse(lines.get(i)));
            } catch (IllegalArgumentException e) {
                throw new RecordDamagedException("Line " + i + " of the record of run " + runId + " cannot be read: "
                        + e.getMessage() + RecordDamagedException.seeVerify(runId));
            }
        }
        return List.copyOf(events);
    }

    /** The run's {@code run.started}, the first event of its record. */
    static RecordedEvent started(List<RecordedEvent> events) {
        if (events.isEmpty() || !RecordTypes.RUN_STARTED.equals(events.getFirst().type())) {
            throw new RunRefusedException("The record does not begin with run.started");
        }
        return events.getFirst();
    }

    /**
     * The content of a blob of the run, checked against the SHA-256 and the size the record names: what a resume or
     * a finalisation acts on must be what the record says.
     *
     * @param what what the blob holds, for the refusal when it is gone or changed
     * @throws RunRefusedException    if the record has no such blob or it was deleted
     * @throws RecordDamagedException if the blob has other content than the record names
     */
    static byte[] content(StoredRun run, BlobRef ref, String what) {
        if (ref == null) {
            throw new RunRefusedException("Run " + run.runId() + " has no " + what + " in its record");
        }
        return checkedContent(run, ref, what).orElseThrow(() -> new RunRefusedException(
                "The " + what + " of run " + run.runId() + " is deleted (blob " + ref.sha256() + ")"));
    }

    /**
     * Like {@link #content}, but empty when the blob was deleted.
     *
     * @throws RecordDamagedException if the blob has other content than the record names
     */
    static Optional<byte[]> checkedContent(StoredRun run, BlobRef ref, String what) {
        Optional<byte[]> content = run.blobs().get(ref.sha256());
        content.ifPresent(bytes -> {
            if (bytes.length != ref.size() || !EventChain.sha256Hex(bytes).equals(ref.sha256())) {
                throw new RecordDamagedException("The " + what + " of run " + run.runId() + " has other content"
                        + " than its record names (blob " + ref.sha256() + ")"
                        + RecordDamagedException.seeVerify(run.runId()));
            }
        });
        return content;
    }

    /**
     * The checkpoint state a resume goes on from, checked and read, with every text it refers to.
     *
     * @return empty when the state's blob was deleted
     * @throws RunRefusedException    if a text the state refers to is deleted, as for a deleted state: a deletion is
     *                                no damage, and {@code verify} does not count it as one
     * @throws RecordDamagedException if the blob has other content than the record names, or is no run snapshot, or
     *                                a text the state refers to has other content than the state names
     */
    static Optional<RunSnapshot> snapshot(StoredRun run, BlobRef state) {
        return checkedContent(run, state, "checkpoint state").map(bytes ->
                read(run, state, bytes, reference -> checkpointText(run, reference)));
    }

    /**
     * The checkpoint state a cancel takes the run's gate from, checked and read like {@link #snapshot}, but none when
     * a text it refers to was deleted, as when the state itself was: an output deleted to remove personal data does
     * not keep the run from ending. A text with other content is damage all the same.
     *
     * @return empty when the state's blob or a blob of a text it refers to was deleted
     * @throws RecordDamagedException if the state or a text it refers to has other content than the record names, or
     *                                the state is no run snapshot
     */
    static Optional<RunSnapshot> snapshotUnlessDeleted(StoredRun run, BlobRef state) {
        Optional<byte[]> bytes = checkedContent(run, state, "checkpoint state");
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        List<RunSnapshotJson.Reference> references;
        try {
            references = RunSnapshotJson.references(bytes.get());
        } catch (IllegalArgumentException e) {
            throw unreadable(run, state, e);
        }
        // Every text is checked before a deleted one counts: one with other content refuses the cancel in any case
        Map<BlobRef, String> texts = new HashMap<>();
        boolean deleted = false;
        for (RunSnapshotJson.Reference reference : references) {
            Optional<byte[]> text = checkedContent(run, reference.blob(), what(reference));
            if (text.isEmpty()) {
                deleted = true;
            } else {
                texts.put(reference.blob(), new String(text.get(), StandardCharsets.UTF_8));
            }
        }
        return deleted ? Optional.empty()
                : Optional.of(read(run, state, bytes.get(), reference -> texts.get(reference.blob())));
    }

    /** @throws RecordDamagedException if {@code bytes} are no run snapshot */
    private static RunSnapshot read(StoredRun run, BlobRef state, byte[] bytes, RunSnapshotJson.TextSource texts) {
        try {
            return RunSnapshotJson.read(bytes, texts);
        } catch (IllegalArgumentException e) {
            throw unreadable(run, state, e);
        }
    }

    private static RecordDamagedException unreadable(StoredRun run, BlobRef state, IllegalArgumentException e) {
        return new RecordDamagedException("The checkpoint state of run " + run.runId() + " cannot be read"
                + " (blob " + state.sha256() + "): " + e.getMessage() + RecordDamagedException.seeVerify(run.runId()));
    }

    /**
     * A text the checkpoint state refers to, checked as the state is. The run cannot go on without it: a deleted one
     * refuses the run as a deleted state does, a changed one as a changed state does.
     *
     * @throws RunRefusedException    if its blob is deleted
     * @throws RecordDamagedException if its blob has other content than the state names
     */
    private static String checkpointText(StoredRun run, RunSnapshotJson.Reference reference) {
        byte[] bytes = checkedContent(run, reference.blob(), what(reference)).orElseThrow(() ->
                new RunRefusedException("The " + what(reference) + " of run " + run.runId() + " is deleted (blob "
                        + reference.blob().sha256() + ")"));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** What a text of the checkpoint state is, for a refusal. */
    private static String what(RunSnapshotJson.Reference reference) {
        return "text " + reference.field() + " of the checkpoint state";
    }

    static String text(StoredRun run, BlobRef ref, String what) {
        return new String(content(run, ref, what), StandardCharsets.UTF_8);
    }

    /**
     * The definition the run was started with, read from the {@code workflow.json} bytes {@code run.started} recorded.
     *
     * @throws RecordDamagedException if the bytes are not a workflow definition
     */
    /** The inputs a run recorded, from the bytes of the {@code inputs} blob of its {@code run.started}. */
    static Map<String, Object> inputs(String runId, byte[] inputsJson) {
        try {
            return DEFINITIONS.readValue(inputsJson, new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new RecordDamagedException("The recorded inputs of run " + runId + " cannot be read: "
                    + e.getMessage() + RecordDamagedException.seeVerify(runId));
        }
    }

    static WorkflowDefinition definition(String runId, byte[] workflowJson) {
        try {
            return traceworks.core.model.WorkflowJson.read(workflowJson);
        } catch (IOException e) {
            throw new RecordDamagedException("The recorded workflow definition of run " + runId + " cannot be read: "
                    + e.getMessage() + RecordDamagedException.seeVerify(runId));
        }
    }

    /**
     * A recorder that goes on after the record's complete lines, for the holder of the writer lock. A tail that a
     * killed writer left without its line feed is cut off first and recorded as {@code run.recovered}, so the next
     * line does not run into it.
     */
    static RunRecorder continueRecord(StoredRun run, List<RecordedEvent> events, WorkflowDefinition definition,
                                      Clock clock) {
        byte[] torn = run.events().discardTornTail();
        RunRecorder recorder = RunRecorder.continueAfter(run.events(), run.blobs(), clock, run.runId(), definition,
                events);
        recordRecovery(recorder, torn);
        return recorder;
    }

    /** Records {@code run.recovered} with the tail that was cut off; nothing when there was none. */
    static void recordRecovery(RunRecorder recorder, byte[] torn) {
        if (torn.length > 0) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("discarded", torn);
            recorder.record(RecordTypes.RUN_RECOVERED, fields);
        }
    }

    /**
     * Refuses the answers left in the inbox while no process ran the run: they were bound to a question of a run that
     * went on without them, and must not reach the questions of the run that goes on now. A cancel is never stale: it
     * is meant for whichever process runs the run next, so it is not refused but returned.
     *
     * @param requests the requests taken from the inbox, oldest first
     * @return the cancels among them, oldest first
     */
    static List<InboxRequest> refuseStale(RunRecorder recorder, List<InboxRequest> requests) {
        List<InboxRequest> cancels = new ArrayList<>();
        for (InboxRequest request : requests) {
            if (isCancel(request)) {
                cancels.add(request);
            } else {
                refuse(recorder, request, "the run was not running when the request arrived");
            }
        }
        return cancels;
    }

    /** Whether the request asks the run to stop. */
    static boolean isCancel(InboxRequest request) {
        return "cancel".equals(request.kind());
    }

    /** Records {@code request.refused}; nothing when the record has ended meanwhile. */
    static void refuse(RunRecorder recorder, InboxRequest request, String reason) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", request.kind());
        fields.put("request", request.id());
        fields.put("actor", request.actor());
        fields.put("reason", reason);
        recorder.record(RecordTypes.REQUEST_REFUSED, fields);
    }

    /** The inline part of a recorded verdict; the description and summary stay blobs. */
    static Map<String, Object> gateOf(RunView view) {
        if (view.gate() == null) {
            return Map.of();
        }
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("gate_status", view.gate().status());
        if (view.gate().category() != null) {
            gate.put("gate_category", view.gate().category());
        }
        return gate;
    }

    /** The fields of {@code run.cancel_requested}. */
    static Map<String, Object> cancelRequested(String actor, String requestId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor", actor);
        fields.put("request", requestId);
        return fields;
    }
}
