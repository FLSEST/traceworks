package traceworks.adapters.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import traceworks.core.port.ScratchEntry;
import traceworks.core.port.ScratchListing;
import traceworks.core.port.StoredRun;
import traceworks.core.record.EventChain;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The scratch directory of a run in its folder, which a step can fill with anything. */
class FileStoredRunTest {

    @TempDir
    Path home;

    private StoredRun create() {
        return new FileRunStore(home.resolve("runs")).create("abcd-1", Instant.parse("2026-11-03T10:00:00Z"));
    }

    private Path scratch(StoredRun run) {
        return Path.of(run.scratchDirectory());
    }

    @Test
    void newRun_hasAnEmptyScratchDirectory_keptAcrossResume() throws Exception {
        Path dir;
        try (StoredRun run = create()) {
            dir = scratch(run);
            assertThat(dir).isDirectory().isEqualTo(Path.of(run.location()).resolve("scratch"));
            assertThat(run.listScratch()).isEmpty();
            Files.writeString(dir.resolve("kept.txt"), "x");
        }
        try (StoredRun again = new FileRunStore(home.resolve("runs")).open("abcd-1")) {
            assertThat(scratch(again)).isEqualTo(dir);
            assertThat(again.listScratch()).extracting(ScratchEntry::path).containsExactly("kept.txt");
        }
    }

    @Test
    void list_hasSizeAndHash_sortedByPath_forNestedFiles() throws Exception {
        try (StoredRun run = create()) {
            Path dir = scratch(run);
            Files.createDirectories(dir.resolve("out/deep"));
            Files.writeString(dir.resolve("out/deep/b.txt"), "bee");
            Files.writeString(dir.resolve("a.txt"), "a");

            List<ScratchEntry> entries = run.listScratch();

            assertThat(entries).extracting(ScratchEntry::path).containsExactly("a.txt", "out/deep/b.txt");
            assertThat(entries.get(1).size()).isEqualTo(3);
            assertThat(entries.get(1).sha256()).isEqualTo(EventChain.sha256Hex("bee".getBytes(StandardCharsets.UTF_8)));
            assertThat(entries.get(1).linkTarget()).isNull();
            assertThat(new String(run.readScratch("out/deep/b.txt"), StandardCharsets.UTF_8)).isEqualTo("bee");
        }
    }

