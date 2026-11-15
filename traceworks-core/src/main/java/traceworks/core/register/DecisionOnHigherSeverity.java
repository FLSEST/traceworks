package traceworks.core.register;

import java.util.Optional;

/**
 * What a register does with a finding a person accepted or dismissed when a report states it again with a severity
 * higher than the one the decision was made on ({@link Finding.DispositionRecord#severity}): the operator's
 * {@code decision_on_higher_severity} in {@code traceworks.json}. A decision covers the finding it was made on, so
 * by default the finding is open again; an equal or lower severity never touches a decision.
 */
public enum DecisionOnHigherSeverity {

    /** The finding is open again ({@code finding.reopened}, reason {@code higher_severity: LOW → CRITICAL}). */
    REOPEN("reopen"),

    /** The decision stays; the register shows the severity now and the severity decided on. */
    KEEP("keep"),

    /**
     * How a version before the setting applied a report: the decision stays, and the state does not name the severity
     * it was made on. Only the replay of a {@code report.applied} line without {@code decision_on_higher_severity}
     * uses it, so that such a line folds to the state it recorded; no setting names it.
     */
    UNCHECKED(null);

    /** The default. */
    public static final DecisionOnHigherSeverity DEFAULT = REOPEN;

    private final String word;

    DecisionOnHigherSeverity(String word) {
        this.word = word;
    }

    /** The word in {@code traceworks.json} and in {@code report.applied}; {@code null} for {@link #UNCHECKED}. */
    public String word() {
        return word;
    }

    /** The value {@code word} names; empty for any other text or {@code null}. */
    public static Optional<DecisionOnHigherSeverity> of(String word) {
        for (DecisionOnHigherSeverity value : values()) {
            if (value.word != null && value.word.equals(word)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
