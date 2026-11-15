package traceworks.core.context;

import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much output text a run may hold in memory: the operator's {@code run_output_budget_mib}. One budget serves a run
 * and its child runs, since they share the process's heap. What counts is the memory the text the run keeps beyond the
 * step that produced it takes ({@link #sizeOf}): the input and the output of every finished top-level step (in the run
 * and in a child run while it runs), what a {@code foreach}, a {@code while} or a {@code switch} has gathered so far,
 * the item list of an {@code items_command} while its loop runs, and the current values of the variables the run's steps set ({@code ExecutionContext#countVariables}). The copies the context keeps of a step's output
 * ({@code previous_output}, {@code step_N_output}, a top-level block's {@code block.<name>.output}) are the same text and
 * do not count again; the output of a named {@code switch} case block stays beside the switch's joined output
 * uncounted, bounded by the number of case blocks.
 *
 * <p>Text that would go beyond the budget is not taken ({@link #take}), and from then on the budget is
 * {@link #exceeded()}: the step that would push the run over fails ({@link #fail}), every loop stops at its next item,
 * and the run ends, whatever the block's {@code on_failure} says, and so does every run above a child run. No step
 * gets a cut text as if it were whole: a loop that stops keeps what it gathered and says why.
 *
 * <p>The run executes on one thread, its child runs on that thread too; the methods are synchronized all the same.
 */
public final class OutputBudget {

    /** The budget of a run that has none: it never runs out. */
    public static final long NONE = Long.MAX_VALUE;

    /** The least budget unless the operator sets one, in MiB. */
    public static final int DEFAULT_MIN_MIB = 128;

    /** The default budget is at least this many times the output limit. */
    public static final int DEFAULT_OUTPUT_LIMITS = 8;

    /** Step metadata key: {@code true} on a step that the budget failed ({@link #fail}). */
    public static final String METADATA_KEY = "outputBudgetExceeded";

    private final long limitBytes;
    private long heldBytes;
    private boolean exceeded;

    /**
     * @param limitBytes how much memory the run's text may take, in bytes ({@link #sizeOf}); {@link #NONE} for no
     *                   budget
     * @throws IllegalArgumentException if it is below 1
     */
    public OutputBudget(long limitBytes) {
        if (limitBytes < 1) {
            throw new IllegalArgumentException("An output budget is at least 1 byte: " + limitBytes);
        }
        this.limitBytes = limitBytes;
    }

    /** A budget that never runs out. */
    public static OutputBudget none() {
        return new OutputBudget(NONE);
    }

    /**
     * The budget unless the operator sets one, in MiB: {@value #DEFAULT_MIN_MIB}, or {@value #DEFAULT_OUTPUT_LIMITS}
     * times the output limit when that is more, so that raising the output limit does not make steps fail at the
     * budget.
     */
    public static int defaultMib(int outputLimitMib) {
        long mib = Math.max(DEFAULT_MIN_MIB, (long) DEFAULT_OUTPUT_LIMITS * outputLimitMib);
        return (int) Math.min(Integer.MAX_VALUE, mib);
    }

    /**
     * Takes {@code bytes} of memory for text the run is going to hold.
     *
     * @return {@code false}, and nothing is taken, when the text would take the run beyond its budget: the budget is
     *         {@link #exceeded()} from then on
     */
    public synchronized boolean take(long bytes) {
        if (bytes > limitBytes - heldBytes) {
            exceeded = true;
            return false;
        }
        heldBytes += bytes;
        return true;
    }

    /**
     * Counts {@code bytes} of text the run holds without a check: the texts of the checkpoint a run resumes from.
     */
    public synchronized void hold(long bytes) {
        heldBytes = bytes > NONE - heldBytes ? NONE : heldBytes + bytes;
    }

    /** Gives back {@code bytes} taken or held before: text the run no longer holds, or holds as another text now. */
    public synchronized void release(long bytes) {
        heldBytes = Math.max(0, heldBytes - bytes);
    }

    /** How much memory the run's text takes now, in bytes. */
    public synchronized long heldBytes() {
        return heldBytes;
    }

    /**
     * Gives back everything taken since {@code mark}, an earlier {@link #heldBytes()}: what a child run held, once it
     * has ended. Nothing the parent counts changes while its child runs.
     */
    public synchronized void releaseTo(long mark) {
        heldBytes = Math.min(heldBytes, Math.max(0, mark));
    }

    /** Whether text was refused: the run ends after its current step. */
    public synchronized boolean exceeded() {
        return exceeded;
    }

    /**
     * Whether no further block may start: text was refused ({@link #exceeded()}), or the run holds more than the budget
     * already, as a resume after the budget was lowered can.
     */
    public synchronized boolean usedUp() {
        return exceeded || heldBytes > limitBytes;
    }

    /** Why no block starts in a run whose budget is {@link #usedUp()}. */
    public String usedUpNote() {
        return "The output this run holds in memory exceeds its budget of " + CommandRunner.sizeText(limitBytes)
                + "; Traceworks starts no further block";
    }

    /** How much memory the run's text may take, in bytes; {@link #NONE} for no budget. */
    public long limitBytes() {
        return limitBytes;
    }

    /** The line a step that the budget fails gets after its output. */
    public String note() {
        return "The output this run holds in memory would exceed its budget of " + CommandRunner.sizeText(limitBytes)
                + "; Traceworks failed this step and ended the run";
    }

    /**
     * Fails {@code step} for the budget, once: {@code FAILED}, the {@link #note()} on a line of its own after its
     * output, and {@value #METADATA_KEY} in its metadata. A step that carries the key already stays as it is.
     */
    public void fail(StepResult step) {
        if (failedFor(step)) {
            return;
        }
        step.setStatus(StepStatus.FAILED);
        String output = step.getOutput() == null ? "" : step.getOutput();
        step.setOutput(output.isEmpty() ? note() : output + "\n" + note());
        Map<String, Object> metadata = new LinkedHashMap<>(step.getMetadata() != null ? step.getMetadata() : Map.of());
        metadata.put(METADATA_KEY, true);
        step.setMetadata(metadata);
    }

    /** Whether the budget failed {@code step} ({@link #fail}); a step that only prints the note is not such a step. */
    public static boolean failedFor(StepResult step) {
        return step.getMetadata() != null && Boolean.TRUE.equals(step.getMetadata().get(METADATA_KEY));
    }

    /** The memory a finished step's text takes: its input and its output ({@link #sizeOf(CharSequence)}). */
    public static long sizeOf(StepResult step) {
        return sizeOf(step.getInput()) + sizeOf(step.getOutput());
    }

    /**
     * The memory {@code text} takes as Java holds a string, in bytes: one byte per character when every character is at
     * most {@code U+00FF} (a compact Latin-1 string), two bytes per character otherwise; {@code 0} for {@code null}.
     * A text with a single {@code ✓} takes twice its length.
     */
    public static long sizeOf(CharSequence text) {
        if (text == null) {
            return 0;
        }
        int length = text.length();
        for (int i = 0; i < length; i++) {
            if (text.charAt(i) > 0xFF) {
                return 2L * length;
            }
        }
        return length;
    }

    /**
     * The memory the text of a variable's value takes ({@link #sizeOf(CharSequence)}): a text, the texts of a list,
     * and the keys and values of a map; {@code 0} for anything else, such as a number.
     */
    public static long sizeOf(Object value) {
        return switch (value) {
            case CharSequence text -> sizeOf(text);
            case Map<?, ?> map -> map.entrySet().stream()
                    .mapToLong(entry -> sizeOf(entry.getKey()) + sizeOf(entry.getValue())).sum();
            case Collection<?> items -> items.stream().mapToLong(OutputBudget::sizeOf).sum();
            case null, default -> 0;
        };
    }
}