    @Test
    void symlinkInScratch_isListedWithItsTarget_andNeverFollowed() throws Exception {
        try (StoredRun run = create()) {
            Path dir = scratch(run);
            Files.createSymbolicLink(dir.resolve("x"), Path.of("../events.jsonl"));
            Files.createSymbolicLink(dir.resolve("etc"), Path.of("/etc"));
            Files.createSymbolicLink(dir.resolve("dangling"), Path.of("/nonexistent/nowhere"));

            List<ScratchEntry> entries = run.listScratch();

            assertThat(entries).extracting(ScratchEntry::path).containsExactly("dangling", "etc", "x");
            assertThat(entries).allSatisfy(e -> {
                assertThat(e.isLink()).isTrue();
                assertThat(e.sha256()).isNull();
            });
            assertThat(entries.get(2).linkTarget()).isEqualTo("../events.jsonl");
            assertThat(entries).as("a linked directory is not entered").hasSize(3);
            assertThatThrownBy(() -> run.readScratch("x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> run.readScratch("etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readScratch_refusesPathsOutsideTheDirectory() throws Exception {
        try (StoredRun run = create()) {
            Files.writeString(scratch(run).resolve("ok"), "ok");
            for (String bad : new String[] {"../events.jsonl", "/etc/passwd", "a/../../events.jsonl", "", ".",
                "a//b", "ok/", "nothing"}) {
                assertThatThrownBy(() -> run.readScratch(bad)).as(bad).isInstanceOf(RuntimeException.class);
            }
            assertThatThrownBy(() -> run.readScratch(null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(run.readScratch("ok")).hasSize(2);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPipe_isSkipped_andNeverOpened() throws Exception {
        try (StoredRun run = create()) {
            Path fifo = scratch(run).resolve("pipe");
            Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
            org.junit.jupiter.api.Assumptions.assumeTrue(mk.waitFor() == 0);
            Files.writeString(scratch(run).resolve("file"), "x");

            assertThat(run.listScratch()).extracting(ScratchEntry::path).containsExactly("file");
            assertThatThrownBy(() -> run.readScratch("pipe")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void largeFile_isHashedWithoutBeingHeld_andReadIsCapped() throws Exception {
        try (StoredRun run = create()) {
            Path big = scratch(run).resolve("big.bin");
            try (RandomAccessFile file = new RandomAccessFile(big.toFile(), "rw")) {
                file.setLength(17L * 1024 * 1024);
            }

            ScratchEntry entry = run.listScratch().getFirst();

            assertThat(entry.size()).isEqualTo(17L * 1024 * 1024);
            assertThat(entry.sha256()).hasSize(64);
            assertThat(run.readScratch("big.bin")).hasSize((int) ScratchEntry.MAX_BLOB_BYTES + 1);
        }
    }

    @Test
    void moreThan10000Entries_theListingIsCutAndMarked() throws Exception {
        try (StoredRun run = create()) {
            Path dir = scratch(run);
            for (int i = 0; i < ScratchEntry.MAX_ENTRIES + 20; i++) {
                Files.createFile(dir.resolve("f" + i));
            }

            ScratchListing listing = run.list(ScratchListing.Known.NONE);
            assertThat(listing.entries()).hasSize(ScratchEntry.MAX_ENTRIES);
            assertThat(listing.truncated()).isTrue();
            assertThat(listing.entries().getLast().path()).as("the cut is deterministic: sorted walk")
                    .isEqualTo(java.util.stream.IntStream.range(0, ScratchEntry.MAX_ENTRIES + 20)
                            .mapToObj(i -> "f" + i).sorted().toList().get(ScratchEntry.MAX_ENTRIES - 1));
        }
    }

    /**
     * The walk holds at most 10 000 paths at a time, listed or waiting, so a step cannot make a listing hold every
     * name of a folder it filled. A folder with more children than there is room for keeps its first by name, also
     * when those are not all entries (two are empty folders here), and the listing says that it was cut. The walk
     * goes on after the folder: the file beside it waited already, and is listed.
     */
    @Test
    void aFolderWithMoreChildrenThanTheWalkHasRoomFor_keepsItsFirstByName_andTheListingIsMarked() throws Exception {
        try (StoredRun run = create()) {
            Path wide = Files.createDirectory(scratch(run).resolve("a-wide"));
            Files.createDirectory(wide.resolve("00-empty"));
            Files.createDirectory(wide.resolve("01-empty"));
            for (int i = 0; i < ScratchEntry.MAX_ENTRIES - 1; i++) {
                Files.createFile(wide.resolve(String.format("f%05d", i)));
            }
            Files.createFile(scratch(run).resolve("b.txt"));

            ScratchListing listing = run.list(ScratchListing.Known.NONE);

            assertThat(listing.truncated()).as("10 001 children, and room for 9 999 beside b.txt").isTrue();
            assertThat(listing.entries()).as("9 999 kept, two of them folders, and b.txt")
                    .hasSize(ScratchEntry.MAX_ENTRIES - 1 - 2 + 1);
            assertThat(listing.entries()).extracting(ScratchEntry::path)
                    .contains("a-wide/f00000", "a-wide/f09996", "b.txt").doesNotContain("a-wide/f09997");
            assertThat(run.list(ScratchListing.Known.NONE)).as("the cut is the same every time").isEqualTo(listing);
        }
    }

    @Test
    void aRunWithoutScratchDirectory_listsNothing() throws Exception {
        try (StoredRun run = create()) {
            Files.delete(scratch(run));
            assertThat(run.listScratch()).isEmpty();
        }
    }

    // ---- fix round 1: nothing is dropped ----

    /** A run folder from before scratch directories gets one before its resume mounts it. */
    @Test
    void prepareScratchDirectory_createsAMissingOne_andKeepsAnExistingOne() throws Exception {
        try (StoredRun run = create()) {
            Files.delete(scratch(run));

            assertThat(run.prepareScratchDirectory()).isEqualTo(run.scratchDirectory());
            assertThat(scratch(run)).isDirectory();
            Files.writeString(scratch(run).resolve("kept"), "x");
            assertThat(run.prepareScratchDirectory()).isEqualTo(run.scratchDirectory());
            assertThat(scratch(run).resolve("kept")).exists();
        }
    }

    /** A link or a file in its place would hand a container whatever it points to, writable: refused. */
    @Test
    void prepareScratchDirectory_refusesALinkOrAFileInItsPlace(@TempDir Path elsewhere) throws Exception {
        try (StoredRun run = create()) {
            Files.delete(scratch(run));
            Files.createSymbolicLink(scratch(run), elsewhere);
            assertThatThrownBy(run::prepareScratchDirectory).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a directory of its own");
            Files.delete(scratch(run));
            Files.writeString(scratch(run), "x");
            assertThatThrownBy(run::prepareScratchDirectory).isInstanceOf(IllegalStateException.class);
        }
    }

    /** A listing does not follow a link in the place of the scratch directory: nothing outside the run is listed. */
    @Test
    void list_ofAScratchDirectoryThatIsALink_isRefused(@TempDir Path elsewhere) throws Exception {
        Files.writeString(elsewhere.resolve("outside"), "x");
        try (StoredRun run = create()) {
            Files.delete(scratch(run));
            Files.createSymbolicLink(scratch(run), elsewhere);

            assertThatThrownBy(() -> run.list(ScratchListing.Known.NONE)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a directory");
        }
    }

    /** The time of a file is kept to the nanosecond, so a file rewritten within a second gets a time of its own. */
    @Test
    void list_keepsTheTimeOfAFileBelowTheSecond() throws Exception {
        try (StoredRun run = create()) {
            Path file = Files.writeString(scratch(run).resolve("a"), "abc");
            java.time.Instant at = java.time.Instant.parse("2026-11-14T10:00:00.5Z");
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(at));
            org.junit.jupiter.api.Assumptions.assumeTrue(Files.getLastModifiedTime(file).toInstant().equals(at),
                    "this file system keeps no fraction of a second in the time of a file");

            ScratchEntry entry = run.list(ScratchListing.Known.NONE).entries().getFirst();

            assertThat(entry.modified()).isEqualTo(at.getEpochSecond() * 1_000_000_000L + 500_000_000L);
        }
    }

    /** A start refused right after its run was created leaves no folder behind. */
    @Test
    void discard_removesTheFolderOfARunWithoutEvents_andReleasesItsLock() throws Exception {
        StoredRun run = create();
        Path folder = Path.of(run.location());
        Files.writeString(scratch(run).resolve("left"), "x");

        run.discard();
        run.close();

        assertThat(folder).doesNotExist();
        assertThat(new FileRunStore(home.resolve("runs")).runIds()).isEmpty();
        try (StoredRun again = create()) {
            assertThat(again.holdsWriterLock()).as("the same id can be created again").isTrue();
        }
    }

    @Test
    void discard_refusesARunWithEvents_andAFolderWithALinkIsRemovedWithoutFollowingIt(@TempDir Path elsewhere)
            throws Exception {
        Path kept = Files.writeString(elsewhere.resolve("kept"), "x");
        try (StoredRun run = create()) {
            Files.createSymbolicLink(scratch(run).resolve("link"), elsewhere);
            run.discard();
            assertThat(kept).as("a link is removed, never followed").exists();
        }
        try (StoredRun run = create()) {
            run.events().append("{\"seq\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(run::discard).isInstanceOf(IllegalStateException.class);
            assertThat(Path.of(run.location())).exists();
        }
    }

    private static void sh(String script, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("sh", "-c", script, "sh"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0, "sh could not create the file");
    }

    private static boolean root() {
        return "root".equals(System.getProperty("user.name"));
    }

    @Test
    void aBackslashName_isListedAndReadable() throws Exception {
        try (StoredRun run = create()) {
            sh("printf x > \"$1/a\\\\b\"", scratch(run).toString());

            ScratchListing listing = run.list(ScratchListing.Known.NONE);

            assertThat(listing.unreadable()).isEmpty();
            assertThat(listing.entries()).extracting(ScratchEntry::path).containsExactly("a\\b");
            assertThat(new String(run.readScratch("a\\b"), StandardCharsets.UTF_8)).isEqualTo("x");
        }
    }

    @Test
    void aNameThatIsNotUtf8_isListedAndReadableThroughItsPath() throws Exception {
        try (StoredRun run = create()) {
            sh("printf x > \"$1/$(printf 'a\\377b')\"", scratch(run).toString());

            ScratchListing listing = run.list(ScratchListing.Known.NONE);

            assertThat(listing.unreadable()).isEmpty();
            assertThat(listing.entries()).hasSize(1);
            assertThat(new String(run.readScratch(listing.entries().getFirst().path()), StandardCharsets.UTF_8))
                    .isEqualTo("x");
        }
    }

    @Test
    void anUnreadableDirectoryAndFile_areListedAsUnreadable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(root(), "root reads everything");
        try (StoredRun run = create()) {
            Path dir = scratch(run);
            Files.createDirectories(dir.resolve("hidden"));
            Files.writeString(dir.resolve("hidden/secret.txt"), "s");
            Files.writeString(dir.resolve("locked.txt"), "l");
            Files.writeString(dir.resolve("open.txt"), "o");
            sh("chmod 000 \"$1/hidden\" \"$1/locked.txt\"", dir.toString());
            try {
                ScratchListing listing = run.list(ScratchListing.Known.NONE);

                assertThat(listing.entries()).extracting(ScratchEntry::path).containsExactly("open.txt");
                assertThat(listing.unreadable()).extracting(ScratchListing.Unreadable::path)
                        .containsExactlyInAnyOrder("hidden", "locked.txt");
                assertThat(listing.unreadable()).allSatisfy(u -> assertThat(u.reason()).isEqualTo("permission denied"));
            } finally {
                sh("chmod 700 \"$1/hidden\"; chmod 600 \"$1/locked.txt\"", dir.toString());
            }
        }
    }

    @Test
    void theHostileNames_appearInTheRecordedEvent() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(root(), "root reads everything");
        try (StoredRun run = create()) {
            Path dir = scratch(run);
            sh("printf x > \"$1/a\\\\b\"; printf x > \"$1/$(printf 'u\\377')\"; mkdir \"$1/hidden\"; "
                    + "printf x > \"$1/locked\"; chmod 000 \"$1/hidden\" \"$1/locked\"", dir.toString());
            try {
                var blobs = new traceworks.core.testing.InMemoryBlobStore();
                var log = new traceworks.core.testing.InMemoryEventLog();
                var recorder = traceworks.core.record.RunRecorder.start(log, blobs,
                        () -> Instant.parse("2026-11-04T10:00:00Z"), "abcd-1",
                        new traceworks.core.model.WorkflowDefinition("wf", "Wf", null, List.of(),
                                null, null, null, null, null, null, null, null, null)).withScratch(run);

                recorder.emit(new traceworks.core.port.RunEvent.StepStarted(0, "s", "shell", 0,
                        false));
                recorder.emit(new traceworks.core.port.RunEvent.StepFinished(0, "s", "shell",
                        traceworks.core.model.StepStatus.COMPLETED, "i", 1, java.util.Map.of()));

                String event = log.readAll().stream().map(l -> new String(l, StandardCharsets.UTF_8))
                        .filter(l -> l.contains("\"step.files\"")).findFirst().orElseThrow();
                assertThat(event).contains("\"path\":\"a\\\\b\"").contains("\"unreadable\":[")
                        .contains("\"path\":\"hidden\"").contains("\"path\":\"locked\"")
                        .contains("permission denied").contains("\"removed\":[]");
                assertThat(event.indexOf("\"files\"")).isLessThan(event.indexOf("\"unreadable\""));
                assertThat(event.indexOf("\"unreadable\"")).isLessThan(event.indexOf("\"removed\""));
                assertThat(event).as("the name with byte 0xff is listed").contains("\"path\":\"u");
            } finally {
                sh("chmod 700 \"$1/hidden\"; chmod 600 \"$1/locked\"", dir.toString());
            }
        }
    }

    @Test
    void aFileOver1GiB_isNotHashed() throws Exception {
        try (StoredRun run = create()) {
            try (RandomAccessFile file = new RandomAccessFile(scratch(run).resolve("huge").toFile(), "rw")) {
                file.setLength(ScratchEntry.MAX_HASH_BYTES + 1);
            }

            ScratchEntry entry = run.list(ScratchListing.Known.NONE).entries().getFirst();

            assertThat(entry.size()).isEqualTo(ScratchEntry.MAX_HASH_BYTES + 1);
            assertThat(entry.sha256()).isNull();
            assertThat(entry.reason()).isEqualTo("too large to hash");
        }
    }

    @Test
    void aKnownHash_isReusedWithoutReading_whenSizeAndTimeAreTheSame() throws Exception {
        try (StoredRun run = create()) {
            Files.writeString(scratch(run).resolve("a"), "abc");
            ScratchEntry first = run.list(ScratchListing.Known.NONE).entries().getFirst();

            ScratchEntry again = run.list((path, size, modified) ->
                    path.equals("a") && size == 3 && modified == first.modified() ? "reused" : null)
                    .entries().getFirst();
            ScratchEntry other = run.list((path, size, modified) -> modified == first.modified() + 1 ? "x" : null)
                    .entries().getFirst();

            assertThat(again.sha256()).isEqualTo("reused");
            assertThat(other.sha256()).isEqualTo(first.sha256());
        }
    }

    @Test
    void readScratch_stopsAtTheGivenSize() throws Exception {
        try (StoredRun run = create()) {
            Files.writeString(scratch(run).resolve("a"), "abcdef");
            assertThat(new String(run.readScratch("a", 3), StandardCharsets.UTF_8)).isEqualTo("abc");
        }
    }

    // ---- fix round 2 ----

    private final traceworks.core.testing.InMemoryEventLog stepLog =
            new traceworks.core.testing.InMemoryEventLog();
    private traceworks.core.record.RunRecorder stepRecorder;
    private int stepNo;

    private com.fasterxml.jackson.databind.JsonNode finishStep(StoredRun run) throws Exception {
        if (stepRecorder == null) {
            stepRecorder = traceworks.core.record.RunRecorder.start(stepLog,
                    new traceworks.core.testing.InMemoryBlobStore(),
                    () -> Instant.parse("2026-11-04T10:00:00Z"), "abcd-1",
                    new traceworks.core.model.WorkflowDefinition("wf", "Wf", null, List.of(), null,
                            null, null, null, null, null, null, null, null)).withScratch(run);
        }
        int before = stepLog.readAll().size();
        stepRecorder.emit(new traceworks.core.port.RunEvent.StepStarted(stepNo, "s", "shell", 0,
                false));
        stepRecorder.emit(new traceworks.core.port.RunEvent.StepFinished(stepNo++, "s", "shell",
                traceworks.core.model.StepStatus.COMPLETED, "i", 1, java.util.Map.of()));
        List<byte[]> lines = stepLog.readAll();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode last = mapper.readTree(lines.getLast());
        return "step.files".equals(last.get("type").asText()) && lines.size() > before + 2 ? last : null;
    }

    @Test
    void aDirectoryThatBecomesUnreadable_doesNotMakeItsRecordedFilesRemoved() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(root(), "root reads everything");
        try (StoredRun run = create()) {
            Path dir = scratch(run);
            Files.createDirectories(dir.resolve("d"));
            Files.writeString(dir.resolve("d/f"), "x");
            assertThat(finishStep(run).get("files")).hasSize(1);
            sh("chmod 000 \"$1/d\"", dir.toString());
            try {
                var event = finishStep(run);

                assertThat(event).isNotNull();
                assertThat(event.get("unreadable")).hasSize(1);
                assertThat(event.get("unreadable").get(0).get("path").asText()).isEqualTo("d");
                assertThat(event.get("removed")).isEmpty();
            } finally {
                sh("chmod 700 \"$1/d\"", dir.toString());
            }
        }
    }

    @Test
    void anUnreadableScratchRoot_doesNotMakeRecordedFilesRemoved() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(root(), "root reads everything");
        try (StoredRun run = create()) {
            Path dir = scratch(run);
            Files.writeString(dir.resolve("top"), "x");
            assertThat(finishStep(run).get("files")).hasSize(1);
            sh("chmod 000 \"$1\"", dir.toString());
            try {
                var event = finishStep(run);

                assertThat(event).isNotNull();
                assertThat(event.get("unreadable")).hasSize(1);
                assertThat(event.get("unreadable").get(0).get("path").asText()).isEmpty();
                assertThat(event.get("removed")).isEmpty();
            } finally {
                sh("chmod 700 \"$1\"", dir.toString());
            }
        }
    }

    @Test
    void namesThatDecodeToTheSameText_areAllKept() throws Exception {
        try (StoredRun run = create()) {
            sh("for b in 377 376 375; do printf x > \"$1/$(printf \"a\\\\${b}b\")\"; done", scratch(run).toString());
            ScratchListing listing = run.list(ScratchListing.Known.NONE);
            org.junit.jupiter.api.Assumptions.assumeTrue(listing.unreadable().size() == 2,
                    "this JVM decodes the three names differently");

            var event = finishStep(run);

            assertThat(event.get("files")).hasSize(1);
            assertThat(event.get("unreadable")).hasSize(2);
        }
    }

    @Test
    void aVeryDeepTree_isListedWithoutRecursion() throws Exception {
        try (StoredRun run = create()) {
            Path deep = scratch(run);
            for (int i = 0; i < 1500; i++) {
                deep = Files.createDirectory(deep.resolve("d"));
            }
            Files.writeString(deep.resolve("leaf"), "x");
            Throwable[] failure = new Throwable[1];
            List<ScratchEntry>[] result = new List[1];
            Thread small = new Thread(null, () -> {
                try {
                    result[0] = run.list(ScratchListing.Known.NONE).entries();
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }, "small-stack", 128 * 1024);
            small.start();
            small.join();

            assertThat(failure[0]).isNull();
            assertThat(result[0]).hasSize(1);
        }
    }

    /**
     * Only the process that writes a run may discard it. A second handle on the same run, which holds no lock, is
     * refused, and the folder stays: the writer is still using it.
     */
    @Test
    void discard_byAHandleThatDoesNotHoldTheWriterLock_isRefused_andTheFolderStays() {
        try (StoredRun writer = create(); StoredRun reader = new FileRunStore(home.resolve("runs")).open("abcd-1")) {
            assertThatThrownBy(reader::discard).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("writer lock");

            assertThat(Path.of(writer.location())).isDirectory();
            assertThat(writer.holdsWriterLock()).isTrue();
        }
    }
}
