package traceworks.core.port;

import java.util.List;
import java.util.SortedMap;

/**
 * The head of a register's log as the ledger holds it, outside the register's folder: the line with this sequence
 * number had this hash when it was written. An anchor also says what was written since the anchor before it
 * ({@link Written}), so the ledger alone tells what happened to a register and by whom, without any content of it.
 *
 * @param seq     the sequence number of the last line of the log at the time
 * @param head    the SHA-256 of that line, lowercase hex
 * @param written what the lines the anchor covers are: the lines the log gained since the anchor before it, all
 *                lines for the first one. {@code null} for a line that says only {@code seq} and {@code head}: the
 *                shape a version before registers had projects wrote under the key alone, and nothing this version
 *                writes
 */
public record RegisterAnchor(int seq, String head, Written written) {

    /** An anchor of the earlier shape, which says nothing of what was written. */
    public RegisterAnchor(int seq, String head) {
        this(seq, head, null);
    }

    /**
     * What the lines an anchor covers say of themselves, and nothing of their content: no title, subject, note,
     * severity or evidence. It is derived from the register's log alone, in one place ({@code RegisterAnchors} of
     * the register), and never said by the writer of the lines.
     *
     * @param lines   each type of the covered lines with its count, sorted by type
     * @param runs    the run ids the covered lines name, sorted, each once; empty when none names one
     * @param actors  who decided or assigned, as the covered lines record it, sorted, each once
     * @param agents  the agents of the decisions in the covered lines, sorted, each once
     * @param decided the ids of the findings a person accepted, dismissed or reopened in the covered lines, sorted,
     *                each once
     */
    public record Written(SortedMap<String, Integer> lines, List<String> runs, List<String> actors,
                          List<String> agents, List<String> decided) {

        public Written {
            lines = java.util.Collections.unmodifiableSortedMap(new java.util.TreeMap<>(lines));
            runs = List.copyOf(runs);
            actors = List.copyOf(actors);
            agents = List.copyOf(agents);
            decided = List.copyOf(decided);
        }
    }
}
