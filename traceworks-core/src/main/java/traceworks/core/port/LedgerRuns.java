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
 * @param unjudged the paths of the entries no hash covers: written by a version whose messages named none, and not
 *                 pinned yet. They are not judged at all, so a check that holds says nothing of them
 * @param pins     the pins of the ledger, oldest first, one sentence each: the commit and how many entries it covers
 */
public record LedgerRuns(List<Run> runs, List<String> unjudged, List<String> pins) {

    public LedgerRuns {
        runs = List.copyOf(runs);
        unjudged = List.copyOf(unjudged);
        pins = List.copyOf(pins);
    }

    /** The runs of a ledger in which a hash covers every entry, and that has no pin. */
    public LedgerRuns(List<Run> runs) {
        this(runs, List.of(), List.of());
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
     * @param unjudged   the paths of this run's entries that no hash covers ({@link LedgerRuns#unjudged})
     * @param changes    what the history did to the entries of the run that Traceworks never does, one sentence
     *                   each, naming the commit and the path: an entry that a commit other than the one that added
     *                   it changed or removed, an entry added under another message than its own, an
     *                   acknowledgement that was not only added to
     */
    public record Run(String runId, boolean startNamed, byte[] start, String endNamed, byte[] end, byte[] acks,
                      List<String> changes, List<String> unjudged) {

        public Run {
            changes = List.copyOf(changes);
            unjudged = List.copyOf(unjudged);
        }

        /** A run all of whose entries a hash covers. */
        public Run(String runId, boolean startNamed, byte[] start, String endNamed, byte[] end, byte[] acks,
                   List<String> changes) {
            this(runId, startNamed, start, endNamed, end, acks, changes, List.of());
        }

        /** A run whose entries are as the commits that name them added them. */
        public Run(String runId, boolean startNamed, byte[] start, String endNamed, byte[] end, byte[] acks) {
            this(runId, startNamed, start, endNamed, end, acks, List.of());
        }
    }
}
