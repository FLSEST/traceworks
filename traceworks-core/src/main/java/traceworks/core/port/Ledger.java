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
     * The name the check of the whole home lists a register under, and under which a problem of it is acknowledged
     * like one of a run: it has the form of a run id. No two registers share a name. A key and a project may both
     * hold dots, so the name says how long the key is: {@code anchors.<length of the key>.<key>.<project>}. The
     * anchors an earlier version wrote under a key alone are listed as {@code registers.<key>}, as they always
     * were.
     *
     * <p>The name is the name of a file too (the acknowledgements of a register lie in {@code acks/<name>.jsonl}),
     * and a key and a project may have 128 characters each. A name of more than {@value #REGISTER_NAME_MAX}
     * characters is therefore written {@code anchors.sha256.<SHA-256 of key/project>}: no key is {@code sha256}
     * where the other form has a number, and no key or project holds a {@code /}, so it is still the name of one
     * register. The sentence of each problem names the key and the project.
     *
     * @param project the project the register is of; {@code null} for the register from before registers had
     *                projects, and for the anchors an earlier version wrote of it
     */
    static String registerName(String key, String project) {
        if (project == null) {
            return "registers." + key;
        }
        String name = "anchors." + key.length() + "." + key + "." + project;
        return name.length() <= REGISTER_NAME_MAX ? name : REGISTER_NAME_HASHED + BlobStore.sha256Hex((key + "/"
                + project).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * How the name of a register begins that does not say its key and its project, because they are too long for
     * a file name; the SHA-256 of {@code <key>/<project>} follows.
     */
    String REGISTER_NAME_HASHED = "anchors.sha256.";

    /** How long the name of a register may be and still be written out: with what a file name adds, it fits one. */
    int REGISTER_NAME_MAX = 200;

    /**
     * A register the ledger holds anchors of.
     *
     * @param project the project the register is of; {@code null} for anchors an earlier version wrote under the
     *                key alone, before registers had projects
     */
    record Anchored(String key, String project) {
    }

    /**
     * Every register the ledger holds anchors of, so that a register whose folder is gone is still named here.
     *
     * @throws IllegalStateException with the reason when the ledger cannot be read
     */
    default List<Anchored> anchoredRegisters() {
        return List.of();
    }

    /**
     * Anchors the head of a register after a write: the ledger keeps every anchor it was given, in order. The same
     * anchor as the last one is not added again.
     *
     * @param key     the register's key
     * @param project the project the register is of
     * @param anchor  the sequence number and the SHA-256 of the last line of the register's log, and what was
     *                written since the anchor before it ({@link RegisterAnchor#written}), which the ledger keeps
     *                with them; an anchor that does not say it is not one this version writes
     * @throws IllegalStateException with the reason when the ledger cannot take it
     */
    default void anchorRegister(String key, String project, RegisterAnchor anchor) {
        throw new IllegalStateException("this ledger anchors no register");
    }

    /**
     * Every anchor of the register of {@code key} and {@code project}, oldest first; empty for a register the ledger
     * has not anchored.
     *
     * @throws IllegalStateException with the reason when the ledger cannot be read
     */
    default List<RegisterAnchor> registerAnchors(String key, String project) {
        return List.of();
    }

    /**
     * The anchors an earlier version wrote of the register {@code key}, from before registers had projects, oldest
     * first; empty when the ledger holds none. This version reads them and adds none: they stay what the ledger
     * said of that register's log, and are the beginning of its anchors once it is assigned to a project
     * (docs/registers.md, "The anchor").
     *
     * @throws IllegalStateException with the reason when the ledger cannot be read
     */
    default List<RegisterAnchor> earlierRegisterAnchors(String key) {
        return List.of();
    }
}
