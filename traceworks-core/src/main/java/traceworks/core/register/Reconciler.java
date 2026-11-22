package traceworks.core.register;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a report to a register state, as pure logic: a report is a full
 * snapshot, so a finding it states is new or seen, and an open finding it does not state is resolved. A finding a
 * person accepted or dismissed stays as it is, as long as no report states it with a severity above the one the
 * decision was made on: then it is open again, unless the operator keeps decisions ({@link DecisionOnHigherSeverity},
 * docs/workflow-format.md).
 *
 * <p>Traceworks tells a measured rule from a judged one ({@link Catalog#judged}). A measurement that is not in a full
 * snapshot is gone. A judgement of a model that is not in it may only not have been named again: an open finding of
 * a judged rule is resolved only when the report names it as gone ({@link Report#gone}), and stays open, counted in
 * {@link Finding#absentReports}, when the report does not mention it. A decided finding of a judged rule is not
 * retired by silence, since the run is not asked about it.
 *
 * <p>Of two findings of one identity in a report, one counts: the most severe, the first among equals
 * ({@link Change.Kind#DUPLICATE_IN_REPORT} for the other).
 *
 * <p>A report may list the items its audit judges ({@link Report#items}); the register keeps for each the digest and
 * the run of its last verdict ({@link Item}).
 *
 * <p>Nothing is guessed: there is no normalisation of subjects, no inheritance of a disposition across rules, and
 * no fuzzy matching of catalog keys, so a reworded subject or a changed rule key is a new finding
 * (docs/workflow-format.md).
 */
public final class Reconciler {

    /** After this many applied reports without a sighting, an accepted or dismissed finding is retired as resolved. */
    public static final int RETIRE_AFTER_ABSENT_REPORTS = 3;

    private Reconciler() {
    }

    /**
     * A report to apply.
     *
     * @param reportBlob the sha256 of the report text in the register's blobs
     * @param factNames  the fact names the run's steps measured, for the evidence check (Task 7); may be empty
     * @param gone       the findings the report names as checked and gone; may be empty
     * @param items      the items the report lists; {@code null} for a report without a list, which leaves the
     *                   register's items as they are
     * @param itemsComplete whether the report vouches for the whole list: only then does an item it does not hold
     *                   leave the register
     */
    public record Report(String runId, Instant finishedAt, String reportBlob, List<ReportedFinding> findings,
                         Set<String> factNames, List<GoneFinding> gone, List<ReportedItem> items,
                         boolean itemsComplete) {
        public Report {
            findings = List.copyOf(findings);
            factNames = Set.copyOf(factNames);
            gone = List.copyOf(gone);
            items = items == null ? null : List.copyOf(items);
        }

        /** A report without a list of items. */
        public Report(String runId, Instant finishedAt, String reportBlob, List<ReportedFinding> findings,
                      Set<String> factNames, List<GoneFinding> gone) {
            this(runId, finishedAt, reportBlob, findings, factNames, gone, null, false);
        }

        /** A report that names nothing as gone. */
        public Report(String runId, Instant finishedAt, String reportBlob, List<ReportedFinding> findings,
                      Set<String> factNames) {
            this(runId, finishedAt, reportBlob, findings, factNames, List.of());
        }
    }

    /** The state after a report, and what the report changed. */
    public record Applied(RegisterState state, List<Change> changes) {
        public Applied {
            changes = List.copyOf(changes);
        }
    }

    /**
     * The state after {@code report}; {@code catalog} may fix subjects ({@code null} for none).
     */
    public static Applied apply(RegisterState state, Report report, Catalog catalog) {
        return apply(state, report, catalog, DecisionOnHigherSeverity.DEFAULT);
    }

    /**
     * The state after {@code report}.
     *
     * @param catalog        may fix subjects and mark rules as judged; {@code null} for none
     * @param higherSeverity what happens to a decided finding stated with a severity above the one decided on
     */
    public static Applied apply(RegisterState state, Report report, Catalog catalog,
                                DecisionOnHigherSeverity higherSeverity) {
        Map<String, Finding> known = new LinkedHashMap<>();
        state.findings().forEach(f -> known.put(f.id(), f));
        List<Change> changes = new ArrayList<>();
        Finding.Seen sighting = new Finding.Seen(report.runId(), report.finishedAt());
        List<ReportedFinding> reported = report.findings().stream().map(Reconciler::unmask).toList();
        List<String> subjects = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        // Of the findings of one identity, the most severe counts, the first among equals: whether a decision holds
        // must not depend on the order a report states them in. A version before that rule took the first
        Map<String, Integer> counted = new java.util.HashMap<>();
        for (int i = 0; i < reported.size(); i++) {
            ReportedFinding f = reported.get(i);
            String subject = catalog == null ? f.subject() : catalog.fixedSubject(f.ruleKey()).orElse(f.subject());
            String id = FindingIdentity.of(state.key(), f.ruleKey(), subject);
            subjects.add(subject);
            ids.add(id);
            Integer other = counted.get(id);
            if (other == null || higherSeverity != DecisionOnHigherSeverity.UNCHECKED
                    && f.severity().compareTo(reported.get(other).severity()) < 0) {
                counted.put(id, i);
            }
        }
        Set<String> seen = counted.keySet();
        for (int i = 0; i < reported.size(); i++) {
            ReportedFinding f = reported.get(i);
            String subject = subjects.get(i);
            String id = ids.get(i);
            if (counted.get(id) != i) {
                changes.add(new Change(Change.Kind.DUPLICATE_IN_REPORT, id, "findings[" + i + "]"));
                continue;
            }
            Finding before = known.get(id);
            if (before == null) {
                known.put(id, new Finding(id, f.ruleKey(), subject, f.severity(), f.title(), f.description(),
                        f.evidence(), f.tags(), FindingStatus.OPEN, sighting, sighting, 1, 0,
                        !EvidenceVerification.isSupported(f.evidence(), report.factNames()), null, null, 0,
                        f.item()));
                changes.add(new Change(Change.Kind.NEW, id, null));
                continue;
            }
            FindingStatus status = before.status();
            int regressions = before.regressions();
            Instant resolvedAt = before.resolvedAt();
            String higher = higherThanDecided(before, f.severity(), higherSeverity);
            switch (before.status()) {
                case OPEN -> changes.add(new Change(Change.Kind.SEEN, id, null));
                case RESOLVED -> {
                    status = FindingStatus.OPEN;
                    regressions++;
                    resolvedAt = null;
                    changes.add(new Change(Change.Kind.REOPENED, id, "regression"));
                }
                case AWAITING_VERIFICATION -> {
                    status = FindingStatus.OPEN;
                    changes.add(new Change(Change.Kind.REOPENED, id, "seen_again"));
                }
                case ACCEPTED_RISK -> {
                    AcceptancePolicy.Assessment assessment = higher != null
                            ? new AcceptancePolicy.Assessment(AcceptancePolicy.Outcome.REOPENED, higher)
                            : AcceptancePolicy.reassess(before, f.evidence(), report.finishedAt());
                    switch (assessment.outcome()) {
                        case REOPENED -> {
                            status = FindingStatus.OPEN;
                            changes.add(new Change(Change.Kind.REOPENED, id, assessment.detail()));
                        }
                        case REVIEW_DUE -> {
                            status = FindingStatus.AWAITING_VERIFICATION;
                            changes.add(new Change(Change.Kind.REVIEW_DUE, id, null));
                        }
                        case UNCHANGED -> changes.add(new Change(Change.Kind.UNCHANGED_TRIAGED, id, null));
                    }
                }
                case DISMISSED -> {
                    if (higher != null) {
                        status = FindingStatus.OPEN;
                        changes.add(new Change(Change.Kind.REOPENED, id, higher));
                    } else {
                        changes.add(new Change(Change.Kind.UNCHANGED_TRIAGED, id, null));
                    }
                }
            }
            known.put(id, new Finding(id, f.ruleKey(), subject, f.severity(), f.title(), f.description(), f.evidence(),
                    f.tags(), status, before.firstSeen(), sighting, before.occurrences() + 1, regressions,
                    !EvidenceVerification.isSupported(f.evidence(), report.factNames()), resolvedAt,
                    before.disposition(), 0, f.item() != null ? f.item() : before.item()));
        }
        Set<String> gone = new java.util.HashSet<>();
        for (GoneFinding g : report.gone()) {
            String ruleKey = unmask(g.ruleKey());
            String subject = unmask(g.subject());
            gone.add(FindingIdentity.of(state.key(), ruleKey,
                    catalog == null ? subject : catalog.fixedSubject(ruleKey).orElse(subject)));
        }
        for (Map.Entry<String, Finding> entry : known.entrySet()) {
            Finding f = entry.getValue();
            if (seen.contains(f.id())) {
                continue;
            }
            // A judgement of a model: that the report is silent about it does not say it is fixed
            boolean judged = catalog != null && catalog.judged(f.ruleKey());
            if (f.status() == FindingStatus.OPEN || f.status() == FindingStatus.AWAITING_VERIFICATION) {
                if (judged && !gone.contains(f.id())) {
                    entry.setValue(new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(),
                            f.description(), f.evidence(), f.tags(), f.status(), f.firstSeen(), f.lastSeen(),
                            f.occurrences(), f.regressions(), f.unverifiedEvidence(), f.resolvedAt(), f.disposition(),
                            f.absentReports() + 1, f.item()));
                    changes.add(new Change(Change.Kind.NOT_MENTIONED, f.id(), null));
                    continue;
                }
                entry.setValue(new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(), f.description(),
                        f.evidence(), f.tags(), FindingStatus.RESOLVED, f.firstSeen(), f.lastSeen(), f.occurrences(),
                        f.regressions(), f.unverifiedEvidence(), report.finishedAt(), f.disposition(), 0,
                        f.item()));
                changes.add(new Change(Change.Kind.RESOLVED, f.id(), judged ? "gone" : "absent"));
            } else if (f.status() == FindingStatus.ACCEPTED_RISK || f.status() == FindingStatus.DISMISSED) {
                boolean ruleLeft = catalog != null && !catalog.rules().containsKey(f.ruleKey());
                boolean namedGone = judged && gone.contains(f.id());
                int absent = f.absentReports() + 1;
                // A decided finding of a judged rule is not asked about, so its absence retires nothing
                if (ruleLeft || namedGone || !judged && absent >= RETIRE_AFTER_ABSENT_REPORTS) {
                    entry.setValue(new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(),
                            f.description(), f.evidence(), f.tags(), FindingStatus.RESOLVED, f.firstSeen(),
                            f.lastSeen(), f.occurrences(), f.regressions(), f.unverifiedEvidence(),
                            report.finishedAt(), f.disposition(), absent, f.item()));
                    changes.add(new Change(Change.Kind.RESOLVED, f.id(), ruleLeft ? "rule_left_catalog"
                            : namedGone ? "gone" : "retired"));
                } else if (f.status() == FindingStatus.ACCEPTED_RISK
                        && AcceptancePolicy.reassess(f, null, report.finishedAt()).outcome()
                        == AcceptancePolicy.Outcome.REVIEW_DUE) {
                    entry.setValue(new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(),
                            f.description(), f.evidence(), f.tags(), FindingStatus.AWAITING_VERIFICATION,
                            f.firstSeen(), f.lastSeen(), f.occurrences(), f.regressions(), f.unverifiedEvidence(),
                            null, f.disposition(), absent, f.item()));
                    changes.add(new Change(Change.Kind.REVIEW_DUE, f.id(), null));
                } else {
                    entry.setValue(new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(),
                            f.description(), f.evidence(), f.tags(), f.status(), f.firstSeen(), f.lastSeen(),
                            f.occurrences(), f.regressions(), f.unverifiedEvidence(), f.resolvedAt(),
                            f.disposition(), absent, f.item()));
                }
            }
        }
        RegisterState after = new RegisterState(state.format(), state.key(), state.workflowId(), state.category(),
                new RegisterState.LastReport(report.runId(), report.finishedAt(), report.reportBlob(), true),
                new ArrayList<>(known.values()), state.validReports() + 1, items(state.items(), report, sighting),
                state.namesDecidedSeverity() || higherSeverity != DecisionOnHigherSeverity.UNCHECKED,
                state.project());
        return new Applied(after, changes);
    }

    /** The detail of a reopening by severity, when a report states a decided finding above the severity decided on. */
    public static final String HIGHER_SEVERITY = "higher_severity";

    /**
     * Why a decided finding stated as {@code now} is open again, as {@code higher_severity: LOW → CRITICAL};
     * {@code null} when it is not: the operator keeps decisions, the severity decided on is not known, or
     * {@code now} is not above it. Only the severity decides, never the evidence alone.
     */
    private static String higherThanDecided(Finding before, Severity now, DecisionOnHigherSeverity higherSeverity) {
        Finding.DispositionRecord decision = before.disposition();
        if (higherSeverity != DecisionOnHigherSeverity.REOPEN || decision == null || decision.severity() == null
                || now.compareTo(decision.severity()) >= 0) {   // the most severe comes first
            return null;
        }
        return HIGHER_SEVERITY + ": " + decision.severity() + " → " + now;
    }

    /**
     * The items after a report. A listed item is added or takes the digest the report gives; a run that judged it
     * becomes its last verdict, and one that did not leaves the earlier verdict as it was, so a call that failed
     * forgets nothing. An item the list does not hold stays, unless the report vouches for the whole list. The
     * findings of an item that left are not touched: they stay until a run names them as gone or a person decides.
     */
    private static List<Item> items(List<Item> before, Report report, Finding.Seen sighting) {
        if (report.items() == null) {
            return before;
        }
        Map<String, Item> known = new LinkedHashMap<>();
        before.forEach(item -> known.put(item.name(), item));
        Map<String, Item> after = report.itemsComplete() ? new LinkedHashMap<>() : known;
        for (ReportedItem listed : report.items()) {
            // the name as a finding's item is read: {{ audit_items }} shows a pipe masked, and an echo of the mask means the pipe
            String name = unmask(listed.name());
            Item earlier = known.get(name);
            after.put(name, listed.judged()
                    ? new Item(name, listed.digest(), listed.digest(), sighting)
                    : new Item(name, listed.digest(), earlier == null ? null : earlier.verdictDigest(),
                            earlier == null ? null : earlier.verdict()));
        }
        return new ArrayList<>(after.values());
    }

    /** {@code audit_findings} shows {@code |} masked as {@code ¦}; a model that echoes the mask meant the pipe. */
    private static ReportedFinding unmask(ReportedFinding f) {
        return new ReportedFinding(unmask(f.ruleKey()), unmask(f.subject()), f.severity(), unmask(f.title()),
                unmask(f.description()), unmask(f.evidence()), f.tags().stream().map(Reconciler::unmask).toList(),
                unmask(f.item()));
    }

    private static String unmask(String text) {
        return text == null ? null : text.replace('¦', '|');
    }
}
