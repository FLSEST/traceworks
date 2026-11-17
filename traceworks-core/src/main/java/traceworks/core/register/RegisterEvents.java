package traceworks.core.register;

/** The types of a register log's lines (docs/registers.md). */
public final class RegisterEvents {

    public static final String CREATED = "register.created";
    public static final String REPORT_APPLIED = "report.applied";
    public static final String REPORT_REJECTED = "report.rejected";
    public static final String FINDING_NEW = "finding.new";
    public static final String FINDING_RESOLVED = "finding.resolved";
    public static final String FINDING_REOPENED = "finding.reopened";
    public static final String FINDING_REVIEW_DUE = "finding.review_due";
    public static final String FINDING_ACCEPTED = "finding.accepted";
    public static final String FINDING_DISMISSED = "finding.dismissed";
    public static final String HOOK_RAN = "hook.ran";
    /** A torn tail, left by a writer that was cut off, was discarded before this line. */
    public static final String REPAIRED = "register.repaired";

    /**
     * A writer found lines after the last anchor in the ledger: {@code first_seq} to {@code last_seq} were in the
     * log and no anchor covered them. It goes on and anchors the head, this line included; the line is what stays
     * of the fact that those lines were taken unproven (docs/registers.md, "The anchor").
     */
    public static final String UNANCHORED = "register.unanchored";

    /**
     * From this line on the register needs a version that knows {@code feature}: its lines fold by a rule an earlier
     * version does not have. A version that does not know the feature, or the line, refuses to replay the log, so it
     * cannot write on top of what it would fold differently. Written once, before the first line that needs it.
     */
    public static final String REQUIRES = "register.requires";

    /**
     * The feature of {@link #REQUIRES} this version knows: a decision covers the severity it was made on
     * ({@link DecisionOnHigherSeverity}), and the states name that severity.
     */
    public static final String DECISION_SEVERITY = "decision_severity";

    /**
     * The feature of {@link #REQUIRES} for a decision an agent made: its line names the agent beside the actor
     * ({@code agent}), and so does every state after it. A version that does not know it would fold the decision as
     * the actor's own and show it so; it refuses the log instead.
     */
    public static final String DECISION_AGENT = "decision_agent";

    private RegisterEvents() {
    }
}
