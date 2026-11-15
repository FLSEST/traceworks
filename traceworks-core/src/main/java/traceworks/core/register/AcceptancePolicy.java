package traceworks.core.register;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The rules of an accepted risk: it reopens when its evidence got worse than the snapshot
 * frozen at the acceptance (a value more than {@value #DEGRADATION_THRESHOLD_PERCENT} % higher, a churn that reached
 * the hotspot threshold, a new advisory id), and it comes up for review at its review date
 * ({@code --until}, else {@link #REVIEW_INTERVAL} after the acceptance). The review date is checked on every
 * applied report, whether or not the finding was seen (decided 2026-11-11): a risk nobody sees any more still comes
 * up for review.
 */
public final class AcceptancePolicy {

    public static final int DEGRADATION_THRESHOLD_PERCENT = 10;
    public static final int HOTSPOT_MIN_CHANGES = 5;
    public static final Duration REVIEW_INTERVAL = Duration.ofDays(180);

    public enum Outcome { UNCHANGED, REOPENED, REVIEW_DUE }

    /** @param detail what got worse, as {@code worse: 320 LOC → 360 LOC, new advisory CVE-…}; {@code null} otherwise */
    public record Assessment(Outcome outcome, String detail) {
    }

    private AcceptancePolicy() {
    }

    /**
     * @param finding         an accepted risk; any other status is unchanged
     * @param currentEvidence the evidence of the sighting; {@code null} when the finding was not seen this time
     * @param observedAt      the time of the report
     */
    public static Assessment reassess(Finding finding, String currentEvidence, Instant observedAt) {
        if (finding.status() != FindingStatus.ACCEPTED_RISK || finding.disposition() == null) {
            return new Assessment(Outcome.UNCHANGED, null);
        }
        if (currentEvidence != null) {
            List<String> degradations = degradations(finding.disposition().evidence(), currentEvidence);
            if (!degradations.isEmpty()) {
                return new Assessment(Outcome.REOPENED, "worse: " + String.join(", ", degradations));
            }
        }
        Instant reviewAt = finding.disposition().until() != null ? finding.disposition().until()
                : finding.disposition().at() == null ? null : finding.disposition().at().plus(REVIEW_INTERVAL);
        if (reviewAt != null && observedAt != null && !observedAt.isBefore(reviewAt)) {
            return new Assessment(Outcome.REVIEW_DUE, null);
        }
        return new Assessment(Outcome.UNCHANGED, null);
    }

    /** What got worse between the snapshot and the current evidence, in words; empty when nothing did. */
    public static List<String> degradations(String snapshotEvidence, String currentEvidence) {
        Map<String, Double> before = EvidenceMetrics.parse(snapshotEvidence);
        Map<String, Double> after = EvidenceMetrics.parse(currentEvidence);
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : before.entrySet()) {
            Double current = after.get(entry.getKey());
            if (current == null) {
                continue;
            }
            double old = entry.getValue();
            boolean degraded = old == 0 ? current > 0 : (current - old) / old * 100 > DEGRADATION_THRESHOLD_PERCENT;
            if (degraded) {
                result.add(EvidenceMetrics.format(old, entry.getKey()) + " → "
                        + EvidenceMetrics.format(current, entry.getKey()));
            }
        }
        churnDegradation(snapshotEvidence, currentEvidence).ifPresent(result::add);
        newAdvisoryIds(snapshotEvidence, currentEvidence).ifPresent(result::add);
        return result;
    }

    private static Optional<String> newAdvisoryIds(String snapshotEvidence, String currentEvidence) {
        if (snapshotEvidence == null || snapshotEvidence.isBlank()) {
            return Optional.empty();
        }
        Set<String> added = EvidenceMetrics.advisoryIds(currentEvidence);
        added.removeAll(EvidenceMetrics.advisoryIds(snapshotEvidence));
        if (added.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((added.size() == 1 ? "new advisory " : "new advisories ") + String.join(", ", added));
    }

    private static Optional<String> churnDegradation(String snapshotEvidence, String currentEvidence) {
        OptionalInt before = EvidenceMetrics.churn(snapshotEvidence);
        OptionalInt after = EvidenceMetrics.churn(currentEvidence);
        if (before.isEmpty() || after.isEmpty()) {
            return Optional.empty();
        }
        int threshold = EvidenceMetrics.hotspotThreshold(currentEvidence).orElse(HOTSPOT_MIN_CHANGES);
        if (before.getAsInt() >= threshold || after.getAsInt() < threshold) {
            return Optional.empty();
        }
        return Optional.of(before.getAsInt() + " changes → " + after.getAsInt() + " changes (hotspot from "
                + threshold + ")");
    }
}
