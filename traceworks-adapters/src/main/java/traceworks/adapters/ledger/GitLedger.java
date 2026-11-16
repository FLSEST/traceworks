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

    /** The line of a commit message that names the SHA-256 of the entry the commit adds. */
    private static final java.util.regex.Pattern PIN = java.util.regex.Pattern.compile("sha256 [0-9a-f]{64}");
    /** The path of an entry of a run, or of the anchors of a register. */
    private static final java.util.regex.Pattern ENTRY_PATH =
            java.util.regex.Pattern.compile("(starts|runs|acks|registers)/([^/]+)\\.jsonl?");

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
            // what git init writes: the format of the repository and what the file system can do
            "core\\.(repositoryformatversion|filemode|bare|logallrefupdates|ignorecase|precomposeunicode|symlinks)"
                    + "|extensions\\.objectformat"
                    // where and how a push or a fetch connects, and as whom: nothing git reads the history with
                    + "|core\\.sshcommand|(remote|branch|push|fetch|pull|credential|http|url)\\..*"
                    // the identity of commits the operator makes by hand (Traceworks sets its own), and when git
                    // packs or prunes objects, which changes how they are stored, not what they are
                    + "|(user|gc|maintenance)\\..*");

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
                initOrRequirePlain();
                git("rev-parse", "--git-dir");
                // The same rule for a start as for a check: a ledger a check would refuse takes no new run
                requireOneHistory();
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

    /** A commit whose message names an entry of a run, with the content it says it adds. */
    private static final class Naming {
        final String commit;
        final String subject;
        /** Where the commit is in the log, newest first. */
        final int position;
        /** The SHA-256 of the entry the message names ({@code sha256 <hex>}); {@code null} for a message without. */
        String pin;

        Naming(String commit, String subject, int position) {
            this.commit = commit;
            this.subject = subject;
            this.position = position;
        }

        String who() {
            return "commit " + commit.substring(0, Math.min(12, commit.length())) + " (\"" + subject + "\")";
        }
    }

    /**
     * Every commit whose subject names a run ({@code run <run-id> <WORD>}), by subject, oldest first: one pass over
     * the messages, which reads no tree and so stays linear in the length of the history.
     */
    private Map<String, List<Naming>> namings() {
        GitProcess.Result log = run("--git-dir=.git", "--work-tree=.", "log", "--encoding=UTF-8",
                "--format=%x01%H%x09%s%n%b", "HEAD");
        requireRead(log, "its history cannot be listed");
        Map<String, List<Naming>> bySubject = new java.util.LinkedHashMap<>();
        Naming current = null;
        int position = 0;
        for (String line : log.outputText().split("\n")) {
            if (line.startsWith("\u0001")) {
                int tab = line.indexOf('\t');
                String subject = tab < 0 ? "" : line.substring(tab + 1);
                java.util.regex.Matcher entry = SUBJECT.matcher(subject);
                current = entry.matches() && Ids.isRunId(entry.group(1))
                        ? new Naming(line.substring(1, tab), subject, position) : null;
                position++;
                if (current != null) {
                    // The log is newest first: each list is turned around by adding at the front
                    bySubject.computeIfAbsent(subject, key -> new java.util.LinkedList<>()).addFirst(current);
                }
            } else if (current != null && current.pin == null && PIN.matcher(line).matches()) {
                current.pin = line.substring("sha256 ".length());
            }
        }
        return bySubject;
    }

    /**
     * Every run the ledger names. The commit messages say which runs started and ended ({@code run <run-id> STARTED},
     * {@code run <run-id> <STATUS>}): a later commit can remove a file, not an earlier message. The files of HEAD
     * are what the ledger holds now: each is read from the working tree when the file there is the object HEAD names,
     * else from the object store. Nothing for a ledger that does not exist yet or has no commit.
     *
     * <p>An entry must be what the commit that names it added ({@link LedgerRuns.Run#changes}). The message of that
     * commit holds the SHA-256 of the entry, so this is one comparison per entry and no walk over the trees, which
     * would grow with the square of the ledger (every tree of {@code starts/} and {@code runs/} lists every run).
     * Only for an entry that does not compare, and for the entries of the time before messages held the hash, the
     * commits that touched the path are looked up, to name them.
     *
     * @throws IllegalStateException if the ledger cannot be read, or holds something that lets git show another
     *                               history than its commits ({@link #requireOneHistory})
     */
    @Override
    public LedgerRuns runs() {
        requireOneHistory();
        if (!hasCommit()) return new LedgerRuns(List.of());
        Map<String, List<Naming>> namings = namings();
        Map<String, boolean[]> startNamed = new java.util.TreeMap<>();
        Map<String, String> endNamed = new java.util.HashMap<>();
        Map<String, List<Naming>> startNamings = new java.util.HashMap<>();
        Map<String, List<Naming>> endNamings = new java.util.HashMap<>();
        namings.forEach((subject, commits) -> {
            java.util.regex.Matcher entry = SUBJECT.matcher(subject);
            if (!entry.matches()) return;
            String id = entry.group(1);
            boolean[] named = startNamed.computeIfAbsent(id, key -> new boolean[1]);
            if (entry.group(2).equals(STARTED)) {
                named[0] = true;
                startNamings.computeIfAbsent(id, key -> new java.util.ArrayList<>()).addAll(commits);
            } else if (FINAL.contains(entry.group(2))) {
                endNamings.computeIfAbsent(id, key -> new java.util.ArrayList<>()).addAll(commits);
            }
        });
        // The oldest message of an end is the one that counts
        endNamings.forEach((id, commits) -> {
            commits.sort(java.util.Comparator.comparingInt((Naming naming) -> naming.position).reversed());
            String subject = commits.getFirst().subject;
            endNamed.put(id, subject.substring(subject.lastIndexOf(' ') + 1));
        });
        GitProcess.Result tree = run("--git-dir=.git", "--work-tree=.", "-c", "core.quotePath=false", "ls-tree",
                "-r", "HEAD", "--", "starts", "runs", "acks");
        requireRead(tree, "the files of its HEAD cannot be listed");
        Map<String, byte[]> starts = new java.util.HashMap<>();
        Map<String, byte[]> ends = new java.util.HashMap<>();
        Map<String, byte[]> acks = new java.util.HashMap<>();
        Map<String, String> blobs = new java.util.HashMap<>();
        for (String line : tree.outputText().split("\n")) {
            java.util.regex.Matcher file = TREE_LINE.matcher(line);
            if (!file.matches() || !Ids.isRunId(file.group(4))) continue;
            boolean lines = file.group(5).equals("jsonl");
            if (lines != file.group(3).equals("acks")) continue;
            startNamed.computeIfAbsent(file.group(4), id -> new boolean[1]);
            blobs.put(file.group(2), file.group(1));
            (lines ? acks : file.group(3).equals("starts") ? starts : ends)
                    .put(file.group(4), blob(file.group(1), file.group(2)));
        }

        // Which entries are not, or cannot be said to be, what the commit that names them added
        Map<String, List<String>> changed = new java.util.HashMap<>();
        java.util.Set<String> suspicious = new java.util.TreeSet<>();
        Map<String, Naming> legacy = new java.util.HashMap<>();
        Map<String, String> pinnedOtherwise = new java.util.HashMap<>();
        for (String id : startNamed.keySet()) {
            screen("starts/" + id + ".json", starts.get(id), startNamings.getOrDefault(id, List.of()), suspicious,
                    legacy, pinnedOtherwise);
            screen("runs/" + id + ".json", ends.get(id), endNamings.getOrDefault(id, List.of()), suspicious, legacy,
                    pinnedOtherwise);
        }
        Map<String, List<Touch>> touched = new java.util.HashMap<>();
        if (!legacy.isEmpty()) {
            // The entries of the time before messages held the hash: up to the newest of their commits the history
            // is walked, which costs what that part of the ledger costs and no more as the ledger grows; since
            // then, an entry is the same object or it is looked up
            Naming newest = legacy.values().stream().min(java.util.Comparator.comparingInt(naming -> naming.position))
                    .orElseThrow();
            Map<String, List<Touch>> early = touches(newest.commit, "starts", "runs");
            GitProcess.Result then = run("--git-dir=.git", "--work-tree=.", "-c", "core.quotePath=false", "ls-tree",
                    "-r", newest.commit, "--", "starts", "runs");
            requireRead(then, "the files of its commit " + newest.commit + " cannot be listed");
            Map<String, String> blobsThen = new java.util.HashMap<>();
            for (String line : then.outputText().split("\n")) {
                java.util.regex.Matcher file = TREE_LINE.matcher(line);
                if (file.matches()) blobsThen.put(file.group(2), file.group(1));
            }
            for (String path : legacy.keySet()) {
                if (!java.util.Objects.equals(blobsThen.get(path), blobs.get(path))) {
                    suspicious.add(path);
                } else if (early.containsKey(path)) {
                    touched.put(path, early.get(path));
                }
            }
        }
        if (!suspicious.isEmpty()) {
            touched.putAll(suspicious.size() > 100 ? touches("HEAD", "starts", "runs")
                    : touches("HEAD", suspicious.toArray(String[]::new)));
        }
        Set<String> rewrites = rewrites("acks", "registers");
        touched.putAll(touches("HEAD", "acks", "registers"));
        List<String> anchors = new java.util.ArrayList<>();
        java.util.Set<String> paths = new java.util.TreeSet<>(touched.keySet());
        paths.addAll(suspicious);
        for (String path : paths) {
            java.util.regex.Matcher entry = ENTRY_PATH.matcher(path);
            if (!entry.matches()) continue;
            List<String> found = touched.containsKey(path) ? changes(path, touched.get(path), rewrites)
                    : new java.util.ArrayList<>();
            if (found.isEmpty() && pinnedOtherwise.containsKey(path)) {
                found.add(pinnedOtherwise.get(path));
            }
            if (found.isEmpty()) continue;
            if (entry.group(1).equals("registers")) {
                anchors.addAll(found);
            } else if (Ids.isRunId(entry.group(2))) {
                startNamed.computeIfAbsent(entry.group(2), id -> new boolean[1]);
                changed.computeIfAbsent(entry.group(2), id -> new java.util.ArrayList<>()).addAll(found);
            }
        }
        if (!anchors.isEmpty()) {
            throw tampered("the anchors of a register were rewritten: " + String.join("; ", anchors));
        }
        List<LedgerRuns.Run> runs = new java.util.ArrayList<>();
        startNamed.forEach((id, named) -> runs.add(new LedgerRuns.Run(id, named[0], starts.get(id), endNamed.get(id),
                ends.get(id), acks.get(id), changed.getOrDefault(id, List.of()))));
        return new LedgerRuns(runs);
    }

    /**
     * Whether an entry is what the one commit that names it says it added, from the messages and the content alone.
     * An entry that is not, or that only a look at the commits that touched its path can judge, goes to
     * {@code suspicious}; one of the time before messages held the hash goes to {@code legacy}.
     *
     * @param content         what HEAD holds at the path; {@code null} for nothing
     * @param commits         the commits whose message names the entry
     * @param pinnedOtherwise what to say of a path whose content is not the one its commit names, when no other
     *                        commit touched it
     */
    private static void screen(String path, byte[] content, List<Naming> commits, java.util.Set<String> suspicious,
                               Map<String, Naming> legacy, Map<String, String> pinnedOtherwise) {
        if (content == null && commits.isEmpty()) return;
        if (content == null || commits.size() != 1) {
            // named and not there; there and named by none; or named more than once
            suspicious.add(path);
            if (commits.size() > 1) {
                pinnedOtherwise.put(path, commits.size() + " commits name " + path + " ("
                        + String.join(", ", commits.stream().map(Naming::who).toList()) + "): an entry is added once");
            }
            return;
        }
        Naming naming = commits.getFirst();
        if (naming.pin == null) {
            legacy.put(path, naming);
        } else if (!naming.pin.equals(sha256(content))) {
            suspicious.add(path);
            pinnedOtherwise.put(path, path + " is not the entry " + naming.who() + " added: its message names the"
                    + " SHA-256 " + naming.pin + ", and the ledger holds " + sha256(content));
        }
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the history did to the entries of one run: {@link #runs()} for that run. */
    @Override
    public List<String> changes(String runId) {
        requireRunId(runId);
        return runs().runs().stream().filter(run -> run.runId().equals(runId)).findFirst()
                .map(LedgerRuns.Run::changes).orElse(List.of());
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
        requirePlain(dotGit);
        for (String file : List.of("shallow", "info/grafts", "objects/info/alternates")) {
            if (Files.exists(dotGit.resolve(file), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw tampered("it has the file .git/" + file);
            }
        }
        GitProcess.Result config = run("--git-dir=.git", "--work-tree=.", "config", "--local", "--no-includes",
                "--list", "--name-only");
        requireRead(config, "its own configuration cannot be listed");
        for (String key : config.outputText().split("\n")) {
            if (!key.isBlank() && !CONFIG_KEY.matcher(key.strip()).matches()) {
                throw tampered("its own git configuration sets " + key.strip() + ", which is not among the settings"
                        + " the ledger may have (those git init writes and those a push needs): it could change what"
                        + " git writes or shows. Remove it with 'git -C " + directory + " config --unset "
                        + key.strip() + "'; give a setting only a push needs on the command line of the push"
                        + " ('git -c " + key.strip() + "=... push'), or push from a clone");
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
        // A merge can bring in a change to an entry that the walk over each commit's own changes does not show
        GitProcess.Result merges = run("--git-dir=.git", "--work-tree=.", "rev-list", "--merges", "--max-count=1",
                "HEAD");
        requireRead(merges, "its commits cannot be listed");
        if (!merges.outputText().isBlank()) {
            throw tampered("its history holds the merge commit " + merges.outputText().strip());
        }
        GitProcess.Result beside = run("--git-dir=.git", "--work-tree=.", "rev-list", "--max-count=1", "--all",
                "--not", "HEAD");
        requireRead(beside, "its commits cannot be listed");
        if (!beside.outputText().isBlank()) {
            throw tampered("a reference holds the commit " + beside.outputText().strip()
                    + ", which its HEAD does not reach: the history was rewound or goes on elsewhere");
        }
    }

    /**
     * Makes the repository when the ledger has none, and refuses one whose {@code .git} is not a plain directory of
     * its own: a {@code .git} file or link, or a {@code commondir}, would send the commits to another repository.
     * Every writer calls it, under the lock.
     */
    private void initOrRequirePlain() {
        Path dotGit = directory.resolve(".git");
        if (!Files.exists(dotGit, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            git("-c", "init.defaultBranch=main", "-c", "init.templateDir=", "init", "-q");
            return;
        }
        requirePlain(dotGit);
    }

    private void requirePlain(Path dotGit) {
        if (!Files.isDirectory(dotGit, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw tampered(".git is no directory of its own, but a file or a link that names another repository");
        }
        if (Files.exists(dotGit.resolve("commondir"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw tampered("it has the file .git/commondir, which names another repository");
        }
    }

    /** A commit's own change to one path, as {@code git log --raw} lists it. */
    private record Touch(String commit, String subject, String status, String path) {

        String who() {
            return "commit " + commit.substring(0, Math.min(12, commit.length())) + " (\"" + subject + "\")";
        }
    }

    /**
     * Every change the commits up to {@code from} made to the paths, oldest first by path, with no content read.
     * The history has no merge ({@link #requireOneHistory}), so each commit's own change is all there is. A walk
     * over {@code starts/} or {@code runs/} reads a tree that lists every run for every commit, so it is made for the
     * few paths that need it, not for the whole ledger.
     */
    private Map<String, List<Touch>> touches(String from, String... paths) {
        List<String> args = new java.util.ArrayList<>(List.of("--git-dir=.git", "--work-tree=.", "-c",
                "core.quotePath=false", "log", "--no-renames", "--raw", "--no-abbrev", "--encoding=UTF-8",
                "--format=%x01%H%x09%s", from, "--"));
        args.addAll(List.of(paths));
        GitProcess.Result log = run(args.toArray(String[]::new));
        requireRead(log, "the changes of its history cannot be listed");
        Map<String, List<Touch>> byPath = new java.util.TreeMap<>();
        String commit = null;
        String subject = null;
        for (String line : log.outputText().split("\n")) {
            if (line.startsWith("\u0001")) {
                int tab = line.indexOf('\t');
                commit = line.substring(1, tab < 0 ? line.length() : tab);
                subject = tab < 0 ? "" : line.substring(tab + 1);
            } else if (line.startsWith(":") && commit != null) {
                int tab = line.indexOf('\t');
                String[] fields = line.substring(0, tab).split(" ");
                // The log is newest first: each path's list is turned around by adding at the front
                byPath.computeIfAbsent(line.substring(tab + 1), path -> new java.util.LinkedList<>())
                        .addFirst(new Touch(commit, subject, fields[fields.length - 1].substring(0, 1), null));
            }
        }
        return byPath;
    }

    /**
     * The commits that took lines out of a file that is only ever added to, as {@code commit + " " + path}: a second
     * walk, over the acknowledgements and the anchors only, that counts the lines each commit removed.
     */
    private Set<String> rewrites(String... paths) {
        List<String> args = new java.util.ArrayList<>(List.of("--git-dir=.git", "--work-tree=.", "-c",
                "core.quotePath=false", "log", "--no-renames", "--numstat", "--format=%x01%H", "HEAD", "--"));
        args.addAll(List.of(paths));
        GitProcess.Result log = run(args.toArray(String[]::new));
        requireRead(log, "the changes of its history cannot be listed");
        Set<String> found = new java.util.HashSet<>();
        String commit = null;
        for (String line : log.outputText().split("\n")) {
            if (line.startsWith("\u0001")) {
                commit = line.substring(1);
            } else if (commit != null && !line.isBlank()) {
                String[] fields = line.split("\t", 3);
                // "-" is a file git takes for binary: not a file of lines that was added to
                if (fields.length == 3 && !fields[1].equals("0")) found.add(commit + " " + fields[2]);
            }
        }
        return found;
    }

    /** What a later commit did to a path: removed it, added it again, or changed it. */
    private static String did(Touch touch, String path) {
        return touch.who() + (touch.status().equals("D") ? " removed " : touch.status().equals("A") ? " added again "
                : " changed ") + path;
    }

    /**
     * What the commits did to an entry that Traceworks never does. A start entry and an end entry are added once,
     * by the commit whose message names them, and never touched again: so what HEAD holds is what that commit added.
     * The acknowledgements of a run and the anchors of a register are added to, each time by a commit that says so,
     * and never removed.
     *
     * @return one sentence per such change, oldest first; empty for a path as Traceworks writes it
     */
    private static List<String> changes(String path, List<Touch> touches, Set<String> rewrites) {
        List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher entry = ENTRY_PATH.matcher(path);
        if (!entry.matches()) return found;
        String kind = entry.group(1);
        String name = entry.group(2);
        Touch first = touches.getFirst();
        if (kind.equals("starts") || kind.equals("runs")) {
            boolean named = kind.equals("starts") ? first.subject().equals("run " + name + " " + STARTED)
                    : first.subject().startsWith("run " + name + " ")
                    && FINAL.contains(first.subject().substring(("run " + name + " ").length()));
            if (!named) {
                found.add(first.who() + " added " + path + " under another message than the one of this entry");
            }
            for (Touch later : touches.subList(1, touches.size())) {
                found.add(did(later, path) + ", which " + first.who() + " added: an entry is never touched again");
            }
            return found;
        }
        String expected = kind.equals("acks") ? "run " + name + " " + ACKNOWLEDGED : "register " + name + " seq ";
        for (Touch touch : touches) {
            boolean named = kind.equals("acks") ? touch.subject().equals(expected)
                    : touch.subject().startsWith(expected);
            if (touch.status().equals("D")) {
                found.add(did(touch, path) + ": it is only ever added to");
            } else if (rewrites.contains(touch.commit() + " " + path)) {
                found.add(touch.who() + " took lines out of " + path + ": it is only ever added to");
            } else if (!named) {
                found.add(did(touch, path) + " under another message than '" + expected
                        + (kind.equals("acks") ? "'" : "<n>'"));
            }
        }
        return found;
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
                initOrRequirePlain();
                if (inHead(relative).isPresent()) throw new IllegalStateException(taken);
                Path target = directory.resolve(relative);
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(directory, ".record-", ".tmp");
                try {
                    Files.write(temp, content);
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                    git("add", "--", relative.toString());
                    // --only: a file staged by an earlier, killed process must not ride along in this commit
                    // The message names the entry's content, so that it can later be said, without a walk over the
                    // trees, that the entry is still what this commit added
                    git("commit", "-q", "-m", message, "-m", "sha256 " + sha256(content), "--only", "--",
                            relative.toString());
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
                initOrRequirePlain();
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
