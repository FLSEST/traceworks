package traceworks.adapters.record;

import traceworks.core.port.BlobStore;
import traceworks.core.port.EventLog;
import traceworks.core.port.Inbox;
import traceworks.core.port.ScratchListing;
import traceworks.core.port.StoredRun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A run in its folder. The writer lock is an OS lock on {@code run.lock}; the OS drops it when the process dies.
 * On Linux, closing any channel of a file drops all of the process's locks on it, so a lock held inside this JVM is
 * tracked in {@link #HELD} by the real path of the lock file. A key is reserved atomically before any channel is
 * opened, and no second channel is opened on a reserved file.
 */
public final class FileStoredRun implements StoredRun {

    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    /** The folder of the files the register of an audit workflow handed the run at its start. */
    static final String REGISTER_FOLDER = "register";

    private final String runId;
    private final Path dir;
    private final Path lockFile;
    private final FileEventLog events;
    private final FileBlobStore blobs;
    private final FileInbox inbox;
    private final FileScratch scratch;
    private final FileRegisterFolder registerFolder;
    private FileChannel lockChannel;
    private FileLock lock;
    private Path heldKey;
    private final boolean inStore;

    FileStoredRun(String runId, Path dir, boolean inStore) {
        this.runId = runId;
        this.inStore = inStore;
        this.dir = dir.toAbsolutePath().normalize();
        this.lockFile = this.dir.resolve("run.lock");
        this.blobs = new FileBlobStore(this.dir.resolve("blobs"));
        this.events = new FileEventLog(this.dir.resolve("events.jsonl"), this::holdsWriterLock, blobs::sync);
        this.inbox = new FileInbox(this.dir.resolve("inbox"));
        this.scratch = new FileScratch(this.dir.resolve("scratch"));
        this.registerFolder = new FileRegisterFolder(this.dir.resolve(REGISTER_FOLDER));
    }

    @Override
    public String runId() {
        return runId;
    }

    @Override
    public boolean inStore() {
        return inStore;
    }

    @Override
    public String location() {
        return dir.toString();
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
    public Inbox inbox() {
        return inbox;
    }

    @Override
    public String scratchDirectory() {
        return dir.resolve("scratch").toString();
    }

    @Override
    public String prepareScratchDirectory() {
        Path scratchDir = dir.resolve("scratch");
        try {
            Files.createDirectory(scratchDir);
        } catch (FileAlreadyExistsException e) {
            // There already, as a directory or as something else: checked below, without following a link
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the scratch directory " + scratchDir, e);
        }
        if (!Files.isDirectory(scratchDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The scratch directory " + scratchDir + " is not a directory of its own"
                    + " (a link or a file is in its place), so the run's containers cannot mount it");
        }
        return scratchDir.toString();
    }

    @Override
    public String registerDirectory() {
        return registerFolder.directory();
    }

    @Override
    public String writeRegisterFiles(Map<String, byte[]> files) {
        return registerFolder.write(files);
    }

    @Override
    public Optional<Map<String, String>> registerFileDigests() {
        return registerFolder.digests();
    }

    @Override
    public void discard() {
        synchronized (HELD) {
            if (!holdsWriterLock()) {
                throw new IllegalStateException("Run " + runId + " is discarded only by the holder of its writer lock");
            }
            if (!events.readAll().isEmpty()) {
                throw new IllegalStateException("Run " + runId + " has events; it is not discarded");
            }
            closeLocked();
        }
        try {
            deleteTree(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot remove the folder of run " + runId + ": " + dir, e);
        }
    }

    /** Deletes a folder and everything in it; a link is deleted itself, never followed. */
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public ScratchListing list(ScratchListing.Known known) {
        return scratch.list(known);
    }

    @Override
    public byte[] readScratch(String path, long maxBytes) {
        return scratch.readScratch(path, maxBytes);
    }

    @Override
    public boolean tryLockWriter() {
        synchronized (HELD) {
            if (holdsWriterLock()) {
                return true;
            }
            Path key = lockKey(true);
            if (!HELD.add(key)) {
                return false;
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                FileLock acquired = channel.tryLock();
                if (acquired != null) {
                    lockChannel = channel;
                    lock = acquired;
                    heldKey = key;
                    return true;
                }
            } catch (OverlappingFileLockException e) {
                // cannot happen while the key is reserved; treated as held
            } catch (IOException e) {
                HELD.remove(key);
                closeQuietly(channel);
                throw new UncheckedIOException("Cannot lock " + lockFile, e);
            }
            // We hold no lock on this file here, so closing our channel drops nothing of anybody in this JVM.
            HELD.remove(key);
            closeQuietly(channel);
            return false;
        }
    }

    @Override
    public boolean holdsWriterLock() {
        return lock != null && lock.isValid();
    }

    @Override
    public boolean writerAlive() {
        synchronized (HELD) {
            if (holdsWriterLock()) {
                return true;
            }
            if (!Files.exists(lockFile)) {
                return false;
            }
            Path key = lockKey(false);
            if (!HELD.add(key)) {
                return true;
            }
            try (FileChannel probe = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                FileLock acquired = probe.tryLock();
                if (acquired == null) {
                    return true;
                }
                acquired.release();
                return false;
            } catch (OverlappingFileLockException e) {
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot probe " + lockFile, e);
            } finally {
                HELD.remove(key);
            }
        }
    }

    /** The identity of the lock file, the same for every path that leads to it (symbolic links included). */
    private Path lockKey(boolean create) {
        try {
            if (create && !Files.exists(lockFile)) {
                try {
                    Files.createFile(lockFile);
                } catch (FileAlreadyExistsException e) {
                    // created by somebody else meanwhile
                }
            }
            return lockFile.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve " + lockFile, e);
        }
    }

    @Override
    public void writeManifest(byte[] json) {
        AtomicFiles.write(dir.resolve("manifest.json"), json);
    }

    @Override
    public Optional<byte[]> readManifest() {
        Path file = dir.resolve("manifest.json");
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    @Override
    public void writeView(String html) {
        AtomicFiles.write(dir.resolve("run.html"), html.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        synchronized (HELD) {
            closeLocked();
        }
    }

    private void closeLocked() {
        events.close();
        if (lock != null) {
            HELD.remove(heldKey);
            heldKey = null;
            try {
                lock.release();
            } catch (IOException e) {
                // closing the channel below releases it anyway
            }
            lock = null;
        }
        closeQuietly(lockChannel);
        lockChannel = null;
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }
}
