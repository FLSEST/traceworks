package traceworks.adapters.record;

import traceworks.core.port.EventLog;
import traceworks.core.port.SealStore;
import traceworks.core.seal.Seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The seals of a home: the seal log {@code seals/events.jsonl} with its lock {@code seals/seals.lock}, the operator's
 * allowed signers file, and the seal file {@code traceworks.seal} in a package directory. A writer of the log holds
 * its lock, an in-JVM lock plus an OS lock, from {@link #openLogForWrite} to {@code close}, so the writers of every
 * thread and process take turns; a reader takes no lock. The folder is created for its owner alone.
 */
public final class FileSealStore implements SealStore {

    /** The most bytes an allowed signers file may have. */
    static final int MAX_SIGNERS_BYTES = 1024 * 1024;
    private static final Map<String, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path sealsDir;
    private final Path allowedSigners;

    /**
     * @param sealsDir       {@code <home>/seals}
     * @param allowedSigners the allowed signers file, which may not exist
     */
    public FileSealStore(Path sealsDir, Path allowedSigners) {
        this.sealsDir = sealsDir;
        this.allowedSigners = allowedSigners;
    }

    @Override
    public Log openLog() {
        return new FileLog(false);
    }

    @Override
    public Log openLogForWrite() {
        return new FileLog(true);
    }

    @Override
    public String logLocation() {
        return sealsDir.toString();
    }

    @Override
    public Optional<byte[]> allowedSigners() {
        if (!Files.exists(allowedSigners, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            if (Files.size(allowedSigners) > MAX_SIGNERS_BYTES) {
                throw new IllegalArgumentException(allowedSigners + " is larger than the limit of "
                        + MAX_SIGNERS_BYTES / (1024 * 1024) + " MiB for an allowed signers file");
            }
            try (var in = Files.newInputStream(allowedSigners)) {
                return Optional.of(in.readNBytes(MAX_SIGNERS_BYTES));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + allowedSigners + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String allowedSignersLocation() {
        return allowedSigners.toString();
    }

    @Override
    public void writeSeal(Path packageDirectory, byte[] content) {
        Path file = packageDirectory.resolve(Seal.FILE_NAME);
        AtomicFiles.write(file, content);
        try {
            // A file of the package like its others: a temporary file is made for its owner alone
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ignored) {
            // a file system without POSIX permissions keeps its defaults
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot set the permissions of " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean removeSeal(Path packageDirectory) {
        Path file = packageDirectory.resolve(Seal.FILE_NAME);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot remove " + file + ": " + e.getMessage(), e);
        }
    }

    private final class FileLog implements Log {
        private final boolean writer;
        private final FileEventLog events;
        private ReentrantLock jvmLock;
        private FileChannel lockChannel;
        private FileLock fileLock;

        FileLog(boolean writer) {
            this.writer = writer;
            if (writer) {
                create();
                lock();
            }
            this.events = new FileEventLog(sealsDir.resolve("events.jsonl"), () -> writer, () -> { });
        }

        private void create() {
            if (Files.isDirectory(sealsDir)) {
                return;
            }
            try {
                Files.createDirectories(sealsDir);
                Files.setPosixFilePermissions(sealsDir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // a file system without POSIX permissions keeps its defaults
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the seal log folder " + sealsDir + ": "
                        + e.getMessage(), e);
            }
        }

        private void lock() {
            jvmLock = JVM_LOCKS.computeIfAbsent(sealsDir.toAbsolutePath().normalize().toString(),
                    k -> new ReentrantLock());
            jvmLock.lock();
            try {
                lockChannel = FileChannel.open(sealsDir.resolve("seals.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                fileLock = lockChannel.lock();
            } catch (IOException e) {
                release();
                throw new UncheckedIOException("Cannot lock the seal log " + sealsDir + ": " + e.getMessage(), e);
            }
        }

        private void release() {
            try {
                if (fileLock != null) {
                    fileLock.release();
                }
                if (lockChannel != null) {
                    lockChannel.close();
                }
            } catch (IOException ignored) {
                // the OS releases the lock with the channel in any case
            } finally {
                fileLock = null;
                lockChannel = null;
                if (jvmLock != null) {
                    jvmLock.unlock();
                    jvmLock = null;
                }
            }
        }

        @Override
        public EventLog events() {
            return events;
        }

        @Override
        public void close() {
            events.close();
            if (writer) {
                release();
            }
        }
    }
}
