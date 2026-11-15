package traceworks.core.testing;

import traceworks.core.port.EventLog;
import traceworks.core.record.EventChain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** An {@link EventLog} in memory, for tests of the core and of the modules that use the core test-jar. */
public final class InMemoryEventLog implements EventLog {

    private final List<byte[]> lines = new ArrayList<>();
    private byte[] tornTail = new byte[0];
    private int failAfter = -1;

    @Override
    public synchronized String append(byte[] line) {
        if (failAfter >= 0 && lines.size() >= failAfter) {
            throw new UncheckedIOException(new IOException("simulated: the disk is full"));
        }
        lines.add(line.clone());
        return EventChain.sha256Hex(line);
    }

    @Override
    public synchronized List<byte[]> readAll() {
        return lines.stream().map(byte[]::clone).toList();
    }

    @Override
    public synchronized byte[] tail() {
        return tornTail.clone();
    }

    @Override
    public synchronized byte[] discardTornTail() {
        byte[] tail = tornTail;
        tornTail = new byte[0];
        return tail;
    }

    /** Removes the last {@code count} lines, as a person cutting the log would. */
    public synchronized void dropLast(int count) {
        for (int i = 0; i < count; i++) {
            lines.removeLast();
        }
    }

    /** Every append after the first {@code lines} lines fails, as a full disk would make it. */
    public synchronized void failAfter(int lines) {
        failAfter = lines;
    }

    /** Simulates a write that was cut off: the next {@link #discardTornTail()} returns {@code tail}. */
    public synchronized void tearTail(byte[] tail) {
        tornTail = tail.clone();
    }

    /** Overwrites the last line, as a person editing the log would. */
    public synchronized void replaceLast(byte[] line) {
        lines.set(lines.size() - 1, line.clone());
    }
}
