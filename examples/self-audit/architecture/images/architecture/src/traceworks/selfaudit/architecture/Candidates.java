package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.Facts.Candidate;
import traceworks.selfaudit.architecture.Facts.Cycle;
import traceworks.selfaudit.architecture.Facts.IoFinding;
import traceworks.selfaudit.architecture.Facts.Limits;
import traceworks.selfaudit.architecture.Facts.Reason;
import traceworks.selfaudit.architecture.Facts.Result;
import traceworks.selfaudit.architecture.Facts.Site;
import traceworks.selfaudit.architecture.Facts.Violation;
import traceworks.selfaudit.architecture.Scan.Member;
import traceworks.selfaudit.architecture.Scan.Model;
import traceworks.selfaudit.architecture.Scan.Reference;
import traceworks.selfaudit.architecture.Scan.Type;
import traceworks.selfaudit.architecture.Sources.Unit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns the candidates into the lines of {@code candidates.jsonl}, one prompt's material each: the numbered code, the
 * measured findings that name the file with their lines, and a context the tool computes from the dependency graph, so
 * the model needs no tools. Every candidate gets its material, also one that is not judged in this run: the digest of
 * a file as an item of the audit is taken from it ({@link Items}).
 *
 * <p>The code is the whole file when it has at most {@code maxCodeLines} lines. A longer file is shown in part: its
 * header (up to the first member), then whole members in this order until the budget is spent: the members that hold a
 * line of a measured finding, then the most complex ones, then the rest from the top. An outline names every member with
 * its lines, so the model knows what it does not see, and the report counts the candidate as read in part.
 *
 * <p>The context names the types that use the file and the types it uses, most used first, each with its kind, its
 * first Javadoc sentence (or, without one, its first signatures) and the members the use goes through; and every
 * package of the sources with the first sentence of its {@code package-info.java}. Each of the two lists of types stops
 * at half of {@code maxContextChars} and names the types it left out; the list of packages comes on top.
 */
final class Candidates {

    private static final int MAX_USED_MEMBERS = 6;
    private static final int MAX_LEFT_OUT_NAMES = 30;

    /** The line the list of packages starts with, the last part of every context. */
    static final String PACKAGES_HEAD = "The packages of the sources:\n";

