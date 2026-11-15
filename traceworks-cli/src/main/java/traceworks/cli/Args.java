package traceworks.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small generic argument parser. Each command declares its flags in a {@link Spec}; {@code --home} and
 * {@code --help} belong to every command. A value comes as {@code --flag value} or {@code --flag=value}; the value
 * may start with a dash. {@code --} ends the flags.
 */
public final class Args {

    private Args() {
    }

    /** What a command accepts. */
    public static final class Spec {
        private final String name;
        private final String summary;
        private final String positional;
        private final Set<String> switches = new LinkedHashSet<>(List.of("help"));
        private final Set<String> options = new LinkedHashSet<>(List.of("home"));
        private final Set<String> repeatable = new LinkedHashSet<>();
        private final Map<String, String> descriptions = new LinkedHashMap<>();
        private final List<String> notes = new ArrayList<>();
        private int minPositionals;
        private int maxPositionals;

        private Spec(String name, String summary, String positional) {
            this.name = name;
            this.summary = summary;
            this.positional = positional;
            this.minPositionals = positional == null ? 0 : 1;
            this.maxPositionals = minPositionals;
        }

        /**
         * @param positional the name of the one positional argument such as {@code <run>}; {@code null} for none
         */
        public static Spec of(String name, String summary, String positional) {
            return new Spec(name, summary, positional);
        }

        /**
         * The command takes between {@code min} and {@code max} positional arguments, described by the text given to
         * {@link #of}; {@link Parsed#positionals()} has them.
         */
        public Spec positionals(int min, int max) {
            this.minPositionals = min;
            this.maxPositionals = max;
            return this;
        }

        /** A flag without a value. */
        public Spec flag(String name, String description) {
            switches.add(name);
            descriptions.put("--" + name, description);
            return this;
        }

        /** A flag with a value, given once (the last one counts). */
        public Spec option(String name, String valueName, String description) {
            options.add(name);
            descriptions.put("--" + name + " " + valueName, description);
            return this;
        }

        /** A flag with a value that may be given several times. */
        public Spec repeated(String name, String valueName, String description) {
            options.add(name);
            repeatable.add(name);
            descriptions.put("--" + name + " " + valueName, description);
            return this;
        }

        /**
         * A paragraph the usage text ends with, after an empty line: what a user of the command has to know that no
         * flag says. Its lines are split at {@code \n}.
         */
        public Spec note(String paragraph) {
            notes.add(paragraph);
            return this;
        }

        public String name() {
            return name;
        }

        public String summary() {
            return summary;
        }

        /** The usage text of the command. */
        public String usage() {
            StringBuilder text = new StringBuilder("usage: traceworks ").append(name);
            if (positional != null) {
                text.append(' ').append(positional);
            }
            text.append(" [options]\n\n").append(summary).append("\n\noptions:\n");
            descriptions.forEach((flag, description) -> text.append(String.format("  %-28s %s%n", flag, description)));
            text.append(String.format("  %-28s %s%n", "--home DIR", "the Traceworks home (default: $TRACEWORKS_HOME,"
                    + " else ~/.traceworks)"));
            text.append(String.format("  %-28s %s%n", "--help", "show this text"));
            for (String note : notes) {
                text.append(System.lineSeparator());
                note.lines().forEach(line -> text.append(line).append(System.lineSeparator()));
            }
            return text.toString();
        }
    }

    /** The result of parsing. */
    public static final class Parsed {
        private final Map<String, List<String>> values = new LinkedHashMap<>();
        private final Set<String> present = new LinkedHashSet<>();
        private final List<String> positionals = new ArrayList<>();

        /** Whether a flag was given. */
        public boolean has(String name) {
            return present.contains(name);
        }

        /** The value of an option; the last one when given repeatedly; {@code null} when absent. */
        public String option(String name) {
            List<String> all = values.get(name);
            return all == null ? null : all.getLast();
        }

        /** All values of an option, in order. */
        public List<String> options(String name) {
            return values.getOrDefault(name, List.of());
        }

        /** All positional arguments, in order. */
        public List<String> positionals() {
            return List.copyOf(positionals);
        }

        /** The positional argument; {@code null} when the command takes none. */
        public String positional() {
            return positionals.isEmpty() ? null : positionals.getFirst();
        }
    }

    /**
     * @throws UsageException for an unknown flag, a missing or unexpected value, a repeated single flag, or the wrong
     *                        number of positional arguments
     */
    public static Parsed parse(Spec spec, List<String> args) {
        Parsed parsed = new Parsed();
        List<String> positionals = new ArrayList<>();
        boolean flagsEnded = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!flagsEnded && arg.equals("--")) {
                flagsEnded = true;
                continue;
            }
            if (flagsEnded || !arg.startsWith("--")) {
                positionals.add(arg);
                continue;
            }
            String name = arg.substring(2);
            String inline = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                inline = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (spec.switches.contains(name)) {
                if (inline != null) {
                    throw new UsageException("--" + name + " takes no value");
                }
                parsed.present.add(name);
            } else if (spec.options.contains(name)) {
                String value = inline;
                if (value == null) {
                    if (i + 1 >= args.size()) {
                        throw new UsageException("--" + name + " needs a value");
                    }
                    value = args.get(++i);
                }
                if (parsed.present.contains(name) && !spec.repeatable.contains(name)) {
                    throw new UsageException("--" + name + " is given twice");
                }
                parsed.present.add(name);
                parsed.values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            } else {
                throw new UsageException("unknown option --" + name);
            }
        }
        if (parsed.has("help")) {
            return parsed;
        }
        int expected = spec.maxPositionals;
        if (spec.minPositionals != spec.maxPositionals) {
            if (positionals.size() < spec.minPositionals || positionals.size() > spec.maxPositionals) {
                throw new UsageException("'" + spec.name + "' takes " + spec.positional + ", got "
                        + positionals.size() + " arguments");
            }
        } else if (positionals.size() != expected) {
            throw new UsageException(expected == 0
                    ? "'" + spec.name + "' takes no argument, got '" + positionals.getFirst() + "'"
                    : positionals.isEmpty() ? "'" + spec.name + "' needs " + spec.positional
                    : "'" + spec.name + "' takes one " + spec.positional + ", got " + positionals.size());
        }
        parsed.positionals.addAll(positionals);
        return parsed;
    }
}
