package traceworks.core.blocks;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.CallPriority;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.WorkflowExecutionException;
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

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One AI call of a step through the run's providers, and the metadata the step carries about it.
 */
@ExtendWith(MockitoExtension.class)
class AiStepCallTest {

    private static final Instant T0 = Instant.parse("2026-11-02T08:15:30Z");

    @Mock
    private AiProvider aiProvider;

    private final AiStepCall call = new AiStepCall();
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        lenient().when(aiProvider.getId()).thenReturn("CLAUDE");
        // Claude-like: the effort reaches the CLI, so aiRequest carries it
        lenient().when(aiProvider.getCapabilities()).thenReturn(AiProviderCapabilities.full());
        context = contextWith(aiProvider, "CLAUDE");
    }

    private static ExecutionContext contextWith(AiProvider provider, String id) {
        return ExecutionContext.builder().workflowRunId("run-1")
                .aiProviders(AiProviders.of(id, List.of(provider)))
                .clock(() -> T0)
                .build();
    }

    private static AiStepRequest.Builder request(String prompt) {
        return AiStepRequest.builder().workingDirectory("/project").prompt(prompt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> aiRequestOf(StepResult result) {
        return (Map<String, Object>) result.getMetadata().get("aiRequest");
    }

    @Test
    void run_success_returnsCompletedStep() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("All tests passed", 0, null));

        StepResult result = call.run(request("npm test").stepIndex(0).build(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("All tests passed");
        assertThat(result.getStepIndex()).isEqualTo(0);
        assertThat(result.getInput()).isEqualTo("npm test");
        assertThat(result.getStartedAt()).isEqualTo(T0);
        assertThat(result.getCompletedAt()).isEqualTo(T0);
        assertThat(result.getDurationMs()).isNotNull();
    }

    /** A record of the call that cannot be written is no failed step: it ends the run as an event-log failure. */
    @Test
    void run_aRecordWriteThatFails_propagates() throws IOException {
        when(aiProvider.executeAction(any())).thenThrow(new EventLogException(new IOException("disk full")));

        assertThatThrownBy(() -> call.run(request("npm test").stepIndex(0).build(), context))
                .isInstanceOf(EventLogException.class).hasMessageContaining("disk full");
    }

    @Test
    void run_nonZeroExitCode_returnsFailedStep() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("Error: tests failed", 1, null));

        StepResult result = call.run(request("npm test").stepIndex(0).build(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void run_storesProviderIdInMetadata() throws IOException {
        AiProvider pi = org.mockito.Mockito.mock(AiProvider.class);
        when(pi.getId()).thenReturn("PI");
        when(pi.executeAction(any())).thenReturn(new AiProvider.ActionResult("output", 0, null));
        ExecutionContext withPi = ExecutionContext.builder().workflowRunId("run-1")
                .aiProviders(AiProviders.of("CLAUDE", List.of(aiProvider, pi))).build();

        StepResult result = call.run(request("pi task").providerId("PI").build(), withPi);

        assertThat(result.getMetadata()).containsEntry("provider", "PI");
    }

    @Test
    void run_storesAiSystemPromptAndAiRequestInMetadata() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));

        StepResult result = call.run(request("analyze something")
                .model("claude-opus-4-6")
                .systemPrompt("You are a helpful assistant.\nBe precise.")
                .timeoutSeconds(300)
                .build(), context);

        assertThat(result.getMetadata())
                .containsEntry("aiSystemPrompt", "You are a helpful assistant.\nBe precise.")
                .containsKey("aiRequest");
        assertThat(aiRequestOf(result))
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "claude-opus-4-6")
                .containsEntry("timeoutSeconds", 300)
                .doesNotContainKey("priority");
    }

    @Test
    void run_propagatesEffortToActionRequestAndMetadata() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));

        StepResult result = call.run(request("task").effort("high").build(), context);

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(aiProvider).executeAction(captor.capture());
        assertThat(captor.getValue().effort()).isEqualTo("high");
        assertThat(aiRequestOf(result)).containsEntry("effort", "high");
    }

    /** A provider without an effort flag ignores it: the call still gets it, the step does not claim it. */
    @Test
    void run_providerWithoutEffortSupport_omitsEffortFromMetadata() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));
        when(aiProvider.getId()).thenReturn("PI");
        when(aiProvider.getCapabilities()).thenReturn(AiProviderCapabilities.minimal());
        ExecutionContext withPi = contextWith(aiProvider, "PI");

        StepResult result = call.run(request("task").effort("high").build(), withPi);

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(aiProvider).executeAction(captor.capture());
        assertThat(captor.getValue().effort()).isEqualTo("high");
        assertThat(aiRequestOf(result)).containsEntry("provider", "PI").doesNotContainKey("effort");
    }

    /** The cache figures of the response reach the step metadata next to the uncached input. */
    @Test
    void run_carriesCacheTokensIntoStepMetadata() throws IOException {
        var metadata = new AiResponseMetadata(2, 918, 0.408808, "claude-opus-5-5", false, 8794L, 1, "sess",
                List.of(), 13720, 48462);
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, metadata));

        StepResult result = call.run(request("task").build(), context);

        assertThat(result.getMetadata())
                .containsEntry("inputTokens", 2)
                .containsEntry("cacheReadInputTokens", 13720)
                .containsEntry("cacheCreationInputTokens", 48462)
                .containsEntry("outputTokens", 918);
    }

    /** Without a policy of its own the call gets no tools and a read-only workspace, in the background slots. */
    @Test
    void run_sendsABackgroundRequestWithoutTools() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));

        call.run(request("task").build(), context);

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(aiProvider).executeAction(captor.capture());
        assertThat(captor.getValue().tools()).isEqualTo(ToolPolicy.NONE);
        assertThat(captor.getValue().writableWorkspace()).isFalse();
        assertThat(captor.getValue().priority()).isEqualTo(CallPriority.BACKGROUND);
        assertThat(captor.getValue().workingDirectory()).isEqualTo("/project");
    }

    /** The block's policy and workspace setting reach the provider as they are. */
    @Test
    void run_passesTheToolPolicyAndTheWritableWorkspace() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));

        call.run(request("task").tools(ToolPolicy.WRITE).writableWorkspace(true).build(), context);

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(aiProvider).executeAction(captor.capture());
        assertThat(captor.getValue().tools()).isEqualTo(ToolPolicy.WRITE);
        assertThat(captor.getValue().writableWorkspace()).isTrue();
    }

    @Test
    void run_omitsEffortFromMetadataWhenUnset() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));

        StepResult result = call.run(request("task").build(), context);

        assertThat(aiRequestOf(result)).doesNotContainKey("effort");
    }

    @Test
    void run_omitsAiSystemPromptWhenNull() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));

        StepResult result = call.run(request("task").build(), context);

        assertThat(result.getMetadata()).doesNotContainKey("aiSystemPrompt");
        assertThat(result.getMetadata()).containsKey("aiRequest");
    }

    /** A provider that reports no model ran the one it was asked for. */
    @Test
    void run_providerReportsNoModel_stepNamesTheRequestedOne() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("report", 0,
                AiResponseMetadata.estimated("prompt", "report")));
        when(aiProvider.getId()).thenReturn("PI");
        ExecutionContext withPi = contextWith(aiProvider, "PI");

        StepResult result = call.run(request("write it").stepIndex(0).model("gpt-6-astra").build(), withPi);

        assertThat(result.getMetadata())
                .containsEntry("model", "gpt-6-astra")
                .containsEntry("requestedModel", "gpt-6-astra");
    }

    @Test
    void run_providerReportsItsModel_keepsIt() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("report", 0,
                new AiResponseMetadata(10, 5, null, "claude-opus-5-5", false, null, null, null, null)));

        StepResult result = call.run(request("write it").stepIndex(0).model("opus").build(), context);

        assertThat(result.getMetadata())
                .containsEntry("model", "claude-opus-5-5")
                .containsEntry("requestedModel", "opus");
    }

    /** A provider that throws (timeout, limit, missing login) makes a failed step that names the error. */
    @Test
    void run_providerThrows_isAFailedStepWithTheError() throws IOException {
        when(aiProvider.executeAction(any())).thenThrow(new IOException("CLI timed out"));

        StepResult result = call.run(request("task").build(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("Error: CLI timed out");
        assertThat(result.getMetadata()).containsEntry("error", "CLI timed out");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    /** An unknown provider is refused by the port and ends as a failed step, never on another provider. */
    @Test
    void run_unknownProvider_isAFailedStep() {
        StepResult result = call.run(request("task").providerId("NOPE").build(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).startsWith("Error: unknown AI provider 'NOPE'");
    }

    /** A question whose answer the block reads itself (a condition): the answer, its exit code and the metadata. */
    @Test
    void ask_returnsTheAnswerWithTheCallMetadata() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("TRUE", 0, null));

        AiStepCall.Answer answer = call.ask(request("Evaluate: x").model("opus").timeoutSeconds(30).build(), context);

        assertThat(answer.text()).isEqualTo("TRUE");
        assertThat(answer.exitCode()).isZero();
        assertThat(answer.metadata()).containsEntry("provider", "CLAUDE").containsEntry("requestedModel", "opus");
        assertThat(answer.metadata().get("aiRequest")).isEqualTo(
                Map.of("provider", "CLAUDE", "model", "opus", "timeoutSeconds", 30));
        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(aiProvider).executeAction(captor.capture());
        assertThat(captor.getValue().prompt()).isEqualTo("Evaluate: x");
        assertThat(captor.getValue().workingDirectory()).isEqualTo("/project");
        assertThat(captor.getValue().priority()).isEqualTo(CallPriority.BACKGROUND);
        assertThat(captor.getValue().tools()).isEqualTo(ToolPolicy.NONE);
    }

    @Test
    void ask_nonZeroExit_isAnAnswerToo() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("usage limit", 1, null));

        AiStepCall.Answer answer = call.ask(request("Evaluate: x").build(), context);

        assertThat(answer.exitCode()).isEqualTo(1);
        assertThat(answer.text()).isEqualTo("usage limit");
    }

    @Test
    void ask_providerThrows_isAWorkflowExecutionExceptionWithTheCause() throws IOException {
        IOException missing = new IOException("pi not found");
        when(aiProvider.executeAction(any())).thenThrow(missing);

        assertThatThrownBy(() -> call.ask(request("Evaluate: x").build(), context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("AI call failed (project: /project)")
                .hasCause(missing);
    }

    /** An unknown provider is refused, not a failed answer: a block must not settle it as a false condition. */
    @Test
    void ask_unknownProvider_isRefusedNamingIt() {
        assertThatThrownBy(() -> call.ask(request("Evaluate: x").providerId("NOPE").build(), context))
                .isInstanceOf(AiStepCall.ProviderNotAvailable.class)
                .isNotInstanceOf(WorkflowExecutionException.class)
                .hasMessage("unknown AI provider 'NOPE'; configured: CLAUDE")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aiRequest_fromRequest_matchesHostStepShape() {
        AiStepRequest request = request("task").model("opus").effort("high").timeoutSeconds(120)
                .tools(ToolPolicy.READ).build();

        Map<String, Object> aiRequest = AiStepCall.aiRequest(request, "CLAUDE", true);

        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "opus")
                .containsEntry("effort", "high")
                .containsEntry("timeoutSeconds", 120)
                .containsEntry("tools", "read")
                .doesNotContainKeys("priority", "allowedTools", "disallowedTools", "workspaceWritable");
    }

    @Test
    void aiRequest_writableWorkspace_isNamed() {
        AiStepRequest request = request("task").tools(ToolPolicy.WRITE).writableWorkspace(true).build();

        assertThat(AiStepCall.aiRequest(request, "CLAUDE", true))
                .containsEntry("tools", "write").containsEntry("workspaceWritable", true);
    }

    /** A provider without an effort flag ignores the setting, so the step does not claim one. */
    @Test
    void aiRequest_providerWithoutEffort_omitsTheEffort() {
        AiStepRequest request = request("task").model("gpt-5.5").effort("high").build();

        Map<String, Object> aiRequest = AiStepCall.aiRequest(request, "PI", false);

        assertThat(aiRequest)
                .containsEntry("provider", "PI")
                .containsEntry("model", "gpt-5.5")
                .doesNotContainKey("effort");
    }

    /** No tools is the default, left out like an unset model or effort. */
    @Test
    void aiRequest_noTools_omitsTheTools() {
        AiStepRequest request = request("task").build();

        Map<String, Object> aiRequest = AiStepCall.aiRequest(request, "CLAUDE", true);

        assertThat(aiRequest).containsExactly(Map.entry("provider", "CLAUDE"));
    }
}
