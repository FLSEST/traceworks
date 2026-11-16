package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code state.json} of a register: the state as sorted, compact JSON with a final line feed, the same bytes for the same state. */
public final class StateJson {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private StateJson() {
    }

    public static byte[] write(RegisterState state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", state.format());
        root.put("key", state.key());
        root.put("workflow_id", state.workflowId());
        root.put("category", state.category());
        root.put("last_report", state.lastReport() == null ? null : map(
                "run_id", state.lastReport().runId(),
                "finished_at", instant(state.lastReport().finishedAt()),
                "report_blob", state.lastReport().reportBlob(),
                "valid", state.lastReport().valid()));
        root.put("valid_reports", state.validReports());
        List<Object> findings = new ArrayList<>();
        for (Finding f : state.findings()) {
            findings.add(finding(f, state.namesDecidedSeverity()));
        }
        root.put("findings", findings);
        if (!state.items().isEmpty()) {
            // Only for a register that has items: the bytes of a state without them are what they always were,
            // and the verifier compares bytes
            root.put("items", state.items().stream().map(StateJson::item).toList());
        }
        try {
            return (JSON.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code findings} as a JSON array with a final line feed, each finding with the fields it has in
     * {@code state.json}: what a script reads from {@code findings <key> list --json}. The text is ASCII only. The
     * severity a decision was made on is named wherever the finding has another one now ({@code disposition.severity}),
     * and a decision that is no longer in force says so ({@code disposition.in_force: false}, absent while it is in
     * force), so a script need not compare the status with the kind.
     */
    public static String findings(List<Finding> findings) {
        try {
            // The texts are a step's or a model's and the array goes to a terminal. JSON escapes only what is below
            // U+0020; with everything from DEL on escaped too, no control or format character arrives raw, and a
            // reader gets the same strings
            return JSON.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII)
                    .writeValueAsString(findings.stream().map(StateJson::listed).toList())
                    .replace("\u007f", "\\u007f") + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code items} as a JSON array with a final line feed, each item with its derived state and the fields it has
     * in {@code state.json}: what a script reads from {@code findings <key> items --json}. ASCII only, as
     * {@link #findings}.
     */
    public static String items(RegisterState state) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Item item : state.items()) {
            Map<String, Object> row = item(item);
            row.put("state", ItemState.of(item, state.findings()).word());
            rows.add(row);
        }
        try {
            return JSON.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(rows)
                    .replace("\u007f", "\\u007f") + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One finding as one line of JSON with its line feed, for a file a step reads ({@link RegisterFiles}): the fields
     * of {@link #findings}, and in {@code disposition} always the severity the decision was made on, where it is
     * known, and {@code in_force}. The line is ASCII only and holds no {@code <}: every other character is escaped,
     * so a text of a report can neither break the line nor stand in the file as a marker or a tag.
     */
    @SuppressWarnings("unchecked")
    static String fileLine(Finding f) {
        Map<String, Object> map = finding(f, false);
        if (f.disposition() != null) {
            Map<String, Object> disposition = (Map<String, Object>) map.get("disposition");
            disposition.put("severity", f.disposition().severity() == null ? null : f.disposition().severity().name());
            disposition.put("in_force", f.decisionInForce());
        }
        return fileLine(map);
    }

    /** One item as one line of JSON with its line feed, as {@link #fileLine(Finding)}: the fields of {@link #items}. */
    static String fileLine(Item item, List<Finding> findings) {
        Map<String, Object> row = item(item);
        row.put("state", ItemState.of(item, findings).word());
        return fileLine(row);
    }

    private static String fileLine(Map<String, Object> map) {
        try {
            return JSON.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(map)
                    .replace("\u007f", "\\u007f").replace("<", "\\u003c") + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> item(Item item) {
        return map("name", item.name(), "digest", item.digest(), "verdict_digest", item.verdictDigest(),
                "verdict", seen(item.verdict()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> listed(Finding f) {
        Map<String, Object> map = finding(f, true);
        if (f.disposition() != null && !f.decisionInForce()) {
            ((Map<String, Object>) map.get("disposition")).put("in_force", false);
        }
        return map;
    }

    /**
     * @param namesDecidedSeverity whether the severity a decision was made on is written where the finding has
     *                             another one now ({@link RegisterState#namesDecidedSeverity})
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> finding(Finding f, boolean namesDecidedSeverity) {
        Map<String, Object> map = findingFields(f);
        if (f.item() != null) {
            map.put("item", f.item());   // only when named, see write
        }
        if (namesDecidedSeverity && f.decidedOnOtherSeverity() != null) {
            // Only where the decision was made on another severity than the finding has now: every other state keeps
            // the bytes it always had, and a reader without the key knows that the decision covers the severity shown
            ((Map<String, Object>) map.get("disposition")).put("severity", f.decidedOnOtherSeverity().name());
        }
        return map;
    }

    private static Map<String, Object> findingFields(Finding f) {
        return map(
                "id", f.id(), "rule_key", f.ruleKey(), "subject", f.subject(), "severity", f.severity().name(),
                "title", f.title(), "description", f.description(), "evidence", f.evidence(), "tags", f.tags(),
                "status", f.status().name(),
                "first_seen", seen(f.firstSeen()), "last_seen", seen(f.lastSeen()),
                "occurrences", f.occurrences(), "regressions", f.regressions(),
                "unverified_evidence", f.unverifiedEvidence(), "resolved_at", instant(f.resolvedAt()),
                "absent_reports", f.absentReports(),
                "disposition", f.disposition() == null ? null : map(
                        "kind", f.disposition().kind().name(), "note", f.disposition().note(),
                        "actor", f.disposition().actor(), "at", instant(f.disposition().at()),
                        "until", instant(f.disposition().until()), "evidence", f.disposition().evidence()));
    }

    /**
     * The state the bytes hold. They name the severity a decision was made on only where the finding has another
     * one, so a decision read without it has none ({@link Finding.DispositionRecord#severity}): the fold of the log
     * ({@link RegisterReplay}) is what knows it for every decision.
     *
     * @throws IllegalArgumentException if the bytes are no state
     */
    public static RegisterState read(byte[] json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("state.json cannot be read: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject() || !root.path("key").isTextual()) {
            throw new IllegalArgumentException("state.json is no register state");
        }
        List<Finding> findings = new ArrayList<>();
        boolean namesDecidedSeverity = false;
        for (JsonNode f : root.path("findings")) {
            List<String> tags = new ArrayList<>();
            f.path("tags").forEach(tag -> tags.add(tag.asText()));
            JsonNode d = f.get("disposition");
            Finding.DispositionRecord disposition = d == null || d.isNull() ? null : new Finding.DispositionRecord(
                    FindingStatus.valueOf(d.path("kind").asText()), text(d, "note"), text(d, "actor"),
                    instant(d, "at"), instant(d, "until"), text(d, "evidence"),
                    Severity.parse(text(d, "severity")).orElse(null));
            namesDecidedSeverity |= disposition != null && disposition.severity() != null;
            findings.add(new Finding(f.path("id").asText(), f.path("rule_key").asText(), f.path("subject").asText(),
                    Severity.valueOf(f.path("severity").asText()), f.path("title").asText(), text(f, "description"),
                    text(f, "evidence"), tags, FindingStatus.valueOf(f.path("status").asText()),
                    seen(f.get("first_seen")), seen(f.get("last_seen")), f.path("occurrences").asInt(),
                    f.path("regressions").asInt(), f.path("unverified_evidence").asBoolean(false),
                    instant(f, "resolved_at"), disposition, f.path("absent_reports").asInt(0), text(f, "item")));
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode i : root.path("items")) {
            items.add(new Item(i.path("name").asText(), i.path("digest").asText(), text(i, "verdict_digest"),
                    seen(i.get("verdict"))));
        }
        JsonNode last = root.get("last_report");
        RegisterState.LastReport lastReport = last == null || last.isNull() ? null : new RegisterState.LastReport(
                text(last, "run_id"), instant(last, "finished_at"), text(last, "report_blob"),
                last.path("valid").asBoolean(false));
        return new RegisterState(root.path("format").asInt(1), root.path("key").asText(), text(root, "workflow_id"),
                text(root, "category"), lastReport, findings, root.path("valid_reports").asInt(0), items,
                namesDecidedSeverity);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> seen(Finding.Seen seen) {
        return seen == null ? null : map("run_id", seen.runId(), "at", instant(seen.at()));
    }

    private static Finding.Seen seen(JsonNode node) {
        return node == null || node.isNull() ? null : new Finding.Seen(text(node, "run_id"), instant(node, "at"));
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(JsonNode node, String field) {
        String text = text(node, field);
        return text == null ? null : Instant.parse(text);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
