package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.RuleFile.Allow;
import traceworks.selfaudit.architecture.RuleFile.Io;
import traceworks.selfaudit.architecture.RuleFile.Layer;
import traceworks.selfaudit.architecture.Scan.IoUse;
import traceworks.selfaudit.architecture.Scan.Member;
import traceworks.selfaudit.architecture.Scan.Model;
import traceworks.selfaudit.architecture.Scan.Reference;
import traceworks.selfaudit.architecture.Scan.Type;
import traceworks.selfaudit.architecture.Sources.Unit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The measured facts of the sources: the package graph and its cycles, the layer violations, the direct I/O, the sizes
 * and complexities, and the candidates an AI judges, each with its code and a context computed from the graph.
 *
 * <p>All names are shown below the rule file's prefix. A <em>measured finding</em> is a layer violation, a use of I/O
 * that its rule does not allow, or a package cycle. The candidates are, in this order: every file a measured finding
 * names (for a cycle, the files of its edge with the fewest classes, the cheapest one to cut), the files of at least
 * {@code largeNcloc} non-comment lines (largest first), and the files with a member of at least {@code complexMember}
 * cognitive complexity (most complex first). Every candidate is an item of the audit; which of them a run judges, at
 * most {@code maxCandidates}, is chosen where the register's items are known ({@link Main}).
 */
final class Facts {

    /** The limits of a measurement. */
    record Limits(int largeNcloc, int complexMember, int maxCandidates, int maxCodeLines, int maxContextChars) {

        static final Limits DEFAULT = new Limits(300, 30, 24, 800, 12_000);
    }

    /** A dependency between two packages, through the top-level types of {@code from} that make it. */
    record Edge(String from, String to, List<String> types) {
    }

    /** A strongly connected set of packages, with its edges and the one with the fewest types. */
    record Cycle(List<String> packages, List<Edge> edges, Edge weakest) {

        String name() {
            return String.join("+", packages);
        }
    }

    /** Where a type makes a dependency or uses I/O: the type, its file and the lines with what each line uses. */
    record Site(String type, String file, Map<Integer, List<String>> lines) {
    }

    /** A package dependency that a layer rule forbids. */
    record Violation(String rule, String from, String to, List<Site> sites) {
    }

    /** The I/O of one type under one rule; {@code allowed} is the rule's reason when the rule allows it, else null. */
    record IoFinding(String rule, String type, String file, String allowed, Map<Integer, List<String>> uses) {
    }

    /** A reason why a file is a candidate. */
    record Reason(String kind, String text) {
    }

    /** A file the AI judges, with why. */
    record Candidate(String file, List<Reason> reasons, boolean measured) {
    }

    /** All the facts. */
    record Result(List<String> packages, Map<String, Integer> packageTypes, List<Edge> edges, List<Cycle> cycles,
                  List<Violation> violations, List<IoFinding> io, List<String> staleAllows,
                  List<String> unusedPatterns, Map<String, Integer> ncloc, Map<String, Integer> lines,
                  List<Member> members, Map<String, Member> mostComplex, List<Candidate> candidates,
                  List<String> unresolvedSourceNames,
                  Map<String, Map<Integer, List<String>>> edgeSites, Map<String, String> qualifiedOf) {

        /** The lines of {@code type}'s references into package {@code to}, with what each line uses. */
        Map<Integer, List<String>> edgeLines(String type, String to) {
            return edgeSites.getOrDefault(type + "\0" + to, Map.of());
        }
    }

    private Facts() {
    }

