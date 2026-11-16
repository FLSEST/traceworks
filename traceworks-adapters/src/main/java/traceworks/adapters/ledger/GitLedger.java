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
 * byte-identical copy of the manifest, with the message {@code run <run-id> <STATUS>}; and one commit per write of a
 * register adds a line to {@code registers/<key>.jsonl}, the anchor of the register's head, with the message
 * {@code register <key> seq <n>}. Every message ends with {@code  sha256:<hex>}, the SHA-256 of the file after the
 * commit, which is what a reader later judges the file by ({@link #judge}).
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
    private static final String RUN_ID = "[A-Za-z0-9][A-Za-z0-9._-]*";
    private static final String SHA256 = "[0-9a-f]{64}";
    /** A record of the log: the full id of a commit, a space, the subject. */
    private static final java.util.regex.Pattern RECORD =
            java.util.regex.Pattern.compile("([0-9a-f]{40}|[0-9a-f]{64}) ([^\\p{Cntrl}]*)");
    /** {@code run <run-id> <WORD>}, since messages hold it with {@code  sha256:<hex>} of the file after the commit. */
    private static final java.util.regex.Pattern SUBJECT =
            java.util.regex.Pattern.compile("run (" + RUN_ID + ") ([A-Z_]+)(?: sha256:(" + SHA256 + "))?");
    private static final java.util.regex.Pattern ANCHOR_SUBJECT = java.util.regex.Pattern.compile(
            "register (" + AuditConfig.KEY + ") seq \\d{1,9}(?: sha256:(" + SHA256 + "))?");
    private static final java.util.regex.Pattern PIN_SUBJECT =
            java.util.regex.Pattern.compile("ledger PINNED sha256:(" + SHA256 + ")");
    /** A line of a pin: the SHA-256 an entry had, two spaces, its path. */
    private static final java.util.regex.Pattern PINNED_LINE = java.util.regex.Pattern.compile(
            "(" + SHA256 + ")  ((?:starts|runs)/" + RUN_ID + "\\.json|acks/" + RUN_ID + "\\.jsonl|registers/"
                    + AuditConfig.KEY + "\\.jsonl)");
    /** A record of {@code ls-tree -r -t -z}: mode, type, object id, a tab, the path. */
    private static final java.util.regex.Pattern LISTED =
            java.util.regex.Pattern.compile("(\\d{6}) (\\w+) ([0-9a-f]{40}|[0-9a-f]{64})\\t(.+)",
                    java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern FOLDER =
            java.util.regex.Pattern.compile("starts|runs|acks|registers|pins");
    /** Every path the ledger holds a file at. */
    private static final java.util.regex.Pattern ENTRY_PATH = java.util.regex.Pattern.compile(
            "(?:starts|runs)/" + RUN_ID + "\\.json|acks/" + RUN_ID + "\\.jsonl|registers/" + AuditConfig.KEY
                    + "\\.jsonl|pins/" + SHA256 + "\\.txt");
    /** The path of an entry of a run. */
    private static final java.util.regex.Pattern RUN_PATH =
            java.util.regex.Pattern.compile("(starts|runs|acks)/(" + RUN_ID + ")\\.jsonl?");

    /** How long a writer waits for the ledger's lock before it gives up. */
    static final int LOCK_WAIT_SECONDS = 60;
    /** The status an acknowledgement has in its commit message; no run status has this name. */
    static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final Set<String> FINAL = Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED");
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
                    // the identity of commits the operator makes by hand (Traceworks sets its own), and the
                    // housekeeping of the operator's own git calls: Traceworks' calls run none (gc.auto=0)
                    + "|(user|gc|maintenance)\\..*");

    /** A remote git may fetch missing objects from by itself: not a setting a push needs. */
    private static final java.util.regex.Pattern PROMISOR_KEY =
            java.util.regex.Pattern.compile("remote\\..*\\.(promisor|partialclonefilter)");

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
        // No housekeeping inside a commit: gc.auto and maintenance.auto are switched off for every call, so no
        // setting of gc (gc.recentObjectsHook names a command) takes effect in a call Traceworks makes. And no
        // attributes of the user: HOME and XDG_CONFIG_HOME name no directory
        this.git = new GitProcess("git", List.of("--no-replace-objects", "-c", "commit.gpgsign=false",
                "-c", "core.commitGraph=false", "-c", "gc.auto=0", "-c", "maintenance.auto=false",
                "-c", "core.attributesFile=/dev/null"), environment, Map.ofEntries(
                Map.entry("HOME", "/dev/null"),
                Map.entry("XDG_CONFIG_HOME", "/dev/null"),
                Map.entry("GIT_NO_REPLACE_OBJECTS", "1"),
                Map.entry("GIT_CONFIG_GLOBAL", "/dev/null"),
                Map.entry("GIT_CONFIG_NOSYSTEM", "1"),
                Map.entry("GIT_AUTHOR_NAME", IDENTITY_NAME),
                Map.entry("GIT_AUTHOR_EMAIL", IDENTITY_EMAIL),
                Map.entry("GIT_COMMITTER_NAME", IDENTITY_NAME),
                Map.entry("GIT_COMMITTER_EMAIL", IDENTITY_EMAIL)), TIMEOUT_SECONDS * 1000L);
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
                pinWhatNoHashCovers();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the ledger " + directory + ": " + e, e);
        }
    }

    @Override
    public String record(String runId, String status, byte[] manifestJson) {
        requireRunId(runId);
        return commit("runs/" + runId + ".json", manifestJson, "run " + runId + " " + status, false,
                "Run is already recorded: " + runId);
    }

    /** One commit adds {@code starts/<run-id>.json} with the message {@code run <run-id> STARTED sha256:<hex>}. */
    @Override
    public String recordStart(String runId, byte[] startJson) {
        requireRunId(runId);
        return commit("starts/" + runId + ".json", startJson, "run " + runId + " " + STARTED, false,
                "The start of the run is already noted: " + runId);
    }

    @Override
    public Optional<byte[]> findStart(String runId) {
        requireRunId(runId);
        requireOneHistory();
        return held("starts/" + runId + ".json");
    }

    /** The end entry of a run; empty also for a ledger that cannot be read, so that a reader finds none. */
    @Override
    public Optional<byte[]> find(String runId) {
        requireRunId(runId);
        try {
            return held("runs/" + runId + ".json");
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    /**
     * What HEAD holds, by path: the object id of every file. This one listing is the only source of what the ledger
     * holds; every reader takes the content by the id it gives, never by a path, which git resolves its own way. The
     * ledger holds regular files at the paths of its entries and nothing else: a path listed twice (a tree can be
     * made to hold a name twice), a link, an executable file, a submodule or any other file fails here, by name.
     *
     * @return empty for a ledger that does not exist yet or has no commit
     * @throws IllegalStateException if the ledger cannot be read, or holds something that is no entry
     */
    private Map<String, String> listing() {
        Map<String, String> files = new java.util.TreeMap<>();
        if (!hasCommit()) return files;
        GitProcess.Result tree = run("--git-dir=.git", "--work-tree=.", "ls-tree", "-r", "-t", "-z", "HEAD");
        requireRead(tree, "the files of its HEAD cannot be listed");
        Set<String> seen = new java.util.HashSet<>();
        for (String line : tree.outputText().split("\0")) {
            if (line.isEmpty()) continue;
            java.util.regex.Matcher entry = LISTED.matcher(line);
            if (!entry.matches()) throw tampered("its HEAD lists something that cannot be read as a file");
            String mode = entry.group(1);
            String type = entry.group(2);
            String path = entry.group(4);
            if (!seen.add(path)) throw tampered("its HEAD lists " + path + " twice");
            boolean folder = type.equals("tree") && mode.equals("040000") && FOLDER.matcher(path).matches();
            boolean file = type.equals("blob") && mode.equals("100644") && ENTRY_PATH.matcher(path).matches();
            if (!folder && !file) {
                throw tampered("its HEAD holds " + path + " (" + type + ", mode " + mode + "), which is no entry of a"
                        + " ledger: a regular file under starts/, runs/, acks/, registers/ or pins/. Take it out"
                        + " with 'git -C " + directory + " rm " + path + "' and a commit");
            }
            if (file) files.put(path, entry.group(3));
        }
        return files;
    }

    /** The content HEAD holds at {@code path}; empty when it holds none. */
    private Optional<byte[]> held(String path) {
        String id = listing().get(path);
        return id == null ? Optional.empty() : Optional.of(blob(id, path, false));
    }

    /**
     * The content of the object {@code id}, which the listing gives for {@code path}.
     *
     * @param fromFile whether the file in the working tree will do when it is this object, by its id: for a reader
     *                 of every entry, which would else start git once per entry. A reader of one entry asks the
     *                 object store, so that an object that is gone is found
     */
    private byte[] blob(String id, String path, boolean fromFile) {
        try {
            Path file = directory.resolve(path);
            if (fromFile && Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                byte[] content = Files.readAllBytes(file);
                if (blobId(content, id.length()).equals(id)) return content;
            }
        } catch (IOException e) {
            // the object store has it
        }
        GitProcess.Result shown = run("--git-dir=.git", "--work-tree=.", "cat-file", "blob", id);
        requireRead(shown, path + " of its HEAD cannot be shown");
        return shown.output();
    }

    /** The id git gives a file of this content, in a repository whose ids have {@code length} hex digits. */
    private static String blobId(byte[] content, int length) {
        return hex(length == 40 ? "SHA-1" : "SHA-256", ("blob " + content.length + "\0")
                .getBytes(StandardCharsets.US_ASCII), content);
    }

    private static String sha256(byte[] content) {
        return hex("SHA-256", new byte[0], content);
    }

    private static String hex(String algorithm, byte[] head, byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
            digest.update(head);
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A commit whose subject is one Traceworks writes: it names a path and, since messages hold it, the SHA-256 of
     * what the path held after the commit.
     *
     * @param position where the commit is in the history, 0 for the newest
     * @param path     the path the subject names; for a pin, the pin file
     * @param word     the word of a run's subject ({@code STARTED}, a status, {@code ACKNOWLEDGED}); {@code null}
     *                 for an anchor and a pin
     * @param hash     the SHA-256 the subject names; {@code null} for a subject of a version that named none
     */
    private record Message(int position, String commit, String subject, String path, String word, String hash) {

        String who() {
            return "commit " + commit.substring(0, 12) + " (\"" + subject + "\")";
        }
    }

    /**
     * The commits of the history whose subject is one Traceworks writes, newest first. One pass over the subjects,
     * which reads no tree and no body: {@code git log -z} ends every record with a NUL, which no message can hold,
     * and a record counts only when it is, as a whole, a full commit id, a space and one of the subjects. Text in a
     * message can therefore not pass for a record, and any other subject names nothing.
     */
    private List<Message> messages() {
        GitProcess.Result log = run("--git-dir=.git", "--work-tree=.", "log", "-z", "--encoding=UTF-8",
                "--format=%H %s", "HEAD", "--");
        requireRead(log, "its history cannot be listed");
        List<Message> messages = new java.util.ArrayList<>();
        int position = 0;
        for (String record : log.outputText().split("\0")) {
            java.util.regex.Matcher commit = RECORD.matcher(record);
            if (commit.matches()) {
                String subject = commit.group(2);
                java.util.regex.Matcher run = SUBJECT.matcher(subject);
                java.util.regex.Matcher anchor = ANCHOR_SUBJECT.matcher(subject);
                java.util.regex.Matcher pin = PIN_SUBJECT.matcher(subject);
                if (run.matches()) {
                    String path = run.group(2).equals(STARTED) ? "starts/" + run.group(1) + ".json"
                            : FINAL.contains(run.group(2)) ? "runs/" + run.group(1) + ".json"
                            : run.group(2).equals(ACKNOWLEDGED) ? "acks/" + run.group(1) + ".jsonl" : null;
                    if (path != null) {
                        messages.add(new Message(position, commit.group(1), subject, path, run.group(2),
                                run.group(3)));
                    }
                } else if (anchor.matches()) {
                    messages.add(new Message(position, commit.group(1), subject,
                            "registers/" + anchor.group(1) + ".jsonl", null, anchor.group(2)));
                } else if (pin.matches()) {
                    messages.add(new Message(position, commit.group(1), subject, "pins/" + pin.group(1) + ".txt",
                            null, pin.group(1)));
                }
            }
            position++;
        }
        return messages;
    }

    /**
     * What the ledger holds against what its commits say, with no walk over old trees.
     *
     * @param files     what HEAD holds ({@link #listing})
     * @param messages  the commits that name a path, newest first ({@link #messages})
     * @param contents  the content of every file that was read, but the pins
     * @param problems  by path, what does not hold, one sentence each
     * @param rewritten the paths whose file is not what its commits left: changed, cut or removed. A reader of such
     *                  a file must not take it; a problem of another kind (a commit that names no hash) leaves the
     *                  file readable
     * @param unjudged  the paths no hash covers yet: of a version whose messages named none, which the next pin
     *                  takes as they are. Told from the messages and the pins alone, for every path
     * @param pins      the pins, oldest first, one sentence each
     */
    private record Judged(Map<String, String> files, List<Message> messages, Map<String, byte[]> contents,
                          Map<String, List<String>> problems, Set<String> rewritten, Set<String> unjudged,
                          List<String> pins) {
    }

    /**
     * Judges every path by hashes that are bound to what came before, so that no later commit chooses what counts.
     * <ul>
     * <li>A start entry and an end entry are named by one commit, whose subject holds their SHA-256: the oldest
     *     commit that names the path counts, and a second one is a problem.</li>
     * <li>The acknowledgements of a run and the anchors of a register are added to. Each commit names the SHA-256 of
     *     the whole file after it, and <em>every</em> one of them, in the order of the history, must be the SHA-256
     *     of a beginning of the file as it is now that ends with a line, each longer than the one before. So a
     *     commit can only have added lines: a file that was rewritten, also under a correct hash of the new file,
     *     no longer begins with what the earlier commits left.</li>
     * <li>An entry of a version whose subjects named no hash is covered by a pin: a commit
     *     {@code ledger PINNED sha256:<hex>} that adds {@code pins/<hex>.txt}, a list of such paths with the SHA-256
     *     each had then. The file is named by its own SHA-256, so it cannot be changed; the oldest pin that lists
     *     a path counts, and a pin covers no path a hash covered before it. Whether a path is covered is told from
     *     its own commits and from what the pins list, never from the fact that a pin exists.</li>
     * <li>Once the ledger has a hash, a commit that names a path without one is a problem of that path: a version
     *     that names none wrote to the ledger.</li>
     * </ul>
     *
     * @param read      which paths to read and compare; the pins are always read, and what is covered is told for all
     * @param fromFiles whether the files of the working tree will do for the content ({@link #blob}): for a reader
     *                  of every entry
     */
    private Judged judge(java.util.function.Predicate<String> read, boolean fromFiles) {
        Map<String, String> files = listing();
        List<Message> messages = files.isEmpty() && !hasCommit() ? List.of() : messages();
        Map<String, byte[]> contents = new java.util.HashMap<>();
        Map<String, List<String>> problems = new java.util.TreeMap<>();
        Set<String> rewritten = new java.util.TreeSet<>();
        Map<String, List<Message>> namers = new java.util.TreeMap<>();
        Map<String, Message> pinOf = new java.util.HashMap<>();
        Map<String, String> pinned = new java.util.HashMap<>();
        Set<String> namedPins = new java.util.HashSet<>();
        List<String> pins = new java.util.ArrayList<>();
        // Where the hashes begin: the oldest commit that names one
        Message first = null;
        for (Message message : messages) {
            if (message.hash() != null) first = message;
            if (!message.path().startsWith("pins/")) {
                namers.computeIfAbsent(message.path(), path -> new java.util.ArrayList<>()).add(message);
            }
        }
        // The pins, oldest first: what each covers, unless something older covers it
        for (Message pin : messages.reversed()) {
            if (!pin.path().startsWith("pins/")) continue;
            namedPins.add(pin.path());
            String id = files.get(pin.path());
            byte[] list = id == null ? null : blob(id, pin.path(), true);
            if (list == null || !sha256(list).equals(pin.hash())) {
                problems.computeIfAbsent(pin.path(), path -> new java.util.ArrayList<>()).add(pin.who()
                        + " pinned entries in " + pin.path() + ", and the ledger " + (list == null ? "holds it no more"
                        : "holds another file there (SHA-256 " + sha256(list) + ")"));
                continue;
            }
            int covers = 0;
            for (String line : new String(list, StandardCharsets.UTF_8).split("\n")) {
                java.util.regex.Matcher entry = PINNED_LINE.matcher(line);
                if (!entry.matches()) continue;
                String path = entry.group(2);
                boolean coveredBefore = pinOf.containsKey(path) || namers.getOrDefault(path, List.of()).stream()
                        .anyMatch(older -> older.hash() != null && older.position() > pin.position());
                if (!coveredBefore) {
                    pinOf.put(path, pin);
                    pinned.put(path, entry.group(1));
                    covers++;
                }
            }
            pins.add(pin.who() + " covers " + covers + (covers == 1 ? " entry" : " entries"));
        }
        Set<String> unjudged = new java.util.TreeSet<>();
        Set<String> paths = new java.util.TreeSet<>(files.keySet());
        paths.addAll(namers.keySet());
        paths.addAll(pinOf.keySet());
        for (String path : paths) {
            List<String> found = problems.computeIfAbsent(path, key -> new java.util.ArrayList<>());
            if (path.startsWith("pins/")) {
                if (!namedPins.contains(path)) found.add("no commit names the pin " + path + ", which the ledger holds");
                continue;
            }
            List<Message> named = namers.getOrDefault(path, List.of());
            Message pin = pinOf.get(path);
            boolean once = path.startsWith("starts/") || path.startsWith("runs/");
            for (Message message : named) {
                if (message.hash() == null && first != null && message.position() < first.position()) {
                    found.add(message.who() + " names " + path + " without a SHA-256, although the ledger has them"
                            + " since commit " + first.commit().substring(0, 12) + ": a version that names none"
                            + " wrote to this ledger. If that was an older Traceworks, acknowledge this and do not"
                            + " use that version on this ledger again");
                }
            }
            // What vouches for the path, oldest first: the pin, then every commit that names a hash
            List<Message> hashed = named.reversed().stream().filter(message -> message.hash() != null).toList();
            boolean covered = pin != null || (once ? !named.isEmpty() && named.getLast().hash() != null
                    : !hashed.isEmpty());
            if (!covered && files.containsKey(path) && !named.isEmpty()) {
                unjudged.add(path);
            }
            if (!read.test(path)) continue;
            byte[] content = files.containsKey(path) ? blob(files.get(path), path, fromFiles) : null;
            if (content != null) contents.put(path, content);
            String holds = content == null ? null : sha256(content);
            if (named.isEmpty() && pin == null) {
                found.add("no commit names " + path + ", which the ledger holds (SHA-256 " + holds + ")");
                rewritten.add(path);
                continue;
            }
            Message says = once ? named.isEmpty() ? null : named.getLast() : hashed.isEmpty() ? null : hashed.getLast();
            String by = says != null && says.hash() != null ? says.who() : pin != null ? "the pin of " + pin.who()
                    : named.getLast().who();
            if (content == null) {
                found.add(by + " names " + path + ", and the ledger does not hold it: a later commit removed it, or"
                        + " the commit added none");
                rewritten.add(path);
                continue;
            }
            if (once) {
                if (named.size() > 1) {
                    found.add(named.size() + " commits name " + path + " (" + String.join(", ", named.reversed()
                            .stream().map(Message::who).toList()) + "): an entry is added once");
                }
                String expected = says != null && says.hash() != null ? says.hash() : pinned.get(path);
                if (expected != null && !expected.equals(holds)) {
                    found.add(path + " is not what " + by + " left: that names the SHA-256 " + expected
                            + ", and the ledger holds " + holds + " ('git log -- " + path + "' in the ledger shows"
                            + " the commits that touched it)");
                    rewritten.add(path);
                }
                continue;
            }
            // A file that is added to: every hash, in order, is one of a beginning of the file, each a longer one
            Map<String, Integer> beginnings = beginnings(content);
            int length = 0;
            boolean holdsAll = true;
            if (pin != null) {
                Integer upTo = beginnings.get(pinned.get(path));
                if (upTo == null) {
                    found.add("the pin of " + pin.who() + " took " + path + " with the SHA-256 " + pinned.get(path)
                            + ", which is no beginning of the file the ledger holds (SHA-256 " + holds + "): the file"
                            + " was rewritten");
                    holdsAll = false;
                } else {
                    length = upTo;
                }
            }
            for (Message message : hashed) {
                Integer upTo = beginnings.get(message.hash());
                if (upTo == null) {
                    found.add(message.who() + " left " + path + " with a SHA-256 that is no beginning of the file the"
                            + " ledger holds (SHA-256 " + holds + "): the file was rewritten");
                    holdsAll = false;
                } else if (upTo <= length) {
                    found.add(message.who() + " left " + path + " no longer than the commit before it: a commit of"
                            + " this file only adds lines");
                    holdsAll = false;
                } else {
                    length = upTo;
                }
            }
            boolean unnamedTail = covered && length < content.length
                    && (named.isEmpty() || named.getFirst().hash() != null);
            if (unnamedTail) {
                found.add("the ledger holds more of " + path + " (SHA-256 " + holds + ") than " + by + " left: lines"
                        + " were added by a commit that does not name the file");
            }
            if (!holdsAll || unnamedTail) rewritten.add(path);
        }
        problems.values().removeIf(List::isEmpty);
        return new Judged(files, messages, contents, problems, rewritten, unjudged, pins);
    }

    /** The SHA-256 of every beginning of {@code content} that ends with a line, and of the whole, with its length. */
    private static Map<String, Integer> beginnings(byte[] content) {
        Map<String, Integer> beginnings = new java.util.HashMap<>();
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < content.length; i++) {
                digest.update(content[i]);
                if (content[i] == '\n' || i == content.length - 1) {
                    java.security.MessageDigest upTo = (java.security.MessageDigest) digest.clone();
                    beginnings.put(java.util.HexFormat.of().formatHex(upTo.digest()), i + 1);
                }
            }
        } catch (java.security.NoSuchAlgorithmException | CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
        return beginnings;
    }

    /** The name a path's problems are listed under: the run, or {@code registers.<key>} for the anchors. */
    private static String owner(String path) {
        java.util.regex.Matcher run = RUN_PATH.matcher(path);
        if (run.matches()) return run.group(2);
        return path.startsWith("registers/") ? "registers." + path.substring("registers/".length(),
                path.length() - ".jsonl".length()) : null;
    }

    /**
     * Every run the ledger names. The subjects of the commits say which runs started and ended: a later commit can
     * remove a file, not an earlier message. The files of HEAD are what the ledger holds now, and each must be what
     * its commits say ({@link #judge}); what is not is a change of its run ({@link LedgerRuns.Run#changes}), for the
     * anchors of a register one of {@code registers.<key>}, and for a pin a failure of the whole ledger. Nothing for
     * a ledger that does not exist yet or has no commit.
     *
     * @throws IllegalStateException if the ledger cannot be read, holds something that is no entry, or something
     *                               that lets git show another history than its commits ({@link #requireOneHistory})
     */
    @Override
    public LedgerRuns runs() {
        requireOneHistory();
        Judged judged = judge(path -> true, true);
        List<String> whole = new java.util.ArrayList<>();
        Map<String, List<String>> changed = new java.util.HashMap<>();
        Map<String, List<String>> unjudged = new java.util.HashMap<>();
        Set<String> ids = new java.util.TreeSet<>();
        Map<String, String> endNamed = new java.util.HashMap<>();
        Set<String> startNamed = new java.util.HashSet<>();
        judged.problems().forEach((path, found) -> {
            String owner = owner(path);
            if (owner == null) {
                whole.addAll(found);
            } else {
                ids.add(owner);
                changed.computeIfAbsent(owner, id -> new java.util.ArrayList<>()).addAll(found);
            }
        });
        if (!whole.isEmpty()) {
            throw tampered(String.join("; ", whole));
        }
        for (String path : judged.unjudged()) {
            String owner = owner(path);
            if (RUN_PATH.matcher(path).matches()) ids.add(owner);
            unjudged.computeIfAbsent(owner, id -> new java.util.ArrayList<>()).add(path);
        }
        // Newest first: the oldest message of an end is the one that counts, so it is put last
        for (Message message : judged.messages()) {
            java.util.regex.Matcher entry = RUN_PATH.matcher(message.path());
            if (!entry.matches()) continue;
            ids.add(entry.group(2));
            if (message.word().equals(STARTED)) startNamed.add(entry.group(2));
            if (FINAL.contains(message.word())) endNamed.put(entry.group(2), message.word());
        }
        for (String path : judged.files().keySet()) {
            java.util.regex.Matcher entry = RUN_PATH.matcher(path);
            if (entry.matches()) ids.add(entry.group(2));
        }
        List<LedgerRuns.Run> runs = new java.util.ArrayList<>();
        for (String id : ids) {
            runs.add(new LedgerRuns.Run(id, startNamed.contains(id), judged.contents().get("starts/" + id + ".json"),
                    endNamed.get(id), judged.contents().get("runs/" + id + ".json"),
                    judged.contents().get("acks/" + id + ".jsonl"), changed.getOrDefault(id, List.of()),
                    unjudged.getOrDefault(id, List.of())));
        }
        return new LedgerRuns(runs, List.copyOf(judged.unjudged()), judged.pins());
    }

    /** The entries of one run, judged alone: only its files are read. */
    private Judged judgeRun(String runId) {
        requireRunId(runId);
        requireOneHistory();
        Set<String> its = Set.of("starts/" + runId + ".json", "runs/" + runId + ".json", "acks/" + runId + ".jsonl");
        return judge(its::contains, false);
    }

    /** What the history did to the entries of one run. */
    @Override
    public List<String> changes(String runId) {
        List<String> found = new java.util.ArrayList<>();
        judgeRun(runId).problems().forEach((path, sentences) -> {
            if (runId.equals(owner(path))) found.addAll(sentences);
        });
        return found;
    }

    /** The entries of one run that no hash covers. */
    @Override
    public List<String> unjudged(String runId) {
        return judgeRun(runId).unjudged().stream().filter(path -> runId.equals(owner(path))).toList();
    }

    /**
     * Pins the entries no hash covers yet, under the lock: one commit {@code ledger PINNED sha256:<hex>} adds
     * {@code pins/<hex>.txt} with the SHA-256 and the path of each, as the ledger holds them now. They are the
     * entries of a version whose messages named no hash; from here on each is judged by the hash of the pin. Before
     * a run starts, so a ledger is pinned by the first run of this version, without a command of its own: a pin
     * says nothing a person decides, only what the ledger holds. Which entries those are is asked of the listing and
     * the hashes each time, so no pin that is there, and no problem somewhere else, keeps one from being pinned.
     */
    private void pinWhatNoHashCovers() throws IOException {
        Judged judged = judge(path -> false, false);
        if (judged.unjudged().isEmpty()) return;
        StringBuilder list = new StringBuilder();
        for (String path : judged.unjudged()) {
            list.append(sha256(blob(judged.files().get(path), path, false))).append("  ").append(path).append('\n');
        }
        byte[] content = list.toString().getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        write("pins/" + hash + ".txt", content, "ledger PINNED sha256:" + hash);
    }

    /**
     * Writes one file and commits it alone, under the lock the caller holds. The bytes git stored are compared with
     * the bytes given: a filter or an attribute that changed them on the way in fails the write, loudly, instead of
     * leaving an entry that does not match its hash.
     *
     * @return the commit id
     */
    private String write(String path, byte[] content, String subject) throws IOException {
        Path target = directory.resolve(path);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(directory, ".entry-", ".tmp");
        try {
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            git("add", "--", path);
            // --only: a file staged by an earlier, killed process must not ride along in this commit
            git("commit", "-q", "-m", subject, "--only", "--", path);
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(temp);
            // git may have committed before it failed or timed out: then the entry is in the ledger
            if (!stored(path, content)) {
                try {
                    git("reset", "-q");
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
                if (e instanceof RuntimeException runtime) throw runtime;
                throw (IOException) e;
            }
        }
        if (!stored(path, content)) {
            throw new IllegalStateException("The ledger " + directory + " stored other bytes at " + path + " than the"
                    + " entry it was given: something changes files on their way into the repository");
        }
        return git("rev-parse", "--verify", "HEAD").strip();
    }

    /** Whether HEAD holds exactly {@code content} at {@code path}, by the id of the object. */
    private boolean stored(String path, byte[] content) {
        try {
            String id = listing().get(path);
            return id != null && id.equals(blobId(content, id.length()));
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Adds a file in one commit of its own, whose subject names the SHA-256 of the file.
     *
     * @param append whether {@code content} is a line to add to the file as HEAD holds it (a file edited in the
     *               working tree does not count, and the line the file already ends with is not added again); else
     *               the whole of a file that HEAD must not hold yet
     * @param taken  what to say when HEAD holds the file already; unused for a line
     * @return the commit id; {@code null} when the line was there already
     */
    private String commit(String path, byte[] content, String subject, boolean append, String taken) {
        try {
            try (Held ignored = hold()) {
                Files.createDirectories(directory);
                initOrRequirePlain();
                // Read strictly: a ledger that fails here must not get a commit that starts a file over
                Optional<byte[]> before = held(path);
                byte[] after = content;
                if (append) {
                    String text = new String(before.orElse(new byte[0]), StandardCharsets.UTF_8);
                    String line = new String(content, StandardCharsets.UTF_8);
                    if (text.equals(line) || text.endsWith("\n" + line)) return null;
                    after = (text + line).getBytes(StandardCharsets.UTF_8);
                } else if (before.isPresent()) {
                    throw new IllegalStateException(taken);
                }
                try {
                    return write(path, after, subject + " sha256:" + sha256(after));
                } catch (IOException | RuntimeException e) {
                    if (!append) Files.deleteIfExists(directory.resolve(path));
                    throw e;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the ledger " + directory, e);
        }
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
        commit("acks/" + runId + ".jsonl", (text + "\n").getBytes(StandardCharsets.UTF_8),
                "run " + runId + " " + ACKNOWLEDGED, true, null);
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
            if (!key.isBlank() && (!CONFIG_KEY.matcher(key.strip()).matches()
                    || PROMISOR_KEY.matcher(key.strip()).matches())) {
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
                "HEAD", "--");
        requireRead(merges, "its commits cannot be listed");
        if (!merges.outputText().isBlank()) {
            throw tampered("its history holds the merge commit " + merges.outputText().strip());
        }
        GitProcess.Result beside = run("--git-dir=.git", "--work-tree=.", "rev-list", "--max-count=1", "--all",
                "--not", "HEAD", "--");
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
        // Attributes can make git store other bytes than it is given, and show others than it stored
        for (String file : List.of(".git/info/attributes", ".gitattributes", "starts/.gitattributes",
                "runs/.gitattributes", "acks/.gitattributes", "registers/.gitattributes", "pins/.gitattributes")) {
            if (Files.exists(directory.resolve(file), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw tampered("it has the attributes file " + file);
            }
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
        try {
            commit(relative.toString(), ("{\"seq\":" + seq + ",\"head\":\"" + head + "\"}\n")
                    .getBytes(StandardCharsets.UTF_8), "register " + key + " seq " + seq, true, null);
        } catch (UncheckedIOException e) {
            throw new IllegalStateException("Cannot write the ledger " + directory + ": " + e.getCause(), e);
        }
    }

    /**
     * The anchors {@code registers/<key>.jsonl} holds in HEAD. None for a ledger that does not exist yet (no folder,
     * or a folder git has not been started in), for one without a commit, and for one whose HEAD does not hold the
     * file. A ledger that exists and cannot be read is an error, never an empty list: "no anchor" must not be what a
     * damaged ledger says. The file is judged as every other reader judges it ({@link #judge}): one that was
     * rewritten is an error too.
     */
    @Override
    public List<RegisterAnchor> registerAnchors(String key) {
        Path relative = anchorFile(key);
        requireOneHistory();
        Judged judged = judge(relative.toString()::equals, false);
        List<String> broken = new java.util.ArrayList<>();
        judged.problems().forEach((path, found) -> {
            if (path.startsWith("pins/") || judged.rewritten().contains(path)) broken.addAll(found);
        });
        if (!broken.isEmpty()) {
            throw tampered(String.join("; ", broken));
        }
        String text = new String(judged.contents().getOrDefault(relative.toString(), new byte[0]),
                StandardCharsets.UTF_8);
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
        boolean none = true;
        for (String line : objects.outputText().split("\n")) {
            if (line.startsWith("count: ") || line.startsWith("in-pack: ")) {
                counted = true;
                none &= line.substring(line.indexOf(' ') + 1).strip().equals("0");
            }
        }
        boolean commits = false;
        if (counted && !none) {
            // Objects without a commit among them are what a writer leaves that was killed before its first commit
            GitProcess.Result types = run("--git-dir=.git", "--work-tree=.", "cat-file", "--batch-all-objects",
                    "--batch-check=%(objecttype)");
            requireRead(types, "its objects cannot be listed");
            commits = types.outputText().lines().anyMatch("commit"::equals);
        }
        if (!counted || !refs.outputText().isBlank() || commits) {
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
