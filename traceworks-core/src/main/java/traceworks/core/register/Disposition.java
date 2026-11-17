package traceworks.core.register;

import java.time.Duration;
import java.time.Instant;

/**
 * What a person decides about a finding: to accept it as a risk (with a note and a review date), to dismiss it (with
 * a note), or to reopen it. The transitions are checked (docs/workflow-format.md): a decision carries who, when and
 * why. A
 * decision keeps the severity the finding had when it was made: that is what it covers
 * ({@link DecisionOnHigherSeverity}). Who is the actor that ran the command, and the agent that acted under that
 * actor when it named itself ({@link DecisionAgent}).
 */
public final class Disposition {

    /** How far ahead a review date may lie: the review interval of an accepted risk. */
    public static final Duration MAX_UNTIL = AcceptancePolicy.REVIEW_INTERVAL;

    private Disposition() {
    }

    /**
     * @param until the review date; {@code null} for {@link #MAX_UNTIL} after {@code at}
     * @throws IllegalArgumentException for a missing note or a review date in the past or beyond the interval
     * @throws IllegalStateException    for a finding that is not open or awaiting verification
     */
    public static Finding accept(Finding finding, String note, String actor, Instant at, Instant until) {
        return accept(finding, note, actor, at, until, null);
    }

    /**
     * As {@link #accept(Finding, String, String, Instant, Instant)}, by an agent under the actor.
     *
     * @param agent the name the agent gave itself ({@link DecisionAgent}); {@code null} for none
     */
    public static Finding accept(Finding finding, String note, String actor, Instant at, Instant until,
                                 String agent) {
        requireNote(note, "accept");
        requireFrom(finding, FindingStatus.ACCEPTED_RISK, FindingStatus.OPEN, FindingStatus.AWAITING_VERIFICATION);
        Instant review = until == null ? at.plus(MAX_UNTIL) : until;
        if (review.isBefore(at)) {
            throw new IllegalArgumentException("the review date " + review + " lies in the past");
        }
        if (review.isAfter(at.plus(MAX_UNTIL))) {
            throw new IllegalArgumentException("the review date " + review + " lies more than " + MAX_UNTIL.toDays()
                    + " days ahead, which is as far as an acceptance reaches");
        }
        return with(finding, FindingStatus.ACCEPTED_RISK, new Finding.DispositionRecord(FindingStatus.ACCEPTED_RISK,
                note.strip(), actor, at, review, finding.evidence(), finding.severity(), agent));
    }

    /** @throws IllegalArgumentException for a missing note; {@link IllegalStateException} as {@link #accept} */
    public static Finding dismiss(Finding finding, String note, String actor, Instant at) {
        return dismiss(finding, note, actor, at, null);
    }

    /** As {@link #dismiss(Finding, String, String, Instant)}, by an agent under the actor; {@code null} for none. */
    public static Finding dismiss(Finding finding, String note, String actor, Instant at, String agent) {
        requireNote(note, "dismiss");
        requireFrom(finding, FindingStatus.DISMISSED, FindingStatus.OPEN, FindingStatus.AWAITING_VERIFICATION);
        return with(finding, FindingStatus.DISMISSED, new Finding.DispositionRecord(FindingStatus.DISMISSED,
                note.strip(), actor, at, null, null, finding.severity(), agent));
    }

    /**
     * Opens an accepted, dismissed or resolved finding again and drops its disposition.
     *
     * @throws IllegalStateException for a finding that is open or awaiting verification already
     */
    public static Finding reopen(Finding finding, String note, String actor, Instant at) {
        requireFrom(finding, FindingStatus.OPEN, FindingStatus.ACCEPTED_RISK, FindingStatus.DISMISSED,
                FindingStatus.RESOLVED);
        return with(finding, FindingStatus.OPEN, null);
    }

    /**
     * What the decision on {@code finding} was made on, for where a decided finding is shown: {@code made on severity
     * LOW}, and where the finding has another severity now, {@code made on severity LOW, not on the CRITICAL the
     * finding has now}, so that nobody reads the decision as covering a severity it was never made on. {@code null}
     * for a finding without a decision, and where the severity is not known.
     */
    public static String madeOn(Finding finding) {
        if (finding.disposition() == null || finding.disposition().severity() == null) {
            return null;
        }
        return "made on severity " + finding.disposition().severity() + (finding.decidedOnOtherSeverity() == null ? ""
                : ", not on the " + finding.severity() + " the finding has now");
    }

    private static void requireNote(String note, String what) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("a note is required to " + what + " a finding: why");
        }
    }

    private static void requireFrom(Finding finding, FindingStatus to, FindingStatus... from) {
        for (FindingStatus allowed : from) {
            if (finding.status() == allowed) {
                return;
            }
        }
        throw new IllegalStateException("the finding is " + finding.status() + "; only " + java.util.Arrays.toString(from)
                .replace("[", "").replace("]", "") + " can become " + to);
    }

    private static Finding with(Finding f, FindingStatus status, Finding.DispositionRecord disposition) {
        return new Finding(f.id(), f.ruleKey(), f.subject(), f.severity(), f.title(), f.description(), f.evidence(),
                f.tags(), status, f.firstSeen(), f.lastSeen(), f.occurrences(), f.regressions(), f.unverifiedEvidence(),
                null, disposition, 0, f.item());
    }
}
