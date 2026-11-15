package traceworks.core.register;

/** The lifecycle status of a finding in a register. */
public enum FindingStatus {
    OPEN, AWAITING_VERIFICATION, RESOLVED, ACCEPTED_RISK, DISMISSED;

    /** Whether the finding needs no attention until something changes: resolved, or triaged by a person. */
    public boolean terminal() {
        return this == RESOLVED || this == ACCEPTED_RISK || this == DISMISSED;
    }
}
