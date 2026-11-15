package traceworks.core.register;

import traceworks.core.port.BlobRef;
import traceworks.core.port.BlobStore;
import traceworks.core.record.RecordJson;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes what happens to a register into its log, with the findings a report stated and the state after it as
 * blobs, so a replay can fold the log without the state and a verifier can check each state against the fold.
 */
public final class RegisterWriter {

    private final RegisterLog log;
    private final BlobStore blobs;

    public RegisterWriter(RegisterLog log, BlobStore blobs) {
        this.log = log;
        this.blobs = blobs;
    }

    /** The first line of a register: which workflow feeds it, in which category. */
    public void created(String workflowId, String category) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("workflow_id", workflowId);
        fields.put("category", category);
        log.append(RegisterEvents.CREATED, fields);
    }

    /**
     * Applies {@code report} to {@code state} and records it: {@code report.applied} with the reported findings, the
     * findings it names as gone ({@code gone_blob}, only when there are any), the items it lists ({@code items_blob}
     * with {@code items_complete} and the counts {@code items}, only when it lists any) and the state after as blobs,
     * then one line per change that matters.
     *
     * @param runHead the head of the run's record, which the register points to
     */
    public Reconciler.Applied applied(RegisterState state, Reconciler.Report report, Catalog catalog,
                                      String runHead) {
        return applied(state, report, catalog, null, runHead);
    }

    /**
     * As {@link #applied(RegisterState, Reconciler.Report, Catalog, String)}, with the catalog's bytes kept as a blob
     * ({@code catalog_blob}), so a replay applies the report with the catalog it was applied with.
     *
     * @param catalogJson the package's {@code catalog.json} that {@code catalog} was parsed from; {@code null} for none
     */
    public Reconciler.Applied applied(RegisterState state, Reconciler.Report report, Catalog catalog,
                                      byte[] catalogJson, String runHead) {
        return applied(state, report, catalog, catalogJson, runHead, DecisionOnHigherSeverity.DEFAULT);
    }

    /**
     * As {@link #applied(RegisterState, Reconciler.Report, Catalog, byte[], String)}, with what the operator set for
     * a decided finding that comes back with a higher severity. The line names it
     * ({@code decision_on_higher_severity}), so a replay applies the report as it was applied.
     *
     * @param higherSeverity {@link DecisionOnHigherSeverity#REOPEN} or {@link DecisionOnHigherSeverity#KEEP}
     */
    public Reconciler.Applied applied(RegisterState state, Reconciler.Report report, Catalog catalog,
                                      byte[] catalogJson, String runHead, DecisionOnHigherSeverity higherSeverity) {
        if (higherSeverity.word() == null) {
            throw new IllegalArgumentException("a report is applied with reopen or keep, not " + higherSeverity);
        }
        state = requiringDecisionSeverity(state);
        Reconciler.Applied applied = Reconciler.apply(state, report, catalog, higherSeverity);
        BlobRef findings = blobs.put(RecordJson.json(report.findings().stream().map(RegisterWriter::json).toList()));
        // What the report named as gone decides what a replay resolves, so the log keeps it as the findings are kept
        BlobRef gone = report.gone().isEmpty() ? null : blobs.put(RecordJson.json(report.gone().stream()
                .map(RegisterWriter::json).toList()));
        // The list of items decides which items a replay knows, so the log keeps it too
        BlobRef items = report.items() == null ? null : blobs.put(RecordJson.json(report.items().stream()
                .map(RegisterWriter::json).toList()));
        BlobRef catalogBlob = catalogJson == null ? null : blobs.put(catalogJson);
        BlobRef after = blobs.put(StateJson.write(applied.state()));
        Map<Change.Kind, Integer> counts = new EnumMap<>(Change.Kind.class);
        applied.changes().forEach(change -> counts.merge(change.kind(), 1, Integer::sum));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run_id", report.runId());
        fields.put("run_head", runHead);
        fields.put("finished_at", report.finishedAt() == null ? null : report.finishedAt().toString());
        fields.put("report_blob", report.reportBlob());
        fields.put("findings_blob", findings.sha256());
        if (gone != null) {
            fields.put("gone_blob", gone.sha256());
        }
        if (items != null) {
            fields.put("items_blob", items.sha256());
            fields.put("items_complete", report.itemsComplete());
            Map<String, Integer> tally = new TreeMap<>();
            tally.put("listed", report.items().size());
            tally.put("judged", (int) report.items().stream().filter(ReportedItem::judged).count());
            Map<ItemState, Integer> states = applied.state().itemCounts();
            tally.put("settled", states.get(ItemState.CLEAN) + states.get(ItemState.DECIDED));
            fields.put("items", tally);
        }
        fields.put("catalog_blob", catalogBlob == null ? null : catalogBlob.sha256());
        fields.put("decision_on_higher_severity", higherSeverity.word());
        fields.put("fact_names", new ArrayList<>(new java.util.TreeSet<>(report.factNames())));
        Map<String, Integer> countsByName = new TreeMap<>();
        counts.forEach((kind, n) -> countsByName.put(kind.name().toLowerCase(java.util.Locale.ROOT), n));
        fields.put("counts", countsByName);
        fields.put("state_blob", after.sha256());
        log.append(RegisterEvents.REPORT_APPLIED, fields);
        for (Change change : applied.changes()) {
            String type = switch (change.kind()) {
                case NEW -> RegisterEvents.FINDING_NEW;
                case RESOLVED -> RegisterEvents.FINDING_RESOLVED;
                case REOPENED -> RegisterEvents.FINDING_REOPENED;
                case REVIEW_DUE -> RegisterEvents.FINDING_REVIEW_DUE;
                default -> null;
            };
            if (type == null) {
                continue;
            }
            Finding finding = applied.state().finding(change.findingId());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", change.findingId());
            line.put("run_id", report.runId());
            if (change.kind() == Change.Kind.NEW && finding != null) {
                line.put("rule_key", finding.ruleKey());
                line.put("subject", finding.subject());
                line.put("severity", finding.severity().name());
                line.put("title", finding.title());
            }
            if (change.detail() != null) {
                line.put("reason", change.detail());
            }
            if (change.kind() == Change.Kind.REOPENED && finding != null && change.detail() != null
                    && change.detail().startsWith(Reconciler.HIGHER_SEVERITY) && finding.disposition() != null) {
                // Why it is open again, as data: the severity the decision was made on, and the one stated now
                line.put("decided_severity", finding.disposition().severity().name());
                line.put("severity", finding.severity().name());
            }
            log.append(type, line);
        }
        return applied;
    }

    /**
     * Records a person's decision: {@code finding.accepted}, {@code finding.dismissed} or {@code finding.reopened}
     * (reason {@code manual}), with the state after as a blob and {@code signature: null} until signing exists. An
     * acceptance and a dismissal name what they were made on: the {@code severity} and the {@code evidence} of the
     * finding at that moment.
     *
     * @param after the state with the decided finding
     * @return the state the line records, which is what a replay of the line gives: {@code after}, and after an
     *         acceptance or a dismissal {@link RegisterState#namingDecidedSeverity}, since the line names the severity
     */
    public RegisterState disposition(RegisterState after, Finding finding, FindingStatus kind, String note,
                                     String actor, java.time.Instant at, java.time.Instant until) {
        RegisterState recorded = kind == FindingStatus.OPEN ? after : requiringDecisionSeverity(after);
        BlobRef state = blobs.put(StateJson.write(recorded));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", finding.id());
        fields.put("note", note);
        fields.put("actor", actor);
        if (kind == FindingStatus.ACCEPTED_RISK) {
            fields.put("until", until == null ? null : until.toString());
            fields.put("evidence", finding.disposition() == null ? null : finding.disposition().evidence());
        }
        if (kind == FindingStatus.DISMISSED) {
            fields.put("evidence", finding.evidence());   // what the dismissal was made on; the state does not keep it
        }
        if (kind != FindingStatus.OPEN) {
            // What the decision covers. A line with it is of a version that reconciles by it (RegisterReplay)
            fields.put("severity", finding.severity() == null ? null : finding.severity().name());
        }
        if (kind == FindingStatus.OPEN) {
            fields.put("reason", "manual");
        }
        fields.put("signature", null);
        fields.put("state_blob", state.sha256());
        log.append(kind == FindingStatus.ACCEPTED_RISK ? RegisterEvents.FINDING_ACCEPTED
                : kind == FindingStatus.DISMISSED ? RegisterEvents.FINDING_DISMISSED
                : RegisterEvents.FINDING_REOPENED, fields, at);
        return recorded;
    }

    /**
     * Before the first line of a register that folds by the severity a decision was made on: {@code
     * register.requires}, which a version without that rule refuses to replay, so it cannot fold the lines after it
     * its own way and write on top. A register that has the line gets no second one.
     */
    private RegisterState requiringDecisionSeverity(RegisterState state) {
        if (state.namesDecidedSeverity()) {
            return state;
        }
        log.append(RegisterEvents.REQUIRES, Map.of("feature", RegisterEvents.DECISION_SEVERITY));
        return state.namingDecidedSeverity();
    }

    /** A report the register did not take: the reason, and the parser's errors if any. */
    public void rejected(String runId, String reason, List<String> errors) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run_id", runId);
        fields.put("reason", reason);
        fields.put("errors", errors);
        log.append(RegisterEvents.REPORT_REJECTED, fields);
    }

    static Map<String, Object> json(ReportedFinding f) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rule_key", f.ruleKey());
        map.put("subject", f.subject());
        map.put("severity", f.severity().name());
        map.put("title", f.title());
        map.put("description", f.description());
        map.put("evidence", f.evidence());
        map.put("tags", f.tags());
        if (f.item() != null) {
            map.put("item", f.item());   // only when named: the blob of a report without items stays as it was
        }
        return map;
    }

    static Map<String, Object> json(ReportedItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", item.name());
        map.put("digest", item.digest());
        map.put("judged", item.judged());
        return map;
    }

    static Map<String, Object> json(GoneFinding g) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rule_key", g.ruleKey());
        map.put("subject", g.subject());
        return map;
    }

    static ReportedFinding reported(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> tags = new ArrayList<>();
        node.path("tags").forEach(tag -> tags.add(tag.asText()));
        return new ReportedFinding(node.path("rule_key").asText(), node.path("subject").asText(),
                Severity.parse(node.path("severity").asText()).orElseThrow(), node.path("title").asText(),
                text(node, "description"), text(node, "evidence"), tags, text(node, "item"));
    }

    static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
