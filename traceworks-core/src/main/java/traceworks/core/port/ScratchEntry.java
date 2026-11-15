package traceworks.core.port;

/**
 * One entry of the run scratch directory.
 *
 * @param path       the path below the scratch directory, with {@code /} as separator
 * @param size       the size in bytes of a regular file as read; 0 for a link
 * @param sha256     the SHA-256 of what was read as lower-case hex; {@code null} for a link, and for a file that was
 *                   not hashed ({@code reason} says why)
 * @param linkTarget the text of a symbolic link; {@code null} for a regular file. A link is never followed
 * @param modified   the modification time of a regular file in nanoseconds since the epoch
 * @param reason     {@link #TOO_LARGE_TO_HASH} for a file that was not hashed; otherwise {@code null}
 */
public record ScratchEntry(String path, long size, String sha256, String linkTarget, long modified, String reason) {

    /** The most entries a listing holds; a listing with more is cut ({@link ScratchListing#truncated()}). */
    public static final int MAX_ENTRIES = 10_000;

    /** The largest file whose content is stored as a blob: 16 MiB. */
    public static final long MAX_BLOB_BYTES = 16L * 1024 * 1024;

    /** The largest file that is hashed: 1 GiB. */
    public static final long MAX_HASH_BYTES = 1024L * 1024 * 1024;

    public static final String TOO_LARGE_TO_HASH = "too large to hash";

    public ScratchEntry(String path, long size, String sha256, String linkTarget) {
        this(path, size, sha256, linkTarget, 0, null);
    }

    public boolean isLink() {
        return linkTarget != null;
    }
}
