package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.port.BlobStore;
import traceworks.core.record.ChainReport;
import traceworks.core.record.EventChain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies a register: the chain of its log (seq, prev, the register key of every line), the blobs its lines point
 * to, the state each applied report recorded against the fold of the log, and {@code state.json} against the final
 * fold.
 */
public final class RegisterVerifier {

    private RegisterVerifier() {
    }

    public static ChainReport verify(String key, List<byte[]> lines, BlobStore blobs, Optional<byte[]> stateFile) {
        List<String> problems = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        String expectedPrev = EventChain.genesis(EventChain.REGISTER_GENESIS_PREFIX, key);
        int previousSeq = -1;
        String head = expectedPrev;
        RegisterState state = RegisterState.empty(key, null, null);
        boolean foldHolds = true;
        for (int i = 0; i < lines.size(); i++) {
            byte[] line = lines.get(i);
            head = EventChain.sha256Hex(line);
            RegisterEvent event;
            try {
                event = RegisterEvent.parse(line);
            } catch (IllegalArgumentException e) {
                problems.add("line " + i + " (after seq " + previousSeq + "): " + e.getMessage());
                expectedPrev = head;
                previousSeq++;
                continue;
            }
            String where = "seq " + event.seq();
            if (event.seq() != previousSeq + 1) {
                problems.add(where + ": expected seq " + (previousSeq + 1) + " here, so lines are missing or moved");
            }
            if (!event.prev().equals(expectedPrev)) {
                problems.add(where + ": prev does not match " + (previousSeq < 0 ? "the genesis of the register"
                        : "the hash of seq " + previousSeq) + ", so the line before was changed or removed");
            }
            if (!key.equals(event.register())) {
                problems.add(where + ": the line belongs to the register " + event.register() + ", not " + key);
            }
            // Every blob a line names: present, and its content hashes to its name, as the run's verifier checks
            for (Iterator<Map.Entry<String, JsonNode>> it = event.fields().fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                if (!field.getKey().endsWith("_blob") || !field.getValue().isTextual()) {
                    continue;
                }
                String sha = field.getValue().asText();
                Optional<byte[]> content = blobs.get(sha);
                if (content.isEmpty()) {
                    deleted.add(sha);
                    problems.add(where + ": the blob " + field.getKey() + " " + sha + " is deleted");
                } else if (!EventChain.sha256Hex(content.get()).equals(sha)) {
                    problems.add(where + ": the blob " + field.getKey() + " " + sha
                            + " does not match its name: it was edited in place");
                    foldHolds = false;   // the fold would read edited content
                }
            }
            if (foldHolds) {
                try {
                    state = RegisterReplay.apply(state, event, blobs);
                    String recorded = event.text("state_blob");
                    if (recorded != null) {
                        Optional<byte[]> stateBlob = blobs.get(recorded);
                        if (stateBlob.isPresent() && !Arrays.equals(stateBlob.get(), StateJson.write(state))) {
                            problems.add(where + ": the recorded state differs from the fold of the log up to here");
                        }
                    }
                } catch (RuntimeException e) {
                    // IllegalStateException from the replay; anything else is a tampered field the fold cannot read
                    problems.add(e instanceof IllegalStateException ? e.getMessage()
                            : where + ": the line cannot be replayed: " + e.getMessage());
                    foldHolds = false;
                }
            }
            expectedPrev = head;
            previousSeq = event.seq();
        }
        if (foldHolds && stateFile.isPresent() && !Arrays.equals(stateFile.get(), StateJson.write(state))) {
            problems.add("state.json differs from the log: it was edited, or written by another version");
        }
        return new ChainReport(problems.isEmpty(), lines.size(), head, problems, deleted);
    }
}