    /** The facts of {@code model} under {@code rules} and {@code limits}. */
    static Result of(Model model, RuleFile rules, Limits limits) {
        Map<String, Type> types = model.types();
        Map<String, String> qualifiedOf = new TreeMap<>();
        for (String name : types.keySet()) qualifiedOf.put(rules.relative(name), name);
        Map<String, Set<String>> packageTypes = new TreeMap<>();
        for (Type type : types.values()) {
            packageTypes.computeIfAbsent(rules.relative(type.pkg()), k -> new TreeSet<>()).add(rules.relative(type.name()));
        }
        List<String> packages = List.copyOf(packageTypes.keySet());

        // Package edges, with the types that make them and their lines
        Map<String, Map<String, Map<String, Map<Integer, List<String>>>>> graph = new TreeMap<>();
        for (Reference ref : model.references()) {
            String from = rules.relative(types.get(ref.from()).pkg());
            String to = rules.relative(types.get(ref.to()).pkg());
            if (from.equals(to)) continue;
            graph.computeIfAbsent(from, k -> new TreeMap<>()).computeIfAbsent(to, k -> new TreeMap<>())
                    .computeIfAbsent(rules.relative(ref.from()), k -> new TreeMap<>())
                    .computeIfAbsent(ref.line(), k -> new ArrayList<>()).add(usedText(rules, ref));
        }
        List<Edge> edges = new ArrayList<>();
        Map<String, Map<Integer, List<String>>> edgeSites = new TreeMap<>();
        graph.forEach((from, targets) -> targets.forEach((to, sites) -> {
            edges.add(new Edge(from, to, List.copyOf(sites.keySet())));
            sites.forEach((type, lines) -> edgeSites.put(type + "\0" + to, dedupe(lines)));
        }));

        List<Cycle> cycles = cycles(packages, edges);

        List<Violation> violations = new ArrayList<>();
        Set<String> usedPatterns = new TreeSet<>();
        for (Layer layer : rules.layers()) {
            for (String pattern : concat(layer.from(), layer.not())) {
                if (packages.stream().anyMatch(p -> RuleFile.matches(pattern, p))) usedPatterns.add(layer.id() + " " + pattern);
            }
            for (Edge edge : edges) {
                if (RuleFile.matchesAny(layer.from(), edge.from()) && RuleFile.matchesAny(layer.not(), edge.to())) {
                    List<Site> sites = new ArrayList<>();
                    graph.get(edge.from()).get(edge.to()).forEach((type, lines) ->
                            sites.add(new Site(type, types.get(qualifiedOf.get(type)).file(), dedupe(lines))));
                    violations.add(new Violation(layer.id(), edge.from(), edge.to(), List.copyOf(sites)));
                }
            }
        }
        List<String> unusedPatterns = new ArrayList<>();
        for (Layer layer : rules.layers()) {
            for (String pattern : concat(layer.from(), layer.not())) {
                if (!usedPatterns.contains(layer.id() + " " + pattern)) unusedPatterns.add(layer.id() + " " + pattern);
            }
        }
        for (Io rule : rules.ios()) {
            for (String pattern : rule.in()) {
                if (packages.stream().noneMatch(p -> RuleFile.matches(pattern, p))) unusedPatterns.add(rule.id() + " " + pattern);
            }
        }

        // I/O per rule and type
        Map<String, Map<String, Map<Integer, List<String>>>> ioUses = new TreeMap<>();
        for (IoUse use : model.io()) {
            ioUses.computeIfAbsent(use.rule() + "\0" + rules.relative(use.type()), k -> new TreeMap<>())
                    .computeIfAbsent(use.file(), k -> new TreeMap<>())
                    .computeIfAbsent(use.line(), k -> new ArrayList<>()).add(use.what());
        }
        List<IoFinding> io = new ArrayList<>();
        Set<String> usedAllows = new TreeSet<>();
        for (Io rule : rules.ios()) {
            ioUses.forEach((key, byFile) -> {
                String[] parts = key.split("\0", 2);
                if (!parts[0].equals(rule.id())) return;
                String type = parts[1];
                String allowed = allowance(rule, type, usedAllows);
                byFile.forEach((file, uses) -> io.add(new IoFinding(rule.id(), type, file, allowed, dedupe(uses))));
            });
        }
        List<String> staleAllows = new ArrayList<>();
        for (Io rule : rules.ios()) {
            for (Allow allow : rule.allowed()) {
                if (!usedAllows.contains(rule.id() + " " + allow.type())) staleAllows.add(rule.id() + " " + allow.type());
            }
        }

        // Sizes and complexity
        Map<String, Integer> ncloc = new TreeMap<>();
        Map<String, Integer> lines = new TreeMap<>();
        for (Unit unit : model.units()) {
            ncloc.put(unit.path(), Ncloc.of(unit.content()));
            lines.put(unit.path(), unit.lines().size());
        }
        Map<String, Member> mostComplex = new TreeMap<>();
        for (Member member : model.members()) {
            mostComplex.merge(member.file(), member, (a, b) -> b.complexity() > a.complexity() ? b : a);
        }

        // Candidates: findings first, then size, then complexity
        Map<String, List<Reason>> reasons = new LinkedHashMap<>();
        Set<String> measured = new TreeSet<>();
        for (Violation v : violations) {
            for (Site site : v.sites()) {
                add(reasons, site.file(), new Reason("layer", "layer " + v.rule() + ": " + v.from() + " -> " + v.to()));
                measured.add(site.file());
            }
        }
        for (IoFinding f : io) {
            if (f.allowed() != null) continue;
            add(reasons, f.file(), new Reason("io", "io " + f.rule() + ": " + String.join(", ", whats(f.uses()))));
            measured.add(f.file());
        }
        for (Cycle cycle : cycles) {
            for (String type : cycle.weakest().types()) {
                String file = types.get(qualifiedOf.get(type)).file();
                add(reasons, file, new Reason("cycle", "cycle " + cycle.name() + ": makes the edge "
                        + cycle.weakest().from() + " -> " + cycle.weakest().to()
                        + ", the edge of the cycle with the fewest types"));
                measured.add(file);
            }
        }
        ncloc.entrySet().stream().filter(e -> e.getValue() >= limits.largeNcloc())
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> add(reasons, e.getKey(), new Reason("size", e.getValue() + " non-comment lines")));
        mostComplex.values().stream().filter(m -> m.complexity() >= limits.complexMember())
                .sorted(Comparator.comparingInt(Member::complexity).reversed().thenComparing(Member::file))
                .forEach(m -> add(reasons, m.file(), new Reason("complexity",
                        m.label() + " has cognitive complexity " + m.complexity())));
        List<Candidate> all = new ArrayList<>();
        reasons.forEach((file, why) -> all.add(new Candidate(file, List.copyOf(why), measured.contains(file))));

