package traceworks.core.record;

import traceworks.core.port.BlobRef;

/**
 * A file of the scratch directory as a step left it.
 *
 * @param blob   the content; {@code null} when it was not stored, then {@code reason} says why
 * @param reason {@code "too large"} or {@code "contains a secret"} when there is no blob
 */
public record FileView(String path, long size, String sha256, BlobRef blob, String reason) { }
