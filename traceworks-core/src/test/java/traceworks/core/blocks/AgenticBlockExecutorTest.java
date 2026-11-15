package traceworks.core.blocks;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.CallPriority;
import traceworks.core.ai.ConversationPrompt;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.AgenticProgress;
import traceworks.core.port.RunEvent.ApprovalRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Stubber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The agentic block. These tests mock the {@link AiProvider} port itself, not a CLI behind a real provider, so a
 * stubbed {@code reason} or {@code executeAction} call is exactly the request the loop sends.
 */
@ExtendWith(MockitoExtension.class)
class AgenticBlockExecutorTest {

    @Mock
    private AiProvider provider;

    @Mock
    private AiProviders providers;

    private AgenticBlockExecutor executor;

    private final List<RunEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(provider.getId()).thenReturn("CLAUDE");
        lenient().when(provider.getCapabilities()).thenReturn(AiProviderCapabilities.full());
        lenient().when(providers.resolve(any())).thenReturn(provider);
        executor = new AgenticBlockExecutor();
    }

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .projectPath("/test/project")
                .variables(new HashMap<>())
                .aiProviders(providers)
                .events(events::add)
                .build();
    }

    private BlockDefinition buildBlock(String goal) {
        return buildBlock(goal, 10);
    }

    private BlockDefinition buildBlock(String goal, int maxIterations) {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", goal);
        config.put("tools", "read");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", maxIterations);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        return BlockDefinition.builder()
                .type("agentic")
                .config(config)
                .build();
    }

    private static AiProvider.ReasoningResult rr(String text) {
        return new AiProvider.ReasoningResult(text, null);
    }

    /** Real usage data, as the Claude CLI reports it. */
    private static AiResponseMetadata usage(int input, int output) {
        return new AiResponseMetadata(input, output, 0.01, null, false, 200L, 1, null, List.of());
    }

    /**
     * A REASON request of the loop: the conversation prompt with the agentic system prompt, no working directory and
     * no turn limit.
     */
    private static AiProvider.ReasoningRequest reasonCall(String model, Integer timeout, String effort) {
        return argThat(req -> req != null && Objects.equals(req.prompt(), "prompt") && req.systemPrompt() != null
                && Objects.equals(req.model(), model) && req.workingDirectory() == null && req.maxTurns() == null
                && Objects.equals(req.timeoutSeconds(), timeout) && Objects.equals(req.effort(), effort));
    }

    private static AiProvider.ReasoningRequest reasonCall() {
        return reasonCall(null, null, null);
    }

    /**
     * An ACT request of the loop in the project, as a background call with the block's tools and no effort;
     * {@code prompt} {@code null} matches any action.
     */
    private static AiProvider.ActionRequest actCall(String prompt, String model, String systemPrompt, Integer timeout) {
        return argThat(req -> req != null && req.prompt() != null && (prompt == null || prompt.equals(req.prompt()))
                && Objects.equals(req.model(), model) && Objects.equals(req.systemPrompt(), systemPrompt)
                && Objects.equals(req.workingDirectory(), "/test/project") && Objects.equals(req.timeoutSeconds(), timeout)
                && req.priority() == CallPriority.BACKGROUND && req.tools() == ToolPolicy.READ
                && req.effort() == null);
    }

    private static AiProvider.ActionRequest actCall(String prompt) {
        return actCall(prompt, null, null, null);
    }

    private static AiProvider.ActionRequest anyAction() {
        return argThat(req -> req != null && req.prompt() != null);
    }

    private void promptIsBuilt() {
        when(provider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
    }

    @Test
    void supports_returnsTrue_forAgenticType() {
        assertThat(executor.supports("agentic")).isTrue();
    }

    @Test
    void supports_returnsFalse_forOtherTypes() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("retry")).isFalse();
    }

    @Test
    void execute_goalAchievedOnFirstIteration() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Already done\nACTION:\nSUMMARY: Goal was already met"));

        StepResult result = executor.execute(buildBlock("Test goal"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("ACHIEVED");

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(metadata.get("totalIterations")).isEqualTo(1);

        // Should not have called action execution
        verify(provider, never()).executeAction(anyAction());
    }

    @Test
    void execute_multiIterationUntilGoalAchieved() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Need to create file\nACTION: Create test.txt\nSUMMARY: Creating file"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: File created\nACTION:\nSUMMARY: Done"));
        when(provider.executeAction(actCall("Create test.txt")))
                .thenReturn(new AiProvider.ActionResult("File created", 0, null));

        StepResult result = executor.execute(buildBlock("Create a file"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(metadata.get("totalIterations")).isEqualTo(2);
    }

    @Test
    void execute_maxIterationsReached() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(
                rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Still working\nACTION: Try again\nSUMMARY: Retrying"));
        when(provider.executeAction(actCall("Try again")))
                .thenReturn(new AiProvider.ActionResult("Still failing", 1, null));

        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Impossible goal");
        config.put("tools", "read");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 2);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        config.put("max_repeated_actions", 10); // disable repeated action detection for this test
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(false);
        assertThat(metadata.get("totalIterations")).isEqualTo(2);
    }

    @Test
    void execute_cancellationStopsLoop() throws Exception {
        promptIsBuilt();
        when(provider.executeAction(actCall("Do something")))
                .thenReturn(new AiProvider.ActionResult("partial", 0, null));

        ExecutionContext context = buildContext();
        // Cancel after first iteration observe
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do something\nSUMMARY: Step 1"))
                .thenAnswer(invocation -> {
                    context.cancel();
                    return rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: More\nACTION: More work\nSUMMARY: Step 2");
                });

        StepResult result = executor.execute(buildBlock("Some goal"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        // Cancelled during the second REASON: its action must not start.
        verify(provider, never()).executeAction(argThat(req -> req != null && Objects.equals(req.prompt(), "More work")));
    }

    @Test
    void execute_reasonError_stopsWithFailure() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenThrow(new IOException("Connection refused"));

        StepResult result = executor.execute(buildBlock("Test"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("REASON ERROR");
    }

    /**
     * The CLI itself fails in the REASON phase (limit, login): the provider throws with its message, as the port
     * requires for a non-zero exit code. The block stops after that one call with the message in its output, instead
     * of taking the message for reasoning without an ACTION and reasoning again until max_iterations.
     */
    @Test
    void execute_reasonCliEndsWithNonZeroExitCode_stopsAfterFirstIterationWithItsMessage() throws Exception {
        promptIsBuilt();
        when(provider.reason(any())).thenThrow(
                new IOException("Claude CLI ended with exit code 1: Claude AI usage limit reached"));

        StepResult result = executor.execute(buildBlock("Test goal", 3), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("REASON ERROR", "exit code 1", "Claude AI usage limit reached");
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(1);
    }

    @Test
    void execute_containerCommandFailure_isObservedAndRetried() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: First try\nACTION: Run tests\nSUMMARY: Running tests"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Try different approach\nACTION: Fix and run\nSUMMARY: Fixing"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Fixed\nACTION:\nSUMMARY: All good"));
        when(provider.executeAction(actCall("Run tests")))
                .thenReturn(new AiProvider.ActionResult("Tests failed: 3 errors", 1, null));
        when(provider.executeAction(actCall("Fix and run")))
                .thenReturn(new AiProvider.ActionResult("All tests pass", 0, null));

        StepResult result = executor.execute(buildBlock("Fix tests", 5), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Exit code: 1");
        assertThat(result.getOutput()).contains("Exit code: 0");
    }

    @Test
    void execute_metadataStoredCorrectly() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        StepResult result = executor.execute(buildBlock("My goal"), buildContext());

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata).containsKey("blockType");
        assertThat(metadata.get("blockType")).isEqualTo("agentic");
        assertThat(metadata).containsKey("goal");
        assertThat(metadata.get("goal")).isEqualTo("My goal");
        assertThat(metadata).containsKey("totalIterations");
        assertThat(metadata).containsKey("goalAchieved");
        assertThat(metadata).containsKey("iterations");
    }

    /** The loop reports its phases as run events. */
    @Test
    void execute_emitsItsPhasesAsRunEvents() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        executor.execute(buildBlock("Test"), buildContext());

        assertThat(events).allMatch(AgenticProgress.class::isInstance)
                .extracting(e -> ((AgenticProgress) e).type())
                .containsExactly("agentic_started", "agentic_reasoning", "agentic_reasoned", "agentic_goal_achieved",
                        "agentic_completed");
        assertThat(((AgenticProgress) events.getFirst()).data()).containsEntry("goal", "Test")
                .containsEntry("maxIterations", 10);
    }

    /** A cut of the reasoning leaves out a secret it would split, so the record can still redact it whole. */
    @Test
    void execute_aSecretAtTheCutOfTheReasoning_isLeftOutWhole() throws Exception {
        String secret = "s3cr3t-value-0123456789";
        String head = "GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete\n";
        String reasoning = head + "x".repeat(AgenticBlockExecutor.TRUNCATE_LONG - 10 - head.length()) + secret
                + " and more";
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(rr(reasoning));
        ExecutionContext context = ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .projectPath("/test/project")
                .variables(new HashMap<>())
                .aiProviders(providers)
                .events(events::add)
                .secrets(Map.of("TOKEN", secret))
                .build();

        executor.execute(buildBlock("Test"), context);

        assertThat(events).filteredOn(e -> ((AgenticProgress) e).type().equals("agentic_reasoned")).singleElement()
                .satisfies(e -> assertThat((String) ((AgenticProgress) e).data().get("reasoning"))
                        .endsWith("x...").doesNotContain("s3cr3t"));
    }

    /** The loop stops once its total timeout has passed, and says so, before it reaches max_iterations. */
    @Test
    void execute_totalTimeoutPassed_stopsTheLoopBeforeTheNextIteration() throws Exception {
        java.time.Instant[] now = {java.time.Instant.parse("2026-11-14T10:00:00Z")};
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(
                rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Doing"));
        when(provider.executeAction(actCall("Do it"))).thenAnswer(call -> {
            now[0] = now[0].plusSeconds(200);
            return new AiProvider.ActionResult("done part", 0, null);
        });
        Map<String, Object> config = new HashMap<>(buildBlock("Slow goal", 5).getConfig());
        config.put("total_timeout_seconds", 150);
        config.put("max_repeated_actions", 10);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();
        ExecutionContext context = ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .projectPath("/test/project")
                .variables(new HashMap<>())
                .aiProviders(providers)
                .events(events::add)
                .clock(() -> now[0])
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[TOTAL TIMEOUT after 200s]");
        assertThat(result.getMetadata()).containsEntry("stopReason", "TOTAL_TIMEOUT")
                .containsEntry("totalIterations", 1);
        verify(provider, times(1)).reason(any());
    }

    @Test
    void execute_goalImpossible_returnsFailed() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(
                rr("GOAL_STATUS: IMPOSSIBLE\nRATIONALE: No access\nACTION:\nSUMMARY: Cannot proceed"));

        StepResult result = executor.execute(buildBlock("Impossible"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata().get("goalAchieved")).isEqualTo(false);
    }

    @Test
    void execute_missingGoalConfig_throws() {
        BlockDefinition block = BlockDefinition.builder()
                .type("agentic")
                .config(new HashMap<>())
                .build();

        assertThatThrownBy(() -> executor.execute(block, buildContext()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("goal");
    }

    /** An agentic block that does not state its tools makes no call: the run admission refuses it earlier. */
    @Test
    void execute_missingTools_throwsBeforeAnyCall() throws Exception {
        BlockDefinition block = BlockDefinition.builder()
                .type("agentic")
                .config(new HashMap<>(Map.of("goal", "Fix it")))
                .build();

        assertThatThrownBy(() -> executor.execute(block, buildContext()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("tools");
        verify(provider, never()).reason(any());
        verify(provider, never()).executeAction(any());
    }

    /** A record of a REASON call that cannot be written is no reasoning error: it ends the run. */
    @Test
    void execute_aRecordWriteThatFailsInReason_propagates() throws Exception {
        promptIsBuilt();
        when(provider.reason(any())).thenThrow(new EventLogException(new IOException("disk full")));

        assertThatThrownBy(() -> executor.execute(buildBlock("Record"), buildContext()))
                .isInstanceOf(EventLogException.class);
    }

    /** {@code web} reaches both phases next to the tools, and both get the notice of untrusted input. */
    @Test
    void execute_web_reachesReasonAndAct() throws Exception {
        promptIsBuilt();
        when(provider.reason(any()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(any())).thenReturn(new AiProvider.ActionResult("done", 0, null));
        BlockDefinition block = buildBlock("Look it up");
        block.getConfig().put("tools", "none");
        block.getConfig().put("web", "fetch");
        block.getConfig().put("web_hosts", java.util.List.of("docs.example.org"));

        executor.execute(block, buildContext());

        traceworks.core.port.WebAccess fetch = new traceworks.core.port.WebAccess(
                traceworks.core.port.WebAccess.Mode.FETCH, java.util.List.of("docs.example.org:443"));
        ArgumentCaptor<AiProvider.ReasoningRequest> reason = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        verify(provider, atLeast(1)).reason(reason.capture());
        ArgumentCaptor<AiProvider.ActionRequest> act = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(provider).executeAction(act.capture());
        assertThat(reason.getAllValues()).allSatisfy(r -> assertThat(r.web()).isEqualTo(fetch));
        assertThat(act.getValue().web()).isEqualTo(fetch);
        assertThat(act.getValue().tools()).isEqualTo(ToolPolicy.NONE);
        assertThat(act.getValue().systemPrompt()).contains("## Foreign content");
    }

    /** {@code workspace_writable: true} reaches both phases. */
    @Test
    void execute_writableWorkspace_reachesReasonAndAct() throws Exception {
        promptIsBuilt();
        when(provider.reason(any()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(any())).thenReturn(new AiProvider.ActionResult("done", 0, null));
        BlockDefinition block = buildBlock("Writable");
        block.getConfig().put("tools", "write");
        block.getConfig().put("workspace_writable", true);

        executor.execute(block, buildContext());

        ArgumentCaptor<AiProvider.ReasoningRequest> reason = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        verify(provider, atLeast(1)).reason(reason.capture());
        ArgumentCaptor<AiProvider.ActionRequest> act = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(provider).executeAction(act.capture());
        assertThat(reason.getAllValues()).allSatisfy(r -> {
            assertThat(r.tools()).isEqualTo(ToolPolicy.WRITE);
            assertThat(r.writableWorkspace()).isTrue();
        });
        assertThat(act.getValue().tools()).isEqualTo(ToolPolicy.WRITE);
        assertThat(act.getValue().writableWorkspace()).isTrue();
    }

    @Test
    void execute_tokenBudgetExceeded_stopsLoop() throws Exception {
        // A response with high token usage exceeds a small budget
        AiResponseMetadata highUsage = new AiResponseMetadata(60000, 60000, 1.0, null, false, 800L, 1, null, List.of());

        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(new AiProvider.ReasoningResult(
                "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do work\nSUMMARY: Step", highUsage));
        when(provider.executeAction(actCall("Do work")))
                .thenReturn(new AiProvider.ActionResult("Done", 0, highUsage));

        // A very low token budget (100k) that the first iteration (240k tokens) exceeds
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Budget test");
        config.put("tools", "read");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("max_total_tokens", 100000);
        config.put("max_repeated_actions", 10);
        config.put("require_approval", false);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("TOKEN BUDGET EXCEEDED");
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(((Number) result.getMetadata().get("totalIterations")).intValue()).isEqualTo(1);
    }

    @Test
    void execute_tokenTrackingFailed_afterConsecutiveBlindIterations() throws Exception {
        // No iteration returns usage data: fallback estimation, abort after 3
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Step A\nSUMMARY: Doing A"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Continuing\nACTION: Step B\nSUMMARY: Doing B"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: More work\nACTION: Step C\nSUMMARY: Doing C"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Keep going\nACTION: Step D\nSUMMARY: Doing D"));
        when(provider.executeAction(actCall(null)))
                .thenReturn(new AiProvider.ActionResult("output", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Blind budget test");
        config.put("tools", "read");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("max_total_tokens", 200000);
        config.put("max_repeated_actions", 10);
        config.put("require_approval", false);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("TOKEN TRACKING FAILED");
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("TOKEN_TRACKING_FAILED");
        assertThat(result.getMetadata().get("tokenEstimationFallback")).isEqualTo(true);
        // Should stop after 3 consecutive blind iterations
        assertThat(((Number) result.getMetadata().get("totalIterations")).intValue()).isEqualTo(3);
        // Fallback estimation should have produced non-zero token count
        assertThat(((Number) result.getMetadata().get("totalTokensUsed")).intValue()).isGreaterThan(0);
    }

    @Test
    void execute_fallbackEstimation_resetsOnRealUsage() throws Exception {
        // First iteration: no usage (blind), second: real usage, third: no usage again; must NOT abort
        AiResponseMetadata realUsage = new AiResponseMetadata(1000, 500, 0.01, null, false, 400L, 1, null, List.of());

        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: First\nACTION: Step 1\nSUMMARY: S1"))
                .thenReturn(new AiProvider.ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Second\nACTION: Step 2\nSUMMARY: S2", realUsage))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Third\nACTION: Step 3\nSUMMARY: S3"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(actCall("Step 1"))).thenReturn(new AiProvider.ActionResult("out1", 0, null));
        when(provider.executeAction(actCall("Step 2"))).thenReturn(new AiProvider.ActionResult("out2", 0, realUsage));
        when(provider.executeAction(actCall("Step 3"))).thenReturn(new AiProvider.ActionResult("out3", 0, null));

        StepResult result = executor.execute(buildBlock("Reset test", 10), buildContext());

        // Goal achieved because consecutive blind count reset at iteration 2
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("goalAchieved")).isEqualTo(true);
    }

    /** A block with a token budget of {@code maxTotalTokens}. */
    private BlockDefinition budgetBlock(int maxTotalTokens) {
        BlockDefinition block = buildBlock("Budget test", 10);
        block.getConfig().put("max_total_tokens", maxTotalTokens);
        block.getConfig().put("max_repeated_actions", 10);
        return block;
    }

    /** An iteration that proposes no action spends tokens too: they count against the budget. */
    @Test
    void execute_theTokensOfAnIterationWithoutAnAction_countAgainstTheBudget() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(new AiProvider.ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Thinking\nACTION: \nSUMMARY: Planning",
                        usage(60000, 60000)))
                .thenReturn(new AiProvider.ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Go\nACTION: Do work\nSUMMARY: Step", usage(10, 10)))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(actCall("Do work")))
                .thenReturn(new AiProvider.ActionResult("Done", 0, usage(10, 10)));

        StepResult result = executor.execute(budgetBlock(100000), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(result.getMetadata().get("totalTokensUsed")).isEqualTo(120040);
    }

    /** An iteration whose action the person rejected spends its reasoning tokens too. */
    @Test
    void execute_theTokensOfAnIterationWhoseActionWasRejected_countAgainstTheBudget() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(new AiProvider.ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Clean\nACTION: rm -rf /\nSUMMARY: s",
                        usage(60000, 60000)))
                .thenReturn(new AiProvider.ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Clean\nACTION: rm -rf build\nSUMMARY: s", usage(10, 10)))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(actCall("rm -rf build")))
                .thenReturn(new AiProvider.ActionResult("cleaned", 0, usage(10, 10)));
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("No"),
                CompletableFuture.completedFuture("Yes"));
        BlockDefinition block = budgetBlock(100000);
        block.getConfig().put("require_approval", true);

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(result.getMetadata().get("totalTokensUsed")).isEqualTo(120040);
    }

    /** A provider that reports no token usage is expected to estimate: its loop is not ended for blind iterations. */
    @Test
    void execute_aProviderWithoutTokenTracking_isNotStoppedForEstimatedTokens() throws Exception {
        when(provider.getCapabilities())
                .thenReturn(new AiProviderCapabilities(true, true, true, true, true, false, true, true, true));
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: a\nACTION: Step A\nSUMMARY: A"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: b\nACTION: Step B\nSUMMARY: B"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: c\nACTION: Step C\nSUMMARY: C"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: d\nACTION: Step D\nSUMMARY: D"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(actCall(null))).thenReturn(new AiProvider.ActionResult("output", 0, null));

        StepResult result = executor.execute(budgetBlock(200000), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).doesNotContainKey("stopReason");
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(5);
    }

    /** The iteration that ends the loop is counted like every other: its usage is in the step's totals. */
    @Test
    void execute_theUsageOfTheLastIteration_isInTheTotals() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(new AiProvider.ReasoningResult(
                "GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete", usage(1000, 200)));

        StepResult result = executor.execute(buildBlock("My goal"), buildContext());

        assertThat(result.getMetadata()).containsEntry("inputTokens", 1000).containsEntry("outputTokens", 200)
                .containsEntry("totalTokensUsed", 1200);
        assertThat((List<?>) result.getMetadata().get("iterations")).hasSize(1);
    }

    /** An iteration whose REASON call failed is in the step's iterations, with the phase and the error. */
    @Test
    void execute_aReasonError_isRecordedAsAnIteration() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenThrow(new IOException("Connection refused"));

        StepResult result = executor.execute(buildBlock("Test"), buildContext());

        assertThat((List<?>) result.getMetadata().get("iterations")).singleElement()
                .satisfies(iteration -> {
                    Map<?, ?> data = (Map<?, ?>) iteration;
                    assertThat(data.get("phase")).isEqualTo("reason");
                    assertThat(data.get("error")).isEqualTo("Connection refused");
                });
    }

    @Test
    void execute_setsContextVariables() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        ExecutionContext context = buildContext();
        executor.execute(buildBlock("Test"), context);

        assertThat(context.getVariable("agentic_goal_achieved")).isEqualTo(true);
        assertThat(context.getVariable("agentic_iterations")).isEqualTo(1);
    }

    @Test
    void execute_repeatedActions_abortsLoop() throws Exception {
        AiResponseMetadata parsed = usage(500, 200);

        promptIsBuilt();
        when(provider.reason(reasonCall())).thenReturn(new AiProvider.ReasoningResult(
                "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Trying\nACTION: same action\nSUMMARY: Retry", parsed));
        when(provider.executeAction(actCall("same action")))
                .thenReturn(new AiProvider.ActionResult("failed", 1, parsed));

        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Repeat test");
        config.put("tools", "read");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("max_repeated_actions", 3);
        config.put("require_approval", false);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Same action repeated 3 times");
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("REPEATED_ACTIONS");
    }

    @Test
    void execute_noActionInPlan_asksForAction() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                // First response has no action
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Thinking\nACTION: \nSUMMARY: Planning"))
                // Second response achieves goal
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        StepResult result = executor.execute(buildBlock("Test"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(2);
        // No executeAction call for the empty-action iteration
        verify(provider, never()).executeAction(anyAction());
    }

    @Test
    void execute_historyCompression_compressesAfterThreshold() throws Exception {
        // 8+ turns trigger compression (keepRecentCount=4, threshold=keepRecentCount+3=7).
        // Each iteration adds 2 turns (assistant reasoning + user observation) to the initial user turn,
        // so 4 iterations = 1 + 4*2 = 9 turns.
        AiResponseMetadata parsed = usage(500, 200);
        List<List<ConversationPrompt.Turn>> snapshots = new ArrayList<>();
        when(provider.buildConversationPrompt(anyString(), anyList())).thenAnswer(inv -> {
            snapshots.add(List.copyOf(inv.<List<ConversationPrompt.Turn>>getArgument(1)));
            return "prompt";
        });
        when(provider.reason(reasonCall()))
                .thenReturn(new AiProvider.ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step1\nACTION: Action A\nSUMMARY: S1", parsed))
                .thenReturn(new AiProvider.ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step2\nACTION: Action B\nSUMMARY: S2", parsed))
                .thenReturn(new AiProvider.ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step3\nACTION: Action C\nSUMMARY: S3", parsed))
                .thenReturn(new AiProvider.ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step4\nACTION: Action D\nSUMMARY: S4", parsed))
                .thenReturn(new AiProvider.ReasoningResult("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete", parsed));
        when(provider.executeAction(actCall(null))).thenReturn(new AiProvider.ActionResult("ok", 0, parsed));

        StepResult result = executor.execute(buildBlock("Compression test", 10), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(5);
        assertThat(snapshots).hasSize(5);
        assertThat(snapshots.getLast()).anySatisfy(turn ->
                assertThat(turn.content()).startsWith("[Compressed history of"));
    }

    @Test
    void execute_actionCompression_compressesOversizedAction() throws Exception {
        // An action larger than the compression threshold (100_000)
        String oversizedAction = "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Big step\nACTION: " +
                "x".repeat(110_000) + "\nSUMMARY: Large action";

        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr(oversizedAction))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        // The compression call through reason
        when(provider.reason(argThat(req -> req != null && req.prompt() != null
                && req.prompt().contains("OVERSIZED ACTION") && req.systemPrompt() == null && req.model() == null
                && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1)
                && Objects.equals(req.timeoutSeconds(), 60) && req.effort() == null)))
                .thenReturn(rr("Compressed: do the thing"));
        when(provider.executeAction(actCall(null))).thenReturn(new AiProvider.ActionResult("done", 0, null));

        StepResult result = executor.execute(buildBlock("Compress test", 5), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // The compressed action is the one executed
        verify(provider).executeAction(actCall("Compressed: do the thing"));
    }

    @Test
    void execute_actionCompression_fallsBackToTruncation_onError() throws Exception {
        String oversizedAction = "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Big\nACTION: " +
                "y".repeat(110_000) + "\nSUMMARY: Large";

        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr(oversizedAction))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        // Compression fails
        when(provider.reason(argThat(req -> req != null && req.prompt() != null
                && req.prompt().contains("OVERSIZED ACTION") && req.systemPrompt() == null
                && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1)
                && Objects.equals(req.timeoutSeconds(), 60) && req.effort() == null)))
                .thenThrow(new IOException("Compression failed"));
        when(provider.executeAction(actCall(null))).thenReturn(new AiProvider.ActionResult("done", 0, null));

        StepResult result = executor.execute(buildBlock("Truncate test", 5), buildContext());

        // Still completes: the fallback truncation was used
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(provider).executeAction(argThat(req -> req != null && req.prompt() != null
                && req.prompt().contains("[TRUNCATED:") && Objects.equals(req.workingDirectory(), "/test/project")));
    }

    @Test
    void execute_withModelAndInstructions_passedToProvider() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall("claude-opus-4-6", null, null)))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(actCall("Do it", "claude-opus-4-6", "Custom instructions for this workflow", null)))
                .thenReturn(new AiProvider.ActionResult("done", 0, null));

        ExecutionContext context = buildContext();
        context.setModel("claude-opus-4-6");
        context.setInstructionPrefix("Custom instructions for this workflow");

        StepResult result = executor.execute(buildBlock("Model test"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // The system prompt includes the custom instructions (built once per iteration)
        verify(provider, atLeast(1)).buildConversationPrompt(contains("Custom instructions for this workflow"), anyList());
        // Model and instructions passed to executeAction
        verify(provider).executeAction(actCall("Do it", "claude-opus-4-6", "Custom instructions for this workflow", null));
    }

    @Test
    void execute_workflowModel_usedInReasonPhase() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall("claude-opus-4-6", null, null)))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        ExecutionContext context = buildContext();
        context.setModel("claude-opus-4-6");

        StepResult result = executor.execute(buildBlock("Reason model test"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // REASON must receive the workflow model, same as ACT
        verify(provider).reason(reasonCall("claude-opus-4-6", null, null));
    }

    @Test
    void execute_actPhaseException_capturedAsError() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do something\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        // ACT phase throws
        when(provider.executeAction(actCall("Do something"))).thenThrow(new RuntimeException("Process crashed"));

        StepResult result = executor.execute(buildBlock("Exception test", 5), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Execution error: Process crashed");
    }

    @Test
    void execute_providerResolved_fromContext() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        ExecutionContext context = buildContext();
        context.setProvider("PI");

        executor.execute(buildBlock("Provider test"), context);

        // Provider was resolved from context
        verify(providers).resolve("PI");
    }

    /**
     * An unknown provider is refused: the step fails with the reason and no model is called (docs/workflow-format.md).
     */
    @Test
    void execute_unknownProvider_failsTheStepWithoutACall() {
        when(providers.resolve("NOPE")).thenThrow(
                new IllegalArgumentException("unknown AI provider 'NOPE'; configured: CLAUDE"));
        BlockDefinition block = buildBlock("Test goal");
        block.getConfig().put("provider", "NOPE");

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("Error: unknown AI provider 'NOPE'; configured: CLAUDE");
        assertThat(result.getInput()).isEqualTo("[Agentic] Test goal");
        verifyNoInteractions(provider);
        assertThat(events).isEmpty();
    }

    /**
     * A failed write of an event is not swallowed. The events are the run's record: a failed write reaches the
     * engine, which stops the run fail-closed.
     */
    @Test
    void execute_eventLogFailure_propagates() {
        ExecutionContext broken = ExecutionContext.builder().workflowRunId("run-1").projectPath("/test/project")
                .aiProviders(providers).events(event -> {
                    throw new EventLogException(new IOException("disk full"));
                }).build();

        assertThatThrownBy(() -> executor.execute(buildBlock("WS fail test"), broken))
                .isInstanceOf(EventLogException.class);
    }

    @Test
    void execute_perBlockTimeout_propagatedToReasonAndAct() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Timeout propagation test");
        config.put("tools", "read");
        config.put("success_criteria", "Timeout forwarded");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        config.put("timeout_seconds", 600);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        promptIsBuilt();
        when(provider.reason(reasonCall(null, 600, null)))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(actCall(null, null, null, 600)))
                .thenReturn(new AiProvider.ActionResult("done", 0, null));

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(provider, atLeast(1)).reason(reasonCall(null, 600, null));
        verify(provider).executeAction(actCall(null, null, null, 600));
    }

    @Test
    void execute_reasonAndActPhases_useDistinctSystemPromptArguments() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall("claude-opus-4-6", null, null)))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(provider.executeAction(actCall(null, "claude-opus-4-6", "WORKFLOW_INSTR_X", null)))
                .thenReturn(new AiProvider.ActionResult("done", 0, null));

        ExecutionContext context = buildContext();
        context.setModel("claude-opus-4-6");
        context.setInstructionPrefix("WORKFLOW_INSTR_X");

        executor.execute(buildBlock("Phase-args goal"), context);

        ArgumentCaptor<AiProvider.ReasoningRequest> reasonRequest = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        verify(provider, atLeast(1)).reason(reasonRequest.capture());
        ArgumentCaptor<AiProvider.ActionRequest> actRequest = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(provider).executeAction(actRequest.capture());
        AiProvider.ReasoningRequest reason = reasonRequest.getValue();
        AiProvider.ActionRequest act = actRequest.getValue();

        // Both phases use the workflow model; ACT runs in the project, without timeout, tools or effort overrides
        assertThat(reason.prompt()).isNotNull();
        assertThat(reason.model()).isEqualTo("claude-opus-4-6");
        assertThat(reason.workingDirectory()).isNull();
        assertThat(reason.maxTurns()).isNull();
        assertThat(reason.timeoutSeconds()).isNull();
        assertThat(reason.effort()).isNull();
        assertThat(act.prompt()).isNotNull();
        assertThat(act.model()).isEqualTo("claude-opus-4-6");
        assertThat(act.workingDirectory()).isEqualTo("/test/project");
        assertThat(act.timeoutSeconds()).isNull();
        assertThat(act.priority()).isEqualTo(CallPriority.BACKGROUND);
        assertThat(act.effort()).isNull();
        // Both phases carry the block's tools and its workspace setting
        assertThat(reason.tools()).isEqualTo(ToolPolicy.READ);
        assertThat(act.tools()).isEqualTo(ToolPolicy.READ);
        assertThat(reason.writableWorkspace()).isFalse();
        assertThat(act.writableWorkspace()).isFalse();

        // REASON gets the full agentic system prompt (contains goal + structured format)
        assertThat(reason.systemPrompt()).contains("Phase-args goal");
        assertThat(reason.systemPrompt()).contains("Your Goal");
        assertThat(reason.systemPrompt()).contains("GOAL_STATUS:");
        // ACT gets only the raw workflow instruction prefix
        assertThat(act.systemPrompt()).isEqualTo("WORKFLOW_INSTR_X");
        assertThat(act.systemPrompt()).doesNotContain("Your Goal");
    }

    @Test
    void execute_providerExecuteActionThrowsCliNotInstalled_marksFailure() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Binary missing test");
        config.put("tools", "read");
        config.put("success_criteria", "Claude binary found");
        config.put("max_iterations", 1);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        config.put("max_repeated_actions", 10);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do something\nSUMMARY: Step"));
        when(provider.executeAction(actCall(null))).thenThrow(new RuntimeException("claude binary not found in PATH"));

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("claude binary not found in PATH");
    }

    // -- approval gate --------------------------------------------------------------------------------------

    private static final ConversationPrompt.Turn REJECTION_TURN = new ConversationPrompt.Turn(
            "user", "The user rejected this action. Propose an alternative approach.");

    private BlockDefinition approvalBlock() {
        BlockDefinition block = buildBlock("Guarded goal", 3);
        block.getConfig().put("require_approval", true);
        return block;
    }

    @SafeVarargs
    private ExecutionContext contextAnswering(CompletableFuture<String>... answers) {
        ExecutionContext context = spy(buildContext());
        Stubber stub = null;
        for (var answer : answers) {
            stub = stub == null ? doReturn(answer) : stub.doReturn(answer);
        }
        stub.when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    private void stubOneActionThenAchieved() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Do it\nACTION: rm -rf build\nSUMMARY: Clean"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Done"));
    }

    @Test
    void approval_yes_executesAction_andRecordsApproved() throws Exception {
        stubOneActionThenAchieved();
        when(provider.executeAction(actCall("rm -rf build"))).thenReturn(new AiProvider.ActionResult("cleaned", 0, null));
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(context, times(1)).requestApproval(argThat(r -> r.getQuestion().equals("Approve action: rm -rf build")
                && r.getOptions().equals(List.of("Yes", "Yes always", "No")) && r.getBlockId().equals("agentic-0-iter-1")));
        verify(provider).executeAction(actCall("rm -rf build"));
        verify(provider, never()).buildConversationPrompt(anyString(), argThat(h -> h.contains(REJECTION_TURN)));
        assertThat(events).filteredOn(ApprovalRequested.class::isInstance).singleElement()
                .extracting(e -> ((ApprovalRequested) e).question()).isEqualTo("Approve action: rm -rf build");
    }

    @Test
    void approval_yesAlways_disablesGateForFollowingIterations() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: a\nACTION: step one\nSUMMARY: s"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: b\nACTION: step two\nSUMMARY: s"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Done"));
        when(provider.executeAction(actCall(null))).thenReturn(new AiProvider.ActionResult("ok", 0, null));
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes always"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(context, times(1)).requestApproval(any(ApprovalRequest.class));
        verify(provider, times(2)).executeAction(actCall(null));
    }

    @Test
    void approval_no_skipsAction_recordsRejection_andContinuesLoop() throws Exception {
        stubOneActionThenAchieved();
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("No"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getOutput()).contains("[iteration 1 - REJECTED]: rm -rf build");
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(2);
        verify(provider, never()).executeAction(anyAction());
    }

    @Test
    void approval_no_appendsRejectionTurn_beforeNextReasoning() throws Exception {
        // The rejection must reach the model; the executor mutates one history instance, so snapshot it per call.
        List<List<ConversationPrompt.Turn>> snapshots = new ArrayList<>();
        when(provider.buildConversationPrompt(anyString(), anyList())).thenAnswer(inv -> {
            snapshots.add(List.copyOf(inv.<List<ConversationPrompt.Turn>>getArgument(1)));
            return "prompt";
        });
        when(provider.reason(reasonCall()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Do it\nACTION: rm -rf build\nSUMMARY: Clean"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Done"));
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("No"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).doesNotContain(REJECTION_TURN);
        assertThat(snapshots.get(1)).last().isEqualTo(REJECTION_TURN);
        assertThat(snapshots.get(1)).containsOnlyOnce(REJECTION_TURN);
    }

    @Test
    void approval_futureFails_abortsWithApprovalError() throws Exception {
        stubOneActionThenAchieved();
        ExecutionContext context = contextAnswering(
                CompletableFuture.failedFuture(new IllegalStateException("channel closed")));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[iteration 1 - APPROVAL ERROR: ");
        assertThat(result.getOutput()).contains("channel closed");
        verify(provider, never()).executeAction(anyAction());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel_andAppearInMetadata() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall("opus", null, null)))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok"));

        ExecutionContext context = buildContext();
        context.setProvider("PI");
        context.setModel("gpt-5");
        BlockDefinition block = buildBlock("Test goal");
        block.getConfig().put("provider", "CLAUDE");
        block.getConfig().put("model", "opus");

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(providers).resolve("CLAUDE");
        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "opus")
                .doesNotContainKey("effort");
        assertThat(result.getMetadata()).containsEntry("model", "opus");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withoutBlockOverride_usesWorkflowProviderAndModel() throws Exception {
        promptIsBuilt();
        when(provider.reason(reasonCall("sonnet", null, "high")))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok"));

        ExecutionContext context = buildContext();
        context.setProvider("CLAUDE");
        context.setModel("sonnet");
        context.setEffort("high");

        StepResult result = executor.execute(buildBlock("Test goal"), context);

        verify(providers).resolve("CLAUDE");
        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "sonnet")
                .containsEntry("effort", "high");
    }

    /**
     * pi reasons with a --thinking level even without an effort (maxTurns heuristic or its default): the step names
     * it, beside the effort that was set (here none).
     */
    @Test
    @SuppressWarnings("unchecked")
    void execute_piReasoning_namesItsThinkingLevelInAiRequest() throws Exception {
        AiProvider pi = mock(AiProvider.class);
        when(pi.getId()).thenReturn("PI");
        when(pi.getCapabilities()).thenReturn(new AiProviderCapabilities(true, true, true, false, true, false, true, true));
        when(pi.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(pi.reason(any())).thenReturn(new AiProvider.ReasoningResult(
                "GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok",
                AiResponseMetadata.estimated(10, 5), "medium"));
        when(providers.resolve(any())).thenReturn(pi);

        ExecutionContext context = buildContext();
        context.setProvider("PI");

        StepResult result = executor.execute(buildBlock("Test goal"), context);

        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest).containsEntry("provider", "PI").containsEntry("reasonThinking", "medium")
                .doesNotContainKey("effort");
    }

    /** A provider without an effort flag: the block does not claim the workflow effort it could not apply. */
    @Test
    @SuppressWarnings("unchecked")
    void execute_providerWithoutEffortSupport_doesNotClaimTheEffort() throws Exception {
        AiProvider pi = mock(AiProvider.class);
        when(pi.getId()).thenReturn("PI");
        when(pi.getCapabilities()).thenReturn(AiProviderCapabilities.minimal());
        when(pi.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(pi.reason(any())).thenReturn(new AiProvider.ReasoningResult(
                "GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok",
                AiResponseMetadata.estimated(10, 5)));
        when(providers.resolve(any())).thenReturn(pi);

        ExecutionContext context = buildContext();
        context.setProvider("PI");
        context.setEffort("high");

        StepResult result = executor.execute(buildBlock("Test goal"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest).containsEntry("provider", "PI").doesNotContainKeys("effort", "reasonThinking");
    }
}
