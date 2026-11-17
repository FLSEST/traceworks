package traceworks.core.register;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A finding as the register knows it: what the last report said about it, its lifecycle, and what a person decided.
 *
 * @param id                 {@link FindingIdentity}
 * @param status             the lifecycle status
 * @param firstSeen          the run that first reported it
 * @param lastSeen           the run that last reported it
 * @param occurrences        how many reports stated it
 * @param regressions        how often it came back after it was resolved
 * @param unverifiedEvidence whether its evidence names no fact a step measured (set from Task 7 on)
 * @param resolvedAt         when it was last resolved; {@code null} while it is not
 * @param disposition        what a person decided, or {@code null}
 * @param absentReports      how many applied reports in a row did not state it; a triaged finding of a measured
 *                           rule is retired after {@value Reconciler#RETIRE_AFTER_ABSENT_REPORTS}, and for an open
 *                           finding of a judged rule it says how long no report has mentioned it
 * @param item               the name of the item of the audit the finding is about ({@link Item}), as the last
 *                           report that named one gave it; {@code null} for a finding that belongs to none
 */
public record Finding(String id, String ruleKey, String subject, Severity severity, String title, String description,
                      String evidence, List<String> tags, FindingStatus status, Seen firstSeen, Seen lastSeen,
                      int occurrences, int regressions, boolean unverifiedEvidence, Instant resolvedAt,
                      DispositionRecord disposition, int absentReports, String item) {

    public Finding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** A finding that belongs to no item. */
    public Finding(String id, String ruleKey, String subject, Severity severity, String title, String description,
                   String evidence, List<String> tags, FindingStatus status, Seen firstSeen, Seen lastSeen,
                   int occurrences, int regressions, boolean unverifiedEvidence, Instant resolvedAt,
                   DispositionRecord disposition, int absentReports) {
        this(id, ruleKey, subject, severity, title, description, evidence, tags, status, firstSeen, lastSeen,
                occurrences, regressions, unverifiedEvidence, resolvedAt, disposition, absentReports, null);
    }

    /** A finding that no applied report has been absent from, and that belongs to no item. */
    public Finding(String id, String ruleKey, String subject, Severity severity, String title, String description,
                   String evidence, List<String> tags, FindingStatus status, Seen firstSeen, Seen lastSeen,
                   int occurrences, int regressions, boolean unverifiedEvidence, Instant resolvedAt,
                   DispositionRecord disposition) {
        this(id, ruleKey, subject, severity, title, description, evidence, tags, status, firstSeen, lastSeen,
                occurrences, regressions, unverifiedEvidence, resolvedAt, disposition, 0);
    }

    /** A sighting: the run and the time of its report. */
    public record Seen(String runId, Instant at) {
    }

    /**
     * A person's decision about a finding.
     *
     * @param kind     {@code ACCEPTED_RISK} or {@code DISMISSED}
     * @param until    the review date of an accepted risk; {@code null} for a dismissal
     * @param evidence the evidence frozen at the acceptance, for the comparison later; {@code null} for a dismissal
     * @param severity the severity the finding had when the person decided: what the decision covers. No report
     *                 changes it. {@code null} only where it is not known: a state read from a {@code state.json}
     *                 that does not name it
     * @param actor    who ran the command that decided ({@code docs/cli.md}, "Actor")
     * @param agent    the agent that decided under that actor, by the name it gave itself ({@link DecisionAgent});
     *                 {@code null} for a decision that named none, which reads as a person's
     */
    public record DispositionRecord(FindingStatus kind, String note, String actor, Instant at, Instant until,
                                    String evidence, Severity severity, String agent) {

        /** A decision that named no agent. */
        public DispositionRecord(FindingStatus kind, String note, String actor, Instant at, Instant until,
                                 String evidence, Severity severity) {
            this(kind, note, actor, at, until, evidence, severity, null);
        }

        /** A decision whose severity is not known, and that named no agent. */
        public DispositionRecord(FindingStatus kind, String note, String actor, Instant at, Instant until,
                                 String evidence) {
            this(kind, note, actor, at, until, evidence, null, null);
        }
    }

    /**
     * The severity the decision on this finding was made on, when it is known and is not the severity the finding has
     * now; {@code null} otherwise, and for a finding without a decision. Where it is not {@code null}, the decision
     * was not made on what the register shows as the finding's severity.
     */
    public Severity decidedOnOtherSeverity() {
        return disposition == null || disposition.severity() == null || disposition.severity() == severity ? null
                : disposition.severity();
    }

    /**
     * Whether the decision on this finding is in force: the finding has the status the decision gave it. It is not
     * after a report or a person opened the finding again, after its review date passed, or after it was resolved;
     * the decision then stays with the finding as what was once decided. {@code false} without a decision.
     */
    public boolean decisionInForce() {
        return disposition != null && status == disposition.kind();
    }

    /** The same finding with another status. */
    public Finding withStatus(FindingStatus newStatus) {
        return new Finding(id, ruleKey, subject, severity, title, description, evidence, tags, newStatus, firstSeen,
                lastSeen, occurrences, regressions, unverifiedEvidence, resolvedAt, disposition, absentReports, item);
    }
}
