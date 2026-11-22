package traceworks.adapters.ledger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import traceworks.core.port.Ledger;
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
        assertTrue(git("log", "-1", "--format=%s").strip().matches("run r1 FAILED sha256:[0-9a-f]{64}"));
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
        assertTrue(git("log", "-1", "--format=%s").strip().matches("run r2 COMPLETED sha256:[0-9a-f]{64}"));
        // A committed run stays recorded when the working copy is gone
        Files.delete(home.resolve("ledger/runs/r1.json"));
        assertArrayEquals(manifest("r1"), ledger.find("r1").orElseThrow());
        assertThrows(IllegalStateException.class, () -> ledger.record("r1", "FAILED", manifest("r1")));
    }

    private static final String HEAD_A = "a".repeat(64);
    private static final String HEAD_B = "b".repeat(64);

    /** An anchor of a write that added no line: what was written is said, and is nothing. */
    /**
     * Reproduced 2026-11-21: for a key and a project of 120 characters each the acknowledgement failed, since
     * {@code acks/<name>.jsonl} was longer than a file name may be.
     */
    @Test
    void aProblemOfARegisterWithTheLongestKeyAndProject_canBeAcknowledged() {
        String key = "k".repeat(128);
        String project = "p".repeat(128);
        ledger().anchorRegister(key, project, anchor(3, HEAD_A));
        String name = traceworks.core.port.Ledger.registerName(key, project);

        ledger().acknowledge(name, "{\"note\":\"looked at\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, ledger().registerAnchors(key, project).size());
        traceworks.core.port.LedgerRuns.Run listed = ledger().runs().runs().stream()
                .filter(run -> run.runId().equals(name)).findFirst().orElseThrow();
        assertTrue(new String(listed.acks(), StandardCharsets.UTF_8).contains("looked at"));
    }

    private static traceworks.core.port.RegisterAnchor anchor(int seq, String head) {
        return new traceworks.core.port.RegisterAnchor(seq, head, NOTHING);
    }

    private static final traceworks.core.port.RegisterAnchor.Written NOTHING =
            new traceworks.core.port.RegisterAnchor.Written(new java.util.TreeMap<>(), List.of(), List.of(), List.of(),
                    List.of());

    /** An anchor as a version before registers had projects wrote it: the sequence number and the head alone. */
    private static traceworks.core.port.RegisterAnchor earlier(int seq, String head) {
        return new traceworks.core.port.RegisterAnchor(seq, head);
    }

    @Test
    void anAnchor_isOneCommit_andTheLedgerKeepsEveryAnchorInOrder() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        ledger().record("r1", "COMPLETED", manifest("r1"));
        ledger().anchorRegister("security", "shop", anchor(5, HEAD_B));
        ledger().anchorRegister("testing", "shop", anchor(0, HEAD_A));

        assertEquals(List.of(anchor(3, HEAD_A), anchor(5, HEAD_B)), ledger().registerAnchors("security", "shop"));
        assertEquals(List.of(anchor(0, HEAD_A)), ledger().registerAnchors("testing", "shop"));
        assertEquals("4", git("rev-list", "--count", "HEAD").strip());
        assertTrue(git("log", "-1", "--format=%s").strip().matches("anchor testing/shop seq 0 sha256:[0-9a-f]{64}"));
        assertEquals("{\"seq\":3,\"head\":\"" + HEAD_A + "\",\"lines\":{}}\n{\"seq\":5,\"head\":\"" + HEAD_B
                + "\",\"lines\":{}}\n", git("show", "HEAD:anchors/security/shop.jsonl"));
        assertArrayEquals(manifest("r1"), ledger().find("r1").orElseThrow());
        assertEquals("", git("status", "--porcelain"));
    }

    @Test
    void theSameAnchorAgain_addsNothing_andARegisterWithoutAnchorsHasNone() throws Exception {
        assertEquals(List.of(), ledger().registerAnchors("security", "shop"));
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));

        assertEquals(List.of(anchor(3, HEAD_A)), ledger().registerAnchors("security", "shop"));
        assertEquals("1", git("rev-list", "--count", "HEAD").strip());
    }

    /** The anchors are read from HEAD: a file edited in the ledger's working tree neither counts nor is committed. */
    @Test
    void anchors_areReadFromHead_notFromTheWorkingTree() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        Files.writeString(home.resolve("ledger/anchors/security/shop.jsonl"), "");

        assertEquals(List.of(anchor(3, HEAD_A)), ledger().registerAnchors("security", "shop"));
        ledger().anchorRegister("security", "shop", anchor(4, HEAD_B));
        assertEquals(List.of(anchor(3, HEAD_A), anchor(4, HEAD_B)), ledger().registerAnchors("security", "shop"));
    }

    // ---- what an anchor says was written ----

    private static traceworks.core.port.RegisterAnchor.Written written(Map<String, Integer> lines, List<String> runs,
                                                                         List<String> actors, List<String> agents,
                                                                         List<String> decided) {
        return new traceworks.core.port.RegisterAnchor.Written(new java.util.TreeMap<>(lines), runs, actors, agents,
                decided);
    }

    /** Adds {@code line} to the anchors of hello and {@code project} by a commit of the form this version makes. */
    private void appendToTheAnchorsOfHello(String project, String line, int seq) throws Exception {
        String path = "anchors/hello/" + project + ".jsonl";
        byte[] after = (git("show", "HEAD:" + path) + line + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger").resolve(path), after);
        git(ME, "commit", "-q", "-m", "anchor hello/" + project + " seq " + seq + " sha256:" + sha256(after), "--",
                path);
    }

    /**
     * The line of an anchor holds what was written beside the sequence number and the head: the count of each type
     * of line, sorted, the run, the actor, the agent and the findings decided, each only when there is one; one
     * run, actor or agent as a word and more as a sorted list. And nothing else. The commit's subject stays.
     */
    @Test
    void theLineOfAnAnchor_holdsWhatWasWritten_andNothingElse_andIsReadBackAsItWas() throws Exception {
        var report = new traceworks.core.port.RegisterAnchor(3, HEAD_A, written(Map.of("report.applied", 1,
                "finding.new", 3, "register.created", 1), List.of("20261120-101500-ab12"), List.of(), List.of(),
                List.of()));
        var decision = new traceworks.core.port.RegisterAnchor(4, HEAD_B, written(Map.of("finding.dismissed", 1),
                List.of(), List.of("operator@host"), List.of("claude-code"), List.of("f".repeat(64))));
        var both = new traceworks.core.port.RegisterAnchor(9, HEAD_A, written(Map.of("report.applied", 2,
                "finding.accepted", 1, "finding.reopened", 1), List.of("run-a", "run-b"),
                List.of("ann@host", "operator@other.host"), List.of("claude-code", "codex"),
                List.of("a".repeat(64), "b".repeat(64))));

        ledger().anchorRegister("hello", "shop", report);
        ledger().anchorRegister("hello", "shop", decision);
        ledger().anchorRegister("hello", "shop", both);

        assertEquals("{\"seq\":3,\"head\":\"" + HEAD_A + "\",\"lines\":{\"finding.new\":3,\"register.created\":1,"
                + "\"report.applied\":1},\"run\":\"20261120-101500-ab12\"}\n"
                + "{\"seq\":4,\"head\":\"" + HEAD_B + "\",\"lines\":{\"finding.dismissed\":1},\"actor\":\"operator@host\","
                + "\"agent\":\"claude-code\",\"decided\":[\"" + "f".repeat(64) + "\"]}\n"
                + "{\"seq\":9,\"head\":\"" + HEAD_A + "\",\"lines\":{\"finding.accepted\":1,\"finding.reopened\":1,"
                + "\"report.applied\":2},\"run\":[\"run-a\",\"run-b\"],\"actor\":[\"ann@host\",\"operator@other.host\"],"
                + "\"agent\":[\"claude-code\",\"codex\"],\"decided\":[\"" + "a".repeat(64) + "\",\"" + "b".repeat(64)
                + "\"]}\n", git("show", "HEAD:anchors/hello/shop.jsonl"));
        assertEquals(List.of(report, decision, both), ledger().registerAnchors("hello", "shop"));
        assertEquals("anchor hello/shop seq 9 sha256:" + sha256(Files.readAllBytes(
                home.resolve("ledger/anchors/hello/shop.jsonl"))), git("log", "-1", "--format=%s").strip());
        assertTrue(ledger().runs().runs().isEmpty(), ledger().runs().runs().toString());
    }

    /**
     * A value in an anchor line has a shape: the run is a run id, a finding its id, an agent a name, a type of
     * line one this version knows, an actor a text of 1 to 255 characters without a control character, or each the
     * marker that stands for a value of another shape. A line that holds anything else is no anchor.
     */
    @Test
    void aLineWhoseValuesHaveNotTheShapeOfTheirFields_isNoAnchor_andTheMarkerIsReadBack() throws Exception {
        String marker = traceworks.core.register.RegisterAnchors.MALFORMED;
        var marked = new traceworks.core.port.RegisterAnchor(0, HEAD_A, written(Map.of(marker, 1,
                "finding.dismissed", 1), List.of(marker), List.of(marker, "operator@host"), List.of(marker),
                List.of(marker, "f".repeat(64))));
        ledger().anchorRegister("hello", "shop", marked);
        assertEquals(List.of(marked), ledger().registerAnchors("hello", "shop"));

        int n = 0;
        for (String line : List.of(
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"run\":\"Mallory was here\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"actor\":\"operator\\u0007the host\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"actor\":\"" + "a".repeat(256) + "\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"actor\":\"\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"agent\":\"Zoë\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"agent\":\"two  spaces\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"decided\":[\"f-1\"]}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{\"any text at all\":1}}")) {
            String project = "p" + n++;
            ledger().anchorRegister("hello", project, anchor(0, HEAD_A));
            appendToTheAnchorsOfHello(project, line, 1);

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> ledger().registerAnchors("hello", project), line);
            assertTrue(refused.getMessage().contains("is no anchor"), refused.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("hello", "web",
                new traceworks.core.port.RegisterAnchor(0, HEAD_A, written(Map.of("report.applied", 1),
                        List.of("Mallory was here"), List.of(), List.of(), List.of()))),
                "nor is such an anchor written");
    }

    /**
     * Reproduced 2026-11-21: the actor {@code Zoë Brontë <zoe@example.org>} was no value an anchor could hold.
     * An actor is the operator's own text: it is written as it is and read back as the same anchor.
     */
    @Test
    void anAnchorWithAnActorOfSpacesAndLettersBeyondAscii_isWrittenAndReadBack() throws Exception {
        String zoe = "Zoë Brontë <zoe@example.org>";
        var decision = new traceworks.core.port.RegisterAnchor(0, HEAD_A, written(Map.of("finding.dismissed", 1),
                List.of(), List.of(zoe), List.of(), List.of("f".repeat(64))));

        ledger().anchorRegister("hello", "shop", decision);

        assertEquals(List.of(decision), ledger().registerAnchors("hello", "shop"));
        assertTrue(Files.readString(home.resolve("ledger/anchors/hello/shop.jsonl")).contains("\"actor\":\"" + zoe
                + "\""));
    }

    /** This version writes no anchor that does not say what was written. */
    @Test
    void anAnchorThatDoesNotSayWhatWasWritten_isRefused_andWritesNothing() {
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("hello", "shop", earlier(3, HEAD_A)));
        assertTrue(Files.notExists(home.resolve("ledger/anchors")));
    }

    /**
     * A line with the sequence number and the head alone, in the file of a key and a project, is read as an anchor
     * that says nothing of what was written: the check of the register reports it, since no version writes it there.
     */
    @Test
    void aLineWithoutLines_inAFilePerProject_isReadAsAnAnchorThatSaysNothing() throws Exception {
        ledger().anchorRegister("hello", "shop", anchor(0, HEAD_A));
        appendToTheAnchorsOfHello("shop", "{\"seq\":1,\"head\":\"" + HEAD_B + "\"}", 1);

        assertEquals(List.of(anchor(0, HEAD_A), earlier(1, HEAD_B)), ledger().registerAnchors("hello", "shop"));
    }

    /** A line of a file per project that holds anything but what an anchor holds is no anchor, and fails its reader. */
    @Test
    void aLineOfAFilePerProject_thatHoldsMoreOrOtherThanAnAnchor_failsItsReader() throws Exception {
        int n = 0;
        for (String line : List.of(
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{\"finding.new\":1},\"title\":\"Big A.java\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{\"finding.new\":\"one\"}}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":[]}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"run\":\"r1\"}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"run\":7}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"actor\":[\"a\",3]}",
                "{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{},\"decided\":\"f1\"}",
                "{\"seq\":-1,\"head\":\"" + HEAD_B + "\",\"lines\":{}}",
                "{\"seq\":1,\"head\":\"nope\",\"lines\":{}}",
                "{\"seq\":1,\"lines\":{}}",
                "[1]", "not json")) {
            String project = "p" + n++;
            ledger().anchorRegister("hello", project, anchor(0, HEAD_A));
            appendToTheAnchorsOfHello(project, line, 1);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ledger().registerAnchors("hello", project), line);
            assertTrue(e.getMessage().contains("line 2 of anchors/hello/" + project + ".jsonl")
                    && e.getMessage().contains("is no anchor"), line + ": " + e.getMessage());
        }
    }

    /** The file an earlier version wrote holds the sequence number and the head alone; a fuller line there is none. */
    @Test
    void aFullerLine_inTheFileOfTheEarlierShape_isNoAnchor() throws Exception {
        anchorAsAnEarlierVersionDid("hello", 3, HEAD_A);
        Path file = home.resolve("ledger/registers/hello.jsonl");
        byte[] after = (Files.readString(file) + "{\"seq\":4,\"head\":\"" + HEAD_B + "\",\"lines\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, after);
        git(ME, "commit", "-q", "-m", "register hello seq 4 sha256:" + sha256(after), "--", "registers/hello.jsonl");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ledger().earlierRegisterAnchors("hello"));
        assertTrue(e.getMessage().contains("line 2 of registers/hello.jsonl") && e.getMessage().contains("is no anchor"),
                e.getMessage());
    }

    // ---- anchors per key and project, and the anchors an earlier version wrote per key ----

    /** Commits one more anchor as a version before registers had projects did: {@code registers/<key>.jsonl}. */
    private void anchorAsAnEarlierVersionDid(String key, int seq, String head) throws Exception {
        Path file = home.resolve("ledger/registers/" + key + ".jsonl");
        Files.createDirectories(file.getParent());
        if (Files.notExists(home.resolve("ledger/.git"))) {
            ledger().prepare();
        }
        String before = Files.exists(file) ? Files.readString(file) : "";
        byte[] after = (before + "{\"seq\":" + seq + ",\"head\":\"" + head + "\"}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, after);
        git("add", "--", "registers/" + key + ".jsonl");
        git(ME, "commit", "-q", "-m", "register " + key + " seq " + seq + " sha256:" + sha256(after), "--",
                "registers/" + key + ".jsonl");
    }

    /** The anchors of two projects of one key are two files, each with its own commits. */
    @Test
    void theAnchorsOfTwoProjectsOfOneKey_areTwoFiles_andNeitherHoldsALineOfTheOther() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        ledger().anchorRegister("security", "web", anchor(1, HEAD_B));
        ledger().anchorRegister("security", "shop", anchor(5, HEAD_B));

        assertEquals(List.of(anchor(3, HEAD_A), anchor(5, HEAD_B)), ledger().registerAnchors("security", "shop"));
        assertEquals(List.of(anchor(1, HEAD_B)), ledger().registerAnchors("security", "web"));
        assertEquals(List.of(), ledger().registerAnchors("security", "other"));
        assertEquals(List.of(), ledger().earlierRegisterAnchors("security"));
        assertEquals("{\"seq\":3,\"head\":\"" + HEAD_A + "\",\"lines\":{}}\n{\"seq\":5,\"head\":\"" + HEAD_B
                + "\",\"lines\":{}}\n", git("show", "HEAD:anchors/security/shop.jsonl"));
        assertEquals("{\"seq\":1,\"head\":\"" + HEAD_B + "\",\"lines\":{}}\n",
                git("show", "HEAD:anchors/security/web.jsonl"));
        assertEquals("anchor security/shop seq 5 sha256:" + sha256(Files.readAllBytes(
                home.resolve("ledger/anchors/security/shop.jsonl"))), git("log", "-1", "--format=%s").strip());
        assertEquals("anchor security/web seq 1 sha256:" + sha256(Files.readAllBytes(
                home.resolve("ledger/anchors/security/web.jsonl"))), git("log", "-1", "--skip=1", "--format=%s")
                .strip());
        assertTrue(ledger().runs().runs().isEmpty(), "anchors that are as their commits left them are no problem");
        ledger().prepare();
    }

    /** A project key that would leave the folder of its key is no anchor, and nothing is written. */
    @Test
    void anAnchorOfAProjectKeyThatIsNone_isRefused_andWritesNothing() {
        for (String bad : List.of("..", "a/b", "Shop", "", "../x")) {
            assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("security", bad, anchor(1, HEAD_A)), bad);
            assertThrows(IllegalArgumentException.class, () -> ledger().registerAnchors("security", bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("security", null, anchor(1, HEAD_A)));
        assertThrows(IllegalArgumentException.class, () -> ledger().earlierRegisterAnchors("a/b"));
        assertTrue(Files.notExists(home.resolve("ledger/registers")));
        assertTrue(Files.notExists(home.resolve("ledger/anchors")));
    }

    /**
     * A ledger that holds anchors of the earlier shape, {@code registers/<key>.jsonl} committed as
     * {@code register <key> seq <n>}, stays a valid ledger: this version knows those commits, reads the anchors, and
     * writes its own beside them, never into that file.
     */
    @Test
    void aLedgerWithAnchorsOfTheEarlierShape_isAccepted_read_andNeverWrittenInto() throws Exception {
        anchorAsAnEarlierVersionDid("hello", 3, HEAD_A);
        anchorAsAnEarlierVersionDid("hello", 5, HEAD_B);
        byte[] earlier = Files.readAllBytes(home.resolve("ledger/registers/hello.jsonl"));

        ledger().prepare();
        assertEquals(List.of(earlier(3, HEAD_A), earlier(5, HEAD_B)), ledger().earlierRegisterAnchors("hello"));
        assertEquals(List.of(), ledger().registerAnchors("hello", "shop"));
        ledger().anchorRegister("hello", "shop", anchor(6, HEAD_A));
        ledger().recordStart("r1", start("r1"));

        assertEquals(List.of(anchor(6, HEAD_A)), ledger().registerAnchors("hello", "shop"));
        assertEquals(List.of(earlier(3, HEAD_A), earlier(5, HEAD_B)), ledger().earlierRegisterAnchors("hello"));
        assertArrayEquals(earlier, git("show", "HEAD:registers/hello.jsonl").getBytes(StandardCharsets.UTF_8),
                "no new line in the file of the earlier shape");
        assertEquals("anchors/hello/shop.jsonl\nregisters/hello.jsonl\nstarts/r1.json\n",
                git("ls-tree", "-r", "--name-only", "HEAD"));
        assertTrue(changesOf("registers.hello").isEmpty(), changesOf("registers.hello").toString());
        assertTrue(changesOf("anchors.5.hello.shop").isEmpty(), changesOf("anchors.5.hello.shop").toString());
    }

    /**
     * The anchors of a key and a project lie under {@code anchors/}, and {@code registers/} holds only the files of
     * the earlier shape. So no key collides with the file an earlier version wrote for another one: a key that
     * ends like an anchor file is anchored beside the earlier file of its stem.
     */
    @Test
    void aKeyThatEndsLikeAnAnchorFile_isAnchoredBesideTheEarlierFileOfItsStem() throws Exception {
        anchorAsAnEarlierVersionDid("hello", 3, HEAD_A);

        ledger().anchorRegister("hello.jsonl", "shop", anchor(0, HEAD_B));
        ledger().anchorRegister("hello", "shop.jsonl", anchor(1, HEAD_B));

        assertEquals(List.of(anchor(0, HEAD_B)), ledger().registerAnchors("hello.jsonl", "shop"));
        assertEquals(List.of(anchor(1, HEAD_B)), ledger().registerAnchors("hello", "shop.jsonl"));
        assertEquals(List.of(earlier(3, HEAD_A)), ledger().earlierRegisterAnchors("hello"));
        assertEquals(List.of(), ledger().earlierRegisterAnchors("hello.jsonl"));
        assertEquals("anchors/hello.jsonl/shop.jsonl\nanchors/hello/shop.jsonl.jsonl\nregisters/hello.jsonl\n",
                git("ls-tree", "-r", "--name-only", "HEAD"));
        assertEquals("anchor hello/shop.jsonl seq 1 sha256:" + sha256(Files.readAllBytes(
                home.resolve("ledger/anchors/hello/shop.jsonl.jsonl"))), git("log", "-1", "--format=%s").strip());
        ledger().prepare();
        assertTrue(ledger().runs().runs().isEmpty(), ledger().runs().runs().toString());
    }

    /** The shape of a file per project under {@code registers/}, which no version wrote into a ledger, is none. */
    @Test
    void anAnchorFileOfAProjectUnderRegisters_isNoEntryOfALedger() throws Exception {
        ledger().anchorRegister("hello", "shop", anchor(0, HEAD_A));
        Path file = home.resolve("ledger/registers/hello/shop.jsonl");
        Files.createDirectories(file.getParent());
        byte[] line = ("{\"seq\":0,\"head\":\"" + HEAD_A + "\"}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, line);
        git("add", "--", "registers/hello/shop.jsonl");
        git(ME, "commit", "-q", "-m", "register hello/shop seq 0 sha256:" + sha256(line), "--",
                "registers/hello/shop.jsonl");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().prepare());
        assertTrue(e.getMessage().contains("registers/hello"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("hello", "shop", anchor(1, HEAD_B)));
    }

    /**
     * A key and a project may both hold dots. The name their anchors are listed and acknowledged under is never
     * the same for two registers: {@code a.b} of {@code c} is not {@code a} of {@code b.c}.
     */
    @Test
    void theAnchorsOfTwoRegistersWhoseKeyAndProjectJoinToTheSameWords_areListedUnderTwoNames() throws Exception {
        ledger().anchorRegister("a.b", "c", anchor(0, HEAD_A));
        ledger().anchorRegister("a", "b.c", anchor(0, HEAD_A));
        byte[] forged = ("{\"seq\":0,\"head\":\"" + HEAD_B + "\"}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/anchors/a.b/c.jsonl"), forged);
        git(ME, "commit", "-q", "-m", "anchor a.b/c seq 0 sha256:" + sha256(forged), "--", "anchors/a.b/c.jsonl");

        String rewritten = Ledger.registerName("a.b", "c");
        String intact = Ledger.registerName("a", "b.c");
        assertNotEquals(rewritten, intact);
        assertEquals("anchors.3.a.b.c", rewritten);
        assertEquals("anchors.1.a.b.c", intact);
        assertEquals(1, changesOf(rewritten).size(), changesOf(rewritten).toString());
        assertTrue(changesOf(intact).isEmpty(), changesOf(intact).toString());
        ledger().acknowledge(rewritten, "{\"note\":\"looked at\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(ledger().runs().runs().stream().anyMatch(run -> run.runId().equals(rewritten)
                && run.acks() != null));
        assertTrue(ledger().runs().runs().stream().noneMatch(run -> run.runId().equals(intact)));
    }

    /** The ledger names every register it anchors, so one whose folder is gone is still known. */
    @Test
    void theLedger_namesEveryRegisterItAnchors() throws Exception {
        assertEquals(List.of(), ledger().anchoredRegisters());
        anchorAsAnEarlierVersionDid("hello", 3, HEAD_A);
        ledger().anchorRegister("hello", "shop", anchor(4, HEAD_B));
        ledger().anchorRegister("a.b", "c", anchor(0, HEAD_A));
        ledger().recordStart("r1", start("r1"));

        assertEquals(java.util.Set.of(new Ledger.Anchored("hello", null), new Ledger.Anchored("hello", "shop"),
                new Ledger.Anchored("a.b", "c")), java.util.Set.copyOf(ledger().anchoredRegisters()));
        assertEquals(3, ledger().anchoredRegisters().size());
    }

    /** The anchors of the earlier shape are judged as they always were: a file that was rewritten is found. */
    @Test
    void anchorsOfTheEarlierShapeThatWereRewritten_areFound_andFailTheirReader() throws Exception {
        anchorAsAnEarlierVersionDid("hello", 3, HEAD_A);
        anchorAsAnEarlierVersionDid("hello", 5, HEAD_B);
        ledger().anchorRegister("hello", "shop", anchor(6, HEAD_A));
        byte[] forged = ("{\"seq\":3,\"head\":\"" + HEAD_B + "\"}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/registers/hello.jsonl"), forged);
        git(ME, "commit", "-q", "-m", "register hello seq 3 sha256:" + sha256(forged), "--", "registers/hello.jsonl");

        List<String> found = changesOf("registers.hello");
        assertTrue(found.stream().anyMatch(change -> change.contains("registers/hello.jsonl")
                && change.contains("is no beginning of the file")), found.toString());
        assertTrue(changesOf("anchors.5.hello.shop").isEmpty(), "the anchors of the project are as they were");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ledger().earlierRegisterAnchors("hello"));
        assertTrue(e.getMessage().contains("registers/hello.jsonl"), e.getMessage());
        assertEquals(List.of(anchor(6, HEAD_A)), ledger().registerAnchors("hello", "shop"));
    }

    /**
     * The check of the whole home lists the anchors of a register under a name of its key and its project, so the
     * anchors of two projects are told apart, and such a problem can be acknowledged like one of a run.
     */
    @Test
    void rewrittenAnchorsOfOneProject_areListedUnderItsKeyAndProject_andCanBeAcknowledged() throws Exception {
        ledger().anchorRegister("hello", "shop", anchor(0, HEAD_A));
        ledger().anchorRegister("hello", "shop", anchor(1, HEAD_B));
        ledger().anchorRegister("hello", "web", anchor(0, HEAD_A));
        byte[] forged = ("{\"seq\":0,\"head\":\"" + HEAD_B + "\"}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/anchors/hello/shop.jsonl"), forged);
        git(ME, "commit", "-q", "-m", "anchor hello/shop seq 0 sha256:" + sha256(forged), "--",
                "anchors/hello/shop.jsonl");

        assertTrue(changesOf("anchors.5.hello.shop").stream().anyMatch(change ->
                change.contains("anchors/hello/shop.jsonl")), changesOf("anchors.5.hello.shop").toString());
        assertTrue(changesOf("anchors.5.hello.web").isEmpty(), changesOf("anchors.5.hello.web").toString());
        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("hello", "shop"));
        assertEquals(List.of(anchor(0, HEAD_A)), ledger().registerAnchors("hello", "web"));
        ledger().acknowledge("anchors.5.hello.shop", "{\"note\":\"restored from a copy\"}"
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(ledger().runs().runs().stream().anyMatch(run -> run.runId().equals("anchors.5.hello.shop")
                && run.acks() != null));
    }

    /** What an entry path is stays closed: a file at a path of no shape this version writes is no entry. */
    @Test
    void aFileBelowTheFolderOfAKey_thatIsNoAnchorFile_makesTheLedgerNoneOfThisVersion() throws Exception {
        ledger().anchorRegister("hello", "shop", anchor(0, HEAD_A));
        // each path, and what the listing names of it first: the file, or the folder that no anchor lies in
        for (Map.Entry<String, String> entry : Map.of("anchors/hello/shop/deeper.jsonl", "anchors/hello/shop (tree",
                "anchors/hello/notes.txt", "anchors/hello/notes.txt", "anchors/hello/Shop.jsonl",
                "anchors/hello/Shop.jsonl").entrySet()) {
            String path = entry.getKey();
            Path file = home.resolve("ledger").resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x\n");
            git("add", "--", path);
            git(ME, "commit", "-q", "-m", known(), "--", path);

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().prepare(), path);
            assertTrue(e.getMessage().contains("its HEAD holds " + entry.getValue())
                    && e.getMessage().contains("is no entry of a ledger"), e.getMessage());
            git("reset", "-q", "--hard", "HEAD~1");
        }
        ledger().prepare();
    }

    /** An attributes file in the folder of a key could change the bytes of its anchors on their way in. */
    @Test
    void anAttributesFileInTheFolderOfAKey_isRefused() throws Exception {
        ledger().anchorRegister("hello", "shop", anchor(0, HEAD_A));
        Files.writeString(home.resolve("ledger/anchors/hello/.gitattributes"), "*.jsonl text eol=crlf\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ledger().anchorRegister("hello", "shop", anchor(1, HEAD_B)));
        assertTrue(e.getMessage().contains("anchors/hello/.gitattributes"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().prepare());
    }

    @Test
    void anAnchorThatIsNoAnchor_isRefused_andWritesNothing() {
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("../x", "shop", anchor(1, HEAD_A)));
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("security", "shop", anchor(-1, HEAD_A)));
        assertThrows(IllegalArgumentException.class, () -> ledger().anchorRegister("security", "shop", anchor(1, "nope\n")));
        assertThrows(IllegalArgumentException.class, () -> ledger().registerAnchors("a/b", "shop"));
        assertTrue(Files.notExists(home.resolve("ledger/registers")));
        assertTrue(Files.notExists(home.resolve("ledger/anchors")));
    }

    @Test
    void anAnchorFileThatCannotBeRead_isAnError_notAnEmptyList() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        Files.writeString(home.resolve("ledger/anchors/security/shop.jsonl"),
                git("show", "HEAD:anchors/security/shop.jsonl") + "not a line\n");
        git("-c", "user.name=x", "-c", "user.email=x@y.invalid", "commit", "-q", "-am", known());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security", "shop"));
        assertTrue(e.getMessage().contains("anchors/security/shop.jsonl")
                && e.getMessage().contains("a commit that names another file"), e.getMessage());
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
                            ledger.anchorRegister("security", "shop", anchor(thread * 10 + i, HEAD_A));
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
        assertEquals(8, ledger().registerAnchors("security", "shop").size());
    }

    @Test
    void anAnchorWhereNoRepositoryCanBeCreated_failsWithTheReason() throws Exception {
        Files.writeString(home.resolve("file"), "x");
        GitLedger blocked = new GitLedger(home.resolve("file/ledger"), home.resolve("ledger.lock"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> blocked.anchorRegister("security", "shop", anchor(1, HEAD_A)));
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

        inFolder.anchorRegister("security", "shop", anchor(3, HEAD_A));
        inFolder.record("r1", "COMPLETED", manifest("r1"));
        inFolder.anchorRegister("security", "shop", anchor(5, HEAD_B));

        assertTrue(Files.isRegularFile(placed.resolve("ledger.lock")));
        assertEquals(List.of(anchor(3, HEAD_A), anchor(5, HEAD_B)), inFolder.registerAnchors("security", "shop"));
        ProcessBuilder tree = new ProcessBuilder("git", "-C", placed.toString(), "ls-tree", "-r", "--name-only", "HEAD")
                .redirectErrorStream(true);
        Process process = tree.start();
        String files = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), files);
        assertEquals("anchors/security/shop.jsonl\nruns/r1.json\n", files);
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
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        Files.writeString(home.resolve("ledger/.git/HEAD"), "garbage\n");

        IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> ledger().registerAnchors("security", "shop"));
        assertTrue(read.getMessage().contains("cannot be read"), read.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", "shop", anchor(4, HEAD_B)));
    }

    @Test
    void aLedgerWhoseObjectsAreGone_failsToReadAndToAnchor_andKeepsNoNewAnchorFile() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        String before = git("rev-parse", "HEAD").strip();
        empty(home.resolve("ledger/.git/objects"));

        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security", "shop"));
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", "shop", anchor(4, HEAD_B)));
        assertEquals(before, git("rev-parse", "HEAD").strip(), "no commit that holds the one new line alone");
    }

    @Test
    void aLedgerPathThatIsAFile_failsToReadAndToAnchor() throws Exception {
        Files.writeString(home.resolve("ledger"), "a file where the ledger should be");
        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security", "shop"));
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", "shop", anchor(4, HEAD_B)));

    }

    /** What is honestly empty stays empty: no folder, a folder with the lock alone, a repository without a commit. */
    @Test
    void aLedgerThatDoesNotExistYet_orHasNoCommit_orNoFileOfTheRegister_hasNoAnchors() throws Exception {
        assertEquals(List.of(), ledger().registerAnchors("security", "shop"));
        Path placed = Files.createDirectories(home.resolve("placed/ledger"));
        Files.writeString(placed.resolve("ledger.lock"), "");
        assertEquals(List.of(), new GitLedger(placed, placed.resolve("ledger.lock")).registerAnchors("security", "shop"));
        ledger().prepare();
        assertEquals(List.of(), ledger().registerAnchors("security", "shop"));
        ledger().record("r1", "COMPLETED", manifest("r1"));
        assertEquals(List.of(), ledger().registerAnchors("security", "shop"));
        ledger().anchorRegister("testing", "shop", anchor(0, HEAD_A));
        assertEquals(List.of(), ledger().registerAnchors("security", "shop"));
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
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        Path ref = branchRef();
        for (byte[] damage : List.of(new byte[0], new byte[41])) {
            Files.write(ref, damage);

            IllegalStateException read = assertThrows(IllegalStateException.class,
                    () -> ledger().registerAnchors("security", "shop"));
            assertTrue(read.getMessage().contains("cannot be read"), read.getMessage());
            assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", "shop", anchor(4, HEAD_B)));
        }
    }

    /**
     * With the branch file gone, a write would be the first commit of a new history, and every earlier anchor and
     * run entry would leave HEAD. The objects of the old history are still there: that is no new ledger.
     */
    @Test
    void aLedgerWhoseBranchFileIsDeleted_failsToReadAndToAnchor_andGetsNoNewHistory() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        Files.delete(branchRef());

        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security", "shop"));
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", "shop", anchor(4, HEAD_B)));
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
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        String before = git("rev-parse", "HEAD").strip();
        Files.delete(objectFile("HEAD^{tree}"));

        IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> ledger().registerAnchors("security", "shop"));
        assertTrue(read.getMessage().contains("cannot be listed"), read.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", "shop", anchor(4, HEAD_B)));
        assertEquals(before, git("rev-parse", "HEAD").strip(), "no commit that holds the one new line alone");
    }

    /** HEAD lists the anchor file, but its content is gone: that is no empty anchor file. */
    @Test
    void aLedgerWhoseAnchorFileContentIsGone_failsToReadAndToAnchor_insteadOfReadingAsEmpty() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        String before = git("rev-parse", "HEAD").strip();
        Files.delete(objectFile("HEAD:anchors/security/shop.jsonl"));

        IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> ledger().registerAnchors("security", "shop"));
        assertTrue(read.getMessage().contains("cannot be shown"), read.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().anchorRegister("security", "shop", anchor(4, HEAD_B)));
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
        assertTrue(git("log", "-1", "--format=%s").strip().matches("run r1 STARTED sha256:[0-9a-f]{64}"));
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
        ledger.anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        git(ME, "rm", "-q", "starts/r1.json");
        git(ME, "commit", "-q", "-m", known());

        assertEquals(List.of("by-hand-1", "old", "r1", "r2"),
                ledger.runs().runs().stream().map(run -> run.runId()).toList());
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

        assertTrue(git("log", "-1", "--format=%s").strip().matches("run r1 ACKNOWLEDGED sha256:[0-9a-f]{64}"));
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

    /** The anchors are read through the same check: a ledger that is not one history gives no anchors. */
    @Test
    void registerAnchors_refuseALedgerThatIsNotOneHistory() throws Exception {
        ledger().anchorRegister("security", "shop", anchor(3, HEAD_A));
        String older = git("rev-parse", "HEAD").strip();
        ledger().anchorRegister("security", "shop", anchor(4, HEAD_B));
        git(ME, "replace", git("rev-parse", "HEAD").strip(), older);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("security", "shop"));
        assertTrue(e.getMessage().contains("not one history") && e.getMessage().contains("replace ref"), e.getMessage());
    }

    /** A removal commit and its id in .git/shallow: the history ends there, and the start is named no more. */
    @Test
    void aShallowFile_failsTheCheck() throws Exception {
        ledger().recordStart("r1", start("r1"));
        git(ME, "rm", "-q", "starts/r1.json");
        git(ME, "commit", "-q", "-m", known());
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

    private int knownSubjects;

    /**
     * A subject this version writes, for a commit made by hand: it names the acknowledgements of a run that does not
     * exist, so the ledger stays one of this version and the commit is judged for what it did to the tree.
     */
    private String known() {
        return "run by-hand-" + ++knownSubjects + " ACKNOWLEDGED sha256:" + "0".repeat(64);
    }

    private String head() throws Exception {
        return git("rev-parse", "HEAD").strip();
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    /**
     * An ordinary commit over an entry, no history rewritten: the entry is no longer what the commit that names it
     * said it added. The subject of that commit holds the SHA-256, so this is one comparison, and the sentence names
     * the commit, the path and both hashes.
     */
    @Test
    void anEntryChangedByALaterCommit_isNamedWithItsCommitThePathAndBothHashes() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));
        String named = head();
        ledger.record("r1", "COMPLETED", manifest("r1"));
        ledger.recordStart("r2", start("r2"));
        assertTrue(ledger.changes("r1").isEmpty() && run("r1").changes().isEmpty(), "as added");

        byte[] changed = new String(start("r1")).strip().getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/starts/r1.json"), changed);
        git(ME, "commit", "-q", "-m", known(), "--", "starts/r1.json");

        List<String> changes = ledger.changes("r1");
        assertEquals(1, changes.size(), changes.toString());
        assertTrue(changes.getFirst().contains("starts/r1.json is not what commit " + named.substring(0, 12))
                && changes.getFirst().contains(sha256(start("r1"))) && changes.getFirst().contains(sha256(changed)),
                changes.toString());
        assertEquals(changes, run("r1").changes());
        assertTrue(ledger.changes("r2").isEmpty() && run("r2").changes().isEmpty(), "another run is not touched");

        // The same under the message of the entry, with the hash of the new file: the commit that added the entry
        // is the one that counts, and a second one is named
        byte[] forged = "{\"run\":\"r1\",\"gate\":\"GREEN\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/runs/r1.json"), forged);
        git(ME, "commit", "-q", "-m", "run r1 COMPLETED sha256:" + sha256(forged), "--", "runs/r1.json");
        List<String> end = ledger.changes("r1").stream().filter(change -> change.contains("runs/r1.json")).toList();
        assertEquals(2, end.size(), end.toString());
        assertTrue(end.get(0).startsWith("2 commits name runs/r1.json"), end.toString());
        assertTrue(end.get(1).startsWith("runs/r1.json is not what commit ") && end.get(1).contains(sha256(forged))
                && end.get(1).contains(sha256(manifest("r1"))), end.toString());
    }

    @Test
    void anEntryDeletedByALaterCommit_isNamedWithItsCommitAndThePath() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));
        String named = head();
        git(ME, "rm", "-q", "starts/r1.json");
        git(ME, "commit", "-q", "-m", known());

        assertEquals(1, ledger.changes("r1").size());
        assertTrue(ledger.changes("r1").getFirst().contains("commit " + named.substring(0, 12))
                && ledger.changes("r1").getFirst().contains("names starts/r1.json, and the ledger does not hold it"),
                ledger.changes("r1").toString());
        assertEquals(ledger.changes("r1"), run("r1").changes());
    }

    /** The subject of an entry's commit names the SHA-256 of the entry: what is compared, with no walk over trees. */
    @Test
    void theCommitOfAnEntry_namesItsSha256() throws Exception {
        ledger().recordStart("r1", start("r1"));
        assertEquals("run r1 STARTED sha256:" + sha256(start("r1")), git("log", "-1", "--format=%B").strip());
        ledger().anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        ledger().anchorRegister("hello", "shop", anchor(1, "b".repeat(64)));
        assertEquals("anchor hello/shop seq 1 sha256:" + sha256(Files.readAllBytes(
                home.resolve("ledger/anchors/hello/shop.jsonl"))), git("log", "-1", "--format=%B").strip());
    }

    /** An entry as a commit of a version before hashes added it, by hand here: the subject alone. */
    private void legacy(String path, byte[] content, String subject) throws Exception {
        Files.createDirectories(home.resolve("ledger").resolve(path).getParent());
        Files.write(home.resolve("ledger").resolve(path), content);
        git(ME, "add", path);
        git(ME, "commit", "-q", "-m", subject);
    }

    /** The acknowledgements of a run are only added to, each by a commit that says so. */
    @Test
    void anAcknowledgementRewrittenOrRemoved_isNamed() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));
        ledger.acknowledge("r1", "{\"note\":\"first\"}".getBytes(StandardCharsets.UTF_8));
        ledger.acknowledge("r1", "{\"note\":\"second\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(ledger.changes("r1").isEmpty(), ledger.changes("r1").toString());

        Files.writeString(home.resolve("ledger/acks/r1.jsonl"), "{\"note\":\"another\"}\n");
        git(ME, "commit", "-q", "-m", known(), "--", "acks/r1.jsonl");

        assertTrue(ledger.changes("r1").stream().allMatch(change -> change.contains("acks/r1.jsonl"))
                && ledger.changes("r1").stream().anyMatch(change -> change.contains("is no beginning of the file")),
                ledger.changes("r1").toString());
    }

    /** The anchors of a register are only added to as well; a commit that rewrites them fails their readers. */
    @Test
    void anAnchorFileRewrittenByAnotherCommit_isAProblemOfTheRegister_andFailsItsReader() throws Exception {
        GitLedger ledger = ledger();
        ledger.anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        ledger.anchorRegister("hello", "shop", anchor(1, "b".repeat(64)));
        ledger.runs();

        Files.writeString(home.resolve("ledger/anchors/hello/shop.jsonl"), "{\"seq\":0,\"head\":\"" + "c".repeat(64)
                + "\"}\n");
        git(ME, "commit", "-q", "-m", known(), "--", "anchors/hello/shop.jsonl");

        assertEquals(2, changesOf("anchors.5.hello.shop").size(), changesOf("anchors.5.hello.shop").toString());
        assertTrue(changesOf("anchors.5.hello.shop").stream().allMatch(change -> change.contains("anchors/hello/shop.jsonl")
                && change.contains("is no beginning of the file")), changesOf("anchors.5.hello.shop").toString());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger.registerAnchors("hello", "shop"));
        assertTrue(e.getMessage().contains("anchors/hello/shop.jsonl"), e.getMessage());
    }

    /** A merge can bring in a change that the walk over the commits' own changes does not show. */
    @Test
    void aMergeCommit_failsTheCheck() throws Exception {
        ledger().recordStart("r0", start("r0"));
        git(ME, "checkout", "-q", "-b", "aside");
        git(ME, "commit", "-q", "--allow-empty", "-m", known());
        git(ME, "checkout", "-q", "main");
        ledger().recordStart("r1", start("r1"));
        git(ME, "merge", "-q", "--no-ff", "-m", known(), "aside");
        git(ME, "branch", "-q", "-d", "aside");

        assertNotOneHistory("merge");
    }

    /**
     * A .git that is a file pointing elsewhere (or a link): the commits would go to another repository. Not written
     * to, and not read.
     */
    @Test
    void aLedgerWhoseDotGitIsAFile_isRefused_alsoForWriting() throws Exception {
        Path elsewhere = Files.createDirectories(home.resolve("elsewhere"));
        new GitLedger(elsewhere, home.resolve("other.lock")).recordStart("r0", start("r0"));
        Files.createDirectories(home.resolve("ledger"));
        Files.writeString(home.resolve("ledger/.git"), "gitdir: " + elsewhere.resolve(".git") + "\n");

        for (org.junit.jupiter.api.function.Executable write : List.<org.junit.jupiter.api.function.Executable>of(
                () -> ledger().prepare(), () -> ledger().recordStart("r1", start("r1")),
                () -> ledger().record("r1", "COMPLETED", manifest("r1")),
                () -> ledger().anchorRegister("hello", "shop", anchor(0, "a".repeat(64))),
                () -> ledger().acknowledge("r1", "{}".getBytes(StandardCharsets.UTF_8)), () -> ledger().runs())) {
            IllegalStateException e = assertThrows(IllegalStateException.class, write);
            assertTrue(e.getMessage().contains(".git"), e.getMessage());
        }
        ProcessBuilder count = new ProcessBuilder("git", "-C", elsewhere.toString(), "rev-list", "--count", "HEAD");
        assertEquals("1", new String(count.start().getInputStream().readAllBytes()).strip(), "nothing went elsewhere");
    }

    @Test
    void aLedgerWithACommondir_isRefused_alsoForWriting() throws Exception {
        ledger().recordStart("r0", start("r0"));
        Files.writeString(home.resolve("ledger/.git/commondir"), "../../elsewhere/.git\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ledger().recordStart("r1", start("r1")));
        assertTrue(e.getMessage().contains("commondir"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().prepare());
    }

    /**
     * The rule for the ledger's own configuration is the same for a start as for a check: a key that could change
     * what git writes or shows refuses the run before it starts, with what to do; what a push needs is allowed.
     */
    @Test
    void aForeignSetting_refusesTheStartToo_andWhatAPushNeedsIsAllowed() throws Exception {
        ledger().recordStart("r0", start("r0"));
        for (String[] setting : new String[][] {{"core.sshCommand", "ssh -i /keys/ledger"}, {"pull.rebase", "true"},
                {"fetch.prune", "true"}, {"maintenance.auto", "false"}, {"url.git@host:.insteadOf", "https://host/"},
                {"remote.origin.url", "/nowhere.git"}, {"branch.main.remote", "origin"}}) {
            git(ME, "config", setting[0], setting[1]);
        }
        ledger().prepare();
        ledger().runs();
        ledger().findStart("r0");

        git(ME, "config", "core.autocrlf", "true");
        for (org.junit.jupiter.api.function.Executable call : List.<org.junit.jupiter.api.function.Executable>of(
                () -> ledger().prepare(), () -> ledger().runs(), () -> ledger().findStart("r0"))) {
            IllegalStateException e = assertThrows(IllegalStateException.class, call);
            assertTrue(e.getMessage().contains("core.autocrlf") && e.getMessage().contains("config --unset")
                    && e.getMessage().contains("git -c"), e.getMessage());
        }
    }

    /** git with bytes on its standard input; gives the trimmed output. */
    private String gitWith(byte[] input, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", home.resolve("ledger").toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(ME);
        Process process = builder.start();
        try (var out = process.getOutputStream()) {
            out.write(input);
        }
        String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), said);
        return said.strip();
    }

    private static byte[] treeEntry(String mode, String name, String id) {
        byte[] head = (mode + " " + name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] raw = java.util.HexFormat.of().parseHex(id);
        byte[] entry = java.util.Arrays.copyOf(head, head.length + raw.length);
        System.arraycopy(raw, 0, entry, head.length, raw.length);
        return entry;
    }

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) all.writeBytes(part);
        return all.toByteArray();
    }

    /**
     * Text in a commit message cannot make itself a commit: the messages are read as records that no message text
     * can imitate, so a body that looks like a record of the log names nothing, and neither does a subject with a
     * control character.
     */
    @Test
    void aMessageThatImitatesARecordOfTheLog_namesNothing() throws Exception {
        ledger().recordStart("r1", start("r1"));
        String fake = "f".repeat(40);
        // A subject of this version, and a body that looks like two records of the log
        gitWith((known() + "\n\n\u0001" + fake + "\trun zz COMPLETED sha256:" + "a".repeat(64) + "\n" + fake
                + " run r1 COMPLETED sha256:" + "a".repeat(64) + "\n").getBytes(StandardCharsets.UTF_8),
                "commit", "-q", "--allow-empty", "-F", "-");

        assertEquals(List.of("by-hand-1", "r1"), ledger().runs().runs().stream().map(run -> run.runId()).toList());
        assertEquals(null, run("r1").endNamed());
        assertTrue(run("r1").changes().isEmpty(), run("r1").changes().toString());

        // A subject with a control character is none this version writes
        git(ME, "commit", "-q", "--allow-empty", "-m", "run\u0001 r1 COMPLETED sha256:" + "a".repeat(64));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(e.getMessage().contains("was written by an earlier version"), e.getMessage());
    }

    /**
     * A tree with two entries of one name, which git lists both and shows the first of by path: the listing is the
     * only source, and a path listed twice fails every reader by name.
     */
    @Test
    void aTreeWithTwoEntriesOfOneName_failsEveryReader() throws Exception {
        ledger().recordStart("r1", start("r1"));
        String real = gitWith(manifest("r1"), "hash-object", "-w", "--stdin");
        String forged = gitWith("{\"run\":\"r1\",\"gate\":\"GREEN\"}".getBytes(StandardCharsets.UTF_8),
                "hash-object", "-w", "--stdin");
        String runsTree = gitWith(join(treeEntry("100644", "r1.json", real), treeEntry("100644", "r1.json", forged)),
                "hash-object", "-t", "tree", "--literally", "-w", "--stdin");
        String root = gitWith(join(treeEntry("40000", "runs", runsTree),
                        treeEntry("40000", "starts", git("rev-parse", "HEAD:starts").strip())),
                "hash-object", "-t", "tree", "--literally", "-w", "--stdin");
        String commit = git(ME, "commit-tree", root, "-p", "HEAD", "-m", "run r1 COMPLETED").strip();
        git(ME, "update-ref", "refs/heads/main", commit);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(e.getMessage().contains("runs/r1.json") && e.getMessage().contains("twice"), e.getMessage());
        assertTrue(ledger().find("r1").isEmpty(), "no reader takes one of the two");
        assertThrows(IllegalStateException.class, () -> ledger().findStart("r1"));
        assertThrows(IllegalStateException.class, () -> ledger().prepare());
    }

    /** Only regular files at the paths the ledger uses: a link, an executable file or any other file fails by name. */
    @Test
    void anEntryThatIsALink_orAnExecutable_orAFileThatIsNoEntry_failsTheCheckByName() throws Exception {
        ledger().recordStart("r1", start("r1"));
        Files.createSymbolicLink(home.resolve("ledger/starts/r2.json"), Path.of("r1.json"));
        git(ME, "add", "starts/r2.json");
        git(ME, "commit", "-q", "-m", "run r2 STARTED");
        IllegalStateException link = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(link.getMessage().contains("starts/r2.json") && link.getMessage().contains("120000"),
                link.getMessage());
        git(ME, "rm", "-q", "starts/r2.json");
        git(ME, "commit", "-q", "-m", known());

        git(ME, "update-index", "--chmod=+x", "starts/r1.json");
        git(ME, "commit", "-q", "-m", known());
        IllegalStateException mode = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(mode.getMessage().contains("starts/r1.json") && mode.getMessage().contains("100755"),
                mode.getMessage());
        git(ME, "update-index", "--chmod=-x", "starts/r1.json");
        git(ME, "commit", "-q", "-m", known());

        Files.writeString(home.resolve("ledger/README"), "notes");
        git(ME, "add", "README");
        git(ME, "commit", "-q", "-m", known());
        IllegalStateException other = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(other.getMessage().contains("README"), other.getMessage());
        assertThrows(IllegalStateException.class, () -> ledger().findStart("r1"));
    }

    /** Attributes can make git store other bytes than it was given: none are read, and one in the ledger is refused. */
    @Test
    void attributes_areNotRead_andAnAttributesFileInTheLedgerIsRefused() throws Exception {
        Path config = Files.createDirectories(home.resolve("xdg/git"));
        Files.writeString(config.resolve("attributes"), "*.json text eol=lf\n");
        GitLedger ledger = new GitLedger(home.resolve("ledger"), home.resolve("ledger.lock"),
                Map.of("XDG_CONFIG_HOME", home.resolve("xdg").toString(), "HOME", home.resolve("xdg").toString()));
        byte[] crlf = "{\"run_id\":\"r1\"}\r\n".getBytes(StandardCharsets.UTF_8);
        ledger.recordStart("r1", crlf);
        assertArrayEquals(crlf, ledger.findStart("r1").orElseThrow(), "stored byte for byte");

        Files.writeString(home.resolve("ledger/.gitattributes"), "*.json text eol=lf\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger.recordStart("r2", crlf));
        assertTrue(e.getMessage().contains(".gitattributes"), e.getMessage());
        assertThrows(IllegalStateException.class, ledger::prepare);
        Files.delete(home.resolve("ledger/.gitattributes"));
        Files.createDirectories(home.resolve("ledger/.git/info"));
        Files.writeString(home.resolve("ledger/.git/info/attributes"), "*.json text eol=lf\n");
        IllegalStateException info = assertThrows(IllegalStateException.class, () -> ledger.recordStart("r2", crlf));
        assertTrue(info.getMessage().contains("info/attributes"), info.getMessage());
    }

    /** A promisor remote lets git fetch a missing object from elsewhere: not a setting a push needs. */
    @Test
    void aPromisorRemote_isRefused() throws Exception {
        ledger().recordStart("r1", start("r1"));
        git(ME, "config", "remote.origin.url", "/nowhere.git");
        ledger().runs();
        git(ME, "config", "remote.origin.promisor", "true");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().runs());
        assertTrue(e.getMessage().contains("remote.origin.promisor"), e.getMessage());
    }

    private static String anchorLine(int seq, String head) {
        return "{\"seq\":" + seq + ",\"head\":\"" + head + "\"}\n";
    }

    private List<String> changesOf(String id) {
        return ledger().runs().runs().stream().filter(run -> run.runId().equals(id)).findFirst()
                .map(run -> run.changes()).orElse(List.of());
    }

    /**
     * A file that is added to is not judged by its newest commit alone, which whoever commits last would choose:
     * every commit that named a hash must have left a beginning of the file as it is now, each longer than the one
     * before. So a rewritten file under a correct hash of the new file is found: the earlier hashes are no
     * beginnings of it.
     */
    @Test
    void anAnchorFileRewrittenUnderACorrectHash_isFound_alsoByTheReaderOfTheAnchors() throws Exception {
        GitLedger ledger = ledger();
        ledger.anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        ledger.anchorRegister("hello", "shop", anchor(1, "b".repeat(64)));
        ledger.anchorRegister("hello", "shop", anchor(2, "c".repeat(64)));
        assertTrue(changesOf("anchors.5.hello.shop").isEmpty());
        assertEquals(3, ledger.registerAnchors("hello", "shop").size());

        byte[] forged = (anchorLine(0, "a".repeat(64)) + anchorLine(1, "d".repeat(64))).getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/anchors/hello/shop.jsonl"), forged);
        git(ME, "commit", "-q", "-m", "anchor hello/shop seq 1 sha256:" + sha256(forged), "--", "anchors/hello/shop.jsonl");

        List<String> found = changesOf("anchors.5.hello.shop");
        assertTrue(found.stream().anyMatch(change -> change.contains("anchors/hello/shop.jsonl")
                && change.contains("is no beginning of the file")), found.toString());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("hello", "shop"));
        assertTrue(e.getMessage().contains("is no beginning of the file"), e.getMessage());
    }

    @Test
    void theAcknowledgementsOfARunRewrittenUnderACorrectHash_areFound() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));
        ledger.acknowledge("r1", "{\"note\":\"first\"}".getBytes(StandardCharsets.UTF_8));
        ledger.acknowledge("r1", "{\"note\":\"second\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(changesOf("r1").isEmpty());

        byte[] forged = "{\"note\":\"mine\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/acks/r1.jsonl"), forged);
        git(ME, "commit", "-q", "-m", "run r1 ACKNOWLEDGED sha256:" + sha256(forged), "--", "acks/r1.jsonl");

        assertTrue(changesOf("r1").stream().anyMatch(change -> change.contains("acks/r1.jsonl")
                && change.contains("is no beginning of the file")), changesOf("r1").toString());

        // Lines put in front or in the middle, with the old lines kept: the earlier hashes are no beginnings either
        GitLedger other = new GitLedger(home.resolve("second"), home.resolve("second.lock"));
        other.recordStart("r1", start("r1"));
        other.acknowledge("r1", "{\"note\":\"first\"}".getBytes(StandardCharsets.UTF_8));
        byte[] grown = "{\"note\":\"mine\"}\n{\"note\":\"first\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("second/acks/r1.jsonl"), grown);
        ProcessBuilder commit = new ProcessBuilder("git", "-C", home.resolve("second").toString(), "commit", "-q", "-m",
                "run r1 ACKNOWLEDGED sha256:" + sha256(grown), "--", "acks/r1.jsonl");
        commit.environment().putAll(ME);
        assertEquals(0, commit.start().waitFor());
        assertTrue(other.changes("r1").stream().anyMatch(change -> change.contains("is no beginning of the file")),
                other.changes("r1").toString());
    }

    /** A file that is no entry refuses a run before it starts, also in a ledger that needs no pin, with the remedy. */
    @Test
    void aStrayFile_refusesPrepare_withWhatToDo() throws Exception {
        ledger().recordStart("r1", start("r1"));
        ledger().prepare();
        Files.writeString(home.resolve("ledger/README"), "notes");
        git(ME, "add", "README");
        git(ME, "commit", "-q", "-m", known());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ledger().prepare());
        assertTrue(e.getMessage().contains("README") && e.getMessage().contains("reset --hard HEAD~1"), e.getMessage());
    }

    /** A file named HEAD in the ledger folder is a file, not the commit the readers mean. */
    @Test
    void aFileNamedHead_inTheLedgerFolder_doesNotStopTheReaders() throws Exception {
        ledger().recordStart("r1", start("r1"));
        ledger().anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        Files.writeString(home.resolve("ledger/HEAD"), "not a commit");

        ledger().prepare();
        assertEquals(1, ledger().runs().runs().size());
        assertArrayEquals(start("r1"), ledger().findStart("r1").orElseThrow());
        assertEquals(1, ledger().registerAnchors("hello", "shop").size());
        ledger().recordStart("r2", start("r2"));
    }

    private void refusedAsEarlier(GitLedger ledger, String names) {
        for (org.junit.jupiter.api.function.Executable call : List.<org.junit.jupiter.api.function.Executable>of(
                ledger::prepare, ledger::runs, () -> ledger.findStart("r1"), () -> ledger.registerAnchors("hello", "shop"),
                () -> ledger.recordStart("r9", start("r9")), () -> ledger.record("r9", "COMPLETED", manifest("r9")),
                () -> ledger.anchorRegister("hello", "shop", anchor(7, "a".repeat(64))), () -> ledger.changes("r1"),
                () -> ledger.acknowledge("r1", "{}".getBytes(StandardCharsets.UTF_8)))) {
            RuntimeException e = assertThrows(RuntimeException.class, call);
            assertTrue(e.getMessage().contains("was written by an earlier version") && e.getMessage().contains(names)
                    && e.getMessage().contains("Move the folder aside")
                    && e.getMessage().contains("the runs and the registers stay where they are"), e.getMessage());
        }
        assertTrue(ledger.find("r1").isEmpty(), "no reader takes an entry of such a ledger");
    }

    /**
     * There is one kind of ledger: every commit has a subject this version writes, which ends with the SHA-256 of
     * the file after it. A ledger with any other commit is one of an earlier version, and every command that reads
     * or writes the ledger refuses it with what to do: move the folder aside, and the next run starts a new ledger.
     */
    @Test
    void aLedgerWithACommitThisVersionDoesNotWrite_isRefusedByEveryCommand_withWhatToDo() throws Exception {
        ledger().recordStart("r1", start("r1"));
        legacy("runs/old.json", manifest("old"), "run old COMPLETED");
        String commit = head();
        long before = Long.parseLong(git("rev-list", "--count", "HEAD").strip());

        refusedAsEarlier(ledger(), "commit " + commit.substring(0, 12));

        assertEquals(before, Long.parseLong(git("rev-list", "--count", "HEAD").strip()), "nothing was written");
    }

    @Test
    void aLedgerWithAnyOtherCommit_alsoAnEmptyOne_isRefusedTheSameWay() throws Exception {
        ledger().recordStart("r1", start("r1"));
        git(ME, "commit", "-q", "--allow-empty", "-m", "tidy");
        refusedAsEarlier(ledger(), "\"tidy\"");
    }

    /**
     * A commit with a subject of this version that does not match what the ledger holds is a problem of the path it
     * names, with what was found, that a person can acknowledge: never a failure of the whole ledger.
     */
    @Test
    void aCommitOfAKnownSubjectThatDoesNotMatchTheTree_isAProblemOfItsPath_notOfTheLedger() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));
        ledger.anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        // names a path it does not add; names a hash the file does not have; names a file again
        git(ME, "commit", "-q", "--allow-empty", "-m", "run r2 COMPLETED sha256:" + "0".repeat(64));
        git(ME, "commit", "-q", "--allow-empty", "-m", "run r1 STARTED sha256:" + "1".repeat(64));
        git(ME, "commit", "-q", "--allow-empty", "-m", "anchor hello/shop seq 9 sha256:" + "2".repeat(64));
        git(ME, "commit", "-q", "--allow-empty", "-m", "run r3 ACKNOWLEDGED sha256:" + "3".repeat(64));

        assertTrue(changesOf("r2").getFirst().contains("names runs/r2.json, and the ledger does not hold it"),
                changesOf("r2").toString());
        assertTrue(changesOf("r1").getFirst().contains("2 commits name starts/r1.json"), changesOf("r1").toString());
        assertTrue(changesOf("anchors.5.hello.shop").getFirst().contains("is no beginning of the file"),
                changesOf("anchors.5.hello.shop").toString());
        assertTrue(changesOf("r3").getFirst().contains("names acks/r3.jsonl, and the ledger does not hold it"),
                changesOf("r3").toString());
        ledger.prepare();
        ledger.acknowledge("r2", "{\"note\":\"a commit of mine\"}".getBytes(StandardCharsets.UTF_8));
        ledger.recordStart("r4", start("r4"));
        assertArrayEquals(start("r1"), ledger.findStart("r1").orElseThrow());
    }

    /** A commit of a file that is added to must leave it longer: the same hash again, or a shorter one, is found. */
    @Test
    void aCommitThatLeavesAnAnchorFileNoLonger_isFound() throws Exception {
        GitLedger ledger = ledger();
        ledger.anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        String one = sha256(Files.readAllBytes(home.resolve("ledger/anchors/hello/shop.jsonl")));
        ledger.anchorRegister("hello", "shop", anchor(1, "b".repeat(64)));
        String two = sha256(Files.readAllBytes(home.resolve("ledger/anchors/hello/shop.jsonl")));
        assertTrue(changesOf("anchors.5.hello.shop").isEmpty());

        git(ME, "commit", "-q", "--allow-empty", "-m", "anchor hello/shop seq 1 sha256:" + two);
        assertEquals(1, changesOf("anchors.5.hello.shop").size(), changesOf("anchors.5.hello.shop").toString());
        assertTrue(changesOf("anchors.5.hello.shop").getFirst().contains("no longer than the commit before it"),
                changesOf("anchors.5.hello.shop").toString());
        assertThrows(IllegalStateException.class, () -> ledger().registerAnchors("hello", "shop"));

        git(ME, "commit", "-q", "--allow-empty", "-m", "anchor hello/shop seq 0 sha256:" + one);
        assertEquals(2, changesOf("anchors.5.hello.shop").size(), changesOf("anchors.5.hello.shop").toString());
    }

    /** A beginning ends with a line: a file whose last line has no line feed, and one with CRLF, are judged as bytes. */
    @Test
    void aFileWithoutAFinalLineFeed_orWithCrlf_isJudgedByItsBytes() throws Exception {
        GitLedger ledger = ledger();
        ledger.recordStart("r1", start("r1"));
        byte[] first = "{\"note\":\"one\"}\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"note\":\"one\"}\r\n{\"note\":\"two\"}".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve("ledger/acks"));
        Files.write(home.resolve("ledger/acks/r1.jsonl"), first);
        git(ME, "-c", "core.autocrlf=false", "add", "acks/r1.jsonl");
        git(ME, "commit", "-q", "-m", "run r1 ACKNOWLEDGED sha256:" + sha256(first));
        Files.write(home.resolve("ledger/acks/r1.jsonl"), second);
        git(ME, "-c", "core.autocrlf=false", "commit", "-q", "-m", "run r1 ACKNOWLEDGED sha256:" + sha256(second),
                "--", "acks/r1.jsonl");

        assertTrue(changesOf("r1").isEmpty(), changesOf("r1").toString());
        assertArrayEquals(second, run("r1").acks());

        // a commit that cut the first line in the middle left no beginning that ends with a line
        byte[] cut = "{\"note\":\"one\"}\r\n{\"note\":\"two\"}xx\n".getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("ledger/acks/r1.jsonl"), cut);
        git(ME, "-c", "core.autocrlf=false", "commit", "-q", "-m", "run r1 ACKNOWLEDGED sha256:" + sha256(cut),
                "--", "acks/r1.jsonl");
        assertEquals(1, changesOf("r1").size(), changesOf("r1").toString());
        assertTrue(changesOf("r1").getFirst().contains("is no beginning of the file"), changesOf("r1").toString());
    }

    /** A HEAD that names a reference outside refs/heads is no branch: nothing is skipped for it. */
    @Test
    void aHeadOutsideRefsHeads_isRefused() throws Exception {
        ledger().recordStart("r1", start("r1"));
        git(ME, "update-ref", "refs/foo/main", head());
        git(ME, "symbolic-ref", "HEAD", "refs/foo/main");
        git(ME, "update-ref", "-d", "refs/heads/main");

        assertNotOneHistory("refs/foo/main");
        assertThrows(IllegalStateException.class, () -> ledger().prepare());
    }

    /** When the ledger began: the time of its oldest commit, and none for a ledger without one. */
    @Test
    void began_isTheTimeOfTheOldestCommit() throws Exception {
        assertTrue(ledger().began().isEmpty());
        ledger().prepare();
        assertTrue(ledger().began().isEmpty());
        java.time.Instant before = java.time.Instant.now().minusSeconds(2);
        ledger().recordStart("r1", start("r1"));
        java.time.Instant first = ledger().began().orElseThrow();
        assertTrue(!first.isBefore(before) && !first.isAfter(java.time.Instant.now().plusSeconds(2)), first.toString());
        ledger().recordStart("r2", start("r2"));
        assertEquals(first, ledger().began().orElseThrow());
    }

    /** A repository whose object ids are SHA-256: the ids are longer, and everything is read and judged the same. */
    @Test
    void aRepositoryWithSha256ObjectIds_worksTheSame() throws Exception {
        Files.createDirectories(home.resolve("ledger"));
        git("init", "-q", "--object-format=sha256", "-b", "main");
        GitLedger ledger = ledger();
        ledger.prepare();
        ledger.recordStart("r1", start("r1"));
        ledger.record("r1", "COMPLETED", manifest("r1"));
        ledger.anchorRegister("hello", "shop", anchor(0, "a".repeat(64)));
        ledger.anchorRegister("hello", "shop", anchor(1, "b".repeat(64)));
        ledger.acknowledge("r1", "{\"note\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(64, head().length());
        assertArrayEquals(start("r1"), ledger.findStart("r1").orElseThrow());
        assertArrayEquals(manifest("r1"), ledger.find("r1").orElseThrow());
        assertEquals(2, ledger.registerAnchors("hello", "shop").size());
        assertTrue(run("r1").changes().isEmpty(), run("r1").changes().toString());
        Files.writeString(home.resolve("ledger/starts/r1.json"), "edited in place");
        assertArrayEquals(start("r1"), run("r1").start());
        ledger.prepare();
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
