package traceworks.selfaudit.areas;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The rules that pick the areas, read from a text file of the package. A rule has an id, a {@code why}, matchers
 * ({@code new}, {@code call}, {@code literal}) and optional {@code in} scopes:
 *
 * <pre>
 * rule process_start
 *   why  Starts a process.
 *   new  java.lang.ProcessBuilder
 *   call java.lang.Runtime#exec
 *   call java.io.PrintStream#printf nonconstant
 *   call java.lang.String#replaceAll nonconstant 0
 *   call java.util.regex.Pattern#compile constant \.[*+].*\.[*+]
 *   literal ^--network
 *   in   traceworks.adapters
 * </pre>
 *
 * Blank lines and lines starting with {@code #} are ignored. An error is refused with its line number.
 */
record RuleFile(List<Rule> rules) {

    private static final Pattern RULE_ID = Pattern.compile("[a-z][a-z0-9_]*");
    private static final String NAME = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final Pattern TYPE = Pattern.compile(NAME + "(\\." + NAME + ")*");
    private static final Pattern TYPE_AND_METHOD = Pattern.compile("(" + TYPE.pattern() + ")#(" + NAME + "|\\*)");

    /** A rule: its matchers apply in the packages of {@code packages}, or everywhere when it is empty. */
    record Rule(String id, String why, List<Matcher> matchers, List<String> packages) {

        /** Whether the rule applies in a compilation unit of package {@code pkg} ({@code ""} for none). */
        boolean appliesIn(String pkg) {
            return packages.isEmpty() || packages.stream().anyMatch(p -> pkg.equals(p) || pkg.startsWith(p + "."));
        }
    }

    /** What a rule matches in the code. */
    sealed interface Matcher {

        /** An instance creation of {@code type} or a subtype, also {@code Type::new}. */
        record New(String type) implements Matcher {
        }

        /** A call or method reference of {@code method} ({@code *} for any) on {@code type} or a subtype. */
        record Call(String type, String method, Argument argument) implements Matcher {
        }

        /** A string literal in which {@code pattern} finds a match. */
        record Literal(Pattern pattern) implements Matcher {

            @Override
            public boolean equals(Object o) {
                return o instanceof Literal other && other.pattern.pattern().equals(pattern.pattern());
            }

            @Override
            public int hashCode() {
                return pattern.pattern().hashCode();
            }
        }
    }

    /**
     * A condition on the arguments of a call: {@code NONCONSTANT} wants the argument at {@code index}, or with
     * {@code index} -1 any argument, to be no compile-time constant; {@code CONSTANT} wants the first argument to be a
     * constant string in which {@code pattern} finds a match.
     */
    record Argument(Kind kind, int index, Pattern pattern) {

        static final Argument ANY = new Argument(Kind.ANY, -1, null);

        enum Kind { ANY, NONCONSTANT, CONSTANT }

        static Argument nonconstant(int index) {
            return new Argument(Kind.NONCONSTANT, index, null);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Argument other && other.kind == kind && other.index == index
                    && (pattern == null ? other.pattern == null
                    : other.pattern != null && other.pattern.pattern().equals(pattern.pattern()));
        }

        @Override
        public int hashCode() {
            return (kind.hashCode() * 31 + index) * 31 + (pattern == null ? 0 : pattern.pattern().hashCode());
        }
    }

    /** The rules of {@code text}. */
    static RuleFile parse(String text) {
        List<Rule> rules = new ArrayList<>();
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
            if (keyword.equals("rule")) {
                if (current != null) rules.add(current.build());
                if (!RULE_ID.matcher(value).matches()) throw error(number, "'" + value + "' is no rule id");
                if (!ids.add(value)) throw error(number, "rule " + value + " twice");
                current = new Builder(value);
                continue;
            }
            if (!List.of("why", "new", "call", "literal", "in").contains(keyword)) {
                throw error(number, "unknown line '" + keyword + "'");
            }
            if (current == null) throw error(number, "'" + keyword + "' before the first rule");
            if (value.isEmpty()) throw error(number, "'" + keyword + "' needs a value");
            switch (keyword) {
                case "why" -> {
                    if (current.why != null) throw error(number, "a second why for rule " + current.id);
                    current.why = value;
                }
                case "new" -> {
                    if (!TYPE.matcher(value).matches()) throw error(number, "'" + value + "' is no type");
                    current.matchers.add(new Matcher.New(value));
                }
                case "call" -> current.matchers.add(call(number, value));
                case "literal" -> current.matchers.add(new Matcher.Literal(regex(number, value)));
                case "in" -> {
                    if (!TYPE.matcher(value).matches()) throw error(number, "'" + value + "' is no package");
                    current.packages.add(value);
                }
                default -> throw new IllegalStateException(keyword);
            }
        }
        if (current != null) rules.add(current.build());
        if (rules.isEmpty()) throw new IllegalArgumentException("the rule file holds no rule");
        return new RuleFile(List.copyOf(rules));
    }

    private static Matcher.Call call(int number, String value) {
        String[] parts = value.split("\\s+", 3);
        java.util.regex.Matcher target = TYPE_AND_METHOD.matcher(parts[0]);
        if (!target.matches()) throw error(number, "'" + parts[0] + "' is no <type>#<method>");
        String type = target.group(1);
        String method = target.group(3);
        if (parts.length == 1) return new Matcher.Call(type, method, Argument.ANY);
        return switch (parts[1]) {
            case "nonconstant" -> {
                if (parts.length > 2 && !parts[2].matches("\\d{1,3}")) {
                    throw error(number, "'nonconstant' takes an argument index (0 for the first) or nothing");
                }
                yield new Matcher.Call(type, method,
                        Argument.nonconstant(parts.length > 2 ? Integer.parseInt(parts[2]) : -1));
            }
            case "constant" -> {
                if (parts.length < 3) throw error(number, "'constant' needs a regular expression");
                yield new Matcher.Call(type, method, new Argument(Argument.Kind.CONSTANT, 0, regex(number, parts[2])));
            }
            default -> throw error(number,
                    "after the method only 'nonconstant [<index>]' or 'constant <regex>' may follow");
        };
    }

    private static Pattern regex(int number, String value) {
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw error(number, "'" + value + "' is no regular expression: " + e.getDescription());
        }
    }

    private static IllegalArgumentException error(int number, String message) {
        return new IllegalArgumentException("line " + number + ": " + message);
    }

    /** A rule while its lines are read. */
    private static final class Builder {
        private final String id;
        private String why;
        private final List<Matcher> matchers = new ArrayList<>();
        private final List<String> packages = new ArrayList<>();

        Builder(String id) {
            this.id = id;
        }

        Rule build() {
            if (why == null) throw new IllegalArgumentException("rule " + id + " has no why");
            if (matchers.isEmpty()) {
                throw new IllegalArgumentException("rule " + id + " has no new, call or literal line");
            }
            return new Rule(id, why, List.copyOf(matchers), List.copyOf(packages));
        }
    }
}
