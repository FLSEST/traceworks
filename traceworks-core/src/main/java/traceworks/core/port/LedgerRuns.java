package traceworks.core.port;

import java.util.List;
import java.util.Set;

/**
 * The runs a ledger names, read from its whole history.
 *
 * @param started the runs whose start the ledger noted, in the order of their ids
 * @param ended   the runs whose end the ledger recorded, in the order of their ids
 * @param removed the entries the history names and the ledger holds no more, because a later commit took them out,
 *                as their paths ({@code starts/<run-id>.json}, {@code runs/<run-id>.json}); Traceworks never
 *                removes an entry
 */
public record LedgerRuns(Set<String> started, Set<String> ended, List<String> removed) {

    public LedgerRuns {
        started = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(started));
        ended = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(ended));
        removed = List.copyOf(removed);
    }
}
