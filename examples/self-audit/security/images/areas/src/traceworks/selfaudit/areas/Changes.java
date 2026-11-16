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
 * <p>Which files differ from the base is the word of a step before, read from the folder it wrote ({@link #read}):
 * the base and the head commit, and a manifest of the files whose content differs, with the bytes the base has of
 * each changed Java file. That step compares content; no diff of git, no index and no ignore rule had a say, so the
 * workspace cannot hide a file from it. Which <em>lines</em> changed is decided here, by comparing the two versions
 * line by line ({@link #changedLines}). The tool starts no git. A line counts as changed when the workspace has it
 * and the base does not have it there; where lines were only removed, the two lines around the gap count. A file
 * the base does not have is changed as a whole. A file that could not be compared is named
 * ({@link Base#unmappable}), and the report does not go GREEN over it.
 *
 * <p>An area is <em>changed</em> when a changed line lies in it, or when a member it shows a part of changed outside
 * that part. It is a <em>neighbour</em> when it is not changed and a member in it calls a changed member or is
 * called by one. Two calls away is not looked at. The changed members an area is connected with are shown to the
 * verdict next to the area's own code ({@link #connectedText}).
 */
final class Changes {

    /** The most connected members whose code a prompt shows, and the most lines of each. */
    static final int MAX_CONNECTED = 4;
    static final int MAX_CONNECTED_LINES = 60;
    private static final int MAX_NAMED = 5;
    /** Above this many pairs of lines, the part of a file between its common start and end counts as rewritten. */
    private static final long MAX_COMPARED = 4_000_000;

    private static final String NOT_REGULAR = " (not a readable regular file)";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private Changes() {
    }

    /** Lines {@code from} to {@code to} of a file as it is now, both included. */
    record Range(int from, int to) {

        boolean overlaps(int start, int end) {
            return from <= end && to >= start;
        }
    }

    /**
     * What the step before found out about the base, and what differs from it.
     *
     * @param base         the commit the changes are measured from
     * @param head         the commit the workspace is at
     * @param from         where the base came from: {@code input} (the run's {@code since}) or {@code register} (the
     *                     workspace commit of the last run of this audit)
     * @param since        the input as it was given; empty without one
     * @param previousHead the workspace commit of the last run of this audit; empty when no report names one
     * @param dirty        whether the workspace differs from its own commit
     * @param lines        the changed lines of each file that differs and is still there, by its path below the
     *                     root; no range for a file whose lines were not compared (no Java file)
     * @param baseDate     when the base was committed, as git writes it; empty when the step did not say
     * @param skipped      how many commits lie after the last run's commit and before the base, which this run
     *                     leaves unjudged: a number, {@code unknown} when the last run's commit is not in the
     *                     checkout, or empty when no earlier run is known
     * @param removed      the files the base has and the workspace does not
     * @param unmappable   the files that differ or may differ and could not be compared, each with the reason
     */
    record Base(String base, String head, String from, String since, String previousHead, boolean dirty,
                Map<String, List<Range>> lines, String baseDate, String skipped, List<String> removed,
                List<String> unmappable) {

        /** Every file the step named: changed, new, removed or not compared. */
        int files() {
            return lines.size() + removed.size()
                    + (int) unmappable.stream().filter(u -> u.endsWith(NOT_REGULAR)).count();
        }
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
     * {@code previous-head}, {@code dirty}, {@code base-date}, {@code skipped}, {@code manifest} and {@code base/}.
     * The manifest holds three fields per file, each ended by a NUL: the status ({@code M} differs, {@code A} new,
     * {@code D} removed, {@code U} not compared), the path below the root, and the name of the file under
     * {@code base/} with the bytes the base has, or nothing. Anything else in the manifest is refused: half a list
     * must not pass for a whole one.
     *
     * @param root the workspace, where the files are as they are now
     */
    static Base read(Path dir, Path root) throws IOException {
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
        if (!skipped.matches("\\d{0,9}|unknown")) throw new IllegalArgumentException("the skipped commits in " + dir + " are no number");
        String date = optional(dir, "base-date");
        Map<String, List<Range>> lines = new TreeMap<>();
        List<String> removed = new ArrayList<>();
        List<String> unmappable = new ArrayList<>();
        String[] fields = file(dir, "manifest").split("\0", -1);
        if (fields.length % 3 != 1 || !fields[fields.length - 1].isEmpty()) {
            throw new IllegalArgumentException("the manifest in " + dir + " is cut");
        }
        for (int i = 0; i + 2 < fields.length; i += 3) {
            String status = fields[i];
            String path = fields[i + 1];
            String kept = fields[i + 2];
            if (path.isEmpty() || !kept.matches("\\d{0,9}")) {
                throw new IllegalArgumentException("the manifest in " + dir + " holds a record that is none");
            }
            switch (status) {
                case "A" -> lines.put(path, List.of(new Range(1, Integer.MAX_VALUE)));
                case "D" -> removed.add(path);
                case "U" -> unmappable.add(path + NOT_REGULAR);
                case "M" -> {
                    if (kept.isEmpty()) {
                        lines.put(path, List.of());
                        break;
                    }
                    Path now = root.resolve(path);
                    try {
                        lines.put(path, changedLines(textLines(Files.readAllBytes(dir.resolve("base").resolve(kept))),
                                textLines(Files.readAllBytes(now))));
                    } catch (IOException e) {
                        // It differs, and nothing can say where: the whole file counts, and the report names it
                        lines.put(path, List.of(new Range(1, Integer.MAX_VALUE)));
                        unmappable.add(path + " (it differs from the base, and its lines could not be compared)");
                    }
                }
                default -> throw new IllegalArgumentException("the manifest in " + dir + " holds an unknown status");
            }
        }
        return new Base(base, head, from, optional(dir, "since"), COMMIT.matcher(previous).matches() ? previous : "",
                file(dir, "dirty").strip().equals("true"), lines, date.matches("[0-9T:+Z-]{1,40}") ? date : "",
                skipped, List.copyOf(removed), List.copyOf(unmappable));
    }

    private static String file(Path dir, String name) throws IOException {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    private static String optional(Path dir, String name) throws IOException {
        return Files.exists(dir.resolve(name)) ? file(dir, name).strip() : "";
    }

    /** The lines of a file, whatever its encoding: each byte a character, a carriage return before a line end dropped. */
    private static List<String> textLines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        return lines;
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
     * annotation of a type. No area and no neighbour comes from them, so they are counted and named. Each as
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

    /** The changed files that are no Java file the finder read: nothing maps their lines to an area. */
    static List<String> unmapped(Result result, Map<String, List<Range>> lines) {
        Set<String> read = new java.util.HashSet<>();
        for (SourceFile file : result.files()) read.add(file.path());
        return lines.keySet().stream().filter(file -> !read.contains(file)).toList();
    }

    /**
     * The areas the changes pick, by the area's id; an area that is neither changed nor a neighbour is not in the
     * map. A changed area names what it is connected with too, so its verdict sees the other side of a change that
     * spans two members.
     */
    static Map<String, Pick> picks(List<Area> areas, Result result, Map<String, List<Range>> lines) {
        List<Declared> changed = changedMembers(result, lines);
        Set<Declared> isChanged = new java.util.HashSet<>(changed);
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
            for (Declared member : held) {
                boolean whole = member.member().start() >= area.start() && member.member().end() <= area.end();
                if (isChanged.contains(member) && !whole
                        && !touched(lines, area.file(), Math.max(area.start(), member.member().start()),
                        Math.min(area.end(), member.member().end()))) {
                    // The area shows a part of a long member, and the member changed in another part
                    connected.add(member);
                    if (!changedHere) reasons.add("changed: " + member.member().label() + " outside the lines of this area");
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
                    .append(cut ? " (a part of it):" : ":");
            List<String> code = files.containsKey(member.file()) ? files.get(member.file()).lines() : List.of();
            for (int line = start; line <= end && line <= code.size(); line++) {
                out.append('\n').append(line).append("| ").append(code.get(line - 1));
            }
        }
        if (connected.size() > MAX_CONNECTED) {
            out.append("\nNot shown, changed and connected too: ");
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
