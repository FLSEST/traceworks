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
 * Verifies a register: the chain of its log (seq, prev, the register key of every line), the project its log names,
 * the blobs its lines point to, the state each applied report recorded against the fold of the log, and
 * {@code state.json} against the final fold.
 */
public final class RegisterVerifier {

    private RegisterVerifier() {
    }

    /**
     * @param project the project the register is of: its log must name it. A log that names another one, or none,
     *                is a problem; {@code null} for a register from before registers had projects, whose log names
     *                none
     */
    public static ChainReport verify(String key, String project, List<byte[]> lines, BlobStore blobs,
                                     Optional<byte[]> stateFile) {
        List<String> problems = new ArrayList<>();
        String named = null;
        String created = null;
        RegisterEvent begins = null;
        List<String> deleted = new ArrayList<>();
        String expectedPrev = EventChain.genesis(EventChain.REGISTER_GENESIS_PREFIX, key);
        int previousSeq = -1;
        String head = expectedPrev;
        RegisterState state = RegisterState.empty(key, null, null);
        // For a register from before registers had projects: the fold before the line of an assignment that is
        // not finished, which is what its state file still holds until the assignment writes it again
        RegisterState beforeAssigned = null;
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
            if (begins == null && !RegisterReplay.note(event.type())) {
                begins = event;   // the line the log begins with, which must say whose the register is
            }
            String again = RegisterReplay.saidAgain(begins, event);
            if (again != null) {
                // A further register.created names nothing: the log said whose it is in the line it begins with
                problems.add(again);
                foldHolds = false;
            } else if (RegisterEvents.CREATED.equals(event.type())) {
                named = event.text("project");   // read from the line itself, so a fold that fails hides nothing
                created = named;
            } else if (RegisterEvents.ASSIGNED.equals(event.type()) && named == null) {
                named = event.text("project");   // a register from before projects, assigned to one by this line
            }
            // Every blob a line names: present, and its content hashes to its name, as the run's verifier checks
            for (Iterator<Map.Entry<String, JsonNode>> it = event.fields().fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                if (!field.getKey().endsWith("_blob") || !field.getValue().isTextual()) {
                    continue;
                }
                String sha = field.getValue().asText();
                Optional<byte[]> content = blobs.getUnverified(sha);
                if (content.isEmpty()) {
                    deleted.add(sha);
                    problems.add(where + ": the blob " + field.getKey() + " " + sha + " is deleted");
                } else if (!EventChain.sha256Hex(content.get()).equals(sha)) {
                    // What it hashes to tells this edit from a later one: an acknowledgement covers a problem
                    // by its sentence
                    problems.add(where + ": the blob " + field.getKey() + " " + sha
                            + " does not match its name: it was edited in place (its content has the SHA-256 "
                            + EventChain.sha256Hex(content.get()) + ")");
                    foldHolds = false;   // the fold would read edited content
                }
            }
            if (foldHolds) {
                try {
                    if (project == null && RegisterEvents.ASSIGNED.equals(event.type())) {
                        beforeAssigned = state;
                    }
                    state = RegisterReplay.apply(state, event, blobs);
                    String recorded = event.text("state_blob");
                    if (recorded != null) {
                        Optional<byte[]> stateBlob = blobs.getUnverified(recorded);
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
        // A register from before registers had projects may hold the line of an assignment that is not finished;
        // what its first line names must be no project
        String other = RegisterReplay.otherProject(key, project, project == null ? created : named, begins);
        if (other != null) {
            problems.add(other);
        }
        // An assignment that was cut off after its line left the state file of before the line: that is no edit,
        // and is said as what it is by whoever verifies the register from before
        boolean ofBeforeTheAssignment = foldHolds && stateFile.isPresent() && beforeAssigned != null
                && Arrays.equals(stateFile.get(), StateJson.write(beforeAssigned));
        if (foldHolds && stateFile.isPresent() && !ofBeforeTheAssignment
                && !Arrays.equals(stateFile.get(), StateJson.write(state))) {
            // The digest of the file as it is tells this edit from a later one, which the log does not
            problems.add("state.json differs from the log: it was edited, or written by another version (state.json"
                    + " has the SHA-256 " + EventChain.sha256Hex(stateFile.get()) + ")");
        }
        return new ChainReport(problems.isEmpty(), lines.size(), head, problems, deleted);
    }
}
