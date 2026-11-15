package traceworks.core.port;

import java.util.List;

/** A run id prefix matches more than one run. */
public final class AmbiguousRunException extends IllegalArgumentException {

    private final List<String> candidates;

    public AmbiguousRunException(String runRef, List<String> candidates) {
        super("'" + runRef + "' matches several runs: " + String.join(", ", candidates));
        this.candidates = List.copyOf(candidates);
    }

    /** The ids of the runs the reference matches. */
    public List<String> candidates() {
        return candidates;
    }
}
