package traceworks.core.port;

import java.util.List;

/**
 * The runs a ledger names, each with what the ledger says and holds of it. A run is named by the message of a commit
 * ({@code run <run-id> STARTED}, {@code run <run-id> <STATUS>}), which a later commit cannot take back, and by the
 * entries the ledger holds now.
 *
 * @param runs every run the ledger names, in the order of their ids
 */
public record LedgerRuns(List<Run> runs) {

    public LedgerRuns {
        runs = List.copyOf(runs);
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
     */
    public record Run(String runId, boolean startNamed, byte[] start, String endNamed, byte[] end, byte[] acks) {
    }
}
