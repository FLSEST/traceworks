package traceworks.core.port;

import java.util.List;

/**
 * The scratch directory of a run, which every step of the run shares. A step can leave anything in it, so a
 * listing never follows a link and skips special files.
 */
public interface ScratchDirectory {

    /**
     * The regular files and symbolic links below the directory, sorted by path, with their size and hash, and the
     * entries that could not be read. Empty when the directory does not exist. Directories are walked in sorted
     * order, so a listing that is cut is cut at the same place every time.
     */
    ScratchListing list(ScratchListing.Known known);

    /** The entries of {@link #list}, without the earlier hashes. */
    default List<ScratchEntry> listScratch() {
        return list(ScratchListing.Known.NONE).entries();
    }

    /**
     * The content of a regular file of the directory, at most {@code maxBytes} bytes (so a file that grew since the
     * listing is not read further).
     *
     * @throws IllegalArgumentException for a link, a path that leaves the directory, or a file that is not regular
     */
    byte[] readScratch(String path, long maxBytes);

    /** At most {@link ScratchEntry#MAX_BLOB_BYTES} + 1 bytes. */
    default byte[] readScratch(String path) {
        return readScratch(path, ScratchEntry.MAX_BLOB_BYTES + 1);
    }
}
