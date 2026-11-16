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
import java.util.TreeSet;
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
    private static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile("(starts|runs)/([^/]+)\\.json");

    private final Path directory;
    private final Path lockFile;
    private final GitProcess git;

    public GitLedger(Path ledgerDirectory, Path lockFile) {
        this(ledgerDirectory, lockFile, Map.of());
    }

    /** With extra child environment variables (for tests); the ledger's own variables take precedence. */
    GitLedger(Path ledgerDirectory, Path lockFile, Map<String, String> environment) {
        this.directory = ledgerDirectory;
        this.lockFile = lockFile;
        // The operator's configuration, an inherited GIT_DIR, GIT_INDEX_FILE, GIT_CONFIG_* or date would redirect or
        // alter the commit: the ledger's own variables replace them
        this.git = new GitProcess("git", List.of("-c", "commit.gpgsign=false"), environment, Map.of(
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
        JVM_LOCK.lock();
        try {
            Files.createDirectories(lockFile.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory.resolve(".git"))) {
                    git("-c", "init.defaultBranch=main", "-c", "init.templateDir=", "init", "-q");
                }
                git("rev-parse", "--git-dir");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the ledger " + directory + ": " + e, e);
        } finally {
            JVM_LOCK.unlock();
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
        return strictInHead(Path.of("starts", runId + ".json"));
    }

    /**
     * Every run the history names, and every entry HEAD holds no more. The commit messages say which runs started and
     * ended ({@code run <run-id> STARTED}, {@code run <run-id> <STATUS>}): a later commit can remove a file, not an
     * earlier message, and reading messages needs no diff, so it stays fast on a long history. The files of HEAD say
     * what is still there. Nothing for a ledger that does not exist yet or has no commit.
     */
    @Override
    public LedgerRuns runs() {
        Set<String> started = new TreeSet<>();
        Set<String> ended = new TreeSet<>();
        List<String> removed = new java.util.ArrayList<>();
        if (!hasCommit()) return new LedgerRuns(started, ended, removed);
        GitProcess.Result log = run("--git-dir=.git", "--work-tree=.", "log", "--format=%s", "HEAD");
        requireRead(log, "its history cannot be listed");
        for (String subject : log.outputText().split("\n")) {
            java.util.regex.Matcher entry = SUBJECT.matcher(subject);
            if (entry.matches() && Ids.isRunId(entry.group(1))) {
                (entry.group(2).equals(STARTED) ? started : ended).add(entry.group(1));
            }
        }
        GitProcess.Result files = run("--git-dir=.git", "--work-tree=.", "-c", "core.quotePath=false", "ls-tree",
                "-r", "--name-only", "HEAD", "--", "starts", "runs");
        requireRead(files, "the files of its HEAD cannot be listed");
        Set<String> held = new java.util.HashSet<>(List.of(files.outputText().split("\n")));
        for (String path : held) {
            // An entry that is there counts, whatever the message of its commit says
            java.util.regex.Matcher entry = ENTRY.matcher(path);
            if (entry.matches() && Ids.isRunId(entry.group(2))) {
                (entry.group(1).equals("starts") ? started : ended).add(entry.group(2));
            }
        }
        started.stream().map(id -> "starts/" + id + ".json").filter(path -> !held.contains(path)).forEach(removed::add);
        ended.stream().map(id -> "runs/" + id + ".json").filter(path -> !held.contains(path)).forEach(removed::add);
        java.util.Collections.sort(removed);
        return new LedgerRuns(started, ended, removed);
    }

    /**
     * Adds a file that HEAD does not hold yet, in one commit of its own.
     *
     * @param taken what to say when HEAD holds the file already
     * @return the commit id
     */
    private String commitNew(Path relative, byte[] content, String message, String taken) {
        JVM_LOCK.lock();
        try {
            Files.createDirectories(lockFile.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
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
        } finally {
            JVM_LOCK.unlock();
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
        String line = "{\"seq\":" + seq + ",\"head\":\"" + head + "\"}\n";
        JVM_LOCK.lock();
        try {
            Files.createDirectories(lockFile.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
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
                    git("commit", "-q", "-m", "register " + key + " seq " + seq, "--only", "--", relative.toString());
                } catch (RuntimeException | IOException e) {
                    Files.deleteIfExists(temp);
                    // git may have committed before it failed or timed out: then the anchor is in the ledger
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
        } finally {
            JVM_LOCK.unlock();
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
