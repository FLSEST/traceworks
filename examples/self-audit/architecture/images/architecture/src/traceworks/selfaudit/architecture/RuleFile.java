package traceworks.selfaudit.architecture;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The architecture rules, read from a text file of the package: which package must not depend on which (a
 * {@code layer} rule), and where direct I/O is forbidden, with the exceptions that are documented (an {@code io} rule).
 *
 * <pre>
 * prefix traceworks
 *
 * layer inner_core
 *   why  The inner packages of the core know nothing of the packages that use them.
 *   from core.port core.model
 *   not  core.app core.engine
 *
 * io core_io
 *   why  The core reaches files, processes and the network through its ports.
 *   in    core.*
 *   type  java.nio.file.Files
 *   type  java.net.*
 *   call  java.lang.System#getenv
 *   allow core.app.TraceworksVersion reads its version from the class path
 * </pre>
 *
 * Names below the {@code prefix} are written without it. A package pattern ending in {@code .*} matches that package
 * and every package below it; any other pattern matches one package. A {@code type} pattern ending in {@code .*}
 * matches every type of that package and below. Blank lines and lines starting with {@code #} are ignored; an error is
 * refused with its line number.
 */
record RuleFile(String prefix, List<Layer> layers, List<Io> ios) {

    private static final Pattern RULE_ID = Pattern.compile("[a-z][a-z0-9_]*");
    private static final String NAME = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final Pattern QUALIFIED = Pattern.compile(NAME + "(\\." + NAME + ")*");
    private static final Pattern PATTERN = Pattern.compile(QUALIFIED.pattern() + "(\\.\\*)?");
    private static final Pattern TYPE_AND_METHOD = Pattern.compile("(" + QUALIFIED.pattern() + ")#(" + NAME + ")");

    /** A layer rule: a package that {@code from} matches must not depend on one that {@code not} matches. */
    record Layer(String id, String why, List<String> from, List<String> not) {
    }

    /**
     * An I/O rule: in the packages {@code in} matches, a reference to a type of {@code types} or a call of
     * {@code calls} is direct I/O, unless its class is one of {@code allowed}.
     */
    record Io(String id, String why, List<String> in, List<String> types, List<Call> calls, List<Allow> allowed) {
    }

    /** A call of {@code method} on {@code type} or a subtype. */
    record Call(String type, String method) {

        @Override
        public String toString() {
            return type + "#" + method;
        }
    }

    /** A documented exception: the class (or class pattern) and why it may do I/O. */
    record Allow(String type, String why) {
    }

    /** Whether package {@code pkg} matches {@code pattern}. */
    static boolean matches(String pattern, String pkg) {
        if (pattern.endsWith(".*")) {
            String base = pattern.substring(0, pattern.length() - 2);
            return pkg.equals(base) || pkg.startsWith(base + ".");
        }
        return pkg.equals(pattern);
    }

    /** Whether package {@code pkg} matches one of {@code patterns}. */
    static boolean matchesAny(List<String> patterns, String pkg) {
        return patterns.stream().anyMatch(p -> matches(p, pkg));
    }

    /** {@code name} without the prefix, when it is below it. */
    String relative(String name) {
        if (prefix.isEmpty()) return name;
        return name.startsWith(prefix + ".") ? name.substring(prefix.length() + 1) : name;
    }

    /** The rule ids of the file, layers first. */
    List<String> ids() {
        List<String> ids = new ArrayList<>();
        layers.forEach(l -> ids.add(l.id()));
        ios.forEach(i -> ids.add(i.id()));
        return List.copyOf(ids);
    }

    /** The rules of {@code text}. */
    static RuleFile parse(String text) {
        String prefix = null;
        List<Layer> layers = new ArrayList<>();
        List<Io> ios = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Builder current = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int number = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            String keyword = parts[0];
            String value = parts.length > 1 ? parts[1].strip() : "";
            if (value.isEmpty()) throw error(number, "'" + keyword + "' needs a value");
            switch (keyword) {
                case "prefix" -> {
                    if (prefix != null) throw error(number, "a second prefix");
                    if (current != null) throw error(number, "the prefix comes before the first rule");
                    if (!QUALIFIED.matcher(value).matches()) throw error(number, "'" + value + "' is no package");
                    prefix = value;
                }
                case "layer", "io" -> {
                    if (current != null) current.build(layers, ios);
                    if (!RULE_ID.matcher(value).matches()) throw error(number, "'" + value + "' is no rule id");
                    if (!ids.add(value)) throw error(number, "rule " + value + " twice");
                    current = new Builder(keyword, value);
                }
                case "why", "from", "not", "in", "type", "call", "allow" -> {
                    if (current == null) throw error(number, "'" + keyword + "' before the first rule");
                    current.add(number, keyword, value);
                }
                default -> throw error(number, "unknown line '" + keyword + "'");
            }
        }
        if (current != null) current.build(layers, ios);
        if (layers.isEmpty() && ios.isEmpty()) throw new IllegalArgumentException("the rule file holds no rule");
        return new RuleFile(prefix == null ? "" : prefix, List.copyOf(layers), List.copyOf(ios));
    }

    private static IllegalArgumentException error(int number, String message) {
        return new IllegalArgumentException("line " + number + ": " + message);
    }

    /** A rule while its lines are read. */
    private static final class Builder {
        private final String kind;
        private final String id;
        private String why;
        private final List<String> from = new ArrayList<>();
        private final List<String> not = new ArrayList<>();
        private final List<String> in = new ArrayList<>();
        private final List<String> types = new ArrayList<>();
        private final List<Call> calls = new ArrayList<>();
        private final List<Allow> allowed = new ArrayList<>();

        Builder(String kind, String id) {
            this.kind = kind;
            this.id = id;
        }

        void add(int number, String keyword, String value) {
            boolean layer = kind.equals("layer");
            if ((layer && !List.of("why", "from", "not").contains(keyword))
                    || (!layer && List.of("from", "not").contains(keyword))) {
                throw error(number, "'" + keyword + "' does not belong in a " + kind + " rule");
            }
            switch (keyword) {
                case "why" -> {
                    if (why != null) throw error(number, "a second why for rule " + id);
                    why = value;
                }
                case "from" -> from.addAll(patterns(number, value));
                case "not" -> not.addAll(patterns(number, value));
                case "in" -> in.addAll(patterns(number, value));
                case "type" -> types.addAll(patterns(number, value));
                case "call" -> {
                    java.util.regex.Matcher m = TYPE_AND_METHOD.matcher(value);
                    if (!m.matches()) throw error(number, "'" + value + "' is no <type>#<method>");
                    calls.add(new Call(m.group(1), m.group(3)));
                }
                case "allow" -> {
                    String[] parts = value.split("\\s+", 2);
                    if (!PATTERN.matcher(parts[0]).matches()) throw error(number, "'" + parts[0] + "' is no class");
                    if (parts.length < 2) throw error(number, "an allow needs a reason after the class");
                    allowed.add(new Allow(parts[0], parts[1].strip()));
                }
                default -> throw new IllegalStateException(keyword);
            }
        }

        private static List<String> patterns(int number, String value) {
            List<String> patterns = new ArrayList<>();
            for (String pattern : value.split("\\s+")) {
                if (!PATTERN.matcher(pattern).matches()) throw error(number, "'" + pattern + "' is no package");
                patterns.add(pattern);
            }
            return patterns;
        }

        void build(List<Layer> layers, List<Io> ios) {
            if (why == null) throw new IllegalArgumentException("rule " + id + " has no why");
            if (kind.equals("layer")) {
                if (from.isEmpty() || not.isEmpty()) {
                    throw new IllegalArgumentException("layer " + id + " needs a from and a not line");
                }
                layers.add(new Layer(id, why, List.copyOf(from), List.copyOf(not)));
            } else {
                if (in.isEmpty()) throw new IllegalArgumentException("io " + id + " needs an in line");
                if (types.isEmpty() && calls.isEmpty()) {
                    throw new IllegalArgumentException("io " + id + " has no type or call line");
                }
                ios.add(new Io(id, why, List.copyOf(in), List.copyOf(types), List.copyOf(calls),
                        List.copyOf(allowed)));
            }
        }
    }
}
