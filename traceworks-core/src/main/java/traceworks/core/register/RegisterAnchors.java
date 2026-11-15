package traceworks.core.register;

import traceworks.core.port.RegisterAnchor;
import traceworks.core.record.EventChain;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a register's log with the anchors the ledger holds of it (docs/registers.md, "The anchor"). The chain
 * of a log says that no line before the last was changed; it cannot say that the last line is the one that was
 * written, or that no line was cut from the end. The ledger can: after every write it was told the sequence number
 * and the hash of the last line, and it keeps every such anchor. Each must still be true of the log.
 */
public final class RegisterAnchors {

    private RegisterAnchors() {
    }

    /**
     * What the comparison found.
     *
     * @param problems what does not hold, each naming the seq; empty when every anchor matches its line
     * @param summary  one line for a person: {@code ok (...)}, {@code ok up to seq N; ...} for lines after the last
     *                 anchor, {@code none: ...} for a register the ledger has not anchored, or {@code NOT ok}
     */
    public record Check(List<String> problems, String summary) {

        public Check {
            problems = List.copyOf(problems);
        }
    }

    /**
     * The lines after the last anchor, for a writer that is about to write: {@code {first, last}} sequence numbers,
     * or {@code null} when there is no anchor yet or the last line is anchored.
     */
    public static int[] tail(int lastSeq, List<RegisterAnchor> anchors) {
        int anchored = anchors.stream().mapToInt(RegisterAnchor::seq).max().orElse(-1);
        return anchors.isEmpty() || anchored >= lastSeq ? null : new int[] {anchored + 1, lastSeq};
    }

    /**
     * @param lines   the lines of the register's log, in order
     * @param anchors the anchors of the register, oldest first
     */
    public static Check check(List<byte[]> lines, List<RegisterAnchor> anchors) {
        if (anchors.isEmpty()) {
            return new Check(List.of(), "none: the ledger holds no anchor of this register, so a changed last line or"
                    + " a cut end would not be found (it was written before registers were anchored, or with another"
                    + " ledger); the next write anchors it");
        }
        List<String> problems = new ArrayList<>();
        int last = -1;
        int lastSeq = lines.size() - 1;
        for (RegisterAnchor anchor : new java.util.LinkedHashSet<>(anchors)) {
            String short12 = anchor.head() == null ? "?" : anchor.head().substring(0, Math.min(12, anchor.head().length()));
            if (anchor.seq() < 0 || anchor.seq() > lastSeq) {
                problems.add("seq " + anchor.seq() + ": the ledger anchors this line (" + short12 + "), but the log"
                        + (lastSeq < 0 ? " is empty" : " ends at seq " + lastSeq) + ", so lines were cut from its end");
            } else if (!EventChain.sha256Hex(lines.get(anchor.seq())).equals(anchor.head())) {
                problems.add("seq " + anchor.seq() + ": the line is not the one the ledger anchors (" + short12
                        + "), so it was changed after it was written, or the log was cut and written on");
            }
            last = Math.max(last, anchor.seq());
        }
        if (!problems.isEmpty()) {
            return new Check(problems, "NOT ok");
        }
        if (last < lastSeq) {
            boolean one = last + 1 == lastSeq;
            return new Check(List.of(), "ok up to seq " + last + "; seq " + (last + 1)
                    + (one ? " is" : " to " + lastSeq + " are") + " not anchored, so " + (one ? "it is" : "they are")
                    + " unproven: the ledger could not be written when " + (one ? "it was" : "they were")
                    + ", or " + (one ? "it was" : "they were") + " appended without Traceworks, and nothing tells the"
                    + " two apart. The next write takes " + (one ? "it" : "them") + " as " + (one ? "it is" : "they are")
                    + ", anchors the head and records which lines it found without an anchor (register.unanchored)");
        }
        return new Check(List.of(), "ok (seq " + last + " is the head the ledger holds, "
                + new java.util.LinkedHashSet<>(anchors).size() + " anchors)");
    }
}
