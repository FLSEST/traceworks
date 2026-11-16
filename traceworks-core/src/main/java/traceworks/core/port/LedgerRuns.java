package traceworks.core.port;

import java.util.List;

/**
 * The runs a ledger names, each with what the ledger says and holds of it. A run is named by the message of a commit
 * ({@code run <run-id> STARTED}, {@code run <run-id> <STATUS>}), which a later commit cannot take back, and by the
 * entries the ledger holds now.
 *
 * @param runs     every run the ledger names, in the order of their ids. The anchors of a register {@code <key>} are
 *                 among them under the name {@code registers.<key>}, with nothing but their changes: so a problem of
 *                 them is listed, and acknowledged, like one of a run
 * @param began when the ledger began, the time of its oldest commit; {@code null} for a ledger without a commit
 */
public record LedgerRuns(List<Run> runs, java.time.Instant began) {

    public LedgerRuns {
        runs = List.copyOf(runs);
    }

    /** The runs of a ledger that does not say when it began. */
    public LedgerRuns(List<Run> runs) {
        this(runs, null);
    }

    /**
     * What the ledger says and holds of one run.
     *
     * @param startNamed whether a commit message says that the run started
     * @param start      the start entry the ledger holds now; {@code null} when it holds none
     * @param endNamed   the status a commit message says the run ended with; {@code null} when none says so
     * @param end        the end entry the ledger holds now, a copy of the run's manifest; {@code null} when it holds
     *                   none
     * @param acks       the acknowledgements of the run the ledger holds now, one JSON line each, oldest first;
     *                   {@code null} when it holds none
     * @param changes    what the history did to the entries of the run that Traceworks never does, one sentence
     *                   each, naming the commit and the path: an entry that a commit other than the one that added
     *                   it changed or removed, an entry added under another message than its own, an
     *                   acknowledgement that was not only added to
     */
    public record Run(String runId, boolean startNamed, byte[] start, String endNamed, byte[] end, byte[] acks,
                      List<String> changes) {

        public Run {
            changes = List.copyOf(changes);
        }

        /** A run whose entries are as the commits that name them added them. */
        public Run(String runId, boolean startNamed, byte[] start, String endNamed, byte[] end, byte[] acks) {
            this(runId, startNamed, start, endNamed, end, acks, List.of());
        }
    }
}
