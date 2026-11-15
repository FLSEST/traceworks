package traceworks.core.testing;

import traceworks.core.record.RunSnapshotJson;
import traceworks.core.model.RunSnapshot;
import traceworks.core.port.BlobRef;

import java.nio.charset.StandardCharsets;

/**
 * The texts of run snapshots as blobs of an {@link InMemoryBlobStore}, unredacted: for tests that write a snapshot and
 * read it back as a checkpoint and a resume from the record do. A text whose blob is gone fails the read with an
 * {@link IllegalStateException}.
 */
public final class SnapshotTexts implements RunSnapshotJson.TextSink, RunSnapshotJson.TextSource {

    private final InMemoryBlobStore blobs;

    public SnapshotTexts() {
        this(new InMemoryBlobStore());
    }

    public SnapshotTexts(InMemoryBlobStore blobs) {
        this.blobs = blobs;
    }

    /** A snapshot written to JSON and read back. */
    public static RunSnapshot roundTrip(RunSnapshot snapshot) {
        SnapshotTexts texts = new SnapshotTexts();
        return RunSnapshotJson.read(RunSnapshotJson.write(snapshot, texts), texts);
    }

    @Override
    public BlobRef store(String text) {
        return blobs.put(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String text(RunSnapshotJson.Reference reference) {
        return new String(blobs.get(reference.blob().sha256()).orElseThrow(() -> new IllegalStateException(
                "No blob " + reference.blob().sha256() + " for " + reference.field())), StandardCharsets.UTF_8);
    }

    public InMemoryBlobStore blobs() {
        return blobs;
    }
}
