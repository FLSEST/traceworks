package traceworks.selfaudit.build;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The build audit's command line.
 *
 * <pre>
 * offline    --rules FILE --root DIR --out FACTS
 * maven      --rules FILE --root DIR --out FACTS [--sbom-runtime FILE] [--sbom-test FILE]
 *            [--mvn mvn] [--work DIR] [--timeout 900]
 * registries --rules FILE --offline FACTS --out FACTS [--timeout 30]
 * report     --rules FILE --offline FACTS --maven FACTS --registries FACTS
 *            [--vulns-runtime SHEET] [--vulns-test SHEET] --out REPORT
 * </pre>
 *
 * {@code offline} reads the POMs and Dockerfiles, {@code maven} resolves the dependencies with Maven, and
 * {@code registries} looks up newer versions; each writes its facts as JSON and prints them as a fact sheet;
 * {@code maven} also writes the resolved dependencies as CycloneDX SBOMs for a scanner, when Maven resolved them. A
 * measurement that fails is a gap in the facts, not an error: these three exit 0 unless their usage or the rule file is
 * wrong. {@code report} prints the gate first, then writes the report. Every value from the workspace or a registry is
 * escaped on standard output. With {@code --vulns-runtime} and {@code --vulns-test} it also reads the SARIF fact
 * sheets of a scanner's tool steps. Exit codes: 0 done, 1 the rule file or an input does not hold, 2 a usage error.
 */
public final class Main {

    private static final String USAGE = """
            usage: facts offline --rules FILE --root DIR --out FACTS
                   facts maven --rules FILE --root DIR --out FACTS [--sbom-runtime FILE] [--sbom-test FILE]
                               [--mvn mvn] [--work DIR] [--timeout SECONDS]
                   facts registries --rules FILE --offline FACTS --out FACTS [--timeout SECONDS]
                   facts report --rules FILE --offline FACTS --maven FACTS --registries FACTS
                                [--vulns-runtime SHEET] [--vulns-test SHEET] --out REPORT""";

