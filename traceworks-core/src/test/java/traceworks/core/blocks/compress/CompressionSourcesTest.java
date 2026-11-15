package traceworks.core.blocks.compress;

import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.context.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressionSourcesTest {

    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = ExecutionContext.builder().workflowRunId("run-1").build();
    }

    private static StepResult step(int index, StepStatus status, Map<String, Object> metadata) {
        return StepResult.builder().id("step-" + index).stepIndex(index).status(status)
                .metadata(new HashMap<>(metadata)).build();
    }

    @Test
    void missingOrNonTextSource_fails() {
        for (Object raw : new Object[]{null, "  ", 42}) {
            assertThatThrownBy(() -> CompressionSources.resolve(ctx, raw))
                    .isInstanceOf(CompressionException.class).hasMessageContaining("'source' is missing");
        }
    }

    @Test
    void previous_withoutAnyStep_fails() {
        assertThatThrownBy(() -> CompressionSources.resolve(ctx, "previous"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("no step has run");
    }

    @Test
    void previous_usesLastStepAndPreviousOutput() {
        ctx.getSourceSteps().record("build", step(0, StepStatus.COMPLETED, Map.of()));
        ctx.getSourceSteps().record("tests", step(1, StepStatus.FAILED, Map.of("exitCode", 1)));
        ctx.setPreviousOutput("last output");

        CompressionSources.Resolved r = CompressionSources.resolve(ctx, "previous");

        assertThat(r.label()).isEqualTo("tests");
        assertThat(r.text()).isEqualTo("last output");
        assertThat(r.origin().status()).isEqualTo(StepStatus.FAILED);
        assertThat(r.origin().exitCode()).isEqualTo(1);
    }

    @Test
    void previous_unnamedStep_isLabelledByIndex_andNullOutputIsEmpty() {
        ctx.getSourceSteps().record(null, step(4, StepStatus.COMPLETED, Map.of()));

        CompressionSources.Resolved r = CompressionSources.resolve(ctx, "previous");

        assertThat(r.label()).isEqualTo("step 4");
        assertThat(r.text()).isEmpty();
    }

    @Test
    void namedSource_usesThatBlocksOutput() {
        ctx.getSourceSteps().record("build", step(0, StepStatus.COMPLETED, Map.of()));
        ctx.getSourceSteps().record("other", step(1, StepStatus.COMPLETED, Map.of()));
        ctx.setBlockOutput("build", "BUILD LOG");
        ctx.setPreviousOutput("other output");

        CompressionSources.Resolved r = CompressionSources.resolve(ctx, "build");

        assertThat(r.label()).isEqualTo("build");
        assertThat(r.text()).isEqualTo("BUILD LOG");
        assertThat(r.origin().stepIndex()).isZero();
    }

    @Test
    void namedSource_notYetRun_or_ambiguous_fails() {
        assertThatThrownBy(() -> CompressionSources.resolve(ctx, "later"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'later' has not run in this run yet");

        ctx.getSourceSteps().record("dup", step(0, StepStatus.COMPLETED, Map.of()));
        ctx.getSourceSteps().record("dup", step(1, StepStatus.COMPLETED, Map.of()));
        assertThatThrownBy(() -> CompressionSources.resolve(ctx, "dup"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("ambiguous");
    }

    @Test
    void derivedSource_pointsToOriginalStep_andUsesOnlyTheBody() {
        Map<String, Object> md = Map.of("derivedOutput", true, "compression",
                Map.of("source", Map.of("block", "tests", "stepIndex", 1, "status", "FAILED", "exitCode", 2)));
        ctx.getSourceSteps().record("short", step(2, StepStatus.COMPLETED, md));
        ctx.setBlockOutput("short", "## tests — FAILED\nStages: x\n\nCORE");

        CompressionSources.Resolved r = CompressionSources.resolve(ctx, "short");

        assertThat(r.label()).isEqualTo("tests (via short)");
        assertThat(r.text()).isEqualTo("CORE");
        assertThat(r.origin().blockName()).isEqualTo("tests");
        assertThat(r.origin().status()).isEqualTo(StepStatus.FAILED);
        assertThat(r.origin().exitCode()).isEqualTo(2);
    }
}
