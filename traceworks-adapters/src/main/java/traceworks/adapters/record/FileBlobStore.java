package traceworks.adapters.record;

import traceworks.core.port.BlobRef;
import traceworks.core.port.BlobStore;
import traceworks.core.record.EventChain;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Blobs as read-only files named by their SHA-256 in the run's {@code blobs/} folder.
 *
 * <p><b>Durability.</b> A blob is durable before a line names it: the run's log calls {@link #sync} before it appends
 * a line, which makes the content of every blob stored since the last call durable, then their directory entries, with
 * one sync of the folder. That costs less than a sync of each blob and of the folder at each put (a step stores several
 * blobs before the line that names them), and a blob no line names (its run died before) is never synced. A name whose file has other content than its name
 * says (a blob a crash left empty or cut) is written again by the next put of that content.
 *
 * <p><b>Links.</b> A run folder opened by path may come from anyone. The folder counts only as a real directory and
 * a blob only as a regular file: a link at either would show files from outside the run folder as the run's content,
 * or send a blob that is stored to a place outside it. Nothing is read through one, and a put through a link at the
 * folder fails.
 */
final class FileBlobStore implements BlobStore {

    private final Path dir;
    /** The blobs stored since the last {@link #sync}, whose content and name may not be durable yet. */
    private final Set<Path> unsynced = new LinkedHashSet<>();

    /**
     * Whether a blob is absent only when the file system says so, or a link stands in its place: the blobs of a
     * register. A blob whose existence cannot be asked (its folder cannot be entered) then cannot be read, and is
     * never a blob that was deleted.
     */
    private final boolean absentOnlyWhenSaid;

    FileBlobStore(Path dir) {
        this(dir, false);
    }

    private FileBlobStore(Path dir, boolean absentOnlyWhenSaid) {
        this.dir = dir;
        this.absentOnlyWhenSaid = absentOnlyWhenSaid;
    }

    /** The blobs of a register: one is absent only when the file system says that it is. */
    static FileBlobStore ofRegister(Path dir) {
        return new FileBlobStore(dir, true);
    }

    @Override
    public synchronized BlobRef put(byte[] content) {
        String sha = EventChain.sha256Hex(content);
        Path file = dir.resolve(sha);
        if (!intact(file, sha)) {
            try {
                // Before the folder is made or used: createDirectories is content with a link to a directory
                if (Files.isSymbolicLink(dir)) {
                    throw new IOException(dir + " is a link, not the folder of the blobs");
                }
                Files.createDirectories(dir);
                Path temp = Files.createTempFile(dir, ".tmp-", ".part");
                try {
                    Files.write(temp, content);
                    if (!temp.toFile().setReadOnly()) {
                        throw new IOException("Cannot make read-only: " + temp);
                    }
                    AtomicFiles.move(temp, file);   // replaces a file with other content
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot store blob " + sha + ": " + e.getMessage(), e);
            }
            unsynced.add(file);
        }
        return new BlobRef(sha, content.length);
    }

    /**
     * Whether the file of a name exists and holds the content its name says. A link does not count, at the name or
     * at the folder, as {@link #get} reads neither: the put then writes the blob as a file, in place of a link at
     * the name, and fails for a link at the folder.
     */
    private boolean intact(Path file, String sha) {
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                return EventChain.sha256Hex(in.readAllBytes()).equals(sha);
            }
        } catch (IOException e) {
            return false;   // unreadable: written again, and a failure there is reported
        }
    }

    /**
     * Makes the blobs stored since the last call durable: their content, then their names. It works with the
     * thread's interrupt flag set, as the log's append does.
     *
     * @throws UncheckedIOException if a sync fails; the blobs stay to be synced by the next call
     */
    synchronized void sync() {
        if (unsynced.isEmpty()) {
            return;
        }
        boolean interrupted = Thread.interrupted();   // a channel refuses to work while the flag is set
        try {
            for (Path file : unsynced) {
                try (FileInputStream in = new FileInputStream(file.toFile())) {
                    in.getFD().sync();
                }
            }
            // The folder is the one the puts above wrote into, which was no link then
            try (FileChannel folder = FileChannel.open(dir, StandardOpenOption.READ)) {
                folder.force(true);
            }
            unsynced.clear();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot make the blobs in " + dir + " durable", e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** How many blobs are not synced yet, for tests. */
    synchronized int unsynced() {
        return unsynced.size();
    }

    @Override
    public Optional<byte[]> getUnverified(String sha256) {
        if (!sha256.matches("[0-9a-f]{64}")) {
            return Optional.empty();
        }
        try {
            // A link is never a blob, and a link is never the folder of the blobs: a run folder opened by path may
            // come from anyone, and a link in it would show a file from outside the folder as the run's content
            Path file = dir.resolve(sha256);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                if (absentOnlyWhenSaid && !Files.isSymbolicLink(dir) && !Files.isSymbolicLink(file)
                        && !Unreadable.absent(dir, LinkOption.NOFOLLOW_LINKS)
                        && !Unreadable.absent(file, LinkOption.NOFOLLOW_LINKS)) {
                    // Something that is no blob lies in its place
                    throw new IOException(Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
                            ? "a folder is in its place" : "no regular file is in its place");
                }
                return Optional.empty();
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (UncheckedIOException e) {
            // What the file system cannot say of the blob or of its folder
            throw new UncheckedIOException("Cannot read blob " + sha256 + ": " + Unreadable.cause(dir.resolve(sha256),
                    e.getCause()) + " (" + dir.resolve(sha256) + ")", e.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read blob " + sha256 + ": " + Unreadable.cause(dir.resolve(sha256),
                    e) + " (" + dir.resolve(sha256) + ")", e);
        }
    }
}
