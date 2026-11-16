package traceworks.core.port;

import java.util.List;
import java.util.Optional;

/**
 * Port to the ledger: every run adds one entry when it starts, before its first step, and every finished run one
 * with a byte-identical copy of its manifest; the history of the ledger chains all runs. A run that never ended, or
 * whose folder is gone, is still named here. It also anchors the head of every register (docs/registers.md, "The
 * anchor"): a register's log is written by runs and by a person's decisions, and the ledger is the place outside
 * the register's folder that says where its log ended after each of them.
 */
public interface Ledger {

    /**
     * Makes sure the ledger can take an entry, so a run is not started that could not be recorded: for the git
     * ledger, git runs and the repository exists or is created.
     *
     * @throws IllegalStateException with the reason when it cannot
     */
    void prepare();

    /**
     * Records a finished run.
     *
     * @param runId        the run id
     * @param status       the final status of the run
     * @param manifestJson the manifest bytes, stored unchanged
     * @return the id of the ledger entry (the commit id)
     * @throws IllegalStateException if the run is already recorded, or the ledger cannot be written
     */
    String record(String runId, String status, byte[] manifestJson);

    /** The recorded manifest of a run, or empty when the run is not recorded. */
    Optional<byte[]> find(String runId);

    /**
     * Notes that a run started: its record holds {@code run.started}, and no step has run yet.
     *
     * @param runId     the run id
     * @param startJson the start entry (docs/events.md, "The start entry"), stored unchanged
     * @return the id of the ledger entry (the commit id)
     * @throws IllegalStateException if the start of the run is already noted, or the ledger cannot be written
     */
    String recordStart(String runId, byte[] startJson);

    /**
     * The start entry of a run, or empty when the ledger noted no start of it.
     *
     * @throws IllegalStateException with the reason when the ledger exists and cannot be read: "no start entry"
     *                               must not be what a damaged ledger says
     */
    Optional<byte[]> findStart(String runId);

    /**
     * Every run the ledger names, by the messages of its whole history and by the entries it holds now, so an entry
     * that a later commit removed still counts.
     *
     * @throws IllegalStateException with the reason when the ledger exists and cannot be read, or holds something
     *                               that shows another history than the one its commits hold
     */
    LedgerRuns runs();

    /**
     * What the history did to the entries of one run that Traceworks never does ({@link LedgerRuns.Run#changes}):
     * an entry is added once, by the commit that names it, and never touched again. Empty when the entries are as
     * they were added, and for a run the ledger does not name.
     *
     * @throws IllegalStateException with the reason when the ledger exists and cannot be read
     */
    List<String> changes(String runId);

    /**
     * When the ledger began: the time of its oldest commit. Empty for a ledger without a commit. A run that started
     * later is one the ledger should know.
     *
     * @throws IllegalStateException with the reason when the ledger exists and cannot be read
     */
    default java.util.Optional<java.time.Instant> began() {
        return java.util.Optional.empty();
    }

    /**
     * Records that a person acknowledged a run the check of the ledger against the runs folder reports: one more
     * entry of that run, which the ledger keeps for good.
     *
     * @param runId the run
     * @param line  the acknowledgement, one JSON line (who, when, what was reported, the note)
     * @throws IllegalStateException with the reason when the ledger cannot take it
     */
    void acknowledge(String runId, byte[] line);

    /**
     * Anchors the head of a register after a write: the ledger keeps every anchor it was given, in order. The same
     * anchor as the last one is not added again.
     *
     * @param key  the register's key
     * @param seq  the sequence number of the last line of the register's log
     * @param head the SHA-256 of that line
     * @throws IllegalStateException with the reason when the ledger cannot take it
     */
    default void anchorRegister(String key, int seq, String head) {
        throw new IllegalStateException("this ledger anchors no register");
    }

    /**
     * Every anchor of the register {@code key}, oldest first; empty for a register the ledger has not anchored.
     *
     * @throws IllegalStateException with the reason when the ledger cannot be read
     */
    default List<RegisterAnchor> registerAnchors(String key) {
        return List.of();
    }
}
