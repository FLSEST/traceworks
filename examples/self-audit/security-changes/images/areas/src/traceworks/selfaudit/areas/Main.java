package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Areas.Area;
import traceworks.selfaudit.areas.Finder.Hit;
import traceworks.selfaudit.areas.Finder.NotParsed;
import traceworks.selfaudit.areas.Finder.Result;
import traceworks.selfaudit.areas.Report.Finding;
import traceworks.selfaudit.areas.Report.Outcome;
import traceworks.selfaudit.areas.RuleFile.Rule;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The area finder's command line.
 *
 * <pre>
 * find   --rules FILE --root DIR [--source-root DIR]... --out AREAS --summary SUMMARY [--known FILE] [--items FILE]
 *        [--verdict-version 1] [--max-areas 200] [--max-lines 160] [--context 15] [--judge NAME --judge-provider ID --judge-model MODEL]
 *        [--changes DIR] [--items-file FILE] [--register KEY]
 * changed --changes DIR --root DIR                                         (writes DIR/wanted)
 * report --areas AREAS --summary SUMMARY --verdicts FILE|- --out REPORT   (and findings.jsonl next to REPORT)
 * </pre>
 *
 * {@code find} writes the areas (one JSON object per line, with a token and a code fence of the run) and a summary, and
 * prints a fact sheet. Every area is an item of the audit; with {@code --items}, the text of {@code {{ audit_items }}},
 * it leaves out an area the register calls clean or decided and that has the digest of its last verdict.
 * {@code --verdict-version} is part of every digest: who changes the question raises it, and every area is judged
 * again. {@code --judge}, {@code --judge-provider} and {@code --judge-model} say who judges this run: the option the
 * run picked of the workflow's model choice, as Traceworks hands it to the step. {@code find} writes the three into
 * the summary, for the head of the report and for {@code findings.jsonl}; no digest holds them ({@link Judge}). Without {@code --source-root} it reads every {@code src/main/java} up to six levels below the
 * root, except below {@code target}, {@code build}, {@code node_modules} and hidden directories. {@code report} prints
 * the gate first, then checks the verdicts and writes the report.
 *
 * <p>With {@code --changes}, {@code find} is the check of the changes: of all the areas it writes only the ones the
 * changes pick ({@link Changes}: a changed line, or one call from a changed member) and the ones the register left
 * open or without a verdict, each with its reason. {@code DIR} is the folder a step before wrote with the base, the
 * trees of the base and the workspace commit, and the base blobs that {@code changed} asked for; what changed is
 * decided here, from content ({@link Changes}). An area the register does not know and the changes do not pick is no item of such a run: nothing
 * looked at it. {@code --items-file} takes the items as the register folder of a run holds them
 * ({@code items.jsonl}), which no cap cuts, in place of {@code --items}. {@code --register} is the key of the
 * audit's register, for the commands the report names. Every name from the workspace is escaped on
 * standard output, and no text of a model reaches it, so neither can add a line of its own. Exit codes: 0 done, 1 the
 * input does not hold, 2 a usage error.
 */
public final class Main {

    private static final String USAGE = """
            usage: areas find --rules FILE --root DIR [--source-root DIR]... --out AREAS --summary SUMMARY
                              [--known FILE] [--items FILE] [--verdict-version N]
                              [--max-areas N] [--max-lines N] [--context N]
                              [--judge NAME --judge-provider ID --judge-model MODEL]
                              [--changes DIR] [--items-file FILE] [--register KEY]
                   areas changed --changes DIR --root DIR
                   areas report --areas AREAS --summary SUMMARY --verdicts FILE|- --out REPORT""";

    private static final int SOURCE_ROOT_DEPTH = 6;
    /** The line between an area's code and the code it is connected with, in what its digest holds. */
    private static final String CONNECTED = "# connected";
    private static final int MAX_UNMAPPED_NAMED = 50;
    private static final Set<String> SKIPPED = Set.of("target", "node_modules", "build");

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
            err.println("areas: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
        try {
            return switch (args[0]) {
                case "find" -> find(options, out, err);
                case "changed" -> changed(options, out);
                case "report" -> report(options, in, out, err);
                default -> throw new IllegalStateException(args[0]);
            };
        } catch (Usage e) {
            err.println("areas: " + e.getMessage());
            err.println(USAGE);
            return 2;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("areas: " + e.getMessage());
            return 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("areas: " + safe(String.valueOf(e)));
            return 1;
        } catch (StackOverflowError e) {
            err.println("areas: an expression nests too deeply to be read");
            return 1;
        }
    }

