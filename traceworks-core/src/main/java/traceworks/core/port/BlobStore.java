package traceworks.core.port;

import java.util.Optional;

/** Port to the content-addressed blobs of a run: everything derived from run data is stored here, not in the log. */
public interface BlobStore {

    /** Stores the content under its SHA-256. Storing the same content again changes nothing. */
    BlobRef put(byte[] content);

    /** The content of a blob; empty when it was never stored or has been deleted. */
    Optional<byte[]> get(String sha256);
}
