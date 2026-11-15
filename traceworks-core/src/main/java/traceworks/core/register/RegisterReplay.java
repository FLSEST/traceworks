package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.port.BlobStore;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The state of a register as a fold over its log: every applied report is applied again, from the findings its line
 * points to, so the state needs no file of its own to be known, and {@code state.json} can be checked against it.
 */
public final class RegisterReplay {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RegisterReplay() {
    }

    /**
     * @throws IllegalStateException if a line cannot be replayed (an unknown type, a blob the fold needs is gone)
     */
    public static RegisterState replay(String key, List<RegisterEvent> events, BlobStore blobs) {
        RegisterState state = RegisterState.empty(key, null, null);
        for (RegisterEvent event : events) {
            state = apply(state, event, blobs);
        }
        return state;
    }

    /** The state after {@code event}. */
    static RegisterState apply(RegisterState state, RegisterEvent event, BlobStore blobs) {
        switch (event.type()) {
            case RegisterEvents.CREATED -> {
                return state.ownedBy(event.text("workflow_id"), event.text("category"));
            }
            case RegisterEvents.REPORT_APPLIED -> {
                String sha = event.text("findings_blob");
                byte[] bytes = blobs.get(sha).orElseThrow(() -> new IllegalStateException("seq " + event.seq()
                        + ": the findings blob " + sha + " is deleted, so the log cannot be replayed"));
                List<ReportedFinding> findings = new ArrayList<>();
                try {
                    for (JsonNode node : JSON.readTree(bytes)) {
                        findings.add(RegisterWriter.reported(node));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("seq " + event.seq() + ": the findings blob cannot be read", e);
                }
                List<GoneFinding> gone = new ArrayList<>();
                String goneSha = event.text("gone_blob");   // absent in a line of an earlier version, and when none
                if (goneSha != null) {
                    byte[] goneBytes = blobs.get(goneSha).orElseThrow(() -> new IllegalStateException("seq "
                            + event.seq() + ": the gone blob " + goneSha + " is deleted, so the log cannot be replayed"));
                    try {
                        for (JsonNode node : JSON.readTree(goneBytes)) {
                            gone.add(new GoneFinding(node.path("rule_key").asText(), node.path("subject").asText()));
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("seq " + event.seq() + ": the gone blob cannot be read", e);
                    }
                }
                List<ReportedItem> items = null;   // absent in a line of an earlier version, and without a list
                String itemsSha = event.text("items_blob");
                if (itemsSha != null) {
                    byte[] itemBytes = blobs.get(itemsSha).orElseThrow(() -> new IllegalStateException("seq "
                            + event.seq() + ": the items blob " + itemsSha + " is deleted, so the log cannot be replayed"));
                    items = new ArrayList<>();
                    try {
                        for (JsonNode node : JSON.readTree(itemBytes)) {
                            items.add(new ReportedItem(node.path("name").asText(), node.path("digest").asText(),
                                    node.path("judged").asBoolean()));
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("seq " + event.seq() + ": the items blob cannot be read", e);
                    }
                }
                Set<String> factNames = new TreeSet<>();
                event.fields().path("fact_names").forEach(name -> factNames.add(name.asText()));
                String finishedAt = event.text("finished_at");
                Reconciler.Report report = new Reconciler.Report(event.text("run_id"),
                        finishedAt == null ? null : Instant.parse(finishedAt), event.text("report_blob"), findings,
                        factNames, gone, items, event.fields().path("items_complete").asBoolean(false));
                // A line without the setting is of a version that kept every decision and named no severity; a
                // value nobody knows is a broken line
                String setting = event.text("decision_on_higher_severity");
                DecisionOnHigherSeverity higherSeverity = setting == null ? DecisionOnHigherSeverity.UNCHECKED
                        : DecisionOnHigherSeverity.of(setting).orElseThrow(() -> new IllegalStateException("seq "
                        + event.seq() + ": unknown decision_on_higher_severity '" + setting + "'"));
                return Reconciler.apply(state, report, catalog(event, blobs), higherSeverity).state();
            }
            case RegisterEvents.REQUIRES -> {
                String feature = event.text("feature");
                if (!RegisterEvents.DECISION_SEVERITY.equals(feature)) {
                    throw new IllegalStateException("seq " + event.seq() + ": the register requires '" + feature
                            + "', which this version of Traceworks does not know: a newer version wrote it");
                }
                return state.namingDecidedSeverity();   // from here on the states name the severity decided on
            }
            case RegisterEvents.REPAIRED, RegisterEvents.UNANCHORED -> {
                return state;   // a torn tail was cut, or lines without an anchor were found: no state is in it
            }
            case RegisterEvents.REPORT_REJECTED -> {
                return state.withLastReport(new RegisterState.LastReport(event.text("run_id"), null, null, false));
            }
            case RegisterEvents.FINDING_ACCEPTED, RegisterEvents.FINDING_DISMISSED -> {
                Finding finding = requireFinding(state, event);
                Finding after = RegisterEvents.FINDING_ACCEPTED.equals(event.type())
                        ? Disposition.accept(finding, event.text("note"), event.text("actor"), event.at(),
                                event.text("until") == null ? null : Instant.parse(event.text("until")))
                        : Disposition.dismiss(finding, event.text("note"), event.text("actor"), event.at());
                // The severity the decision covers is the one the fold holds at this line, for a line of any version
                return state.with(replacing(state, after));
            }
            case RegisterEvents.FINDING_REOPENED -> {
                if (!"manual".equals(event.text("reason"))) {
                    return state;   // the fold of the applied report already holds it
                }
                Finding after = Disposition.reopen(requireFinding(state, event), event.text("note"),
                        event.text("actor"), event.at());
                return state.with(replacing(state, after));
            }
            case RegisterEvents.FINDING_NEW, RegisterEvents.FINDING_RESOLVED, RegisterEvents.FINDING_REVIEW_DUE,
                 RegisterEvents.HOOK_RAN -> {
                return state;   // derived from, or beside, an applied report: the fold already holds it
            }
            default -> throw new IllegalStateException("seq " + event.seq() + ": unknown register event "
                    + event.type());
        }
    }

    /** The catalog the report was applied with, from its blob; {@code null} when the run had none. */
    private static Catalog catalog(RegisterEvent event, BlobStore blobs) {
        String sha = event.text("catalog_blob");
        if (sha == null) {
            return null;
        }
        byte[] bytes = blobs.get(sha).orElseThrow(() -> new IllegalStateException("seq " + event.seq()
                + ": the catalog blob " + sha + " is deleted, so the log cannot be replayed"));
        try {
            return Catalog.parse(bytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("seq " + event.seq() + ": the catalog blob cannot be read: "
                    + e.getMessage(), e);
        }
    }

    private static Finding requireFinding(RegisterState state, RegisterEvent event) {
        Finding finding = state.finding(event.text("id"));
        if (finding == null) {
            throw new IllegalStateException("seq " + event.seq() + ": " + event.type() + " names the finding "
                    + event.text("id") + ", which the register does not hold");
        }
        return finding;
    }

    private static List<Finding> replacing(RegisterState state, Finding after) {
        List<Finding> findings = new ArrayList<>();
        for (Finding f : state.findings()) {
            findings.add(f.id().equals(after.id()) ? after : f);
        }
        return findings;
    }
}
