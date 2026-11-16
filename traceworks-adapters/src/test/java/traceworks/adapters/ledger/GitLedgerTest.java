package traceworks.adapters.ledger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitLedgerTest {

    @TempDir Path home;

    private GitLedger ledger() {
        return new GitLedger(home.resolve("ledger"), home.resolve("ledger.lock"));
    }

    private static byte[] manifest(String id) {
        return ("{\"run\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private String git(Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", home.resolve("ledger").toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), out);
        return out;
    }

    private String git(String... args) throws Exception {
        return git(Map.of(), args);
    }

    @Test
    void prepare_initialisesTheRepository_withoutACommit_andAgainChangesNothing() throws Exception {
        ledger().prepare();
        ledger().prepare();

        assertTrue(Files.isDirectory(home.resolve("ledger/.git")));
        assertTrue(ledger().find("r1").isEmpty());
        assertEquals("1", git("rev-list", "--count", ledger().record("r1", "COMPLETED", manifest("r1"))).strip());
    }

    @Test
    void prepare_whereNoRepositoryCanBeCreated_failsWithTheReason() throws Exception {
        Files.writeString(home.resolve("ledger"), "a file, not a directory");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().prepare());

        assertTrue(e.getMessage().contains(home.resolve("ledger").toString()), e.getMessage());
    }

    @Test
    void find_returnsTheManifestOnly_evenWhenGitWarnsOnStderr() throws Exception {
        ledger().record("r1", "COMPLETED", manifest("r1"));
        git("update-ref", "refs/tags/HEAD", "HEAD");   // "warning: refname 'HEAD' is ambiguous." on stderr

        assertArrayEquals(manifest("r1"), ledger().find("r1").orElseThrow());
    }

    @Test
    void firstRecord_initialisesTheRepository_andCommits() throws Exception {
        String commit = ledger().record("r1", "COMPLETED", manifest("r1"));
        assertTrue(Files.isDirectory(home.resolve("ledger/.git")));
        assertEquals(commit, git("rev-parse", "HEAD").strip());
        assertEquals("1", git("rev-list", "--count", "HEAD").strip());
    }

    @Test
    void eachRun_isOneCommit_withTheManifestCopy() throws Exception {
        GitLedger ledger = ledger();
        ledger.record("r1", "COMPLETED", manifest("r1"));
        ledger.record("r2", "FAILED", manifest("r2"));
        assertEquals("2", git("rev-list", "--count", "HEAD").strip());
        assertEquals(new String(manifest("r1")), git("show", "HEAD~1:runs/r1.json"));
        assertEquals(new String(manifest("r2")), git("show", "HEAD:runs/r2.json"));
    }

    @Test
    void commitMessage_namesRunAndStatus() throws Exception {
        ledger().record("r1", "FAILED", manifest("r1"));
        assertEquals("run r1 FAILED", git("log", "-1", "--format=%s").strip());
    }

    @Test
    void author_isTraceworks() throws Exception {
        ledger().record("r1", "COMPLETED", manifest("r1"));
        assertEquals("Traceworks <traceworks@localhost.invalid>|Traceworks <traceworks@localhost.invalid>",
                git("log", "-1", "--format=%an <%ae>|%cn <%ce>").strip());
    }

    @Test
    void sameRunTwice_isRefused() throws Exception {
        GitLedger ledger = ledger();
        ledger.record("r1", "COMPLETED", manifest("r1"));
        assertThrows(IllegalStateException.class, () -> ledger.record("r1", "FAILED", manifest("other")));
        assertEquals("1", git("rev-list", "--count", "HEAD").strip());
        assertArrayEquals(manifest("r1"), ledger.find("r1").orElseThrow());
    }

    @Test
    void find_returnsTheBytes_orEmpty() {
        GitLedger ledger = ledger();
        assertTrue(ledger.find("r1").isEmpty());
        ledger.record("r1", "COMPLETED", manifest("r1"));
        assertArrayEquals(manifest("r1"), ledger.find("r1").orElseThrow());
        assertTrue(ledger.find("r2").isEmpty());
    }

    @Test
    void concurrentRecords_allLand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                int thread = t;
                Callable<Void> work = () -> {
                    GitLedger ledger = ledger();
                    for (int i = 0; i < 5; i++) {
                        String id = "t" + thread + "-" + i;
                        ledger.record(id, "COMPLETED", manifest(id));
                    }
                    return null;
                };
                futures.add(pool.submit(work));
            }
            for (Future<Void> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals("10", git("rev-list", "--count", "HEAD").strip());
    }

    @Test
    void userGitConfig_doesNotApply() throws Exception {
        Path userHome = Files.createDirectories(home.resolve("user"));
        Path hooks = Files.createDirectories(userHome.resolve("hooks"));
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        hook.toFile().setExecutable(true);
        Files.writeString(userHome.resolve(".gitconfig"),
                "[core]\n\thooksPath = " + hooks + "\n[commit]\n\tgpgsign = true\n[gpg]\n\tprogram = /bin/false\n");
        Map<String, String> env = Map.of("HOME", userHome.toString(), "XDG_CONFIG_HOME", userHome.toString());
        GitLedger ledger = new GitLedger(home.resolve("ledger"), home.resolve("ledger.lock"), env);
        ledger.record("r1", "COMPLETED", manifest("r1"));
        assertEquals("1", git("rev-list", "--count", "HEAD").strip());
    }

    @Test
    void inheritedGitEnvironment_doesNotApply() throws Exception {
        Path other = Files.createDirectories(home.resolve("other"));
        new ProcessBuilder("git", "-C", other.toString(), "init", "-q").inheritIO().start().waitFor();
        Map<String, String> env = Map.of(
                "GIT_DIR", other.resolve(".git").toString(),
                "GIT_INDEX_FILE", other.resolve("index-elsewhere").toString(),
                "GIT_AUTHOR_DATE", "2000-01-01T00:00:00Z",
                "GIT_COMMITTER_DATE", "2000-01-01T00:00:00Z");
        GitLedger ledger = new GitLedger(home.resolve("ledger"), home.resolve("ledger.lock"), env);
        String commit = ledger.record("r1", "COMPLETED", manifest("r1"));
        assertEquals(commit, git("rev-parse", "HEAD").strip());
        assertTrue(!git("log", "-1", "--format=%ad", "--date=format:%Y").strip().equals("2000"));
        assertTrue(!Files.exists(other.resolve("index-elsewhere")));
        ProcessBuilder otherHead = new ProcessBuilder("git", "-C", other.toString(), "rev-parse", "-q", "--verify", "HEAD");
        otherHead.redirectErrorStream(true);
        Process process = otherHead.start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor() != 0, "the other repository must stay without commits");
    }

    @Test
    void leftoverStagedFile_isNotCommittedWithTheRun() throws Exception {
        Path repo = Files.createDirectories(home.resolve("ledger"));
        git("init", "-q");
        Files.writeString(repo.resolve("stale.txt"), "stale");
        git("add", "stale.txt");
        GitLedger ledger = ledger();
        ledger.record("r1", "COMPLETED", manifest("r1"));
        assertEquals("runs/r1.json", git("show", "--name-only", "--format=", "HEAD").strip());
        ledger.record("r2", "COMPLETED", manifest("r2"));
        assertEquals("runs/r2.json", git("show", "--name-only", "--format=", "HEAD").strip());
    }

    @Test
    void alreadyRecorded_isDecidedFromHead() throws Exception {
        GitLedger ledger = ledger();
        ledger.record("r1", "COMPLETED", manifest("r1"));
        // An uncommitted copy is not a record
        Files.write(home.resolve("ledger/runs/r2.json"), manifest("r2"));
        assertTrue(ledger.find("r2").isEmpty());
        ledger.record("r2", "COMPLETED", manifest("r2"));
        assertEquals("run r2 COMPLETED", git("log", "-1", "--format=%s").strip());
        // A committed run stays recorded when the working copy is gone
        Files.delete(home.resolve("ledger/runs/r1.json"));
        assertArrayEquals(manifest("r1"), ledger.find("r1").orElseThrow());
        assertThrows(IllegalStateException.class, () -> ledger.record("r1", "FAILED", manifest("r1")));
    }

    private static final String HEAD_A = "a".repeat(64);
    private static final String HEAD_B = "b".repeat(64);

    private static traceworks.core.port.RegisterAnchor anchor(int seq, String head) {
        return new traceworks.core.port.RegisterAnchor(seq, head);
    }

    @Test
    void anAnchor_isOneCommit_andTheLedgerKeepsEveryAnchorInOrder() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        ledger().record("r1", "COMPLETED", manifest("r1"));
        ledger().anchorRegister("security", 5, HEAD_B);
        ledger().anchorRegister("testing", 0, HEAD_A);

        assertEquals(List.of(anchor(3, HEAD_A), anchor(5, HEAD_B)), ledger().registerAnchors("security"));
        assertEquals(List.of(anchor(0, HEAD_A)), ledger().registerAnchors("testing"));
        assertEquals("4", git("rev-list", "--count", "HEAD").strip());
        assertEquals("register testing seq 0", git("log", "-1", "--format=%s").strip());
        assertEquals("{\"seq\":3,\"head\":\"" + HEAD_A + "\"}\n{\"seq\":5,\"head\":\"" + HEAD_B + "\"}\n",
                git("show", "HEAD:registers/security.jsonl"));
        assertArrayEquals(manifest("r1"), ledger().find("r1").orElseThrow());
        assertEquals("", git("status", "--porcelain"));
    }

    @Test
    void theSameAnchorAgain_addsNothing_andARegisterWithoutAnchorsHasNone() throws Exception {
        assertEquals(List.of(), ledger().registerAnchors("security"));
        ledger().anchorRegister("security", 3, HEAD_A);
        ledger().anchorRegister("security", 3, HEAD_A);

        assertEquals(List.of(anchor(3, HEAD_A)), ledger().registerAnchors("security"));
        assertEquals("1", git("rev-list", "--count", "HEAD").strip());
    }

    /** The anchors are read from HEAD: a file edited in the ledger's working tree neither counts nor is committed. */
    @Test
    void anchors_areReadFromHead_notFromTheWorkingTree() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        Files.writeString(home.resolve("ledger/registers/security.jsonl"), "");

        assertEquals(List.of(anchor(3, HEAD_A)), ledger().registerAnchors("security"));
        ledger().anchorRegister("security", 4, HEAD_B);
        assertEquals(List.of(anchor(3, HEAD_A), anchor(4, HEAD_B)), ledger().registerAnchors("security"));
    }

    @Test
    void anAnchorThatIsNoAnchor_isRefused_andWritesNothing() {
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("../x", 1, HEAD_A));
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("security", -1, HEAD_A));
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("security", 1, "nope\n"));
        assertThrows(IllegalArgumentException.class, () -> ledger().registerAnchors("a/b"));
        assertTrue(Files.notExists(home.resolve("ledger/registers")));
    }

    @Test
    void anAnchorFileThatCannotBeRead_isAnError_notAnEmptyList() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        Files.writeString(home.resolve("ledger/registers/security.jsonl"), "{\"seq\":3,\"head\":\"" + HEAD_A + "\"}\nnot a line\n");
        git("-c", "user.name=x", "-c", "user.email=x@y.invalid", "commit", "-q", "-am", "edit");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security"));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void concurrentAnchorsAndRecords_allLand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                int thread = t;
                Callable<Void> work = () -> {
                    GitLedger ledger = ledger();
                    for (int i = 0; i < 4; i++) {
                        if (thread == 2) {
                            ledger.record("run-" + i, "COMPLETED", manifest("run-" + i));
                        } else {
                            ledger.anchorRegister("security", thread * 10 + i, HEAD_A);
                        }
                    }
                    return null;
                };
                futures.add(pool.submit(work));
            }
            for (Future<Void> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals("12", git("rev-list", "--count", "HEAD").strip());
        assertEquals(8, ledger().registerAnchors("security").size());
    }

    @Test
    void anAnchorWhereNoRepositoryCanBeCreated_failsWithTheReason() throws Exception {
        Files.writeString(home.resolve("file"), "x");
        GitLedger blocked = new GitLedger(home.resolve("file/ledger"), home.resolve("ledger.lock"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> blocked.anchorRegister("security", 1, HEAD_A));
        assertTrue(e.getMessage().contains("Cannot write the ledger"), e.getMessage());
    }

    /**
     * With ledger_dir set, the lock lies in the ledger folder. An anchor takes that lock like a run's entry, and the
     * lock is never committed: each commit names the one file it adds.
     */
    @Test
    void withTheLockInTheLedgerFolder_anAnchorTakesIt_andNeverCommitsIt() throws Exception {
        Path placed = home.resolve("placed/ledger");
        GitLedger inFolder = new GitLedger(placed, placed.resolve("ledger.lock"));

        inFolder.anchorRegister("security", 3, HEAD_A);
        inFolder.record("r1", "COMPLETED", manifest("r1"));
        inFolder.anchorRegister("security", 5, HEAD_B);

        assertTrue(Files.isRegularFile(placed.resolve("ledger.lock")));
        assertEquals(List.of(anchor(3, HEAD_A), anchor(5, HEAD_B)), inFolder.registerAnchors("security"));
        ProcessBuilder tree = new ProcessBuilder("git", "-C", placed.toString(), "ls-tree", "-r", "--name-only", "HEAD")
                .redirectErrorStream(true);
        Process process = tree.start();
        String files = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), files);
        assertEquals("registers/security.jsonl\nruns/r1.json\n", files);
    }

    /** Empties a directory tree but keeps the directory itself. */
    private static void empty(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory)) Files.delete(path);
            }
        }
    }

    /**
     * A ledger that exists and cannot be read is no ledger without anchors: reading the anchors fails, and so does
     * writing one, which must not start the anchor file again with the one new line.
     */
    @Test
    void aLedgerWhoseHeadIsGarbage_failsToReadAndToAnchor_insteadOfReadingAsEmpty() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        Files.writeString(home.resolve("ledger/.git/HEAD"), "garbage\n");

        IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> ledger().registerAnchors("security"));
        assertTrue(read.getMessage().contains("cannot be read"), read.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", 4, HEAD_B));
    }

    @Test
    void aLedgerWhoseObjectsAreGone_failsToReadAndToAnchor_andKeepsNoNewAnchorFile() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        String before = git("rev-parse", "HEAD").strip();
        empty(home.resolve("ledger/.git/objects"));

        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security"));
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", 4, HEAD_B));
        assertEquals(before, git("rev-parse", "HEAD").strip(), "no commit that holds the one new line alone");
    }

    @Test
    void aLedgerPathThatIsAFile_failsToReadAndToAnchor() throws Exception {
        Files.writeString(home.resolve("ledger"), "a file where the ledger should be");
        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security"));
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", 4, HEAD_B));

    }

    /** What is honestly empty stays empty: no folder, a folder with the lock alone, a repository without a commit. */
    @Test
    void aLedgerThatDoesNotExistYet_orHasNoCommit_orNoFileOfTheRegister_hasNoAnchors() throws Exception {
        assertEquals(List.of(), ledger().registerAnchors("security"));
        Path placed = Files.createDirectories(home.resolve("placed/ledger"));
        Files.writeString(placed.resolve("ledger.lock"), "");
        assertEquals(List.of(), new GitLedger(placed, placed.resolve("ledger.lock")).registerAnchors("security"));
        ledger().prepare();
        assertEquals(List.of(), ledger().registerAnchors("security"));
        ledger().record("r1", "COMPLETED", manifest("r1"));
        assertEquals(List.of(), ledger().registerAnchors("security"));
        ledger().anchorRegister("testing", 0, HEAD_A);
        assertEquals(List.of(), ledger().registerAnchors("security"));
    }

    /** The file of the branch HEAD names, as git wrote it after the first commit. */
    private Path branchRef() throws Exception {
        return home.resolve("ledger/.git").resolve(git("symbolic-ref", "HEAD").strip());
    }

    /**
     * A branch file that is empty, or full of NUL bytes, is the usual damage after a crash. git then answers for
     * HEAD as it does for a repository without a commit; the ledger has history, so that is an error and not "no
     * anchor", for the read and for the write.
     */
    @Test
    void aLedgerWhoseBranchFileIsEmptyOrZeroed_failsToReadAndToAnchor_insteadOfReadingAsUnborn() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        Path ref = branchRef();
        for (byte[] damage : List.of(new byte[0], new byte[41])) {
            Files.write(ref, damage);

            IllegalStateException read = assertThrows(IllegalStateException.class,
                    () -> ledger().registerAnchors("security"));
            assertTrue(read.getMessage().contains("cannot be read"), read.getMessage());
            assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", 4, HEAD_B));
        }
    }

    /**
     * With the branch file gone, a write would be the first commit of a new history, and every earlier anchor and
     * run entry would leave HEAD. The objects of the old history are still there: that is no new ledger.
     */
    @Test
    void aLedgerWhoseBranchFileIsDeleted_failsToReadAndToAnchor_andGetsNoNewHistory() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        Files.delete(branchRef());

        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security"));
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", 4, HEAD_B));
        assertEquals("", git("for-each-ref").strip(), "no branch was made anew");
    }

    /** The loose object file of {@code revision} in the ledger. */
    private Path objectFile(String revision) throws Exception {
        String sha = git("rev-parse", revision).strip();
        return home.resolve("ledger/.git/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
    }

    /**
     * The commit HEAD names is there, its tree is not: the files of HEAD cannot be listed. That is a damaged ledger,
     * not one without anchors, for the read and for the write.
     */
    @Test
    void aLedgerWhoseTreeIsGone_failsToReadAndToAnchor_insteadOfReadingAsEmpty() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        String before = git("rev-parse", "HEAD").strip();
        Files.delete(objectFile("HEAD^{tree}"));

        IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> ledger().registerAnchors("security"));
        assertTrue(read.getMessage().contains("cannot be listed"), read.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", 4, HEAD_B));
        assertEquals(before, git("rev-parse", "HEAD").strip(), "no commit that holds the one new line alone");
    }

    /** HEAD lists the anchor file, but its content is gone: that is no empty anchor file. */
    @Test
    void aLedgerWhoseAnchorFileContentIsGone_failsToReadAndToAnchor_insteadOfReadingAsEmpty() throws Exception {
        ledger().anchorRegister("security", 3, HEAD_A);
        String before = git("rev-parse", "HEAD").strip();
        Files.delete(objectFile("HEAD:registers/security.jsonl"));

        IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> ledger().registerAnchors("security"));
        assertTrue(read.getMessage().contains("cannot be shown"), read.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", 4, HEAD_B));
        assertEquals(before, git("rev-parse", "HEAD").strip(), "no commit that holds the one new line alone");
    }

    /** Preparing the ledger lets go of it: a write on another thread right after it goes through. */
    @Test
    void prepare_letsGoOfTheLedger_soAnotherThreadCanWrite() throws Exception {
        ledger().prepare();

        // A virtual thread: one the ledger never lets in must not keep the test JVM from ending
        java.util.concurrent.FutureTask<String> recorded = new java.util.concurrent.FutureTask<>(
                () -> ledger().record("r1", "COMPLETED", manifest("r1")));
        Thread.ofVirtual().start(recorded);

        assertEquals(40, recorded.get(20, java.util.concurrent.TimeUnit.SECONDS).length());
    }

    private static byte[] start(String id) {
        return ("{\"run_id\":\"" + id + "\",\"run_started\":\"abc\"}\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The start of a run is one commit of its own: {@code starts/<run-id>.json}, {@code run <run-id> STARTED}. */
    @Test
    void aStart_isOneCommit_withTheEntry_andTheEndIsAnother() throws Exception {
        GitLedger ledger = ledger();

        String commit = ledger.recordStart("r1", start("r1"));

        assertEquals(commit, git("rev-parse", "HEAD").strip());
        assertEquals("run r1 STARTED", git("log", "-1", "--format=%s").strip());
        assertEquals(new String(start("r1")), git("show", "HEAD:starts/r1.json"));
        assertArrayEquals(start("r1"), ledger.findStart("r1").orElseThrow());
        assertTrue(ledger.find("r1").isEmpty(), "a start is no end entry");
        assertTrue(ledger.findStart("r2").isEmpty());

        ledger.record("r1", "COMPLETED", manifest("r1"));

        assertEquals("2", git("rev-list", "--count", "HEAD").strip());
        assertArrayEquals(start("r1"), ledger.findStart("r1").orElseThrow(), "the end keeps the start");
        assertArrayEquals(manifest("r1"), ledger.find("r1").orElseThrow());
    }

    @Test
    void theSameStartTwice_isRefused() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));

        assertThrows(IllegalStateException.class, () -> ledger.recordStart("r1", start("other")));

        assertEquals("1", git("rev-list", "--count", "HEAD").strip());
        assertArrayEquals(start("r1"), ledger.findStart("r1").orElseThrow());
    }

    @Test
    void aStartWhereNoRepositoryCanBeCreated_failsWithTheReason() throws Exception {
        Files.writeString(home.resolve("ledger"), "a file, not a directory");

        RuntimeException e = assertThrows(RuntimeException.class, () -> ledger().recordStart("r1", start("r1")));

        assertTrue(String.valueOf(e.getMessage()).contains(home.resolve("ledger").toString()), e.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().findStart("r1"),
                "a ledger that cannot be read does not say 'no start entry'");
    }

    /** Runs that start at the same time, while others end: every entry lands. */
    @Test
    void concurrentStartsAndRecords_allLand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                int thread = t;
                Callable<Void> work = () -> {
                    GitLedger ledger = ledger();
                    for (int i = 0; i < 4; i++) {
                        String id = "t" + thread + "-" + i;
                        ledger.recordStart(id, start(id));
                        if (thread == 2) ledger.record(id, "COMPLETED", manifest(id));
                    }
                    return null;
                };
                futures.add(pool.submit(work));
            }
            for (Future<Void> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals("16", git("rev-list", "--count", "HEAD").strip());
        var runs = ledger().runs().runs();
        assertEquals(12, runs.size());
        assertTrue(runs.stream().allMatch(run -> run.startNamed() && run.start() != null));
        assertEquals(List.of("t2-0", "t2-1", "t2-2", "t2-3"),
                runs.stream().filter(run -> run.end() != null).map(run -> run.runId()).toList());
    }

    private static final Map<String, String> ME = Map.of("GIT_AUTHOR_NAME", "x", "GIT_AUTHOR_EMAIL", "x@x",
            "GIT_COMMITTER_NAME", "x", "GIT_COMMITTER_EMAIL", "x@x");

    private traceworks.core.port.LedgerRuns.Run run(String id) {
        return ledger().runs().runs().stream().filter(run -> run.runId().equals(id)).findFirst().orElseThrow();
    }

    /** The runs are named by the messages of the commits: an entry that a later commit removed is still named. */
    @Test
    void runs_namesEveryStartAndEnd_alsoOneALaterCommitRemoved() throws Exception {
        GitLedger ledger = ledger();
        assertTrue(ledger.runs().runs().isEmpty(), "no ledger yet");
        ledger.prepare();
        assertTrue(ledger.runs().runs().isEmpty(), "no commit yet");
        ledger.recordStart("r1", start("r1"));
        ledger.recordStart("r2", start("r2"));
        ledger.record("r2", "FAILED", manifest("r2"));
        ledger.record("old", "COMPLETED", manifest("old"));
        ledger.anchorRegister("hello", 0, "a".repeat(64));
        git(ME, "rm", "-q", "starts/r1.json");
        git(ME, "commit", "-q", "-m", "tidy up");

        assertEquals(List.of("old", "r1", "r2"), ledger.runs().runs().stream().map(run -> run.runId()).toList());
        assertTrue(run("r1").startNamed());
        assertEquals(null, run("r1").start(), "HEAD holds it no more");
        assertArrayEquals(start("r2"), run("r2").start());
        assertEquals("FAILED", run("r2").endNamed());
        assertArrayEquals(manifest("r2"), run("r2").end());
        assertTrue(!run("old").startNamed() && run("old").start() == null);
        assertEquals("COMPLETED", run("old").endNamed());
        assertTrue(ledger.findStart("r1").isEmpty(), "HEAD holds it no more");
    }

    @Test
    void runs_ofALedgerThatCannotBeRead_isAnError_notAnEmptyList() throws Exception {
        ledger().recordStart("r1", start("r1"));
        Files.writeString(home.resolve("ledger/.git/HEAD"), "garbage\n");

        assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertThrows(IllegalStateException.class, () -> ledger().findStart("r1"));
    }

    /** One added commit with runs/<id>.json and any content: the content is handed on, with the message's status. */
    @Test
    void anEndEntryAddedByHand_isReadWithItsContent_andACommitWithoutItsEntryIsNamed() throws Exception {
        ledger().recordStart("r1", start("r1"));
        Files.createDirectories(home.resolve("ledger/runs"));
        Files.writeString(home.resolve("ledger/runs/r1.json"), "anything");
        git(ME, "add", "runs/r1.json");
        git(ME, "commit", "-q", "-m", "run r1 COMPLETED");
        git(ME, "commit", "-q", "--allow-empty", "-m", "run 11111111-2222-3333-4444-555555555555 STARTED");

        assertEquals("anything", new String(run("r1").end(), StandardCharsets.UTF_8));
        assertEquals("COMPLETED", run("r1").endNamed());
        var empty = run("11111111-2222-3333-4444-555555555555");
        assertTrue(empty.startNamed());
        assertEquals(null, empty.start(), "the commit added no entry");
    }

    /** What HEAD holds counts, not the file in the working tree. */
    @Test
    void runs_readsWhatHeadHolds_notAnEditedWorkingFile() throws Exception {
        ledger().recordStart("r1", start("r1"));
        Files.writeString(home.resolve("ledger/starts/r1.json"), "edited in place");
        assertArrayEquals(start("r1"), run("r1").start());
        Files.delete(home.resolve("ledger/starts/r1.json"));
        assertArrayEquals(start("r1"), run("r1").start());
    }

    @Test
    void anAcknowledgement_isOneCommit_andTheLedgerKeepsEveryOne() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));

        ledger.acknowledge("r1", "{\"note\":\"first\"}".getBytes(StandardCharsets.UTF_8));
        ledger.acknowledge("r1", "{\"note\":\"second\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals("run r1 ACKNOWLEDGED", git("log", "-1", "--format=%s").strip());
        assertEquals("3", git("rev-list", "--count", "HEAD").strip());
        assertEquals("{\"note\":\"first\"}\n{\"note\":\"second\"}\n", git("show", "HEAD:acks/r1.jsonl"));
        assertEquals("{\"note\":\"first\"}\n{\"note\":\"second\"}\n",
                new String(run("r1").acks(), StandardCharsets.UTF_8));
        assertEquals(null, run("r1").endNamed(), "an acknowledgement is no end");
        assertThrows(IllegalArgumentException.class,
                () -> ledger.acknowledge("r1", "two\nlines".getBytes(StandardCharsets.UTF_8)));
    }

    private void assertNotOneHistory(String what) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(e.getMessage().contains("not one history") && e.getMessage().contains(what), e.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().findStart("r1"));
    }

    /** git replace <HEAD> <older>: every reader that honours it sees the older commit in HEAD's place. */
    @Test
    void aReplaceRef_isNotHonoured_andFailsTheCheck() throws Exception {
        ledger().recordStart("r0", start("r0"));
        String older = git("rev-parse", "HEAD").strip();
        ledger().recordStart("r1", start("r1"));
        assertArrayEquals(start("r1"), ledger().findStart("r1").orElseThrow());
        git(ME, "replace", git("rev-parse", "HEAD").strip(), older);
        assertTrue(git("log", "--format=%s").lines().noneMatch(line -> line.contains("r1")), "git itself is fooled");

        assertNotOneHistory("replace ref");
        assertArrayEquals(start("r1"), ledger().find("r1").or(() -> java.util.Optional.of(start("r1"))).get());
    }

    /** A removal commit and its id in .git/shallow: the history ends there, and the start is named no more. */
    @Test
    void aShallowFile_failsTheCheck() throws Exception {
        ledger().recordStart("r1", start("r1"));
        git(ME, "rm", "-q", "starts/r1.json");
        git(ME, "commit", "-q", "-m", "tidy");
        Files.writeString(home.resolve("ledger/.git/shallow"), git("rev-parse", "HEAD"));
        assertTrue(git("log", "--format=%s").lines().noneMatch(line -> line.contains("STARTED")), "git is fooled");

        assertNotOneHistory(".git/shallow");
    }

    @Test
    void aGraftsFile_orAlternates_failTheCheck() throws Exception {
        ledger().recordStart("r1", start("r1"));
        Files.createDirectories(home.resolve("ledger/.git/info"));
        Files.writeString(home.resolve("ledger/.git/info/grafts"), git("rev-parse", "HEAD"));
        assertNotOneHistory(".git/info/grafts");
        Files.delete(home.resolve("ledger/.git/info/grafts"));

        Files.writeString(home.resolve("ledger/.git/objects/info/alternates"), "/nowhere\n");
        assertNotOneHistory("alternates");
    }

    /** HEAD moved back, with the newer commits on a branch, under a tag, or HEAD on no branch at all. */
    @Test
    void aSecondBranch_aDetachedHead_orACommitHeadDoesNotReach_failTheCheck() throws Exception {
        ledger().recordStart("r0", start("r0"));
        String older = git("rev-parse", "HEAD").strip();
        ledger().recordStart("r1", start("r1"));
        String newer = git("rev-parse", "HEAD").strip();

        git(ME, "branch", "aside", older);
        assertNotOneHistory("branches");
        git(ME, "branch", "-q", "-D", "aside");

        git(ME, "checkout", "-q", "--detach", older);
        assertNotOneHistory("names no branch");
        git(ME, "checkout", "-q", "main");

        git(ME, "tag", "keep", newer);
        git(ME, "reset", "-q", "--hard", older);
        assertNotOneHistory("does not reach");
        git(ME, "tag", "-d", "keep");
        ledger().runs();   // nothing names the newer commit any more: this is a rewritten history, which nothing here finds
    }

    /** A setting in the ledger's own config can change what git shows; only what init and a push need is there. */
    @Test
    void aSettingInTheLedgersOwnConfig_failsTheCheck_andARemoteDoesNot() throws Exception {
        ledger().recordStart("r1", start("r1"));
        git(ME, "remote", "add", "origin", "/nowhere.git");
        ledger().runs();

        git(ME, "config", "core.worktree", "/elsewhere");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(e.getMessage().contains("core.worktree"), e.getMessage());
    }

    /** A lock that is never let go must not leave a run waiting for ever: the writer gives up and names the lock. */
    @Test
    void aLockThatIsHeld_isWaitedForABoundedTime_thenTheWriteIsRefused() throws Exception {
        Path lock = home.resolve("ledger.lock");
        GitLedger ledger = new GitLedger(home.resolve("ledger"), lock, 300L);
        ledger.prepare();
        try (var channel = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE);
                var held = channel.lock()) {
            long before = System.nanoTime();
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ledger.recordStart("r1", start("r1")));
            assertTrue(e.getMessage().contains(lock.toString()) && e.getMessage().contains("held"), e.getMessage());
            assertTrue(System.nanoTime() - before < 10_000_000_000L, "it did not wait for ever");
            assertThrows(IllegalStateException.class, ledger::prepare);
        }
        ledger.recordStart("r1", start("r1"));
        assertArrayEquals(start("r1"), ledger.findStart("r1").orElseThrow());
    }

    /** A run id is a name, never a path: one that would lead out of {@code runs/} is refused, and nothing is written. */
    @Test
    void aRunIdThatIsNoRunId_isRefused_andWritesNothing() {
        assertThrows(IllegalArgumentException.class, () -> ledger().record("../escape", "COMPLETED",
                manifest("escape")));
        assertThrows(IllegalArgumentException.class, () -> ledger().find("../escape"));
        assertThrows(IllegalArgumentException.class, () -> ledger().recordStart("../escape", start("escape")));
        assertThrows(IllegalArgumentException.class, () -> ledger().findStart("../escape"));
        assertTrue(Files.notExists(home.resolve("ledger")));
    }
}
