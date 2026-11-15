package traceworks.adapters.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import traceworks.core.port.BlobRef;
import traceworks.core.record.EventChain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStoreTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void put_namesTheFileByItsHash() throws IOException {
        FileBlobStore store = new FileBlobStore(dir.resolve("blobs"));
        BlobRef ref = store.put(b("hello"));
        assertThat(ref.sha256()).isEqualTo(EventChain.sha256Hex(b("hello")));
        assertThat(ref.size()).isEqualTo(5);
        assertThat(Files.readString(dir.resolve("blobs").resolve(ref.sha256()))).isEqualTo("hello");
        assertThat(store.get(ref.sha256())).hasValueSatisfying(c -> assertThat(c).isEqualTo(b("hello")));
    }

    @Test
    void put_twice_writesOnce() throws IOException {
        FileBlobStore store = new FileBlobStore(dir.resolve("blobs"));
        BlobRef ref = store.put(b("hello"));
        Path file = dir.resolve("blobs").resolve(ref.sha256());
        var first = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000));
        store.put(b("hello"));
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(1_000);
        assertThat(first).isNotNull();
        assertThat(listing(dir.resolve("blobs"))).hasSize(1);
    }

    /**
     * Review C, L6: a blob a crash left empty or cut (its rename was durable, its content was not) is repaired by the
     * next put of the same content, instead of being skipped because its name exists.
     */
    @Test
    void put_ofAnExistingNameWithOtherContent_repairsIt() throws IOException {
        FileBlobStore store = new FileBlobStore(dir.resolve("blobs"));
        BlobRef ref = store.put(b("hello"));
        Path file = dir.resolve("blobs").resolve(ref.sha256());
        assertThat(file.toFile().setWritable(true)).isTrue();
        Files.write(file, new byte[0]);

        assertThat(store.put(b("hello"))).isEqualTo(ref);

        assertThat(Files.readString(file)).isEqualTo("hello");
        assertThat(Files.isWritable(file)).isFalse();
        assertThat(listing(dir.resolve("blobs"))).hasSize(1);
    }

    /** Review C, L6: a blob is durable before a line names it; the log makes the blobs stored so far durable first. */
    @Test
    void aLineAppended_makesTheBlobsStoredBeforeItDurable() {
        try (FileStoredRun run = (FileStoredRun) new FileRunStore(dir.resolve("runs")).create("run-1", java.time.Instant.now())) {
            FileBlobStore blobs = (FileBlobStore) run.blobs();
            blobs.put(b("one"));
            blobs.put(b("two"));
            assertThat(blobs.unsynced()).isEqualTo(2);

            run.events().append(b("{\"seq\":0}"));

            assertThat(blobs.unsynced()).isZero();
        }
    }

    @Test
    void get_missing_isEmpty() {
        assertThat(new FileBlobStore(dir.resolve("blobs")).get("0".repeat(64))).isEmpty();
    }

    @Test
    void blobs_areReadOnlyFiles() {
        FileBlobStore store = new FileBlobStore(dir.resolve("blobs"));
        Path file = dir.resolve("blobs").resolve(store.put(b("hello")).sha256());
        assertThat(Files.isWritable(file)).isFalse();
    }

    @Test
    void put_isAtomic() throws IOException {
        FileBlobStore store = new FileBlobStore(dir.resolve("blobs"));
        store.put(b("one"));
        store.put(b("two"));
        assertThat(listing(dir.resolve("blobs"))).hasSize(2).allMatch(n -> n.matches("[0-9a-f]{64}"));
    }

    private static List<String> listing(Path d) throws IOException {
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }

    /**
     * A run folder opened by path may come from anyone. A blob in it that is a link is not read: it would show a file
     * from outside the folder as the run's content.
     */
    @Test
    void get_aBlobThatIsALinkToAFileOutside_isNotRead() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "not of this run");
        Path blobs = Files.createDirectories(dir.resolve("blobs"));
        String sha = EventChain.sha256Hex(b("not of this run"));
        Files.createSymbolicLink(blobs.resolve(sha), outside);

        assertThat(new FileBlobStore(blobs).get(sha)).isEmpty();
    }

    /**
     * The same for the folder itself: a {@code blobs} that is a link to a directory outside holds no blob of this
     * run, though a file with the right name and the right content lies behind it.
     */
    @Test
    void get_throughABlobsFolderThatIsALink_readsNothing() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        String sha = EventChain.sha256Hex(b("not of this run"));
        Files.writeString(outside.resolve(sha), "not of this run");
        Path run = Files.createDirectories(dir.resolve("run"));
        Path blobs = Files.createSymbolicLink(run.resolve("blobs"), outside);

        assertThat(new FileBlobStore(blobs).get(sha)).isEmpty();
    }

    /** A blob is never written through a link at the folder: it would land outside the run folder. */
    @Test
    void put_throughABlobsFolderThatIsALink_isRefused_andWritesNothingOutside() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path run = Files.createDirectories(dir.resolve("run"));
        Path blobs = Files.createSymbolicLink(run.resolve("blobs"), outside);
        FileBlobStore store = new FileBlobStore(blobs);

        assertThatThrownBy(() -> store.put(b("hello"))).isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("is a link, not the folder of the blobs");
        assertThat(listing(outside)).isEmpty();
        assertThat(store.unsynced()).isZero();
    }

    /** A {@code blobs} that is a file is no folder of blobs either: nothing is read from it, and a put says so. */
    @Test
    void aBlobsFolderThatIsAFile_holdsNothing_andTakesNothing() throws IOException {
        Path blobs = Files.writeString(dir.resolve("blobs"), "a file");
        FileBlobStore store = new FileBlobStore(blobs);

        assertThat(store.get("0".repeat(64))).isEmpty();
        assertThatThrownBy(() -> store.put(b("hello"))).isInstanceOf(UncheckedIOException.class);
    }

    /**
     * A link in the place of a blob, to a file with that very content: {@link FileBlobStore#get} does not read it, so
     * a put must not take it for the stored blob. It writes the blob as a file of the folder.
     */
    @Test
    void put_overALinkWithTheSameContent_writesTheBlobAsAFile() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "hello");
        Path blobs = Files.createDirectories(dir.resolve("blobs"));
        String sha = EventChain.sha256Hex(b("hello"));
        Files.createSymbolicLink(blobs.resolve(sha), outside);
        FileBlobStore store = new FileBlobStore(blobs);

        store.put(b("hello"));

        assertThat(blobs.resolve(sha)).isRegularFile().isNotEqualTo(outside);
        assertThat(Files.isSymbolicLink(blobs.resolve(sha))).isFalse();
        assertThat(store.get(sha)).hasValueSatisfying(content -> assertThat(content).isEqualTo(b("hello")));
        assertThat(outside).hasContent("hello");
    }
}
