package traceworks.core.testing;

import traceworks.core.port.BlobRef;
import traceworks.core.port.BlobStore;
import traceworks.core.record.EventChain;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A {@link BlobStore} in memory, for tests of the core and of the modules that use the core test-jar. */
public final class InMemoryBlobStore implements BlobStore {

    private final Map<String, byte[]> blobs = new HashMap<>();

    @Override
    public synchronized BlobRef put(byte[] content) {
        String sha = EventChain.sha256Hex(content);
        blobs.putIfAbsent(sha, content.clone());
        return new BlobRef(sha, content.length);
    }

    @Override
    public synchronized Optional<byte[]> getUnverified(String sha256) {
        byte[] content = blobs.get(sha256);
        return content == null ? Optional.empty() : Optional.of(content.clone());
    }

    /** Removes a blob, as a person deleting it from the run folder would. */
    public synchronized void delete(String sha256) {
        blobs.remove(sha256);
    }

    /** Stores content under a name it does not hash to, as a person editing a blob file would. */
    public synchronized void putRaw(String sha256, byte[] content) {
        blobs.put(sha256, content.clone());
    }

    public synchronized int size() {
        return blobs.size();
    }

    /** A copy of the content of every blob, in no particular order. */
    public synchronized java.util.List<byte[]> all() {
        return blobs.values().stream().map(byte[]::clone).toList();
    }
}
