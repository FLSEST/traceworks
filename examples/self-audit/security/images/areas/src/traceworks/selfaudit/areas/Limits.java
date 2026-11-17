package traceworks.selfaudit.areas;

import java.util.ArrayList;
import java.util.List;

/**
 * The limits file of the security self-audit ({@code rules/security.limits}): what the documents say the defaults do
 * not protect. The judge gets the limits as a numbered list in the instructions of {@code workflow.json}, which
 * {@link #rendered} makes from the file and a test holds to it. {@code find --limits} reads the file and hands the
 * report the line of each limit by its number ({@link #numbered}), so that a verdict that cites a limit can be
 * checked: the limit must be one of the list.
 *
 * <pre>
 * source docs/security.md         the document the sections below are in
 * section Seals                   a heading of that document
 * limit A called package is ...   a bullet of the section, by its bold lead: a limit the judge gets
 *   quote A called package ...    a sentence of that bullet, word for word; one or more
 *   says  A called package ...    the limit in one line, for the judge
 *   digest 3f0c...                a digest of the bullet's whole text, which a test holds to the documents
 * aside The private key is ...    a bullet that says nothing about what the code does: named, not sent
 *   digest 9ab1...
 * </pre>
 */
final class Limits {

    /**
     * One bullet of a document, as the file names it.
     *
     * @param says   the one line the judge gets; empty for an aside
     * @param quotes sentences of the bullet the limit rests on; empty for an aside
     * @param digest the digest of the bullet's whole text the file names; empty when it names none
     */
    record Entry(String source, String section, String lead, boolean limit, List<String> quotes, String says,
                 String digest) {
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
        String digest = "";
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
            if (indented && lead != null && parts[0].equals("digest") && rest.matches("[0-9a-f]{16}")
                    && digest.isEmpty()) {
                digest = rest;
                continue;
            }
            if (indented || rest.isEmpty() || !List.of("source", "section", "limit", "aside").contains(parts[0])) {
                throw new IllegalArgumentException("line " + (i + 1) + ": not a line of a limits file: " + line);
            }
            if (lead != null) {
                if (limit && (quotes.isEmpty() || says.isEmpty())) {
                    throw new IllegalArgumentException("the limit '" + lead + "' needs a quote and a says line");
                }
                limits.entries.add(new Entry(source, section, lead, limit, List.copyOf(quotes), says, digest));
                lead = null;
                quotes.clear();
                says = "";
                digest = "";
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

    /** The line of each limit, in the order of their numbers: the first is {@code L1}. */
    List<String> numbered() {
        List<String> lines = new ArrayList<>();
        for (Section section : sections) {
            for (Entry entry : entries) {
                if (entry.limit() && entry.source().equals(section.source())
                        && entry.section().equals(section.heading())) {
                    lines.add(entry.says());
                }
            }
        }
        return List.copyOf(lines);
    }
}
