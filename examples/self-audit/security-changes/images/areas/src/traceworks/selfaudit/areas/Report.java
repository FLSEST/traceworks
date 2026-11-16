package traceworks.selfaudit.areas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the AI's verdicts against the areas they judged, and writes the report and the gate.
 *
 * <p>The verdicts are the output of the {@code foreach} over the lines of {@code areas.jsonl}: each item as
 * {@code [<item>]: } followed by its answer. The answer of area <em>i</em> is the text between its marker and the next
 * marker found. The engine writes each marker once, and each line holds a token of the run that no prompt shows, so a
 * model cannot write one; a marker that occurs twice was copied all the same, and its area is not judged. An area
 * without a marker, without a JSON object that has an {@code area}, with an answer for another area, or without a
 * {@code findings} list is <em>not judged</em>. A finding's rule is shown only when it is a rule id of the rule file,
 * else as {@code other}.
 *
 * <p>A finding is <em>verified</em> when its severity is high, medium or low, its line lies inside the area, and its
 * quote, with blanks collapsed, occurs in that line of the code the AI saw and has at least
 * {@value #MIN_QUOTE} characters or is the whole line. Every other finding is <em>unverified</em>, with the reason, and
 * does not count.
 *
 * <p>The register of the audit keeps a model's finding open until a run says that it is gone (docs/registers.md,
 * "Reconciliation"). So an area line carries the open findings the register knows of it ({@code known}, each with an
 * id), and the answer has to settle each in its {@code known} list: {@code holds}, with a line and a quote that are
 * checked like a new finding's, which states the finding again under its rule; or {@code gone}, with a reason, which
 * puts it into the envelope's {@code gone} list. A known finding without such an answer is <em>not answered</em>:
 * neither stated nor gone, so the register keeps it open. What {@code find} could match to no area
 * ({@code knownGone} of the summary) is gone without a model.
 *
 * <p>The gate follows the items ({@link States}): every area is one, and its state after the run is clean, decided,
 * open or not judged. RED while an open finding of high severity stands: a verified high one, or a high one of the
 * register that this run did not answer as gone. YELLOW while an item is open or not judged, a file was not read, or
 * no Java file was read at all. GREEN when every item is settled.
 *
 * <p>The report of a check of the changes (a summary with {@code changes}, which {@code find --changes} writes) says
 * so in its title and in the gate's description, names the base, and counts what was looked at and what was not;
 * each area is listed with the reason it was in. Its items are the areas this run picked and the ones the register
 * knew, so its gate speaks about those and about no other area of the project.
 */
final class Report {

    static final int MIN_QUOTE = 8;

    private static final Set<String> SEVERITIES = Set.of("high", "medium", "low");
    // DOTALL: a code line may hold a line or paragraph separator, which is legal in a comment or a string
    private static final Pattern NUMBERED = Pattern.compile("(\\d+)\\| ?(.*)", Pattern.DOTALL);
    private static final int MAX_SHOWN = 120;
    private static final Set<String> HIGH = Set.of("HIGH", "CRITICAL");

    /** What the register takes in one report (docs/registers.md, "Items"). */
    static final int ITEMS_MAX = 1000;

    /** A finding of the AI; {@code problem} is {@code null} for a verified one, else why its evidence does not hold. */
    record Finding(String area, String subject, String file, String member, String rule, String severity, Integer line,
                   String quote, String issue, String fix, String problem) {
    }

    /** An area without a usable answer. */
    record NotJudged(String id, String subject, String reason) {
    }

    /**
     * What the check found, the gate, the report, and the envelope: the audit result envelope v1 that a register
     * reads (docs/registers.md), with the verified findings; and {@code findingsJsonl}, the same findings as data with
     * the model's words, one JSON object per finding of the envelope and line.
     */
    record Outcome(List<Finding> verified, List<Finding> unverified, List<NotJudged> notJudged, int areas, int judged,
                   String gate, String description, String markdown, String envelope, String findingsJsonl,
                   List<Gone> gone, List<String> notAnswered, List<Finding> low) {
    }

    /**
     * An open finding of an earlier run that is gone: the answer for its area said so, with a reason, or no area has
     * its subject any more ({@code area} is empty then).
     */
    record Gone(String rule, String subject, String area, String reason) {
    }

    /** An answer that calls an open finding gone, before it is known whether the answer can be taken. */
    private record SaidGone(Gone gone, String knownId, boolean high) {
    }

    /** An open finding of the register that got no usable answer under "known": its area, its rule, the line for the report. */
    private record Unanswered(String area, String rule, String text, boolean high) {
    }

    private Report() {
    }

