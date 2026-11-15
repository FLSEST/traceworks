package traceworks.core.port;

import java.util.List;
import java.util.Optional;

/**
 * Port to the ledger: every finished run adds one entry with a byte-identical copy of its manifest, and the
 * history of the ledger chains all runs. It also anchors the head of every register (docs/registers.md, "The
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
