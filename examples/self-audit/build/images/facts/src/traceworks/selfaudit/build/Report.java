package traceworks.selfaudit.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gate and the report of the build audit, from the facts of every step. The gate is a table, not a judgment: each
 * check is a broken rule ({@code RED}), something to look at ({@code YELLOW}) or a note ({@code INFO}), and every gap
 * of a step (what it could not measure, a step that left no facts) is {@code YELLOW}. So the gate is never GREEN when
 * something was not measured, and no text from the workspace or a registry decides it.
 *
 * <p>An {@code accept} line of the rule file turns the items of its check in its file into notes, with its reason; an
 * {@code accept} that matches nothing is {@code YELLOW}, so a stale exception is seen.
 */
final class Report {

    static final String RED = "RED";
    static final String YELLOW = "YELLOW";
    static final String INFO = "INFO";

    /** The level of each check. A check that is not listed is {@code YELLOW}. */
    static final Map<String, String> LEVELS = levels();

    /** What each check means, for the report's headings. */
    static final Map<String, String> TITLES = new LinkedHashMap<>();

    private static final Pattern VULN_FACT = Pattern.compile(
            "FACT (vulns_runtime|vulns_test)_(findings|critical|high|medium|low|suppressed|exit_code): (\\d{1,9})");
    private static final Pattern VULN_HIT = Pattern.compile(
            "HIT (vulns_runtime|vulns_test): (.*?) (CRITICAL|HIGH|MEDIUM|LOW) (\\S+) – (.*)");

