package traceworks.adapters.record;

import traceworks.core.port.SealStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code seals/} in the home, the allowed signers file, and the seal file of a package. */
class FileSealStoreTest {

    @TempDir
    Path home;
    @TempDir
    Path pkg;

    private FileSealStore store() {
        return new FileSealStore(home.resolve("seals"), home.resolve("allowed_signers"));
    }

    private static byte[] line(int n) {
        return ("{\"seq\":" + n + "}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void openLog_createsNothing_andReadsAnEmptyLog() {
        try (SealStore.Log log = store().openLog()) {
            assertThat(log.events().readAll()).isEmpty();
            assertThatThrownBy(() -> log.events().append(line(0))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(home.resolve("seals")).doesNotExist();
        assertThat(store().logLocation()).isEqualTo(home.resolve("seals").toString());
    }

    @Test
    void openLogForWrite_createsTheFolderForTheOwner_andAReaderSeesTheLines() throws IOException {
        try (SealStore.Log log = store().openLogForWrite()) {
            log.events().append(line(0));
            log.events().append(line(1));
        }

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("seals"))))
                .isEqualTo("rwx------");
        assertThat(Files.readString(home.resolve("seals/events.jsonl"))).isEqualTo("{\"seq\":0}\n{\"seq\":1}\n");
        try (SealStore.Log log = store().openLog()) {
            assertThat(log.events().readAll()).hasSize(2);
        }
    }

    @Test
    void aTornTail_isNotALine_andTheWriterDiscardsIt() throws IOException {
        try (SealStore.Log log = store().openLogForWrite()) {
            log.events().append(line(0));
        }
        Files.writeString(home.resolve("seals/events.jsonl"), "{\"seq\":0}\n{\"se");

        try (SealStore.Log log = store().openLog()) {
            assertThat(log.events().readAll()).hasSize(1);
        }
        try (SealStore.Log log = store().openLogForWrite()) {
            assertThat(log.events().discardTornTail()).hasSize(4);
        }
        assertThat(Files.readString(home.resolve("seals/events.jsonl"))).isEqualTo("{\"seq\":0}\n");
    }

    @Test
    void twoWriters_takeTurns() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<?> second;
            try (SealStore.Log first = store().openLogForWrite()) {
                second = pool.submit(() -> {
                    started.countDown();
                    try (SealStore.Log log = store().openLogForWrite()) {
                        log.events().append(line(1));
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150);
                assertThat(second.isDone()).isFalse();
                first.events().append(line(0));
            }
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(Files.readString(home.resolve("seals/events.jsonl"))).isEqualTo("{\"seq\":0}\n{\"seq\":1}\n");
    }

    /** A writer in another process takes turns too: the writer holds seals.lock in the OS, not only in this JVM. */
    @Test
    void aWriter_holdsTheLockFile_soAnotherProcessCannotTakeIt() throws Exception {
        try (SealStore.Log log = store().openLogForWrite()) {
            assertThat(OtherProcessLock.free(home.resolve("seals/seals.lock"))).isFalse();
        }
        assertThat(OtherProcessLock.free(home.resolve("seals/seals.lock"))).as("released on close").isTrue();
    }

    @Test
    void theAllowedSigners_areEmptyWithoutTheFile_andItsBytesWithIt() throws IOException {
        assertThat(store().allowedSigners()).isEmpty();
        assertThat(store().allowedSignersLocation()).isEqualTo(home.resolve("allowed_signers").toString());

        Files.writeString(home.resolve("allowed_signers"), "flo ssh-ed25519 AAAA\n");

        assertThat(store().allowedSigners()).hasValue("flo ssh-ed25519 AAAA\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void allowedSigners_thatCannotBeRead_orAreNoFile_orAreHuge_fail_theyAreNotTakenForEmpty() throws IOException {
        Files.createDirectories(home.resolve("allowed_signers"));
        assertThatThrownBy(() -> store().allowedSigners()).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("allowed_signers");
        Files.delete(home.resolve("allowed_signers"));

        Files.writeString(home.resolve("allowed_signers"), " ".repeat(FileSealStore.MAX_SIGNERS_BYTES + 1));
        assertThatThrownBy(() -> store().allowedSigners()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger");
    }

    @Test
    void theSealFileOfAPackage_isWrittenWhole_replaced_andRemoved() throws IOException {
        store().writeSeal(pkg, "one".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(pkg.resolve("traceworks.seal"))).isEqualTo("one");

        store().writeSeal(pkg, "two".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(pkg.resolve("traceworks.seal"))).isEqualTo("two");
        try (var files = Files.list(pkg)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("traceworks.seal");
        }
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(pkg.resolve("traceworks.seal"))))
                .isEqualTo("rw-r--r--");

        assertThat(store().removeSeal(pkg)).isTrue();
        assertThat(store().removeSeal(pkg)).isFalse();
        assertThat(pkg.resolve("traceworks.seal")).doesNotExist();
    }

    @Test
    void writingTheSeal_replacesALink_itDoesNotWriteThroughIt(@TempDir Path other) throws IOException {
        Path target = Files.writeString(other.resolve("target"), "untouched");
        Files.createSymbolicLink(pkg.resolve("traceworks.seal"), target);

        store().writeSeal(pkg, "seal".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(target)).isEqualTo("untouched");
        assertThat(Files.isSymbolicLink(pkg.resolve("traceworks.seal"))).isFalse();
    }
}
