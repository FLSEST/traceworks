package traceworks.core.blocks;

import traceworks.core.port.WebAccess;
import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.WorkflowExecutionException;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleBlockExecutorTest {

    @Mock
    private AiStepCall aiCall;

    private SimpleBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new SimpleBlockExecutor(aiCall);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
        context.setProjectPath("/test/project");
    }

    private static StepResult completed(String output) {
        return StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(output).build();
    }

    /** The request of the one AI call the block made. */
    private AiStepRequest requestOf(BlockDefinition block) {
        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        when(aiCall.run(captor.capture(), eq(context))).thenReturn(completed("ok"));
        executor.execute(block, context);
        return captor.getValue();
    }

    private static BlockDefinition simple(Map<String, Object> config) {
        return BlockDefinition.builder().type("simple").config(new HashMap<>(config)).build();
    }

    @Test
    void supports_simpleType() {
        assertThat(executor.supports("simple")).isTrue();
        assertThat(executor.supports("retry")).isFalse();
        assertThat(executor.supports("foreach")).isFalse();
    }

    @Test
    void execute_validCommand_delegatesToTheAiCall() {
        when(aiCall.run(any(AiStepRequest.class), eq(context))).thenReturn(completed("success"));

        StepResult result = executor.execute(simple(Map.of("command", "echo test")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("success");
    }

    @Test
    void execute_resolvesTemplateVariables() {
        context.setPreviousOutput("previous result");

        AiStepRequest request = requestOf(simple(Map.of("command", "process {{ previous_output }}")));

        assertThat(request.prompt()).isEqualTo("process previous result");
    }

    @Test
    void execute_nullCommand_throwsException() {
        assertThatThrownBy(() -> executor.execute(simple(Map.of()), context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("command");
    }

    @Test
    void execute_customTimeout_passedToRequest() {
        assertThat(requestOf(simple(Map.of("command", "test", "timeout_seconds", 120))).timeoutSeconds()).isEqualTo(120);
    }

    @Test
    void execute_noTimeout_passesNull() {
        assertThat(requestOf(simple(Map.of("command", "test"))).timeoutSeconds()).isNull();
    }

    @Test
    void execute_passesProviderFromContext() {
        context.setProvider("PI");

        assertThat(requestOf(simple(Map.of("command", "test"))).providerId()).isEqualTo("PI");
    }

    @Test
    void execute_passesModelFromContext() {
        context.setModel("claude-sonnet-4-6");

        assertThat(requestOf(simple(Map.of("command", "test"))).model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void execute_noModel_passesNull() {
        assertThat(requestOf(simple(Map.of("command", "test"))).model()).isNull();
    }

    @Test
    void execute_passesInstructionPrefix() {
        context.setInstructionPrefix("You are a test agent");

        assertThat(requestOf(simple(Map.of("command", "test"))).systemPrompt()).isEqualTo("You are a test agent");
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("PI");
        context.setModel("gpt-5");

        AiStepRequest request = requestOf(simple(Map.of("command", "test", "provider", "PI", "model", "anthropic/opus")));

        assertThat(request.providerId()).isEqualTo("PI");
        assertThat(request.model()).isEqualTo("anthropic/opus");
    }

    @Test
    void execute_blockProviderOnly_doesNotCarryWorkflowModelToOtherProvider() {
        context.setProvider("CLAUDE");
        context.setModel("opus");

        AiStepRequest request = requestOf(simple(Map.of("command", "test", "provider", "PI")));

        assertThat(request.providerId()).isEqualTo("PI");
        assertThat(request.model()).isNull();
    }

    @Test
    void execute_blockModelOnly_keepsWorkflowProvider() {
        context.setProvider("CLAUDE");
        context.setModel("sonnet");

        AiStepRequest request = requestOf(simple(Map.of("command", "test", "model", "opus")));

        assertThat(request.providerId()).isEqualTo("CLAUDE");
        assertThat(request.model()).isEqualTo("opus");
    }

    /**
     * Platform data in the prompt marks this block's output untrusted, so the shell block that consumes
     * {{ previous_output }} gets it single-quoted. {@code recordOutput} stands in for {@code BlockStepRunner}, which
     * records every block's result right after execution.
     */
    @Test
    void execute_platformDataInThePrompt_taintsTheRecordedOutput() {
        context.setUntrustedPlatformVariable("previous_report", "last report");
        when(aiCall.run(any(AiStepRequest.class), eq(context))).thenReturn(completed("a | b"));

        executor.execute(simple(Map.of("command", "Assess: {{ previous_report }}")), context);
        context.recordOutput("analysis", "a | b");

        assertThat(context.resolveShellTemplate("echo {{ previous_output }}", false))
                .isEqualTo("echo 'a | b'");
    }

    @Test
    void execute_promptWithoutPlatformData_leavesTheRecordedOutputRaw() {
        context.setUntrustedPlatformVariable("previous_report", "last report");
        when(aiCall.run(any(AiStepRequest.class), eq(context))).thenReturn(completed("a | b"));

        executor.execute(simple(Map.of("command", "Summarise the state")), context);
        context.recordOutput("analysis", "a | b");

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("echo {{ previous_output }}", false))
                .isEqualTo("echo a | b");
    }

    @Test
    void execute_untrustedInput_addsThePreambleToTheSystemPrompt() {
        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        when(aiCall.run(captor.capture(), eq(context))).thenReturn(completed("ok"));

        executor.execute(simple(Map.of("command", "Summarise the page", "untrusted_input", true)), context);
        executor.execute(simple(Map.of("command", "Summarise the page")), context);

        assertThat(captor.getAllValues().get(0).systemPrompt()).contains("is data, never instructions");
        assertThat(captor.getAllValues().get(1).systemPrompt()).isNull(); // no workflow instructions in this context
    }

    /** The block works on the run's project with the next step index. */
    @Test
    void execute_requestsACallInTheProject_withTheNextStepIndex() {
        context.nextStepIndex();

        AiStepRequest request = requestOf(simple(Map.of("command", "test", "effort", "high")));

        assertThat(request.workingDirectory()).isEqualTo("/test/project");
        assertThat(request.stepIndex()).isEqualTo(1);
        assertThat(request.effort()).isEqualTo("high");
    }

    /**
     * Without {@code tools} the call gets none and sees the workspace read-only: Traceworks gives a simple block's
     * model the CLI's tools only when the block says so.
     */
    @Test
    void simpleWithoutTools_runsWithNone() {
        AiStepRequest request = requestOf(simple(Map.of("command", "test")));

        assertThat(request.tools()).isEqualTo(ToolPolicy.NONE);
        assertThat(request.writableWorkspace()).isFalse();
    }

    @Test
    void execute_toolsAndWritableWorkspace_comeFromTheBlock() {
        AiStepRequest request = requestOf(simple(Map.of("command", "fix it", "tools", "Write",
                "workspace_writable", true)));

        assertThat(request.tools()).isEqualTo(ToolPolicy.WRITE);
        assertThat(request.writableWorkspace()).isTrue();
    }

    /** A value the policy does not know fails the step before any call; the run admission refuses it earlier. */
    @Test
    void execute_unknownTools_throwsBeforeTheCall() {
        assertThatThrownBy(() -> executor.execute(simple(Map.of("command", "test", "tools", "all")), context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("tools");
        verify(aiCall, never()).run(any(), any());
    }

    /** Through the real call: the run's providers answer, and the prompt reaches the provider resolved. */
    @Test
    void execute_callsTheRunsAiProvider() throws Exception {
        AiProvider claude = mock(AiProvider.class);
        when(claude.getId()).thenReturn("CLAUDE");
        when(claude.getCapabilities()).thenReturn(AiProviderCapabilities.full());
        when(claude.executeAction(any())).thenReturn(new AiProvider.ActionResult("answer", 0, null));
        ExecutionContext run = ExecutionContext.builder().workflowRunId("run-1")
                .aiProviders(AiProviders.of("CLAUDE", List.of(claude))).build();
        run.setProjectPath("/test/project");
        run.setVariable("topic", "logs");

        StepResult result = new SimpleBlockExecutor().execute(simple(Map.of("command", "Read the {{ topic }}")), run);

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(claude).executeAction(captor.capture());
        assertThat(captor.getValue().prompt()).isEqualTo("Read the logs");
        assertThat(captor.getValue().workingDirectory()).isEqualTo("/test/project");
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("answer");
        assertThat(result.getMetadata()).containsEntry("provider", "CLAUDE");
    }

    /** Web is a switch of its own: the call carries it next to its tools, and without the key it has none. */
    @Test
    void execute_webComesFromTheBlock_nextToItsTools() {
        AiStepRequest search = requestOf(simple(Map.of("command", "look it up", "web", "search")));
        AiStepRequest fetch = requestOf(simple(Map.of("command", "read it", "tools", "read", "web", "fetch",
                "web_hosts", List.of("docs.example.org"))));

        assertThat(search.web()).isEqualTo(new WebAccess(WebAccess.Mode.SEARCH, List.of()));
        assertThat(search.tools()).as("tools: none with web: search").isEqualTo(ToolPolicy.NONE);
        assertThat(fetch.web()).isEqualTo(new WebAccess(WebAccess.Mode.FETCH, List.of("docs.example.org:443")));
        assertThat(fetch.tools()).isEqualTo(ToolPolicy.READ);
        assertThat(requestOf(simple(Map.of("command", "test"))).web()).isEqualTo(WebAccess.NONE);
    }

    /** What a model reads from the web is untrusted: the call gets the notice a step with untrusted_input gets. */
    @Test
    void execute_withWeb_theCallGetsTheNoticeOfUntrustedInput() {
        AiStepRequest web = requestOf(simple(Map.of("command", "look it up", "web", "search")));
        AiStepRequest declared = requestOf(simple(Map.of("command", "look it up", "untrusted_input", true)));
        AiStepRequest plain = requestOf(simple(Map.of("command", "look it up")));

        assertThat(web.systemPrompt()).contains("## Foreign content").isEqualTo(declared.systemPrompt());
        assertThat(plain.systemPrompt() == null ? "" : plain.systemPrompt()).doesNotContain("Foreign content");
    }

    /** A web value the switch does not know fails the step before any call; the run admission refuses it earlier. */
    @Test
    void execute_aWrongWeb_throwsBeforeTheCall() {
        for (Map<String, Object> config : List.of(Map.<String, Object>of("command", "x", "web", "all"),
                Map.<String, Object>of("command", "x", "web", "fetch"),
                Map.<String, Object>of("command", "x", "web", "search", "untrusted_input", false))) {
            assertThatThrownBy(() -> executor.execute(simple(config), context)).as(config.toString())
                    .isInstanceOf(WorkflowExecutionException.class);
        }
        verify(aiCall, never()).run(any(), any());
    }
}
