package traceworks.cli.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the jar carries for whoever writes a workflow: a short guide, the documents of the format and of the
 * registers, and files of some example packages of the repository (the two smallest whole). It is Traceworks' own text, the same for every home,
 * and nothing here reads a file system: a name picks an entry of a fixed list, never a path.
 */
final class McpGuide {

    /** The most characters of one page: a page ends at the end of a line before it. */
    static final int PAGE_CHARS = 30_000;

    private static final String ROOT = "/traceworks-guide/";

    /** The topics of the tool {@code guide}, each with the file it reads; the first is the one without a topic. */
    static final Map<String, String> TOPICS = topics();

    /**
     * An example package.
     *
     * @param shows what a reader learns from it
     * @param files the files of it a client may ask for; the first is the one without a file
     */
    record Example(String shows, List<String> files) {
    }

    /** What every example hands out. */
    private static final List<String> FILES = List.of("workflow.json", "README.md");

    /** The examples, by their folder under {@code examples/} of the repository. */
    static final Map<String, Example> EXAMPLES = examples();

    /**
     * The parts of a text that hold every word of a search.
     *
     * @param matches how many parts hold them
     * @param text    those parts, a blank line between two, as far as a page holds them
     * @param cut     whether parts beyond the page were left out
     */
    record Found(int matches, String text, boolean cut) {
    }

    private McpGuide() {
    }

    private static Map<String, String> topics() {
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("authoring", "authoring.md");
        topics.put("format", "workflow-format.md");
        topics.put("registers", "registers.md");
        return topics;
    }

    private static Map<String, Example> examples() {
        Map<String, String> examples = new LinkedHashMap<>();
        examples.put("hello", "The smallest workflow, whole: shell steps, an input, a script of the package, an"
                + " approval and a gate. No AI.");
        examples.put("ai-hello", "One AI step that may read the workspace and change nothing: provider, tools and"
                + " requires.");
        examples.put("audit-hello", "The smallest audit, whole and without AI: it measures, reports each finding in"
                + " the envelope a register reads, and the register keeps the findings and what a person decided"
                + " about each across runs. Start here when a person decides item by item.");
        examples.put("self-audit/build", "An audit of a build and its dependencies with deterministic steps only:"
                + " tools in images of the package, a rule file, a gate.");
        examples.put("self-audit/architecture", "Measures a code base with a tool of its own (package graph, layer"
                + " rules, sizes), then has a model judge each candidate file: foreach over items.");
        examples.put("self-audit/security", "A deterministic area finder in front of one AI verdict per area, with"
                + " a model choice, read-only tools and a check of each finding's evidence.");
        examples.put("self-audit/security-changes", "The short check beside it: git says what changed since a"
                + " base, and the model judges only the changed areas and their neighbours.");
        Map<String, List<String>> more = Map.of(
                "hello", List.of("scripts/count-files.sh"),
                "audit-hello", List.of("catalog.json", "scripts/measure.sh", "scripts/report.sh"));
        Map<String, Example> all = new LinkedHashMap<>();
        examples.forEach((name, shows) -> {
            List<String> files = new ArrayList<>(FILES);
            files.addAll(more.getOrDefault(name, List.of()));
            all.put(name, new Example(shows, List.copyOf(files)));
        });
        return all;
    }

    /**
     * The parts of {@code text} that hold every word of {@code search}, whatever the case: a part is a line of a
     * table or of a list with the lines that continue it, or a paragraph, so a rule of the format comes whole.
     */
    static Found search(String text, String search) {
        List<String> words = List.of(search.toLowerCase(Locale.ROOT).trim().split("\\s+"));
        StringBuilder out = new StringBuilder();
        int matches = 0;
        boolean cut = false;
        for (String part : parts(text)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (!words.stream().allMatch(lower::contains)) {
                continue;
            }
            matches++;
            if (out.length() + part.length() + 2 > PAGE_CHARS) {
                cut = true;
                continue;
            }
            out.append(out.isEmpty() ? "" : "\n\n").append(part);
        }
        return new Found(matches, out.toString(), cut);
    }

    /** The paragraphs of a text, a row of a table and an entry of a list each as a part of its own. */
    private static List<String> parts(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String line : text.split("\n")) {
            boolean starts = line.startsWith("|") || line.startsWith("- ") || line.startsWith("#");
            if (line.isBlank() || starts) {
                if (!part.isEmpty()) {
                    parts.add(part.toString());
                    part.setLength(0);
                }
            }
            if (!line.isBlank()) {
                part.append(part.isEmpty() ? "" : "\n").append(line);
            }
        }
        if (!part.isEmpty()) {
            parts.add(part.toString());
        }
        return parts;
    }

    /** The text of a topic. */
    static String topic(String topic) {
        return read(TOPICS.get(topic));
    }

    /** The file of an example. */
    static String example(String name, String file) {
        return read("examples/" + name + "/" + file);
    }

    /**
     * Where the page that starts at {@code from} ends: at the end of the text, or after the last line that ends
     * within {@link #PAGE_CHARS}; within a line longer than a page, after that many characters.
     */
    static int pageEnd(String text, int from) {
        if (text.length() - from <= PAGE_CHARS) {
            return text.length();
        }
        int line = text.lastIndexOf('\n', from + PAGE_CHARS - 1);
        return line >= from ? line + 1 : from + PAGE_CHARS;
    }

    private static String read(String file) {
        try (InputStream in = McpGuide.class.getResourceAsStream(ROOT + file)) {
            if (in == null) {
                throw new IllegalStateException("The jar does not carry " + ROOT + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
