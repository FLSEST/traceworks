package traceworks.core.compare;

import traceworks.core.port.BlobRef;

import java.util.List;
import java.util.Objects;

/**
 * Two runs side by side, A and B: the header of each, the inputs key by key, the steps, and whether B reproduced A.
 *
 * @param header      every header field with the value of each run, in a fixed order
 * @param inputs      the recorded inputs key by key; empty when a run's inputs blob was deleted (a note says so)
 * @param steps       the steps of both runs, matched as {@link RunComparer} describes, child runs' steps included
 * @param differences why B did not reproduce A, one reason each; empty when it did
 * @param notes       what a reader has to know about the comparison itself, such as a deleted inputs blob
 * @param findings    for two audit runs, what changed in their register between them; {@code null} otherwise
 */
public record RunComparison(String runA, String runB, List<Field> header, List<Input> inputs, List<Step> steps,
                            List<String> differences, List<String> notes, FindingsDelta findings) {

    public RunComparison {
        header = List.copyOf(header);
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
        differences = List.copyOf(differences);
        notes = List.copyOf(notes);
    }

    /** Whether run B reproduced run A: nothing that counts differs ({@link RunComparer}). */
    public boolean reproduced() {
        return differences.isEmpty();
    }

    /** The same comparison with the findings of the runs' register. */
    public RunComparison withFindings(FindingsDelta delta) {
        return new RunComparison(runA, runB, header, inputs, steps, differences, notes, delta);
    }

    /**
     * A header field.
     *
     * @param a the value of run A; {@code null} when it has none
     */
    public record Field(String name, String a, String b) {

        public boolean same() {
            return Objects.equals(a, b);
        }
    }

    /**
     * One input.
     *
     * @param same   whether both records hold the same value under the key
     * @param hidden whether a record holds a redacted value here: neither value is handed on then
     * @param a      the value of run A as JSON; {@code null} when the run has no such input, or it is hidden
     */
    public record Input(String key, boolean same, boolean hidden, String a, String b) {}

    /** How two outputs compare, by the hash of their blobs. */
    public enum Output {
        /** Both have the same output. */
        SAME,
        /** Both have one, and they differ. */
        DIFFERS,
        /** Neither has one. */
        NONE,
        /** Only run A has one. */
        ONLY_A,
        /** Only run B has one. */
        ONLY_B;

        static Output of(BlobRef a, BlobRef b) {
            if (a == null || b == null) {
                return a == null && b == null ? NONE : a == null ? ONLY_B : ONLY_A;
            }
            return a.sha256().equals(b.sha256()) ? SAME : DIFFERS;
        }

        /** Whether the two sides do not hold the same. */
        public boolean differs() {
            return this == DIFFERS || this == ONLY_A || this == ONLY_B;
        }
    }

    /**
     * A step of one or both runs.
     *
     * @param label   the block name, with {@code #n} from its second occurrence on, and the steps of a child run
     *                under their parent's label ({@code parent > child})
     * @param ai      whether it is an AI step: the block can ask a model, or it made an AI call in a run
     * @param statusA the status in run A; {@code null} when the step is in run B only
     * @param outputA the output blob in run A; {@code null} for none
     * @param calls   its AI calls, matched by item and order
     * @param items   its {@code foreach} items, matched by index
     */
    public record Step(String label, boolean ai, String statusA, String statusB, Output output, BlobRef outputA,
                       BlobRef outputB, List<AiCall> calls, List<Item> items) {

        public Step {
            calls = List.copyOf(calls);
            items = List.copyOf(items);
        }

        /** Whether the step is in both runs. */
        public boolean inBoth() {
            return statusA != null && statusB != null;
        }

        /** Whether status and output are the same in both runs. */
        public boolean same() {
            return inBoth() && statusA.equals(statusB) && !output.differs();
        }
    }

    /**
     * A {@code foreach} item of one or both runs.
     *
     * @param sameItem whether both runs had the same item at this index
     * @param statusA  {@code null} when run A has no such item
     */
    public record Item(int index, boolean sameItem, String statusA, String statusB, Output output, BlobRef outputA,
                       BlobRef outputB) {

        /** Whether item, status and output are the same in both runs. */
        public boolean same() {
            return sameItem && Objects.equals(statusA, statusB) && !output.differs();
        }
    }

    /**
     * An AI call of one or both runs.
     *
     * @param label                 {@code AI call n}, with the item in front for a call of a {@code foreach} item
     * @param a                     the call of run A; {@code null} when only run B made it
     * @param setupDifferences      the parts of the setup that differ, such as {@code model}; a prompt that follows
     *                              a differing AI output is not among them
     * @param promptSame            whether both prompts have the same hash
     * @param promptFollowsAiOutput whether the prompts differ and an AI output before the call differed, so the
     *                              difference is that output carried on
     * @param output                how the answers compare
     */
    public record AiCall(String label, Call a, Call b, List<String> setupDifferences, boolean promptSame,
                         boolean promptFollowsAiOutput, Output output, BlobRef outputA, BlobRef outputB) {

        public AiCall {
            setupDifferences = List.copyOf(setupDifferences);
        }

        /** Whether both runs made the call. */
        public boolean inBoth() {
            return a != null && b != null;
        }
    }

    /**
     * One run's side of an AI call.
     *
     * @param model      the model asked for; {@code null} for the CLI's default
     * @param answeredBy the model that answered; {@code null} when the call reported none
     * @param web        the web mode with the hosts of a fetch, such as {@code fetch (example.org:443)}
     * @param local      the model server of the operator's the call went to, as
     *                   {@code local backend <id> at <url>}; {@code null} for a call to the provider
     */
    public record Call(String provider, String model, String answeredBy, String effort, String tools, String web,
                       Integer inputTokens, Integer outputTokens, Double costUsd, String local) {

        /** One side of a call to the provider. */
        public Call(String provider, String model, String answeredBy, String effort, String tools, String web,
                    Integer inputTokens, Integer outputTokens, Double costUsd) {
            this(provider, model, answeredBy, effort, tools, web, inputTokens, outputTokens, costUsd, null);
        }
    }

    /**
     * What changed in the register of two audit runs between the state run A left and the state run B left.
     *
     * @param register the register's key
     * @param note     why the findings are not compared; {@code null} when they are
     * @param added    the findings run B's state has and run A's has not
     * @param resolved the findings that are resolved in run B's state and were not in run A's
     * @param reopened the findings that are open in run B's state and were resolved, accepted, dismissed or awaiting
     *                 verification in run A's
     */
    public record FindingsDelta(String register, String note, List<Finding> added, List<Finding> resolved,
                                List<Finding> reopened) {

        public FindingsDelta {
            added = List.copyOf(added);
            resolved = List.copyOf(resolved);
            reopened = List.copyOf(reopened);
        }

        /** Findings that are not compared, with the reason. */
        public static FindingsDelta notCompared(String register, String note) {
            return new FindingsDelta(register, note, List.of(), List.of(), List.of());
        }
    }

    /** A finding as a comparison names it. */
    public record Finding(String id, String severity, String subject, String title) {}
}
