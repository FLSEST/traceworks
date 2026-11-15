package traceworks.selfaudit.areas;

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
 * The areas as items of the audit (docs/registers.md, "Items"). An item is what one verdict is about: here an area.
 * The register keeps for each item the digest of what its last verdict saw and says whether it is open, not judged,
 * decided or clean; a run gets that back in {@code {{ audit_items }}} and leaves out an area that is settled and has
 * not changed.
 *
 * <p>A row is {@code state | verdict digest | item}, with {@code -} for no verdict. Anything that is no row gives
 * none: the head of the block, the text of a first run, and the literal placeholder where nothing sets the variable
 * (an older Traceworks). Then no item is known and every area is judged.
 */
final class Items {

    /** The most characters the register takes for a name. */
    static final int NAME_LIMIT = 300;

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
     * The item names of areas, each given as its file and its first member, in order: {@code <file>#<member>}, and
     * {@code ~2}, {@code ~3} for a further area of the same member. The first member names an area better than all
     * its members do: an area takes in a neighbour when the code between them shrinks, and its name should not
     * change for that. A name the register would refuse is made one it takes: a control character becomes
     * {@code ?}, and a name over {@value #NAME_LIMIT} characters is cut and ends with a digest of the whole.
     */
    static List<String> names(List<String[]> fileAndMember) {
        Map<String, Integer> seen = new HashMap<>();
        Set<String> taken = new java.util.HashSet<>();
        List<String> names = new ArrayList<>();
        for (String[] area : fileAndMember) {
            // Counted on the name as the register gets it: two names that differ in a control character only are
            // one name once fitted, and the register refuses a list that holds a name twice
            String name = fitted(area[0] + "#" + area[1]);
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
     * What a verdict about an area sees, as 16 hex digits: the version of the question, the file, the rules that
     * picked the area, and its code without the line numbers, so an area that only moved keeps its digest.
     */
    static String digest(int verdictVersion, String file, List<String> rules, String numberedCode) {
        StringBuilder seen = new StringBuilder("verdict_version ").append(verdictVersion).append('\n')
                .append(file).append('\n').append(String.join(",", rules)).append('\n');
        for (String line : numberedCode.split("\n", -1)) {
            seen.append(line.replaceFirst("^\\d+\\| ?", "")).append('\n');
        }
        return hex(seen.toString()).substring(0, 16);
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
