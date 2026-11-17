package traceworks.selfaudit.architecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the register of the audit knows, as a run gets it in {@code {{ audit_findings }}}, read back into rows and
 * sorted to the items a model judges (docs/registers.md, "What the next run gets" and "Reconciliation").
 *
 * <p>A model's finding is not resolved by silence: the register keeps it open until a run says that it is gone. So
 * each verdict call gets the open findings of its own item, each with an id ({@code K1}, {@code K2}), and has to
 * answer for each: it still holds, or it is gone. A finding a person accepted or dismissed is decided: the call gets
 * it as something not to report again. A finding whose subject is no item any more is gone without a model, with
 * the reason that was measured: its file is gone, or its member is ({@link #goneReason}).
 *
 * <p>The register hands a run what it knows in two forms. The block of {@code {{ audit_findings }}} ({@link #parse})
 * holds at most 250 rows and cuts a subject after 300 characters; a cut subject equals no area's, so such a finding
 * was never asked about and never named gone. The file {@code findings.jsonl} of the register folder
 * ({@link #parseJsonl}) holds every finding whole, and is what the packages read.
 *
 * <p>A row is {@code STATUS | SEVERITY | ruleKey | subject | title | Evidence: ... [| Disposition: ...] [| Decided
 * on: ...]}. The severity of a row is that of the last report; a decision covers the severity it was made on, and
 * where that is another one the row says so in its last cell ({@code Decided on: LOW, not on CRITICAL}). The register
 * writes every field on one line, cut, with blanks collapsed and {@code |} masked as {@code ¦}, so the columns split
 * at {@code " | "}; a subject is compared here the way the register shows it. Everything in a row is data: it comes
 * from earlier reports and from a person's notes.
 */
final class Known {

    /** What an item's text says when the register knows nothing about it. */
    static final String NONE = "(none)";

    private static final Set<String> ASKED = Set.of("OPEN", "AWAITING_VERIFICATION");
    private static final Set<String> DECIDED = Set.of("ACCEPTED_RISK", "DISMISSED");
    /** How the register marks a field it cut. */
    private static final String CUT = "…";

    private Known() {
    }

    /**
     * One finding of the register.
     *
     * @param evidence  empty when the register shows none
     * @param note      the note of the decision; empty for an open finding or without a note
     * @param decidedOn the severity the decision on a decided finding was made on: what the row's last cell names,
     *                  else the severity the row shows
     * @param whole     whether the subject is the finding's whole subject: {@code false} for one the block cut
     */
    record Row(String status, String severity, String ruleKey, String subject, String title, String evidence,
               String note, String decidedOn, boolean whole) {

        /** Whether the next run is asked about it: it is open, or a fix waits to be verified. */
        boolean asked() {
            return ASKED.contains(status);
        }

        /** Whether a person decided it: it is not asked about and not reported again. */
        boolean decided() {
            return DECIDED.contains(status);
        }
    }

    /** The rows of the block; empty for the text of a first run, and for anything that is no block. */
    static List<Row> parse(String auditFindings) {
        List<Row> rows = new ArrayList<>();
        for (String line : auditFindings.split("\n")) {
            String[] cells = line.strip().split(" \\| ");
            if (cells.length < 6 || !(ASKED.contains(cells[0]) || DECIDED.contains(cells[0]))
                    || !cells[5].startsWith("Evidence: ")) {
                continue;   // the head, the legend, the count of the rows left out
            }
            String evidence = cells[5].substring("Evidence: ".length());
            String note = cells.length > 6 && cells[6].startsWith("Disposition: ")
                    ? cells[6].substring("Disposition: ".length()) : "";
            String decidedOn = cells[1];
            for (int i = 6; i < cells.length; i++) {
                if (cells[i].startsWith("Decided on: ")) {
                    String named = cells[i].substring("Decided on: ".length());
                    decidedOn = (named.indexOf(',') < 0 ? named : named.substring(0, named.indexOf(','))).strip();
                }
            }
            rows.add(new Row(cells[0], cells[1], cells[2], cells[3], cells[4], evidence.equals("(none)") ? "" : evidence,
                    note.equals("(no note)") ? "" : note, decidedOn, !cells[3].endsWith(CUT)));
        }
        return rows;
    }

    /**
     * The rows of {@code findings.jsonl} in the register folder of a run (docs/registers.md, "The register folder of
     * a run"): one JSON object a line, with {@code status}, {@code severity}, {@code rule_key}, {@code subject},
     * {@code evidence} and {@code disposition}. The file holds every finding the block of {@link #parse} lists, and
     * none of them cut: a subject is the whole subject, whatever its length. An empty file is a register that knows
     * no finding. A line that is no such object is refused: the file is the register's own, and half a list must not
     * pass for a whole one. The evidence and the note become one line each, as the prompt lists them.
     */
    static List<Row> parseJsonl(String findingsJsonl) {
        List<Row> rows = new ArrayList<>();
        for (String line : findingsJsonl.split("\n")) {
            if (line.isBlank()) continue;
            if (!(Json.parse(line) instanceof Map<?, ?> finding) || !(finding.get("status") instanceof String status)
                    || !(ASKED.contains(status) || DECIDED.contains(status))
                    || !(finding.get("rule_key") instanceof String rule)
                    || !(finding.get("subject") instanceof String subject)) {
                throw new IllegalArgumentException("a line of the register's findings is no finding");
            }
            String severity = finding.get("severity") instanceof String text ? text : "";
            Map<?, ?> disposition = finding.get("disposition") instanceof Map<?, ?> map ? map : Map.of();
            rows.add(new Row(status, severity, rule, subject, oneLine(finding.get("title")),
                    oneLine(finding.get("evidence")), oneLine(disposition.get("note")),
                    disposition.get("severity") instanceof String decidedOn ? decidedOn : severity, true));
        }
        return rows;
    }

    private static String oneLine(Object value) {
        return value instanceof String text ? text.strip().replaceAll("\\s+", " ") : "";
    }

    private static final java.util.regex.Pattern MISSING = java.util.regex.Pattern.compile("^\\(\\+(\\d{1,9}) more:");

    /**
     * How many findings the block left out: it holds at most 250 rows or 60 000 bytes and ends with
     * {@code (+N more: OPEN 3 · DISMISSED 2)} when it cut some. The rows of a person's decisions are kept first, so
     * the open ones are the first to go. 0 for a block that is whole, and for anything that is no block.
     *
     * <p>Nothing may be concluded from a row that is not there. With rows missing, the report keeps the gate from
     * GREEN and keeps an item open that the register calls open, whatever a verdict that never saw the finding says.
     */
    static int missing(String auditFindings) {
        int count = 0;
        for (String line : auditFindings.split("\n")) {
            java.util.regex.Matcher found = MISSING.matcher(line.strip());
            if (found.find()) count = Math.max(count, Integer.parseInt(found.group(1)));
        }
        return count;
    }

    private static final java.util.regex.Pattern STATUS_COUNT =
            java.util.regex.Pattern.compile("\\b(OPEN|AWAITING_VERIFICATION|ACCEPTED_RISK|DISMISSED) (\\d{1,9})");

    /**
     * How many of the findings the block left out are open or await verification, by the block's own count
     * ({@code (+5 more: OPEN 3 · DISMISSED 2)} says 3). When the line names no status, every missing one may be open.
     *
     * <p>With open rows missing, an item the register calls open stays open in the report, whatever was shown of it
     * and whatever was answered: the block does not say whose the missing rows are.
     */
    static int missingOpen(String auditFindings) {
        int count = 0;
        for (String line : auditFindings.split("\n")) {
            java.util.regex.Matcher found = MISSING.matcher(line.strip());
            if (!found.find()) continue;
            java.util.regex.Matcher status = STATUS_COUNT.matcher(line.strip().substring(found.end()));
            boolean named = false;
            int open = 0;
            while (status.find()) {
                named = true;
                if (ASKED.contains(status.group(1))) open += Integer.parseInt(status.group(2));
            }
            count = Math.max(count, named ? open : Integer.parseInt(found.group(1)));
        }
        return count;
    }

    /**
     * The status of the decision the register holds for the finding of {@code rule} and {@code subject}
     * ({@code ACCEPTED_RISK} or {@code DISMISSED}), by what {@link #attach} gave {@code item}
     * ({@code decided_known}); {@code null} when it holds none.
     *
     * <p>A finding a run states counts like any other, also when the register holds a decision for its rule and
     * subject: it opens its item and counts for the gate. Whether it is what the person decided cannot be told from
     * what the register shows of it, and a decision must not hide something the person never saw. A decision covers
     * the severity it was made on: the register opens the finding again when it takes a report that states it
     * above that severity, and keeps the decision otherwise ({@link #registerWill}). The report names such findings
     * for a person to look at, and says which of the two the register does.
     */
    static String decidedStatus(Map<String, Object> item, String rule, String subject) {
        Map<?, ?> row = decided(item, rule, subject);
        return row == null ? null : String.valueOf(row.get("status"));
    }

    /**
     * What {@link #attach} gave {@code item} of the decision the register holds for the finding of {@code rule} and
     * {@code subject}: its {@code status}, and under {@code severity} the severity it was made on; {@code null} when
     * the register holds none.
     */
    static Map<?, ?> decided(Map<String, Object> item, String rule, String subject) {
        if (item == null || !(item.get("decided_known") instanceof List<?> list)) return null;
        String shown = shown(subject);
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> row && rule.equals(row.get("rule"))
                    && shown.equals(shown(String.valueOf(row.get("subject"))))) {
                return row;
            }
        }
        return null;
    }

    private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    /**
     * What the register does with a decided finding that this run states as {@code stated}, for the report: it
     * opens the finding again when that is above the severity the decision was made on, and keeps the decision
     * otherwise (docs/registers.md, "What a decision covers"). The operator can set the register to keep every
     * decision, which a run does not see; the report says so once, above the list. Empty when one of the two is no
     * severity, as with the facts of a version that did not name the severity of a decision.
     */
    static String registerWill(Object decidedOn, String stated) {
        int decided = SEVERITIES.indexOf(String.valueOf(decidedOn).strip().toUpperCase(java.util.Locale.ROOT));
        int now = SEVERITIES.indexOf(String.valueOf(stated).strip().toUpperCase(java.util.Locale.ROOT));
        if (decided < 0 || now < 0) return "";
        return ": decided on " + SEVERITIES.get(decided) + ", stated as " + SEVERITIES.get(now) + ", which is "
                + (now > decided ? "above it: the register opens the finding again"
                : "not above it: the register keeps the decision");
    }

    /**
     * A subject as the register shows it in a row: blanks collapsed, and {@code |} as the masked {@code ¦}. Two
     * subjects are compared in this form, whichever of the two forms a row came from: applied twice it changes
     * nothing.
     */
    static String shown(String subject) {
        return subject.strip().replaceAll("\\s+", " ").replace('|', '¦');
    }

    /**
     * Gives {@code item} what the register knows about {@code subject}: {@code known}, the open findings to answer
     * for, each with an {@code id}, its {@code rule}, its {@code subject}, its {@code severity} and its
     * {@code evidence}; {@code known_text}, the same as lines for the prompt; {@code decided_text}, the accepted and
     * dismissed ones with their notes; {@code known_decided}, how many of those there are; and
     * {@code decided_known}, each with its rule, subject, status and the severity its decision was made on
     * ({@link #decided}).
     *
     * @param rules the rule keys a model judges; {@code null} when it judges every rule
     */
    static void attach(Map<String, Object> item, String subject, List<Row> rows, Set<String> rules) {
        List<Map<String, Object>> known = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        List<String> decided = new ArrayList<>();
        List<Map<String, Object>> decidedKnown = new ArrayList<>();
        String shown = shown(subject);
        for (Row row : rows) {
            if (!shown(row.subject()).equals(shown) || rules != null && !rules.contains(row.ruleKey())) continue;
            if (row.asked()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", "K" + (known.size() + 1));
                entry.put("rule", row.ruleKey());
                entry.put("subject", row.subject());
                entry.put("severity", row.severity());
                entry.put("evidence", row.evidence());
                known.add(entry);
                asked.add(entry.get("id") + " | " + row.ruleKey() + " | " + row.severity() + " | "
                        + (row.evidence().isEmpty() ? NONE : row.evidence()));
            } else if (row.decided()) {
                decided.add(row.status() + " | " + row.ruleKey() + " | " + (row.evidence().isEmpty() ? NONE
                        : row.evidence()) + " | note: " + (row.note().isEmpty() ? NONE : row.note()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rule", row.ruleKey());
                entry.put("subject", row.subject());
                entry.put("status", row.status());
                entry.put("severity", row.decidedOn());
                decidedKnown.add(entry);
            }
        }
        item.put("known", known);
        item.put("known_text", asked.isEmpty() ? NONE : String.join("\n", asked));
        item.put("decided_text", decided.isEmpty() ? NONE : String.join("\n", decided));
        item.put("known_decided", (long) decided.size());
        item.put("decided_known", decidedKnown);
    }

    /**
     * What the report needs of an item to say in what state it is after the run (the gate follows the items): how
     * many open findings the register holds of it ({@code open}), how many of them are high ({@code open_high}),
     * and how many a person decided ({@code decided}).
     *
     * @param item an item after {@link #attach}
     */
    static Map<String, Object> counts(Map<String, Object> item) {
        List<?> known = item.get("known") instanceof List<?> list ? list : List.of();
        long high = known.stream().filter(entry -> entry instanceof Map<?, ?> finding
                && ("HIGH".equals(finding.get("severity")) || "CRITICAL".equals(finding.get("severity")))).count();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("open", (long) known.size());
        counts.put("open_high", high);
        counts.put("decided", item.get("known_decided") instanceof Long decided ? decided : 0L);
        return counts;
    }

    /**
     * The open findings of {@code rules} whose subject is none of {@code subjects}, each as {@code ruleKey} and
     * {@code subject}: what they were about is no more, so they cannot hold as they were stated. A subject the
     * block cut is left out, since no subject could equal it; the file of the register folder cuts none. The subject
     * is given as the row has it, so the register finds the finding it names. A decided finding is left out too: a person
     * accepted or dismissed it, and it stays so until its review date brings it back as one that is asked about.
     *
     * @param rules    the rule keys a model judges; {@code null} when it judges every rule
     * @param subjects every subject an item could have, also of the items a cap cut
     */
    static List<Map<String, Object>> orphans(List<Row> rows, Set<String> rules, Set<String> subjects) {
        Set<String> shown = new java.util.HashSet<>();
        for (String subject : subjects) shown.add(shown(subject));
        List<Map<String, Object>> orphans = new ArrayList<>();
        for (Row row : rows) {
            if (!row.asked() || rules != null && !rules.contains(row.ruleKey())
                    || shown.contains(shown(row.subject())) || !row.whole()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ruleKey", row.ruleKey());
            entry.put("subject", row.subject());
            orphans.add(entry);
        }
        return orphans;
    }

    /** Why a finding is gone whose file is no source file the audit reads any more. */
    static final String FILE_GONE = "the file is gone";
    /** Why a finding is gone whose file is still read, and has no area with the finding's members. */
    static final String MEMBER_GONE = "the member is gone";

    /**
     * Why an orphan is gone, measured: a subject is {@code <file>#<members>}, so the file of {@code subject} is
     * still read when the subject starts with one of {@code files} and a {@code #}. Then the file has no area with
     * these members any more ({@link #MEMBER_GONE}): a member was removed or renamed, or lost what a rule points at.
     * Otherwise no source file has that path ({@link #FILE_GONE}): it was removed, renamed or moved.
     *
     * @param files the paths of the source files the finder read, relative to the root
     */
    static String goneReason(String subject, java.util.Collection<String> files) {
        String shown = shown(subject);
        for (String file : files) {
            if (shown.startsWith(shown(file) + "#")) return MEMBER_GONE;
        }
        return FILE_GONE;
    }

    /**
     * The open findings of {@code rules} that no item carries and that are no orphans either: the block cut their
     * subject, so no subject could equal it and nothing can tell whether what they were about is still there. No run
     * asks about them and none names them as gone: the report lists them for a person, and the gate does not go
     * green over them. Each as {@code ruleKey}, {@code subject} and {@code severity}.
     *
     * @param rules    the rule keys a model judges; {@code null} when it judges every rule
     * @param subjects every subject an item could have, also of the items a cap cut
     */
    static List<Map<String, Object>> unowned(List<Row> rows, Set<String> rules, Set<String> subjects) {
        Set<String> shown = new java.util.HashSet<>();
        for (String subject : subjects) shown.add(shown(subject));
        List<Map<String, Object>> unowned = new ArrayList<>();
        for (Row row : rows) {
            if (!row.asked() || rules != null && !rules.contains(row.ruleKey())
                    || shown.contains(shown(row.subject())) || row.whole()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ruleKey", row.ruleKey());
            entry.put("subject", row.subject());
            entry.put("severity", row.severity());
            unowned.add(entry);
        }
        return unowned;
    }
}
