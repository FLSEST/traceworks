package traceworks.adapters.record;

import traceworks.core.port.RegisterStore;
import traceworks.core.testing.EarlierRegister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code registers/<key>/<project>/} in the home: a log, blobs, state.json and report.html, with one lock per
 * register, which is one per key and project.
 */
class FileRegisterStoreTest {

    @TempDir
    Path home;

    private FileRegisterStore store() {
        return new FileRegisterStore(home.resolve("registers"));
    }

    private static byte[] line(int n) {
        return ("{\"seq\":" + n + "}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void open_createsNothing_andReadsAnEmptyLog() {
        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            assertThat(opened.key()).isEqualTo("arch");
            assertThat(opened.events().readAll()).isEmpty();
            assertThat(opened.state()).isEmpty();
            assertThat(opened.blobs().get("a".repeat(64))).isEmpty();
        }
        assertThat(home.resolve("registers")).doesNotExist();
        assertThat(store().keys()).isEmpty();
    }

    @Test
    void openForWrite_createsTheFolderForTheOwner_andTheLogAndBlobsAndStateLiveInIt() throws IOException {
        try (RegisterStore.Opened opened = store().openForWrite("arch", "shop")) {
            opened.events().append(line(0));
            String sha = opened.blobs().put("blob".getBytes(StandardCharsets.UTF_8)).sha256();
            opened.writeState("{\"state\":1}\n".getBytes(StandardCharsets.UTF_8));
            opened.writeView("<html>".getBytes(StandardCharsets.UTF_8));

            Path dir = home.resolve("registers").resolve("arch").resolve("shop");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("registers"))))
                    .isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.getParent())))
                    .as("the folder of the key").isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
            assertThat(Files.readString(dir.resolve("events.jsonl"))).isEqualTo("{\"seq\":0}\n");
            assertThat(dir.resolve("blobs").resolve(sha)).exists();
            assertThat(Files.readString(dir.resolve("state.json"))).isEqualTo("{\"state\":1}\n");
            assertThat(Files.readString(dir.resolve("report.html"))).isEqualTo("<html>");
            assertThat(opened.state()).contains("{\"state\":1}\n".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(store().keys()).containsExactly("arch");
        assertThat(store().projects("arch")).containsExactly("shop");
        try (RegisterStore.Opened again = store().open("arch", "shop")) {
            assertThat(again.events().readAll()).hasSize(1);
            assertThat(again.project()).isEqualTo("shop");
            assertThat(again.location()).isEqualTo(home.resolve("registers/arch/shop").toString());
        }
    }

    /** Two projects of one key are two registers: each has its folder, its log, its blobs and its state. */
    @Test
    void twoProjectsOfOneKey_haveAFolderEach_andShareNothing() throws IOException {
        try (RegisterStore.Opened shop = store().openForWrite("arch", "shop")) {
            shop.events().append(line(0));
            shop.blobs().put("of the shop".getBytes(StandardCharsets.UTF_8));
            shop.writeState("shop".getBytes(StandardCharsets.UTF_8));
        }
        try (RegisterStore.Opened web = store().openForWrite("arch", "web")) {
            assertThat(web.events().readAll()).as("the log of the other project is not this one's").isEmpty();
            assertThat(web.state()).isEmpty();
            web.events().append(line(0));
            web.events().append(line(1));
        }

        assertThat(store().keys()).containsExactly("arch");
        assertThat(store().projects("arch")).containsExactly("shop", "web");
        try (Stream<Path> folders = Files.list(home.resolve("registers/arch"))) {
            assertThat(folders.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("shop", "web");
        }
        assertThat(Files.readString(home.resolve("registers/arch/shop/events.jsonl"))).isEqualTo("{\"seq\":0}\n");
        assertThat(Files.readString(home.resolve("registers/arch/web/events.jsonl")))
                .isEqualTo("{\"seq\":0}\n{\"seq\":1}\n");
        try (Stream<Path> blobs = Files.list(home.resolve("registers/arch/web/blobs"))) {
            assertThat(blobs).isEmpty();
        }
    }

    /**
     * The lock is per key and project: a writer of one project does not wait for a writer of another project of the
     * same key, and waits for a writer of its own.
     */
    @Test
    void theLockIsPerKeyAndProject() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (RegisterStore.Opened shop = store().openForWrite("arch", "shop")) {
            shop.events().append(line(0));
            Future<Integer> other = pool.submit(() -> {
                try (RegisterStore.Opened web = store().openForWrite("arch", "web")) {
                    web.events().append(line(0));
                    return web.events().readAll().size();
                }
            });
            assertThat(other.get(30, TimeUnit.SECONDS)).as("the other project was written while this one is held")
                    .isEqualTo(1);
            CountDownLatch asked = new CountDownLatch(1);
            Future<Integer> same = pool.submit(() -> {
                asked.countDown();
                try (RegisterStore.Opened again = store().openForWrite("arch", "shop")) {
                    return again.events().readAll().size();
                }
            });
            assertThat(asked.await(30, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> same.get(300, TimeUnit.MILLISECONDS)).as("a writer of the same project waits")
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            shop.events().append(line(1));
            // closing lets the waiting writer in, which then sees both lines
            shop.close();
            assertThat(same.get(30, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void writeState_isAtomic_noTempFileStays() throws IOException {
        try (RegisterStore.Opened opened = store().openForWrite("arch", "shop")) {
            opened.writeState("one".getBytes(StandardCharsets.UTF_8));
            opened.writeState("two".getBytes(StandardCharsets.UTF_8));
        }
        try (Stream<Path> files = Files.list(home.resolve("registers").resolve("arch").resolve("shop"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("blobs", "state.json",
                    "register.lock");
        }
    }

    @Test
    void aReaderCannotWrite() {
        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            assertThatThrownBy(() -> opened.writeState(new byte[0])).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("opened for reading");
            assertThatThrownBy(() -> opened.events().append(line(0))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> opened.writeView(new byte[0])).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("opened for reading");
        }
    }

    @Test
    void anInvalidKey_isRefused() {
        for (String bad : List.of("A", "a/b", "..", ".x", "", "a b")) {
            assertThatThrownBy(() -> store().open(bad, "shop")).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("register key");
            assertThatThrownBy(() -> store().openForWrite(bad, "shop")).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A project key that would leave the folder of its key, or name none, is refused, and nothing is created. */
    @Test
    void aProjectKeyThatIsNone_isRefused_andCreatesNothing() {
        List<String> bad = new ArrayList<>(List.of("..", "a/b", "../other", "Shop", "", " ", ".hidden", "a b", "/abs"));
        bad.add(null);
        for (String project : bad) {
            assertThatThrownBy(() -> store().open("arch", project)).as(String.valueOf(project))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project key");
            assertThatThrownBy(() -> store().openForWrite("arch", project)).as(String.valueOf(project))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project key");
        }
        assertThat(home.resolve("registers")).doesNotExist();
    }

    @Test
    void twoThreads_appendInTurn() throws Exception {
        int perThread = 50;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            writers.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    try (RegisterStore.Opened opened = store().openForWrite("arch", "shop")) {
                        int next = opened.events().readAll().size();
                        opened.events().append(line(next));
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> writer : writers) {
            writer.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            List<byte[]> lines = opened.events().readAll();
            assertThat(lines).hasSize(2 * perThread);
            for (int i = 0; i < lines.size(); i++) {
                assertThat(new String(lines.get(i), StandardCharsets.UTF_8)).isEqualTo("{\"seq\":" + i + "}");
            }
        }
    }

    /** A second JVM appends under the same lock: the file lock serialises processes, not only threads. */
    @Test
    void twoProcesses_appendInTurn() throws Exception {
        String java = ProcessHandle.current().info().command().orElse(null);
        assumeTrue(java != null, "the java binary of this JVM is not known");
        String classPath = System.getProperty("java.class.path");
        int perProcess = 30;
        List<Process> processes = new ArrayList<>();
        for (int p = 0; p < 2; p++) {
            processes.add(new ProcessBuilder(java, "-cp", classPath, Writer.class.getName(),
                    home.resolve("registers").toString(), String.valueOf(perProcess)).inheritIO().start());
        }
        for (Process process : processes) {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the writer process ended").isTrue();
            assertThat(process.exitValue()).isZero();
        }

        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            List<byte[]> lines = opened.events().readAll();
            assertThat(lines).hasSize(2 * perProcess);
            for (int i = 0; i < lines.size(); i++) {
                assertThat(new String(lines.get(i), StandardCharsets.UTF_8)).isEqualTo("{\"seq\":" + i + "}");
            }
        }
    }

    /** The writer of {@link #twoProcesses_appendInTurn}: appends the next seq under the lock, N times. */
    public static final class Writer {
        public static void main(String[] args) {
            FileRegisterStore store = new FileRegisterStore(Path.of(args[0]));
            for (int i = 0; i < Integer.parseInt(args[1]); i++) {
                try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
                    int next = opened.events().readAll().size();
                    opened.events().append(line(next));
                }
            }
        }
    }

    /**
     * The registers are the folders named like a register key. Whatever else is in the directory (a file, a folder
     * with another kind of name) is not a register, and every key that is listed can be opened.
     */
    @Test
    void keys_listOnlyTheFoldersNamedLikeARegisterKey() throws IOException {
        try (RegisterStore.Opened opened = store().openForWrite("arch", "shop")) {
            opened.events().append(line(0));
        }
        Path registers = home.resolve("registers");
        Files.createDirectories(registers.resolve("security"));
        Files.createDirectories(registers.resolve("Not A Key"));
        Files.createDirectories(registers.resolve(".tmp-arch"));
        Files.writeString(registers.resolve("notes"), "a file named like a key");

        List<String> keys = store().keys();

        assertThat(keys).containsExactly("arch", "security");
        for (String key : keys) {
            store().open(key, "shop").close();
        }
    }

    /**
     * A project is its folder: the projects of a key are the folders in it that are named like a project key, with
     * or without a log. So a project whose log is gone still counts, and the only project a key has never becomes
     * another one without a word. A file and a folder with another kind of name are no project.
     */
    @Test
    void projects_areTheFoldersNamedLikeAProjectKey_withOrWithoutALog() throws IOException {
        for (String project : List.of("web", "shop")) {
            try (RegisterStore.Opened opened = store().openForWrite("arch", project)) {
                opened.events().append(line(0));
            }
        }
        store().openForWrite("arch", "never-written").close();
        Path arch = home.resolve("registers/arch");
        Files.createDirectories(arch.resolve("Not A Key"));
        Files.createDirectories(arch.resolve(".tmp"));
        Files.writeString(arch.resolve("notes"), "a file named like a key");

        assertThat(store().projects("arch")).containsExactly("never-written", "shop", "web");

        Files.delete(arch.resolve("web/events.jsonl"));

        assertThat(store().projects("arch")).as("a project whose log is gone still counts")
                .containsExactly("never-written", "shop", "web");
        assertThat(store().projects("security")).as("a key without a folder").isEmpty();
        assertThatThrownBy(() -> store().projects("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Once no register from before lies at the key, a folder named blobs is a project's like any other. */
    @Test
    void aFolderNamedBlobs_isAProject_whereNoRegisterFromBeforeLies() throws IOException {
        Files.createDirectories(arch().resolve("blobs"));

        assertThat(store().unassigned("arch")).isFalse();
        assertThat(store().projects("arch")).containsExactly("blobs");
    }

    /**
     * Reproduced 2026-11-21: the log of a register from before was gone, and its blobs folder was listed as a
     * project, so {@code render --project blobs} wrote into it. A folder named blobs that holds blobs is what such
     * a register left, with or without its log; the folder of a project holds none beside its log.
     */
    @Test
    void theBlobsFolderARegisterFromBeforeLeft_isNoProject_alsoWhenItsLogIsGone() throws IOException {
        EarlierRegister.writeTo(arch());
        Files.delete(arch().resolve("events.jsonl"));

        assertThat(store().unassigned("arch")).isFalse();
        assertThat(store().projects("arch")).as("the leftover blobs are no project").isEmpty();

        try (RegisterStore.Opened blobs = store().openForWrite("other", "blobs")) {
            blobs.events().append(line(0));
            blobs.blobs().put("a blob".getBytes(StandardCharsets.UTF_8));
        }
        Files.delete(home.resolve("registers/other/blobs/events.jsonl"));

        assertThat(store().projects("other")).as("a project named blobs, whose log is gone, still counts")
                .containsExactly("blobs");
    }

    // ---- a register from before registers had projects, and its assignment to one ----

    private Path arch() {
        return home.resolve("registers").resolve("arch");
    }

    private List<String> entries(Path folder) throws IOException {
        try (Stream<Path> children = Files.list(folder)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** The register of a project holds everything the register from before held, and its key folder nothing else. */
    private void assertAssignedTo(String project) throws IOException {
        Path dir = arch().resolve(project);
        assertThat(Files.readAllBytes(dir.resolve("events.jsonl"))).startsWith(
                String.join("\n", EarlierRegister.lines().stream().map(l -> new String(l, StandardCharsets.UTF_8))
                        .toList()).getBytes(StandardCharsets.UTF_8));
        for (String blob : EarlierRegister.blobs().keySet()) {
            assertThat(dir.resolve("blobs").resolve(blob)).as(project).isRegularFile();
        }
        assertThat(dir.resolve("state.json")).isRegularFile();
        assertThat(dir.resolve("report.html")).isRegularFile();
        assertThat(dir.resolve("register.lock")).isRegularFile();
        assertThat(store().unassigned("arch")).as(project).isFalse();
        assertThat(store().projects("arch")).contains(project);
        assertThat(Files.isRegularFile(arch().resolve("events.jsonl")) && !project.equals("events.jsonl"))
                .as("no log is left in the folder of the key").isFalse();
    }

    @Test
    void aRegisterFromBeforeProjects_isDetected_andIsTheRegisterOfNoProject() throws IOException {
        EarlierRegister.writeTo(arch());
        try (RegisterStore.Opened shop = store().openForWrite("other", "shop")) {
            shop.events().append(line(0));
        }

        assertThat(store().unassigned("arch")).isTrue();
        assertThat(store().unassigned("other")).as("a key with registers of projects only").isFalse();
        assertThat(store().unassigned("none")).as("a key without a folder").isFalse();
        assertThat(store().keys()).containsExactly("arch", "other");
        assertThat(store().projects("arch")).as("its blobs folder is no project").isEmpty();
        try (RegisterStore.Opened asAProject = store().open("arch", "blobs")) {
            assertThat(asAProject.events().readAll()).isEmpty();
        }
        assertThatThrownBy(() -> store().unassigned("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Reproduced 2026-11-21: a folder {@code .assigning} that held nothing but a lock made its key a register from
     * before for every reader, and every command on the registers of the key was refused. A staging folder without
     * a log that has begun is nothing: no reader takes the key for a register from before because of it, and it is
     * removed when asked.
     */
    @Test
    void aStagingFolderWithoutABegunLog_isNothingToAReader_andIsRemovedWhenAsked() throws IOException {
        try (RegisterStore.Opened shop = store().openForWrite("arch", "shop")) {
            shop.events().append(line(0));
        }
        Path staging = Files.createDirectories(arch().resolve(".assigning"));
        Files.createFile(staging.resolve("register.lock"));

        assertThat(store().unassigned("arch")).as("a lock alone").isFalse();
        assertThat(store().projects("arch")).containsExactly("shop");
        assertThatThrownBy(() -> store().openUnassigned("arch")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store().openUnassignedForWrite("arch")).isInstanceOf(IllegalStateException.class);
        assertThat(entries(staging)).as("a writer that is refused leaves it as it was").containsExactly(
                "register.lock");

        Files.createFile(staging.resolve("events.jsonl"));
        assertThat(store().unassigned("arch")).as("a log without a line").isFalse();

        store().clearStaging("arch");

        assertThat(entries(arch())).as("the staging folder is gone, and nothing else").containsExactly("shop");
        store().clearStaging("arch");
        store().clearStaging("none");
        assertThat(entries(arch())).containsExactly("shop");
    }

    /** What is no leftover of an assignment stays where it is: a log that has begun, and anything else in the folder. */
    @Test
    void clearStaging_removesNoLogThatHasBegun_andNothingItDoesNotKnow() throws IOException {
        EarlierRegister.writeTo(arch());
        Path staging = Files.createDirectories(arch().resolve(".assigning"));
        for (String entry : List.of("blobs", "state.json", "report.html", "events.jsonl", "register.lock")) {
            Files.move(arch().resolve(entry), staging.resolve(entry));
        }

        store().clearStaging("arch");

        assertThat(store().unassigned("arch")).as("an assignment that was cut off in its move").isTrue();
        assertThat(entries(staging)).containsExactly("blobs", "events.jsonl", "register.lock", "report.html",
                "state.json");

        Files.delete(staging.resolve("events.jsonl"));
        assertThat(store().unassigned("arch")).as("its log is gone: no register from before lies there").isFalse();
        store().clearStaging("arch");
        assertThat(entries(staging)).as("its blobs and its files are not removed").containsExactly("blobs",
                "report.html", "state.json");
    }

    // ---- a staging folder beside a register at the key moves nothing and locks nothing by itself ----

    /** Every folder and every file below {@code root}, the files with the SHA-256 of their bytes. */
    private static java.util.SortedMap<String, String> tree(Path root) throws IOException {
        java.util.SortedMap<String, String> tree = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                tree.put(root.relativize(path).toString(), Files.isDirectory(path) ? "folder"
                        : traceworks.core.record.EventChain.sha256Hex(Files.readAllBytes(path)));
            }
        }
        return tree;
    }

    /**
     * Reproduced 2026-11-21: the writer of a register from before moved the whole register into a staging folder
     * that lay beside it as soon as it was opened, before any check. An assignment that was then refused left
     * nothing at the key. A writer that is closed without an assignment leaves every file at its place.
     */
    @Test
    void aWriterOfARegisterFromBefore_movesNothing_untilItAssigns_alsoBesideAStagingFolder() throws IOException {
        for (boolean withLock : List.of(false, true)) {
            EarlierRegister.writeTo(arch());
            Path staging = Files.createDirectories(arch().resolve(".assigning"));
            if (withLock) {
                Files.createFile(staging.resolve("register.lock"));
            }
            java.util.SortedMap<String, String> before = tree(arch());

            try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
                assertThat(opened.events().readAll()).hasSize(EarlierRegister.lines().size());
                assertThat(opened.state()).contains(EarlierRegister.state());
                assertThat(opened.blobs().get(EarlierRegister.blobs().keySet().iterator().next())).isPresent();
                assertThat(opened.placeTaken("shop")).isFalse();
                assertThat(tree(arch()).keySet()).as("while it is open, the lock of the assignment and nothing"
                        + " else is new").containsExactlyInAnyOrderElementsOf(with(before.keySet(), ".assign.lock"));
            }

            assertThat(tree(arch())).as("with a lock in the staging folder: " + withLock).isEqualTo(before);
            try (Stream<Path> walk = Files.walk(arch())) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static List<String> with(java.util.Collection<String> names, String one) {
        List<String> all = new ArrayList<>(names);
        all.add(one);
        return all;
    }

    /**
     * Reproduced 2026-11-21: with a {@code register.lock} in the staging folder the writer locked that file and
     * not the one at the key, so it did not hold the register's own lock. The register's lock is the one at the
     * key while the key holds one.
     */
    @Test
    void aWriterOfARegisterFromBefore_takesTheLockAtTheKey_alsoWhenTheStagingFolderHoldsOne() throws IOException {
        EarlierRegister.writeTo(arch());
        Path staging = Files.createDirectories(arch().resolve(".assigning"));
        Files.createFile(staging.resolve("register.lock"));
        java.util.SortedMap<String, String> before = tree(arch());

        try (java.nio.channels.FileChannel held = java.nio.channels.FileChannel.open(arch().resolve("register.lock"),
                java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock lock = held.lock()) {
            assertThat(lock.isValid()).isTrue();
            assertThatThrownBy(() -> store().openUnassignedForWrite("arch")).as("the register's own lock is held"
                    + " by another channel").isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
        }

        assertThat(tree(arch())).as("and the writer that could not take it moved and left nothing").isEqualTo(before);
        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            opened.assignTo("shop");
        }
        assertAssignedTo("shop");
        assertThat(entries(arch())).as("the staged lock was a leftover").containsExactly("shop");
    }

    /**
     * Reproduced 2026-11-21: a part of the register at the key and in the staging folder. The move replaced a
     * staged log by the log at the key (a {@code register.assigned} line was lost), and {@code blobs} at both
     * places ended in "directory not empty". No move replaces a file or a folder: such a register is not opened
     * for an assignment, and the refusal names both paths.
     */
    @Test
    void aPartAtTheKeyAndInTheStagingFolder_refusesTheWriter_namesBothPaths_andNothingIsLost() throws IOException {
        for (String part : List.of("events.jsonl", "blobs", "state.json", "report.html")) {
            EarlierRegister.writeTo(arch());
            Path staging = Files.createDirectories(arch().resolve(".assigning"));
            if (part.equals("blobs")) {
                Files.createDirectories(staging.resolve("blobs"));
                Files.writeString(staging.resolve("blobs").resolve("a".repeat(64)), "another blob");
            } else if (part.equals("events.jsonl")) {
                // The log as an assignment that was cut off staged it: with its line
                Files.writeString(staging.resolve(part), Files.readString(arch().resolve(part))
                        + "{\"seq\":10,\"type\":\"register.assigned\"}\n");
            } else {
                Files.writeString(staging.resolve(part), "another " + part);
            }
            java.util.SortedMap<String, String> before = tree(arch());

            assertThatThrownBy(() -> store().openUnassignedForWrite("arch")).as(part)
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessageContaining("lies in two places: " + arch().resolve(part) + " and "
                            + staging.resolve(part))
                    .hasMessageContaining("one of the two is a copy")
                    .hasMessageContaining("remove the copy by hand");

            assertThat(tree(arch())).as(part + ": no line lost, nothing moved, no lock left").isEqualTo(before);
            assertThat(store().unassigned("arch")).isTrue();
            try (Stream<Path> walk = Files.walk(arch())) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /** A staged lock and a staged log without a byte are leftovers: the assignment that goes through removes them. */
    @Test
    void aStagedLockAndAStagedLogWithoutAByte_areLeftovers_thatTheAssignmentRemoves() throws IOException {
        EarlierRegister.writeTo(arch());
        Path staging = Files.createDirectories(arch().resolve(".assigning"));
        Files.createFile(staging.resolve("register.lock"));
        Files.createFile(staging.resolve("events.jsonl"));

        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            assertThat(opened.events().readAll()).as("the log at the key is the register's").hasSize(
                    EarlierRegister.lines().size());
            opened.assignTo("shop");
        }

        assertAssignedTo("shop");
        assertThat(entries(arch())).containsExactly("shop");
        assertThat(Files.readAllLines(arch().resolve("shop/events.jsonl"))).as("no line was lost to the empty log")
                .hasSize(EarlierRegister.lines().size());
    }

    // ---- a part that cannot be read says why, and one that cannot be asked for is not absent ----

    private static void mode(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    /**
     * Reproduced 2026-11-21: {@code Cannot read <file>} without the cause, so the sentence of verify held no
     * reason. The cause is the kind of failure, in words that do not depend on the machine's language.
     */
    @Test
    void aLogThatCannotBeRead_saysWhy() throws IOException {
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        try (RegisterStore.Opened shop = store().openForWrite("arch", "shop")) {
            shop.events().append(line(0));
        }
        Path log = arch().resolve("shop/events.jsonl");
        mode(log, "---------");
        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            assertThatThrownBy(() -> opened.events().readAll()).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessage("Cannot read " + log + ": access denied");
        } finally {
            mode(log, "rw-------");
        }
        Files.delete(log);
        Files.createDirectories(log);
        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            assertThatThrownBy(() -> opened.events().readAll()).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessage("Cannot read " + log + ": a folder is in its place");
            assertThatThrownBy(() -> opened.events().tail()).hasMessage("Cannot read " + log
                    + ": a folder is in its place");
        }
    }

    /**
     * Reproduced 2026-11-21: the log of a register whose folder cannot be entered was read as a log without lines,
     * so a register that had been assigned looked like one nobody had begun. What the file system cannot say is
     * not absent: the log, the state file and a blob are absent only when the file system says so.
     */
    @Test
    void aPartWhoseExistenceCannotBeAsked_isNotAbsent_andAPartThatIsAbsentIs() throws IOException {
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        String blob;
        try (RegisterStore.Opened shop = store().openForWrite("arch", "shop")) {
            blob = shop.blobs().put("a blob".getBytes(StandardCharsets.UTF_8)).sha256();
            shop.events().append(line(0));
            shop.writeState("{}".getBytes(StandardCharsets.UTF_8));
        }
        Path folder = arch().resolve("shop");

        mode(folder, "---------");
        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            assertThatThrownBy(() -> opened.events().readAll()).as("no log without lines")
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessage("Cannot read " + folder.resolve("events.jsonl") + ": access denied");
            assertThatThrownBy(opened::state).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessage("Cannot read " + folder.resolve("state.json") + ": access denied");
            assertThatThrownBy(() -> opened.blobs().getUnverified(blob))
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessageStartingWith("Cannot read blob " + blob + ": access denied");
        } finally {
            mode(folder, "rwx------");
        }
        mode(folder.resolve("blobs"), "---------");
        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            assertThatThrownBy(() -> opened.blobs().getUnverified(blob)).as("a blobs folder that cannot be entered")
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessageStartingWith("Cannot read blob " + blob + ": access denied");
        } finally {
            mode(folder.resolve("blobs"), "rwx------");
        }

        // absent, as the file system says: an empty log, no state file, no blob
        try (RegisterStore.Opened web = store().open("arch", "web"); RegisterStore.Opened opened =
                store().open("arch", "shop")) {
            assertThat(web.events().readAll()).isEmpty();
            assertThat(web.state()).isEmpty();
            assertThat(web.blobs().getUnverified(blob)).isEmpty();
            assertThat(opened.blobs().getUnverified("f".repeat(64))).isEmpty();
            assertThat(opened.blobs().getUnverified(blob)).isPresent();
            assertThat(opened.state()).isPresent();
        }
        Files.delete(folder.resolve("state.json"));
        Files.createDirectories(folder.resolve("state.json"));
        try (RegisterStore.Opened opened = store().open("arch", "shop")) {
            assertThatThrownBy(opened::state).as("a folder in the place of the state file is no absent file")
                    .hasMessage("Cannot read " + folder.resolve("state.json") + ": a folder is in its place");
        }
    }

    /** Whether a register from before lies at a key cannot be said of a key folder that cannot be entered. */
    @Test
    void aKeyFolderThatCannotBeEntered_isNotAKeyWithoutARegisterFromBefore() throws IOException {
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        EarlierRegister.writeTo(arch());
        mode(arch(), "---------");
        try {
            assertThatThrownBy(() -> store().unassigned("arch")).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessage("Cannot read " + arch().resolve("events.jsonl") + ": access denied");
        } finally {
            mode(arch(), "rwx------");
        }
        assertThat(store().unassigned("arch")).isTrue();
    }

    /** A folder of a key whose log is a folder, as a project named events.jsonl has it, is no register from before. */
    @Test
    void aProjectNamedLikeTheLog_doesNotMakeItsKeyARegisterFromBefore() {
        try (RegisterStore.Opened opened = store().openForWrite("arch", "events.jsonl")) {
            opened.events().append(line(0));
        }

        assertThat(store().unassigned("arch")).isFalse();
        assertThat(store().projects("arch")).containsExactly("events.jsonl");
    }

    @Test
    void openUnassignedForWrite_whereNoRegisterFromBeforeLies_isRefused_andCreatesNothing() {
        assertThatThrownBy(() -> store().openUnassignedForWrite("arch")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arch");
        assertThat(home.resolve("registers")).doesNotExist();

        try (RegisterStore.Opened opened = store().openForWrite("arch", "shop")) {
            opened.events().append(line(0));
            assertThatThrownBy(() -> opened.assignTo("web")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> opened.placeTaken("web")).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThatThrownBy(() -> store().openUnassignedForWrite("arch")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignTo_movesTheRegisterToItsProject_withEverythingItHeld_andHoldsTheLockOfThatRegister() throws Exception {
        EarlierRegister.writeTo(arch());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            assertThat(opened.key()).isEqualTo("arch");
            assertThat(opened.project()).isNull();
            assertThat(opened.location()).isEqualTo(arch().toString());
            assertThat(opened.events().readAll()).hasSize(EarlierRegister.lines().size());
            assertThat(opened.state()).contains(EarlierRegister.state());
            assertThat(opened.blobs().get(EarlierRegister.blobs().keySet().iterator().next())).isPresent();
            opened.events().append(line(10));
            String sha = opened.blobs().put("a blob of the assignment".getBytes(StandardCharsets.UTF_8)).sha256();
            opened.writeState("{\"assigned\":true}\n".getBytes(StandardCharsets.UTF_8));
            opened.writeView("<html>again".getBytes(StandardCharsets.UTF_8));
            assertThat(opened.placeTaken("shop")).isFalse();

            opened.assignTo("shop");

            assertThat(opened.project()).isEqualTo("shop");
            assertThat(opened.location()).isEqualTo(arch().resolve("shop").toString());
            assertThat(opened.events().readAll()).as("the log is read at its new place").hasSize(11);
            assertThat(opened.blobs().get(sha)).isPresent();
            assertThat(opened.state()).contains("{\"assigned\":true}\n".getBytes(StandardCharsets.UTF_8));
            Future<Integer> writer = pool.submit(() -> {
                try (RegisterStore.Opened next = store().openForWrite("arch", "shop")) {
                    return next.events().readAll().size();
                }
            });
            assertThatThrownBy(() -> writer.get(300, TimeUnit.MILLISECONDS))
                    .as("the lock of the register of the project is held from the move on")
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            opened.close();
            assertThat(writer.get(30, TimeUnit.SECONDS)).isEqualTo(11);
        } finally {
            pool.shutdownNow();
        }

        assertAssignedTo("shop");
        assertThat(entries(arch())).as("nothing but the project is left in the folder of the key")
                .containsExactly("shop");
        assertThat(entries(arch().resolve("shop"))).containsExactly("blobs", "events.jsonl", "register.lock",
                "report.html", "state.json");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(arch().resolve("shop"))))
                .isEqualTo("rwx------");
        assertThat(Files.readString(arch().resolve("shop/report.html"))).isEqualTo("<html>again");
    }

    /** A project may have any valid key, also the name of something the folder of a register holds. */
    @Test
    void assignTo_takesAnyProjectKey_alsoTheNameOfAnEntryOfTheFolder() throws IOException {
        for (String project : List.of("blobs", "events.jsonl", "state.json", "report.html", "register.lock")) {
            EarlierRegister.writeTo(arch());
            try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
                assertThat(opened.placeTaken(project)).as(project + " is an entry of its own folder, no project")
                        .isFalse();

                opened.assignTo(project);
            }

            assertAssignedTo(project);
            assertThat(entries(arch())).as(project).containsExactly(project);
            try (RegisterStore.Opened again = store().open("arch", project)) {
                assertThat(again.events().readAll()).as(project).hasSize(EarlierRegister.lines().size());
                assertThat(again.blobs().get(EarlierRegister.blobs().keySet().iterator().next())).isPresent();
            }
            try (Stream<Path> walk = Files.walk(arch())) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    void assignTo_isRefused_whereTheProjectHasAFolder_andNothingIsMoved() throws IOException {
        try (RegisterStore.Opened shop = store().openForWrite("arch", "shop")) {
            shop.events().append(line(0));
        }
        Files.createDirectories(arch().resolve("empty"));
        EarlierRegister.writeTo(arch());
        List<String> before = entries(arch());

        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            for (String taken : List.of("shop", "empty")) {
                assertThat(opened.placeTaken(taken)).as(taken).isTrue();
                assertThatThrownBy(() -> opened.assignTo(taken)).as(taken).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(arch().resolve(taken).toString());
                assertThat(opened.project()).isNull();
            }
            for (String bad : List.of("..", "a/b", "Shop", "")) {
                assertThatThrownBy(() -> opened.assignTo(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            }
        }

        assertThat(entries(arch())).as("as it was: a refused assignment leaves no lock of its own")
                .isEqualTo(before);
        assertThat(store().unassigned("arch")).isTrue();
        assertThat(Files.readAllBytes(arch().resolve("shop/events.jsonl"))).isEqualTo("{\"seq\":0}\n".getBytes());

        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            opened.assignTo("web");
        }
        assertAssignedTo("web");
        assertThat(store().projects("arch")).as("the other projects are as they were: a folder is a project")
                .containsExactly("empty", "shop", "web");
    }

    /**
     * A move that was cut off leaves the register in a staging folder, whole or in part. It is still a register
     * from before, the next writer finds every part of it, and the same call finishes the move.
     */
    @Test
    void anAssignmentThatWasCutOffInTheMove_isStillARegisterFromBefore_andIsFinishedByTheNext() throws IOException {
        for (List<String> moved : List.of(List.<String>of(), List.of("blobs"), List.of("blobs", "state.json"),
                List.of("blobs", "state.json", "report.html"),
                List.of("blobs", "state.json", "report.html", "events.jsonl"),
                List.of("blobs", "state.json", "report.html", "events.jsonl", "register.lock"))) {
            EarlierRegister.writeTo(arch());
            Path staging = Files.createDirectories(arch().resolve(".assigning"));
            for (String entry : moved) {
                Files.move(arch().resolve(entry), staging.resolve(entry));
            }

            assertThat(store().unassigned("arch")).as(moved.toString()).isTrue();
            assertThat(store().projects("arch")).as(moved.toString()).isEmpty();
            try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
                assertThat(opened.events().readAll()).as(moved.toString()).hasSize(EarlierRegister.lines().size());
                assertThat(opened.state()).as(moved.toString()).contains(EarlierRegister.state());
                assertThat(opened.blobs().get(EarlierRegister.blobs().keySet().iterator().next())).isPresent();
                assertThat(opened.placeTaken("shop")).isFalse();

                opened.assignTo("shop");
            }

            assertAssignedTo("shop");
            assertThat(entries(arch())).as(moved.toString()).containsExactly("shop");
            try (Stream<Path> walk = Files.walk(arch())) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /** Two commands assign at once: one of them does, and the other finds no register from before any more. */
    @Test
    void twoAssignmentsAtOnce_oneMovesTheRegister_andTheOtherIsRefused() throws Exception {
        EarlierRegister.writeTo(arch());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> assignments = new ArrayList<>();
        for (String project : List.of("shop", "web")) {
            assignments.add(pool.submit(() -> {
                go.await();
                try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
                    opened.events().append(line(opened.events().readAll().size()));
                    opened.assignTo(project);
                    return project;
                } catch (IllegalStateException e) {
                    return "refused";
                }
            }));
        }
        go.countDown();
        List<String> ended = new ArrayList<>();
        for (Future<String> assignment : assignments) {
            ended.add(assignment.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(ended).containsOnlyOnce("refused");
        String project = ended.stream().filter(e -> !e.equals("refused")).findFirst().orElseThrow();
        assertThat(store().projects("arch")).containsExactly(project);
        assertThat(entries(arch())).containsExactly(project);
        try (RegisterStore.Opened opened = store().open("arch", project)) {
            assertThat(opened.events().readAll()).as("one line of one assignment").hasSize(11);
        }
    }

    // ---- the locks of an assignment, and a register from before for reading ----

    /** Whether this JVM holds a lock on {@code file}: a second channel of the same JVM is told so. */
    private static boolean lockedByThisJvm(Path file) throws IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = channel.tryLock();
            if (lock != null) {
                lock.release();
            }
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true;
        }
    }

    /**
     * A writer of the version that left the register takes the register's own lock, and knows no other: the
     * assignment holds it too, from the start and through the move, and no lock of its own stays behind when it is
     * refused or fails.
     */
    @Test
    void anAssignment_holdsTheRegistersOwnLockToo_andARefusedOneLeavesNoLockOfItsOwn() throws IOException {
        EarlierRegister.writeTo(arch());
        List<String> before = entries(arch());

        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            assertThat(lockedByThisJvm(arch().resolve("register.lock"))).as("the register's own lock").isTrue();
            assertThat(lockedByThisJvm(arch().resolve(".assign.lock"))).as("the lock of the assignment").isTrue();
            assertThat(opened.project()).isNull();
        }

        assertThat(entries(arch())).as("closed without an assignment: as it was").isEqualTo(before);
        assertThat(lockedByThisJvm(arch().resolve("register.lock"))).isFalse();

        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            opened.assignTo("shop");
            assertThat(lockedByThisJvm(arch().resolve("shop/register.lock")))
                    .as("the same lock, at the place of the register now").isTrue();
        }
        assertThat(lockedByThisJvm(arch().resolve("shop/register.lock"))).isFalse();
        assertThat(entries(arch())).containsExactly("shop");
    }

    /** A register from before that never had a lock file gets one where it lies, and it moves with the register. */
    @Test
    void anAssignment_ofARegisterWithoutALockFile_locksOneWhereTheRegisterLies() throws IOException {
        EarlierRegister.writeTo(arch());
        Files.delete(arch().resolve("register.lock"));

        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            assertThat(lockedByThisJvm(arch().resolve("register.lock"))).isTrue();
            opened.assignTo("shop");
        }

        assertAssignedTo("shop");
        assertThat(entries(arch())).containsExactly("shop");
    }

    /**
     * A lock that this JVM holds on the lock file through another channel fails the writer, as an overlapping lock
     * does. The lock for the threads of this JVM must not stay held then: the next writer would wait for ever.
     */
    @Test
    void aWriterWhoseFileLockFails_releasesTheLockForTheThreadsOfThisJvm() throws Exception {
        store().openForWrite("arch", "shop").close();
        Path lockFile = arch().resolve("shop/register.lock");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (java.nio.channels.FileChannel foreign = java.nio.channels.FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock held = foreign.lock();

            assertThatThrownBy(() -> store().openForWrite("arch", "shop"))
                    .isInstanceOf(java.nio.channels.OverlappingFileLockException.class);

            held.release();
            Future<Integer> next = pool.submit(() -> {
                try (RegisterStore.Opened opened = store().openForWrite("arch", "shop")) {
                    return opened.events().readAll().size();
                }
            });
            assertThat(next.get(30, TimeUnit.SECONDS)).as("another thread gets the lock").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The lock of an assignment is removed by the writer that held it. A writer of another process that waited for
     * it then holds a file nobody else sees: it must take the lock again, at its place, so that a third one waits.
     */
    @Test
    void aProcessThatWaitedForTheLockOfAnAssignment_holdsTheOneAtItsPlace() throws Exception {
        String java = ProcessHandle.current().info().command().orElse(null);
        assumeTrue(java != null, "the java binary of this JVM is not known");
        EarlierRegister.writeTo(arch());
        Path lock = arch().resolve(".assign.lock");
        Process waiter;
        try (RegisterStore.Opened opened = store().openUnassignedForWrite("arch")) {
            waiter = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Waiter.class.getName(),
                    home.resolve("registers").toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            assertThat(waiter.getInputStream().read()).as("the other process is about to wait").isEqualTo('w');
            Thread.sleep(500);   // it waits for the lock by now; if not, it takes a new one, which is right too
            assertThat(waiter.isAlive()).isTrue();
        }
        try {
            assertThat(waiter.getInputStream().read()).as("the other process holds the lock").isEqualTo('h');
            assertThat(lock).as("the lock it holds is the file at the place of the lock").exists();
            assertThat(Files.size(lock)).isZero();
        } finally {
            waiter.getOutputStream().write('\n');
            waiter.getOutputStream().flush();
        }
        assertThat(waiter.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(waiter.exitValue()).isZero();
        assertThat(lock).as("and it removed it when it was done").doesNotExist();
        assertThat(store().unassigned("arch")).isTrue();
    }

    /** The other process of the test above: takes the locks of an assignment, says so, and waits for a line. */
    public static final class Waiter {
        public static void main(String[] args) throws IOException {
            FileRegisterStore store = new FileRegisterStore(Path.of(args[0]));
            System.out.write('w');
            System.out.flush();
            try (RegisterStore.Opened opened = store.openUnassignedForWrite("arch")) {
                System.out.write('h');
                System.out.flush();
                System.in.read();
            }
        }
    }

    /**
     * A register from before is read without a lock, to be verified before it is assigned: its log, its blobs and
     * its state file, each where it lies, also after a move that was cut off. Reading creates and moves nothing.
     */
    @Test
    void openUnassigned_readsTheRegisterFromBefore_whereverItsPartsLie_andChangesNothing() throws IOException {
        for (List<String> moved : List.of(List.<String>of(), List.of("blobs"), List.of("blobs", "state.json"),
                List.of("blobs", "state.json", "report.html", "events.jsonl", "register.lock"))) {
            EarlierRegister.writeTo(arch());
            if (!moved.isEmpty()) {
                Path staging = Files.createDirectories(arch().resolve(".assigning"));
                for (String entry : moved) {
                    Files.move(arch().resolve(entry), staging.resolve(entry));
                }
            }
            List<String> before = entries(arch());

            try (RegisterStore.Opened opened = store().openUnassigned("arch")) {
                assertThat(opened.project()).as(moved.toString()).isNull();
                assertThat(opened.key()).isEqualTo("arch");
                assertThat(opened.events().readAll()).as(moved.toString()).hasSize(EarlierRegister.lines().size());
                assertThat(opened.blobs().get(EarlierRegister.blobs().keySet().iterator().next()))
                        .as(moved.toString()).isPresent();
                assertThat(opened.state()).as(moved.toString()).contains(EarlierRegister.state());
                assertThatThrownBy(() -> opened.writeState(new byte[0])).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> opened.assignTo("shop")).isInstanceOf(UnsupportedOperationException.class);
            }

            assertThat(entries(arch())).as(moved + ": nothing was made or moved").isEqualTo(before);
            try (Stream<Path> walk = Files.walk(arch())) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        store().openForWrite("arch", "shop").close();
        assertThatThrownBy(() -> store().openUnassigned("arch")).isInstanceOf(IllegalStateException.class);
    }
}
