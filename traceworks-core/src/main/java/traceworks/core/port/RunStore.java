package traceworks.core.port;

import java.util.List;

/** Port to the place where runs are kept. */
public interface RunStore {

    /** Minimal length of a run id prefix. */
    int MIN_PREFIX = 4;

    /** Creates the run and returns a handle that holds its writer lock; an existing run id is an error. */
    StoredRun create(String runId, java.time.Instant startedAt);

    /**
     * Opens a run by id, by a unique prefix of at least {@value #MIN_PREFIX} characters or by the path of its folder.
     *
     * @throws NoSuchRunException        when nothing matches
     * @throws AmbiguousRunException     when a prefix matches several runs
     * @throws RunReferenceTooShortException when the prefix is too short
     */
    StoredRun open(String runRef);

    /** The ids of all runs, newest first. */
    List<String> runIds();

    /** Resolves an id or prefix among known ids; shared by the implementations so they agree. */
    static String match(String runRef, List<String> ids) {
        if (ids.contains(runRef)) {
            return runRef;
        }
        if (runRef.length() < MIN_PREFIX) {
            throw new RunReferenceTooShortException(runRef);
        }
        List<String> hits = ids.stream().filter(id -> id.startsWith(runRef)).toList();
        if (hits.isEmpty()) {
            throw new NoSuchRunException(runRef);
        }
        if (hits.size() > 1) {
            throw new AmbiguousRunException(runRef, hits);
        }
        return hits.get(0);
    }
}
