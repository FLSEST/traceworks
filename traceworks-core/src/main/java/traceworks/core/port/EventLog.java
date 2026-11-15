package traceworks.core.port;

import java.util.List;

/**
 * Port to the append-only event log of a run: one JSON object per line. The core writes the lines; the adapter
 * stores them durably.
 */
public interface EventLog {

    /**
     * Appends one line and makes it durable.
     *
     * @param line the JSON of the event, without the trailing line feed
     * @return the SHA-256 of {@code line} as lower-case hex
     */
    String append(byte[] line);

    /** The complete lines of the log (each without its line feed), in order. */
    List<byte[]> readAll();

    /**
     * The bytes after the last line feed, which a write that was cut off leaves; an empty array when there are none.
     * Reads only: it needs no writer lock.
     */
    byte[] tail();

    /**
     * Removes a tail without a final line feed, left by a write that was cut off.
     *
     * @return the removed tail; an empty array when there is none
     */
    byte[] discardTornTail();
}
