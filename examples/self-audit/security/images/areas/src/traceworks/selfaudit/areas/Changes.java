package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Areas.Area;
import traceworks.selfaudit.areas.Finder.Call;
import traceworks.selfaudit.areas.Finder.Declared;
import traceworks.selfaudit.areas.Finder.Result;
import traceworks.selfaudit.areas.Finder.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Which areas a check of the changes looks at: the ones whose lines changed since a base commit, and the ones one
 * call away from a member that changed, in either direction ({@link Finder.Result#calls}).
 *
 * <p>What changed is decided here, by the tool that also compiles the files, so that no second program has another
 * idea of which files there are or where a line is. A step before hands over what only git has: the base and the
 * head commit, the tree of each ({@code git ls-tree -r -z}: every path with its mode and blob id), and the bytes of
 * the blobs this tool asked for by their ids ({@code git cat-file --batch}). The tool computes the blob id of every
 * file itself ({@link #blobId}), so no path ever reaches git, and no diff, index, ignore rule, attribute or filter
 * of the workspace has a say ({@link #compare}). The files whose lines are compared are the files the finder read,
 * with the lines the finder and the compiler count ({@link String#lines()}): a line counts as changed when the
 * workspace has it and the base does not have it there; where lines were only removed, the two lines around the
 * gap count. A file the base does not have is changed as a whole, and so is one whose base bytes are not there.
 * Every other file that differs is named by its kind ({@link #kinds}), and the report does not go GREEN over a
 * changed file that this check cannot judge.
 *
 * <p>An area is <em>changed</em> when a changed line lies in it, when a member it shows a part of changed outside
 * that part, or when a line of its file changed that lies in no member (an import, the package, the head of a
 * class: such a line can change what every name in the file means, so every area of the file is judged). It is a <em>neighbour</em> when it is not changed and a member in it calls a changed member or is
 * called by one. Two calls away is not looked at. The changed members an area is connected with are shown to the
 * verdict next to the area's own code ({@link #connectedText}).
 */
final class Changes {

    /** The most connected members whose code a prompt shows, and the most lines of each. */
    static final int MAX_CONNECTED = 4;
    static final int MAX_CONNECTED_LINES = 60;
    private static final int MAX_NAMED = 5;
    static final String HEAD_CHANGED = "changed: the head of its file (outside every member)";
    /** Above this many pairs of lines, the part of a file between its common start and end counts as rewritten. */
    private static final long MAX_COMPARED = 4_000_000;

    private static final String NOT_REGULAR = " (not a readable regular file)";
    private static final String NO_NAME = " (its name is no UTF-8, so it could not be compared)";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private Changes() {
    }

    /** Lines {@code from} to {@code to} of a file as it is now, both included. */
    record Range(int from, int to) {

        boolean overlaps(int start, int end) {
            return from <= end && to >= start;
        }
    }

    /** A file of a tree: its mode ({@code 100644}, {@code 100755}, {@code 120000} for a link) and its blob id. */
    record Entry(String mode, String id) {
    }

    /**
     * What the step before handed over.
     *
     * @param base         the commit the changes are measured from
     * @param head         the commit the workspace is at
     * @param from         where the base came from: {@code input} (the run's {@code since}) or {@code register} (the
     *                     workspace commit of the last run of this audit)
     * @param since        the input as it was given; empty without one
     * @param previousHead the workspace commit of the last run of this audit; empty when no report names one
     * @param baseDate     when the base was committed, as git writes it; empty when the step did not say
     * @param skipped      how many commits lie after the last run's commit and before the base, which this run
     *                     leaves unjudged: a number, {@code unknown} when the last run's commit is not in the
     *                     checkout, or empty when no earlier run is known
     * @param baseTree     the files of the base, by their path below the root
     * @param headTree     the files of the workspace commit
     * @param blobs        the bytes of the blobs that were asked for, by their id
     */
    record Handed(String base, String head, String from, String since, String previousHead, String baseDate,
                  String skipped, Map<String, Entry> baseTree, Map<String, Entry> headTree,
                  Map<String, byte[]> blobs) {
    }

    /**
     * What differs from the base.
     *
     * @param dirty      whether the workspace differs from its own commit
     * @param status     every file that differs, by its path: {@code M} changed, {@code A} new, {@code D} removed,
     *                   {@code U} not compared (no readable regular file any more, or a path outside the root)
     * @param baseIds    the blob id the base has of each changed file
     */
    record Compared(boolean dirty, Map<String, String> status, Map<String, String> baseIds) {
    }

    /**
     * What a check of the changes works with: the base, and what differs from it.
     *
     * @param dirty      whether the workspace differs from its own commit
     * @param lines      the changed lines of each changed or new file the finder read
     * @param status     every file that differs, by its path ({@link Compared#status})
     * @param removed    the files the base has and the workspace does not
     * @param unmappable the files that differ and could not be compared or mapped, each with the reason
     */
    record Base(String base, String head, String from, String since, String previousHead, boolean dirty,
                Map<String, List<Range>> lines, String baseDate, String skipped, List<String> removed,
                List<String> unmappable, Map<String, String> status) {

        /** Every file that differs: changed, new, removed or not compared. */
        int files() {
            return status.size();
        }
    }

    /**
     * The files that differ and that no area comes from, by their kind, each list sorted.
     *
     * @param tests       Java files of a test source root ({@code src/test/java}): not what this audit judges
     * @param uncompiled  other Java files that the finder did not read: code that nothing looked at
     * @param docs        documentation: {@code *.md}, and everything below a directory {@code docs}
     * @param cannotJudge every other file: a script, a workflow, a build file, a resource. It can change what the
     *                    project does, and this check has no word on it
     * @param removedJava Java files that are gone, other than tests: their areas are gone with them, and what
     *                    called them is looked at only where it changed
     */
    record Kinds(List<String> tests, List<String> uncompiled, List<String> docs, List<String> cannotJudge,
                 List<String> removedJava) {
    }

    /** Why an area is looked at, and the changed members it is connected with outside its own lines. */
    record Pick(boolean changed, List<String> reasons, List<Declared> connected) {

        /** The reasons on one line, as the prompt and the report show them. */
        String reason() {
            return String.join("; ", reasons);
        }
    }

    /**
     * Reads the folder the step before wrote: {@code base-commit}, {@code head}, {@code from}, {@code since},
     * {@code previous-head}, {@code base-date}, {@code skipped}, {@code base-tree} and {@code head-tree} (the output
     * of {@code git ls-tree -r -z}), and, once they were asked for, {@code blobs} (the output of
     * {@code git cat-file --batch}).
     */
    static Handed read(Path dir) throws IOException {
        String base = file(dir, "base-commit").strip();
        String head = file(dir, "head").strip();
        String from = file(dir, "from").strip();
        if (!COMMIT.matcher(base).matches()) throw new IllegalArgumentException("the base in " + dir + " is no commit id");
        if (!COMMIT.matcher(head).matches()) throw new IllegalArgumentException("the head in " + dir + " is no commit id");
        if (!from.equals("input") && !from.equals("register")) {
            throw new IllegalArgumentException("the base in " + dir + " does not say where it is from");
        }
        String previous = optional(dir, "previous-head");
        String skipped = optional(dir, "skipped");
        if (!skipped.matches("\\d{0,9}|unknown")) {
            throw new IllegalArgumentException("the skipped commits in " + dir + " are no number");
        }
        String date = optional(dir, "base-date");
        Map<String, byte[]> blobs = Files.exists(dir.resolve("blobs"))
                ? blobs(Files.readAllBytes(dir.resolve("blobs"))) : Map.of();
        return new Handed(base, head, from, optional(dir, "since"), COMMIT.matcher(previous).matches() ? previous : "",
                date.matches("[0-9T:+Z-]{1,40}") ? date : "", skipped,
                tree(Files.readAllBytes(dir.resolve("base-tree"))), tree(Files.readAllBytes(dir.resolve("head-tree"))),
                blobs);
    }

    private static String file(Path dir, String name) throws IOException {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    private static String optional(Path dir, String name) throws IOException {
        return Files.exists(dir.resolve(name)) ? file(dir, name).strip() : "";
    }

    /**
     * The files of a tree as {@code git ls-tree -r -z} lists them: {@code <mode> <type> <id>}, a tab, the path, a
     * NUL. A list that does not read so is refused, and so is a submodule: the check does not look into one.
     */
    static Map<String, Entry> tree(byte[] listed) {
        Map<String, Entry> tree = new TreeMap<>();
        int start = 0;
        for (int i = 0; i < listed.length; i++) {
            if (listed[i] != 0) continue;
            // Read byte for byte, so that the path is cut at the right place whatever its bytes are
            String record = new String(listed, start, i - start, StandardCharsets.ISO_8859_1);
            start = i + 1;
            int tab = record.indexOf('\t');
            String[] head = tab < 0 ? new String[0] : record.substring(0, tab).split(" ");
            if (head.length != 3 || !head[0].matches("[0-7]{6}") || !COMMIT.matcher(head[2]).matches()
                    || tab + 1 >= record.length()) {
                throw new IllegalArgumentException("the tree of a commit holds a line that is none");
            }
            String path = name(record.substring(tab + 1).getBytes(StandardCharsets.ISO_8859_1));
            if (head[1].equals("commit")) {
                throw new IllegalArgumentException("the workspace has a submodule (" + Main.safe(path)
                        + "): this check does not look into one");
            }
            if (!head[1].equals("blob")) throw new IllegalArgumentException("the tree of a commit holds no file at " + Main.safe(path));
            tree.put(path, new Entry(head[0], head[2]));
        }
        if (start != listed.length) throw new IllegalArgumentException("the tree of a commit is cut");
        return tree;
    }

    /** How a path that is no UTF-8 is marked: no path of a file starts so. */
    static final String NO_UTF8 = "\uFFFD";

    /**
     * A path as the key of a file: its text when its bytes are UTF-8, as they are in a tree and on disk. A path
     * whose bytes are no UTF-8 has no text that leads back to the file, so it keeps its bytes, each as a character,
     * behind {@link #NO_UTF8}: the same key for the same bytes, and a file that cannot be compared.
     */
    static String name(byte[] path) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(path)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return NO_UTF8 + new String(path, StandardCharsets.ISO_8859_1);
        }
    }

    /** The path of {@code file} below {@code root} with the bytes it has on disk, which its URI keeps. */
    private static String nameOnDisk(Path root, Path file) {
        String all = file.toUri().getRawPath();
        String prefix = root.toUri().getRawPath();
        String raw = all.startsWith(prefix) ? all.substring(prefix.length()) : all;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length() + 0 && raw.substring(i + 1, i + 3).matches("[0-9A-Fa-f]{2}")) {
                bytes.write(Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return name(bytes.toByteArray());
    }

    /**
     * The blobs of {@code git cat-file --batch}: per object a line {@code <id> blob <size>}, the bytes and a line
     * end; {@code <id> missing} for one git does not have. A blob whose bytes do not give its id is left out, like a
     * missing one: the file it belongs to then counts as changed as a whole.
     */
    static Map<String, byte[]> blobs(byte[] batch) {
        Map<String, byte[]> blobs = new HashMap<>();
        int at = 0;
        while (at < batch.length) {
            int end = at;
            while (end < batch.length && batch[end] != '\n') end++;
            if (end >= batch.length) throw new IllegalArgumentException("the blobs of the base are cut");
            String[] head = new String(batch, at, end - at, StandardCharsets.ISO_8859_1).split(" ");
            at = end + 1;
            if (head.length == 2 && head[1].equals("missing")) continue;
            if (head.length != 3 || !COMMIT.matcher(head[0]).matches() || !head[2].matches("\\d{1,10}")) {
                throw new IllegalArgumentException("the blobs of the base hold a line that is none");
            }
            long size = Long.parseLong(head[2]);
            if (at + size + 1 > batch.length) throw new IllegalArgumentException("the blobs of the base are cut");
            byte[] bytes = java.util.Arrays.copyOfRange(batch, at, at + (int) size);
            at += (int) size + 1;
            if (head[1].equals("blob") && blobId(bytes, head[0].length()).equals(head[0])) blobs.put(head[0], bytes);
        }
        return blobs;
    }

    /**
     * The id git gives a file with these bytes: the SHA-1 (40 hex digits) or the SHA-256 (64) of {@code blob}, a
     * space, the length, a NUL and the bytes. Computed here, so that no file name is ever handed to git.
     */
    static String blobId(byte[] bytes, int hexDigits) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(hexDigits == 64 ? "SHA-256" : "SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.ISO_8859_1));
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The id of the file at {@code path}; {@code null} when it is not there, empty when it cannot be compared. */
    private static String idNow(Path root, String path, int hexDigits) {
        if (path.startsWith(NO_UTF8)) return "";
        Path file;
        try {
            file = root.resolve(path).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return "";
        }
        if (!file.startsWith(root) || file.equals(root)) return "";
        try {
            if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.isSymbolicLink(file)) {
                return blobId(Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8), hexDigits);
            }
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "";
            return blobId(Files.readAllBytes(file), hexDigits);
        } catch (IOException e) {
            return "";
        }
    }

    /** The mode a tree would give the file at {@code path}: a link, an executable file, or a plain one. */
    private static String modeNow(Path root, String path) {
        Path file = root.resolve(path);
        return Files.isSymbolicLink(file) ? "120000" : Files.isExecutable(file) ? "100755" : "100644";
    }

    /**
     * Compares the workspace with the base, by content. Every file of the base and of the workspace commit is
     * looked up in the workspace and its blob id computed: another id is a changed file, and so is another mode (a
     * file made executable, or replaced by a link); no file is a removed one. A
     * file the workspace commit has and the base does not is new, whatever its kind. A {@code .java} file that
     * neither tree has is new too, whether git tracks it, ignores it or neither: the walk takes every regular
     * {@code .java} file below the root that is not below a {@code .git} directory, so every file the finder can
     * read is in it. A new file of another kind that is in no commit is not seen: nothing tells it from the output
     * of a build.
     */
    static Compared compare(Handed handed, Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        int hex = handed.base().length();
        Map<String, String> status = new TreeMap<>();
        Map<String, String> baseIds = new HashMap<>();
        boolean dirty = false;
        java.util.TreeSet<String> paths = new java.util.TreeSet<>(handed.baseTree().keySet());
        paths.addAll(handed.headTree().keySet());
        for (String path : paths) {
            Entry before = handed.baseTree().get(path);
            Entry committed = handed.headTree().get(path);
            String now = idNow(root, path, hex);
            String mode = now == null || now.isEmpty() ? "" : modeNow(root, path);
            if (before != null) {
                if (now == null) status.put(path, "D");
                else if (now.isEmpty()) status.put(path, "U");
                else if (!now.equals(before.id())) {
                    status.put(path, "M");
                    baseIds.put(path, before.id());
                } else if (!mode.equals(before.mode())) {
                    status.put(path, "M");
                }
            } else if (now != null) {
                // Of the workspace commit and not of the base. (One that is in neither any more is no change.)
                status.put(path, now.isEmpty() ? "U" : "A");
            }
            // A file the workspace commit does not have and the disk does makes the two differ, whatever the base
            dirty |= committed == null ? now != null
                    : !committed.id().equals(now) || !committed.mode().equals(mode);
        }
        List<String> walked = new ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                return dir.getFileName() != null && dir.getFileName().toString().equals(".git") && !dir.equals(root)
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes a) {
                if (file.getFileName().toString().endsWith(".java") && (a.isRegularFile() || a.isSymbolicLink())) {
                    walked.add(nameOnDisk(root, file));
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        for (String path : walked) {
            if (paths.contains(path)) continue;
            status.put(path, path.startsWith(NO_UTF8) ? "U" : "A");
            dirty = true;
        }
        return new Compared(dirty, status, baseIds);
    }

    /** The ids of the base blobs whose bytes the line comparison needs: those of the changed {@code .java} files. */
    static List<String> wanted(Compared compared) {
        return compared.status().entrySet().stream()
                .filter(e -> e.getValue().equals("M") && e.getKey().endsWith(".java"))
                .map(e -> compared.baseIds().get(e.getKey())).distinct().sorted().toList();
    }

    /**
     * What the check works with: what differs, and for each changed or new file the finder read its changed lines.
     * The lines of the workspace are the finder's own, and the lines of the base are split the same way, so a line
     * number means here what it means to the compiler. A changed file whose base bytes are not there, or are no
     * UTF-8, counts as changed as a whole and is named.
     */
    static Base of(Handed handed, Compared compared, Result result) {
        Map<String, List<Range>> lines = new TreeMap<>();
        List<String> removed = new ArrayList<>();
        List<String> unmappable = new ArrayList<>();
        Map<String, SourceFile> read = new HashMap<>();
        for (SourceFile file : result.files()) read.put(file.path(), file);
        compared.status().forEach((path, status) -> {
            if (status.equals("D")) removed.add(path);
            if (status.equals("U")) {
                unmappable.add(path.startsWith(NO_UTF8) ? Main.safe(path.substring(1)) + NO_NAME : path + NOT_REGULAR);
            }
            SourceFile file = read.get(path);
            if (file == null || status.equals("D") || status.equals("U")) return;
            List<Range> whole = List.of(new Range(1, Integer.MAX_VALUE));
            if (status.equals("A")) {
                lines.put(path, whole);
                return;
            }
            if (!compared.baseIds().containsKey(path)) {
                // The same bytes under another mode: nothing says which lines that touches, so all of them
                lines.put(path, whole);
                return;
            }
            byte[] before = handed.blobs().get(compared.baseIds().get(path));
            String text = null;
            if (before != null) {
                try {
                    text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(before)).toString();
                } catch (java.nio.charset.CharacterCodingException e) {
                    text = null;
                }
            }
            if (text == null) {
                // It differs, and nothing can say where: the whole file counts, and the report names it
                lines.put(path, whole);
                unmappable.add(path + " (it differs from the base, and its lines could not be compared)");
            } else {
                lines.put(path, changedLines(text.lines().toList(), file.lines()));
            }
        });
        return new Base(handed.base(), handed.head(), handed.from(), handed.since(), handed.previousHead(),
                compared.dirty(), lines, handed.baseDate(), handed.skipped(), List.copyOf(removed),
                List.copyOf(unmappable), compared.status());
    }

    /**
     * The files that differ and that no area comes from, by their kind: every file of {@link Base#status} that the
     * finder did not read and that could be compared. Each is of one kind.
     *
     * @param workspace the root, where a {@code workflow.json} marks a package
     */
    static Kinds kinds(Base base, Result result, Path workspace) {
        Set<String> read = new java.util.HashSet<>();
        for (SourceFile file : result.files()) read.add(file.path());
        List<String> tests = new ArrayList<>();
        List<String> uncompiled = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        List<String> cannotJudge = new ArrayList<>();
        List<String> removedJava = new ArrayList<>();
        base.status().forEach((path, status) -> {
            // What could not be compared is named as that
            if (read.contains(path) || status.equals("U")) return;
            if (path.endsWith(".java")) {
                (("/" + path).contains("/src/test/java/") ? tests : status.equals("D") ? removedJava : uncompiled)
                        .add(path);
            } else if (documentation(path, workspace)) {
                docs.add(path);
            } else {
                cannotJudge.add(path);
            }
        });
        return new Kinds(List.copyOf(tests), List.copyOf(uncompiled), List.copyOf(docs), List.copyOf(cannotJudge),
                List.copyOf(removedJava));
    }

    /**
     * Whether a file is documentation that nothing reads when the project runs: a {@code *.md} file, or a file below
     * the {@code docs} directory of the workspace. Not one below a {@code src} or a hidden directory, not one of a
     * workflow package (a directory with a {@code workflow.json}, at or above the file: a package's files are what
     * its steps and prompts can read), and not {@code AGENTS.md} or {@code CLAUDE.md}, which an AI tool reads as
     * instructions.
     */
    static boolean documentation(String path, Path workspace) {
        String[] parts = path.split("/");
        String name = parts[parts.length - 1];
        if (name.equals("AGENTS.md") || name.equals("CLAUDE.md")) return false;
        Path dir = workspace;
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].startsWith(".") || parts[i].equals("src")) return false;
            dir = dir.resolve(parts[i]);
            if (Files.exists(dir.resolve("workflow.json"))) return false;
        }
        if (name.startsWith(".") || Files.exists(workspace.resolve("workflow.json")) && parts.length == 1) return false;
        return name.endsWith(".md") || parts.length > 1 && parts[0].equals("docs");
    }

    /**
     * The lines of {@code now} that {@code before} does not have in their place, as ranges of line numbers of
     * {@code now}: a longest common subsequence of the two decides which lines stayed. Where lines of
     * {@code before} are gone and none came, the line before the gap and the line after it are the range. Two
     * versions that hold the same lines give none (they differed in their line ends only).
     */
    static List<Range> changedLines(List<String> before, List<String> now) {
        int start = 0;
        while (start < before.size() && start < now.size() && before.get(start).equals(now.get(start))) start++;
        int endBefore = before.size();
        int endNow = now.size();
        while (endBefore > start && endNow > start && before.get(endBefore - 1).equals(now.get(endNow - 1))) {
            endBefore--;
            endNow--;
        }
        int n = endBefore - start;
        int m = endNow - start;
        // kept[j]: line start + j of now stayed
        boolean[] kept = new boolean[m];
        // gapBefore[j]: lines of before are gone right before line start + j of now (j == m: before the common end)
        boolean[] gapBefore = new boolean[m + 1];
        if (n > 0 && m > 0 && (long) n * m <= MAX_COMPARED) {
            int[][] common = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    common[i][j] = before.get(start + i).equals(now.get(start + j)) ? common[i + 1][j + 1] + 1
                            : Math.max(common[i + 1][j], common[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < n && j < m) {
                if (before.get(start + i).equals(now.get(start + j))) {
                    kept[j] = true;
                    i++;
                    j++;
                } else if (common[i + 1][j] >= common[i][j + 1]) {
                    gapBefore[j] = true;
                    i++;
                } else {
                    j++;
                }
            }
            if (i < n) gapBefore[j] = true;
        } else if (n > 0) {
            gapBefore[0] = true;
        }
        List<Range> ranges = new ArrayList<>();
        int from = -1;
        for (int j = 0; j <= m; j++) {
            boolean changed = j < m && !kept[j];
            if (changed && from < 0) from = j;
            if (!changed && from >= 0) {
                ranges.add(new Range(start + from + 1, start + j));
                from = -1;
            }
            // Only removed here: no changed line of now touches the gap
            if (gapBefore[j] && !changed && (j == 0 || kept[j - 1])) {
                int line = start + j;
                ranges.add(new Range(Math.max(1, line), line + 1));
            }
        }
        return List.copyOf(ranges);
    }

    /**
     * The changed lines of the Java files the finder read that lie in no member: an import, the head of a class, an
     * annotation of a type. Every area of such a file counts as changed ({@link #picks}); the places are counted and
     * named. Each as
     * {@code <file>:<from>-<to>}.
     */
    static List<String> outsideMembers(Result result, Map<String, List<Range>> lines) {
        Map<String, List<Declared>> byFile = new HashMap<>();
        for (Declared member : result.members()) byFile.computeIfAbsent(member.file(), k -> new ArrayList<>()).add(member);
        List<String> outside = new ArrayList<>();
        for (SourceFile file : result.files()) {
            for (Range range : lines.getOrDefault(file.path(), List.of())) {
                int to = Math.min(range.to(), Math.max(file.lines().size(), 1));
                if (range.from() > to) continue;
                boolean inMember = byFile.getOrDefault(file.path(), List.of()).stream()
                        .anyMatch(m -> m.member().start() <= to && m.member().end() >= range.from());
                if (!inMember) outside.add(file.path() + ":" + range.from() + "-" + to);
            }
        }
        return outside;
    }

    /** The members with a changed line, in the order the finder lists them. */
    static List<Declared> changedMembers(Result result, Map<String, List<Range>> lines) {
        List<Declared> changed = new ArrayList<>();
        for (Declared member : result.members()) {
            if (touched(lines, member.file(), member.member().start(), member.member().end())) changed.add(member);
        }
        return changed;
    }

    /**
     * The areas the changes pick, by the area's id; an area that is neither changed nor a neighbour is not in the
     * map. A changed area names what it is connected with too, so its verdict sees the other side of a change that
     * spans two members.
     */
    static Map<String, Pick> picks(List<Area> areas, Result result, Map<String, List<Range>> lines) {
        List<Declared> changed = changedMembers(result, lines);
        Set<Declared> isChanged = new java.util.HashSet<>(changed);
        for (Declared head : result.heads()) {
            if (touched(lines, head.file(), head.member().start(), head.member().end())) isChanged.add(head);
        }
        // A changed line outside every member (an import, the package, the head of a class) can change what any
        // name in its file means and what its classes inherit. So every member of that file counts as changed for
        // who calls it, and so does every member of the files that hold a supertype or a subtype of its classes:
        // a call that goes through the changed class may reach other code now
        Set<String> headChanged = new java.util.HashSet<>();
        for (String outside : outsideMembers(result, lines)) headChanged.add(outside.substring(0, outside.lastIndexOf(':')));
        Set<String> reached = new java.util.HashSet<>(headChanged);
        for (String file : headChanged) reached.addAll(result.related().getOrDefault(file, Set.of()));
        for (Declared member : result.members()) if (reached.contains(member.file())) isChanged.add(member);
        for (Declared head : result.heads()) if (reached.contains(head.file())) isChanged.add(head);
        // Who is one call from whom, in either direction, in the finder's order
        Map<Declared, Set<Declared>> near = new HashMap<>();
        for (Call call : result.calls()) {
            near.computeIfAbsent(call.from(), k -> new LinkedHashSet<>()).add(call.to());
            near.computeIfAbsent(call.to(), k -> new LinkedHashSet<>()).add(call.from());
        }
        Map<String, List<Declared>> byFile = new HashMap<>();
        for (Declared member : result.members()) byFile.computeIfAbsent(member.file(), k -> new ArrayList<>()).add(member);
        Comparator<Declared> place = Comparator.comparing(Declared::file)
                .thenComparingInt((Declared d) -> d.member().start()).thenComparing(d -> d.member().label());

        Map<String, Pick> picks = new LinkedHashMap<>();
        for (Area area : areas) {
            List<Declared> held = byFile.getOrDefault(area.file(), List.of()).stream()
                    .filter(m -> m.member().start() <= area.end() && m.member().end() >= area.start()).toList();
            Set<Declared> connected = new java.util.TreeSet<>(place);
            List<String> reasons = new ArrayList<>();
            boolean changedHere = touched(lines, area.file(), area.start(), area.end());
            if (changedHere) reasons.add("changed");
            // An import, the package, the head of a class: what a name in any member means may have changed
            else if (headChanged.contains(area.file())) reasons.add(HEAD_CHANGED);
            for (Declared member : held) {
                boolean whole = member.member().start() >= area.start() && member.member().end() <= area.end();
                if (isChanged.contains(member) && !whole
                        && !touched(lines, area.file(), Math.max(area.start(), member.member().start()),
                        Math.min(area.end(), member.member().end()))) {
                    // The area shows a part of a long member, and the member changed in another part
                    connected.add(member);
                    if (reasons.isEmpty()) reasons.add("changed: " + member.member().label() + " outside the lines of this area");
                }
                for (Declared other : near.getOrDefault(member, Set.of())) {
                    boolean inside = other.file().equals(area.file()) && other.member().start() >= area.start()
                            && other.member().end() <= area.end();
                    if (isChanged.contains(other) && !inside) connected.add(other);
                }
            }
            boolean changedArea = !reasons.isEmpty();
            if (!changedArea) {
                List<String> names = connected.stream().map(m -> m.member().label()).distinct().toList();
                for (int i = 0; i < names.size() && i < MAX_NAMED; i++) reasons.add("neighbour of " + names.get(i));
                if (names.size() > MAX_NAMED) reasons.add("neighbour of " + (names.size() - MAX_NAMED) + " more");
            }
            if (!reasons.isEmpty()) picks.put(area.id(), new Pick(changedArea, List.copyOf(reasons), List.copyOf(connected)));
        }
        return picks;
    }

    /**
     * What the code of an area means beyond its text, for its digest: what the calls of its members resolved to (the
     * declaring type and the signature of each, as the compiler resolved them), and the lines of its file that lie
     * in no member (the package, the imports, the heads of the classes). When an import, a supertype or an overload
     * points a call of the area at other code, this text changes although the area's own lines do not, so the area
     * does not pass for judged as it is.
     */
    static String meaning(Area area, Result result) {
        java.util.TreeSet<String> targets = new java.util.TreeSet<>();
        List<Declared> inFile = new ArrayList<>();
        for (Declared member : result.members()) {
            if (!member.file().equals(area.file())) continue;
            inFile.add(member);
            if (member.member().start() <= area.end() && member.member().end() >= area.start()) {
                targets.addAll(result.resolves().getOrDefault(member, List.of()));
            }
        }
        StringBuilder out = new StringBuilder("# resolves\n");
        for (String target : targets) out.append(target).append('\n');
        out.append("# head\n");
        for (SourceFile file : result.files()) {
            if (!file.path().equals(area.file())) continue;
            for (int line = 1; line <= file.lines().size(); line++) {
                int at = line;
                if (inFile.stream().noneMatch(m -> m.member().start() <= at && m.member().end() >= at)) {
                    out.append(file.lines().get(line - 1)).append('\n');
                }
            }
        }
        return out.toString();
    }

    /**
     * The whole code of every changed member an area is connected with, without line numbers: what the digest of
     * the area holds of them. The prompt shows a part ({@link #connectedText}); the digest must change whenever any
     * line of any of them does, or a later change beyond what was shown would pass for judged.
     */
    static String connectedWhole(List<Declared> connected, Result result) {
        Map<String, SourceFile> files = new HashMap<>();
        for (SourceFile file : result.files()) files.put(file.path(), file);
        StringBuilder out = new StringBuilder();
        for (Declared member : connected) {
            out.append(member.file()).append(", ").append(member.member().label()).append(":\n");
            List<String> code = files.containsKey(member.file()) ? files.get(member.file()).lines() : List.of();
            for (int line = member.member().start(); line <= member.member().end() && line <= code.size(); line++) {
                out.append(code.get(line - 1)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The code of the changed members an area is connected with, for the prompt: per member a line with its file and
     * name, then its lines with their numbers, at most {@value #MAX_CONNECTED_LINES} of them from five lines before
     * its first changed line; at most {@value #MAX_CONNECTED} members, the rest named. Empty when there is none.
     */
    static String connectedText(List<Declared> connected, Result result, Map<String, List<Range>> lines) {
        Map<String, SourceFile> files = new HashMap<>();
        for (SourceFile file : result.files()) files.put(file.path(), file);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < connected.size() && i < MAX_CONNECTED; i++) {
            Declared member = connected.get(i);
            int start = member.member().start();
            int end = member.member().end();
            boolean cut = end - start + 1 > MAX_CONNECTED_LINES;
            if (cut) {
                int first = start;
                for (Range range : lines.getOrDefault(member.file(), List.of())) {
                    if (range.overlaps(start, end)) {
                        first = Math.max(start, range.from());
                        break;
                    }
                }
                start = Math.max(start, Math.min(first - 5, end - MAX_CONNECTED_LINES + 1));
                end = start + MAX_CONNECTED_LINES - 1;
            }
            if (!out.isEmpty()) out.append('\n');
            out.append(member.file()).append(", ").append(member.member().label())
                    .append(cut ? " (cut: " + MAX_CONNECTED_LINES + " of its " + (member.member().end()
                            - member.member().start() + 1) + " lines; read the rest in the workspace):" : ":");
            List<String> code = files.containsKey(member.file()) ? files.get(member.file()).lines() : List.of();
            for (int line = start; line <= end && line <= code.size(); line++) {
                out.append('\n').append(line).append("| ").append(code.get(line - 1));
            }
        }
        if (connected.size() > MAX_CONNECTED) {
            out.append("\nCut: not shown, changed and connected too (read them in the workspace): ");
            List<String> names = new ArrayList<>();
            for (Declared member : connected.subList(MAX_CONNECTED, connected.size())) {
                names.add(member.file() + ", " + member.member().label());
            }
            out.append(String.join("; ", names));
        }
        return out.toString();
    }

    private static boolean touched(Map<String, List<Range>> lines, String file, int start, int end) {
        for (Range range : lines.getOrDefault(file, List.of())) {
            if (range.overlaps(start, end)) return true;
        }
        return false;
    }
}
