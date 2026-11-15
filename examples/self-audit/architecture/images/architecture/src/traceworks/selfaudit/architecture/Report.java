package traceworks.selfaudit.architecture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the AI's verdicts against the candidates they judged, and writes the report and the gate. The measured
 * findings come from the facts alone, so the report lists them even when every AI call failed.
 *
 * <p>The verdicts are the output of the {@code foreach} over the lines of {@code candidates.jsonl}: each item as
 * {@code [<item>]: } followed by its answer. The answer of candidate <em>i</em> is the text between its marker and the
 * next marker found. Each line holds a token of the run that no prompt shows, so a model cannot write a marker; a marker
 * that occurs twice was copied all the same, and its candidate is not judged. A candidate without a marker, without a
 * JSON object that has a {@code candidate}, or with an answer for another candidate is <em>not judged</em>.
 *
 * <p>A verdict is <em>verified</em> when it is {@code fine}, {@code split} or {@code move}; it quotes at least one line
 * and every quote, with blanks collapsed, occurs in the line it names of the code the AI saw and has at least
 * {@value #MIN_QUOTE} characters or is the whole line; a {@code split} names the members that move, each a member of the
 * file, and a target; a {@code move} names a target, and any member it names is one of the file; and, for a candidate
 * with a measured finding, it says what the dependency or the I/O is for ({@code purpose}) and the smallest change that
 * removes it ({@code change}); and a {@code split} or {@code move} says how much it matters: {@code high},
 * {@code medium} or {@code low}. Every other verdict is <em>unverified</em>, with the reason.
 *
 * <p>The register and the gate take the splits and moves a verdict rates medium or high. One it rates low is listed in
 * the report of the run and nowhere else: not in the envelope, not in {@code findings.jsonl}. Its file counts as
 * judged. An open verdict the register holds is answered as before: the same verdict again states it, whatever its
 * severity.
 *
 * <p>The gate follows the items ({@link States}): every candidate file is one, and its state after the run is clean,
 * decided, open or not judged. RED for a layer violation or I/O the rule file forbids, and while an open verdict of
 * high severity stands: a split or move rated high, or a high one of the register that this run did not answer.
 * YELLOW for a package cycle, while an item is open or not judged, for a candidate shown in part, a file not read, a
 * type name that did not resolve but names a type of the sources, or no Java file read at all. GREEN when every item
 * is settled.
 *
 * <p>Every candidate file is an item of the audit (docs/registers.md, "Items"). The envelope lists them all and says
 * which of them this run judged: a file whose call failed, whose verdict does not hold or that got no place is an
 * item not judged, and the snapshot stays full.
 */
final class Report {

    static final int MIN_QUOTE = 8;

    private static final Set<String> VERDICTS = Set.of("fine", "split", "move");
    private static final Set<String> SEVERITIES = Set.of("high", "medium", "low");
    // DOTALL: a code line may hold a line or paragraph separator, which is legal in a comment or a string
    private static final Pattern NUMBERED = Pattern.compile("(\\d+)\\| ?(.*)", Pattern.DOTALL);
    private static final int MAX_SHOWN = 120;
    private static final int MAX_TABLE = 10;

    private static final Set<String> HIGH = Set.of("HIGH", "CRITICAL");

    /** What the register takes in one report (docs/registers.md, "Items"). */
    static final int ITEMS_MAX = 1000;

    /** A verdict of the AI; {@code problem} is {@code null} for a verified one, else why it does not hold. */
    record Verdict(String id, String file, boolean measured, String verdict, String severity, String reason,
                   List<String> members, String target, String purpose, String change, List<String> quotes,
                   String problem) {
    }

    /** A candidate without a usable answer. */
    record NotJudged(String id, String file, String reason) {
    }

    /**
     * What the check found, the gate, the report, and the envelope: the audit result envelope v1 that a register
     * reads (docs/registers.md), with the measured findings and the verified split and move verdicts; and
     * {@code findingsJsonl}, the same findings as data, one JSON object per finding of the envelope and line, with the
     * facts of a measured finding and the model's reason and target of a verdict.
     */
    record Outcome(List<Verdict> verified, List<Verdict> unverified, List<NotJudged> notJudged, int candidates,
                   int judged, String gate, String description, String markdown, String envelope,
                   String findingsJsonl, List<Gone> gone, List<String> notAnswered, List<Verdict> low) {
    }

    /** The rules of the envelope that are a model's judgement; the catalog marks them as judged. */
    static final Set<String> JUDGED_RULES = Set.of("verdict-split", "verdict-move");

    /**
     * An open verdict of an earlier run that is gone: this run's verified verdict for the file is another one
     * ({@code reason} is the model's then), or the file is no candidate any more ({@code candidate} is empty then).
     */
    record Gone(String rule, String subject, String candidate, String why, String reason) {
    }

    private Report() {
    }

    /**
     * Checks {@code verdicts} against the candidates.
     *
     * @param candidateLines the lines of {@code candidates.jsonl}, exactly as the {@code foreach} saw them
     * @param facts          {@code architecture-facts.json}
     * @param verdicts       the output of the {@code foreach}
     */
    static Outcome of(List<String> candidateLines, Map<String, Object> facts, String verdicts) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String line : candidateLines) candidates.add(object(Json.parse(line)));

        // Every place where a marker occurs; a candidate's answer runs to the next of them
        int[] markers = new int[candidateLines.size()];
        int[] counts = new int[candidateLines.size()];
        TreeSet<Integer> occurrences = new TreeSet<>();
        for (int i = 0; i < candidateLines.size(); i++) {
            String marker = "[" + candidateLines.get(i) + "]: ";
            markers[i] = verdicts.indexOf(marker);
            for (int at = markers[i]; at >= 0; at = verdicts.indexOf(marker, at + 1)) {
                counts[i]++;
                occurrences.add(at);
            }
        }

        List<Verdict> verified = new ArrayList<>();
        List<Verdict> unverified = new ArrayList<>();
        List<NotJudged> notJudged = new ArrayList<>();
        List<Gone> gone = new ArrayList<>();
        List<String> notAnswered = new ArrayList<>();
        // The splits and moves a verdict rates low, and that state no open verdict of the register again: they are
        // verified, so their files are judged, and they are in the report of the run and nowhere else
        List<Verdict> low = new ArrayList<>();
        // For the state of the items after the run: the candidates with an open verdict of the register that got no
        // answer, the ones with one that is gone now, and whether an unanswered one is high
        Set<String> unanswered = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        boolean highUnanswered = false;
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> candidate = candidates.get(i);
            String id = text(candidate.get("id"));
            String file = text(candidate.get("file"));
            if (markers[i] < 0) {
                notJudged.add(new NotJudged(id, file, "no answer in the verdicts: the loop did not reach the candidate"));
                continue;
            }
            if (counts[i] > 1) {
                notJudged.add(new NotJudged(id, file,
                        "its marker occurs " + counts[i] + " times in the verdicts: an answer copied it"));
                continue;
            }
            int start = markers[i] + candidateLines.get(i).length() + 4;
            Integer next = occurrences.higher(markers[i]);
            String answer = verdicts.substring(start, next == null ? verdicts.length() : Math.max(start, next));
            var verdict = Json.firstObject(answer, "candidate");
            if (verdict.isEmpty()) {
                notJudged.add(new NotJudged(id, file, "no JSON answer with a \"candidate\": " + shown(answer)));
                continue;
            }
            if (!id.equals(text(verdict.get().get("candidate")))) {
                notJudged.add(new NotJudged(id, file,
                        "the answer is for candidate " + shown(text(verdict.get().get("candidate")))));
                continue;
            }
            Verdict checked = check(id, file, candidate, verdict.get());
            (checked.problem() == null ? verified : unverified).add(checked);
            // The open verdicts the register knows of this file: the verdict of this run answers each. The same
            // one again states it; another one that holds says it is gone; one that does not hold says nothing
            boolean statesAnOpenOne = false;
            for (Object entry : list(candidate.get("known"))) {
                Map<String, Object> known = object(entry);
                String rule = text(known.get("rule"));
                if (checked.problem() != null) {
                    notAnswered.add(id + " " + text(known.get("id")) + " " + rule);
                    unanswered.add(id);
                    highUnanswered |= HIGH.contains(text(known.get("severity")));
                } else if (!rule.equals("verdict-" + checked.verdict())) {
                    gone.add(new Gone(rule, file, id, "the verdict of this run is " + checked.verdict(),
                            checked.reason()));
                    resolved.add(id);
                } else {
                    statesAnOpenOne = true;
                }
            }
            if (checked.problem() == null && checked.severity().equals("low") && !statesAnOpenOne) low.add(checked);
        }
        for (Object entry : list(facts.get("knownGone"))) {
            Map<String, Object> orphan = object(entry);
            gone.add(new Gone(text(orphan.get("ruleKey")), text(orphan.get("subject")), "",
                    "the file is no candidate any more", ""));
        }
        int judged = candidates.size() - notJudged.size();
        // The files shown in part: the candidates of this run, and the items the facts left out as settled whose
        // verdict saw a part too. The gate says the same in the run that judges such a file and in the later ones
        List<String> partial = new ArrayList<>(candidates.stream()
                .filter(c -> !"whole".equals(text(c.get("shown")))).map(c -> text(c.get("file"))).toList());
        List<String> partialLeftOut = new ArrayList<>();
        for (Object entry : list(facts.get("items"))) {
            Map<String, Object> item = object(entry);
            if (Boolean.TRUE.equals(item.get("shown_in_part")) && !Boolean.TRUE.equals(item.get("chosen"))) {
                partialLeftOut.add(text(item.get("name")));
            }
        }
        partial.addAll(partialLeftOut);
        // What the register gets of the verdicts: the splits and moves that are not only in this report
        List<Verdict> stated = verified.stream().filter(v -> !v.verdict().equals("fine") && !low.contains(v)).toList();
        // The gate follows the items: in what state each is after this run
        Map<String, String> numberOf = new HashMap<>();
        Set<String> answered = new HashSet<>();
        for (Map<String, Object> candidate : candidates) {
            numberOf.put(States.name(candidate), text(candidate.get("id")));
            answered.add(text(candidate.get("id")));
        }
        for (NotJudged c : notJudged) answered.remove(c.id());
        Set<String> openNow = new HashSet<>(unanswered);
        Set<String> doubtful = new HashSet<>();
        // Every verdict the run states opens its file and counts, also one the register holds a decision for: what
        // the register shows of a decided verdict cannot tell whether this is it, and a decision must not hide what
        // the person never saw. The report names those, and says for each whether the register will open it again
        // (given above the severity the decision was made on) or keep the decision
        Map<String, Map<String, Object>> candidateOf = new HashMap<>();
        for (Map<String, Object> candidate : candidates) candidateOf.put(text(candidate.get("id")), candidate);
        List<String> decided = new ArrayList<>();
        for (Verdict v : stated) {
            Map<?, ?> known = Known.decided(candidateOf.get(v.id()), "verdict-" + v.verdict(), v.file());
            if (known != null) {
                decided.add(v.verdict() + " " + code(v.file()) + " (" + plain(String.valueOf(known.get("status")))
                        + ")" + Known.registerWill(known.get("severity"), v.severity()));
            }
        }
        for (Verdict v : stated) openNow.add(v.id());
        for (Verdict v : unverified) doubtful.add(v.id());
        int missing = (int) number(facts.get("knownMissing"));
        // Older facts name no count of the open ones: then every missing row may be open
        boolean openMissing = (facts.containsKey("knownMissingOpen") ? number(facts.get("knownMissingOpen"))
                : missing) > 0;
        States.Counts states = States.of(States.items(facts.get("items"), candidates, list(facts.get("cut")), "file"),
                numberOf, answered, doubtful, openNow, resolved,
                highUnanswered || unownedHigh(facts) || stated.stream().anyMatch(v -> v.severity().equals("high")),
                openMissing);
        String gate = gate(states, partial, facts);
        String description = description(stated, unverified, notJudged, partial, candidates.size(), judged, facts, low,
                states);
        String markdown = markdown(verified, unverified, low, notJudged, candidates, judged, facts, gate, description,
                states)
                + partialLeftOut(partialLeftOut)
                + known(gone, notAnswered) + unowned(facts, "verdicts", "file", "architecture")
                + decided(decided) + unseen(missing);
        // Each candidate is an item of the audit; the ones with a verdict that holds are the items judged in this
        // run. A verdict whose evidence does not hold is no verdict to keep: the register never sees it, so the file
        // would read clean and leave the loop. It is listed as not judged, and the next run asks again
        Map<String, String> itemOf = new HashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String item = text(candidate.get("item"));
            if (!item.isEmpty()) itemOf.put(text(candidate.get("id")), item);
        }
        Set<String> judgedItems = new HashSet<>();
        for (Verdict v : verified) {
            if (itemOf.containsKey(v.id())) judgedItems.add(itemOf.get(v.id()));
        }
        // A candidate without a verdict (its call failed, or it got no place) is an item not judged, which the
        // envelope says. Only a file nobody could read makes the snapshot partial: nothing was measured there, and a
        // measured finding that was not looked for would be resolved by its absence
        boolean complete = list(facts.get("notRead")).isEmpty();
        Snapshot snapshot = snapshot(stated, gone, complete, facts, itemOf, judgedItems);
        return new Outcome(List.copyOf(verified), List.copyOf(unverified), List.copyOf(notJudged), candidates.size(),
                judged, gate, description, markdown, snapshot.envelope(), snapshot.findingsJsonl(), List.copyOf(gone),
                List.copyOf(notAnswered), List.copyOf(low));
    }

    /**
     * The audit result envelope v1: every measured finding (a layer violation, I/O the rule file forbids, a package
     * cycle) with severity HIGH, then every verified {@code split} or {@code move} verdict that is not only for the
     * report of the run (a new one rated low), with the severity the verdict gives. It is
     * a full snapshot unless a file could not be read: a register that applied a partial one would resolve measured
     * findings nobody looked for. It lists every item of the audit, each candidate file, with its digest and whether
     * this run gave a verdict for it that holds ({@code items}, {@code expectedItems}), and every verdict names the
     * item of its file: a file whose call failed, or that got no place, is an item not judged, and the register keeps
     * what it knows of it (docs/registers.md, "Items"). The evidence of each finding names a fact of the {@code facts}
     * step, so the register can check it. No text of a model is in it: a verdict's reason and target stay in the
     * report file; its members and quotes are the file's own text.
     *
     * <p>Beside it, {@code findings.jsonl}: one JSON object and line per finding of the envelope, with the same
     * {@code ruleKey} and {@code subject}, so the two join. A measured finding has its places there, a verdict its
     * quoted lines and the model's reason and target; {@code item} is the item of the envelope, and {@code number}
     * the number the candidate has in this run's report. The register never reads that file; the record of the run
     * keeps it, and {@code traceworks findings architecture show} reads it from there.
     */
    /**
     * The files shown in part that this run did not judge: their verdict is of an earlier run and saw a part of the
     * file. Empty when there are none.
     */
    private static String partialLeftOut(List<String> files) {
        if (files.isEmpty()) return "";
        StringBuilder md = new StringBuilder("\n## Files left out, whose last verdict saw a part (" + files.size()
                + ")\n\nSettled in the register and unchanged, so not judged in this run. Each is longer than the"
                + " prompt holds (`--max-code-lines`), so its verdict saw a part of it; the gate is not GREEN over"
                + " that, in this run as in the one that judged it.\n\n");
        for (String file : files) md.append("- ").append(code(file)).append("\n");
        return md.toString();
    }

    /** The evidence the envelope gives a verdict: the fact of the facts step, and each quoted line with its place. */
    private static String evidence(Verdict v) {
        List<String> quoted = new ArrayList<>();
        for (String quote : v.quotes()) {
            // A quote is "<line>: <code>", checked against the code the AI saw
            int colon = quote.indexOf(": ");
            quoted.add(colon < 0 ? "\"" + quote + "\""
                    : "line " + quote.substring(0, colon) + ": \"" + quote.substring(colon + 2) + "\"");
        }
        return "arch_candidates: " + v.id() + "; " + String.join("; ", quoted);
    }

    /** The stated verdicts the register holds a decision for; empty when there are none. */
    private static String decided(List<String> decided) {
        if (decided.isEmpty()) return "";
        StringBuilder md = new StringBuilder("\n## Verdicts the register holds a decision for (" + decided.size()
                + ")\n\nA person accepted or dismissed a verdict of the same kind for the file of each of these, and"
                + " this run gives one again. A decision covers the severity it was made on: when the register takes"
                + " this report, a verdict given above that severity is open again, and one given at or below it keeps"
                + " its decision, unless a person reopens it (`traceworks findings architecture reopen <id>`). Each"
                + " line says which; with `\"decision_on_higher_severity\": \"keep\"` in the operator's"
                + " `traceworks.json`, which a run does not see, the register keeps every decision. Whether this is"
                + " what was decided, or another reason to split or move the file, is not told here: the gate counts"
                + " each of them in this run, and its file is open.\n\n");
        for (String entry : decided) md.append("- ").append(entry).append("\n");
        return md.toString();
    }

    /** What the register did not show this run, for the end of the report; empty when it showed everything. */
    private static String unseen(int missing) {
        if (missing <= 0) return "";
        return "\n## Findings of the register this run did not see (" + missing + ")\n\nThe register handed this run"
                + " its findings in a block that holds at most 250 rows or 60 000 bytes, and left " + missing + " out."
                + " An open verdict can be among them, so no call was asked about it. The gate is not GREEN over"
                + " that, and a file the register calls open stays open. Decide or fix findings until the block holds"
                + " them all.\n";
    }

    /** What became of the open verdicts of earlier runs, for the end of the report; empty when there were none. */
    private static String known(List<Gone> gone, List<String> notAnswered) {
        StringBuilder md = new StringBuilder();
        if (!gone.isEmpty()) {
            md.append("\n## Earlier verdicts that are gone\n\nOpen in the register, and no longer what this run ")
                    .append("finds. The register resolves them. A reason is the model's, not checked.\n\n");
            for (Gone g : gone) {
                md.append("- ").append(ruleId(g.rule())).append(" ").append(code(g.subject())).append(": ")
                        .append(g.why()).append(g.reason().isBlank() ? "" : ". " + plain(g.reason())).append("\n");
            }
        }
        if (!notAnswered.isEmpty()) {
            md.append("\n## Open verdicts without an answer\n\nOpen in the register, and the verdict of this run for ")
                    .append("their file does not hold, so it says nothing about them. They stay open.\n\n");
            for (String entry : notAnswered) md.append("- ").append(plain(entry)).append("\n");
        }
        return md.toString();
    }

    private static Snapshot snapshot(List<Verdict> stated, List<Gone> gone, boolean complete,
                                     Map<String, Object> facts, Map<String, String> itemOf,
                                     Set<String> judgedItems) {
        List<Map<String, Object>> findings = new ArrayList<>();
        StringBuilder jsonl = new StringBuilder();
        for (Object entry : list(facts.get("layerViolations"))) {
            Map<String, Object> v = object(entry);
            String edge = text(v.get("from")) + " -> " + text(v.get("to"));
            List<String> where = new ArrayList<>();
            List<Map<String, Object>> places = new ArrayList<>();
            for (Object s : list(v.get("sites"))) {
                Map<String, Object> site = object(s);
                for (Object l : list(site.get("lines"))) {
                    Map<String, Object> use = object(l);
                    String what = String.join(", ", list(use.get("what")).stream().map(String::valueOf).toList());
                    where.add(text(site.get("type")) + " in " + text(site.get("file")) + ", line " + number(use.get("line"))
                            + ": " + what);
                    places.add(place(text(site.get("file")), number(use.get("line")), "type", text(site.get("type")),
                            "uses", what, "rule", ruleId(text(v.get("rule")))));
                }
            }
            entry(jsonl, "layer-violation", edge, "HIGH", null, null, "measured", places);
            findings.add(finding("layer-violation", edge, "HIGH", "Layer violation: " + edge + " (rule "
                    + ruleId(text(v.get("rule"))) + ")", String.join("; ", where),
                    "arch_layer_violations: " + edge + " (rule " + ruleId(text(v.get("rule"))) + ")"));
        }
        for (Object entry : list(facts.get("io"))) {
            Map<String, Object> f = object(entry);
            if (Boolean.TRUE.equals(f.get("allowed"))) continue;
            List<String> where = new ArrayList<>();
            List<Map<String, Object>> places = new ArrayList<>();
            String rule = ruleId(text(f.get("rule")));
            for (Object u : list(f.get("uses"))) {
                Map<String, Object> use = object(u);
                String what = String.join(", ", list(use.get("what")).stream().map(String::valueOf).toList());
                where.add("line " + number(use.get("line")) + ": " + what);
                places.add(place(text(f.get("file")), number(use.get("line")), "uses", what, "rule", rule));
            }
            entry(jsonl, "forbidden-io", text(f.get("type")), "HIGH", null, null, "measured", places);
            findings.add(finding("forbidden-io", text(f.get("type")), "HIGH", "Direct I/O: " + text(f.get("type"))
                    + " (rule " + rule + ")", "In " + text(f.get("file")) + ", " + String.join("; ", where),
                    "arch_io_forbidden: " + text(f.get("type")) + " (rule " + rule + ")"));
        }
        for (Object entry : list(facts.get("cycles"))) {
            Map<String, Object> c = object(entry);
            String name = String.join("+", list(c.get("packages")).stream().map(String::valueOf).toList());
            Map<String, Object> weakest = object(c.get("weakest"));
            Map<String, Object> cut = new LinkedHashMap<>();
            cut.put("weakest", text(weakest.get("from")) + " -> " + text(weakest.get("to")) + " ("
                    + String.join(", ", list(weakest.get("types")).stream().map(String::valueOf).toList()) + ")");
            entry(jsonl, "package-cycle", name, "HIGH", null, null, "measured", List.of(cut));
            findings.add(finding("package-cycle", name, "HIGH", "Package cycle: " + name,
                    "The edge with the fewest types, the cheapest to cut: " + text(weakest.get("from")) + " -> "
                            + text(weakest.get("to")) + " ("
                            + String.join(", ", list(weakest.get("types")).stream().map(String::valueOf).toList()) + ")",
                    "arch_cycles: " + name));
        }
        for (Verdict v : stated) {
            String severity = v.severity().toUpperCase(Locale.ROOT);
            // The members by their verified simple names: the model's own wording of a member stays out
            List<String> members = v.members().stream().map(Report::simpleName).toList();
            String title = v.verdict() + ": " + (members.isEmpty() ? "the whole file" : String.join(", ", members));
            List<Map<String, Object>> places = new ArrayList<>();
            for (String quote : v.quotes()) {
                // A quote is "<line>: <code>", checked against the code the AI saw
                int colon = quote.indexOf(": ");
                Map<String, Object> place = new LinkedHashMap<>();
                place.put("file", v.file());
                if (colon > 0 && quote.substring(0, colon).matches("\\d{1,9}")) {
                    place.put("line", Long.parseLong(quote.substring(0, colon)));
                }
                place.put("quote", normalized(colon < 0 ? quote : quote.substring(colon + 2)));
                if (places.isEmpty()) {
                    // Once, with the first place: the members by their verified names, and under "model" what
                    // nothing checked: why, where to, and what a measured finding of the file is for
                    place.put("members", String.join(", ", members));
                    Map<String, Object> words = new LinkedHashMap<>();
                    words.put("reason", v.reason());
                    words.put("target", v.target());
                    if (!v.purpose().isEmpty()) words.put("purpose", v.purpose());
                    if (!v.change().isEmpty()) words.put("change", v.change());
                    place.put("model", words);
                }
                places.add(place);
            }
            entry(jsonl, "verdict-" + v.verdict(), v.file(), severity, itemOf.getOrDefault(v.id(), v.id()), v.id(),
                    "model", places);
            Map<String, Object> finding = finding("verdict-" + v.verdict(), v.file(), severity, title, "Candidate "
                    + v.id() + " of the architecture report; the reason and the target are in architecture-report.md",
                    evidence(v));
            if (itemOf.containsKey(v.id())) finding.put("item", itemOf.get(v.id()));
            findings.add(finding);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("snapshotComplete", complete);
        envelope.put("expectedFindings", (long) findings.size());
        envelope.put("findings", findings);
        if (!gone.isEmpty()) {
            // The open verdicts this run checked and no longer finds: only this resolves a model's judgement in the
            // register (docs/registers.md, "Reconciliation"). The rule and the file only, no word of a model
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Gone g : gone) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ruleKey", g.rule());
                entry.put("subject", g.subject());
                entries.add(entry);
            }
            envelope.put("gone", entries);
        }
        if (facts.get("items") instanceof List<?> listed) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index : listedItems(listed)) {
                Map<String, Object> item = object(listed.get(index));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", text(item.get("name")));
                out.put("digest", text(item.get("digest")));
                out.put("judged", judgedItems.contains(text(item.get("name"))));
                items.add(out);
            }
            // expectedItems vouches that the list is the whole one; a list in part goes without it
            if (listed.size() <= ITEMS_MAX) envelope.put("expectedItems", (long) items.size());
            envelope.put("items", items);
        }
        // "<" and ">" as escapes: a name of the workspace in it must not be able to end the comment
        return new Snapshot("<!-- traceworks:audit-result:v1 " + Json.write(envelope).replace("<", "\\u003c")
                .replace(">", "\\u003e") + " -->", jsonl.toString());
    }

    /**
     * The items the register calls settled and that this run left out, and all items: clean, decided, all. The facts
     * mark them: an item whose state was clean or decided and whose digest is the same. A clean item that changed
     * and got no place waits; it is not settled.
     */
    private static int[] settledItems(Map<String, Object> facts) {
        int clean = 0;
        int decided = 0;
        List<?> items = list(facts.get("items"));
        for (Object entry : items) {
            Map<String, Object> item = object(entry);
            if (!Boolean.TRUE.equals(item.get("settled"))) continue;
            if ("clean".equals(item.get("state"))) clean++;
            if ("decided".equals(item.get("state"))) decided++;
        }
        return new int[] {clean, decided, items.size()};
    }

    /**
     * Which items go into the envelope, by their place in the list, in the list's order. The register takes at most
     * {@link #ITEMS_MAX} items in a report and refuses a longer list as a whole. So a longer list is sent in part:
     * first the items of this run (a verdict names one of them), then the ones that wait for a verdict, then settled
     * ones, which the register knows already. A list in part goes without {@code expectedItems}: it vouches for
     * nothing, and no item leaves the register.
     */
    private static List<Integer> listedItems(List<?> items) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) order.add(i);
        if (items.size() <= ITEMS_MAX) return order;
        order.sort(java.util.Comparator.comparingInt((Integer i) -> {
            Map<String, Object> item = object(items.get(i));
            return Boolean.TRUE.equals(item.get("chosen")) ? 0 : Boolean.TRUE.equals(item.get("settled")) ? 2 : 1;
        }));
        List<Integer> kept = new ArrayList<>(order.subList(0, ITEMS_MAX));
        kept.sort(null);
        return kept;
    }

    /** The envelope, and the same findings as the lines of {@code findings.jsonl}. */
    private record Snapshot(String envelope, String findingsJsonl) {
    }

    /** A place of a measured finding: the file, the line, then names and values in pairs. */
    private static Map<String, Object> place(String file, long line, String... pairs) {
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("file", file);
        place.put("line", line);
        for (int i = 0; i < pairs.length; i += 2) place.put(pairs[i], pairs[i + 1]);
        return place;
    }

    /**
     * One line of {@code findings.jsonl}; {@code item} is the item of a verdict's file and {@code number} the number
     * its candidate has in this run, both {@code null} for a measurement.
     */
    private static void entry(StringBuilder jsonl, String ruleKey, String subject, String severity, String item,
                              String number, String source, List<Map<String, Object>> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ruleKey", ruleKey);
        entry.put("subject", subject);
        entry.put("severity", severity);
        if (item != null) entry.put("item", item);
        if (number != null) entry.put("number", number);
        entry.put("source", source);
        entry.put("details", details);
        jsonl.append(Json.write(entry)).append('\n');
    }

    private static Map<String, Object> finding(String ruleKey, String subject, String severity, String title,
                                               String description, String evidence) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("ruleKey", ruleKey);
        finding.put("subject", subject);
        finding.put("severity", severity);
        finding.put("title", title);
        finding.put("description", description);
        finding.put("evidence", evidence);
        return finding;
    }

    private static Verdict check(String id, String file, Map<String, Object> candidate, Map<String, Object> answer) {
        boolean measured = Boolean.TRUE.equals(candidate.get("measured"));
        String verdict = text(answer.get("verdict")).strip().toLowerCase(Locale.ROOT);
        String severity = text(answer.get("severity")).strip().toLowerCase(Locale.ROOT);
        String reason = text(answer.get("reason"));
        List<String> members = new ArrayList<>();
        for (Object m : list(answer.get("members"))) members.add(text(m));
        String target = text(answer.get("target")).strip();
        String purpose = text(answer.get("purpose")).strip();
        String change = text(answer.get("change")).strip();
        List<String> quotes = new ArrayList<>();
        String problem = null;

        Map<Integer, String> code = numberedLines(text(candidate.get("code")));
        Set<String> names = memberNames(candidate);
        List<?> evidence = list(answer.get("evidence"));
        if (!VERDICTS.contains(verdict)) {
            problem = "the verdict '" + shown(verdict) + "' is not fine, split or move";
        } else if (evidence.isEmpty()) {
            problem = "no evidence: the verdict quotes no line";
        }
        for (Object entry : evidence) {
            if (problem != null) break;
            if (!(entry instanceof Map<?, ?> raw)) {
                problem = "a piece of evidence is no object";
                break;
            }
            Map<String, Object> e = object(raw);
            Integer line = lineNumber(e.get("line"));
            String quote = text(e.get("quote"));
            quotes.add((line == null ? "?" : line) + ": " + quote);
            String collapsed = normalized(quote);
            if (line == null) {
                problem = "a quote has no line number";
            } else if (!code.containsKey(line)) {
                problem = "line " + line + " is not in the code the AI saw";
            } else if (collapsed.isEmpty()) {
                problem = "the evidence for line " + line + " has no quote";
            } else {
                String codeLine = normalized(code.get(line));
                if (!codeLine.contains(collapsed)) {
                    problem = "the quote does not occur in line " + line;
                } else if (collapsed.length() < MIN_QUOTE && !collapsed.equals(codeLine)) {
                    problem = "the quote for line " + line + " is shorter than " + MIN_QUOTE
                            + " characters and not the whole line";
                }
            }
        }
        if (problem == null && verdict.equals("split") && members.isEmpty()) {
            problem = "a split names no member that moves";
        }
        if (problem == null && !verdict.equals("fine")) {
            for (String member : members) {
                if (!names.contains(simpleName(member))) {
                    problem = "the member '" + shown(member) + "' is not in the file";
                    break;
                }
            }
            if (problem == null && target.isEmpty()) problem = "a " + verdict + " names no target";
            if (problem == null && !SEVERITIES.contains(severity)) {
                problem = "the severity '" + shown(severity) + "' of the " + verdict + " is not high, medium or low";
            }
        }
        if (problem == null && measured && (purpose.isEmpty() || change.isEmpty())) {
            problem = "the file has a measured finding, and the verdict does not say what it is for and the smallest "
                    + "change that removes it";
        }
        return new Verdict(id, file, measured, verdict, verdict.equals("fine") ? "" : severity, reason,
                List.copyOf(members), target, purpose, change, List.copyOf(quotes), problem);
    }

    /** The simple names of the file's members, as the candidate line lists them. */
    private static Set<String> memberNames(Map<String, Object> candidate) {
        Set<String> names = new HashSet<>();
        for (Object m : list(candidate.get("members"))) {
            if (m instanceof Map<?, ?> member) names.add(text(member.get("name")));
        }
        return names;
    }

    /**
     * The simple name a model's member reference means: without parameters, without a class or {@code #} before it
     * and without {@code new}, so {@code start}, {@code start(Request)}, {@code RunServices.start} and
     * {@code RunServices#start()} all mean {@code start}.
     */
    static String simpleName(String member) {
        String name = member.strip();
        int paren = name.indexOf('(');
        if (paren >= 0) name = name.substring(0, paren);
        if (name.startsWith("new ")) name = name.substring(4);
        name = name.strip();
        int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('#'));
        return cut >= 0 ? name.substring(cut + 1) : name;
    }

    /**
     * The gate follows the items: RED for a layer violation, I/O the rule file forbids, or while an open verdict of
     * high severity stands; YELLOW for a package cycle, while an item is open or not judged, and for what keeps a
     * measurement or a verdict from being whole (a candidate shown in part, a file not read, an unresolved name of a
     * source type, no Java file read); GREEN when every item is settled.
     */
    private static String gate(States.Counts states, List<String> partial, Map<String, Object> facts) {
        if (!list(facts.get("layerViolations")).isEmpty() || forbiddenIo(facts) > 0 || states.highStands()) return "RED";
        if (!list(facts.get("cycles")).isEmpty() || !states.allSettled() || !partial.isEmpty()
                || !list(facts.get("knownUnowned")).isEmpty() || number(facts.get("knownMissing")) > 0
                || !list(facts.get("notRead")).isEmpty() || !list(facts.get("unresolvedSourceNames")).isEmpty()
                || number(facts.get("javaFiles")) == 0) {
            return "YELLOW";
        }
        return "GREEN";
    }

    private static long forbiddenIo(Map<String, Object> facts) {
        return list(facts.get("io")).stream().filter(f -> f instanceof Map<?, ?> m && !Boolean.TRUE.equals(m.get("allowed")))
                .count();
    }

    private static String description(List<Verdict> stated, List<Verdict> unverified, List<NotJudged> notJudged,
                                      List<String> partial, int candidates, int judged, Map<String, Object> facts,
                                      List<Verdict> low, States.Counts states) {
        List<String> parts = new ArrayList<>();
        int violations = list(facts.get("layerViolations")).size();
        long io = forbiddenIo(facts);
        int cycles = list(facts.get("cycles")).size();
        if (violations > 0) parts.add(count(violations, "layer violation"));
        if (io > 0) parts.add(count((int) io, "type") + " with I/O the rule file forbids");
        if (cycles > 0) parts.add(count(cycles, "package cycle"));
        if (violations == 0 && io == 0 && cycles == 0) parts.add("no measured finding");
        long split = stated.stream().filter(v -> v.verdict().equals("split")).count();
        long move = stated.stream().filter(v -> v.verdict().equals("move")).count();
        parts.add(split + " split and " + move + " move verified");
        parts.add(judged + " of " + candidates + " candidates judged");
        if (!unverified.isEmpty()) parts.add(count(unverified.size(), "unverified verdict"));
        if (!partial.isEmpty()) parts.add(count(partial.size(), "candidate") + " shown in part");
        int notRead = list(facts.get("notRead")).size();
        int cut = list(facts.get("cut")).size();
        int unresolved = list(facts.get("unresolvedSourceNames")).size();
        if (number(facts.get("javaFiles")) == 0) parts.add("no Java file read");
        if (notRead > 0) parts.add(count(notRead, "file") + " not read");
        if (cut > 0) parts.add(count(cut, "candidate") + " cut by the cap");
        if (unresolved > 0) parts.add(count(unresolved, "unresolved name") + " of a source type");
        if (!low.isEmpty()) parts.add(count(low.size(), "low verdict") + " in this report only");
        int unowned = list(facts.get("knownUnowned")).size();
        if (unowned > 0) parts.add(count(unowned, "open verdict") + " of the register that no item carries");
        int missing = (int) number(facts.get("knownMissing"));
        if (missing > 0) parts.add(count(missing, "finding") + " of the register not shown to this run");
        parts.add(states.text());
        return String.join("; ", parts);
    }

    private static String markdown(List<Verdict> verified, List<Verdict> unverified, List<Verdict> low,
                                   List<NotJudged> notJudged,
                                   List<Map<String, Object>> candidates, int judged, Map<String, Object> facts,
                                   String gate, String description, States.Counts states) {
        StringBuilder md = new StringBuilder();
        md.append("# Architecture self-audit\n\n");
        md.append("**Gate:** ").append(gate).append(": ").append(plain(description)).append("\n\n");
        md.append("A deterministic tool measured the package graph, the layer rules, direct I/O, sizes and complexity ")
                .append("from the sources, and chose the candidates below; an AI judged each candidate with its code ")
                .append("and a computed context in the prompt and no tools; a verdict counts only when its evidence ")
                .append("holds.\n\n");
        md.append("## Files of this run\n\n")
                .append("| File | Read it for |\n|---|---|\n")
                .append("| `architecture-report.md` | this report: the gate, the measured findings and the verified ")
                .append("verdicts; the one file to read |\n")
                .append("| `architecture-facts.json` | the whole measurement: every package, dependency, file and ")
                .append("complex member, the rules checked, and how the candidates were chosen |\n")
                .append("| `candidates.jsonl` | what the AI saw for each candidate: the numbered code, the computed ")
                .append("context and the members, one line per candidate |\n\n");

        Map<String, Object> limits = facts.get("limits") instanceof Map<?, ?> l ? object(l) : Map.of();
        long forbidden = forbiddenIo(facts);
        md.append("## Coverage\n\n| | |\n|---|---|\n");
        row(md, "Java files read", String.valueOf(number(facts.get("javaFiles"))));
        row(md, "Files not read", String.valueOf(list(facts.get("notRead")).size()));
        row(md, "Packages", String.valueOf(list(facts.get("packages")).size()));
        row(md, "Package dependencies", String.valueOf(list(facts.get("edges")).size()));
        row(md, "Package cycles", String.valueOf(list(facts.get("cycles")).size()));
        row(md, "Layer violations", String.valueOf(list(facts.get("layerViolations")).size()));
        row(md, "Types with forbidden I/O", String.valueOf(forbidden));
        row(md, "Types with documented I/O", String.valueOf(list(facts.get("io")).size() - forbidden));
        long ncloc = 0;
        long large = 0;
        for (Object f : list(facts.get("files"))) {
            long n = number(object(f).get("ncloc"));
            ncloc += n;
            if (n >= number(limits.get("largeNcloc"))) large++;
        }
        row(md, "Non-comment lines", String.valueOf(ncloc));
        row(md, "Files of at least " + number(limits.get("largeNcloc")) + " non-comment lines", String.valueOf(large));
        row(md, "Candidates", String.valueOf(candidates.size()));
        row(md, "Candidates judged", judged + " of " + candidates.size());
        row(md, "Candidates shown in part", String.valueOf(candidates.stream()
                .filter(c -> !"whole".equals(text(c.get("shown")))).count()));
        row(md, "Candidates cut by the cap", String.valueOf(list(facts.get("cut")).size()));
        if (facts.get("items") instanceof List<?>) {
            int[] settled = settledItems(facts);
            row(md, "Items (every file that could be judged)", String.valueOf(settled[2]));
            row(md, "Items settled and unchanged, not judged again", (settled[0] + settled[1]) + " (" + settled[0]
                    + " clean, " + settled[1] + " decided)");
            row(md, "Items that wait for a later run", String.valueOf(list(facts.get("cut")).size()));
            row(md, "Items after this run", states.text());
            if (settled[2] > ITEMS_MAX) {
                row(md, "Items listed for the register", ITEMS_MAX + " of " + settled[2] + ": it takes at most "
                        + ITEMS_MAX + " in a report, the ones of this run first, and no item leaves it by this report");
            }
        }
        List<?> unresolved = list(facts.get("unresolvedSourceNames"));
        row(md, "Unresolved names that a source type has", unresolved.isEmpty() ? "none"
                : String.join(", ", unresolved.stream().map(n -> code(String.valueOf(n))).toList()));

        md.append("\n## Measured findings\n\n");
        List<?> violations = list(facts.get("layerViolations"));
        List<?> cycles = list(facts.get("cycles"));
        if (violations.isEmpty() && forbidden == 0 && cycles.isEmpty()) md.append("None.\n\n");
        for (Object entry : violations) {
            Map<String, Object> v = object(entry);
            md.append("### Layer violation: ").append(code(text(v.get("from")))).append(" -> ")
                    .append(code(text(v.get("to")))).append(" (rule ").append(ruleId(text(v.get("rule"))))
                    .append(")\n\n");
            for (Object s : list(v.get("sites"))) sites(md, object(s));
            md.append("\n");
        }
        for (Object entry : list(facts.get("io"))) {
            Map<String, Object> f = object(entry);
            if (Boolean.TRUE.equals(f.get("allowed"))) continue;
            md.append("### Direct I/O: ").append(code(text(f.get("type")))).append(" (rule ")
                    .append(ruleId(text(f.get("rule")))).append(")\n\n");
            md.append("In ").append(code(text(f.get("file")))).append(":\n\n");
            uses(md, list(f.get("uses")));
            md.append("\n");
        }
        for (Object entry : cycles) {
            Map<String, Object> c = object(entry);
            List<String> packages = list(c.get("packages")).stream().map(String::valueOf).toList();
            md.append("### Package cycle: ").append(code(String.join("+", packages))).append("\n\n");
            for (Object e : list(c.get("edges"))) {
                Map<String, Object> edge = object(e);
                List<?> types = list(edge.get("types"));
                md.append("- ").append(code(text(edge.get("from")))).append(" -> ").append(code(text(edge.get("to"))))
                        .append(": ").append(count(types.size(), "type")).append(" (")
                        .append(String.join(", ", types.stream().limit(MAX_TABLE).map(t -> code(String.valueOf(t)))
                                .toList())).append(types.size() > MAX_TABLE ? ", ..." : "").append(")\n");
            }
            Map<String, Object> weakest = object(c.get("weakest"));
            md.append("\nThe edge with the fewest types, the cheapest to cut: ").append(code(text(weakest.get("from"))))
                    .append(" -> ").append(code(text(weakest.get("to")))).append(".\n\n");
        }

        md.append("## Verdicts\n\n");
        List<Verdict> changes = verified.stream().filter(v -> !v.verdict().equals("fine") && !low.contains(v)).toList();
        List<Verdict> fine = verified.stream().filter(v -> v.verdict().equals("fine")).toList();
        if (verified.isEmpty()) md.append("No verified verdict.\n\n");
        int n = 0;
        for (Verdict v : changes) {
            n++;
            md.append("### ").append(n).append(". ").append(v.verdict()).append(", ").append(v.severity()).append(", ")
                    .append(v.id()).append(", ").append(code(v.file())).append("\n\n");
            md.append(plain(v.reason())).append("\n\n");
            if (!v.members().isEmpty()) {
                md.append("**Members that move:** ").append(String.join(", ", v.members().stream().map(Report::code)
                        .toList())).append("\n\n");
            }
            md.append("**Target:** ").append(code(v.target())).append("\n\n");
            measuredLines(md, v);
            md.append("**Evidence:**\n\n");
            for (String quote : v.quotes()) md.append("- ").append(code(quote)).append("\n");
            md.append("\n");
        }
        if (!fine.isEmpty()) {
            md.append("### Fine as they are\n\n");
            for (Verdict v : fine) {
                md.append("- ").append(v.id()).append(" ").append(code(v.file()))
                        .append(v.reason().isBlank() ? "" : ": " + plain(v.reason())).append("\n");
                if (v.measured()) {
                    md.append("  - **What the measured finding is for:** ").append(plain(v.purpose())).append("\n");
                    md.append("  - **Smallest change that removes it:** ").append(plain(v.change())).append("\n");
                }
            }
            md.append("\n");
        }
        if (!low.isEmpty()) {
            md.append("### Low, in this report only\n\nThe verdict rated each of them low: a matter of reading, with ")
                    .append("nothing that relies on it. They passed the same check as the others. They are not in the ")
                    .append("register and not in findings.jsonl, they do not count for the gate, and no later run is ")
                    .append("asked about them.\n\n");
            for (Verdict v : low) {
                md.append("- ").append(v.verdict()).append(", ").append(v.id()).append(" ").append(code(v.file()))
                        .append(v.reason().isBlank() ? "" : ": " + plain(v.reason())).append("\n");
                if (!v.members().isEmpty()) {
                    md.append("  - **Members that move:** ").append(String.join(", ", v.members().stream()
                            .map(Report::code).toList())).append("\n");
                }
                md.append("  - **Target:** ").append(code(v.target())).append("\n");
            }
            md.append("\n");
        }
        if (!unverified.isEmpty()) {
            md.append("## Unverified verdicts\n\nTheir evidence does not hold, so they do not count. ")
                    .append("They may still point at something; read the file before you drop them.\n\n");
            for (Verdict v : unverified) {
                md.append("- ").append(v.id()).append(" ").append(code(v.file())).append(" (")
                        .append(plain(v.verdict())).append("): ").append(plain(v.problem())).append(". ")
                        .append(plain(v.reason())).append("\n");
            }
            md.append("\n");
        }
        if (!notJudged.isEmpty()) {
            md.append("## Candidates not judged\n\n");
            for (NotJudged c : notJudged) {
                md.append("- ").append(c.id()).append(" ").append(code(c.file())).append(": ").append(plain(c.reason()))
                        .append("\n");
            }
            md.append("\n");
        }
        List<Map<String, Object>> partial = candidates.stream().filter(c -> !"whole".equals(text(c.get("shown"))))
                .toList();
        if (!partial.isEmpty()) {
            md.append("## Candidates shown in part\n\n");
            for (Map<String, Object> c : partial) {
                md.append("- ").append(text(c.get("id"))).append(" ").append(code(text(c.get("file")))).append(": ")
                        .append(plain(text(c.get("shown")))).append("\n");
            }
            md.append("\n");
        }
        List<?> notRead = list(facts.get("notRead"));
        if (!notRead.isEmpty()) {
            md.append("## Files not read\n\n");
            for (Object entry : notRead) {
                Map<String, Object> file = object(entry);
                md.append("- ").append(code(text(file.get("file")))).append(": ").append(plain(text(file.get("reason"))))
                        .append("\n");
            }
            md.append("\n");
        }
        List<?> cut = list(facts.get("cut"));
        if (!cut.isEmpty()) {
            md.append("## Candidates cut by the cap\n\nThey need a verdict and got no place in this run. They wait ")
                    .append("for a later run.\n\n");
            for (Object entry : cut) {
                Map<String, Object> c = object(entry);
                md.append("- ").append(code(text(c.get("file")))).append(": ")
                        .append(plain(String.join("; ", list(c.get("reasons")).stream().map(String::valueOf).toList())))
                        .append("\n");
            }
            md.append("\n");
        }

        md.append("## Candidates\n\n| Id | File | Why |\n|---|---|---|\n");
        for (Map<String, Object> c : candidates) {
            List<String> reasons = new ArrayList<>();
            for (Object r : list(c.get("reasons"))) reasons.add(r instanceof Map<?, ?> m ? text(m.get("text")) : text(r));
            md.append("| ").append(text(c.get("id"))).append(" | ").append(code(text(c.get("file")))).append(" | ")
                    .append(plain(String.join("; ", reasons))).append(" |\n");
        }
        md.append("\n");

        List<Map<String, Object>> documented = new ArrayList<>();
        for (Object entry : list(facts.get("io"))) {
            Map<String, Object> f = object(entry);
            if (Boolean.TRUE.equals(f.get("allowed"))) documented.add(f);
        }
        if (!documented.isEmpty()) {
            md.append("## Documented I/O\n\nThe rule file allows these, as the documentation names them.\n\n");
            for (Map<String, Object> f : documented) {
                Set<String> whats = new LinkedHashSet<>();
                for (Object u : list(f.get("uses"))) {
                    for (Object w : list(object(u).get("what"))) whats.add(String.valueOf(w));
                }
                md.append("- ").append(code(text(f.get("type")))).append(" (").append(ruleId(text(f.get("rule"))))
                        .append("): ").append(String.join(", ", whats.stream().map(Report::code).toList()))
                        .append(". ").append(plain(text(f.get("reason")))).append("\n");
            }
            md.append("\n");
        }
        List<?> stale = list(facts.get("staleAllows"));
        List<?> unusedPatterns = list(facts.get("unusedPatterns"));
        if (!stale.isEmpty() || !unusedPatterns.isEmpty()) {
            md.append("## Notes on the rule file\n\n");
            for (Object s : stale) md.append("- An allow that matches no I/O: ").append(code(String.valueOf(s))).append("\n");
            for (Object s : unusedPatterns) {
                md.append("- A pattern that matches no package: ").append(code(String.valueOf(s))).append("\n");
            }
            md.append("\n");
        }

        md.append("## Measurements\n\n### Largest files\n\n| Non-comment lines | Lines | Most complex member | File |\n")
                .append("|---|---|---|---|\n");
        List<Map<String, Object>> files = new ArrayList<>();
        for (Object f : list(facts.get("files"))) files.add(object(f));
        files.sort((a, b) -> Long.compare(number(b.get("ncloc")), number(a.get("ncloc"))));
        for (Map<String, Object> f : files.subList(0, Math.min(MAX_TABLE, files.size()))) {
            md.append("| ").append(number(f.get("ncloc"))).append(" | ").append(number(f.get("lines"))).append(" | ")
                    .append(number(f.get("maxComplexity"))).append(" | ").append(code(text(f.get("file")))).append(" |\n");
        }
        md.append("\n### Most complex members\n\n| Cognitive complexity | Member | Lines | File |\n|---|---|---|---|\n");
        List<?> complex = list(facts.get("complexMembers"));
        for (Object entry : complex.subList(0, Math.min(MAX_TABLE, complex.size()))) {
            Map<String, Object> m = object(entry);
            md.append("| ").append(number(m.get("complexity"))).append(" | ").append(code(text(m.get("member"))))
                    .append(" | ").append(number(m.get("start"))).append("-").append(number(m.get("end"))).append(" | ")
                    .append(code(text(m.get("file")))).append(" |\n");
        }
        md.append("\n### Package dependencies\n\n");
        Map<String, List<String>> byFrom = new java.util.TreeMap<>();
        for (Object e : list(facts.get("edges"))) {
            Map<String, Object> edge = object(e);
            byFrom.computeIfAbsent(text(edge.get("from")), k -> new ArrayList<>()).add(text(edge.get("to")));
        }
        byFrom.forEach((from, tos) -> md.append("- ").append(code(from)).append(" -> ")
                .append(String.join(", ", tos.stream().map(Report::code).toList())).append("\n"));
        return md.toString();
    }

    /** Whether an open finding of the register that no item carries is high: it stands like any other. */
    private static boolean unownedHigh(Map<String, Object> facts) {
        return list(facts.get("knownUnowned")).stream().anyMatch(u -> u instanceof Map<?, ?> finding
                && HIGH.contains(String.valueOf(finding.get("severity"))));
    }

    /** The open findings no item carries, for the end of the report; empty when there are none. */
    private static String unowned(Map<String, Object> facts, String findings, String what, String key) {
        List<?> unowned = list(facts.get("knownUnowned"));
        if (unowned.isEmpty()) return "";
        StringBuilder md = new StringBuilder("\n## Open " + findings + " that no item carries\n\nOpen in the register, "
                + "and no " + what + " of this run is what they are about, nor could the tool tell that it is gone, so "
                + "no run asks about them. A person must decide them: `traceworks findings " + key + " dismiss <id>` or "
                + "`accept <id>`.\n\n");
        for (Object entry : unowned) {
            Map<String, Object> finding = object(entry);
            md.append("- ").append(plain(text(finding.get("severity")))).append(' ')
                    .append(text(finding.get("ruleKey")).matches("[a-z][a-z0-9_-]*") ? text(finding.get("ruleKey"))
                            : plain(text(finding.get("ruleKey")))).append(' ')
                    .append(code(text(finding.get("subject")))).append("\n");
        }
        return md.toString();
    }

    private static void measuredLines(StringBuilder md, Verdict v) {
        if (!v.measured()) return;
        md.append("**What the measured finding is for:** ").append(plain(v.purpose())).append("\n\n");
        md.append("**Smallest change that removes it:** ").append(plain(v.change())).append("\n\n");
    }

    private static void sites(StringBuilder md, Map<String, Object> site) {
        md.append("- ").append(code(text(site.get("type")))).append(" in ").append(code(text(site.get("file"))))
                .append(":\n");
        for (Object u : list(site.get("lines"))) {
            Map<String, Object> use = object(u);
            md.append("  - line ").append(number(use.get("line"))).append(": ")
                    .append(String.join(", ", list(use.get("what")).stream().map(w -> code(String.valueOf(w))).toList()))
                    .append("\n");
        }
    }

    private static void uses(StringBuilder md, List<?> uses) {
        for (Object u : uses) {
            Map<String, Object> use = object(u);
            md.append("- line ").append(number(use.get("line"))).append(": ")
                    .append(String.join(", ", list(use.get("what")).stream().map(w -> code(String.valueOf(w))).toList()))
                    .append("\n");
        }
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    /** A name from the workspace as a code span on one line: no Markdown inside it takes effect. */
    static String code(String text) {
        String line = normalized(text);
        String ticks = "`".repeat(longestBackticks(line) + 1);
        // A space keeps a backtick at either end from joining the fence; Markdown drops one space on each side
        String pad = line.startsWith("`") || line.endsWith("`") ? " " : "";
        return ticks + pad + line + pad + ticks;
    }

    /** Model text as plain text on one line: it cannot start a heading, a list, a link, an image or raw HTML. */
    static String plain(String text) {
        return normalized(text).replaceAll("([\\\\`*_\\[\\]()!#|<>~{}+-])", "\\\\$1");
    }

    /** A rule id as it is, which its form keeps harmless; anything else as plain text. */
    private static String ruleId(String rule) {
        return rule.matches("[a-z][a-z0-9_]*") ? rule : plain(rule);
    }

    private static int longestBackticks(String text) {
        int longest = 0;
        Matcher run = Pattern.compile("`+").matcher(text);
        while (run.find()) longest = Math.max(longest, run.group().length());
        return longest;
    }

    /**
     * Text on one line: format characters (zero-width ones) removed, every blank, control character and line or
     * paragraph separator a single space. The evidence check compares quote and code line this way.
     */
    static String normalized(String text) {
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

    /** At most {@value #MAX_SHOWN} characters of a text on one line, never cutting a character in two. */
    private static String shown(String text) {
        String line = normalized(text);
        if (line.length() <= MAX_SHOWN) return line;
        int end = Character.isHighSurrogate(line.charAt(MAX_SHOWN - 1)) ? MAX_SHOWN - 1 : MAX_SHOWN;
        return line.substring(0, end) + "...";
    }

    /** The report as UTF-8; a lone surrogate from a model's answer becomes a replacement character, never an error. */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<Integer, String> numberedLines(String code) {
        Map<Integer, String> lines = new HashMap<>();
        for (String line : code.split("\n", -1)) {
            Matcher m = NUMBERED.matcher(line);
            if (m.matches()) lines.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        return lines;
    }

    private static Integer lineNumber(Object value) {
        if (value instanceof Long l && l > 0 && l <= Integer.MAX_VALUE) return l.intValue();
        if (value instanceof String s && s.strip().matches("\\d{1,9}")) return Integer.parseInt(s.strip());
        return null;
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String text(Object value) {
        return value instanceof String s ? s : value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Long l ? l : value instanceof Integer i ? i : 0;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("not a JSON object: " + shown(String.valueOf(value)));
    }
}
