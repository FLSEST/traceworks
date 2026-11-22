package traceworks.adapters.record;

import traceworks.core.port.BlobStore;
import traceworks.core.port.EventLog;
import traceworks.core.port.RegisterStore;
import traceworks.core.model.AuditConfig;
import traceworks.core.model.ProjectKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The registers of a home, one folder per key and project under {@code registers/}:
 * {@code registers/<key>/<project>/} with {@code events.jsonl}, {@code blobs/}, {@code state.json},
 * {@code report.html} and {@code register.lock}. A writer holds the register's lock, an in-JVM lock per folder plus
 * an OS lock on {@code register.lock}, from {@link #openForWrite} to {@code close}, so the writers of every thread
 * and process take turns; the writers of two projects of one key do not wait for each other. A reader takes no
 * lock. The folders are created for their owner alone.
 *
 * <p>A version before registers had projects kept one register per key, in the folder of the key itself
 * ({@code registers/<key>/events.jsonl}). Such a register is told from the registers of projects
 * ({@link #unassigned}), is the register of no project, and is only opened to be verified
 * ({@link #openUnassigned}) and to be moved to a project ({@link #openUnassignedForWrite}).
 */
public final class FileRegisterStore implements RegisterStore {

    private static final Pattern KEY = Pattern.compile(AuditConfig.KEY);
    /** The name of a register's log in its folder. */
    private static final String LOG = "events.jsonl";
    private static final String BLOBS = "blobs";
    private static final String LOCK = "register.lock";
    /** The files of a register's folder; with {@value #BLOBS}, everything a register from before left at its key. */
    private static final Set<String> ENTRIES = Set.of(LOG, "state.json", "report.html", LOCK);
    /**
     * Where a register from before registers had projects is gathered before it is moved to its project in one
     * step. No project key begins with a dot, so the name is no project's.
     */
    private static final String STAGING = ".assigning";
    /**
     * The lock every assignment of a key's register from before takes; no project's name either. The register's
     * own lock moves with the register, so it cannot be what two assignments wait for. The file is there only
     * while an assignment holds it: the writer that held it removes it ({@link FileRegister#close}).
     */
    private static final String ASSIGN_LOCK = ".assign.lock";
    /**
     * How often a writer takes the lock of an assignment again because the file it locked was removed by the
     * writer before it. Each time is an assignment of another process that ended, so the bound is never reached by
     * assignments; a file with content that someone put there is.
     */
    private static final int ASSIGN_LOCK_TRIES = 100;
    private static final Map<String, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path registersDir;

    public FileRegisterStore(Path registersDir) {
        this.registersDir = registersDir;
    }

    @Override
    public Opened open(String key, String project) {
        return new FileRegister(requireKey(key), ProjectKey.checked(project), false);
    }

    @Override
    public Opened openForWrite(String key, String project) {
        return new FileRegister(requireKey(key), ProjectKey.checked(project), true);
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

    /**
     * The folders of {@code key} that are named like a project key, with or without a log: a project is its folder,
     * so one whose log is gone still counts. Only the blobs of a register from before registers had projects, which
     * lie in a folder of the key too, are no project: beside the log of that register, and also when that log is
     * gone and the folder still holds its blobs ({@link #holdsBlobs}).
     */
    @Override
    public List<String> projects(String key) {
        Path ofKey = registersDir.resolve(requireKey(key));
        if (!Files.isDirectory(ofKey)) {
            return List.of();
        }
        boolean fromBefore = Files.isRegularFile(ofKey.resolve(LOG), LinkOption.NOFOLLOW_LINKS);
        try (Stream<Path> children = Files.list(ofKey)) {
            return children.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(ProjectKey::matches)
                    .filter(name -> !(BLOBS.equals(name) && ownEntry(ofKey, name)
                            && (fromBefore || holdsBlobs(ofKey.resolve(name))))).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the registers in " + ofKey + ": " + e.getMessage(), e);
        }
    }

    /** The name of a blob: the SHA-256 of its content. */
    private static final Pattern BLOB = Pattern.compile("[0-9a-f]{64}");

    /**
     * Whether {@code folder} holds a blob itself: a file named as a SHA-256. That is what the blobs folder of a
     * register from before registers had projects holds, also once the log of that register is gone; the folder
     * of a project, whatever its name, holds its log, its lock and a blobs folder of its own, and no blob.
     */
    private static boolean holdsBlobs(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.anyMatch(entry -> BLOB.matcher(entry.getFileName().toString()).matches()
                    && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the registers in " + folder + ": " + e.getMessage(), e);
        }
    }

    /**
     * A register from before registers had projects left its log in the folder of the key itself; a move of it that
     * was cut off left the log in the staging folder. A staging folder without a log that has begun is nothing: an
     * assignment moves the log of a register that has begun, and nothing else makes one. So a folder
     * {@value #STAGING} that holds a lock alone, or what else was put there, does not make its key a register
     * from before, which every reader would refuse.
     *
     * <p>The log in the folder of the key is asked first: an assignment moves it into the staging folder in one
     * step, so one of the two is there while it is under way.
     */
    @Override
    public boolean unassigned(String key) {
        Path ofKey = registersDir.resolve(requireKey(key));
        return logAt(ofKey.resolve(LOG)) || stagedLogBegun(ofKey);
    }

    /**
     * Whether a log lies at {@code log}: a regular file. Something else there (the folder of a project named like
     * the log) and nothing there are no log; a place the file system cannot ask about (its folder cannot be
     * entered) is neither, and cannot be read.
     *
     * @throws UncheckedIOException {@code Cannot read <log>: <cause>} when the file system cannot say
     */
    private static boolean logAt(Path log) {
        if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        Unreadable.absent(log, LinkOption.NOFOLLOW_LINKS);   // refuses a place that cannot be asked about
        return false;
    }

    /**
     * Whether the staging folder of a key holds a log that has begun: a line that is no note of the tool. A log
     * that cannot be read counts as one: it is damaged, not nothing, and is said as that by whoever opens it.
     */
    private static boolean stagedLogBegun(Path ofKey) {
        Path log = ofKey.resolve(STAGING).resolve(LOG);
        if (!logAt(log)) {
            return false;
        }
        try {
            return traceworks.core.register.RegisterReplay.begunLines(FileEventLog.ofRegister(log, () -> false, () -> {
            }).readAll());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Removes the staging folder of {@code key} while it holds no log that has begun and no register from before
     * lies at the key: the lock an assignment left in it, a log without a byte, and then the folder. Anything else
     * in it (blobs, a state file, a log with a note or a fragment) stays, and the folder with it: it is still
     * nothing to a reader, and nothing of a register is deleted here.
     */
    @Override
    public void clearStaging(String key) {
        Path ofKey = registersDir.resolve(requireKey(key));
        Path staging = ofKey.resolve(STAGING);
        if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) || unassigned(key)) {
            return;
        }
        try {
            Path lock = staging.resolve(LOCK);
            if (Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(lock);
            }
            Path log = staging.resolve(LOG);
            if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) && Files.size(log) == 0) {
                Files.delete(log);
            }
            Files.delete(staging);
        } catch (java.nio.file.DirectoryNotEmptyException e) {
            // something else lies in it, which is not removed here
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot remove the staging folder " + staging + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Opened openUnassignedForWrite(String key) {
        return new FileRegister(requireKey(key), true);
    }

    @Override
    public Opened openUnassigned(String key) {
        return new FileRegister(requireKey(key), false);
    }

    private static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("'" + key + "' is no register key: it must match " + AuditConfig.KEY);
        }
        return key;
    }

    /** Whether {@code name} in the folder of a key is a part of a register from before, not a project's folder. */
    private static boolean ownEntry(Path ofKey, String name) {
        Path entry = ofKey.resolve(name);
        return BLOBS.equals(name)
                ? Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(entry.resolve(LOG))
                : ENTRIES.contains(name) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * One register, open. A register of a project lives in {@code <key>/<project>/} from the start. A register
     * from before registers had projects lives in the folder of its key, or, while a move that was cut off is not
     * finished, in the staging folder {@value #STAGING} in it; {@link #assignTo} moves it to its project.
     */
    private final class FileRegister implements Opened {
        private final String key;
        private final Path ofKey;
        private final boolean writer;
        private String project;
        private Path dir;
        private FileBlobStore blobs;
        private FileEventLog events;
        private final List<ReentrantLock> jvmLocks = new ArrayList<>();
        private final List<FileChannel> lockChannels = new ArrayList<>();
        /** The channel that holds the lock of the assignment; {@code null} for a writer that holds none. */
        private FileChannel assignment;
        /**
         * The lock file of a register from before that this writer made by taking the lock, because the register
         * had none; {@code null} otherwise. A register that is not assigned in the end is left without it, as it
         * was: a refused assignment changes nothing.
         */
        private Path madeLock;
        /** For a reader of a register from before: where its state file lies; {@code null} for {@link #dir}. */
        private Path stateFile;
        /** Whether this is a register from before registers had projects, opened to be assigned. */
        private final boolean fromBefore;

        FileRegister(String key, String project, boolean writer) {
            this.key = key;
            this.ofKey = registersDir.resolve(key);
            this.project = project;
            this.dir = ofKey.resolve(project);
            this.writer = writer;
            this.fromBefore = false;
            if (writer) {
                create();
                lock(dir, dir.resolve(LOCK));
            }
            at(dir);
        }

        /**
         * The register from before registers had projects of {@code key}: for a writer under the lock of its
         * assignment and under the register's own lock, for a reader without a lock.
         */
        FileRegister(String key, boolean writer) {
            this.key = key;
            this.ofKey = registersDir.resolve(key);
            this.writer = writer;
            this.fromBefore = true;
            this.dir = ofKey;
            if (!unassigned(key)) {
                throw noneFromBefore();
            }
            Path staging = ofKey.resolve(STAGING);
            if (!writer) {
                // A move that was cut off left some parts in the staging folder: each is read where it lies
                atParts();
                return;
            }
            lockAssignment();
            try {
                if (!unassigned(key)) {
                    throw noneFromBefore();   // another writer assigned it while this one waited
                }
                refuseTwoPlaces(staging);
                // The register's own lock too: a writer of the version that left the register takes this one,
                // and knows nothing of the lock of an assignment. It is the one at the key while the key holds
                // one. It is moved last, so a staged one is the register's only when the key holds none; beside
                // one at the key it is a leftover
                Path atKey = ofKey.resolve(LOCK);
                Path own = !Files.isRegularFile(atKey, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(staging.resolve(LOCK), LinkOption.NOFOLLOW_LINKS)
                        ? staging.resolve(LOCK) : atKey;
                boolean had = Files.exists(own, LinkOption.NOFOLLOW_LINKS);
                lock(own, own);
                if (!had) {
                    madeLock = own;   // taking the lock made the file: a refused assignment takes it away again
                }
                // Nothing is moved here: the register is opened where its parts lie, as a reader opens it, and
                // staged only when it is assigned (assignTo), after every check. A writer that is closed without
                // an assignment leaves every file at its place
                atParts();
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }

        /** Opens the log, the blobs and the state file of a register from before, each where it lies. */
        private void atParts() {
            this.dir = part(LOG).getParent();
            this.stateFile = part("state.json");
            this.blobs = FileBlobStore.ofRegister(part(BLOBS));
            FileBlobStore store = blobs;
            this.events = FileEventLog.ofRegister(part(LOG), () -> writer, store::sync);
        }

        /**
         * Refuses a register of which a part lies at the key and in the staging folder: no move replaces a file
         * or a folder, and which of the two is the register's cannot be told here. Only what an assignment
         * leaves behind and that holds nothing counts as a leftover, which the assignment that goes through
         * removes ({@link #stage}): a staged lock, and a staged log without a byte.
         *
         * @throws UncheckedIOException with both paths; nothing is moved or written
         */
        private void refuseTwoPlaces(Path staging) {
            for (String entry : List.of(LOG, BLOBS, "state.json", "report.html")) {
                Path staged = staging.resolve(entry);
                if (!ownEntry(ofKey, entry) || !Files.exists(staged, LinkOption.NOFOLLOW_LINKS)
                        || (LOG.equals(entry) && leftoverLog(staged))) {
                    continue;
                }
                String message = "a part of the register " + key + " lies in two places: " + ofKey.resolve(entry)
                        + " and " + staged + "; one of the two is a copy, and an assignment replaces neither:"
                        + " remove the copy by hand";
                throw new UncheckedIOException(message, new java.nio.file.FileAlreadyExistsException(
                        ofKey.resolve(entry).toString(), staged.toString(), "a part of the register at two places"));
            }
        }

        /** Whether a staged log is a leftover: a regular file without a byte. */
        private static boolean leftoverLog(Path staged) {
            try {
                return Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS) && Files.size(staged) == 0;
            } catch (IOException e) {
                return false;
            }
        }

        /** Where a part of a register from before lies: in the folder of the key, or moved to the staging folder. */
        private Path part(String name) {
            Path staged = ofKey.resolve(STAGING).resolve(name);
            return !ownEntry(ofKey, name) && Files.exists(staged, LinkOption.NOFOLLOW_LINKS) ? staged
                    : ofKey.resolve(name);
        }

        private IllegalStateException noneFromBefore() {
            return new IllegalStateException("no register from before registers had projects lies at " + ofKey);
        }

        private void at(Path directory) {
            this.blobs = FileBlobStore.ofRegister(directory.resolve(BLOBS));
            FileBlobStore store = blobs;
            this.events = FileEventLog.ofRegister(directory.resolve(LOG), () -> writer, store::sync);
        }

        private void create() {
            try {
                createOwnerOnly(registersDir);
                createOwnerOnly(dir.getParent());
                createOwnerOnly(dir);
                Files.createDirectories(dir.resolve(BLOBS));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the register " + dir + ": " + e.getMessage(), e);
            }
        }

        /**
         * Takes one lock: for the threads of this JVM by {@code name}, for processes on {@code file}. The locks of
         * this object are released together, by {@link #release}.
         */
        private void lock(Path name, Path file) {
            lockInJvm(name);
            lockFile(file);
        }

        private void lockInJvm(Path name) {
            ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(name.toAbsolutePath().normalize().toString(),
                    k -> new ReentrantLock());
            jvmLock.lock();
            jvmLocks.add(jvmLock);
        }

        /**
         * @return the channel that holds the lock
         * @throws RuntimeException if the lock cannot be taken; every lock of this object is released then, the
         *                          ones for the threads of this JVM too: a failure here must not leave the next
         *                          writer waiting for ever
         */
        private FileChannel lockFile(Path file) {
            try {
                FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lockChannels.add(channel);
                channel.lock();
                return channel;
            } catch (IOException e) {
                release();
                throw new UncheckedIOException("Cannot lock the register " + dir + ": " + e.getMessage(), e);
            } catch (RuntimeException e) {
                release();   // an overlapping lock of this JVM, a channel closed by an interrupt
                throw e;
            }
        }

        /**
         * Takes the lock of the assignment of this key. The writer that held it removes the file when it is done,
         * and marks the removed file with a byte before it lets go of it. So a writer that waited and now holds a
         * file with content holds one nobody else sees: it lets go and takes the lock at its place again, where a
         * writer after it waits.
         */
        private void lockAssignment() {
            Path file = ofKey.resolve(ASSIGN_LOCK);
            lockInJvm(file);
            for (int tries = 0; tries < ASSIGN_LOCK_TRIES; tries++) {
                FileChannel channel = lockFile(file);
                try {
                    if (channel.size() == 0) {
                        assignment = channel;
                        return;
                    }
                    lockChannels.remove(channel);
                    channel.close();
                } catch (IOException e) {
                    release();
                    throw new UncheckedIOException("Cannot lock the register " + dir + ": " + e.getMessage(), e);
                }
            }
            release();
            throw new UncheckedIOException(new IOException("the lock of the assignment " + file + " holds content,"
                    + " which no assignment leaves at its place: remove the file"));
        }

        private void release() {
            if (madeLock != null && project == null) {
                try {
                    Files.deleteIfExists(madeLock);   // while the lock is held; one that was moved is not here
                } catch (IOException ignored) {
                    // a file that stays is the lock a writer of that version makes too
                }
                madeLock = null;
            }
            if (assignment != null) {
                // No assignment is under way any more: the lock goes with its holder. The file is removed while
                // the lock is held, and then marked for a writer that waits for it through a channel of its own
                try {
                    if (Files.deleteIfExists(ofKey.resolve(ASSIGN_LOCK))) {
                        assignment.write(java.nio.ByteBuffer.wrap(new byte[] {'x'}));
                    }
                } catch (IOException ignored) {
                    // a file that stays is taken again by the next assignment, and is no project
                }
                assignment = null;
            }
            for (FileChannel channel : lockChannels.reversed()) {
                try {
                    channel.close();   // the OS releases the lock with the channel
                } catch (IOException ignored) {
                    // nothing to do about it: the process holds no other channel to the file
                }
            }
            lockChannels.clear();
            jvmLocks.reversed().forEach(ReentrantLock::unlock);
            jvmLocks.clear();
        }

        /**
         * Moves what a register from before left in the folder of its key into {@code staging}, part by part. No
         * move replaces anything: a staged lock and a staged log without a byte beside the ones at the key are
         * leftovers and removed first, and any other part at both places was refused when the register was opened
         * ({@link #refuseTwoPlaces}).
         */
        private void stage(Path staging) {
            try {
                createOwnerOnly(staging);
                // The log last: while it lies in the folder of the key, nothing of the register is out of place
                for (String entry : List.of(BLOBS, "state.json", "report.html", LOG, LOCK)) {
                    if (!ownEntry(ofKey, entry)) {
                        continue;
                    }
                    Path staged = staging.resolve(entry);
                    if (Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
                            && (LOCK.equals(entry) || (LOG.equals(entry) && leftoverLog(staged)))) {
                        Files.delete(staged);
                    }
                    if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                        throw new java.nio.file.FileAlreadyExistsException(ofKey.resolve(entry).toString(),
                                staged.toString(), "a part of the register at two places");
                    }
                    Files.move(ofKey.resolve(entry), staged, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot move the register " + ofKey + " into " + staging + ": "
                        + e.getMessage(), e);
            }
        }

        @Override
        public boolean placeTaken(String target) {
            requireFromBefore();
            ProjectKey.checked(target);
            // In the folder of the key, a name of the register's own parts is no project's folder
            return Files.exists(ofKey.resolve(target), LinkOption.NOFOLLOW_LINKS) && !ownEntry(ofKey, target);
        }

        @Override
        public void assignTo(String target) {
            requireFromBefore();
            Path place = ofKey.resolve(ProjectKey.checked(target));
            if (placeTaken(target)) {
                throw new IllegalStateException("the place of the register " + key + " of " + target + ", " + place
                        + ", is taken");
            }
            events.close();
            // Only now, when the assignment is certain, is anything moved: what still lies at the key follows
            // what an assignment that was cut off staged
            Path staging = ofKey.resolve(STAGING);
            stage(staging);
            // The lock of the register at its new place, before it is there: no writer gets in between the move
            // and what this one still does under it. For processes it is the register's own lock, which this
            // writer holds and which moves with the register; for the threads of this JVM it is taken here
            lockInJvm(place);
            try {
                Files.move(staging, place, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException("the register " + key + " cannot be moved to " + place + ": " + e, e);
            }
            this.project = target;
            this.dir = place;
            this.stateFile = null;
            at(place);
        }

        private void requireFromBefore() {
            if (!fromBefore || !writer || project != null) {
                throw new UnsupportedOperationException("only a register from before registers had projects is"
                        + " assigned");
            }
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String project() {
            return project;
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
            return project == null ? ofKey.toString() : dir.toString();
        }

        @Override
        public Optional<byte[]> state() {
            Path file = stateFile != null ? stateFile : dir.resolve("state.json");
            // Absent only when the file system says so: a state file that cannot be asked for, or with a folder
            // in its place, cannot be read
            if (Unreadable.absent(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException e) {
                throw Unreadable.of(file, e);
            }
        }

        @Override
        public void writeState(byte[] json) {
            requireWriter();
            AtomicFiles.write(derived("state.json"), json);
        }

        @Override
        public void writeView(byte[] html) {
            requireWriter();
            AtomicFiles.write(derived("report.html"), html);
        }

        /**
         * Where a derived file is written: in the register's folder; for a register from before that is not
         * assigned yet, where the file lies ({@link #part}), so that no second one comes to lie beside one an
         * assignment that was cut off staged.
         */
        private Path derived(String name) {
            return fromBefore && project == null ? part(name) : dir.resolve(name);
        }

        private void requireWriter() {
            if (!writer) {
                throw new IllegalStateException("the register " + key + " of " + project
                        + " was opened for reading");
            }
        }

        @Override
        public void close() {
            if (events != null) {
                events.close();
            }
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
