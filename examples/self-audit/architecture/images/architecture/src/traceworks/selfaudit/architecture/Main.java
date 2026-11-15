package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.Candidates.Item;
import traceworks.selfaudit.architecture.Facts.Candidate;
import traceworks.selfaudit.architecture.Facts.Cycle;
import traceworks.selfaudit.architecture.Facts.Edge;
import traceworks.selfaudit.architecture.Facts.IoFinding;
import traceworks.selfaudit.architecture.Facts.Limits;
import traceworks.selfaudit.architecture.Facts.Result;
import traceworks.selfaudit.architecture.Facts.Site;
import traceworks.selfaudit.architecture.Facts.Violation;
import traceworks.selfaudit.architecture.Report.Outcome;
import traceworks.selfaudit.architecture.Report.Verdict;
import traceworks.selfaudit.architecture.Scan.Member;
import traceworks.selfaudit.architecture.Scan.Model;
import traceworks.selfaudit.architecture.Sources.Discovery;
import traceworks.selfaudit.architecture.Sources.Loaded;
import traceworks.selfaudit.architecture.Sources.NotRead;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The architecture tool's command line.
 *
 * <pre>
 * facts  --rules FILE --root DIR [--source-root DIR]... --facts FACTS --candidates CANDIDATES
 *        [--large-ncloc 300] [--complex-member 30] [--max-candidates 24] [--max-code-lines 800] [--known FILE]
 *        [--max-context-chars 12000] [--items FILE] [--verdict-version 1]
 * report --facts FACTS --candidates CANDIDATES --verdicts FILE|- --out REPORT   (and findings.jsonl next to REPORT)
 * </pre>
 *
 * {@code facts} measures the sources and writes the facts (one JSON object) and the candidates (one JSON object per
 * line, with a token and a code fence of the run), and prints a fact sheet. Without {@code --source-root} it reads every
 * {@code src/main/java} up to six levels below the root, except below {@code target}, {@code build},
 * {@code node_modules} and hidden directories. Every candidate file is an item of the audit; with {@code --items}, the
 * text of {@code {{ audit_items }}}, a file the register calls clean or decided with the digest it has now is not
 * judged again. {@code --verdict-version} is part of every digest: who changes the question raises it, and every file
 * is judged again. {@code report} prints the gate first, then checks the verdicts and writes
 * the report, and ends with the audit result envelope that a register reads (docs/registers.md). Every name from the
 * workspace is escaped on standard output, and no text of a model reaches it, so neither can add a line of its own.
 * Exit codes: 0 done, 1 the input does not hold, 2 a usage error.
 */
public final class Main {

    private static final String USAGE = """
            usage: architecture facts --rules FILE --root DIR [--source-root DIR]... --facts FACTS --candidates FILE
                                      [--large-ncloc N] [--complex-member N] [--max-candidates N] [--known FILE]
                                      [--max-code-lines N] [--max-context-chars N] [--items FILE]
                                      [--verdict-version N]
                   architecture report --facts FACTS --candidates FILE --verdicts FILE|- --out REPORT""";

