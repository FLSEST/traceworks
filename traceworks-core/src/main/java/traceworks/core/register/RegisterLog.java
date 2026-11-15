package traceworks.core.register;

import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.Clock;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordJson;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The log of one register: lines hash-chained as a run's are, from the genesis of the register
 * ({@link EventChain#REGISTER_GENESIS_PREFIX} and the key). It continues after the lines the log already holds.
 */
public final class RegisterLog {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final RegisterStore.Opened opened;
    private final Clock clock;
    private int nextSeq;
    private String head;

    public RegisterLog(RegisterStore.Opened opened, Clock clock) {
        this.opened = opened;
        this.clock = clock;
        List<byte[]> lines = opened.events().readAll();
        this.nextSeq = lines.size();
        this.head = lines.isEmpty() ? EventChain.genesis(EventChain.REGISTER_GENESIS_PREFIX, opened.key())
                : EventChain.sha256Hex(lines.getLast());
    }

    /**
     * The log for a writer: a tail without its line feed, left by a writer that was cut off, is discarded first and
     * recorded as {@code register.repaired}, so the next line does not run into it.
     */
    public static RegisterLog forWriting(RegisterStore.Opened opened, Clock clock) {
        byte[] torn = opened.events().discardTornTail();
        RegisterLog log = new RegisterLog(opened, clock);
        if (torn.length > 0) {
            log.append(RegisterEvents.REPAIRED, Map.of("discarded_bytes", torn.length));
        }
        return log;
    }

    /** The sequence number of the last line; {@code -1} for an empty log. */
    public int lastSeq() {
        return nextSeq - 1;
    }

    /** The hash of the last line, or the genesis. */
    public String head() {
        return head;
    }

    /**
     * Appends one line and returns the new head.
     *
     * @throws IllegalArgumentException if a field cannot be written as JSON
     */
    public String append(String type, Map<String, ?> fields) {
        return append(type, fields, clock.now());
    }

    /** Appends one line with the given time, so a state that names the time and the line agree. */
    public synchronized String append(String type, Map<String, ?> fields, java.time.Instant at) {
        ObjectNode line = RecordJson.object();
        line.put("seq", nextSeq);
        line.put("at", AT.format(at));
        line.put("type", type);
        line.put("register", opened.key());
        line.put("prev", head);
        ObjectNode own = RecordJson.mapper().valueToTree(fields);
        line.setAll(own);
        byte[] bytes = RecordJson.json(line);
        String hash = opened.events().append(bytes);
        nextSeq++;
        head = hash;
        return hash;
    }

    /** Every line, parsed, in order. */
    public List<RegisterEvent> read() {
        List<RegisterEvent> events = new ArrayList<>();
        for (byte[] line : opened.events().readAll()) {
            events.add(RegisterEvent.parse(line));
        }
        return events;
    }
}