    private static Map<String, String> levels() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String red : List.of("runtime_declared", "runtime_resolved", "enforcer_guard_missing",
                "enforcer_guard_differs", "version_not_pinned", "plugin_without_version", "plugin_not_pinned",
                "insecure_repository", "output_timestamp_missing", "java_release_differs", "base_unpinned",
                "package_unpinned", "download_unverified", "vulnerability_high")) {
            m.put(red, RED);
        }
        for (String yellow : List.of("pom_not_read", "dockerfile_not_read", "version_unresolved", "conflict",
                "managed_downgrade", "dependency_update", "plugin_update", "npm_update", "npm_version_unknown",
                "base_image_moved", "vulnerability", "accept_unused")) {
            m.put(yellow, YELLOW);
        }
        for (String info : List.of("build_extension", "package_build_only", "npm_dependencies", "base_no_tag",
                "accepted")) {
            m.put(info, INFO);
        }
        return Map.copyOf(m);
    }

    static {
        TITLES.put("runtime_declared", "A dependency outside the test scope that the rule file does not allow");
        TITLES.put("runtime_resolved", "A resolved dependency outside the test scope that the rule file does not allow");
        TITLES.put("enforcer_guard_missing", "A module without the enforcer's guard of the runtime dependencies");
        TITLES.put("enforcer_guard_differs", "The enforcer's guard differs from the rule file");
        TITLES.put("version_not_pinned", "A version that is not pinned");
        TITLES.put("plugin_without_version", "A plugin without a version");
        TITLES.put("plugin_not_pinned", "A plugin the build uses without a version in any POM");
        TITLES.put("insecure_repository", "A repository over plain http");
        TITLES.put("output_timestamp_missing", "A module without project.build.outputTimestamp");
        TITLES.put("java_release_differs", "A module that compiles for another Java release");
        TITLES.put("base_unpinned", "A base image that is not pinned by digest");
        TITLES.put("package_unpinned", "A package installed without a version");
        TITLES.put("download_unverified", "A download without a checksum");
        TITLES.put("vulnerability_high", "A high or critical vulnerability in a dependency outside the test scope");
        TITLES.put("pom_not_read", "A POM that was not read");
        TITLES.put("dockerfile_not_read", "A Dockerfile, or a part of one, that was not read");
        TITLES.put("version_unresolved", "A version whose property does not resolve");
        TITLES.put("conflict", "A version conflict");
        TITLES.put("managed_downgrade", "A version the dependency management lowers");
        TITLES.put("dependency_update", "A newer release of a dependency");
        TITLES.put("plugin_update", "A newer release of a plugin");
        TITLES.put("npm_update", "A newer release of a pinned npm package (an AI CLI of an image)");
        TITLES.put("npm_version_unknown", "A pinned npm version the registry does not list");
        TITLES.put("base_image_moved", "A newer build of a base image's tag");
        TITLES.put("vulnerability", "A vulnerability in a dependency (medium or low, or in the test scope only)");
        TITLES.put("accept_unused", "An accept line of the rule file that matches nothing");
        TITLES.put("build_extension", "A build extension that Maven loads");
        TITLES.put("package_build_only", "A package installed without a version and removed in the same RUN");
        TITLES.put("npm_dependencies", "An npm package whose own dependencies resolve on the day of the build");
        TITLES.put("base_no_tag", "A base image pinned by digest without a tag");
        TITLES.put("accepted", "Accepted by the rule file");
    }

    /** One item with its level, and the step that measured it. */
    record Entry(String level, String step, Facts.Item item) {
    }

    /** The gate, its one-line description, every entry, the gaps, and the Markdown. */
    record Outcome(String gate, String description, List<Entry> entries, List<Facts.Gap> gaps, String markdown) {
    }

    /** A step's facts, or why there are none. */
    record Step(String name, Facts facts, String missing) {
    }

    private Report() {
    }

    /**
     * The outcome of the audit.
     *
     * @param steps the facts of the offline, Maven and registry steps, in that order
     * @param scans the SARIF fact sheet of each vulnerability scan the workflow runs, by its fact prefix
     *              ({@code vulns_runtime}, {@code vulns_test}); {@code null} for a scan that left none
     */
    static Outcome of(RuleFile rules, List<Step> steps, Map<String, String> scans) {
        List<Entry> entries = new ArrayList<>();
        List<Facts.Gap> gaps = new ArrayList<>();
        for (Step s : steps) {
            if (s.facts() == null) {
                gaps.add(new Facts.Gap(s.name(), s.missing()));
                continue;
            }
            for (Facts.Gap g : s.facts().gaps) gaps.add(g);
            for (Facts.Item item : s.facts().items) {
                entries.add(new Entry(LEVELS.getOrDefault(item.check(), YELLOW), s.name(), item));
            }
        }
        Map<String, String> scanFacts = new LinkedHashMap<>();
        scans.forEach((prefix, sheet) -> vulnerabilities(prefix, sheet, prefix.equals("vulns_runtime"), entries, gaps,
                scanFacts));

        // The accept lines of the rule file: an item they name becomes a note, with the reason
        boolean[] used = new boolean[rules.accepts().size()];
        List<Entry> accepted = new ArrayList<>();
        for (Entry e : entries) {
            Entry result = e;
            for (int i = 0; i < rules.accepts().size(); i++) {
                RuleFile.Accept a = rules.accepts().get(i);
                if (a.check().equals(e.item().check()) && a.file().equals(e.item().file())) {
                    used[i] = true;
                    Facts.Item it = e.item();
                    result = new Entry(INFO, e.step(), new Facts.Item("accepted", it.file(), it.line(), it.subject(),
                            it.check() + ": " + it.detail() + "; accepted: " + a.reason()));
                    break;
                }
            }
            accepted.add(result);
        }
        for (int i = 0; i < used.length; i++) {
            RuleFile.Accept a = rules.accepts().get(i);
            if (!used[i]) {
                accepted.add(new Entry(YELLOW, "rules", new Facts.Item("accept_unused", "rule file", a.line(),
                        a.check() + " " + a.file(), "matches no item of this run")));
            }
        }
        long red = accepted.stream().filter(e -> e.level().equals(RED)).count();
        long yellow = accepted.stream().filter(e -> e.level().equals(YELLOW)).count();
        String gate = red > 0 ? RED : yellow > 0 || !gaps.isEmpty() ? YELLOW : "GREEN";
        String description = Registries.count((int) red, "broken rule") + ", "
                + Registries.count((int) yellow, "item") + " to look at, "
                + gaps.size() + " not measured, "
                + Registries.count((int) accepted.stream().filter(e -> e.level().equals(INFO)).count(), "note");
        String markdown = markdown(gate, description, steps, scanFacts, accepted, gaps);
        return new Outcome(gate, description, List.copyOf(accepted), List.copyOf(gaps), markdown);
    }

    /** The items and gaps of one scan's SARIF fact sheet, which the engine wrote ({@code docs/cli.md}). */
    private static void vulnerabilities(String prefix, String sheet, boolean runtime, List<Entry> entries,
                                        List<Facts.Gap> gaps, Map<String, String> facts) {
        if (sheet == null || sheet.isBlank()) {
            gaps.add(new Facts.Gap(prefix, "the scan left no fact sheet"));
            return;
        }
        boolean measured = false;
        for (String line : sheet.split("\r?\n")) {
            Matcher fact = VULN_FACT.matcher(line);
            if (fact.matches() && fact.group(1).equals(prefix)) {
                facts.put(prefix + " " + fact.group(2), fact.group(3));
                if (fact.group(2).equals("findings")) measured = true;
            }
            if (line.startsWith("NOT MEASURED: " + prefix)) {
                gaps.add(new Facts.Gap(prefix, Main.shown(line.substring("NOT MEASURED: ".length()))));
            }
            Matcher hit = VULN_HIT.matcher(line);
            if (hit.matches() && hit.group(1).equals(prefix)) {
                String severity = hit.group(3);
                boolean high = severity.equals("CRITICAL") || severity.equals("HIGH");
                String check = runtime && high ? "vulnerability_high" : "vulnerability";
                String detail = severity + ": " + Main.shown(hit.group(5)) + (runtime ? "" : " (test scope only: not in"
                        + " the built artifact)");
                entries.add(new Entry(LEVELS.get(check), prefix, new Facts.Item(check, "", 0, Main.shown(hit.group(4)),
                        detail)));
            }
        }
        if (!measured && gaps.stream().noneMatch(g -> g.what().equals(prefix))) {
            gaps.add(new Facts.Gap(prefix, "the fact sheet holds no count of findings"));
        }
    }

    private static String markdown(String gate, String description, List<Step> steps, Map<String, String> scanFacts,
                                   List<Entry> entries, List<Facts.Gap> gaps) {
        StringBuilder md = new StringBuilder();
        md.append("# Build and dependencies self-audit\n\n");
        md.append("**Gate:** ").append(gate).append(": ").append(description).append("\n\n");
        md.append("Every value below was measured by a deterministic step; no model decided anything. A broken rule ")
                .append("of the rule file is RED, an item to look at or anything not measured is YELLOW.\n\n");

        md.append("## Measured\n\n| Fact | Value |\n|---|---|\n");
        for (Step s : steps) {
            if (s.facts() == null) continue;
            s.facts().facts.forEach((name, value) -> md.append("| ").append(s.name()).append(" ").append(name)
                    .append(" | ").append(code(String.valueOf(value)).replace("|", "\\|")).append(" |\n"));
        }
        scanFacts.forEach((name, value) -> md.append("| ").append(name).append(" | `").append(value).append("` |\n"));
        md.append("\n");
        for (String level : List.of(RED, YELLOW, INFO)) {
            List<Entry> of = entries.stream().filter(e -> e.level().equals(level)).toList();
            md.append("## ").append(switch (level) {
                case RED -> "Broken rules (RED)";
                case YELLOW -> "To look at (YELLOW)";
                default -> "Notes";
            }).append("\n\n");
            if (of.isEmpty()) {
                md.append("None.\n\n");
                continue;
            }
            Map<String, List<Entry>> byCheck = new LinkedHashMap<>();
            for (Entry e : of) byCheck.computeIfAbsent(e.item().check(), k -> new ArrayList<>()).add(e);
            byCheck.forEach((check, list) -> {
                md.append("### ").append(plain(TITLES.getOrDefault(check, check))).append(" (").append(code(check)).append(", ")
                        .append(list.size()).append(")\n\n");
                for (Entry e : list) {
                    md.append("- ");
                    if (!e.item().where().isEmpty()) md.append(code(e.item().where())).append(" ");
                    md.append(code(e.item().subject()));
                    if (!e.item().detail().isEmpty()) md.append(": ").append(plain(e.item().detail()));
                    md.append("\n");
                }
                md.append("\n");
            });
        }
        md.append("## Not measured\n\n");
        if (gaps.isEmpty()) md.append("Nothing: every step measured what it looks at.\n");
        for (Facts.Gap g : gaps) md.append("- ").append(code(g.what())).append(": ").append(plain(g.reason())).append("\n");
        md.append("\n## What this audit does not see\n\n")
                .append("- The npm dependencies of a pinned CLI, and the Debian packages of an image, resolve when the ")
                .append("image is built; the audit reads the Dockerfile, not the image.\n")
                .append("- An update is a version number. What changed in it, and whether the code that reads a CLI's ")
                .append("output still fits, is for whoever raises the version.\n")
                .append("- A vulnerability is the scanner's database entry for a resolved version; whether Traceworks ")
                .append("reaches the vulnerable code is not judged here.\n");
        return md.toString();
    }

    /** A value from the workspace or a registry as a code span on one line: no Markdown inside it takes effect. */
    static String code(String text) {
        String line = Main.oneLine(text);
        int longest = 0;
        Matcher run = Pattern.compile("`+").matcher(line);
        while (run.find()) longest = Math.max(longest, run.group().length());
        String ticks = "`".repeat(longest + 1);
        String pad = line.startsWith("`") || line.endsWith("`") ? " " : "";
        return ticks + pad + line + pad + ticks;
    }

    /** Text as plain text on one line: it cannot start a heading, a list, a link, an image or raw HTML. */
    static String plain(String text) {
        return Main.oneLine(text).replaceAll("([\\\\`*_\\[\\]()!#|<>~{}+-])", "\\\\$1");
    }
}