    private static int find(Map<String, List<String>> options, PrintStream out, PrintStream err) throws IOException {
        Path rulesFile = Path.of(required(options, "rules"));
        Path root = Path.of(required(options, "root")).toAbsolutePath().normalize();
        Path areasFile = Path.of(required(options, "out"));
        Path summaryFile = Path.of(required(options, "summary"));
        int maxAreas = number(options, "max-areas", 200);
        int maxLines = number(options, "max-lines", 160);
        int context = number(options, "context", 15);
        boolean judged = options.containsKey("judge");
        if (judged != options.containsKey("judge-provider") || judged != options.containsKey("judge-model")) {
            throw new Usage("--judge, --judge-provider and --judge-model go together");
        }
        // Before anything is written: what the run was told about its judge must be something a report can name
        Judge judge = judged ? Judge.of(required(options, "judge"), required(options, "judge-provider"),
                required(options, "judge-model")) : null;
        String register = options.containsKey("register") ? required(options, "register") : "security";
        if (!register.matches("[a-z0-9][a-z0-9._-]{0,127}")) throw new Usage("--register is no key of a register");
        if (options.containsKey("items") && options.containsKey("items-file")) {
            throw new Usage("--items and --items-file do not go together");
        }
        // The check of the changes: what git said changed since the base, as a step before wrote it down
        RuleFile rules = RuleFile.parse(Files.readString(rulesFile));
        if (!Files.isDirectory(root)) throw new IllegalArgumentException(root + " is no directory");
        Changes.Handed handed = options.containsKey("changes")
                ? Changes.read(Path.of(required(options, "changes"))) : null;

        List<Path> sourceRoots = new ArrayList<>();
        for (String given : options.getOrDefault("source-root", List.of())) {
            Path sourceRoot = root.resolve(given).normalize();
            if (!Files.isDirectory(sourceRoot)) throw new IllegalArgumentException(given + " is no directory");
            sourceRoots.add(sourceRoot);
        }
        List<NotParsed> notRead = new ArrayList<>();
        if (sourceRoots.isEmpty()) {
            Discovery discovery = discovered(root);
            sourceRoots.addAll(discovery.roots());
            for (Path link : discovery.linkedDirectories()) {
                notRead.add(new NotParsed(root.relativize(link).toString().replace('\\', '/'),
                        "a symbolic link to a directory, not searched for source roots"));
            }
        }
        if (sourceRoots.isEmpty()) throw new IllegalArgumentException("no src/main/java below " + root);

        Result result = Finder.find(root, sourceRoots, rules);
        // The check of the changes: what differs from the base, decided here from content, and the changed lines
        // of exactly the files the finder read
        Changes.Base base = handed == null ? null : Changes.of(handed, Changes.compare(handed, root), result);
        Changes.Kinds kinds = base == null ? null : Changes.kinds(base, result, root);
        notRead.addAll(result.notParsed());
        notRead.sort(java.util.Comparator.comparing(NotParsed::file));
        SecureRandom random = new SecureRandom();
        String token = HexFormat.of().formatHex(bytes(random));
        String fence = HexFormat.of().formatHex(bytes(random));
        List<Area> all = Areas.of(result, rules, maxLines, context);

        // What the register knows (the text of {{ audit_findings }}): each area gets the findings of its subject
        String knownText = options.containsKey("known")
                ? Files.readString(Path.of(required(options, "known")), StandardCharsets.UTF_8) : "";
        List<Known.Row> known = Known.parse(knownText);
        // Every area is an item of the audit. The register says which items are settled ({{ audit_items }}): an
        // area it calls clean or decided, with the digest it has now, is not judged again. The places, at most
        // --max-areas, go first to the areas that changed or were never judged, then to the open ones
        Map<String, Items.Row> knownItems = options.containsKey("items")
                ? Items.parse(Files.readString(Path.of(required(options, "items")), StandardCharsets.UTF_8))
                : options.containsKey("items-file")
                ? Items.parseJsonl(Files.readString(Path.of(required(options, "items-file")), StandardCharsets.UTF_8))
                : Map.of();
        Map<String, Changes.Pick> picks = base == null ? Map.of() : Changes.picks(all, result, base.lines());
        int changedAreas = 0;
        int neighbourAreas = 0;
        int carriedAreas = 0;
        int judgedAsTheyAre = 0;
        int notLookedAt = 0;
        int verdictVersion = number(options, "verdict-version", 1);
        List<String> names = Items.names(all.stream().map(a -> new String[] {a.file(), a.firstMember()}).toList());
        List<Map<String, Object>> changed = new ArrayList<>();
        List<Map<String, Object>> openUnchanged = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        Map<Area, Map<String, Object>> itemOf = new java.util.IdentityHashMap<>();
        Map<Map<String, Object>, Area> areaOf = new java.util.IdentityHashMap<>();
        Map<Area, String> reasonOf = new java.util.IdentityHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Area area = all.get(i);
            Changes.Pick pick = picks.get(area.id());
            // What the verdict of a picked area sees beside its own code: the changed members it is connected with
            String connected = pick == null ? "" : Changes.connectedText(pick.connected(), result, base.lines());
            String digest = Items.digest(verdictVersion, area.file(), area.rules(),
                    connected.isEmpty() ? area.code()
                            : area.code() + "\n" + CONNECTED + "\n" + Changes.connectedWhole(pick.connected(), result));
            Items.Row row = knownItems.get(Known.shown(names.get(i)));
            Map<String, Object> json = new LinkedHashMap<>(area.toJson(token, fence));
            json.put("item", names.get(i));
            json.put("digest", digest);
            Known.attach(json, area.subject(), known, null);
            // A finding of a run before items existed names no item, so its area may read clean: the finding decides
            boolean hasOpen = !((List<?>) json.get("known")).isEmpty();
            boolean same = row != null && row.digest().equals(digest);
            String reason = null;
            if (base != null) {
                boolean judgedAsItIs = same && row.settled() && !hasOpen;
                if (pick != null && !judgedAsItIs) {
                    reason = pick.reason();
                    if (pick.changed()) changedAreas++;
                    else neighbourAreas++;
                } else if (hasOpen || row != null && row.state().equals("open")) {
                    // A model's finding is not resolved by silence: every run asks about what the register holds open
                    reason = "an open finding of an earlier run";
                    carriedAreas++;
                } else if (row != null && row.state().equals("not judged")) {
                    reason = "not judged by an earlier run";
                    carriedAreas++;
                }
                if (reason == null) {
                    notLookedAt++;
                    if (pick != null) judgedAsTheyAre++;
                    // An area the register does not know is no item of this audit: nothing ever looked at it. One
                    // it calls settled stays as it is: listed with the digest of its verdict, which is not this
                    // run's to change, so that the list is whole and an area that is gone leaves the register
                    if (row == null || !row.settled() || row.digest().isEmpty()) continue;
                    Map<String, Object> settled = new LinkedHashMap<>();
                    settled.put("name", names.get(i));
                    settled.put("digest", row.digest());
                    settled.put("chosen", false);
                    settled.put("state", row.state());
                    settled.put("settled", true);
                    settled.putAll(Known.counts(json));
                    items.add(settled);
                    continue;
                }
                json.put("reason", reason);
                reasonOf.put(area, reason);
                json.put("connected", connected.isEmpty() ? Known.NONE : connected);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", names.get(i));
            item.put("digest", digest);
            item.put("chosen", false);
            item.put("state", row == null ? "" : row.state());
            items.add(item);
            itemOf.put(area, item);
            areaOf.put(json, area);
            item.put("settled", base == null && same && row.settled() && !hasOpen);
            // For the report, which says in what state the item is after the run: what the register holds of it
            item.putAll(Known.counts(json));
            if (base != null) {
                // Under the cap the areas of the changes go first, then what earlier runs left
                (pick != null && reason.equals(pick.reason()) ? changed : openUnchanged).add(json);
                continue;
            }
            if (same && row.settled() && !hasOpen) continue;
            (same && (row.state().equals("open") || hasOpen) ? openUnchanged : changed).add(json);
        }
        List<Map<String, Object>> toJudge = new ArrayList<>(changed);
        toJudge.addAll(openUnchanged);
        List<Area> kept = new ArrayList<>();
        List<Area> cut = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < toJudge.size(); i++) {
            Area area = areaOf.get(toJudge.get(i));
            if (i < maxAreas) {
                kept.add(area);
                itemOf.get(area).put("chosen", true);
                lines.append(Json.write(toJudge.get(i))).append('\n');
            } else {
                cut.add(area);
            }
        }
        Files.writeString(areasFile, lines, StandardCharsets.UTF_8);
        Set<String> subjects = new java.util.HashSet<>();
        for (Area area : all) subjects.add(area.subject());

