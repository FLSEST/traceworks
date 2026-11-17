package traceworks.selfaudit.areas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the pin of the limits file reads of the documents: sections, bullets, passages, and the digest of a text. */
final class LimitDocs {

    private LimitDocs() {
    }

    /** The first 16 hex digits of the SHA-256 of a bullet's or a passage's text, as {@link #bullets} gives it. */
    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Pattern HEADING = Pattern.compile("^(#+) (.+)$");
    private static final Pattern BULLET = Pattern.compile("^- \\*\\*(.+?)\\*\\*");

    /** The lines below the heading {@code heading}, up to the next heading of its level or above. */
    static List<String> section(String document, String heading) {
        List<String> lines = new ArrayList<>();
        int level = 0;
        for (String line : document.split("\n")) {
            Matcher found = HEADING.matcher(line);
            if (level == 0) {
                if (found.matches() && found.group(2).equals(heading)) level = found.group(1).length();
                continue;
            }
            if (found.matches() && found.group(1).length() <= level) break;
            lines.add(line);
        }
        if (level == 0) throw new IllegalArgumentException("no heading '" + heading + "'");
        return lines;
    }

    /**
     * The bullets of a section by their bold lead, in order, each with its whole text on one line: the bullets that
     * start a line with {@code - **lead**}, with everything up to the next such line or heading. A bullet at the
     * start of a line without a bold lead has no name a limits file could give, and is refused. The bullets below a
     * line {@code What ... protects:} are guarantees, not limits: they are left out, up to the line
     * {@code What ... does not protect:}.
     */
    static Map<String, String> bullets(List<String> section) {
        Map<String, String> bullets = new LinkedHashMap<>();
        String lead = null;
        boolean guarantees = false;
        for (String line : section) {
            if (line.matches("What .* does not protect:")) {
                guarantees = false;
                lead = null;
                continue;
            }
            if (line.matches("What .* protects:")) {
                guarantees = true;
                lead = null;
                continue;
            }
            if (guarantees) continue;
            Matcher found = BULLET.matcher(line);
            if (found.find()) {
                lead = found.group(1);
                if (bullets.put(lead, line) != null) throw new IllegalArgumentException("two bullets '" + lead + "'");
            } else if (line.startsWith("- ")) {
                throw new IllegalArgumentException("a bullet without a bold lead: " + line);
            } else if (HEADING.matcher(line).matches()) {
                lead = null;
            } else if (lead != null) {
                bullets.merge(lead, line, (a, b) -> a + " " + b);
            }
        }
        bullets.replaceAll((name, text) -> text.replaceAll("\\s+", " "));
        return bullets;
    }

    private static final Pattern PASSAGE = Pattern.compile("^(?:- )?\\*\\*(.+?)\\*\\*");

    /**
     * The passages of a section that is not a list of limits, by their bold lead, each with its whole text on one
     * line: a paragraph or a bullet that starts a line with {@code **lead**}, with everything up to the next such
     * line or heading, a list without leads included. A section of guarantees states a limit here and there, in the
     * middle of a paragraph; a limits file names the passage and quotes the sentence. Nothing says that such a
     * section is named whole: only the quotes are pinned.
     */
    static Map<String, String> passages(List<String> section) {
        Map<String, String> passages = new LinkedHashMap<>();
        String lead = null;
        for (String line : section) {
            Matcher found = PASSAGE.matcher(line);
            if (found.find()) {
                lead = found.group(1);
                passages.merge(lead, line, (a, b) -> a + " " + b);
            } else if (HEADING.matcher(line).matches()) {
                lead = null;
            } else if (lead != null) {
                passages.merge(lead, line, (a, b) -> a + " " + b);
            }
        }
        passages.replaceAll((name, text) -> text.replaceAll("\\s+", " "));
        return passages;
    }

    /** The headings one level below {@code heading}, in order. */
    static List<String> subsections(String document, String heading) {
        List<String> found = new ArrayList<>();
        int level = 0;
        for (String line : section(document, heading)) {
            Matcher match = HEADING.matcher(line);
            if (!match.matches()) continue;
            if (level == 0) level = match.group(1).length();
            if (match.group(1).length() == level) found.add(match.group(2));
        }
        return found;
    }
}
