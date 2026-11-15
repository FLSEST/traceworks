package traceworks.core.engine;

import traceworks.core.model.StepResult;
import traceworks.core.util.TruncationUtil;

import java.util.Collection;
import java.util.List;

/**
 * The run error of a failed step: {@code "Step N (type) failed: ..."}.
 *
 * <p>By default its detail is cut from the step output, tail-biased, because the error of a single call (HTTP
 * status, exception, timeout) sits at the end of it. A foreach breaks that assumption: it runs every item even after
 * one failed, so the failed item can sit anywhere in a long transcript of successful ones, and a tail cut can drop
 * it. A block that knows where it failed writes that under {@link #KEY} into the step metadata, and the run error
 * starts with it.
 */
public final class StepFailureSummary {

    /** Step metadata key: where the step failed, written by blocks whose output does not end with it. */
    public static final String KEY = "failureSummary";

    private static final int OUTPUT_HEAD_CHARS = 100;
    private static final int OUTPUT_TAIL_CHARS = 400;

    private StepFailureSummary() {
    }

    /** Records where the step failed; {@link #runError} puts it first. */
    public static void put(StepResult step, String summary) {
        StepAiMetadata.put(step, KEY, summary);
    }

    /** The run error for a failed step: the block's own summary if it wrote one, else its output. */
    public static String runError(StepResult step, String blockType) {
        return runError(step, blockType, List.of());
    }

    /**
     * Like {@link #runError(StepResult, String)} in a run with secrets: the cut through the output keeps each of
     * their {@code values} whole, so the record can redact it.
     */
    public static String runError(StepResult step, String blockType, Collection<String> values) {
        Object summary = step.getMetadata() != null ? step.getMetadata().get(KEY) : null;
        String detail = summary instanceof String text && !text.isBlank()
                ? text
                : TruncationUtil.smart(step.getOutput() != null ? step.getOutput() : "",
                        OUTPUT_HEAD_CHARS, OUTPUT_TAIL_CHARS, values);
        return "Step " + step.getStepIndex() + " (" + blockType + ") failed: " + detail;
    }
}
