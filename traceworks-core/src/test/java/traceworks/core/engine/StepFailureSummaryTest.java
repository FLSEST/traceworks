package traceworks.core.engine;

import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepFailureSummaryTest {

    @Test
    void runError_startsWithTheSummaryTheBlockWrote() {
        StepResult step = failed(3, "[a]: GATE PASS\n[b]: API Error\n[c]: GATE PASS");
        StepFailureSummary.put(step, "1/3 items failed: b\n[b]: API Error");

        assertThat(StepFailureSummary.runError(step, "foreach"))
                .isEqualTo("Step 3 (foreach) failed: 1/3 items failed: b\n[b]: API Error");
    }

    @Test
    void runError_withoutSummary_keepsTheOutputTail() {
        StepResult step = failed(0, "x".repeat(970) + "HTTP 500 Internal Server Error");

        String message = StepFailureSummary.runError(step, "simple");

        assertThat(message).startsWith("Step 0 (simple) failed: xxx")
                .contains("[... skipped 500 of 1000 characters ...]")
                .endsWith("HTTP 500 Internal Server Error");
    }

    /**
     * Redaction finds a value only where it is whole: a cut through a secret would leave a part of it in the error,
     * so the cut moves to the edge of the secret and leaves it out whole.
     */
    @Test
    void runError_neverCutsASecretInTwo() {
        String secret = "s3cr3t-d3ploy-t0ken";
        String output = "x".repeat(95) + secret + "y".repeat(500) + secret + "z".repeat(390);
        StepResult step = failed(0, output);

        String message = StepFailureSummary.runError(step, "shell", java.util.List.of(secret));

        assertThat(message).startsWith("Step 0 (shell) failed: " + "x".repeat(95) + "\n[... skipped ")
                .endsWith("\n" + "z".repeat(390));
        assertThat(message.replace(secret, "")).doesNotContain("s3cr3t").doesNotContain("t0ken");
        assertThat(StepFailureSummary.runError(step, "shell")).as("without secrets the cut stays where it was")
                .startsWith("Step 0 (shell) failed: " + "x".repeat(95) + "s3cr3");
    }

    @Test
    void runError_blankSummaryOrNoOutput_fallsBackWithoutNull() {
        StepResult blank = failed(1, "boom");
        blank.setMetadata(Map.of(StepFailureSummary.KEY, "  "));
        StepResult empty = failed(2, null);
        empty.setMetadata(null);

        assertThat(StepFailureSummary.runError(blank, "shell")).isEqualTo("Step 1 (shell) failed: boom");
        assertThat(StepFailureSummary.runError(empty, "shell")).isEqualTo("Step 2 (shell) failed: ");
    }

    @Test
    void put_keepsTheOtherMetadata_evenOnAnImmutableMap() {
        StepResult step = failed(0, "out");
        step.setMetadata(Map.of("totalItems", 3));

        StepFailureSummary.put(step, "why");

        assertThat(step.getMetadata()).containsEntry("totalItems", 3).containsEntry(StepFailureSummary.KEY, "why");
    }

    private static StepResult failed(int index, String output) {
        return StepResult.builder().stepIndex(index).status(StepStatus.FAILED).output(output).build();
    }
}
