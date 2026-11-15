package traceworks.adapters.record;

import traceworks.core.port.BlobStore;
import traceworks.core.port.EventLog;
import traceworks.core.port.RegisterStore;
import traceworks.core.model.AuditConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The registers of a home, one folder each under {@code registers/}: {@code events.jsonl}, {@code blobs/},
 * {@code state.json}, {@code report.html} and {@code register.lock}. A writer holds the register's lock, an in-JVM
 * lock per key plus an OS lock on {@code register.lock}, from {@link #openForWrite} to {@code close}, so the writers
 * of every thread and process take turns. A reader takes no lock. The folders are created for their owner alone.
 */
public final class FileRegisterStore implements RegisterStore {

    private static final Pattern KEY = Pattern.compile(AuditConfig.KEY);
    private static final Map<String, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path registersDir;

    public FileRegisterStore(Path registersDir) {
        this.registersDir = registersDir;
    }

    @Override
    public Opened open(String key) {
        return new FileRegister(requireKey(key), false);
    }

    @Override
    public Opened openForWrite(String key) {
        return new FileRegister(requireKey(key), true);
    }

    @Override
    public List<String> keys() {
        if (!Files.isDirectory(registersDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(registersDir)) {
            return children.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(name -> KEY.matcher(name).matches()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the registers in " + registersDir + ": " + e.getMessage(), e);
        }
    }

    private static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("'" + key + "' is no register key: it must match " + AuditConfig.KEY);
        }
        return key;
    }

    private final class FileRegister implements Opened {
        private final String key;
        private final Path dir;
        private final boolean writer;
        private final FileBlobStore blobs;
        private final FileEventLog events;
        private ReentrantLock jvmLock;
        private FileChannel lockChannel;
        private FileLock fileLock;

        FileRegister(String key, boolean writer) {
            this.key = key;
            this.dir = registersDir.resolve(key);
            this.writer = writer;
            if (writer) {
                create();
                lock();
            }
            this.blobs = new FileBlobStore(dir.resolve("blobs"));
            this.events = new FileEventLog(dir.resolve("events.jsonl"), () -> writer, blobs::sync);
        }

        private void create() {
            try {
                createOwnerOnly(registersDir);
                createOwnerOnly(dir);
                Files.createDirectories(dir.resolve("blobs"));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the register " + dir + ": " + e.getMessage(), e);
            }
        }

        private void lock() {
            jvmLock = JVM_LOCKS.computeIfAbsent(dir.toAbsolutePath().normalize().toString(),
                    k -> new ReentrantLock());
            jvmLock.lock();
            try {
                lockChannel = FileChannel.open(dir.resolve("register.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                fileLock = lockChannel.lock();
            } catch (IOException e) {
                release();
                throw new UncheckedIOException("Cannot lock the register " + dir + ": " + e.getMessage(), e);
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
        public String key() {
            return key;
        }

        @Override
        public EventLog events() {
            return events;
        }

        @Override
        public BlobStore blobs() {
            return blobs;
        }

        @Override
        public String location() {
            return dir.toString();
        }

        @Override
        public Optional<byte[]> state() {
            Path file = dir.resolve("state.json");
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void writeState(byte[] json) {
            requireWriter();
            AtomicFiles.write(dir.resolve("state.json"), json);
        }

        @Override
        public void writeView(byte[] html) {
            requireWriter();
            AtomicFiles.write(dir.resolve("report.html"), html);
        }

        private void requireWriter() {
            if (!writer) {
                throw new IllegalStateException("the register " + key + " was opened for reading");
            }
        }

        @Override
        public void close() {
            events.close();
            if (writer) {
                release();
            }
        }
    }

    /**
     * Creates {@code directory} for its owner alone, with that mode from the start: a folder that is created with
     * the default mode and narrowed by a second call is open to others for the moment in between. A folder that
     * exists is left as it is; its mode is the operator's.
     */
    private static void createOwnerOnly(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        try {
            Files.createDirectories(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            // a file system without POSIX permissions keeps its defaults
            Files.createDirectories(directory);
        }
    }

}