        Set<String> simpleNames = types.keySet().stream().map(n -> n.substring(n.lastIndexOf('.') + 1))
                .collect(Collectors.toSet());
        List<String> unresolvedSourceNames = model.unresolvedNames().stream().filter(simpleNames::contains).toList();

        Map<String, Integer> typeCounts = new TreeMap<>();
        packageTypes.forEach((p, t) -> typeCounts.put(p, t.size()));
        return new Result(packages, typeCounts, List.copyOf(edges), cycles, List.copyOf(violations), List.copyOf(io),
                List.copyOf(staleAllows), List.copyOf(unusedPatterns), ncloc, lines, model.members(), mostComplex,
                List.copyOf(all), unresolvedSourceNames, edgeSites, qualifiedOf);
    }

    /** The reason of the first {@code allow} of {@code rule} that matches {@code type}, or null; notes it as used. */
    private static String allowance(Io rule, String type, Set<String> usedAllows) {
        for (Allow allow : rule.allowed()) {
            if (RuleFile.matches(allow.type(), type)) {
                usedAllows.add(rule.id() + " " + allow.type());
                return allow.why();
            }
        }
        return null;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static void add(Map<String, List<Reason>> reasons, String file, Reason reason) {
        List<Reason> list = reasons.computeIfAbsent(file, k -> new ArrayList<>());
        if (!list.contains(reason)) list.add(reason);
    }

    /**
     * The distinct things of each line, in their first order. A thing is a type, or a type, a blank and a member of it:
     * the members of one type join it, as {@code core.port.Clock (now(), new Clock())}.
     */
    private static Map<Integer, List<String>> dedupe(Map<Integer, List<String>> lines) {
        Map<Integer, List<String>> out = new TreeMap<>();
        lines.forEach((line, what) -> {
            Map<String, Set<String>> byType = new LinkedHashMap<>();
            for (String w : what) {
                int blank = w.indexOf(' ');
                Set<String> members = byType.computeIfAbsent(blank < 0 ? w : w.substring(0, blank),
                        k -> new java.util.LinkedHashSet<>());
                if (blank >= 0) members.add(w.substring(blank + 1));
            }
            List<String> joined = new ArrayList<>();
            byType.forEach((type, members) -> joined.add(members.isEmpty() ? type
                    : type + " (" + String.join(", ", members) + ")"));
            out.put(line, List.copyOf(joined));
        });
        return out;
    }

    static List<String> whats(Map<Integer, List<String>> uses) {
        return uses.values().stream().flatMap(List::stream).distinct().sorted().toList();
    }

    /** What a reference uses, for the lines of a site: the type and the member. */
    private static String usedText(RuleFile rules, Reference ref) {
        String type = rules.relative(ref.to());
        return ref.member() == null ? type : type + " " + ref.member();
    }

    /**
     * The strongly connected sets of more than one package (Tarjan's algorithm, without recursion), each with its edges
     * and the edge with the fewest types (then the first by name), sorted by their first package.
     */
    static List<Cycle> cycles(List<String> packages, List<Edge> edges) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Edge e : edges) out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        List<List<String>> components = new ArrayList<>();
        int[] counter = {0};
        Set<String> nodes = new TreeSet<>(packages);
        edges.forEach(e -> {
            nodes.add(e.from());
            nodes.add(e.to());
        });
        for (String start : nodes) {
            if (index.containsKey(start)) continue;
            // An explicit stack of (node, next successor index)
            java.util.Deque<Object[]> work = new java.util.ArrayDeque<>();
            work.push(new Object[] {start, 0});
            index.put(start, counter[0]);
            low.put(start, counter[0]++);
            stack.push(start);
            onStack.add(start);
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String v = (String) frame[0];
                List<String> next = out.getOrDefault(v, List.of());
                int i = (int) frame[1];
                if (i < next.size()) {
                    frame[1] = i + 1;
                    String w = next.get(i);
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]++);
                        stack.push(w);
                        onStack.add(w);
                        work.push(new Object[] {w, 0});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    String parent = (String) work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    List<String> component = new ArrayList<>();
                    String w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        component.add(w);
                    } while (!w.equals(v));
                    if (component.size() > 1) components.add(component.stream().sorted().toList());
                }
            }
        }
        components.sort(Comparator.comparing(c -> c.getFirst()));
        List<Cycle> cycles = new ArrayList<>();
        for (List<String> component : components) {
            List<Edge> inside = edges.stream()
                    .filter(e -> component.contains(e.from()) && component.contains(e.to())).toList();
            Edge weakest = inside.stream().min(Comparator.comparingInt((Edge e) -> e.types().size())
                    .thenComparing(Edge::from).thenComparing(Edge::to)).orElseThrow();
            cycles.add(new Cycle(component, inside, weakest));
        }
        return List.copyOf(cycles);
    }
}
