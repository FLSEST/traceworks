package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.port.BlobRef;
import traceworks.core.port.BlobStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a run record: every line is an event, the seq counts up without a gap, every {@code prev} is the hash of the
 * line before (the first is the genesis of the run id) and every blob holds the content it is named after, the texts
 * a checkpoint's state refers to included. A blob that is gone is reported, but it is not a problem: content may be
 * deleted on purpose. The last line is anchored only by the head, which the manifest keeps.
 */
public final class ChainVerifier {

    private ChainVerifier() {
    }

    public static ChainReport verify(String runId, List<byte[]> lines, BlobStore blobs) {
        List<String> problems = new ArrayList<>();
        Set<String> deleted = new LinkedHashSet<>();
        String expectedPrev = EventChain.genesis(runId);
        String previousLabel = "the genesis of the run";
        int previousSeq = -1;
        for (int i = 0; i < lines.size(); i++) {
            byte[] line = lines.get(i);
            RecordedEvent event;
            try {
                event = RecordedEvent.parse(line);
            } catch (IllegalArgumentException e) {
                problems.add("line " + i + " (after seq " + previousSeq + "): " + e.getMessage());
                expectedPrev = EventChain.sha256Hex(line);
                previousLabel = "the hash of line " + i;
                previousSeq++;
                continue;
            }
            String where = "seq " + event.seq();
            if (event.seq() != previousSeq + 1) {
                problems.add(where + ": expected seq " + (previousSeq + 1) + " here, so lines are missing or moved");
            }
            if (!event.prev().equals(expectedPrev)) {
                problems.add(where + ": prev does not match " + (previousSeq < 0 ? previousLabel
                        : "the hash of seq " + previousSeq) + ", so the line before was changed or removed");
            }
            if (!event.run().equals(runId) && event.parent() == null) {
                problems.add(where + ": the event belongs to run " + event.run() + ", not to " + runId);
            }
            if (event.run().equals(runId) && event.parent() != null) {
                // The run's own events name none; with one, readers would disagree on whether the run has ended
                problems.add(where + ": the event is of run " + runId + " itself and names a parent, "
                        + event.parent());
            }
            checkBlobs(event, where, blobs, problems, deleted);
            expectedPrev = EventChain.sha256Hex(line);
            previousSeq = event.seq();
            previousLabel = "the hash of seq " + previousSeq;
        }
        return new ChainReport(problems.isEmpty(), lines.size(), expectedPrev, problems, new ArrayList<>(deleted));
    }

    /**
     * Checks the blobs of the event where the recorder writes blob references: its own fields, the scratch files of a
     * {@code step.files} line, each with a reference of its own, and the texts the state of a {@code run.checkpoint}
     * refers to. A deleted blob is reported; a blob with other content or another size is a problem. Inline data that
     * only looks like a reference (a value of the workflow's own, an approval's fields) is not taken for one.
     */
    private static void checkBlobs(RecordedEvent event, String where, BlobStore blobs, List<String> problems,
                                   Set<String> deleted) {
        event.fields().properties().forEach(entry -> {
            Optional<byte[]> intact = check(event.blob(entry.getKey()), entry.getKey(), where, blobs, problems,
                    deleted);
            if (RecordTypes.RUN_CHECKPOINT.equals(event.type()) && "state".equals(entry.getKey())) {
                intact.ifPresent(state -> checkStateTexts(state, event.blob("state"), where, blobs, problems,
                        deleted));
            }
        });
        if (RecordTypes.STEP_FILES.equals(event.type())) {
            JsonNode files = event.fields().path("files");
            for (int i = 0; i < files.size(); i++) {
                check(RecordedEvent.reference(files.get(i).get("blob")), "files[" + i + "].blob", where, blobs,
                        problems, deleted);
            }
        }
    }

    /**
     * The texts a checkpoint's state refers to ({@code RunSnapshotJson}), checked as the event's own blobs are. Only a
     * state that holds what the record names is read: one deleted or changed is reported above, and what it refers to
     * cannot be told. A state of the first format embeds its texts and refers to none.
     */
    private static void checkStateTexts(byte[] state, BlobRef ref, String where, BlobStore blobs,
                                        List<String> problems, Set<String> deleted) {
        List<RunSnapshotJson.Reference> references;
        try {
            references = RunSnapshotJson.references(state);
        } catch (IllegalArgumentException e) {
            problems.add(where + ": the checkpoint state (blob " + ref.sha256() + ") cannot be read: "
                    + e.getMessage());
            return;
        }
        references.forEach(reference ->
                check(reference.blob(), "state." + reference.field(), where, blobs, problems, deleted));
    }

    /** @return the content, when the blob holds what {@code ref} names */
    private static Optional<byte[]> check(BlobRef ref, String field, String where, BlobStore blobs,
                                          List<String> problems, Set<String> deleted) {
        if (ref == null) {
            return Optional.empty();
        }
        Optional<byte[]> content = blobs.get(ref.sha256());
        if (content.isEmpty()) {
            deleted.add(ref.sha256());
        } else if (!EventChain.sha256Hex(content.get()).equals(ref.sha256())) {
            problems.add(where + ": blob " + ref.sha256() + " of " + field + " has other content");
        } else if (content.get().length != ref.size()) {
            problems.add(where + ": blob " + ref.sha256() + " of " + field + " has another size than recorded");
        } else {
            return content;
        }
        return Optional.empty();
    }
}
