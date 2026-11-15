package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.blocks.compress.CompressAiPrompt;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompressAiBlockExecutorTest {

    @Mock private AiStepCall aiCall;
    @Mock private AiProvider provider;

    private CompressAiBlockExecutor executor;
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        executor = new CompressAiBlockExecutor(aiCall, CompressAiBlockExecutor.DEFAULT_MAX_AI_INPUT_CHARS);
        when(provider.getId()).thenReturn("CLAUDE");
        ctx = contextWith(AiProviders.of("CLAUDE", List.of(provider)));
    }

    private static ExecutionContext contextWith(AiProviders providers) {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").aiProviders(providers).build();
        context.setProjectPath("/project");
        Map<String, Object> md = new HashMap<>(Map.of("blockType", "shell", "exitCode", 1));
        StepResult source = StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output("LOG " + "x".repeat(100))
                .durationMs(5_000L).metadata(md).build();
        context.recordOutput("backend_tests", source.getOutput());
        context.getSourceSteps().record("backend_tests", source);
        return context;
    }

    private static BlockDefinition block(Map<String, Object> cfg) {
        return BlockDefinition.builder().type("compress-ai").name("core").config(cfg).build();
    }

    private static StepResult aiAnswer(StepStatus status, String output) {
        return StepResult.builder().status(status).input("PROMPT WITH SOURCE").output(output)
                .metadata(new HashMap<>(Map.of("aiRequest", Map.of("provider", "CLAUDE", "model", "haiku")))).build();
    }

    @Test
    void supportsCompressAiOnly() {
        assertThat(executor.supports("compress-ai")).isTrue();
        assertThat(executor.supports("compress")).isFalse();
    }

    /**
     * The call gets no tools and sees the workspace read-only, in the run's container like every AI call; it no
     * longer gets a temporary directory of its own on the host, which a container would not see.
     */
    @Test
    void callsProviderWithoutTools_inTheReadOnlyWorkspace_withFixedSystemPrompt() {
        when(aiCall.run(any(), any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "2 tests failed: A, B"));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "instruction", "Name errors {{ open_todos }}")), ctx);

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(aiCall).run(captor.capture(), any());
        AiStepRequest req = captor.getValue();
        assertThat(req.tools()).isEqualTo(ToolPolicy.NONE);
        assertThat(req.writableWorkspace()).isFalse();
        assertThat(req.workingDirectory()).isEqualTo("/project");
        assertThat(req.systemPrompt()).isEqualTo(CompressAiPrompt.SYSTEM);
        assertThat(req.prompt()).contains("<compress_source>\nLOG ").contains("Name errors {{ open_todos }}");

        assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(r.getOutput()).startsWith("## backend_tests — FAILED · exit 1 · 5 s")
                .contains("\nSummary: AI (CLAUDE)\n\n2 tests failed: A, B");
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("aiRequest").containsKey("compression");
        assertThat(r.getInput()).doesNotContain("x".repeat(100)).contains("Name errors");
    }

    /** A record of the call that cannot be written is no failed step: it ends the run as an event-log failure. */
    @Test
    void aRecordWriteThatFails_propagates() {
        when(aiCall.run(any(), any())).thenThrow(new traceworks.core.engine.EventLogException(
                new java.io.IOException("disk full")));

        assertThatThrownBy(() -> executor.execute(block(Map.of("source", "backend_tests")), ctx))
                .isInstanceOf(traceworks.core.engine.EventLogException.class);
    }

    @Test
    void inputOverLimit_failsWithoutCall() {
        CompressAiBlockExecutor small = new CompressAiBlockExecutor(aiCall, 50);

        StepResult r = small.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).contains("put a compress block before this one");
        verifyNoInteractions(aiCall);
    }

    @Test
    void providerOtherThanClaude_failsWithoutCall() {
        when(provider.getId()).thenReturn("PI");
        ExecutionContext pi = contextWith(AiProviders.of("PI", List.of(provider)));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), pi);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).contains("compress-ai supports only the provider CLAUDE — set")
                .contains("\"provider\": \"CLAUDE\"").doesNotContain("without tools");
        verifyNoInteractions(aiCall);
    }

    /** The run's providers refuse an unknown provider id: the step fails without a call and never falls back. */
    @Test
    void unknownProvider_failsWithoutCall() {
        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "provider", "NOPE")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).startsWith("Compression failed: IllegalArgumentException: unknown AI provider 'NOPE'");
        verifyNoInteractions(aiCall);
    }

    @Test
    void aiFailure_isFailed_noFallbackToOriginal() {
        when(aiCall.run(any(), any())).thenReturn(aiAnswer(StepStatus.FAILED, "Error: timeout"));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).contains("AI compression failed").doesNotContain("x".repeat(100));
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("aiRequest");
    }

    @Test
    void blockModelEffortAndProvider_reachTheRequest() {
        when(aiCall.run(any(), any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "short"));

        executor.execute(block(Map.of("source", "backend_tests", "model", "haiku", "effort", "low", "provider", "CLAUDE")), ctx);

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(aiCall).run(captor.capture(), any());
        assertThat(captor.getValue().model()).isEqualTo("haiku");
        assertThat(captor.getValue().effort()).isEqualTo("low");
        assertThat(captor.getValue().providerId()).isEqualTo("CLAUDE");
    }

    @Test
    void blankAnswer_isFailed_keepsAiMetadata() {
        when(aiCall.run(any(), any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "  \n "));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).isEqualTo("AI compression failed: AI returned no answer");
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("aiRequest").doesNotContainKey("compression");
    }

    @Test
    void aiFailure_outputIsNullSafeAndCappedTo2000Chars() {
        String prefix = "AI compression failed: ";
        when(aiCall.run(any(), any())).thenReturn(aiAnswer(StepStatus.FAILED, "E".repeat(9_000) + "END"));

        StepResult longFailure = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(longFailure.getOutput()).startsWith(prefix).endsWith("END").contains("chars omitted");
        assertThat(longFailure.getOutput().length()).isLessThanOrEqualTo(prefix.length() + 2_000);

        when(aiCall.run(any(), any())).thenReturn(aiAnswer(StepStatus.FAILED, null));

        StepResult nullFailure = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(nullFailure.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(nullFailure.getOutput()).startsWith(prefix).doesNotContain("null");
    }

    @Test
    void unexpectedRuntimeException_afterCall_isFailed_keepsAiMetadata() {
        StepResult broken = mock(StepResult.class);
        when(broken.getMetadata()).thenReturn(new HashMap<>(Map.of("aiRequest", Map.of("provider", "CLAUDE"))));
        when(broken.getStatus()).thenThrow(new IllegalStateException("broken"));
        when(aiCall.run(any(), any())).thenReturn(broken);

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).isEqualTo("Compression failed: IllegalStateException: broken");
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("error").containsKey("aiRequest");
    }

    @Test
    void tooLongAnswer_isCappedDeterministically() {
        when(aiCall.run(any(), any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "y".repeat(5000)));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "max_chars", 1000)), ctx);

        assertThat(r.getOutput().length()).isLessThanOrEqualTo(1000);
        assertThat(((Map<?, ?>) r.getMetadata().get("compression")).get("capped")).isEqualTo(true);
    }
}