    /** One candidate as the prompt and the report see it. */
    record Item(String id, String file, List<String> types, List<Reason> reasons, boolean measured, int lines,
                int ncloc, String findings, String context, String code, String shown, String outline,
                List<Map<String, Object>> members) {

        /** The candidate under the number it has among the candidates a run judges. */
        Item withId(String number) {
            return new Item(number, file, types, reasons, measured, lines, ncloc, findings, context, code, shown,
                    outline, members);
        }

        /** The candidate as one JSON object, a line of {@code candidates.jsonl}, with the run's token and fence. */
        Map<String, Object> toJson(String token, String fence) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("file", file);
            json.put("types", types);
            json.put("reasons", reasons.stream().map(r -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", r.kind());
                entry.put("text", r.text());
                return (Object) entry;
            }).toList());
            json.put("reasons_text", reasons.stream().map(r -> "- " + r.text()).collect(Collectors.joining("\n")));
            json.put("measured", measured);
            json.put("lines", (long) lines);
            json.put("ncloc", (long) ncloc);
            json.put("findings", findings);
            json.put("context", context);
            json.put("shown", shown);
            json.put("shown_text", shown.equals("whole") ? "The whole file, lines 1 to " + lines + "."
                    : "Only part of the file (" + shown + "), since it is longer than the prompt holds; a line \"...| "
                    + "(lines a to b not shown)\" marks a gap. Every member of the file, with its lines:\n" + outline);
            json.put("members", members);
            json.put("code", code);
            json.put("token", token);
            json.put("fence", fence);
            return json;
        }
    }

    private Candidates() {
    }

    /** The items of every candidate of {@code facts}, in their order, numbered {@code C01}, {@code C02}, ... */
    static List<Item> of(Model model, RuleFile rules, Result facts, Limits limits) {
        Map<String, Unit> units = model.units().stream().collect(Collectors.toMap(Unit::path, Function.identity()));
        Map<String, List<Member>> membersByFile = new TreeMap<>();
        for (Member m : model.members()) membersByFile.computeIfAbsent(m.file(), k -> new ArrayList<>()).add(m);
        Map<String, List<Type>> typesByFile = new TreeMap<>();
        for (Type t : model.types().values()) typesByFile.computeIfAbsent(t.file(), k -> new ArrayList<>()).add(t);
        String packages = packages(rules, facts, model);

        List<Item> items = new ArrayList<>();
        for (Candidate candidate : facts.candidates()) {
            String file = candidate.file();
            Unit unit = units.get(file);
            List<Member> members = membersByFile.getOrDefault(file, List.of());
            List<Type> fileTypes = typesByFile.getOrDefault(file, List.of());
            Set<String> names = fileTypes.stream().map(Type::name).collect(Collectors.toCollection(TreeSet::new));
            Map<Integer, String> findingLines = new TreeMap<>();
            String findings = findings(rules, facts, file, names, findingLines);
            Shown shown = code(unit, members, findingLines.keySet(), limits.maxCodeLines());
            String context = context(rules, model, fileTypes, names, limits.maxContextChars()) + "\n\n" + packages;
            List<Map<String, Object>> memberList = new ArrayList<>();
            for (Member m : members) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("label", m.label());
                entry.put("name", m.name());
                entry.put("kind", m.kind());
                entry.put("start", (long) m.start());
                entry.put("end", (long) m.end());
                entry.put("complexity", (long) m.complexity());
                memberList.add(entry);
            }
            items.add(new Item(String.format("C%02d", items.size() + 1), file,
                    fileTypes.stream().map(t -> rules.relative(t.name())).toList(), candidate.reasons(),
                    candidate.measured(), unit.lines().size(), facts.ncloc().get(file), findings, context, shown.code,
                    shown.shown, outline(members), List.copyOf(memberList)));
        }
        return List.copyOf(items);
    }

    /** The measured findings that name {@code file}, one block each, with the lines; notes those lines. */
    private static String findings(RuleFile rules, Result facts, String file, Set<String> names,
                                   Map<Integer, String> lines) {
        List<String> blocks = new ArrayList<>();
        for (Violation v : facts.violations()) {
            for (Site site : v.sites()) {
                if (!site.file().equals(file)) continue;
                blocks.add("Layer violation (rule " + v.rule() + "): package " + v.from() + " depends on " + v.to()
                        + ", which the rule forbids. This file's references to " + v.to() + ":\n"
                        + siteLines(site.lines(), lines));
            }
        }
        for (IoFinding f : facts.io()) {
            if (f.allowed() != null || !f.file().equals(file)) continue;
            blocks.add("Direct I/O (rule " + f.rule() + ") that the rule file does not allow for " + f.type() + ":\n"
                    + siteLines(f.uses(), lines));
        }
        for (Cycle cycle : facts.cycles()) {
            Facts.Edge weakest = cycle.weakest();
            for (String type : weakest.types()) {
                if (!names.contains(facts.qualifiedOf().get(type))) continue;
                Map<Integer, List<String>> refs = facts.edgeLines(type, weakest.to());
                String others = cycle.edges().stream().filter(e -> e != weakest)
                        .map(e -> e.from() + " -> " + e.to() + " (" + e.types().size() + " types)")
                        .collect(Collectors.joining(", "));
                blocks.add("Package cycle " + cycle.name() + ": this file makes the edge " + weakest.from() + " -> "
                        + weakest.to() + ", which " + weakest.types().size() + (weakest.types().size() == 1 ? " type makes" : " types make")
                        + ", the fewest of the cycle"
                        + (others.isEmpty() ? "" : "; its other edges: " + others) + ". Its references to "
                        + weakest.to() + ":\n" + siteLines(refs, lines));
            }
        }
        return blocks.isEmpty() ? "none" : String.join("\n\n", blocks);
    }

    private static String siteLines(Map<Integer, List<String>> uses, Map<Integer, String> lines) {
        StringBuilder out = new StringBuilder();
        uses.forEach((line, what) -> {
            lines.put(line, String.join(", ", what));
            out.append("- line ").append(line).append(": ").append(String.join(", ", what)).append('\n');
        });
        return out.toString().stripTrailing();
    }

    /** The code a prompt shows, and which part. */
    private record Shown(String code, String shown) {
    }

    private static Shown code(Unit unit, List<Member> members, Set<Integer> findingLines, int maxLines) {
        List<String> lines = unit.lines();
        if (lines.size() <= maxLines) return new Shown(numbered(lines, List.of(new int[] {1, lines.size()})), "whole");
        List<Member> outer = members.stream().filter(m -> !m.kind().equals("type")).toList();
        int header = outer.stream().mapToInt(Member::start).min().orElse(lines.size()) - 1;
        List<int[]> ranges = new ArrayList<>();
        int budget = maxLines;
        if (header > 0) {
            int take = Math.min(header, budget);
            ranges.add(new int[] {1, take});
            budget -= take;
        }
        List<Member> order = new ArrayList<>();
        outer.stream().filter(m -> findingLines.stream().anyMatch(l -> l >= m.start() && l <= m.end()))
                .forEach(order::add);
        outer.stream().filter(m -> m.complexity() > 0).sorted(Comparator.comparingInt(Member::complexity).reversed())
                .filter(m -> !order.contains(m)).forEach(order::add);
        outer.stream().filter(m -> !order.contains(m)).forEach(order::add);
        for (Member m : order) {
            int size = m.end() - m.start() + 1;
            if (size > budget) continue;
            ranges.add(new int[] {m.start(), m.end()});
            budget -= size;
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        String shown = "part: lines " + ranges.stream().map(r -> r[0] + "-" + r[1]).collect(Collectors.joining(", "))
                + " of " + lines.size();
        return new Shown(numbered(lines, ranges), shown);
    }

    private static String numbered(List<String> lines, List<int[]> ranges) {
        StringBuilder code = new StringBuilder();
        int last = 0;
        for (int[] range : ranges) {
            if (last > 0 && range[0] > last + 1) code.append("...| (lines ").append(last + 1).append(" to ")
                    .append(range[0] - 1).append(" not shown)\n");
            for (int line = range[0]; line <= range[1] && line <= lines.size(); line++) {
                code.append(line).append("| ").append(lines.get(line - 1)).append('\n');
            }
            last = range[1];
        }
        if (last < lines.size() && last > 0 && !ranges.isEmpty() && ranges.getLast()[1] < lines.size()) {
            code.append("...| (lines ").append(last + 1).append(" to ").append(lines.size()).append(" not shown)\n");
        }
        return code.toString().stripTrailing();
    }

    /** Every member with its lines and complexity, one per line. */
    private static String outline(List<Member> members) {
        return members.stream().map(m -> "- " + m.label() + " (" + m.kind() + ", lines " + m.start() + "-" + m.end()
                + (m.complexity() > 0 ? ", complexity " + m.complexity() : "") + ")").collect(Collectors.joining("\n"));
    }

    /** Who uses the file's types and what they use, most used first, bounded by {@code maxChars}. */
    private static String context(RuleFile rules, Model model, List<Type> fileTypes, Set<String> names, int maxChars) {
        Map<String, Set<String>> usedBy = new TreeMap<>();
        Map<String, Integer> usedByCount = new TreeMap<>();
        Map<String, Set<String>> uses = new TreeMap<>();
        Map<String, Integer> usesCount = new TreeMap<>();
        for (Reference ref : model.references()) {
            if (names.contains(ref.to()) && !names.contains(ref.from())) {
                Set<String> members = usedBy.computeIfAbsent(ref.from(), k -> new TreeSet<>());
                if (ref.member() != null) members.add(ref.member());
                usedByCount.merge(ref.from(), 1, Integer::sum);
            }
            if (names.contains(ref.from()) && !names.contains(ref.to())) {
                Set<String> members = uses.computeIfAbsent(ref.to(), k -> new TreeSet<>());
                if (ref.member() != null) members.add(ref.member());
                usesCount.merge(ref.to(), 1, Integer::sum);
            }
        }
        StringBuilder out = new StringBuilder();
        for (Type t : fileTypes) {
            out.append("This file: ").append(t.kind()).append(' ').append(rules.relative(t.name()));
            if (!t.summary().isEmpty()) out.append(": ").append(t.summary());
            out.append('\n');
        }
        int budget = maxChars / 2;
        out.append('\n');
        section(out, "Used by", usedBy, usedByCount, model, rules, budget, "uses");
        out.append('\n');
        section(out, "Uses", uses, usesCount, model, rules, budget, "through");
        return out.toString().stripTrailing();
    }

    private static void section(StringBuilder out, String title, Map<String, Set<String>> related,
                                Map<String, Integer> counts, Model model, RuleFile rules, int budget, String verb) {
        out.append(title).append(" (").append(related.size()).append(related.size() == 1 ? " type" : " types")
                .append(" of the sources):\n");
        if (related.isEmpty()) {
            out.append("- none\n");
            return;
        }
        List<String> order = related.keySet().stream()
                .sorted(Comparator.comparingInt((String n) -> -counts.get(n)).thenComparing(n -> n)).toList();
        int used = 0;
        List<String> left = new ArrayList<>();
        for (String name : order) {
            Type type = model.types().get(name);
            StringBuilder line = new StringBuilder("- ").append(rules.relative(name)).append(" (").append(type.kind())
                    .append("): ");
            line.append(type.summary().isEmpty() ? "no Javadoc; " + String.join(", ", type.api().stream().limit(4).toList())
                    : type.summary());
            List<String> members = List.copyOf(related.get(name));
            if (!members.isEmpty()) {
                line.append(" [").append(verb).append(' ')
                        .append(String.join(", ", members.subList(0, Math.min(MAX_USED_MEMBERS, members.size()))));
                if (members.size() > MAX_USED_MEMBERS) line.append(" and ").append(members.size() - MAX_USED_MEMBERS)
                        .append(" more");
                line.append(']');
            }
            if (used + line.length() > budget && used > 0) {
                left.add(rules.relative(name));
                continue;
            }
            used += line.length() + 1;
            out.append(line).append('\n');
        }
        if (!left.isEmpty()) {
            out.append("- and ").append(left.size()).append(" more, not described: ")
                    .append(String.join(", ", left.subList(0, Math.min(MAX_LEFT_OUT_NAMES, left.size()))))
                    .append(left.size() > MAX_LEFT_OUT_NAMES ? ", ..." : "").append('\n');
        }
    }

    /** Every package of the sources, with its type count and the first sentence of its package-info. */
    private static String packages(RuleFile rules, Result facts, Model model) {
        Map<String, String> summaries = new TreeMap<>();
        model.packageSummaries().forEach((pkg, summary) -> summaries.put(rules.relative(pkg), summary));
        StringBuilder out = new StringBuilder(PACKAGES_HEAD);
        for (String pkg : facts.packages()) {
            String summary = summaries.getOrDefault(pkg, "");
            int types = facts.packageTypes().get(pkg);
            out.append("- ").append(pkg).append(" (").append(types).append(types == 1 ? " type)" : " types)");
            if (!summary.isEmpty()) out.append(": ").append(summary);
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }
}
