package traceworks.core.port;

import java.util.Optional;

/** A run kept in a store: its event log, blobs and inbox, the writer lock and the files derived from the log. */
public interface StoredRun extends AutoCloseable, ScratchDirectory {

    String runId();

    /** Where the run lives, for messages; a folder path for the file store. */
    String location();

    EventLog events();

    BlobStore blobs();

    Inbox inbox();

    /** Where the shared scratch directory of the run is, to mount it into containers; a folder path. */
    String scratchDirectory();

    /**
     * Makes sure the scratch directory is there before a container mounts it, and returns its path: a run folder
     * from before scratch directories has none, and gets it now. What is in it stays.
     *
     * @throws IllegalStateException if something other than a directory of its own is in its place: a link would
     *                               hand the containers whatever it points to, writable
     */
    String prepareScratchDirectory();

    /**
     * Removes the run from the store, folder and all, and releases this handle's writer lock: for a start that is
     * refused right after its run was created, so a refused start leaves no run behind. Links in the folder are
     * removed, never followed.
     *
     * @throws IllegalStateException if the run has an event, or this handle does not hold its writer lock
     */
    void discard();

    /** Tries to become the single writer of the run; {@code true} when this handle holds the lock afterwards. */
    boolean tryLockWriter();

    boolean holdsWriterLock();

    /** Whether some handle, in this or another process, holds the writer lock. */
    boolean writerAlive();

    /**
     * Whether the run lives in this store: a run folder opened by its path somewhere else, such as a copy, does not,
     * and the ledger of this store does not speak for it.
     */
    boolean inStore();

    void writeManifest(byte[] json);

    Optional<byte[]> readManifest();

    /** Writes the rendered view of the run ({@code run.html}). */
    void writeView(String html);

    /** Releases the writer lock, if held, and any open file. */
    @Override
    void close();
}
