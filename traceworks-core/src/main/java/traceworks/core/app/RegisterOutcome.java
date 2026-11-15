package traceworks.core.app;

import traceworks.core.register.Change;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * What a run's end did to its register.
 *
 * @param key     the register's key
 * @param applied whether the report was applied; {@code false} for a rejected report or a register that failed
 * @param reason  why it was not applied; {@code null} when it was
 * @param hookExitCode the exit code of the operator's hook, when one ran; {@code null} otherwise
 * @param counts  how many findings were new, seen, resolved, reopened, …; empty when not applied
 * @param items   the items of the report, counted; {@code null} for a report that lists none, for one that was not
 *                applied, and for a run the register took in before
 * @param unanchored why the ledger could not anchor the register's head after this write (docs/registers.md, "The
 *                anchor"); {@code null} when it did, and for a run the register took in before. The write is kept
 * @param anchorNote what this write found when it compared the register with its anchors before it wrote: lines
 *                after the last anchor, which it took as they were and recorded ({@code register.unanchored}), or a
 *                log that does not match its anchors; {@code null} when the last line was anchored
 */
public record RegisterOutcome(String key, boolean applied, String reason, Map<Change.Kind, Integer> counts,
                              Integer hookExitCode, ItemTally items, String unanchored, String anchorNote) {

    /** An outcome of a write that found the register's last line anchored. */
    public RegisterOutcome(String key, boolean applied, String reason, Map<Change.Kind, Integer> counts,
                           Integer hookExitCode, ItemTally items, String unanchored) {
        this(key, applied, reason, counts, hookExitCode, items, unanchored, null);
    }

    /** An outcome whose write the ledger anchored. */
    public RegisterOutcome(String key, boolean applied, String reason, Map<Change.Kind, Integer> counts,
                           Integer hookExitCode, ItemTally items) {
        this(key, applied, reason, counts, hookExitCode, items, null, null);
    }

    /**
     * The items of an applied report.
     *
     * @param listed  how many items the report lists
     * @param judged  how many of them this run gave a verdict for
     * @param settled how many are clean or decided in the register after the report
     */
    public record ItemTally(int listed, int judged, int settled) {
    }

    /** An outcome without a hook and without items. */
    public RegisterOutcome(String key, boolean applied, String reason, Map<Change.Kind, Integer> counts) {
        this(key, applied, reason, counts, null, null);
    }

    /** The same outcome, with the exit code of the hook that ran afterwards. */
    public RegisterOutcome withHook(int exitCode) {
        return new RegisterOutcome(key, applied, reason, counts, exitCode, items, unanchored, anchorNote);
    }

    /** The same outcome, with the items of its report counted. */
    public RegisterOutcome withItems(ItemTally tally) {
        return new RegisterOutcome(key, applied, reason, counts, hookExitCode, tally, unanchored, anchorNote);
    }

    /** The same outcome, with the reason the ledger gave for not anchoring it; {@code null} keeps what it has. */
    public RegisterOutcome withUnanchored(String why) {
        return why == null ? this
                : new RegisterOutcome(key, applied, reason, counts, hookExitCode, items, why, anchorNote);
    }

    /** The same outcome, with what the write found of the anchors before it wrote; {@code null} keeps what it has. */
    public RegisterOutcome withAnchorNote(String note) {
        return note == null ? this : new RegisterOutcome(key, applied, reason, counts, hookExitCode, items,
                unanchored, anchorNote == null ? note : anchorNote + "; " + note);
    }

    public RegisterOutcome {
        Map<Change.Kind, Integer> copy = new EnumMap<>(Change.Kind.class);
        if (counts != null) {
            copy.putAll(counts);
        }
        counts = Collections.unmodifiableMap(copy);
    }

    /**
     * {@code N new, N resolved, N reopened, N open now} for the summary a run prints; and how many of the open ones
     * the report did not mention, when a rule a model judges kept them open; and for an audit that lists its items,
     * how many of them are settled and how many this run judged; and at the end, when the ledger could not anchor the
     * register's head, that and why.
     */
    public String summary() {
        String anchor = (anchorNote == null ? "" : "; " + anchorNote)
                + (unanchored == null ? "" : "; not anchored in the ledger (" + unanchored + ")");
        if (!applied) {
            return "not applied — " + reason + anchor;
        }
        int silent = counts.getOrDefault(Change.Kind.NOT_MENTIONED, 0);
        return counts.getOrDefault(Change.Kind.NEW, 0) + " new, " + counts.getOrDefault(Change.Kind.RESOLVED, 0)
                + " resolved, " + counts.getOrDefault(Change.Kind.REOPENED, 0) + " reopened, "
                + (counts.getOrDefault(Change.Kind.SEEN, 0) + counts.getOrDefault(Change.Kind.NEW, 0)
                + counts.getOrDefault(Change.Kind.REOPENED, 0) + silent) + " open now"
                + (silent == 0 ? "" : ", " + silent + " of them not mentioned by this report")
                + (hookExitCode == null ? "" : hookExitCode == 0 ? "; hook ran" : "; hook exited " + hookExitCode)
                + (items == null ? "" : "; items " + items.settled() + " of " + items.listed() + " settled, "
                + items.judged() + " judged in this run") + anchor;
    }
}