    private static final int MAX_SHOWN = 300;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8),
                new PrintStream(System.err, true, StandardCharsets.UTF_8)));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options;
        try {
            options = options(args);
        } catch (IllegalArgumentException e) {
            err.println("facts: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
        try {
            RuleFile rules = RuleFile.parse(Files.readString(Path.of(required(options, "rules")), StandardCharsets.UTF_8));
            return switch (args[0]) {
                case "offline" -> offline(rules, options, out);
                case "maven" -> maven(rules, options, out);
                case "registries" -> registries(rules, options, out);
                case "report" -> report(rules, options, out);
                default -> throw new IllegalStateException(args[0]);
            };
        } catch (Usage e) {
            err.println("facts: " + e.getMessage());
            err.println(USAGE);
            return 2;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("facts: " + safe(String.valueOf(e.getMessage())));
            return 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("facts: " + safe(String.valueOf(e)));
            return 1;
        }
    }

    private static int offline(RuleFile rules, Map<String, String> options, PrintStream out) throws IOException {
        Path root = root(options);
        Facts facts = new Facts("offline");
        Poms.check(Poms.read(root), rules, facts);
        Dockerfiles.check(root, rules, facts);
        return write(facts, Path.of(required(options, "out")), out);
    }

    private static int maven(RuleFile rules, Map<String, String> options, PrintStream out) throws IOException {
        Path root = root(options);
        Facts facts = new Facts("maven");
        Path sbomRuntime = options.containsKey("sbom-runtime") ? Path.of(options.get("sbom-runtime")) : null;
        Path sbomTest = options.containsKey("sbom-test") ? Path.of(options.get("sbom-test")) : null;
        deleteSboms(sbomRuntime, sbomTest);
        String tmp = System.getProperty("java.io.tmpdir");
        Path work = Path.of(options.getOrDefault("work", tmp == null ? "/tmp" : tmp));
        MavenTree.Options run = new MavenTree.Options(options.getOrDefault("mvn", "mvn"), work,
                Duration.ofSeconds(number(options, "timeout", 900)));
        try {
            MavenTree.run(root, rules, run, sbomRuntime, sbomTest, facts);
        } catch (IOException | RuntimeException e) {
            // No crash: what Maven could not resolve is a gap the gate sees
            facts.gap("maven_tree", e.getClass().getSimpleName() + ": " + shown(String.valueOf(e.getMessage())));
            deleteSboms(sbomRuntime, sbomTest);
        }
        return write(facts, Path.of(required(options, "out")), out);
    }

    /** No SBOM of an earlier try stays behind: a scan of a stale one would measure the wrong dependencies. */
    private static void deleteSboms(Path... sboms) throws IOException {
        for (Path sbom : sboms) {
            if (sbom != null) Files.deleteIfExists(sbom);
        }
    }

    private static int registries(RuleFile rules, Map<String, String> options, PrintStream out) throws IOException {
        Facts facts = new Facts("registries");
        Http.Client http = Http.jdk(Duration.ofSeconds(number(options, "timeout", 30)));
        Facts offline;
        try {
            offline = Facts.of(Json.parse(Files.readString(Path.of(required(options, "offline")), StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            facts.gap("registries", "the offline facts are not readable: " + shown(String.valueOf(e.getMessage())));
            return write(facts, Path.of(required(options, "out")), out);
        }
        try {
            new Registries(rules, http, facts).lookUp(offline);
        } catch (RuntimeException e) {
            facts.gap("registries", e.getClass().getSimpleName() + ": " + shown(String.valueOf(e.getMessage())));
        }
        return write(facts, Path.of(required(options, "out")), out);
    }

    private static int report(RuleFile rules, Map<String, String> options, PrintStream out) throws IOException {
        List<Report.Step> steps = new ArrayList<>();
        for (String name : List.of("offline", "maven", "registries")) steps.add(step(name, Path.of(required(options, name))));
        Map<String, String> scans = new java.util.LinkedHashMap<>();
        for (String scan : List.of("vulns-runtime", "vulns-test")) {
            if (options.containsKey(scan)) scans.put(scan.replace('-', '_'), sheet(Path.of(options.get(scan))));
        }
        Report.Outcome outcome = Report.of(rules, steps, scans);
        Files.writeString(Path.of(required(options, "out")), outcome.markdown(), StandardCharsets.UTF_8);

        // The gate first: the engine takes the first gate line of a step's output
        out.println("gate_status: " + outcome.gate());
        out.println("gate_category: build");
        out.println("gate_description: " + outcome.description());
        for (String level : List.of(Report.RED, Report.YELLOW, Report.INFO)) {
            out.println("FACT report_" + level.toLowerCase(java.util.Locale.ROOT) + ": "
                    + outcome.entries().stream().filter(e -> e.level().equals(level)).count());
        }
        out.println("FACT report_not_measured: " + outcome.gaps().size());
        for (Report.Entry e : outcome.entries()) {
            if (e.level().equals(Report.INFO)) continue;
            Facts.Item i = e.item();
            out.println(e.level() + " " + safe(i.check() + " " + (i.where().isEmpty() ? "" : i.where() + " ")
                    + i.subject()));
        }
        for (Facts.Gap g : outcome.gaps()) out.println("NOT MEASURED: " + safe(g.what()) + " (" + safe(g.reason()) + ")");
        return 0;
    }

    private static Report.Step step(String name, Path file) {
        if (!Files.isRegularFile(file)) return new Report.Step(name, null, "the step left no facts: it failed or did not run");
        try {
            return new Report.Step(name, Facts.of(Json.parse(Files.readString(file, StandardCharsets.UTF_8))), null);
        } catch (IOException | IllegalArgumentException e) {
            return new Report.Step(name, null, "its facts are not readable: " + shown(String.valueOf(e.getMessage())));
        }
    }

    private static String sheet(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    private static int write(Facts facts, Path file, PrintStream out) throws IOException {
        Files.writeString(file, Json.write(facts.toJson()) + "\n", StandardCharsets.UTF_8);
        out.println("## FACT SHEET " + facts.step);
        facts.print(out);
        return 0;
    }

    private static Path root(Map<String, String> options) {
        Path root = Path.of(required(options, "root")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException(root + " is no directory");
        return root;
    }

    private static Map<String, String> options(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("a command is missing");
        List<String> needed = switch (args[0]) {
            case "offline", "maven" -> List.of("rules", "root", "out");
            case "registries" -> List.of("rules", "offline", "out");
            case "report" -> List.of("rules", "offline", "maven", "registries", "out");
            default -> throw new IllegalArgumentException("unknown command '" + args[0] + "'");
        };
        Set<String> known = new java.util.HashSet<>(needed);
        known.addAll(switch (args[0]) {
            case "maven" -> List.of("sbom-runtime", "sbom-test", "mvn", "work", "timeout");
            case "registries" -> List.of("timeout");
            case "report" -> List.of("vulns-runtime", "vulns-test");
            default -> List.<String>of();
        });
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || !known.contains(arg.substring(2))) {
                throw new IllegalArgumentException("unknown option '" + arg + "'");
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException(arg + " needs a value");
            if (options.put(arg.substring(2), args[++i]) != null) throw new IllegalArgumentException(arg + " is given twice");
        }
        for (String name : needed) {
            if (!options.containsKey(name)) throw new IllegalArgumentException("--" + name + " is missing");
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) throw new Usage("--" + name + " is missing");
        return value;
    }

    private static int number(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        if (value == null) return fallback;
        try {
            int n = Integer.parseInt(value);
            if (n < 1) throw new Usage("--" + name + " must be at least 1");
            return n;
        } catch (NumberFormatException e) {
            throw new Usage("--" + name + " needs a number");
        }
    }

    /**
     * Text for one line of standard output: a backslash and everything beyond printable ASCII escaped, so a name with
     * a line break or a line separator cannot start a line of its own.
     */
    static String safe(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    /**
     * Text on one line: format characters (zero-width and direction ones) removed, every blank, control character and
     * line or paragraph separator a single space.
     */
    static String oneLine(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean blank = false;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (Character.getType(c) == Character.FORMAT) continue;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                blank = true;
                continue;
            }
            if (blank && !out.isEmpty()) out.append(' ');
            blank = false;
            out.appendCodePoint(c);
        }
        return out.toString();
    }

    /** At most {@value #MAX_SHOWN} characters of a text on one line. */
    static String shown(String text) {
        String line = oneLine(text);
        if (line.length() <= MAX_SHOWN) return line;
        int end = Character.isHighSurrogate(line.charAt(MAX_SHOWN - 1)) ? MAX_SHOWN - 1 : MAX_SHOWN;
        return line.substring(0, end) + "...";
    }

    /** A usage error: exit code 2 with the usage. */
    private static final class Usage extends IllegalArgumentException {
        Usage(String message) {
            super(message);
        }
    }
}
