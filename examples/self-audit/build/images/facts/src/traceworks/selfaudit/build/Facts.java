package traceworks.selfaudit.build;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one step of the audit measured: its facts (counts and values), its items (each a place where a check found
 * something) and its gaps (what it could not measure, and why). A step writes it as JSON into the scratch directory
 * and prints it as a fact sheet; the report step reads the JSON of every step and decides the gate. A step never
 * decides a level: which check is a broken rule is the report's table ({@link Report#LEVELS}).
 */
final class Facts {

    /** How many items of one check the fact sheet on standard output lists; the JSON holds them all. */
    static final int SHOWN = 60;

    /**
     * One place where a check found something.
     *
     * @param check   the check's id, such as {@code version_range}
     * @param file    the file, relative to the workspace, or {@code ""}
     * @param line    its line, or 0
     * @param subject what it is about, such as {@code groupId:artifactId}
     * @param detail  what was found, in one line
     */
    record Item(String check, String file, int line, String subject, String detail) {

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", check);
            m.put("file", file);
            m.put("line", (long) line);
            m.put("subject", subject);
            m.put("detail", detail);
            return m;
        }

        static Item of(Object json) {
            Map<String, Object> m = object(json);
            return new Item(text(m.get("check")), text(m.get("file")), (int) number(m.get("line")),
                    text(m.get("subject")), text(m.get("detail")));
        }

        /** {@code file:line}, {@code file} or {@code ""}. */
        String where() {
            if (file.isEmpty()) return "";
            return line > 0 ? file + ":" + line : file;
        }
    }

    /** Something a step could not measure. */
    record Gap(String what, String reason) {

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("what", what);
            m.put("reason", reason);
            return m;
        }

        static Gap of(Object json) {
            Map<String, Object> m = object(json);
            return new Gap(text(m.get("what")), text(m.get("reason")));
        }
    }

    final String step;
    final Map<String, Object> facts = new LinkedHashMap<>();
    final List<Item> items = new ArrayList<>();
    final List<Gap> gaps = new ArrayList<>();
    /** What a later step needs from this one, such as the inventory of versions to look up. */
    final Map<String, Object> data = new LinkedHashMap<>();

    Facts(String step) {
        this.step = step;
    }

    void fact(String name, Object value) {
        facts.put(name, value instanceof Integer i ? (long) i : value);
    }

    void item(String check, String file, int line, String subject, String detail) {
        items.add(new Item(check, file, line, subject, detail));
    }

    void gap(String what, String reason) {
        gaps.add(new Gap(what, reason));
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("facts", facts);
        m.put("items", items.stream().map(Item::toJson).toList());
        m.put("gaps", gaps.stream().map(Gap::toJson).toList());
        m.put("data", data);
        return m;
    }

    static Facts of(Object json) {
        Map<String, Object> m = object(json);
        Facts f = new Facts(text(m.get("step")));
        object(m.getOrDefault("facts", Map.of())).forEach(f.facts::put);
        for (Object item : list(m.get("items"))) f.items.add(Item.of(item));
        for (Object gap : list(m.get("gaps"))) f.gaps.add(Gap.of(gap));
        object(m.getOrDefault("data", Map.of())).forEach(f.data::put);
        return f;
    }

    /**
     * The fact sheet on standard output: {@code FACT}, then {@code HIT} per item (at most {@value #SHOWN} per check),
     * then {@code NOT MEASURED} per gap. Every value from the workspace or the network is escaped
     * ({@link Main#safe}), so none can start a line of its own.
     */
    void print(PrintStream out) {
        facts.forEach((name, value) -> out.println("FACT " + step + "_" + name + ": " + Main.safe(String.valueOf(value))));
        Map<String, Integer> shown = new LinkedHashMap<>();
        for (Item item : items) {
            int n = shown.merge(item.check(), 1, Integer::sum);
            if (n > SHOWN) continue;
            out.println("HIT " + item.check() + ": " + Main.safe(item.where().isEmpty() ? item.subject()
                    : item.where() + " " + item.subject()) + (item.detail().isEmpty() ? "" : " " + Main.safe(item.detail())));
        }
        shown.forEach((check, n) -> {
            if (n > SHOWN) out.println("# " + (n - SHOWN) + " more " + check + " lines in the JSON");
        });
        for (Gap gap : gaps) out.println("NOT MEASURED: " + Main.safe(gap.what()) + " (" + Main.safe(gap.reason()) + ")");
    }

    static String text(Object value) {
        return value instanceof String s ? s : value == null ? "" : String.valueOf(value);
    }

    static long number(Object value) {
        return value instanceof Long l ? l : value instanceof Integer i ? i : 0;
    }

    static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("not a JSON object");
    }
}
