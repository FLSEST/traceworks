package traceworks.core.register;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a report changed in a register, as the JSON a hook gets on its standard input: the new, resolved and reopened
 * findings, the ones still open, and the counts. Deterministic: the same change gives the same bytes.
 */
public final class RegisterDiff {

    public static final int FORMAT = 1;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private RegisterDiff() {
    }

    /** Whether the changes are worth a hook: something new, resolved or reopened. */
    public static boolean changesAnything(List<Change> changes) {
        return changes.stream().anyMatch(c -> c.kind() == Change.Kind.NEW || c.kind() == Change.Kind.RESOLVED
                || c.kind() == Change.Kind.REOPENED || c.kind() == Change.Kind.REVIEW_DUE);
    }

    public static byte[] of(String key, String workflowId, String runId, boolean reportValid, RegisterState before,
                            RegisterState after, List<Change> changes) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("register", key);
        root.put("workflow_id", workflowId);
        root.put("run_id", runId);
        root.put("report_valid", reportValid);
        root.put("new", findings(after, changes, Change.Kind.NEW));
        root.put("resolved", findings(after, changes, Change.Kind.RESOLVED));
        root.put("reopened", findings(after, changes, Change.Kind.REOPENED));
        root.put("review_due", findings(after, changes, Change.Kind.REVIEW_DUE));
        List<Object> stillOpen = new ArrayList<>();
        for (Finding f : after.findings()) {
            boolean changed = changes.stream().anyMatch(c -> c.findingId().equals(f.id())
                    && (c.kind() == Change.Kind.NEW || c.kind() == Change.Kind.REOPENED));
            if (f.status() == FindingStatus.OPEN && !changed) {
                stillOpen.add(json(f));
            }
        }
        root.put("still_open", stillOpen);
        Map<String, Integer> counts = new TreeMap<>();
        changes.forEach(c -> counts.merge(c.kind().name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum));
        root.put("counts", counts);
        try {
            return (JSON.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Object> findings(RegisterState after, List<Change> changes, Change.Kind kind) {
        List<Object> list = new ArrayList<>();
        for (Change change : changes) {
            if (change.kind() != kind) {
                continue;
            }
            Finding f = after.finding(change.findingId());
            if (f != null) {
                Map<String, Object> json = json(f);
                if (change.detail() != null) {
                    json.put("reason", change.detail());
                }
                list.add(json);
            }
        }
        return list;
    }

    private static Map<String, Object> json(Finding f) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", f.id());
        json.put("rule_key", f.ruleKey());
        json.put("subject", f.subject());
        json.put("severity", f.severity().name());
        json.put("title", f.title());
        json.put("description", f.description());
        json.put("evidence", f.evidence());
        json.put("tags", f.tags());
        json.put("status", f.status().name());
        json.put("unverified_evidence", f.unverifiedEvidence());
        json.put("occurrences", f.occurrences());
        json.put("regressions", f.regressions());
        return json;
    }
}
