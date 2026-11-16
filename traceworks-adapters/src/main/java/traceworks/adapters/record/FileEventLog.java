package traceworks.adapters.record;

import traceworks.core.port.EventLog;
import traceworks.core.record.EventChain;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The {@code events.jsonl} of a run. Writing needs the writer lock. It goes through a {@link FileOutputStream} in
 * append mode, not a channel, so it still works when the thread's interrupt flag is set.
 */
final class FileEventLog implements EventLog {

    private final Path file;
    private final BooleanSupplier holdsWriterLock;
    private final Runnable beforeAppend;
    private FileOutputStream out;

    /**
     * @param beforeAppend runs before each line is appended: makes the blobs a line may name durable first
     */
    FileEventLog(Path file, BooleanSupplier holdsWriterLock, Runnable beforeAppend) {
        this.file = file;
        this.holdsWriterLock = holdsWriterLock;
        this.beforeAppend = beforeAppend;
    }

    @Override
    public synchronized String append(byte[] line) {
        requireLock();
        beforeAppend.run();
        try {
            if (out == null) {
                out = new FileOutputStream(file.toFile(), true);
            }
            byte[] record = Arrays.copyOf(line, line.length + 1);
            record[line.length] = '\n';
            out.write(record);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to " + file, e);
        }
        return EventChain.sha256Hex(line);
    }

    @Override
    public synchronized List<byte[]> readAll() {
        byte[] all = readFile();
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                lines.add(Arrays.copyOfRange(all, start, i));
                start = i + 1;
            }
        }
        return lines;
    }

    /** Reads up to the first line feed and no further. */
    @Override
    public synchronized java.util.Optional<byte[]> first() {
        if (!Files.exists(file)) {
            return java.util.Optional.empty();
        }
        try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            for (int b = in.read(); b >= 0; b = in.read()) {
                if (b == '\n') {
                    return java.util.Optional.of(line.toByteArray());
                }
                line.write(b);
            }
            return java.util.Optional.empty();   // no complete line
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    @Override
    public synchronized byte[] tail() {
        byte[] all = readFile();
        return Arrays.copyOfRange(all, endOfLastLine(all), all.length);
    }

    @Override
    public synchronized byte[] discardTornTail() {
        requireLock();
        byte[] all = readFile();
        int end = endOfLastLine(all);
        if (end == all.length) {
            return new byte[0];
        }
        closeStream();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(end);
            raf.getFD().sync();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot truncate " + file, e);
        }
        return Arrays.copyOfRange(all, end, all.length);
    }

    synchronized void close() {
        closeStream();
    }

    /** Where the bytes after the last line feed begin: the length when the log ends with one, or is empty. */
    private static int endOfLastLine(byte[] all) {
        int end = all.length;
        while (end > 0 && all[end - 1] != '\n') {
            end--;
        }
        return end;
    }

    private void requireLock() {
        if (!holdsWriterLock.getAsBoolean()) {
            throw new IllegalStateException("The writer lock of this run is not held: " + file.getParent());
        }
    }

    private byte[] readFile() {
        try {
            return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private void closeStream() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot close " + file, e);
            } finally {
                out = null;
            }
        }
    }
}
