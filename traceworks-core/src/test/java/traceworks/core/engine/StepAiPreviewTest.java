package traceworks.core.engine;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The AI settings a running step shows before it ends. */
class StepAiPreviewTest {

    private final AiProviders registry = mock(AiProviders.class);
    private final StepAiPreview preview = new StepAiPreview(registry);

    private final ExecutionContext context = ExecutionContext.builder()
            .workflowRunId("run-1").provider("CLAUDE").model("opus").effort("medium").build();

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name("b").config(new HashMap<>(config)).build();
    }

    private AiProvider provider(String id, AiProviderCapabilities capabilities) {
        AiProvider provider = mock(AiProvider.class);
        when(provider.getId()).thenReturn(id);
        when(provider.getCapabilities()).thenReturn(capabilities);
        return provider;
    }

    @Test
    void aiBlock_namesProviderModelAndEffort_asTheExecutorWillResolveThem() {
        AiProvider claude = provider("CLAUDE", AiProviderCapabilities.full());
        when(registry.resolve("CLAUDE")).thenReturn(claude);

        assertThat(preview.of(block("simple", Map.of("command", "x")), context)).isEqualTo(Map.of(
                "provider", "CLAUDE", "requestedModel", "opus", "model", "opus",
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus", "effort", "medium")));
        // block keys win over the workflow's
        assertThat(preview.of(block("agentic", Map.of("goal", "x", "model", "haiku", "effort", "high")), context))
                .containsEntry("model", "haiku")
                .containsEntry("aiRequest", Map.of("provider", "CLAUDE", "model", "haiku", "effort", "high"));
    }

    @Test
    void providerWithoutEffort_claimsNone_andAnUnsetModelStaysUnset() {
        AiProvider pi = provider("PI", AiProviderCapabilities.minimal());
        when(registry.resolve("PI")).thenReturn(pi);
        ExecutionContext piRun = ExecutionContext.builder().workflowRunId("run-1").provider("PI").effort("high").build();

        assertThat(preview.of(block("simple", Map.of("command", "x")), piRun)).isEqualTo(Map.of(
                "provider", "PI", "aiRequest", Map.of("provider", "PI")));
    }

    @Test
    void wrappers_inAiMode_areAiSteps() {
        AiProvider claude = provider("CLAUDE", AiProviderCapabilities.full());
        when(registry.resolve("CLAUDE")).thenReturn(claude);

        for (String type : List.of("foreach", "while", "conditional", "retry")) {
            assertThat(preview.of(block(type, Map.of("command", "x")), context)).as(type).containsKey("aiRequest");
        }
        assertThat(preview.of(block("compress-ai", Map.of("source", "s")), context)).containsKey("aiRequest");
    }

    @Test
    void blocksWithoutAnAiCallOfTheirOwn_getNothing() {
        for (BlockDefinition block : List.of(
                block("shell", Map.of("command", "ls")),
                block("approval", Map.of("question", "?")),
                block("sub-workflow", Map.of("workflow_id", "wf-2")),
                block("switch", Map.of("expression", "x")),
                block("foreach", Map.of("command", "x", "execution_mode", "shell")),
                block("while", Map.of("command", "x", "execution_mode", "shell")),
                block("conditional", Map.of("condition", "x", "execution_mode", "shell")),
                block("retry", Map.of("command", "x", "execution_mode", "shell")),
                block("foreach", Map.of("items", List.of("a"), "workflow_id", "wf-2")))) {
            assertThat(preview.of(block, context)).as(block.getType() + " " + block.getConfig()).isEmpty();
        }
    }

    @Test
    void anInvalidExecutionMode_leavesThePlaceholderAsBefore_insteadOfThrowing() {
        // the executor reports the typo as a failed step; the preview must not fail startStep before that
        assertThat(preview.of(block("foreach", Map.of("command", "x", "execution_mode", "bash")), context)).isEmpty();
        assertThat(preview.of(block("while", Map.of("command", "x", "execution_mode", 42)), context)).isEmpty();
    }

    @Test
    void anUnresolvableProvider_leavesThePlaceholderAsBefore() {
        assertThat(preview.of(block("simple", Map.of("command", "x")), context)).as("no provider").isEmpty();
        when(registry.resolve("CLAUDE")).thenThrow(new IllegalArgumentException("unknown AI provider 'CLAUDE'"));
        assertThat(preview.of(block("simple", Map.of("command", "x")), context)).as("provider refused").isEmpty();
    }
}
