package traceworks.core.testing;

import traceworks.core.port.EventLog;
import traceworks.core.port.SealStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** The seals of a home in memory: the log, the allowed signers file and the seal files of packages, by directory. */
public final class InMemorySealStore implements SealStore {

    private InMemoryEventLog events = new InMemoryEventLog();
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Path, byte[]> seals = new LinkedHashMap<>();
    private byte[] allowedSigners;
    /** When set, reading the log fails with it, as a file without read permission does. */
    public volatile RuntimeException readFailure;
    /** When set, reading the allowed signers fails with it. */
    public volatile RuntimeException signersFailure;
    /** When set, writing a seal file fails with it. */
    public volatile RuntimeException writeFailure;

    private Log log(boolean locked) {
        if (readFailure != null) {
            throw readFailure;
        }
        if (locked) {
            lock.lock();
        }
        InMemoryEventLog current = events;
        return new Log() {
            @Override
            public EventLog events() {
                return current;
            }

            @Override
            public void close() {
                if (locked) {
                    lock.unlock();
                }
            }
        };
    }

    @Override
    public Log openLog() {
        return log(false);
    }

    @Override
    public Log openLogForWrite() {
        return log(true);
    }

    @Override
    public String logLocation() {
        return "memory:seals";
    }

    @Override
    public synchronized Optional<byte[]> allowedSigners() {
        if (signersFailure != null) {
            throw signersFailure;
        }
        return Optional.ofNullable(allowedSigners);
    }

    @Override
    public String allowedSignersLocation() {
        return "memory:allowed_signers";
    }

    @Override
    public synchronized void writeSeal(Path packageDirectory, byte[] content) {
        if (writeFailure != null) {
            throw writeFailure;
        }
        seals.put(packageDirectory.normalize(), content.clone());
    }

    @Override
    public synchronized boolean removeSeal(Path packageDirectory) {
        return seals.remove(packageDirectory.normalize()) != null;
    }

    /** The seal file of the package in {@code directory}; {@code null} when it carries none. */
    public synchronized byte[] seal(Path directory) {
        return seals.get(directory.normalize());
    }

    /** Sets the content of the allowed signers file; {@code null} removes the file. */
    public synchronized void allow(String content) {
        allowedSigners = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
    }

    /** The lines of the log, as text. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        events.readAll().forEach(line -> lines.add(new String(line, StandardCharsets.UTF_8)));
        return lines;
    }

    /** Replaces the log by these lines, as a person editing the file would. */
    public void rewrite(List<String> lines) {
        InMemoryEventLog rewritten = new InMemoryEventLog();
        lines.forEach(line -> rewritten.append(line.getBytes(StandardCharsets.UTF_8)));
        events = rewritten;
    }

    /** The log itself, for a test that tears its tail. */
    public InMemoryEventLog log() {
        return events;
    }
}
