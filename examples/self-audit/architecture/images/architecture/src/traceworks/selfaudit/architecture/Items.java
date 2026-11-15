package traceworks.selfaudit.architecture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The candidate files as items of the audit (docs/registers.md, "Items"). An item is what one verdict is about: here
 * a file. The register keeps for each item the digest of what its last verdict saw and says whether it is open, not
 * judged, decided or clean; a run gets that back in {@code {{ audit_items }}} and leaves out a file that is settled
 * and has not changed.
 *
 * <p>A row is {@code state | verdict digest | item}, with {@code -} for no verdict. Anything that is no row gives
 * none: the head of the block, the text of a first run, and the literal placeholder where nothing sets the variable
 * (an older Traceworks). Then no item is known and every candidate is judged.
 */
final class Items {

    /** The most characters the register takes for a name. */
    static final int NAME_LIMIT = 300;

    /**
     * The fields of a candidate line that the prompt shows and that make the question: the file, its sizes, why the
     * tool chose it, the measured findings that name it, the computed context (without the type counts of its list
     * of packages, {@link #withoutTypeCounts}), and the code with what is said about the part shown. The number of the candidate, the frame of the run and what the register knows are not among
     * them: they differ from run to run and the question stays the same.
     */
    static final List<String> SEEN = List.of("file", "ncloc", "lines", "reasons_text", "findings", "context",
            "shown_text", "code");

    private static final Set<String> STATES = Set.of("open", "not judged", "decided", "clean");

    private Items() {
    }

    /**
     * What the register says of one item.
     *
     * @param digest the digest of its last verdict; empty when it has none
     */
    record Row(String state, String digest) {

        /** Whether there is nothing to do for the item while its digest stays the same. */
        boolean settled() {
            return state.equals("clean") || state.equals("decided");
        }
    }

    /** The rows of the block by the name the register shows. */
    static Map<String, Row> parse(String auditItems) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (String line : auditItems.split("\n")) {
            String[] cells = line.strip().split(" \\| ");
            if (cells.length != 3 || !STATES.contains(cells[0])) {
                continue;
            }
            rows.put(cells[2], new Row(cells[0], cells[1].equals("-") ? "" : cells[1]));
        }
        return rows;
    }

    /**
     * The item names of files, in order: the path of the file below the workspace, which is also the subject of a
     * verdict about it. A name the register would refuse is made one it takes: a control character becomes
     * {@code ?}, a name over {@value #NAME_LIMIT} characters is cut and ends with a digest of the whole, and a name
     * that is there already gets {@code ~2}, {@code ~3}.
     */
    static List<String> names(List<String> files) {
        Map<String, Integer> seen = new HashMap<>();
        Set<String> taken = new java.util.HashSet<>();
        List<String> names = new ArrayList<>();
        for (String file : files) {
            // Counted on the name as the register gets it: two names that differ in a control character only are
            // one name once fitted, and the register refuses a list that holds a name twice
            String name = fitted(file);
            int count = seen.merge(name, 1, Integer::sum);
            String item = count == 1 ? name : fitted(name + "~" + count);
            while (!taken.add(item)) {
                count = seen.merge(name, 1, Integer::sum);
                item = fitted(name + "~" + count);
            }
            names.add(item);
        }
        return names;
    }

    private static String fitted(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        name.codePoints().forEach(c -> safe.appendCodePoint(
                Character.isISOControl(c) || c == 0x2028 || c == 0x2029 ? '?' : c));
        if (safe.length() <= NAME_LIMIT) {
            return safe.toString();
        }
        int cut = NAME_LIMIT - 17;
        if (Character.isHighSurrogate(safe.charAt(cut - 1))) cut--;
        return safe.substring(0, cut) + "~" + hex(name).substring(0, 16);
    }

    /**
     * What a verdict about a file sees, as 16 hex digits: the version of the question, and every field of the
     * candidate line in {@link #SEEN}, each by its name and its length, so two fields never read as one.
     *
     * @param line a line of {@code candidates.jsonl}, as a prompt gets it
     */
    static String digest(int verdictVersion, Map<String, Object> line) {
        StringBuilder seen = new StringBuilder("verdict_version ").append(verdictVersion).append('\n');
        for (String field : SEEN) {
            String value = String.valueOf(line.get(field));
            if (field.equals("context")) value = withoutTypeCounts(value);
            seen.append(field).append(' ').append(value.length()).append('\n').append(value).append('\n');
        }
        return hex(seen.toString()).substring(0, 16);
    }

    /**
     * The context as the digest takes it: the list of packages without the number of types of each. A class added
     * anywhere changes a count, and that must not make every file be judged again; a package that is new or gone
     * still changes the digest, since a move names a package.
     */
    static String withoutTypeCounts(String context) {
        int list = context.lastIndexOf(Candidates.PACKAGES_HEAD);
        if (list < 0) return context;
        return context.substring(0, list)
                + context.substring(list).replaceAll("(?m)^(- \\S+) \\(\\d+ types?\\)", "$1");
    }

    private static String hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