    private static final int MAX_LISTED = 25;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, new PrintStream(System.out, true, StandardCharsets.UTF_8),
                new PrintStream(System.err, true, StandardCharsets.UTF_8)));
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Map<String, List<String>> options;
        try {
            options = options(args);
        } catch (IllegalArgumentException e) {
            err.println("architecture: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
        try {
            return switch (args[0]) {
                case "facts" -> facts(options, out);
                case "report" -> report(options, in, out);
                default -> throw new IllegalStateException(args[0]);
            };
        } catch (Usage e) {
            err.println("architecture: " + e.getMessage());
            err.println(USAGE);
            return 2;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("architecture: " + safe(String.valueOf(e.getMessage())));
            return 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("architecture: " + safe(String.valueOf(e)));
            return 1;
        } catch (StackOverflowError e) {
            err.println("architecture: an expression nests too deeply to be read");
            return 1;
        }
    }

    private static int facts(Map<String, List<String>> options, PrintStream out) throws IOException {
        Path rulesFile = Path.of(required(options, "rules"));
        Path root = Path.of(required(options, "root")).toAbsolutePath().normalize();
        Path factsFile = Path.of(required(options, "facts"));
        Path candidatesFile = Path.of(required(options, "candidates"));
        Limits limits = new Limits(number(options, "large-ncloc", Limits.DEFAULT.largeNcloc()),
                number(options, "complex-member", Limits.DEFAULT.complexMember()),
                number(options, "max-candidates", Limits.DEFAULT.maxCandidates()),
                number(options, "max-code-lines", Limits.DEFAULT.maxCodeLines()),
                number(options, "max-context-chars", Limits.DEFAULT.maxContextChars()));
        RuleFile rules = RuleFile.parse(Files.readString(rulesFile));
        if (!Files.isDirectory(root)) throw new IllegalArgumentException(root + " is no directory");

        List<Path> sourceRoots = new ArrayList<>();
        for (String given : options.getOrDefault("source-root", List.of())) {
            Path sourceRoot = root.resolve(given).normalize();
            if (!Files.isDirectory(sourceRoot)) throw new IllegalArgumentException(given + " is no directory");
            sourceRoots.add(sourceRoot);
        }
        List<NotRead> notRead = new ArrayList<>();
        if (sourceRoots.isEmpty()) {
            Discovery discovery = Sources.discover(root);
            sourceRoots.addAll(discovery.roots());
            for (Path link : discovery.linkedDirectories()) {
                notRead.add(new NotRead(Sources.relative(root, link),
                        "a symbolic link to a directory, not searched for source roots"));
            }
        }
        if (sourceRoots.isEmpty()) throw new IllegalArgumentException("no src/main/java below " + root);

        Loaded loaded = Sources.load(root, sourceRoots);
        notRead.addAll(loaded.notRead());
        notRead.sort(Comparator.comparing(NotRead::file));
        Model model = Scan.of(loaded, rules);
        Result facts = Facts.of(model, rules, limits);
        List<Item> every = Candidates.of(model, rules, facts, limits);

        SecureRandom random = new SecureRandom();
        String token = HexFormat.of().formatHex(bytes(random));
        String fence = HexFormat.of().formatHex(bytes(random));
        // What the register knows (the text of {{ audit_findings }}): each file gets its open and decided verdicts
        String knownText = options.containsKey("known")
                ? Files.readString(Path.of(required(options, "known")), StandardCharsets.UTF_8) : "";
        List<Known.Row> known = Known.parse(knownText);
        // Every candidate file is an item of the audit. The register says which items are settled
        // ({{ audit_items }}): a file it calls clean or decided, with the digest it has now, is not judged again.
        // The places, at most --max-candidates, go first to the files that changed or were never judged, then to
        // the open ones
        Map<String, Items.Row> knownItems = options.containsKey("items")
                ? Items.parse(Files.readString(Path.of(required(options, "items")), StandardCharsets.UTF_8)) : Map.of();
        int verdictVersion = number(options, "verdict-version", 1);
        List<String> names = Items.names(every.stream().map(Item::file).toList());
        List<Map<String, Object>> itemList = new ArrayList<>();
        List<Integer> changed = new ArrayList<>();
        List<Integer> openUnchanged = new ArrayList<>();
        List<Map<String, Object>> lineOf = new ArrayList<>();
        Set<String> subjects = new java.util.HashSet<>();
        for (int i = 0; i < every.size(); i++) {
            Item candidate = every.get(i);
            Map<String, Object> line = new LinkedHashMap<>(candidate.toJson(token, fence));
            String digest = Items.digest(verdictVersion, line);
            line.put("item", names.get(i));
            line.put("digest", digest);
            Known.attach(line, candidate.file(), known, Report.JUDGED_RULES);
            lineOf.add(line);
            // A file that is not judged in this run is still a candidate: its verdict is not gone
            subjects.add(candidate.file());
            Items.Row row = knownItems.get(Known.shown(names.get(i)));
            // A verdict of a run before items existed names no item, so its file may read clean: the verdict decides
            boolean hasOpen = !((List<?>) line.get("known")).isEmpty();
            boolean same = row != null && row.digest().equals(digest);
            boolean settled = same && row.settled() && !hasOpen;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", names.get(i));
            entry.put("digest", digest);
            entry.put("chosen", false);
            entry.put("state", row == null ? "" : row.state());
            entry.put("settled", settled);
            // A file longer than the prompt holds gets a verdict on a part of it: the report says so in every run,
            // also in the ones that leave the file out as settled
            if (!candidate.shown().equals("whole")) entry.put("shown_in_part", true);
            // For the report, which says in what state the item is after the run: what the register holds of it
            entry.putAll(Known.counts(line));
            itemList.add(entry);
            if (settled) continue;
            (same && (row.state().equals("open") || hasOpen) ? openUnchanged : changed).add(i);
        }
        List<Integer> toJudge = new ArrayList<>(changed);
        toJudge.addAll(openUnchanged);
        List<Item> items = new ArrayList<>();
        List<Candidate> cut = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        for (int index : toJudge) {
            if (items.size() == limits.maxCandidates()) {
                cut.add(facts.candidates().get(index));
                continue;
            }
            // The number a candidate has is its place among the candidates of this run
            Item chosen = every.get(index).withId(String.format("C%02d", items.size() + 1));
            items.add(chosen);
            itemList.get(index).put("chosen", true);
            lineOf.get(index).put("id", chosen.id());
            lines.append(Json.write(lineOf.get(index))).append('\n');
        }
        Files.writeString(candidatesFile, lines, StandardCharsets.UTF_8);

        List<String> roots = sourceRoots.stream().map(r -> Sources.relative(root, r)).toList();
        Map<String, Object> json = json(facts, items, cut, rules, limits, roots, model, notRead);
        json.put("items", itemList);
        // Open verdicts of files that are no candidates any more: the report names them as gone
        json.put("knownGone", Known.orphans(known, Report.JUDGED_RULES, subjects));
        // Open verdicts that no file carries and that are not gone either: for a person, and for the gate
        json.put("knownUnowned", Known.unowned(known, Report.JUDGED_RULES, subjects));
        // How many findings {{ audit_findings }} left out: nothing is concluded from a row that is not there
        json.put("knownMissing", (long) Known.missing(knownText));
        // How many of them are open by the block's own count: with any, a file the register calls open stays open
        json.put("knownMissingOpen", (long) Known.missingOpen(knownText));
        Files.writeString(factsFile, Json.write(json) + "\n", StandardCharsets.UTF_8);

        long forbidden = facts.io().stream().filter(f -> f.allowed() == null).count();
        out.println("FACT arch_source_roots: " + safe(String.join(", ", roots)));
        out.println("FACT arch_java_files: " + model.units().size());
        out.println("FACT arch_not_read: " + notRead.size());
        out.println("FACT arch_packages: " + facts.packages().size());
        out.println("FACT arch_package_edges: " + facts.edges().size());
        out.println("FACT arch_cycles: " + facts.cycles().size());
        out.println("FACT arch_layer_violations: " + facts.violations().size());
        out.println("FACT arch_io_forbidden: " + forbidden);
        out.println("FACT arch_io_documented: " + (facts.io().size() - forbidden));
        out.println("FACT arch_ncloc_total: " + facts.ncloc().values().stream().mapToInt(Integer::intValue).sum());
        out.println("FACT arch_files_over_" + limits.largeNcloc() + ": "
                + facts.ncloc().values().stream().filter(n -> n >= limits.largeNcloc()).count());
        out.println("FACT arch_members_complexity_" + limits.complexMember() + ": "
                + facts.members().stream().filter(m -> m.complexity() >= limits.complexMember()).count());
        out.println("FACT arch_unresolved_source_names: " + safe(String.join(", ", facts.unresolvedSourceNames())));
        out.println("FACT arch_items: " + every.size());
        out.println("FACT arch_items_settled: " + (every.size() - items.size() - cut.size()));
        out.println("FACT arch_candidates: " + items.size());
        out.println("FACT arch_candidates_cut: " + cut.size());
        for (Cycle c : facts.cycles()) {
            out.println("CYCLE " + safe(c.name()) + " (weakest edge " + safe(c.weakest().from()) + " -> "
                    + safe(c.weakest().to()) + ", " + count(c.weakest().types().size(), "type") + ")");
        }
        for (Violation v : facts.violations()) {
            out.println("LAYER " + v.rule() + " " + safe(v.from()) + " -> " + safe(v.to()) + " ("
                    + count(v.sites().size(), "type") + ")");
        }
        for (IoFinding f : facts.io()) {
            out.println("IO " + (f.allowed() == null ? "forbidden " : "documented ") + f.rule() + " " + safe(f.type()));
        }
        for (String stale : facts.staleAllows()) out.println("NOTE an allow that matches no I/O: " + safe(stale));
        for (String unused : facts.unusedPatterns()) out.println("NOTE a pattern that matches no package: " + safe(unused));
        for (NotRead n : notRead) {
            out.println("NOT MEASURED: arch_file " + safe(n.file()) + " (" + safe(n.reason()) + ")");
        }
        for (Candidate c : cut) {
            out.println("NOT MEASURED: arch_cut " + safe(c.file()) + " (beyond --max-candidates "
                    + limits.maxCandidates() + ")");
        }
        for (Item item : items) {
            out.println("CANDIDATE " + item.id() + " " + safe(item.file()) + " "
                    + item.reasons().stream().map(Facts.Reason::kind).distinct()
                    .collect(Collectors.joining(", ", "[", "]")) + (item.shown().equals("whole") ? "" : " (in part)"));
        }
        return 0;
    }

    /**
     * The facts as one JSON object, which the report reads back.
     *
     * @param items the candidates this run judges
     * @param cut   the candidates that need a verdict and got no place under the cap
     */
    private static Map<String, Object> json(Result facts, List<Item> items, List<Candidate> cut, RuleFile rules,
                                            Limits limits, List<String> roots, Model model, List<NotRead> notRead) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sourceRoots", roots);
        json.put("javaFiles", (long) model.units().size());
        json.put("notRead", notRead.stream().map(n -> map("file", n.file(), "reason", n.reason())).toList());
        json.put("unresolvedNames", List.copyOf(model.unresolvedNames()));
        json.put("unresolvedSourceNames", facts.unresolvedSourceNames());
        json.put("prefix", rules.prefix());
        json.put("rules", rules.ids());
        Map<String, Object> limitsJson = new LinkedHashMap<>();
        limitsJson.put("largeNcloc", (long) limits.largeNcloc());
        limitsJson.put("complexMember", (long) limits.complexMember());
        limitsJson.put("maxCandidates", (long) limits.maxCandidates());
        limitsJson.put("maxCodeLines", (long) limits.maxCodeLines());
        limitsJson.put("maxContextChars", (long) limits.maxContextChars());
        json.put("limits", limitsJson);
        json.put("packages", facts.packages().stream()
                .map(p -> map("name", p, "types", (long) facts.packageTypes().get(p))).toList());
        json.put("edges", facts.edges().stream().map(Main::edge).toList());
        json.put("cycles", facts.cycles().stream().map(c -> map("packages", c.packages(),
                "edges", c.edges().stream().map(Main::edge).toList(), "weakest", edge(c.weakest()))).toList());
        json.put("layerViolations", facts.violations().stream().map(v -> map("rule", v.rule(), "from", v.from(),
                "to", v.to(), "sites", v.sites().stream().map(Main::site).toList())).toList());
        json.put("io", facts.io().stream().map(f -> {
            Map<String, Object> entry = map("rule", f.rule(), "type", f.type(), "file", f.file(),
                    "allowed", f.allowed() != null, "reason", f.allowed());
            entry.put("uses", lines(f.uses()));
            return (Object) entry;
        }).toList());
        json.put("staleAllows", facts.staleAllows());
        json.put("unusedPatterns", facts.unusedPatterns());
        json.put("files", facts.ncloc().keySet().stream().map(file -> {
            Member most = facts.mostComplex().get(file);
            return (Object) map("file", file, "lines", (long) facts.lines().get(file), "ncloc",
                    (long) facts.ncloc().get(file), "maxComplexity", most == null ? 0L : (long) most.complexity());
        }).toList());
        json.put("complexMembers", facts.members().stream()
                .sorted(Comparator.comparingInt(Member::complexity).reversed().thenComparing(Member::file)
                        .thenComparingInt(Member::start))
                .limit(MAX_LISTED).map(m -> map("file", m.file(), "member", m.label(), "start", (long) m.start(),
                        "end", (long) m.end(), "complexity", (long) m.complexity())).toList());
        json.put("candidates", items.stream().map(i -> map("id", i.id(), "file", i.file(), "reasons",
                i.reasons().stream().map(Facts.Reason::text).toList(), "measured", i.measured(), "shown",
                i.shown())).toList());
        json.put("cut", cut.stream().map(c -> map("file", c.file(), "reasons",
                c.reasons().stream().map(Facts.Reason::text).toList())).toList());
        return json;
    }

    private static Object edge(Edge e) {
        return map("from", e.from(), "to", e.to(), "types", e.types());
    }

    private static Object site(Site s) {
        Map<String, Object> entry = map("type", s.type(), "file", s.file());
        entry.put("lines", lines(s.lines()));
        return entry;
    }

    private static List<Object> lines(Map<Integer, List<String>> lines) {
        List<Object> out = new ArrayList<>();
        lines.forEach((line, what) -> out.add(map("line", (long) line, "what", what)));
        return out;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    private static int report(Map<String, List<String>> options, InputStream in, PrintStream out) throws IOException {
        Path factsFile = Path.of(required(options, "facts"));
        Path candidatesFile = Path.of(required(options, "candidates"));
        String verdictsFile = required(options, "verdicts");
        Path reportFile = Path.of(required(options, "out"));
        List<String> candidateLines = Files.readString(candidatesFile, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
        if (!(Json.parse(Files.readString(factsFile, StandardCharsets.UTF_8)) instanceof Map<?, ?> rawFacts)) {
            throw new IllegalArgumentException(factsFile + " holds no JSON object");
        }
        Map<String, Object> facts = new HashMap<>();
        rawFacts.forEach((k, v) -> facts.put(String.valueOf(k), v));
        String verdicts = verdictsFile.equals("-") ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(verdictsFile), StandardCharsets.UTF_8);

        Outcome outcome = Report.of(candidateLines, facts, verdicts);
        Files.write(reportFile, Report.utf8(outcome.markdown()));
        // The findings as data, next to the report: the scratch directory is recorded, so the run keeps both
        Files.write(reportFile.resolveSibling("findings.jsonl"), Report.utf8(outcome.findingsJsonl()));

        // The gate first: the engine takes the first gate line of a step's output
        out.println("gate_status: " + outcome.gate());
        out.println("gate_category: architecture");
        out.println("gate_description: " + safe(outcome.description()));
        out.println("FACT report_candidates: " + outcome.candidates());
        out.println("FACT report_judged: " + outcome.judged());
        out.println("FACT report_verdicts_verified: " + outcome.verified().size());
        out.println("FACT report_verdicts_unverified: " + outcome.unverified().size());
        out.println("FACT report_known_gone: " + outcome.gone().size());
        out.println("FACT report_known_not_answered: " + outcome.notAnswered().size());
        for (Verdict v : outcome.verified()) {
            // The verdict is one of three words: no model text
            out.println("VERIFIED " + v.verdict() + " " + v.id() + " " + safe(v.file()));
        }
        // Last: the envelope a register reads (one line of JSON, every control character escaped; no model text)
        out.println(outcome.envelope());
        return 0;
    }

    /** The subcommand's options, each with its values; {@code --source-root} may repeat. */
    private static Map<String, List<String>> options(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("a command is missing");
        Set<String> known = switch (args[0]) {
            case "facts" -> Set.of("rules", "root", "source-root", "facts", "candidates", "large-ncloc",
                    "complex-member", "max-candidates", "max-code-lines", "max-context-chars", "known", "items",
                    "verdict-version");
            case "report" -> Set.of("facts", "candidates", "verdicts", "out");
            default -> throw new IllegalArgumentException("unknown command '" + args[0] + "'");
        };
        Map<String, List<String>> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || !known.contains(arg.substring(2))) {
                throw new IllegalArgumentException("unknown option '" + arg + "'");
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException(arg + " needs a value");
            String name = arg.substring(2);
            List<String> values = options.computeIfAbsent(name, k -> new ArrayList<>());
            if (!values.isEmpty() && !name.equals("source-root")) {
                throw new IllegalArgumentException(arg + " is given twice");
            }
            values.add(args[++i]);
        }
        return options;
    }

    private static String required(Map<String, List<String>> options, String name) {
        List<String> values = options.get(name);
        if (values == null) throw new Usage("--" + name + " is missing");
        return values.getFirst();
    }

    private static int number(Map<String, List<String>> options, String name, int fallback) {
        List<String> values = options.get(name);
        if (values == null) return fallback;
        try {
            int n = Integer.parseInt(values.getFirst());
            if (n < 1) throw new Usage("--" + name + " must be at least 1");
            return n;
        } catch (NumberFormatException e) {
            throw new Usage("--" + name + " needs a number");
        }
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static byte[] bytes(SecureRandom random) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return bytes;
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

    /** A usage error: exit code 2 with the usage. */
    private static final class Usage extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Usage(String message) {
            super(message);
        }
    }
}