        List<String> roots = sourceRoots.stream().map(r -> root.relativize(r).toString().replace('\\', '/')).toList();
        Map<String, Long> perRule = new LinkedHashMap<>();
        for (Rule rule : rules.rules()) perRule.put(rule.id(), 0L);
        for (Hit hit : result.hits()) perRule.merge(hit.rule(), 1L, Long::sum);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceRoots", roots);
        summary.put("javaFiles", (long) result.files().size());
        summary.put("notRead", notRead.stream().map(n -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", n.file());
            entry.put("reason", n.reason());
            return entry;
        }).toList());
        summary.put("unresolvedReferences", (long) result.unresolved());
        summary.put("hits", (long) result.hits().size());
        summary.put("hitsPerRule", perRule);
        summary.put("areas", (long) kept.size());
        summary.put("cut", cut.stream().map(a -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("subject", a.subject());
            entry.put("start", (long) a.start());
            entry.put("end", (long) a.end());
            entry.put("rules", a.rules());
            return entry;
        }).toList());
        summary.put("items", items);
        summary.put("rules", rules.rules().stream().map(Rule::id).toList());
        summary.put("unresolvedTargets", result.unresolvedTargets());
        // Known findings whose member is no area any more: the report names them as gone
        List<Map<String, Object>> orphans = Known.orphans(known, null, subjects);
        List<Map<String, Object>> unowned = new ArrayList<>(Known.unowned(known, null, subjects));
        if (kinds != null && !(kinds.uncompiled().isEmpty() && kinds.tests().isEmpty()) && !orphans.isEmpty()) {
            // Java code changed or came where the finder does not read, a test source root included. The member
            // of such a finding may have moved there, so its absence from the areas says nothing: the finding stays
            // open, for a person
            for (Map<String, Object> orphan : orphans) {
                Map<String, Object> entry = new LinkedHashMap<>(orphan);
                entry.put("severity", known.stream().filter(row -> row.asked() && row.ruleKey().equals(orphan.get("ruleKey"))
                        && row.subject().equals(orphan.get("subject"))).map(Known.Row::severity).findFirst().orElse(""));
                unowned.add(entry);
            }
            orphans = List.of();
        }
        summary.put("knownGone", orphans);
        // Open findings that no area carries and that are not gone either: for a person, and for the gate
        summary.put("knownUnowned", unowned);
        // How many findings {{ audit_findings }} left out: nothing is concluded from a row that is not there
        summary.put("knownMissing", (long) Known.missing(knownText));
        // How many of them are open by the block's own count: with any, an area the register calls open stays open
        summary.put("knownMissingOpen", (long) Known.missingOpen(knownText));
        // Who judges this run: for a reader of the report and of findings.jsonl, never for a digest or the register
        if (judge != null) summary.put("judge", judge.toJson());
        // The key of the audit's register, for the commands the report names
        summary.put("register", register);
        if (base != null) {
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("base", base.base());
            changes.put("head", base.head());
            changes.put("from", base.from());
            changes.put("since", base.since());
            changes.put("previousHead", base.previousHead());
            changes.put("dirty", base.dirty());
            changes.put("changedFiles", (long) base.files());
            changes.put("baseDate", base.baseDate());
            changes.put("skipped", base.skipped());
            changes.put("removedFiles", (long) base.removed().size());
            changes.put("unmappable", base.unmappable());
            List<String> outside = Changes.outsideMembers(result, base.lines());
            changes.put("outsideMembers", (long) outside.size());
            changes.put("outside", outside.subList(0, Math.min(outside.size(), MAX_UNMAPPED_NAMED)));
            changes.put("testFiles", (long) kinds.tests().size());
            changes.put("docFiles", (long) kinds.docs().size());
            changes.put("uncompiledFiles", (long) kinds.uncompiled().size());
            changes.put("uncompiled", named(kinds.uncompiled()));
            changes.put("cannotJudgeFiles", (long) kinds.cannotJudge().size());
            changes.put("cannotJudge", named(kinds.cannotJudge()));
            changes.put("removedJavaFiles", (long) kinds.removedJava().size());
            changes.put("removedJava", named(kinds.removedJava()));
            // Up to where the history counts as judged: the workspace commit, when the files that were judged are
            // that commit. A workspace that differs from it was judged as it is, not as the commit is, so the next
            // run starts from this run's base again and no commit is left out
            changes.put("judgedUpTo", base.dirty() ? base.base() : base.head());
            changes.put("changedMembers", (long) Changes.changedMembers(result, base.lines()).size());
            changes.put("areasOfProject", (long) all.size());
            changes.put("changedAreas", (long) changedAreas);
            changes.put("neighbourAreas", (long) neighbourAreas);
            changes.put("carriedAreas", (long) carriedAreas);
            changes.put("judgedAsTheyAre", (long) judgedAsTheyAre);
            changes.put("notLookedAt", (long) notLookedAt);
            summary.put("changes", changes);
        }
        Files.writeString(summaryFile, Json.write(summary) + "\n", StandardCharsets.UTF_8);

        if (base != null) {
            out.println("FACT changes_base: " + base.base());
            out.println("FACT changes_head: " + base.head());
            out.println("FACT changes_base_from: " + (base.from().equals("input") ? "the input since"
                    : "the last run of this audit"));
            out.println("FACT changes_uncommitted: " + base.dirty());
            out.println("FACT changes_files: " + base.files());
            out.println("FACT changes_files_not_compared: " + base.unmappable().size());
            out.println("FACT changes_commits_skipped: " + (base.skipped().isEmpty() ? "0" : base.skipped()));
            for (String file : base.unmappable()) out.println("NOT MEASURED: changes_file " + safe(file));
            out.println("FACT changes_files_java_not_compiled: " + kinds.uncompiled().size());
            out.println("FACT changes_files_cannot_judge: " + kinds.cannotJudge().size());
            out.println("FACT changes_files_tests: " + kinds.tests().size());
            out.println("FACT changes_files_docs: " + kinds.docs().size());
            out.println("FACT changes_files_java_removed: " + kinds.removedJava().size());
            out.println("FACT changes_areas_of_project: " + all.size());
            out.println("FACT changes_areas_changed: " + changedAreas);
            out.println("FACT changes_areas_neighbours: " + neighbourAreas);
            out.println("FACT changes_areas_carried: " + carriedAreas);
            out.println("FACT changes_areas_not_looked_at: " + notLookedAt);
        }
        out.println("FACT areas_source_roots: " + safe(String.join(", ", roots)));
        out.println("FACT areas_java_files: " + result.files().size());
        out.println("FACT areas_not_read: " + notRead.size());
        out.println("FACT areas_unresolved_references: " + result.unresolved());
        out.println("FACT areas_unresolved_targets: " + String.join(", ", result.unresolvedTargets()));
        out.println("FACT areas_hits: " + result.hits().size());
        perRule.forEach((rule, n) -> out.println("FACT areas_hits_" + rule + ": " + n));
        out.println("FACT areas_items: " + (base == null ? all.size() : items.size()));
        out.println("FACT areas_items_settled: " + (base == null ? all.size() - kept.size() - cut.size()
                : items.size() - kept.size() - cut.size()));
        out.println("FACT areas: " + kept.size());
        out.println("FACT areas_cut: " + cut.size());
        if (judge != null) {
            out.println("FACT areas_judge: " + safe(judge.name()));
            out.println("FACT areas_judge_provider: " + safe(judge.provider()));
            out.println("FACT areas_judge_model: " + safe(judge.model()));
        }
        for (NotParsed n : notRead) {
            out.println("NOT MEASURED: areas_file " + safe(n.file()) + " (" + safe(n.reason()) + ")");
        }
        for (Area a : cut) {
            out.println("NOT MEASURED: areas_cut " + safe(a.subject()) + " (beyond --max-areas " + maxAreas + ")");
        }
        for (Area a : kept) {
            out.println("AREA " + a.id() + " " + safe(a.file()) + ":" + a.start() + "-" + a.end() + " "
                    + safe(a.member()) + " " + a.rules().stream().collect(Collectors.joining(", ", "[", "]"))
                    + (reasonOf.containsKey(a) ? " because: " + safe(reasonOf.get(a)) : ""));
        }
        return 0;
    }

    /** The findings of a report as data, written next to the report (docs/registers.md, "The detail of a finding"). */
    static final String FINDINGS_FILE = "findings.jsonl";

    private static int report(Map<String, List<String>> options, InputStream in, PrintStream out, PrintStream err)
            throws IOException {
        Path areasFile = Path.of(required(options, "areas"));
        Path summaryFile = Path.of(required(options, "summary"));
        String verdictsFile = required(options, "verdicts");
        Path reportFile = Path.of(required(options, "out"));
        List<String> areaLines = Files.readString(areasFile, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
        if (!(Json.parse(Files.readString(summaryFile, StandardCharsets.UTF_8)) instanceof Map<?, ?> rawSummary)) {
            throw new IllegalArgumentException(summaryFile + " holds no JSON object");
        }
        Map<String, Object> summary = new HashMap<>();
        rawSummary.forEach((k, v) -> summary.put(String.valueOf(k), v));
        String verdicts = verdictsFile.equals("-") ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(verdictsFile), StandardCharsets.UTF_8);

        Outcome outcome = Report.of(areaLines, summary, verdicts);
        Files.write(reportFile, Report.utf8(outcome.markdown()));
        // The findings as data, next to the report: the scratch directory is recorded, so the run keeps both
        Files.write(reportFile.resolveSibling(FINDINGS_FILE), Report.utf8(outcome.findingsJsonl()));

        // The gate first: the engine takes the first gate line of a step's output
        out.println("gate_status: " + outcome.gate());
        out.println("gate_category: security");
        out.println("gate_description: " + outcome.description());
        // Right after the gate, before any name of the workspace: the next run of the audit reads its base from
        // the first changes_head line of this output, which the register hands it as the previous report. It is
        // the commit up to which this run judged: the workspace commit, or this run's base again when the files
        // were not that commit
        if (summary.get("changes") instanceof Map<?, ?> changes) {
            out.println("FACT changes_base: " + safe(String.valueOf(changes.get("base"))));
            out.println("FACT changes_head: " + safe(String.valueOf(changes.containsKey("judgedUpTo")
                    ? changes.get("judgedUpTo") : changes.get("head"))));
            out.println("FACT changes_areas_changed: " + count(changes.get("changedAreas")));
            out.println("FACT changes_areas_neighbours: " + count(changes.get("neighbourAreas")));
            out.println("FACT changes_areas_carried: " + count(changes.get("carriedAreas")));
            out.println("FACT changes_areas_not_looked_at: " + count(changes.get("notLookedAt")));
        }
        out.println("FACT report_areas: " + outcome.areas());
        out.println("FACT report_judged: " + outcome.judged());
        out.println("FACT report_findings_verified: " + outcome.verified().size());
        out.println("FACT report_findings_unverified: " + outcome.unverified().size());
        out.println("FACT report_known_gone: " + outcome.gone().size());
        out.println("FACT report_known_not_answered: " + outcome.notAnswered().size());
        for (Finding f : outcome.verified()) {
            // The rule is a rule id of the file or "other", the severity one of three words: no model text
            out.println("VERIFIED " + f.severity() + " " + f.rule() + " " + f.area() + " " + safe(f.file()) + ":"
                    + f.line());
        }
        // The envelope last: what a register reads of this step (docs/registers.md)
        out.println(outcome.envelope());
        return 0;
    }

    /** The subcommand's options, each with its values; {@code --source-root} may repeat. */
    private static Map<String, List<String>> options(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("a command is missing");
        Set<String> known = switch (args[0]) {
            case "find" -> Set.of("rules", "root", "source-root", "out", "summary", "max-areas", "max-lines",
                    "context", "known", "items", "verdict-version", "judge", "judge-provider", "judge-model",
                    "changes", "items-file", "register");
            case "report" -> Set.of("areas", "summary", "verdicts", "out");
            case "changed" -> Set.of("changes", "root");
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

    /** The source roots discovery found, and the symbolic links to directories it could not enter. */
    private record Discovery(List<Path> roots, List<Path> linkedDirectories) {
    }

    /**
     * Every {@code src/main/java} directory up to {@value #SOURCE_ROOT_DEPTH} levels below {@code root}, sorted, except
     * in skipped and hidden directories; and every symbolic link to a directory there, which the walk does not enter.
     */
    private static Discovery discovered(Path root) throws IOException {
        List<Path> roots = new ArrayList<>();
        List<Path> linked = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, SOURCE_ROOT_DEPTH)) {
            walk.filter(dir -> {
                for (Path part : root.relativize(dir)) {
                    String name = part.toString();
                    if (SKIPPED.contains(name) || name.startsWith(".")) return false;
                }
                return true;
            }).forEach(dir -> {
                if (Files.isSymbolicLink(dir)) {
                    if (Files.isDirectory(dir)) linked.add(dir);
                } else if (Files.isDirectory(dir) && dir.endsWith(Path.of("src", "main", "java"))) {
                    roots.add(dir);
                }
            });
        }
        roots.sort(null);
        linked.sort(null);
        return new Discovery(List.copyOf(roots), List.copyOf(linked));
    }

    private static List<String> named(List<String> files) {
        return files.subList(0, Math.min(files.size(), MAX_UNMAPPED_NAMED));
    }

    /**
     * {@code changed}: compares the workspace with the base and writes the ids of the base blobs the line
     * comparison of {@code find --changes} needs into {@code wanted}, one a line. It refuses a base that leaves
     * nothing to check: one the run named that is the workspace commit itself, with a workspace that does not
     * differ from it.
     */
    private static int changed(Map<String, List<String>> options, PrintStream out) throws IOException {
        Path dir = Path.of(required(options, "changes"));
        Path root = Path.of(required(options, "root")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException(root + " is no directory");
        Changes.Handed handed = Changes.read(dir);
        Changes.Compared compared = Changes.compare(handed, root);
        if (handed.from().equals("input") && handed.base().equals(handed.head()) && !compared.dirty()) {
            throw new IllegalArgumentException("nothing to check: the base '" + safe(handed.since())
                    + "' is the workspace commit itself (" + handed.base()
                    + (handed.baseDate().isEmpty() ? "" : ", committed " + handed.baseDate())
                    + "), and the workspace holds no other change. Name an earlier commit or date");
        }
        List<String> wanted = Changes.wanted(compared);
        Files.writeString(dir.resolve("wanted"), wanted.isEmpty() ? "" : String.join("\n", wanted) + "\n",
                StandardCharsets.UTF_8);
        out.println("FACT changes_files: " + compared.status().size());
        out.println("FACT changes_uncommitted: " + compared.dirty());
        out.println("FACT changes_base_blobs_wanted: " + wanted.size());
        return 0;
    }

    private static long count(Object value) {
        return value instanceof Long n ? n : 0;
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
        Usage(String message) {
            super(message);
        }
    }
}