    /**
     * Checks {@code verdicts} against the areas.
     *
     * @param areaLines the lines of {@code areas.jsonl}, exactly as the {@code foreach} saw them
     * @param summary   {@code areas-summary.json}
     * @param verdicts  the output of the {@code foreach}
     */
    static Outcome of(List<String> areaLines, Map<String, Object> summary, String verdicts) {
        List<Map<String, Object>> areas = new ArrayList<>();
        for (String line : areaLines) areas.add(object(Json.parse(line)));

        // Every place where a marker occurs; an area's answer runs to the next of them
        int[] markers = new int[areaLines.size()];
        int[] counts = new int[areaLines.size()];
        TreeSet<Integer> occurrences = new TreeSet<>();
        for (int i = 0; i < areaLines.size(); i++) {
            String marker = "[" + areaLines.get(i) + "]: ";
            markers[i] = verdicts.indexOf(marker);
            for (int at = markers[i]; at >= 0; at = verdicts.indexOf(marker, at + 1)) {
                counts[i]++;
                occurrences.add(at);
            }
        }
        Set<String> ruleIds = new HashSet<>();
        for (Object id : list(summary.get("rules"))) ruleIds.add(String.valueOf(id));

        List<Finding> verified = new ArrayList<>();
        List<Finding> unverified = new ArrayList<>();
        List<NotJudged> notJudged = new ArrayList<>();
        List<Gone> calledGone = new ArrayList<>();
        List<String> notAnswered = new ArrayList<>();
        // The new findings a verdict rates low: in the report of the run, and nowhere else
        List<Finding> low = new ArrayList<>();
        // For the state of the items after the run: the areas with an open finding of the register that got no
        // answer, the ones with one answered as gone, and whether an unanswered one is high
        Set<String> unanswered = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        boolean highUnanswered = false;
        List<SaidGone> saidGone = new ArrayList<>();
        List<Unanswered> knownWithoutAnswer = new ArrayList<>();
        for (int i = 0; i < areas.size(); i++) {
            Map<String, Object> area = areas.get(i);
            String id = text(area.get("id"));
            String subject = text(area.get("subject"));
            if (markers[i] < 0) {
                notJudged.add(new NotJudged(id, subject, "no answer in the verdicts: the loop did not reach the area"));
                continue;
            }
            if (counts[i] > 1) {
                notJudged.add(new NotJudged(id, subject,
                        "its marker occurs " + counts[i] + " times in the verdicts: an answer copied it"));
                continue;
            }
            int start = markers[i] + areaLines.get(i).length() + 4;
            Integer next = occurrences.higher(markers[i]);
            String answer = verdicts.substring(start, next == null ? verdicts.length() : Math.max(start, next));
            var verdict = Json.firstObject(answer, "area");
            if (verdict.isEmpty()) {
                notJudged.add(new NotJudged(id, subject, "no JSON answer with an \"area\": " + shown(answer)));
                continue;
            }
            if (!id.equals(text(verdict.get().get("area")))) {
                notJudged.add(new NotJudged(id, subject, "the answer is for area " + shown(text(verdict.get().get("area")))));
                continue;
            }
            if (!(verdict.get().get("findings") instanceof List<?> findings)) {
                notJudged.add(new NotJudged(id, subject, "the answer has no findings list"));
                continue;
            }
            Map<Integer, String> code = numberedLines(text(area.get("code")));
            int first = number(area.get("start"));
            int last = number(area.get("end"));
            // A finding of a rule the register holds an open finding of in this area is that finding, not a new one
            Set<String> openRules = new HashSet<>();
            for (Object entry : list(area.get("known"))) openRules.add(text(object(entry).get("rule")));
            for (Object entry : findings) {
                Finding finding = check(id, subject, area, entry, code, first, last, ruleIds, null);
                if (finding.problem() == null && finding.severity().equals("low") && !openRules.contains(finding.rule())) {
                    // The register and the gate take medium and high: the area is judged, without this finding
                    low.add(finding);
                } else {
                    (finding.problem() == null ? verified : unverified).add(finding);
                }
            }
            // The open findings the register knows of this area: each needs an answer of its own, by its id
            Map<String, Map<String, Object>> answers = new HashMap<>();
            for (Object entry : list(verdict.get().get("known"))) {
                if (entry instanceof Map<?, ?> raw) answers.putIfAbsent(text(raw.get("id")).strip(), object(raw));
            }
            for (Object entry : list(area.get("known"))) {
                Map<String, Object> known = object(entry);
                String knownId = text(known.get("id"));
                String rule = text(known.get("rule"));
                Map<String, Object> settled = answers.get(knownId);
                String status = settled == null ? "" : text(settled.get("status")).strip().toLowerCase(Locale.ROOT);
                if (status.equals("holds")) {
                    // Stated again under the rule the register knows it by, after the check a new finding gets
                    Finding finding = check(id, subject, area, settled, code, first, last, ruleIds, rule);
                    (finding.problem() == null ? verified : unverified).add(finding);
                    if (finding.problem() == null) continue;
                } else if (status.equals("gone")) {
                    saidGone.add(new SaidGone(new Gone(rule, subject, id, text(settled.get("reason"))), knownId,
                            HIGH.contains(text(known.get("severity")))));
                    continue;
                }
                knownWithoutAnswer.add(new Unanswered(id, rule, id + " " + knownId + " " + rule,
                        HIGH.contains(text(known.get("severity")))));
            }
        }
        // "Gone" from an answer that also held a finding that did not verify is not taken. The register would resolve
        // the finding and keep the earlier verdict of the area, with the digest the area still has: the area would
        // read decided, and no run would ask about it again. The finding stays open, and the next run asks
        Set<String> withUnverified = new HashSet<>();
        for (Finding f : unverified) withUnverified.add(f.area());
        for (SaidGone said : saidGone) {
            String id = said.gone().area();
            if (!withUnverified.contains(id)) {
                calledGone.add(said.gone());
                resolved.add(id);
                continue;
            }
            knownWithoutAnswer.add(new Unanswered(id, said.gone().rule(), id + " " + said.knownId() + " "
                    + said.gone().rule() + ": the answer said that it is gone; that is not taken, since the same answer"
                    + " held a finding that did not verify", said.high()));
        }
        // A finding of the same rule that the answer states under "findings" is the register's finding, stated again
        // with the severity the answer gives: it got its answer, and is not one that stands as the register has it
        Set<String> statedRules = new HashSet<>();
        for (Finding f : verified) statedRules.add(f.area() + "\n" + f.rule());
        for (Unanswered open : knownWithoutAnswer) {
            if (statedRules.contains(open.area() + "\n" + open.rule())) continue;
            notAnswered.add(open.text());
            unanswered.add(open.area());
            highUnanswered |= open.high();
        }
        for (Object entry : list(summary.get("knownGone"))) {
            Map<String, Object> orphan = object(entry);
            calledGone.add(new Gone(text(orphan.get("ruleKey")), text(orphan.get("subject")), "",
                    "no area has this subject any more"));
        }
        // What the report states is not gone: a new finding of the same rule in the area is the register's finding
        Set<String> stated = new HashSet<>();
        for (Finding f : verified) stated.add(f.rule() + "\n" + Known.shown(f.subject()));
        List<Gone> gone = calledGone.stream()
                .filter(g -> !stated.contains(g.rule() + "\n" + Known.shown(g.subject()))).toList();
        int judged = areas.size() - notJudged.size();
        // Each area is an item of the audit; the ones with an answer that was read are the items judged in this run
        Map<String, String> itemOfArea = new HashMap<>();
        Set<String> judgedItems = new HashSet<>();
        Set<String> withoutAnswer = new HashSet<>();
        for (NotJudged a : notJudged) withoutAnswer.add(a.id());
        // An answer with a finding that did not verify is no verdict to keep: the register never sees that finding,
        // so the area would read clean and leave the loop. It is listed as not judged, and the next run asks again
        for (Finding f : unverified) withoutAnswer.add(f.area());
        for (Map<String, Object> area : areas) {
            String item = text(area.get("item"));
            if (item.isEmpty()) continue;
            itemOfArea.put(text(area.get("id")), item);
            if (!withoutAnswer.contains(text(area.get("id")))) judgedItems.add(item);
        }
        // The gate follows the items: in what state each is after this run
        Map<String, String> numberOf = new HashMap<>();
        Set<String> answered = new HashSet<>();
        for (Map<String, Object> area : areas) {
            numberOf.put(States.name(area), text(area.get("id")));
            answered.add(text(area.get("id")));
        }
        for (NotJudged a : notJudged) answered.remove(a.id());
        Set<String> openNow = new HashSet<>(unanswered);
        Set<String> doubtful = new HashSet<>();
        // Every finding the run states opens its area and counts, also one the register holds a decision for: what
        // the register shows of a decided finding cannot tell whether this is it, and a decision must not hide what
        // the person never saw. The report names those, and says for each whether the register will open it again
        // (stated above the severity the decision was made on) or keep the decision
        Map<String, Map<String, Object>> areaOf = new HashMap<>();
        for (Map<String, Object> area : areas) areaOf.put(text(area.get("id")), area);
        List<String> decided = new ArrayList<>();
        for (List<Finding> group : grouped(verified)) {
            Finding first = group.getFirst();
            Map<?, ?> known = Known.decided(areaOf.get(first.area()), first.rule(), first.subject());
            if (known != null) {
                decided.add(ruleId(first.rule()) + " " + code(first.subject()) + " ("
                        + plain(String.valueOf(known.get("status"))) + ")"
                        + Known.registerWill(known.get("severity"), severity(group)));
            }
        }
        for (Finding f : verified) openNow.add(f.area());
        for (Finding f : unverified) doubtful.add(f.area());
        int missing = number(summary.get("knownMissing"));
        // An older summary names no count of the open ones: then every missing row may be open
        boolean openMissing = (summary.containsKey("knownMissingOpen") ? number(summary.get("knownMissingOpen"))
                : missing) > 0;
        States.Counts states = States.of(States.items(summary.get("items"), areas, list(summary.get("cut")), "subject"),
                numberOf, answered, doubtful, openNow, resolved,
                highUnanswered || unownedHigh(summary) || verified.stream().anyMatch(f -> f.severity().equals("high")),
                openMissing);
        String gate = gate(states, summary);
        String register = summary.get("register") instanceof String key && key.matches("[a-z0-9][a-z0-9._-]{0,127}")
                ? key : "security";
        String description = sinceBase(summary)
                + description(verified, unverified, notAnswered, areas.size(), judged, summary, low, states)
                + notLookedAt(summary);
        String markdown = markdown(verified, unverified, low, notJudged, gone, notAnswered, areas.size(), judged, summary,
                gate, description, states, areas) + unowned(summary, "findings", "area", register)
                + decided(decided, register) + unseen(missing, "area");
        // An area without a verdict (its call failed, or it got no place) is an item not judged, which the envelope
        // says. Only a file nobody could read makes the snapshot partial: nothing looked for findings there
        boolean complete = list(summary.get("notRead")).isEmpty();
        return new Outcome(List.copyOf(verified), List.copyOf(unverified), List.copyOf(notJudged), areas.size(), judged,
                gate, description, markdown, envelope(verified, gone, complete, summary, itemOfArea, judgedItems),
                findingsJsonl(verified, itemOfArea, Judge.fromJson(summary.get("judge"))), gone, List.copyOf(notAnswered), List.copyOf(low));
    }

