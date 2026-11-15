package traceworks.core.port;

/**
 * A stored blob.
 *
 * @param sha256 the SHA-256 of the content as lower-case hex, which is also its address
 * @param size   the size of the content in bytes
 */
public record BlobRef(String sha256, long size) {}
