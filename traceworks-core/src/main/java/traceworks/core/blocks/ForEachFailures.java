package traceworks.core.blocks;

import traceworks.core.engine.StepAiMetadata;
import traceworks.core.engine.StepFailureSummary;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.util.TruncationUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * How a {@code foreach} counts failed items, when it stops early and how it reports both, split off
 * {@link ForEachBlockExecutor}. One instance tallies one run of the block: the counters, the stop rule and the status
 * formula live together, so a change to one cannot leave the others behind.
 */
final class ForEachFailures {

    static final int ITEM_LABEL_MAX_CHARS = 120;
    /** Budget of the failure summary: failed item names, then the error of the first one (head + tail). */
    private static final int FAILED_LABEL_MAX_CHARS = 60;
    private static final int FAILED_NAMES_MAX_CHARS = 160;
    private static final int ERROR_HEAD_CHARS = 80;
    private static final int ERROR_TAIL_CHARS = 240;
    /**
     * Default of {@code max_consecutive_failures}: after this many failed items in a row the loop stops instead of
     * calling the rest. A quota error ("You've hit your session limit") fails every following item too and only burns
     * time. {@code 0} switches it off.
     */
    static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    private final int totalItems;
    private final int maxConsecutiveFailures;
    private final boolean continueOnError;
    /** The run's secret values, which a cut of the summary keeps whole. */
    private final java.util.Collection<String> keepWhole;
    private final List<String> failedItems = new ArrayList<>();
    private String firstFailureOutput;
    private int consecutiveFailures;
    private int skippedItems;

    /** @param keepWhole the run's secret values ({@code ExecutionContext#secretValues}), which no cut splits */
    ForEachFailures(int totalItems, int maxConsecutiveFailures, boolean continueOnError,
                    java.util.Collection<String> keepWhole) {
        this.totalItems = totalItems;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.continueOnError = continueOnError;
        this.keepWhole = keepWhole;
    }

    /**
     * Counts the result of item {@code index}. After {@code max_consecutive_failures} failed items in a row the loop
     * stops ({@link #aborted()}), unless no item is left: then it simply ran to its end.
     */
    void record(int index, String item, StepResult itemResult) {
        if (itemResult.getStatus() == StepStatus.FAILED) {
            failedItems.add(item);
            if (firstFailureOutput == null) {
                firstFailureOutput = itemResult.getOutput() != null ? itemResult.getOutput() : "";
            }
            consecutiveFailures++;
        } else {
            consecutiveFailures = 0;
        }
        if (maxConsecutiveFailures > 0 && consecutiveFailures >= maxConsecutiveFailures && index < totalItems - 1) {
            skippedItems = totalItems - 1 - index;
        }
    }

    /** Whether the loop stopped early with items left unrun. */
    boolean aborted() {
        return skippedItems > 0;
    }

    /** Why the loop stopped early, {@code null} when it did not. */
    String abortReason() {
        return aborted() ? abortReason(consecutiveFailures, skippedItems) : null;
    }

    /** continue_on_error tolerates failed items, not a loop that stopped with items left unrun. */
    boolean failsTheBlock() {
        return (!failedItems.isEmpty() && !continueOnError) || aborted();
    }

    /**
     * Writes the tally onto the block's step: the abort and the failed items into metadata and output, and, when the
     * block fails, the run error (abort reason first, then {@link #failureSummary}).
     */
    void report(StepResult aggregate, StringBuilder outputs) {
        String abortReason = abortReason();
        if (abortReason != null) {
            StepAiMetadata.put(aggregate, "abortedAfterConsecutiveFailures", consecutiveFailures);
            StepAiMetadata.put(aggregate, "skippedItemCount", skippedItems);
            outputs.append("\n[max_consecutive_failures] ").append(abortReason);
        }
        if (failedItems.isEmpty()) return;
        StepAiMetadata.put(aggregate, "failedItemCount", failedItems.size());
        StepAiMetadata.put(aggregate, "failedItems", List.copyOf(failedItems));
        if (continueOnError) {
            outputs.append("\n[continue_on_error] ").append(failedItems.size()).append("/").append(totalItems)
                    .append(" items failed: ").append(String.join(", ", failedItems));
        }
        if (failsTheBlock()) {
            String summary = failureSummary(failedItems, firstFailureOutput, totalItems, keepWhole);
            StepFailureSummary.put(aggregate, abortReason != null ? abortReason + "\n" + summary : summary);
        }
    }

    /**
     * Where the block failed, for the run error: the failed items first, then the first one with its error,
     * {@code "2/5 items failed: a, b\n[a]: <error>"}. The output cannot say that: it lists every item in order, so a
     * failed item sits between successful ones.
     */
    static String failureSummary(List<String> failedItems, String firstFailureOutput, int totalItems,
                                 java.util.Collection<String> keepWhole) {
        StringBuilder names = new StringBuilder();
        int listed = 0;
        for (String item : failedItems) {
            String label = TruncationUtil.head(item, FAILED_LABEL_MAX_CHARS, keepWhole);
            if (listed > 0 && names.length() + label.length() > FAILED_NAMES_MAX_CHARS) break;
            names.append(listed > 0 ? ", " : "").append(label);
            listed++;
        }
        if (listed < failedItems.size()) {
            names.append(", … (+").append(failedItems.size() - listed).append(")");
        }
        String error = TruncationUtil.smart(firstFailureOutput.strip(), ERROR_HEAD_CHARS, ERROR_TAIL_CHARS,
                keepWhole);
        return failedItems.size() + "/" + totalItems + " items failed: " + names
                + "\n[" + TruncationUtil.head(failedItems.get(0), ITEM_LABEL_MAX_CHARS, keepWhole) + "]: " + error;
    }

    /** Why the loop stopped early, for the output and the run error. */
    static String abortReason(int consecutiveFailures, int skippedItems) {
        return "stopped after " + consecutiveFailures + " consecutive failed items (max_consecutive_failures), "
                + skippedItems + " " + (skippedItems == 1 ? "item" : "items") + " not run";
    }
}
