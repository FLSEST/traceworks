package traceworks.core.seal;

import traceworks.core.record.EventChain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A seal log, read and verified: the chain of its lines ({@code seq}, {@code prev}), every line a type this version
 * knows, and the fold of the lines to the workflows that are sealed now. Whoever decides on it must look at
 * {@link #intact} first: the fold of a log with a problem says nothing.
 *
 * <p>What it cannot find without an anchor outside the home: lines cut from the end of the log.
 *
 * @param problems what is wrong with the log, one text each; empty for a log that holds
 * @param sealed   the workflows sealed now, by workflow id
 * @param events   the lines that could be read, in order
 */
public record SealLogCheck(List<String> problems, Map<String, Sealed> sealed, List<SealEvent> events,
                           Map<String, Instant> lastChanges) {

    /**
     * The time of the last change the log holds for a workflow: the latest of the times of its {@code SEALED} and
     * {@code UNSEALED} lines and of the signed times of the seals those lines name. It never goes back, also when a
     * seal was signed by a clock that ran ahead. A seal that arrives from elsewhere must be newer than this to be
     * taken; an older one is an earlier seal put back. {@code null} for a workflow the log never named.
     */
    public Instant lastChange(String workflowId) {
        return workflowId == null ? null : lastChanges.get(workflowId);
    }

    /**
     * A workflow the log holds as sealed.
     *
     * @param digest     the seal digest of the last {@code SEALED} line
     * @param packageDir where the package was when it was sealed
     * @param key        the fingerprint of the key that signed
     */
    public record Sealed(String workflowId, String workflowName, String packageDir, String digest, String signer,
                         String key, Instant at) {
    }

    public SealLogCheck {
        problems = List.copyOf(problems);
        sealed = Collections.unmodifiableMap(new TreeMap<>(sealed));
        events = List.copyOf(events);
        lastChanges = Collections.unmodifiableMap(new TreeMap<>(lastChanges));
    }

    /** Whether the log holds: no problem was found. */
    public boolean intact() {
        return problems.isEmpty();
    }

    /** Reads and verifies the complete lines of a seal log. */
    public static SealLogCheck of(List<byte[]> lines) {
        List<String> problems = new ArrayList<>();
        Map<String, Sealed> sealed = new TreeMap<>();
        List<SealEvent> events = new ArrayList<>();
        Map<String, Instant> lastChanges = new TreeMap<>();
        String expectedPrev = SealLog.genesis();
        int previousSeq = -1;
        for (int i = 0; i < lines.size(); i++) {
            byte[] line = lines.get(i);
            SealEvent event;
            try {
                event = SealEvent.parse(line);
            } catch (IllegalArgumentException e) {
                problems.add("line " + (i + 1) + ": " + e.getMessage());
                expectedPrev = EventChain.sha256Hex(line);
                previousSeq++;
                continue;
            }
            String where = "seq " + event.seq();
            if (event.seq() != previousSeq + 1) {
                problems.add(where + ": expected seq " + (previousSeq + 1) + " here, so lines are missing or moved");
            }
            if (!event.prev().equals(expectedPrev)) {
                problems.add(where + ": prev does not match " + (i == 0 ? "the genesis of the seal log"
                        : "the hash of the line before") + ", so a line before was changed or removed");
            }
            String id = event.text("workflow_id");
            switch (event.type()) {
                case SealLog.SEALED -> {
                    if (id == null || event.text("digest") == null) {
                        problems.add(where + ": a SEALED line without workflow_id or digest");
                    } else {
                        sealed.put(id, new Sealed(id, event.text("workflow_name"), event.text("package_dir"),
                                event.text("digest"), event.text("signer"), event.text("key"), event.at()));
                        lastChanges.merge(id, event.at(), SealLogCheck::later);
                        try {
                            if (event.text("sealed_at") != null) {
                                lastChanges.merge(id, Instant.parse(event.text("sealed_at")), SealLogCheck::later);
                            }
                        } catch (java.time.format.DateTimeParseException e) {
                            problems.add(where + ": the sealed_at of a SEALED line is not a time");
                        }
                    }
                }
                case SealLog.UNSEALED -> {
                    if (id == null) {
                        problems.add(where + ": an UNSEALED line without workflow_id");
                    } else {
                        sealed.remove(id);
                        lastChanges.merge(id, event.at(), SealLogCheck::later);
                    }
                }
                case SealLog.REFUSED, SealLog.REPAIRED -> { }
                default -> problems.add(where + ": the type " + event.type() + " is not one this version of"
                        + " Traceworks knows, so what it means for a seal is not known");
            }
            events.add(event);
            expectedPrev = EventChain.sha256Hex(line);
            previousSeq = event.seq();
        }
        return new SealLogCheck(problems, sealed, events, lastChanges);
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
