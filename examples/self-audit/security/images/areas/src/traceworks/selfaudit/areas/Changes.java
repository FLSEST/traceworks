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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which areas a check of the changes looks at: the ones whose lines changed since a base commit, and the ones one
 * call away from a member that changed, in either direction ({@link Finder.Result#calls}).
 *
 * <p>What changed is git's word, read from a folder a step before wrote ({@link #read}): the base and the head
 * commit, and the unified diff between the base and the files as they are, with the files git does not track. The
 * tool starts no git itself. A line counts as changed when the diff adds or rewrites it; where lines were only
 * removed, the two lines around the gap count. A file git does not track is changed as a whole.
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

    private static final Pattern HUNK = Pattern.compile("@@ -\\d+(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
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
     * What the step before found out about the base.
     *
     * @param base         the commit the changes are measured from
     * @param head         the commit the workspace is at
     * @param from         where the base came from: {@code input} (the run's {@code since}) or {@code register} (the
     *                     workspace commit of the last run of this audit)
     * @param since        the input as it was given; empty without one
     * @param previousHead the workspace commit of the last run of this audit; empty when no report names one
     * @param dirty        whether the workspace holds changes that are in no commit
     * @param lines        the changed lines of each file, by its path below the root
     */
    record Base(String base, String head, String from, String since, String previousHead, boolean dirty,
                Map<String, List<Range>> lines) {
    }

    /** Why an area is looked at, and the changed members it is connected with outside its own lines. */
    record Pick(boolean changed, List<String> reasons, List<Declared> connected) {

        /** The reasons on one line, as the prompt and the report show them. */
        String reason() {
            return String.join("; ", reasons);
        }
    }

    /**
     * Reads the folder the step before wrote: {@code base}, {@code head}, {@code from}, {@code since},
     * {@code previous-head}, {@code dirty}, {@code diff} and {@code untracked} (names ended by a NUL).
     */
    static Base read(Path dir) throws IOException {
        String base = file(dir, "base").strip();
        String head = file(dir, "head").strip();
        String from = file(dir, "from").strip();
        if (!COMMIT.matcher(base).matches()) throw new IllegalArgumentException("the base in " + dir + " is no commit id");
        if (!COMMIT.matcher(head).matches()) throw new IllegalArgumentException("the head in " + dir + " is no commit id");
        if (!from.equals("input") && !from.equals("register")) {
            throw new IllegalArgumentException("the base in " + dir + " does not say where it is from");
        }
        String previous = Files.exists(dir.resolve("previous-head")) ? file(dir, "previous-head").strip() : "";
        return new Base(base, head, from, Files.exists(dir.resolve("since")) ? file(dir, "since").strip() : "",
                COMMIT.matcher(previous).matches() ? previous : "", file(dir, "dirty").strip().equals("true"),
                lines(file(dir, "diff"), file(dir, "untracked")));
    }

    private static String file(Path dir, String name) throws IOException {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * The changed lines of each file, from a unified diff ({@code git diff -U0}, with the prefixes {@code a/} and
     * {@code b/}) and the names of the files git does not track, each ended by a NUL. A removed file has no lines and
     * is not in the map. A hunk is read by the line counts of its header, so a line of code that looks like a header
     * is code.
     */
    static Map<String, List<Range>> lines(String diff, String untracked) {
        Map<String, List<Range>> lines = new TreeMap<>();
        String file = null;
        int oldLeft = 0;
        int newLeft = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("\\")) continue;
            if (oldLeft > 0 || newLeft > 0) {
                if (line.startsWith("-") && oldLeft > 0) {
                    oldLeft--;
                    continue;
                }
                if (line.startsWith("+") && newLeft > 0) {
                    newLeft--;
                    continue;
                }
                // Not what the header announced: the hunk is over, and the line is read like any other
                oldLeft = 0;
                newLeft = 0;
            }
            if (line.startsWith("+++ ")) {
                file = path(line.substring(4));
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (!hunk.matches() || file == null) continue;
            oldLeft = hunk.group(1) == null ? 1 : number(hunk.group(1));
            int start = number(hunk.group(2));
            newLeft = hunk.group(3) == null ? 1 : number(hunk.group(3));
            // Lines were only removed: the lines on both sides of the gap are what changed here
            Range range = newLeft == 0 ? new Range(Math.max(1, start), start + 1) : new Range(start, start + newLeft - 1);
            lines.computeIfAbsent(file, k -> new ArrayList<>()).add(range);
        }
        for (String name : untracked.split("\0")) {
            if (!name.isEmpty()) lines.put(name, List.of(new Range(1, Integer.MAX_VALUE)));
        }
        return lines;
    }

    private static int number(String digits) {
        return digits.length() > 9 ? Integer.MAX_VALUE / 2 : Integer.parseInt(digits);
    }

    /** The file a {@code +++} line names, without its prefix; {@code null} for a removed file. */
    private static String path(String written) {
        String name = written.endsWith("\t") ? written.substring(0, written.length() - 1) : written;
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) name = unquoted(name);
        if (name.equals("/dev/null")) return null;
        return name.startsWith("b/") ? name.substring(2) : name;
    }

    /** A name git wrote in quotes: {@code \\}, {@code \"}, {@code \t}, {@code \n} and bytes as three octal digits. */
    private static String unquoted(String quoted) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        String inner = quoted.substring(1, quoted.length() - 1);
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\' || i + 1 >= inner.length()) {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            char next = inner.charAt(++i);
            if (next >= '0' && next <= '3' && i + 2 < inner.length()
                    && inner.substring(i, i + 3).matches("[0-7]{3}")) {
                bytes.write(Integer.parseInt(inner.substring(i, i + 3), 8));
                i += 2;
            } else {
                bytes.write(switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
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
