package traceworks.core.seal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.Clock;
import traceworks.core.port.EventLog;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordJson;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The seal log of a home, for its writer: lines hash-chained as a run's are, from the genesis of the seal log. It
 * continues after the lines the log already holds. {@link SealLogCheck} reads and verifies it.
 */
public final class SealLog {

    /** A package was sealed. */
    public static final String SEALED = "SEALED";
    /** The seal of a workflow was removed. */
    public static final String UNSEALED = "UNSEALED";
    /** A run or a resume was refused because of a seal, or the lack of one. */
    public static final String REFUSED = "REFUSED";
    /** A tail without its line feed, left by a writer that was cut off, was discarded before this line. */
    public static final String REPAIRED = "REPAIRED";
    /** Every type this version writes and reads; a line of any other type is a problem of the log. */
    public static final Set<String> TYPES = Set.of(SEALED, UNSEALED, REFUSED, REPAIRED);

    /** What the genesis hash is computed over. */
    static final String GENESIS_PREFIX = "traceworks-seals-v1|";

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final EventLog events;
    private final Clock clock;
    private int nextSeq;
    private String head;

    private SealLog(EventLog events, Clock clock) {
        this.events = events;
        this.clock = clock;
        List<byte[]> lines = events.readAll();
        this.nextSeq = lines.size();
        this.head = lines.isEmpty() ? genesis() : EventChain.sha256Hex(lines.getLast());
    }

    /** The {@code prev} of the first line of every seal log. */
    public static String genesis() {
        return EventChain.genesis(GENESIS_PREFIX, "home");
    }

    /**
     * The log for a writer that holds its lock: a tail without its line feed is discarded first and recorded as
     * {@value #REPAIRED}, so the next line does not run into it.
     */
    public static SealLog forWriting(EventLog events, Clock clock) {
        byte[] torn = events.discardTornTail();
        SealLog log = new SealLog(events, clock);
        if (torn.length > 0) {
            log.append(REPAIRED, Map.of("discarded_bytes", torn.length));
        }
        return log;
    }

    /**
     * Appends one line and returns the new head.
     *
     * @throws IllegalArgumentException if a field cannot be written as JSON
     */
    public synchronized String append(String type, Map<String, ?> fields) {
        ObjectNode line = RecordJson.object();
        line.put("seq", nextSeq);
        line.put("at", AT.format(clock.now()));
        line.put("type", type);
        line.put("prev", head);
        ObjectNode own = RecordJson.mapper().valueToTree(fields);
        line.setAll(own);
        String hash = events.append(RecordJson.json(line));
        nextSeq++;
        head = hash;
        return hash;
    }
}
