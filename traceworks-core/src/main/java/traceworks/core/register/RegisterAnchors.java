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
 *
 * <p>An anchor of a key and a project also says what was written since the anchor before it
 * ({@link RegisterAnchor.Written}), so the ledger alone tells what happened to a register. That is derived here
 * from the log ({@link #written}), when an anchor is written and again when it is checked: an anchor that says
 * anything else than the log gives is a problem, field by field.
 */
public final class RegisterAnchors {

    private RegisterAnchors() {
    }

    /**
     * What stands in an anchor in the place of a value that has not the shape of its field. A line someone
     * appended to a register by hand can hold any text as its run, its agent, the id of its finding or its type,
     * and any length or control character as its actor; the anchor says that such a value was there, and nothing
     * of the text. An actor is the one free text an anchor holds, within its bounds ({@link #actor}): it is the
     * operator's own, as in the start entry of a run.
     */
    public static final String MALFORMED = "(malformed)";

    /** The types of line this version writes; any other type is counted under {@link #MALFORMED}. */
    private static final java.util.Set<String> TYPES = java.util.Set.of(RegisterEvents.CREATED,
            RegisterEvents.ASSIGNED, RegisterEvents.REPORT_APPLIED, RegisterEvents.REPORT_REJECTED,
            RegisterEvents.FINDING_NEW, RegisterEvents.FINDING_RESOLVED, RegisterEvents.FINDING_REOPENED,
            RegisterEvents.FINDING_REVIEW_DUE, RegisterEvents.FINDING_ACCEPTED, RegisterEvents.FINDING_DISMISSED,
            RegisterEvents.HOOK_RAN, RegisterEvents.REPAIRED, RegisterEvents.UNANCHORED, RegisterEvents.REQUIRES);

    /** The id of a finding: a SHA-256, lowercase hex. */
    private static final java.util.regex.Pattern FINDING_ID = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /** A run id is the name of a folder too, so it is no longer than a file name may be. */
    private static final int RUN_ID_MAX = 255;

    private static boolean run(String value) {
        return value.length() <= RUN_ID_MAX && traceworks.core.port.Ids.isRunId(value);
    }

    private static boolean finding(String value) {
        return FINDING_ID.matcher(value).matches();
    }

    /**
     * An agent is a name: within the bounds a decision's command applies to it ({@link DecisionAgent}: its length
     * and its characters), with no white space around it.
     */
    private static boolean name(String value) {
        return DecisionAgent.problem(value) == null && value.equals(value.strip());
    }

    /** How long an actor may be and still stand in an anchor. */
    public static final int ACTOR_MAX_CHARS = 255;

    /**
     * An actor is the operator's own text, as in the start entry of a run: who a home says acts
     * ({@code user@host}, or what {@code actor} in {@code traceworks.json} or {@code TRACEWORKS_ACTOR} names, such
     * as {@code Ann Lee <ann@example.org>}). It stands in an anchor as it is while it has 1 to
     * {@value #ACTOR_MAX_CHARS} characters, none of them a control character; any other value of a line's
     * {@code actor} is under the marker.
     */
    private static boolean actor(String value) {
        int length = value.codePointCount(0, value.length());
        return length >= 1 && length <= ACTOR_MAX_CHARS && value.codePoints().noneMatch(Character::isISOControl);
    }

    /** {@code value} when it has the shape of its field, else the marker; {@code null} for no value. */
    private static String shaped(String value, java.util.function.Predicate<String> shape) {
        return value == null ? null : shape.test(value) ? value : MALFORMED;
    }

    private static boolean all(List<String> values, java.util.function.Predicate<String> shape) {
        return values.stream().allMatch(value -> MALFORMED.equals(value) || shape.test(value));
    }

    /**
     * Whether every value of what an anchor says has the shape of its field, or is the marker: what
     * {@link #written} gives always is. The reader of the ledger asks it of every line, so a line that holds a free
     * text is no anchor.
     */
    public static boolean shaped(RegisterAnchor.Written written) {
        return all(List.copyOf(written.lines().keySet()), TYPES::contains) && all(written.runs(), RegisterAnchors::run)
                && all(written.actors(), RegisterAnchors::actor) && all(written.agents(), RegisterAnchors::name)
                && all(written.decided(), RegisterAnchors::finding);
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
     * What an anchor says of the lines it covers ({@link RegisterAnchor.Written}). This is the one place that
     * derives it: the writer of an anchor and the check of one both ask here, with the lines of the log, so no
     * caller says what it wrote. A value has the shape of its field (the run a run id, a finding its id, an actor
     * the text of an actor, an agent a name, a type one this version writes), or {@link #MALFORMED} stands in its
     * place, for the writer and the check alike.
     *
     * @param covered the lines the log gained since the anchor before, in order
     */
    public static RegisterAnchor.Written written(List<RegisterEvent> covered) {
        java.util.SortedMap<String, Integer> lines = new java.util.TreeMap<>();
        java.util.SortedSet<String> runs = new java.util.TreeSet<>();
        java.util.SortedSet<String> actors = new java.util.TreeSet<>();
        java.util.SortedSet<String> agents = new java.util.TreeSet<>();
        java.util.SortedSet<String> decided = new java.util.TreeSet<>();
        for (RegisterEvent event : covered) {
            // Each value by its shape: a line appended by hand can hold any text, and none of it reaches an anchor
            lines.merge(shaped(event.type(), TYPES::contains), 1, Integer::sum);
            add(runs, shaped(event.text("run_id"), RegisterAnchors::run));
            add(actors, shaped(event.text("actor"), RegisterAnchors::actor));
            add(agents, shaped(event.text("agent"), RegisterAnchors::name));
            // A report reopens a finding too; a person's reopening is the one with the reason "manual", which is
            // how the replay tells the two apart
            boolean byAPerson = switch (event.type()) {
                case RegisterEvents.FINDING_ACCEPTED, RegisterEvents.FINDING_DISMISSED -> true;
                case RegisterEvents.FINDING_REOPENED -> "manual".equals(event.text("reason"));
                default -> false;
            };
            if (byAPerson) {
                add(decided, shaped(event.text("id"), RegisterAnchors::finding));
            }
        }
        return new RegisterAnchor.Written(lines, List.copyOf(runs), List.copyOf(actors), List.copyOf(agents),
                List.copyOf(decided));
    }

    /**
     * The lines an anchor covers: those the log gained since the last anchor before it that the log still agrees
     * with ({@link #agreed}), up to the anchor's own line; all lines up to it when there is none. So the anchor of
     * a write after a cut, or to a log that was replaced, says what was written.
     *
     * @param lines  the lines of the log, in order
     * @param before the last anchor before this one that the log agrees with ({@link #agreed} of the anchors the
     *               ledger holds before it); {@code null} for none
     * @param seq    the sequence number of the anchor
     * @return the covered lines; none when the anchor before reaches as far
     */
    public static <T> List<T> covered(List<T> lines, RegisterAnchor before, int seq) {
        int from = before == null ? 0 : Math.max(0, before.seq() + 1);
        int to = Math.min(seq + 1, lines.size());
        return from >= to ? List.of() : lines.subList(from, to);
    }

    /**
     * Whether the log still agrees with {@code anchor}: its sequence number is in the log, and the line there
     * hashes to its head.
     */
    private static boolean agrees(List<byte[]> lines, RegisterAnchor anchor) {
        return anchor.seq() >= 0 && anchor.seq() < lines.size()
                && EventChain.sha256Hex(lines.get(anchor.seq())).equals(anchor.head());
    }

    /**
     * The last of {@code anchors}, in the order the ledger holds them, that the log still agrees with: its
     * sequence number is in the log, and the line there hashes to its head. It is the anchor before the next one:
     * what {@link #covered} counts from. An anchor the log no longer agrees with (its line was cut, or the log was
     * replaced) says nothing of the lines that are there, so it is passed over.
     *
     * @param lines   the lines of the log, in order
     * @param anchors the anchors the ledger holds, oldest first
     * @return that anchor; {@code null} when the log agrees with none, and every line is covered by the next one
     */
    public static RegisterAnchor agreed(List<byte[]> lines, List<RegisterAnchor> anchors) {
        for (RegisterAnchor anchor : anchors.reversed()) {
            if (agrees(lines, anchor)) {
                return anchor;
            }
        }
        return null;
    }

    /**
     * Compares what an anchor says was written with what {@link #written} gives of the lines it covers, and adds
     * each difference: which anchor, which field, what the anchor says and what the log says.
     */
    private static void said(List<byte[]> lines, RegisterAnchor before, RegisterAnchor anchor,
                             List<String> problems) {
        String at = "seq " + anchor.seq() + ": ";
        List<byte[]> covered = covered(lines, before, anchor.seq());
        int first = anchor.seq() - covered.size() + 1;
        String range = covered.isEmpty() ? "no line" : "seq " + first + (covered.size() == 1 ? "" : " to "
                + anchor.seq());
        RegisterAnchor.Written log;
        try {
            log = written(covered.stream().map(RegisterEvent::parse).toList());
        } catch (IllegalArgumentException e) {
            // the chain report names the line
            problems.add(at + "what the anchor in the ledger says was written cannot be compared: a line it covers ("
                    + range + ") is no line of a register");
            return;
        }
        RegisterAnchor.Written says = anchor.written();
        String covers = ", and the lines it covers (" + range + ") give ";
        if (!says.lines().equals(log.lines())) {
            problems.add(at + "the anchor in the ledger says lines " + counts(says.lines()) + covers
                    + counts(log.lines()));
        }
        differs(problems, at, "run", says.runs(), log.runs(), covers);
        differs(problems, at, "actor", says.actors(), log.actors(), covers);
        differs(problems, at, "agent", says.agents(), log.agents(), covers);
        differs(problems, at, "decided", says.decided(), log.decided(), covers);
    }

    private static void differs(List<String> problems, String at, String field, List<String> says, List<String> log,
                                String covers) {
        if (!says.equals(log)) {
            problems.add(at + "the anchor in the ledger says " + field + " " + words(says) + covers + field + " "
                    + words(log));
        }
    }

    private static String words(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String counts(java.util.SortedMap<String, Integer> lines) {
        List<String> counts = new ArrayList<>();
        lines.forEach((type, count) -> counts.add(type + " " + count));
        return words(counts);
    }

    private static void add(java.util.Set<String> values, String value) {
        if (value != null) {
            values.add(value);
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
     * @param earlier the anchors a version before registers had projects wrote of the register under its key
     *                alone, oldest first: they hold the sequence number and the head and are checked by those
     * @param own     the anchors of the register of its key and project, oldest first, which follow the earlier
     *                ones: each also says what was written since the anchor before it, and must say what the log
     *                says
     */
    public static Check check(List<byte[]> lines, List<RegisterAnchor> earlier, List<RegisterAnchor> own) {
        List<RegisterAnchor> anchors = new ArrayList<>(earlier);
        anchors.addAll(own);
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
        // What each anchor of the project says was written, against the lines it covers. Only for an anchor whose
        // own line is the one in the log: of any other the log is not what was anchored, which is said above, and
        // the lines it covered are not there to count. One that says nothing is no anchor of this version at all
        // The lines it covers are those since the last anchor before it that the log still agrees with: the
        // writer counted from there, and an anchor of a line that is gone covers nothing that is in the log
        RegisterAnchor before = agreed(lines, earlier);
        for (RegisterAnchor anchor : own) {
            boolean agrees = agrees(lines, anchor);
            if (anchor.written() == null) {
                problems.add("seq " + anchor.seq() + ": the anchor in the ledger does not say what was written (it"
                        + " has no lines), and this version writes no such anchor for the register of a project");
            } else if (agrees) {
                said(lines, before, anchor, problems);
            }
            if (agrees) {
                before = anchor;
            }
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
