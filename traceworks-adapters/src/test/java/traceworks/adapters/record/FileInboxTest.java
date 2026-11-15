package traceworks.adapters.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import traceworks.core.port.InboxRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileInboxTest {

    @TempDir
    Path dir;

    private static InboxRequest request(String id, long millis) {
        return new InboxRequest(id, "approval", "run-" + id, 2, 17, "yes", Map.of("k", "v"), "ok", "flo",
                Instant.ofEpochMilli(millis));
    }

    @Test
    void post_thenTake_returnsTheRequestsOldestFirst() {
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("b", 2_000));
        inbox.post(request("a", 1_000));
        InboxRequest cancel = new InboxRequest("c", "cancel", null, null, null, null, null, null, "flo",
                Instant.ofEpochMilli(3_000));
        inbox.post(cancel);
        assertThat(inbox.take()).containsExactly(request("a", 1_000), request("b", 2_000), cancel);
        assertThat(inbox.take()).isEmpty();
    }

    @Test
    void take_movesThemToDone() throws IOException {
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("a", 1_000));
        inbox.take();
        try (Stream<Path> pending = Files.list(dir.resolve("inbox"))) {
            assertThat(pending.filter(Files::isRegularFile)).isEmpty();
        }
        try (Stream<Path> done = Files.list(dir.resolve("inbox").resolve("done"))) {
            assertThat(done.map(p -> p.getFileName().toString()).toList()).hasSize(1).allMatch(n -> n.endsWith("-a.json"));
        }
    }

    @Test
    void take_anUnparsableFile_isMovedAsInvalidAndSkipped() throws IOException {
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("a", 2_000));
        Files.writeString(dir.resolve("inbox").resolve("1-bad.json"), "{nope");
        assertThat(inbox.take()).containsExactly(request("a", 2_000));
        assertThat(dir.resolve("inbox").resolve("done").resolve("1-bad.json.invalid")).exists();
    }

    /** A post being written is no request yet: take leaves its temporary file where it is. */
    @Test
    void take_leavesAPostBeingWrittenInPlace() throws IOException {
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("a", 1_000));
        Path partial = Files.writeString(dir.resolve("inbox").resolve(".tmp-123.part"), "{\"id\":");

        assertThat(inbox.take()).containsExactly(request("a", 1_000));
        assertThat(partial).exists();
    }

    @Test
    void post_isAtomic() throws IOException {
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("a", 1_000));
        try (Stream<Path> s = Files.list(dir.resolve("inbox"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("1000-a.json");
        }
    }

    @Test
    void take_aFileThatCannotBeMoved_staysAndTheOthersAreStillReturned() throws IOException {
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("a", 1_000));
        inbox.post(request("b", 2_000));
        inbox.post(request("c", 3_000));
        Path blocker = dir.resolve("inbox/done/2000-b.json");
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("x"), "x");
        assertThat(inbox.take()).containsExactly(request("a", 1_000), request("c", 3_000));
        assertThat(dir.resolve("inbox/2000-b.json")).exists();
    }

    @Test
    void post_anIdThatIsNotAFileNameToken_isRefused() {
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inbox.post(request("../x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A run folder opened by path may come from anyone. An {@code inbox} that is a link is not an inbox: taking from
     * it would move the JSON files of the folder it names (the Traceworks home's, say) into a {@code done} there.
     */
    @Test
    void take_fromAnInboxThatIsALink_leavesTheFilesOfTheFolderItNamesInPlace() throws IOException {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path file = Files.writeString(elsewhere.resolve("a.json"), "{}");
        Path inbox = Files.createSymbolicLink(dir.resolve("inbox"), elsewhere);

        assertThat(new FileInbox(inbox).take()).isEmpty();

        assertThat(file).exists();
        assertThat(elsewhere.resolve("done")).doesNotExist();
    }

    @Test
    void take_withADoneThatIsALink_movesNothingOutOfTheInbox() throws IOException {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("a", 1_000));
        Files.createSymbolicLink(dir.resolve("inbox").resolve("done"), elsewhere);

        assertThatThrownBy(inbox::take).isInstanceOf(UncheckedIOException.class).hasMessageContaining("done");

        try (Stream<Path> moved = Files.list(elsewhere)) {
            assertThat(moved).isEmpty();
        }
    }

    @Test
    void take_skipsAFileThatIsALink() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.json"), "{}");
        FileInbox inbox = new FileInbox(dir.resolve("inbox"));
        inbox.post(request("a", 2_000));
        Files.createSymbolicLink(dir.resolve("inbox").resolve("1-link.json"), outside);

        assertThat(inbox.take()).containsExactly(request("a", 2_000));

        assertThat(outside).exists();
        assertThat(dir.resolve("inbox").resolve("1-link.json")).isSymbolicLink();
    }

    @Test
    void post_toAnInboxThatIsALink_isRefused() throws IOException {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path inbox = Files.createSymbolicLink(dir.resolve("inbox"), elsewhere);

        assertThatThrownBy(() -> new FileInbox(inbox).post(request("a", 1_000)))
                .isInstanceOf(UncheckedIOException.class);

        try (Stream<Path> written = Files.list(elsewhere)) {
            assertThat(written).isEmpty();
        }
    }
}
