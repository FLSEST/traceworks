package traceworks.adapters.ledger;

import traceworks.adapters.pkg.GitProcess;
import traceworks.core.port.Ids;
import traceworks.core.model.AuditConfig;
import traceworks.core.port.Ledger;
import traceworks.core.port.LedgerRuns;
import traceworks.core.port.RegisterAnchor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ledger as a git repository: one commit per started run adds {@code starts/<run-id>.json}, the start entry, with
 * the message {@code run <run-id> STARTED}; one commit per finished run adds {@code runs/<run-id>.json}, a
 * byte-identical copy of the manifest, with the message {@code run <run-id> <STATUS>}; and one commit per write of a register adds a line
 * to {@code registers/<key>.jsonl}, the anchor of the register's head, with the message
 * {@code register <key> seq <n>}.
 *
 * <p>Commits are serialised by one in-JVM lock (a {@link FileChannel} lock alone does not exclude threads of the
 * same JVM) and an exclusive lock on the lock file for processes. The lock channel is the only one opened on the lock
 * file while it is held: closing any channel to a file drops all locks the process holds on it.
 *
 * <p>The operator's git configuration must not apply: writes run with an empty global and system configuration, no
 * hooks, no signing and no init template. Every git call is a {@link GitProcess}, with at most
 * {@value #TIMEOUT_SECONDS} seconds.
 */
public final class GitLedger implements Ledger {

    static final int TIMEOUT_SECONDS = 30;
    private static final String IDENTITY_NAME = "Traceworks";
    private static final String IDENTITY_EMAIL = "traceworks@localhost.invalid";
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile(AuditConfig.KEY);
    private static final java.util.regex.Pattern HEAD = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private static final java.util.regex.Pattern ANCHOR =
            java.util.regex.Pattern.compile("\\{\"seq\":(\\d{1,9}),\"head\":\"([0-9a-f]{64})\"\\}");

    /** The status a start entry has in its commit message; no run status has this name. */
    static final String STARTED = "STARTED";
    /** The message of the commit of a run's entry: {@code run <run-id> STARTED} or {@code run <run-id> <STATUS>}. */
    private static final java.util.regex.Pattern SUBJECT = java.util.regex.Pattern.compile("run (\\S+) ([A-Z_]+)");
    /** The path of a start entry or an end entry. */

    /** How long a writer waits for the ledger's lock before it gives up. */
    static final int LOCK_WAIT_SECONDS = 60;
    /** The status an acknowledgement has in its commit message; no run status has this name. */
    static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final Set<String> FINAL = Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED");
    /** A line of {@code ls-tree -r}: mode, type, object id, a tab, the path. */
    private static final java.util.regex.Pattern TREE_LINE =
            java.util.regex.Pattern.compile("\\d+ blob ([0-9a-f]{40,64})\\t((starts|runs|acks)/([^/]+)\\.(json|jsonl))");
    /**
     * The keys the ledger's own git configuration may hold: what {@code git init} writes, and what an operator sets
     * to push the ledger. Any other key could change what git shows of the history, so the check refuses it.
     */
    private static final java.util.regex.Pattern CONFIG_KEY = java.util.regex.Pattern.compile(
            "core\\.(repositoryformatversion|filemode|bare|logallrefupdates|ignorecase|precomposeunicode|symlinks)"
                    + "|extensions\\.objectformat|(remote|branch|user|push|credential|http|gc)\\..*");

    private final Path directory;
    private final Path lockFile;
    private final long lockWaitMillis;
    private final GitProcess git;

    public GitLedger(Path ledgerDirectory, Path lockFile) {
        this(ledgerDirectory, lockFile, Map.of());
    }

    /** With another wait for the lock, for tests. */
    GitLedger(Path ledgerDirectory, Path lockFile, long lockWaitMillis) {
        this(ledgerDirectory, lockFile, Map.of(), lockWaitMillis);
    }

    /** With extra child environment variables (for tests); the ledger's own variables take precedence. */
    GitLedger(Path ledgerDirectory, Path lockFile, Map<String, String> environment) {
        this(ledgerDirectory, lockFile, environment, LOCK_WAIT_SECONDS * 1000L);
    }

    private GitLedger(Path ledgerDirectory, Path lockFile, Map<String, String> environment, long lockWaitMillis) {
        this.directory = ledgerDirectory;
        this.lockFile = lockFile;
        this.lockWaitMillis = lockWaitMillis;
        // The operator's configuration, an inherited GIT_DIR, GIT_INDEX_FILE, GIT_CONFIG_* or date would redirect or
        // alter the commit: the ledger's own variables replace them
        // Replace refs would show another history than the one the commits hold, and a commit-graph file is a
        // second, unchecked description of it: neither is read
        this.git = new GitProcess("git", List.of("--no-replace-objects", "-c", "commit.gpgsign=false",
                "-c", "core.commitGraph=false"), environment, Map.of(
                "GIT_NO_REPLACE_OBJECTS", "1",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_AUTHOR_NAME", IDENTITY_NAME,
                "GIT_AUTHOR_EMAIL", IDENTITY_EMAIL,
                "GIT_COMMITTER_NAME", IDENTITY_NAME,
                "GIT_COMMITTER_EMAIL", IDENTITY_EMAIL), TIMEOUT_SECONDS * 1000L);
    }

    /** Creates the repository if needed, under the ledger lock, and checks that git runs in it. */
    @Override
    public void prepare() {
        try {
            try (Held ignored = hold()) {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory.resolve(".git"))) {
                    git("-c", "init.defaultBranch=main", "-c", "init.templateDir=", "init", "-q");
                }
                git("rev-parse", "--git-dir");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the ledger " + directory + ": " + e, e);
        }
    }

    @Override
    public String record(String runId, String status, byte[] manifestJson) {
        requireRunId(runId);
        return commitNew(Path.of("runs", runId + ".json"), manifestJson, "run " + runId + " " + status,
                "Run is already recorded: " + runId);
    }

    /** One commit adds {@code starts/<run-id>.json} with the message {@code run <run-id> STARTED}. */
    @Override
    public String recordStart(String runId, byte[] startJson) {
        requireRunId(runId);
        return commitNew(Path.of("starts", runId + ".json"), startJson, "run " + runId + " " + STARTED,
                "The start of the run is already noted: " + runId);
    }

    @Override
    public Optional<byte[]> findStart(String runId) {
        requireRunId(runId);
        requireOneHistory();
        return strictInHead(Path.of("starts", runId + ".json"));
    }

    /**
     * Every run the ledger names. The commit messages say which runs started and ended ({@code run <run-id> STARTED},
     * {@code run <run-id> <STATUS>}): a later commit can remove a file, not an earlier message, and reading messages
     * needs no diff, so it stays fast on a long history. The files of HEAD are what the ledger holds now: each is read
     * from the working tree when the file there is the object HEAD names, else from the object store. Nothing for a
     * ledger that does not exist yet or has no commit.
     *
     * @throws IllegalStateException if the ledger cannot be read, or holds something that lets git show another
     *                               history than its commits ({@link #requireOneHistory})
     */
    @Override
    public LedgerRuns runs() {
        requireOneHistory();
        if (!hasCommit()) return new LedgerRuns(List.of());
        Map<String, boolean[]> startNamed = new java.util.TreeMap<>();
        Map<String, String> endNamed = new java.util.HashMap<>();
        GitProcess.Result log = run("--git-dir=.git", "--work-tree=.", "log", "--encoding=UTF-8", "--format=%s", "HEAD");
        requireRead(log, "its history cannot be listed");
        for (String subject : log.outputText().split("\n")) {
            java.util.regex.Matcher entry = SUBJECT.matcher(subject);
            if (!entry.matches() || !Ids.isRunId(entry.group(1))) continue;
            boolean[] named = startNamed.computeIfAbsent(entry.group(1), id -> new boolean[1]);
            if (entry.group(2).equals(STARTED)) {
                named[0] = true;
            } else if (FINAL.contains(entry.group(2))) {
                // The log is newest first: the oldest message of an end is the one that counts
                endNamed.put(entry.group(1), entry.group(2));
            }
        }
        GitProcess.Result tree = run("--git-dir=.git", "--work-tree=.", "-c", "core.quotePath=false", "ls-tree",
                "-r", "HEAD", "--", "starts", "runs", "acks");
        requireRead(tree, "the files of its HEAD cannot be listed");
        Map<String, byte[]> starts = new java.util.HashMap<>();
        Map<String, byte[]> ends = new java.util.HashMap<>();
        Map<String, byte[]> acks = new java.util.HashMap<>();
        for (String line : tree.outputText().split("\n")) {
            java.util.regex.Matcher file = TREE_LINE.matcher(line);
            if (!file.matches() || !Ids.isRunId(file.group(4))) continue;
            boolean lines = file.group(5).equals("jsonl");
            if (lines != file.group(3).equals("acks")) continue;
            startNamed.computeIfAbsent(file.group(4), id -> new boolean[1]);
            (lines ? acks : file.group(3).equals("starts") ? starts : ends)
                    .put(file.group(4), blob(file.group(1), file.group(2)));
        }
        List<LedgerRuns.Run> runs = new java.util.ArrayList<>();
        startNamed.forEach((id, named) -> runs.add(new LedgerRuns.Run(id, named[0], starts.get(id), endNamed.get(id),
                ends.get(id), acks.get(id))));
        return new LedgerRuns(runs);
    }

    /** The content of the blob {@code id}, which HEAD holds at {@code path}. */
    private byte[] blob(String id, String path) {
        try {
            Path file = directory.resolve(path);
            if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                byte[] content = Files.readAllBytes(file);
                java.security.MessageDigest digest =
                        java.security.MessageDigest.getInstance(id.length() == 40 ? "SHA-1" : "SHA-256");
                digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
                if (java.util.HexFormat.of().formatHex(digest.digest(content)).equals(id)) return content;
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            // the object store has it
        }
        GitProcess.Result shown = run("--git-dir=.git", "--work-tree=.", "cat-file", "blob", id);
        requireRead(shown, path + " of its HEAD cannot be shown");
        return shown.output();
    }

    /**
     * Adds one line to {@code acks/<run-id>.jsonl} and commits it as {@code run <run-id> ACKNOWLEDGED}.
     */
    @Override
    public void acknowledge(String runId, byte[] line) {
        requireRunId(runId);
        String text = new String(line, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || text.contains("\n")) {
            throw new IllegalArgumentException("An acknowledgement is one line");
        }
        requireOneHistory();
        append(Path.of("acks", runId + ".jsonl"), text + "\n", "run " + runId + " " + ACKNOWLEDGED);
    }

    /**
     * The ledger must have one history, and git must show it as its commits hold it. Refused are the things that
     * make git show another one, none of which Traceworks ever writes:
     * <ul>
     * <li>replace refs ({@code refs/replace/}), a grafts file ({@code .git/info/grafts}) and a shallow file
     *     ({@code .git/shallow}), which swap or cut commits for every reader;</li>
     * <li>alternates ({@code .git/objects/info/alternates}) and a {@code .git} that is a file or a link, or has a
     *     {@code commondir}: objects and refs of another repository;</li>
     * <li>a HEAD that names no branch, a second branch, and any commit that a ref holds and HEAD does not reach: a
     *     history that was rewound, or continued somewhere else;</li>
     * <li>a key in the ledger's own configuration other than those {@code git init} writes or a push needs.</li>
     * </ul>
     * A ledger that does not exist yet has none of these.
     *
     * @throws IllegalStateException that names what was found
     */
    private void requireOneHistory() {
        Path dotGit = directory.resolve(".git");
        if (!Files.exists(dotGit, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(dotGit, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw tampered(".git is no directory of its own");
        }
        for (String file : List.of("shallow", "info/grafts", "objects/info/alternates", "commondir")) {
            if (Files.exists(dotGit.resolve(file), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw tampered("it has the file .git/" + file);
            }
        }
        GitProcess.Result config = run("--git-dir=.git", "--work-tree=.", "config", "--local", "--no-includes",
                "--list", "--name-only");
        requireRead(config, "its own configuration cannot be listed");
        for (String key : config.outputText().split("\n")) {
            if (!key.isBlank() && !CONFIG_KEY.matcher(key.strip()).matches()) {
                throw tampered("its own git configuration sets " + key.strip());
            }
        }
        GitProcess.Result refs = run("--git-dir=.git", "--work-tree=.", "for-each-ref", "--format=%(refname)");
        requireRead(refs, "its references cannot be listed");
        List<String> branches = new java.util.ArrayList<>();
        for (String ref : refs.outputText().split("\n")) {
            if (ref.startsWith("refs/replace/")) throw tampered("it has the replace ref " + ref);
            if (ref.startsWith("refs/heads/")) branches.add(ref);
        }
        if (branches.isEmpty()) return;   // no commit yet, or a damaged HEAD, which reading reports
        GitProcess.Result head = run("--git-dir=.git", "--work-tree=.", "symbolic-ref", "-q", "HEAD");
        if (head.exitCode() != 0) throw tampered("its HEAD names no branch");
        if (branches.size() > 1 || !branches.getFirst().equals(head.outputText().strip())) {
            throw tampered("it has the branches " + String.join(", ", branches) + ", and HEAD is "
                    + head.outputText().strip());
        }
        GitProcess.Result beside = run("--git-dir=.git", "--work-tree=.", "rev-list", "--max-count=1", "--all",
                "--not", "HEAD");
        requireRead(beside, "its commits cannot be listed");
        if (!beside.outputText().isBlank()) {
            throw tampered("a reference holds the commit " + beside.outputText().strip()
                    + ", which its HEAD does not reach: the history was rewound or goes on elsewhere");
        }
    }

    private IllegalStateException tampered(String what) {
        return new IllegalStateException("The ledger " + directory + " is not one history as Traceworks writes it: "
                + what);
    }

    /** The ledger's lock, held until closed. */
    private record Held(FileChannel channel, java.nio.channels.FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try (channel) {
                lock.release();
            } finally {
                JVM_LOCK.unlock();
            }
        }
    }

    /**
     * Takes the ledger's lock, for threads and for processes, and waits a bounded time for it: a lock that a hung
     * process holds must not stop every run for ever.
     *
     * @throws IllegalStateException that names the lock file when the wait is over
     */
    private Held hold() throws IOException {
        long deadline = System.nanoTime() + lockWaitMillis * 1_000_000L;
        boolean mine = false;
        FileChannel channel = null;
        try {
            if (!JVM_LOCK.tryLock(lockWaitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) throw lockHeld();
            mine = true;
            Files.createDirectories(lockFile.toAbsolutePath().getParent());
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                java.nio.channels.FileLock lock = null;
                try {
                    lock = channel.tryLock();
                } catch (java.nio.channels.OverlappingFileLockException e) {
                    // held through another channel of this process: waited for like any other holder
                }
                if (lock != null) return new Held(channel, lock);
                if (System.nanoTime() >= deadline) throw lockHeld();
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(channel, mine);
            throw new IllegalStateException("Interrupted while waiting for the ledger's lock " + lockFile, e);
        } catch (IOException | RuntimeException e) {
            close(channel, mine);
            throw e;
        }
    }

    private static void close(FileChannel channel, boolean mine) {
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
            // the failure that brought us here is the one to report
        } finally {
            if (mine) JVM_LOCK.unlock();
        }
    }

    private IllegalStateException lockHeld() {
        return new IllegalStateException("The ledger's lock " + lockFile + " is held by another process for more than "
                + (lockWaitMillis >= 1000 ? lockWaitMillis / 1000 + " s" : lockWaitMillis + " ms")
                + "; the ledger was not written. If no Traceworks or git process runs any more, a process that hangs"
                + " holds it: end that process");
    }

    /**
     * Adds a file that HEAD does not hold yet, in one commit of its own.
     *
     * @param taken what to say when HEAD holds the file already
     * @return the commit id
     */
    private String commitNew(Path relative, byte[] content, String message, String taken) {
        try {
            try (Held ignored = hold()) {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory.resolve(".git"))) {
                    git("-c", "init.defaultBranch=main", "-c", "init.templateDir=", "init", "-q");
                }
                if (inHead(relative).isPresent()) throw new IllegalStateException(taken);
                Path target = directory.resolve(relative);
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(directory, ".record-", ".tmp");
                try {
                    Files.write(temp, content);
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                    git("add", "--", relative.toString());
                    // --only: a file staged by an earlier, killed process must not ride along in this commit
                    git("commit", "-q", "-m", message, "--only", "--", relative.toString());
                } catch (RuntimeException | IOException e) {
                    Files.deleteIfExists(temp);
                    // git may have committed before it failed or timed out: then the entry is in the ledger
                    if (inHead(relative).isPresent()) return git("rev-parse", "HEAD").strip();
                    Files.deleteIfExists(target);
                    try {
                        git("reset", "-q");
                    } catch (RuntimeException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                    if (e instanceof RuntimeException runtime) throw runtime;
                    throw (IOException) e;
                }
                return git("rev-parse", "HEAD").strip();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the ledger " + directory, e);
        }
    }

    @Override
    public Optional<byte[]> find(String runId) {
        requireRunId(runId);
        return inHead(Path.of("runs", runId + ".json"));
    }

    /**
     * Adds one line to {@code registers/<key>.jsonl} and commits it as {@code register <key> seq <n>}. The file as
     * HEAD holds it is what the line is added to, so a file edited in the working tree does not count. The anchor
     * the file already ends with is not added again.
     */
    @Override
    public void anchorRegister(String key, int seq, String head) {
        Path relative = anchorFile(key);
        if (seq < 0 || head == null || !HEAD.matcher(head).matches()) {
            throw new IllegalArgumentException("Invalid anchor of the register " + key + ": seq " + seq);
        }
        append(relative, "{\"seq\":" + seq + ",\"head\":\"" + head + "\"}\n", "register " + key + " seq " + seq);
    }

    /**
     * Adds {@code line} to the file {@code relative} as HEAD holds it, in one commit. A file edited in the working
     * tree does not count, and the line the file already ends with is not added again.
     */
    private void append(Path relative, String line, String message) {
        try {
            try (Held ignored = hold()) {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory.resolve(".git"))) {
                    git("-c", "init.defaultBranch=main", "-c", "init.templateDir=", "init", "-q");
                }
                // Read strictly: a ledger that fails here must not get a commit that starts the file again with the
                // one new line, which would take every earlier anchor out of HEAD
                String before = new String(strictInHead(relative).orElse(new byte[0]), StandardCharsets.UTF_8);
                if (before.equals(line) || before.endsWith("\n" + line)) return;
                byte[] after = (before + line).getBytes(StandardCharsets.UTF_8);
                Path target = directory.resolve(relative);
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(directory, ".anchor-", ".tmp");
                try {
                    Files.write(temp, after);
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    git("add", "--", relative.toString());
                    git("commit", "-q", "-m", message, "--only", "--", relative.toString());
                } catch (RuntimeException | IOException e) {
                    Files.deleteIfExists(temp);
                    // git may have committed before it failed or timed out: then the line is in the ledger
                    if (inHead(relative).filter(now -> java.util.Arrays.equals(now, after)).isPresent()) return;
                    try {
                        git("reset", "-q");
                    } catch (RuntimeException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                    if (e instanceof RuntimeException runtime) throw runtime;
                    throw (IOException) e;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the ledger " + directory + ": " + e, e);
        }
    }

    /**
     * The anchors {@code registers/<key>.jsonl} holds in HEAD. None for a ledger that does not exist yet (no folder,
     * or a folder git has not been started in), for one without a commit, and for one whose HEAD does not hold the
     * file. A ledger that exists and cannot be read is an error, never an empty list: "no anchor" must not be what a
     * damaged ledger says.
     */
    @Override
    public List<RegisterAnchor> registerAnchors(String key) {
        Path relative = anchorFile(key);
        String text = new String(strictInHead(relative).orElse(new byte[0]), StandardCharsets.UTF_8);
        List<RegisterAnchor> anchors = new java.util.ArrayList<>();
        int number = 0;
        for (String line : text.split("\n")) {
            number++;
            if (line.isEmpty()) continue;
            java.util.regex.Matcher anchor = ANCHOR.matcher(line);
            if (!anchor.matches()) {
                throw new IllegalStateException("line " + number + " of " + relative + " in the ledger " + directory
                        + " is no anchor");
            }
            anchors.add(new RegisterAnchor(Integer.parseInt(anchor.group(1)), anchor.group(2)));
        }
        return List.copyOf(anchors);
    }

    /**
     * The content of a file in HEAD, telling "it is not there" from "the ledger cannot be read". Empty when the
     * ledger does not exist yet, has no commit, or its HEAD does not hold the file.
     *
     * @throws IllegalStateException if the ledger exists and git cannot read it: its HEAD names no commit that can be
     *                               read, the tree cannot be listed, or the file is listed and cannot be shown
     */
    private Optional<byte[]> strictInHead(Path relative) {
        if (!hasCommit()) return Optional.empty();
        GitProcess.Result listed = run("--git-dir=.git", "--work-tree=.", "ls-tree", "HEAD", "--", relative.toString());
        requireRead(listed, "the files of its HEAD cannot be listed");
        if (listed.outputText().isBlank()) return Optional.empty();
        GitProcess.Result shown = run("--git-dir=.git", "--work-tree=.", "show", "HEAD:" + relative);
        requireRead(shown, relative + " of its HEAD cannot be shown");
        return Optional.of(shown.output());
    }

    /**
     * Whether the ledger has a commit to read. Not for a ledger that does not exist yet (no folder, or a folder git
     * has not been started in) and for a new repository.
     *
     * @throws IllegalStateException if the ledger exists and git cannot read it: it is no directory, or its HEAD names
     *                               no commit that can be read
     */
    private boolean hasCommit() {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("The ledger " + directory + " cannot be read: it is no directory");
        }
        if (!Files.exists(directory.resolve(".git"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        // The repository is named: with a damaged .git, git would otherwise look for one in the folders above
        GitProcess.Result head = run("--git-dir=.git", "--work-tree=.", "rev-parse", "--verify", "-q", "HEAD");
        if (head.exitCode() == 1 && head.outputText().isBlank() && head.errorText().isBlank()) {
            // HEAD names a branch without a commit. That is a new repository, and it is also what a branch file
            // that is empty, zeroed or deleted looks like. A new repository has no history at all
            requireNoHistory();
            return false;
        }
        requireRead(head, "its HEAD cannot be read");
        // rev-parse only reads the name: whether the commit it names is there is asked of the object store
        requireRead(run("--git-dir=.git", "--work-tree=.", "cat-file", "-e", head.outputText().strip() + "^{commit}"),
                "the commit its HEAD names is not there");
        return true;
    }

    /**
     * A repository whose HEAD names no commit holds nothing: no branch or tag, and no object, loose or packed. A
     * branch file that was emptied, zeroed or deleted leaves the commits it named in the object store, so such a
     * ledger is told from a new one by its objects; only a repository emptied altogether reads as new, and nothing
     * can tell that one from a new one.
     *
     * @throws IllegalStateException if the repository holds a reference or an object
     */
    private void requireNoHistory() {
        GitProcess.Result refs = run("--git-dir=.git", "--work-tree=.", "for-each-ref");
        requireRead(refs, "its references cannot be listed");
        GitProcess.Result objects = run("--git-dir=.git", "--work-tree=.", "count-objects", "-v");
        requireRead(objects, "its objects cannot be counted");
        boolean counted = false;
        boolean none = refs.outputText().isBlank();
        for (String line : objects.outputText().split("\n")) {
            if (line.startsWith("count: ") || line.startsWith("in-pack: ")) {
                counted = true;
                none &= line.substring(line.indexOf(' ') + 1).strip().equals("0");
            }
        }
        if (!counted || !none) {
            throw new IllegalStateException("The ledger " + directory + " cannot be read: its HEAD names no commit,"
                    + " but the repository holds history, so the file of its branch was emptied or removed");
        }
    }

    private void requireRead(GitProcess.Result result, String what) {
        if (result.exitCode() != 0) {
            throw new IllegalStateException("The ledger " + directory + " cannot be read: " + what + " (git exit "
                    + result.exitCode() + (result.errorText().isBlank() ? "" : ": " + result.errorText().strip()) + ")");
        }
    }

    private static Path anchorFile(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid register key: " + key);
        }
        return Path.of("registers", key + ".jsonl");
    }

    private static void requireRunId(String runId) {
        if (!Ids.isRunId(runId)) throw new IllegalArgumentException("Invalid run id: " + runId);
    }

    /** The content of a file in HEAD, or empty when there is no such file or no HEAD yet. */
    private Optional<byte[]> inHead(Path relative) {
        if (!Files.isDirectory(directory.resolve(".git"))) return Optional.empty();
        GitProcess.Result result = run("show", "HEAD:" + relative);
        return result.exitCode() == 0 ? Optional.of(result.output()) : Optional.empty();
    }

    /** Runs git in the ledger directory and returns its output; any failure is an {@link IllegalStateException}. */
    private String git(String... args) {
        GitProcess.Result result = run(args);
        String what = "git " + String.join(" ", args);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(what + " failed with exit code " + result.exitCode() + ": "
                    + result.errorText() + result.outputText());
        }
        return result.outputText();
    }

    /**
     * Runs git in the ledger directory; a git that cannot run or does not finish in time is an
     * {@link IllegalStateException}.
     */
    private GitProcess.Result run(String... args) {
        String what = "git " + String.join(" ", args);
        try {
            return git.run(directory, List.of(args));
        } catch (GitProcess.TimedOut e) {
            throw new IllegalStateException(what + " did not finish within " + TIMEOUT_SECONDS + " seconds", e);
        } catch (IOException e) {
            throw new IllegalStateException(what + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " was interrupted", e);
        }
    }
}
