package traceworks.core.port;

import java.util.List;

/**
 * What a listing of the scratch directory found.
 *
 * @param entries    regular files and links, at most {@link ScratchEntry#MAX_ENTRIES}
 * @param unreadable every entry that could not be listed, with the reason: it is not silently left out
 * @param truncated  whether the directory held more than the listing does
 */
public record ScratchListing(List<ScratchEntry> entries, List<Unreadable> unreadable, boolean truncated) {

    public ScratchListing {
        entries = List.copyOf(entries);
        unreadable = List.copyOf(unreadable);
    }

    /** An entry that could not be read: a directory or file without permission, a path too long, an I/O error. */
    public record Unreadable(String path, String reason) { }

    /** Tells the listing the hash of a file it saw before, so an unchanged file is not read again. */
    @FunctionalInterface
    public interface Known {

        /** The earlier SHA-256 of the file when its size and modification time are those given; else {@code null}. */
        String sha256(String path, long size, long modified);

        Known NONE = (path, size, modified) -> null;
    }
}
