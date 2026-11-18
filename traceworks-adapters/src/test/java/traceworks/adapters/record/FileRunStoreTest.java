package traceworks.adapters.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import traceworks.core.port.AmbiguousRunException;
import traceworks.core.port.NoSuchRunException;
import traceworks.core.port.StoredRun;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRunStoreTest {

    @TempDir
    Path home;

    @Test
    void create_makesTheDateFolderOfTheStartInUtc() {
        try (StoredRun run = new FileRunStore(home.resolve("runs")).create("abcd-1", Instant.parse("2026-11-03T23:30:00Z"))) {
            Path folder = home.resolve("runs/2026-11-03/abcd-1");
            assertThat(folder).isDirectory();
            assertThat(run.location()).isEqualTo(folder.toString());
            assertThat(folder.resolve("events.jsonl")).exists();
            assertThat(folder.resolve("inbox/done")).isDirectory();
            assertThat(folder.resolve("blobs")).isDirectory();
            assertThat(run.holdsWriterLock()).isTrue();
        }
    }

    @Test
    void create_existingId_isRefused() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("abcd-1", Instant.parse("2026-11-03T10:00:00Z")).close();
        assertThatThrownBy(() -> store.create("abcd-1", Instant.parse("2026-11-04T10:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void open_byIdPrefixAndPath() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("abcd-1111", Instant.parse("2026-11-03T10:00:00Z")).close();
        store.create("ffff-2222", Instant.parse("2026-11-03T11:00:00Z")).close();
        assertThat(store.open("abcd-1111").runId()).isEqualTo("abcd-1111");
        assertThat(store.open("abcd").runId()).isEqualTo("abcd-1111");
        assertThat(store.open(home.resolve("runs/2026-11-03/ffff-2222").toString()).runId()).isEqualTo("ffff-2222");
    }

    @Test
    void inStore_isTrueForARunOfThisHome_andFalseForARunFolderElsewhere() throws Exception {
        FileRunStore store = new FileRunStore(home.resolve("home").resolve("runs"));
        store.create("abcd-1111", Instant.parse("2026-11-03T10:00:00Z")).close();
        Path copy = Files.createDirectories(home.resolve("elsewhere/abcd-1111"));
        Files.writeString(copy.resolve("events.jsonl"), "");

        try (StoredRun byId = store.open("abcd");
             StoredRun byPath = store.open(home.resolve("home/runs/2026-11-03/abcd-1111").toString());
             StoredRun copied = store.open(copy.toString())) {
            assertThat(byId.inStore()).isTrue();
            assertThat(byPath.inStore()).isTrue();
            assertThat(copied.inStore()).isFalse();
        }
    }

    @Test
    void open_shortPrefix_isRefused() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("abcd-1111", Instant.now()).close();
        assertThatThrownBy(() -> store.open("abc")).isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(NoSuchRunException.class).hasMessageContaining("4");
    }

    @Test
    void open_ambiguousPrefix_namesTheCandidates() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("abcd-1111", Instant.now()).close();
        store.create("abcd-2222", Instant.now()).close();
        assertThatThrownBy(() -> store.open("abcd")).isInstanceOf(AmbiguousRunException.class)
                .hasMessageContaining("abcd-1111").hasMessageContaining("abcd-2222");
    }

    @Test
    void open_unknown_throwsNoSuchRun() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        assertThatThrownBy(() -> store.open("nothing")).isInstanceOf(NoSuchRunException.class);
        assertThatThrownBy(() -> store.open(home.resolve("nowhere").toString())).isInstanceOf(NoSuchRunException.class);
    }

    @Test
    void runIds_newestFirst() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        try (var a = store.create("aaaa", Instant.parse("2026-11-02T10:00:00Z"));
             var b = store.create("bbbb", Instant.parse("2026-11-03T08:00:00Z"));
             var c = store.create("cccc", Instant.parse("2026-11-03T09:00:00Z"));
             var d = store.create("dddd", Instant.parse("2026-11-03T07:00:00Z"))) {
            a.events().append(event("2026-11-02T10:00:00.000Z"));
            b.events().append(event("2026-11-03T08:00:00.000Z"));
            c.events().append(event("2026-11-03T09:00:00.000Z"));
            d.events().append(event("2026-11-03T07:00:00.000Z"));
        }
        assertThat(store.runIds()).containsExactly("cccc", "bbbb", "dddd", "aaaa");
    }

    private static byte[] event(String at) {
        return ("{\"seq\":0,\"at\":\"" + at + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writerAlive_whileAnotherHandleHoldsTheLock() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        try (StoredRun writer = store.create("abcd-1", Instant.now())) {
            try (StoredRun other = store.open("abcd-1")) {
                assertThat(other.holdsWriterLock()).isFalse();
                assertThat(other.writerAlive()).isTrue();
                assertThat(other.tryLockWriter()).isFalse();
                // the probe must not have disturbed the writer
                assertThat(writer.holdsWriterLock()).isTrue();
                assertThat(other.writerAlive()).isTrue();
            }
            assertThat(writer.writerAlive()).isTrue();
        }
        try (StoredRun other = store.open("abcd-1")) {
            assertThat(other.writerAlive()).isFalse();
        }
    }

    @Test
    void tryLockWriter_failsWhileHeld_andSucceedsAfterClose() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        StoredRun writer = store.create("abcd-1", Instant.now());
        StoredRun other = store.open("abcd-1");
        assertThat(other.tryLockWriter()).isFalse();
        writer.close();
        assertThat(other.tryLockWriter()).isTrue();
        assertThat(other.holdsWriterLock()).isTrue();
        other.events().append("x".getBytes(StandardCharsets.UTF_8));
        other.close();
        assertThat(other.holdsWriterLock()).isFalse();
    }

    /**
     * A writer in another process: while it holds run.lock this JVM gets no lock, and once it is gone this JVM takes
     * the lock and sees no writer alive.
     */
    @Test
    void tryLockWriter_failsWhileAnotherProcessHoldsTheLock_andSucceedsOnceItIsGone() throws Exception {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("abcd-1", Instant.now()).close();
        StoredRun run = store.open("abcd-1");
        try (OtherProcessLock holder = OtherProcessLock.hold(Path.of(run.location()).resolve("run.lock"))) {
            assertThat(run.tryLockWriter()).isFalse();
            assertThat(run.writerAlive()).isTrue();
        }

        assertThat(run.writerAlive()).isFalse();
        assertThat(run.tryLockWriter()).isTrue();
        run.close();
    }

    /** A run folder without run.lock has no writer, and a look at it makes no lock file. */
    @Test
    void writerAlive_isFalse_forARunFolderWithoutItsLockFile() throws Exception {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("abcd-1", Instant.now()).close();
        try (StoredRun run = store.open("abcd-1")) {
            Path lock = Path.of(run.location()).resolve("run.lock");
            Files.delete(lock);

            assertThat(run.writerAlive()).isFalse();
            assertThat(lock).doesNotExist();
        }
    }

    /**
     * A lock another process holds: every try opens the lock file and closes it again. Where the channel stayed
     * open, the process would hold a descriptor of run.lock for each try.
     */
    @Test
    void tryLockWriter_whileAnotherProcessHoldsTheLock_leavesNoChannelOpen() throws Exception {
        Path descriptors = Path.of("/proc/self/fd");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(descriptors), "no /proc/self/fd here");
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("abcd-1", Instant.now()).close();
        try (StoredRun run = store.open("abcd-1")) {
            Path lock = Path.of(run.location()).resolve("run.lock").toRealPath();
            try (OtherProcessLock holder = OtherProcessLock.hold(lock)) {
                for (int i = 0; i < 20; i++) {
                    assertThat(run.tryLockWriter()).isFalse();
                }

                assertThat(openDescriptorsOf(descriptors, lock)).as("descriptors of %s", lock).isZero();
            }
        }
    }

    /** How many descriptors of this process name {@code file}. */
    private static long openDescriptorsOf(Path descriptors, Path file) throws Exception {
        try (var entries = Files.list(descriptors)) {
            return entries.filter(entry -> {
                try {
                    return Files.readSymbolicLink(entry).equals(file);
                } catch (Exception gone) {
                    // The descriptor of the listing itself, or one closed meanwhile
                    return false;
                }
            }).count();
        }
    }

    @Test
    void manifestAndView_roundTrip() throws Exception {
        try (StoredRun run = new FileRunStore(home.resolve("runs")).create("abcd-1", Instant.parse("2026-11-03T10:00:00Z"))) {
            assertThat(run.readManifest()).isEmpty();
            run.writeManifest("{}".getBytes(StandardCharsets.UTF_8));
            run.writeView("<html>\u00e9\u4e2d</html>");
            assertThat(run.readManifest()).hasValueSatisfying(m -> assertThat(new String(m)).isEqualTo("{}"));
            assertThat(Files.readString(Path.of(run.location()).resolve("run.html"))).isEqualTo("<html>\u00e9\u4e2d</html>");
        }
    }

    @Test
    void secondHandleThroughASymlinkedPath_neverDisturbsTheFirstHandlesLock() throws Exception {
        Path link = Files.createSymbolicLink(home.getParent().resolve(home.getFileName() + "-link"), home);
        try {
            StoredRun first = new FileRunStore(home.resolve("runs")).create("abcd-1", Instant.now());
            try (StoredRun second = new FileRunStore(link.resolve("runs")).open("abcd-1")) {
                for (int i = 0; i < 3; i++) {
                    assertThat(second.tryLockWriter()).isFalse();
                    assertThat(second.writerAlive()).isTrue();
                }
                assertThat(first.holdsWriterLock()).isTrue();
                first.events().append("x".getBytes(StandardCharsets.UTF_8));
            }
            // the lock is still really held by the OS: another process cannot take it
            assertThat(OtherProcessLock.free(Path.of(first.location()).resolve("run.lock"))).isFalse();
            first.close();
        } finally {
            Files.deleteIfExists(link);
        }
    }


    /**
     * A reference with a symbolic link and {@code ..} names one folder to the file system (which follows the link
     * first) and another one as text (which removes {@code ..} first). The folder that was checked is the one opened.
     */
    @Test
    void open_byAPathThroughALinkAndDotDot_opensTheFolderTheFileSystemNames() throws Exception {
        FileRunStore store = new FileRunStore(home.resolve("home").resolve("runs"));
        Path real = Files.createDirectories(home.resolve("far/away/run-0001"));
        Files.writeString(real.resolve("events.jsonl"), "");
        Files.createDirectories(home.resolve("far/away/sub"));
        Files.createDirectories(home.resolve("near"));
        Path link = Files.createSymbolicLink(home.resolve("near/link"), home.resolve("far/away/sub"));
        Path asText = Files.createDirectories(home.resolve("near/run-0001"));
        Files.writeString(asText.resolve("events.jsonl"), "");

        try (StoredRun opened = store.open(link + "/../run-0001")) {
            assertThat(opened.location()).isEqualTo(real.toRealPath().toString());
            assertThat(opened.runId()).isEqualTo("run-0001");
        }
    }

    /** A bare id never resolves against the working directory, where a step may have left a folder of that name. */
    @Test
    void aReference_isAFolderOnlyWhenItIsWrittenAsAPath() {
        assertThat(FileRunStore.writtenAsPath("abcd-1111")).isFalse();
        assertThat(FileRunStore.writtenAsPath("abcd")).isFalse();
        assertThat(FileRunStore.writtenAsPath("./abcd-1111")).isTrue();
        assertThat(FileRunStore.writtenAsPath("/media/usb/abcd-1111")).isTrue();
        assertThat(FileRunStore.writtenAsPath("copies/abcd-1111")).isTrue();
        assertThat(FileRunStore.writtenAsPath(".")).isTrue();
        assertThat(FileRunStore.writtenAsPath("..")).isTrue();
    }

    /** A path names a run only when the folder holds a record: any other directory is no run. */
    @Test
    void open_byThePathOfADirectoryWithoutARecord_throwsNoSuchRun() throws Exception {
        FileRunStore store = new FileRunStore(home.resolve("home").resolve("runs"));
        Path plain = Files.createDirectories(home.resolve("project/abcd-1111"));
        Path eventsIsAFolder = Files.createDirectories(home.resolve("odd/abcd-2222"));
        Files.createDirectories(eventsIsAFolder.resolve("events.jsonl"));

        assertThatThrownBy(() -> store.open(plain.toString())).isInstanceOf(NoSuchRunException.class);
        assertThatThrownBy(() -> store.open(eventsIsAFolder.toString())).isInstanceOf(NoSuchRunException.class);
    }

    /** A run id is the name of a folder that every listing shows: it does not start with a dot. */
    @Test
    void create_withAnIdThatStartsWithADot_isRefused_andMakesNoFolder() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));

        for (String id : List.of(".abcd-1", ".", "..", "", "a/b", "a\\b")) {
            assertThatThrownBy(() -> store.create(id, Instant.parse("2026-11-03T10:00:00Z"))).as(id)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a usable run id");
        }
        assertThat(home.resolve("runs")).doesNotExist();
    }

    /**
     * A run without a first event (it stopped before it wrote one) has no start in its record: it is placed among
     * the runs of its day by the time its folder was last changed.
     */
    @Test
    void runIds_placeARunWithoutEvents_byTheTimeItsFolderWasLastChanged() throws Exception {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        Instant day = Instant.parse("2026-11-03T00:00:00Z");
        try (var early = store.create("early-no-events", day);
             var middle = store.create("middle-with-event", day);
             var late = store.create("late-no-events", day)) {
            middle.events().append(event("2026-11-03T08:00:00.000Z"));
        }
        Path runs = home.resolve("runs/2026-11-03");
        Files.setLastModifiedTime(runs.resolve("early-no-events"),
                FileTime.from(Instant.parse("2026-11-03T07:00:00Z")));
        Files.setLastModifiedTime(runs.resolve("late-no-events"),
                FileTime.from(Instant.parse("2026-11-03T09:00:00Z")));

        assertThat(store.runIds()).containsExactly("late-no-events", "middle-with-event", "early-no-events");
    }
}