    /**
     * The audit result envelope v1: the verified findings, those of one area and one rule as one finding, because a
     * register counts a rule and a subject once per report. The rule key is the rule of the rule file (or
     * {@code other}), the subject is the area's file and member, the severity the highest of its findings. It is a
     * full snapshot unless a file could not be read. It lists every item of the audit, each area that could be
     * judged, with its digest and whether this run read a verdict for it ({@code items}, {@code expectedItems}), and
     * every finding names the item of its area: an area whose call failed, or that got no place, is an item not
     * judged, and the register keeps what it knows of it (docs/registers.md, "Items"). The evidence names the fact {@code report_findings_verified} of this
     * step and the quoted lines, which are the file's own text. No text of a model is in it: the issue and the fix
     * stay in the report file.
     *
     * <p>{@code gone} names the open findings of earlier runs that this run checked and found gone: the register
     * resolves a model's finding only then, never because a report is silent about it.
     */
    private static String envelope(List<Finding> verified, List<Gone> gone, boolean complete,
                                   Map<String, Object> summary, Map<String, String> itemOfArea,
                                   Set<String> judgedItems) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (List<Finding> group : grouped(verified)) {
            Finding first = group.getFirst();
            String severity = severity(group);
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("ruleKey", first.rule());
            finding.put("subject", first.subject());
            finding.put("severity", severity.toUpperCase(Locale.ROOT));
            finding.put("title", (group.size() == 1 ? first.rule() + " finding" : group.size() + " " + first.rule()
                    + " findings") + " in " + first.file() + ", " + first.member());
            finding.put("description", "Area " + first.area() + " of the security report; the issue and the fix are"
                    + (changes(summary) == null ? " in security-report.md" : " in security-changes-report.md"));
            finding.put("evidence", evidence(group));
            if (itemOfArea.containsKey(first.area())) finding.put("item", itemOfArea.get(first.area()));
            findings.add(finding);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("snapshotComplete", complete);
        envelope.put("expectedFindings", (long) findings.size());
        envelope.put("findings", findings);
        if (!gone.isEmpty()) {
            // The rule and the subject only: why it is gone are a model's words, and stay in the report file
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Gone g : gone) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ruleKey", g.rule());
                entry.put("subject", g.subject());
                entries.add(entry);
            }
            envelope.put("gone", entries);
        }
        if (summary.get("items") instanceof List<?> listed) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index : listedItems(listed)) {
                Map<String, Object> item = object(listed.get(index));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", text(item.get("name")));
                out.put("digest", text(item.get("digest")));
                out.put("judged", judgedItems.contains(text(item.get("name"))));
                items.add(out);
            }
            if (listed.size() <= ITEMS_MAX) {
                envelope.put("expectedItems", (long) items.size());
            }
            envelope.put("items", items);
        }
        // "<" and ">" as escapes: a name of the workspace in it must not be able to end the comment
        return "<!-- traceworks:audit-result:v1 " + Json.write(envelope).replace("<", "\\u003c")
                .replace(">", "\\u003e") + " -->";
    }

    /** The verified findings as the envelope counts them: those of one rule and one subject together, in order. */
    private static List<List<Finding>> grouped(List<Finding> verified) {
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding f : verified) {
            grouped.computeIfAbsent(f.rule() + "\n" + f.subject(), key -> new ArrayList<>()).add(f);
        }
        return List.copyOf(grouped.values());
    }

    /** The highest severity of a group, as the envelope states it. */
    private static String severity(List<Finding> group) {
        String severity = "low";
        for (Finding f : group) {
            if (rank(f.severity()) > rank(severity)) severity = f.severity();
        }
        return severity;
    }

    /**
     * {@code findings.jsonl}: one JSON object and line per finding of the envelope, with the same {@code ruleKey} and
     * {@code subject}, so the two join. Here the model's words are: per verified finding the file, the line and the
     * quote the tool checked, and under {@code model} the issue and the fix as the model wrote them. {@code item} is
     * the item of the envelope, and {@code number} the number the area has in this run's report. The register never reads this file;
     * the record of the run keeps it, and {@code traceworks findings security show} reads it from there.
     */
    private static String findingsJsonl(List<Finding> verified, Map<String, String> itemOfArea, Judge judge) {
        StringBuilder out = new StringBuilder();
        for (List<Finding> group : grouped(verified)) {
            Finding first = group.getFirst();
            List<Map<String, Object>> details = new ArrayList<>();
            for (Finding f : group) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("file", f.file());
                detail.put("line", (long) f.line());
                detail.put("quote", normalized(f.quote()));
                detail.put("severity", f.severity());
                // What nothing checked goes under "model", so a reader can tell it from the file's own text
                Map<String, Object> words = new LinkedHashMap<>();
                words.put("issue", f.issue());
                words.put("fix", f.fix());
                detail.put("model", words);
                // Whose words they are: the provider and the model of the run's verdicts, where the run names them.
                // In the place, not in the finding: a finding is its rule and its subject, whoever reported it
                if (judge != null) {
                    if (!judge.provider().isEmpty()) detail.put("judge_provider", judge.provider());
                    if (!judge.model().isEmpty()) detail.put("judge_model", judge.model());
                }
                details.add(detail);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ruleKey", first.rule());
            entry.put("subject", first.subject());
            entry.put("severity", severity(group).toUpperCase(Locale.ROOT));
            entry.put("item", itemOfArea.getOrDefault(first.area(), first.area()));
            entry.put("number", first.area());
            entry.put("source", "model");
            entry.put("details", details);
            out.append(Json.write(entry)).append('\n');
        }
        return out.toString();
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }

    /** @param knownRule the rule of the known finding the entry answers for; {@code null} for a new finding */
    private static Finding check(String id, String subject, Map<String, Object> area, Object entry,
                                 Map<Integer, String> code, int first, int last, Set<String> ruleIds,
                                 String knownRule) {
        String file = text(area.get("file"));
        String member = text(area.get("member"));
        if (!(entry instanceof Map<?, ?> raw)) {
            return new Finding(id, subject, file, member, "", "", null, "", "", "", "the finding is no object");
        }
        Map<String, Object> f = object(raw);
        String rule = text(f.get("rule")).strip();
        String severity = text(f.get("severity")).strip().toLowerCase(Locale.ROOT);
        Integer line = lineNumber(f.get("line"));
        String quote = text(f.get("quote"));
        String issue = text(f.get("issue"));
        String fix = text(f.get("fix"));
        String problem = null;
        String collapsed = normalized(quote);
        if (!SEVERITIES.contains(severity)) {
            problem = "severity '" + shown(severity) + "' is not high, medium or low";
        } else if (line == null) {
            problem = "no line number";
        } else if (line < first || line > last) {
            problem = "line " + line + " lies outside the area (" + first + "-" + last + ")";
        } else if (collapsed.isEmpty()) {
            problem = "no quote";
        } else {
            String codeLine = normalized(code.getOrDefault(line, ""));
            if (!codeLine.contains(collapsed)) {
                problem = "the quote does not occur in line " + line;
            } else if (collapsed.length() < MIN_QUOTE && !collapsed.equals(codeLine)) {
                problem = "the quote is shorter than " + MIN_QUOTE + " characters and not the whole line";
            }
        }
        return new Finding(id, subject, file, member, knownRule != null ? knownRule
                : ruleIds.contains(rule) ? rule : "other", severity, line, quote, issue, fix, problem);
    }

    /**
     * The gate follows the items: RED while an open finding of high severity stands; YELLOW while an item is open or
     * not judged, a file was not read or no Java file was read at all; GREEN when every item is settled.
     */
    private static String gate(States.Counts states, Map<String, Object> summary) {
        if (states.highStands()) return "RED";
        if (!states.allSettled() || !changesDoubt(summary).isEmpty() || !list(summary.get("knownUnowned")).isEmpty()
                || number(summary.get("knownMissing")) > 0
                || !list(summary.get("notRead")).isEmpty() || number(summary.get("javaFiles")) == 0) {
            return "YELLOW";
        }
        return "GREEN";
    }

    /** The changes a check of the changes is about, or nothing for an audit of everything. */
    private static Map<String, Object> changes(Map<String, Object> summary) {
        return summary.get("changes") instanceof Map<?, ?> ? object(summary.get("changes")) : null;
    }

    /**
     * How the gate of a check of the changes begins: it speaks about the changes since the base, and says so. A run
     * that found no changed file says that first, in plain words: its gate is about what earlier runs left, if
     * anything.
     */
    private static String sinceBase(Map<String, Object> summary) {
        Map<String, Object> changes = changes(summary);
        if (changes == null) return "";
        String doubt = changesDoubt(summary);
        return (number(changes.get("changedFiles")) == 0 ? "nothing changed since " : "of the changes since ")
                + shortId(text(changes.get("base"))) + ": " + (doubt.isEmpty() ? "" : doubt + "; ");
    }

    /**
     * Why a check of the changes cannot be GREEN whatever its verdicts say, or nothing: a file that differs and
     * could not be compared, a changed or new Java file the finder did not read (other than a test), a changed file
     * of another kind that is no documentation, or commits after the last run's commit that this run's base left
     * out. A GREEN over any of them would say more than was checked.
     */
    private static String changesDoubt(Map<String, Object> summary) {
        Map<String, Object> changes = changes(summary);
        if (changes == null) return "";
        List<String> doubts = new ArrayList<>();
        int unmappable = list(changes.get("unmappable")).size();
        if (unmappable > 0) doubts.add(count(unmappable, "changed file") + " could not be compared");
        int uncompiled = number(changes.get("uncompiledFiles"));
        if (uncompiled > 0) doubts.add(count(uncompiled, "changed Java file") + " that no compiled source root holds");
        int cannotJudge = number(changes.get("cannotJudgeFiles"));
        if (cannotJudge > 0) doubts.add(count(cannotJudge, "changed file") + " this check cannot judge");
        String skipped = text(changes.get("skipped"));
        if (skipped.equals("unknown")) {
            doubts.add("the commit of the last run is not in this checkout, so commits may have been skipped");
        } else if (skipped.matches("[1-9][0-9]{0,8}")) {
            doubts.add(count(Integer.parseInt(skipped), "commit") + " after the last run skipped and not judged");
        }
        return String.join("; ", doubts);
    }

    /** How the gate of a check of the changes ends: how much of the project it does not speak about. */
    private static String notLookedAt(Map<String, Object> summary) {
        Map<String, Object> changes = changes(summary);
        return changes == null ? "" : "; " + number(changes.get("notLookedAt")) + " of "
                + count(number(changes.get("areasOfProject")), "area") + " of the project not looked at";
    }

    /** A commit id as a reader names it: its first twelve digits, and nothing that is no commit id. */
    private static String shortId(String commit) {
        return commit.matches("[0-9a-f]{40}|[0-9a-f]{64}") ? commit.substring(0, 12) : "an unknown base";
    }

    private static String fullId(String commit) {
        return commit.matches("[0-9a-f]{40}|[0-9a-f]{64}") ? "`" + commit + "`" : "unknown";
    }

    /** The part of the report only a check of the changes has: the base, the counts, and why each area was in. */
    private static void changesSection(StringBuilder md, Map<String, Object> changes, List<Map<String, Object>> areas) {
        String base = text(changes.get("base"));
        String head = text(changes.get("head"));
        String previous = text(changes.get("previousHead"));
        boolean input = "input".equals(changes.get("from"));
        boolean dirty = Boolean.TRUE.equals(changes.get("dirty"));
        md.append("**This gate speaks about the changes only.** It says nothing about the ")
                .append(number(changes.get("notLookedAt"))).append(" of ")
                .append(count(number(changes.get("areasOfProject")), "area"))
                .append(" of the project that this run did not look at; the long audit judges every area.\n\n");
        md.append("## What was looked at\n\n| | |\n|---|---|\n");
        String date = text(changes.get("baseDate"));
        row(md, "Base: the commit the changes are measured from", fullId(base)
                + (date.matches("[0-9T:+Z-]{1,40}") ? ", committed " + date : ""));
        row(md, "Where the base is from", input ? "the input `since`: " + code(text(changes.get("since")))
                : "the workspace commit of the last run of this audit, as its report names it");
        row(md, "Workspace commit", fullId(head) + (dirty ? ", with changes that are in no commit (a `.java` file git ignores is one); they count as"
                + " changed"
                : ", with nothing uncommitted"));
        String skipped = text(changes.get("skipped"));
        if (input && !previous.isEmpty() && !previous.equals(base)) {
            row(md, "Workspace commit of the last run of this audit", fullId(previous) + ": not the base of this run");
        }
        if (skipped.equals("unknown")) {
            row(md, "**Commits skipped**", "unknown: the commit of the last run is not in this checkout. The run was"
                    + " started with `skip=yes`. The gate is not GREEN over that");
        } else if (skipped.matches("[1-9][0-9]{0,8}")) {
            row(md, "**Commits skipped**", "**" + skipped + "**: the commits after " + fullId(previous) + " up to "
                    + fullId(base) + " (`git log " + shortId(previous) + ".." + shortId(base) + "`) are judged by no"
                    + " run of this check. The run was started with `skip=yes`. The gate is not GREEN over that, and"
                    + " the next run goes on from this run's workspace commit");
        }
        row(md, "Files that differ from the base", number(changes.get("changedFiles")) + " ("
                + number(changes.get("removedFiles")) + " of them removed)");
        row(md, "Of them tests (`src/test/java`), not judged by this check", String.valueOf(number(changes.get("testFiles"))));
        row(md, "Of them documentation (`*.md`, `docs/`)", String.valueOf(number(changes.get("docFiles"))));
        row(md, "Of them Java files that no compiled source root holds", String.valueOf(number(changes.get("uncompiledFiles"))));
        row(md, "Of them files this check cannot judge (a script, a workflow, a build file, a resource)",
                String.valueOf(number(changes.get("cannotJudgeFiles"))));
        row(md, "Changed files that could not be compared", String.valueOf(list(changes.get("unmappable")).size()));
        row(md, "Changed places in no member (an import, the head of a class): no area comes from them",
                String.valueOf(number(changes.get("outsideMembers"))));
        row(md, "Changed members", String.valueOf(number(changes.get("changedMembers"))));
        row(md, "Areas of the project", String.valueOf(number(changes.get("areasOfProject"))));
        row(md, "Areas with a changed line", String.valueOf(number(changes.get("changedAreas"))));
        row(md, "Neighbours: areas one call from a changed member", String.valueOf(number(changes.get("neighbourAreas"))));
        row(md, "Areas an earlier run left open or without a verdict", String.valueOf(number(changes.get("carriedAreas"))));
        row(md, "Areas of the project not looked at", number(changes.get("notLookedAt")) + " ("
                + number(changes.get("judgedAsTheyAre")) + " of them changed or a neighbour, and judged by an earlier"
                + " run as they are now)");
        named(md, "Changed or new Java files that no compiled source root holds. Nothing looked at their code, so the"
                + " gate is not GREEN, and no finding is named as gone because its member is no area any more: it may"
                + " have moved there", list(changes.get("uncompiled")), number(changes.get("uncompiledFiles")));
        named(md, "Changed, new or removed files this check cannot judge. They can change what the project does, so"
                + " the gate is not GREEN", list(changes.get("cannotJudge")), number(changes.get("cannotJudgeFiles")));
        named(md, "Changed files that could not be compared. Nothing mapped them to an area, so the gate is not"
                + " GREEN", list(changes.get("unmappable")), list(changes.get("unmappable")).size());
        named(md, "Changed places that lie in no member", list(changes.get("outside")),
                number(changes.get("outsideMembers")));
        if (number(changes.get("changedFiles")) == 0) {
            md.append("\n**Nothing changed.** No file of the workspace differs from the base")
                    .append(input ? "" : ", the workspace commit of the last run")
                    .append(". What this run judged, if anything, is what earlier runs left open or without a verdict.\n");
        }
        md.append("\n## Why each area was in\n\n");
        if (areas.isEmpty()) md.append("No area: nothing changed in an area or next to one, and nothing was left open.\n");
        for (Map<String, Object> area : areas) {
            md.append("- ").append(plain(text(area.get("id")))).append(" ").append(code(text(area.get("subject"))))
                    .append(": ").append(plain(text(area.get("reason")))).append("\n");
        }
        md.append("\n");
    }

    /** A line that names files or places of the workspace, the first of them when there are more; nothing for none. */
    private static void named(StringBuilder md, String what, List<?> names, int all) {
        if (names.isEmpty()) return;
        md.append("\n").append(what);
        if (all > names.size()) md.append(", the first ").append(names.size()).append(" of ").append(all);
        md.append(": ").append(String.join(", ", names.stream().map(n -> code(String.valueOf(n))).toList()))
                .append("\n");
    }

    private static String description(List<Finding> verified, List<Finding> unverified, List<String> notAnswered,
                                      int areas, int judged, Map<String, Object> summary, List<Finding> low,
                                      States.Counts states) {
        StringBuilder out = new StringBuilder();
        if (verified.isEmpty()) {
            out.append("no verified finding");
        } else {
            out.append(count(verified.size(), "verified finding")).append(" (")
                    .append(severityCount(verified, "high")).append(" high, ")
                    .append(severityCount(verified, "medium")).append(" medium, ")
                    .append(severityCount(verified, "low")).append(" low)");
        }
        out.append("; ").append(judged).append(" of ").append(areas).append(" areas judged");
        int notRead = list(summary.get("notRead")).size();
        int cut = list(summary.get("cut")).size();
        if (number(summary.get("javaFiles")) == 0) out.append("; no Java file read");
        if (notRead > 0) out.append("; ").append(count(notRead, "file")).append(" not read");
        if (cut > 0) out.append("; ").append(count(cut, "area")).append(" cut by the cap");
        long unverifiedHigh = severityCount(unverified, "high");
        long unverifiedLow = severityCount(unverified, "low");
        long unverifiedOther = unverified.size() - unverifiedHigh - unverifiedLow;
        if (unverifiedHigh > 0) out.append("; ").append(count((int) unverifiedHigh, "unverified high finding"));
        if (unverifiedOther > 0) {
            out.append("; ").append(count((int) unverifiedOther, "unverified medium or unclassified finding"));
        }
        if (unverifiedLow > 0) out.append("; ").append(count((int) unverifiedLow, "unverified low finding"));
        if (!notAnswered.isEmpty()) {
            out.append("; ").append(notAnswered.size()).append(notAnswered.size() == 1 ? " open finding"
                    : " open findings").append(" of an earlier run not answered");
        }
        if (!low.isEmpty()) out.append("; ").append(count(low.size(), "low finding")).append(" in this report only");
        int unowned = list(summary.get("knownUnowned")).size();
        if (unowned > 0) out.append("; ").append(count(unowned, "open finding")).append(" of the register that no item carries");
        int missing = number(summary.get("knownMissing"));
        if (missing > 0) out.append("; ").append(count(missing, "finding")).append(" of the register not shown to this run");
        return out.append("; ").append(states.text()).toString();
    }

    private static String markdown(List<Finding> verified, List<Finding> unverified, List<Finding> low,
                                   List<NotJudged> notJudged,
                                   List<Gone> gone, List<String> notAnswered, int areas, int judged,
                                   Map<String, Object> summary, String gate, String description,
                                   States.Counts states, List<Map<String, Object>> areaLines) {
        StringBuilder md = new StringBuilder();
        Map<String, Object> changes = changes(summary);
        md.append(changes == null ? "# Security self-audit\n\n" : "# Security check of the changes\n\n");
        md.append("**Gate:** ").append(gate).append(": ").append(description).append("\n\n");
        if (changes != null) changesSection(md, changes, areaLines);
        Judge judge = Judge.fromJson(summary.get("judge"));
        if (judge != null) {
            md.append("**Judged by:** provider ").append(code(judge.provider())).append(", model ")
                    .append(code(judge.model()))
                    .append(" (the option ").append(code(judge.name())).append(" of the model choice `judge`, as ")
                    .append("this run picked it; the record of the run holds the model of every call). The register ")
                    .append("knows a finding by its rule and its area, not by the model that reported it.\n\n");
        }
        md.append("A deterministic tool picked the areas below by resolved type; an AI judged each area with its code ")
                .append("in the prompt and could read the rest of the workspace to follow a call; a finding counts ")
                .append("only when it quotes a line of its area.\n\n");

        md.append("## Coverage\n\n| | |\n|---|---|\n");
        row(md, "Java files read", String.valueOf(number(summary.get("javaFiles"))));
        row(md, "Files not read", String.valueOf(list(summary.get("notRead")).size()));
        row(md, "Unresolved references (matched through imports)",
                String.valueOf(number(summary.get("unresolvedReferences"))));
        row(md, "Hits", String.valueOf(number(summary.get("hits"))));
        row(md, "Areas", String.valueOf(areas));
        row(md, "Areas judged", judged + " of " + areas);
        row(md, "Areas cut by the cap", String.valueOf(list(summary.get("cut")).size()));
        if (summary.get("items") instanceof List<?>) {
            int[] settled = settledItems(summary);
            row(md, changes == null ? "Items (every area that could be judged)"
                    : "Items (the areas of this run, and the ones the register knew)", String.valueOf(settled[2]));
            row(md, "Items settled and unchanged, not judged again", (settled[0] + settled[1]) + " (" + settled[0]
                    + " clean, " + settled[1] + " decided)");
            row(md, "Items after this run", states.text());
            if (settled[2] > ITEMS_MAX) {
                row(md, "Items listed for the register", ITEMS_MAX + " of " + settled[2] + ": it takes at most " + ITEMS_MAX
                        + " in a report, the ones of this run first, and no item leaves it by this report");
            }
        }
        List<?> targets = list(summary.get("unresolvedTargets"));
        row(md, "Rule types not in the sources or the platform", targets.isEmpty() ? "none"
                : String.join(", ", targets.stream().map(t -> code(String.valueOf(t))).toList()));
        if (summary.get("hitsPerRule") instanceof Map<?, ?> perRule && !perRule.isEmpty()) {
            md.append("\nHits per rule: ");
            List<String> parts = new ArrayList<>();
            perRule.forEach((rule, n) -> parts.add(ruleId(String.valueOf(rule)) + " " + n));
            md.append(String.join(", ", parts)).append("\n");
        }

        md.append("\n## Verified findings\n\n");
        if (verified.isEmpty()) md.append("None.\n\n");
        int n = 0;
        for (Finding f : verified) {
            n++;
            md.append("### ").append(n).append(". ").append(f.severity()).append(", ").append(ruleId(f.rule())).append(", ")
                    .append(f.area()).append("\n\n");
            place(md, f);
        }
        if (!low.isEmpty()) {
            md.append("## Low findings, in this report only\n\nThe verdict rated each of them low: a hardening gap ")
                    .append("with no known way through. They passed the same check as the others. They are not in ")
                    .append("the register and not in findings.jsonl, they do not count for the gate, and no later ")
                    .append("run is asked about them.\n\n");
            int number = 0;
            for (Finding f : low) {
                number++;
                md.append("### ").append(number).append(". ").append(ruleId(f.rule())).append(", ").append(f.area())
                        .append("\n\n");
                place(md, f);
            }
        }

        if (!unverified.isEmpty()) {
            md.append("## Unverified findings\n\nTheir evidence does not hold, so they do not count. ")
                    .append("They may still point at something; read the area before you drop them.\n\n");
            for (Finding f : unverified) {
                md.append("- ").append(f.area()).append(" ").append(code(f.subject())).append(" (")
                        .append(plain(f.severity())).append(", ").append(f.rule()).append("): ")
                        .append(plain(f.problem())).append(". ").append(plain(f.issue())).append("\n");
            }
            md.append("\n");
        }
        if (!gone.isEmpty()) {
            md.append("## Earlier findings that are gone\n\nOpen in the register, checked in this run and no longer ")
                    .append("there. The register resolves them. The reason is the model's, not checked.\n\n");
            for (Gone g : gone) {
                md.append("- ").append(ruleId(g.rule())).append(" ").append(code(g.subject())).append(": ")
                        .append(plain(g.reason())).append("\n");
            }
            md.append("\n");
        }
        if (!notAnswered.isEmpty()) {
            md.append("## Open findings without an answer\n\nOpen in the register, and the answer for their area ")
                    .append("neither showed that they still hold nor said that they are gone. They stay open.\n\n");
            for (String entry : notAnswered) md.append("- ").append(plain(entry)).append("\n");
            md.append("\n");
        }
        if (!notJudged.isEmpty()) {
            md.append("## Areas not judged\n\n");
            for (NotJudged a : notJudged) {
                md.append("- ").append(a.id()).append(" ").append(code(a.subject())).append(": ")
                        .append(plain(a.reason())).append("\n");
            }
            md.append("\n");
        }
        List<?> notRead = list(summary.get("notRead"));
        if (!notRead.isEmpty()) {
            md.append("## Files not read\n\n");
            for (Object entry : notRead) {
                Map<String, Object> file = object(entry);
                md.append("- ").append(code(text(file.get("file")))).append(": ").append(plain(text(file.get("reason"))))
                        .append("\n");
            }
            md.append("\n");
        }
        List<?> cut = list(summary.get("cut"));
        if (!cut.isEmpty()) {
            md.append("## Areas cut by the cap\n\n");
            for (Object entry : cut) {
                Map<String, Object> area = object(entry);
                md.append("- ").append(code(text(area.get("subject")))).append(" (")
                        .append(plain(String.valueOf(area.get("rules")))).append(")\n");
            }
            md.append("\n");
        }
        return md.toString();
    }

    /** Whether an open finding of the register that no item carries is high: it stands like any other. */
    private static boolean unownedHigh(Map<String, Object> facts) {
        return list(facts.get("knownUnowned")).stream().anyMatch(u -> u instanceof Map<?, ?> finding
                && HIGH.contains(String.valueOf(finding.get("severity"))));
    }

    /** The evidence the envelope gives a group: the fact of this step, and each quoted line with its place. */
    private static String evidence(List<Finding> group) {
        List<String> quoted = new ArrayList<>();
        for (Finding f : group) {
            quoted.add(f.file() + ":" + f.line() + ": " + shown(normalized(f.quote())));
        }
        return "report_findings_verified: " + String.join(" | ", quoted);
    }

    /** The stated findings the register holds a decision for; empty when there are none. */
    private static String decided(List<String> decided, String register) {
        if (decided.isEmpty()) return "";
        StringBuilder md = new StringBuilder("\n## Findings the register holds a decision for (" + decided.size()
                + ")\n\nA person accepted or dismissed a finding of the same rule and area as each of these, and this"
                + " run states one again. A decision covers the severity it was made on: when the register takes"
                + " this report, a finding stated above that severity is open again, and one stated at or below it"
                + " keeps its decision, unless a person reopens it (`traceworks findings " + register + " reopen <id>`). Each"
                + " line says which; with `\"decision_on_higher_severity\": \"keep\"` in the operator's"
                + " `traceworks.json`, which a run does not see, the register keeps every decision. Whether this is"
                + " what was decided, or something else of the rule in the area, is not told here: the gate counts"
                + " each of them in this run, and its area is open.\n\n");
        for (String entry : decided) md.append("- ").append(entry).append("\n");
        return md.toString();
    }

    /** What the register did not show this run, for the end of the report; empty when it showed everything. */
    private static String unseen(int missing, String what) {
        if (missing <= 0) return "";
        return "\n## Findings of the register this run did not see (" + missing + ")\n\nThe register handed this run"
                + " its findings in a block that holds at most 250 rows or 60 000 bytes, and left " + missing + " out."
                + " An open finding can be among them, so no verdict was asked about it. The gate is not GREEN over"
                + " that, and an " + what + " the register calls open stays open. Decide or fix findings until the"
                + " block holds them all.\n";
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

    /** Where a finding is, its quoted line, and the model's issue and fix. */
    private static void place(StringBuilder md, Finding f) {
        md.append(code(f.file() + ":" + f.line())).append(" in ").append(code(f.member())).append("\n\n");
        fenced(md, normalized(f.quote()));
        md.append("**Issue:** ").append(plain(f.issue())).append("\n\n");
        md.append("**Fix:** ").append(plain(f.fix())).append("\n\n");
    }

    /**
     * The items the register calls settled and that this run left out, and all items: clean, decided, all. The fact
     * step marks them: an item whose state was clean or decided and whose digest is the same. A clean item that
     * changed and got no place waits; it is not settled.
     */
    private static int[] settledItems(Map<String, Object> summary) {
        int clean = 0;
        int decided = 0;
        List<?> items = list(summary.get("items"));
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
     * first the items of this run (a finding names one of them), then the ones that wait for a verdict, then settled
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
        }).thenComparingInt(i -> i));
        List<Integer> kept = new ArrayList<>(order.subList(0, ITEMS_MAX));
        java.util.Collections.sort(kept);
        return kept;
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    /** A fenced block whose fence is longer than any run of backticks in the text. */
    private static void fenced(StringBuilder md, String text) {
        String fence = "`".repeat(Math.max(3, longestBackticks(text) + 1));
        md.append(fence).append("\n").append(text).append("\n").append(fence).append("\n\n");
    }

    /** A name from the workspace as a code span on one line: no Markdown inside it takes effect. */
    private static String code(String text) {
        String line = normalized(text);
        String ticks = "`".repeat(longestBackticks(line) + 1);
        // A space keeps a backtick at either end from joining the fence; Markdown drops one space on each side
        String pad = line.startsWith("`") || line.endsWith("`") ? " " : "";
        return ticks + pad + line + pad + ticks;
    }

    /** Model text as plain text on one line: it cannot start a heading, a list, a link, an image or raw HTML. */
    private static String plain(String text) {
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

    private static long severityCount(List<Finding> findings, String severity) {
        return findings.stream().filter(f -> f.severity().equals(severity)).count();
    }

    private static String text(Object value) {
        return value instanceof String s ? s : value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value) {
        return value instanceof Long l ? l.intValue() : value instanceof Integer i ? i : 0;
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
