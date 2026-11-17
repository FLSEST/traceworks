package traceworks.selfaudit.areas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The limits file of the security self-audit ({@code rules/security.limits}) and the documents it is pinned to, for
 * the tests. No step reads the file: the judge gets the limits as a numbered list in the instructions of
 * {@code workflow.json}, which {@link #rendered} makes from the file, and the tests hold the file, the documents and
 * the instructions together.
 *
 * <pre>
 * source docs/security.md         the document the sections below are in
 * section Seals                   a heading of that document
 * limit A called package is ...   a bullet of the section, by its bold lead: a limit the judge gets
 *   quote A called package ...    a sentence of that bullet, word for word; one or more
 *   says  A called package ...    the limit in one line, for the judge
 * aside The private key is ...    a bullet that says nothing about what the code does: named, not sent
 * </pre>
 */
final class Limits {

    /**
     * One bullet of a document, as the file names it.
     *
     * @param says   the one line the judge gets; empty for an aside
     * @param quotes sentences of the bullet the limit rests on; empty for an aside
     */
    record Entry(String source, String section, String lead, boolean limit, List<String> quotes, String says) {
    }

    /** A section the file names, also one without an entry. */
    record Section(String source, String heading) {
    }

    private final List<Section> sections = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    private Limits() {
    }

    List<Section> sections() {
        return List.copyOf(sections);
    }

    List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** @throws IllegalArgumentException for a line the format does not know, with its number */
    static Limits parse(String text) {
        Limits limits = new Limits();
        String source = null;
        String section = null;
        String lead = null;
        boolean limit = false;
        List<String> quotes = new ArrayList<>();
        String says = "";
        String[] lines = text.split("\n");
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "source end";
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.strip().split("\\s+", 2);
            String rest = parts.length > 1 ? parts[1].strip() : "";
            boolean indented = Character.isWhitespace(line.charAt(0));
            if (indented && lead != null && limit && parts[0].equals("quote") && !rest.isEmpty()) {
                quotes.add(rest);
                continue;
            }
            if (indented && lead != null && limit && parts[0].equals("says") && !rest.isEmpty() && says.isEmpty()) {
                says = rest;
                continue;
            }
            if (indented || rest.isEmpty() || !List.of("source", "section", "limit", "aside").contains(parts[0])) {
                throw new IllegalArgumentException("line " + (i + 1) + ": not a line of a limits file: " + line);
            }
            if (lead != null) {
                if (limit && (quotes.isEmpty() || says.isEmpty())) {
                    throw new IllegalArgumentException("the limit '" + lead + "' needs a quote and a says line");
                }
                limits.entries.add(new Entry(source, section, lead, limit, List.copyOf(quotes), says));
                lead = null;
                quotes.clear();
                says = "";
            }
            switch (parts[0]) {
                case "source" -> {
                    source = rest;
                    section = null;
                }
                case "section" -> {
                    if (source == null) throw new IllegalArgumentException("line " + (i + 1) + ": a section before a source");
                    section = rest;
                    limits.sections.add(new Section(source, section));
                }
                default -> {
                    if (section == null) throw new IllegalArgumentException("line " + (i + 1) + ": an entry before a section");
                    lead = rest;
                    limit = parts[0].equals("limit");
                }
            }
        }
        return limits;
    }

    /**
     * The limits as the instructions hold them: numbered through all sections, one line each, under the document and
     * the heading they come from. A section without a limit is left out.
     */
    String rendered() {
        StringBuilder out = new StringBuilder();
        int number = 0;
        for (Section section : sections) {
            List<Entry> limits = entries.stream().filter(e -> e.limit() && e.source().equals(section.source())
                    && e.section().equals(section.heading())).toList();
            if (limits.isEmpty()) continue;
            out.append("From ").append(section.source()).append(", \"").append(section.heading()).append("\":\n");
            for (Entry entry : limits) out.append("L").append(++number).append(". ").append(entry.says()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    // ---- the documents ----

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
