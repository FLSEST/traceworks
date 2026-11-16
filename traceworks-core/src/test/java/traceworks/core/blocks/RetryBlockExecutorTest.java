package traceworks.core.blocks;

import traceworks.core.context.ShellLineRefusedException;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryBlockExecutorTest {

    @Mock
    private WrapperCommands commands;

    private RetryBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new RetryBlockExecutor(commands);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
        context.setProjectPath("/test/project");
    }

    private static BlockDefinition retry(Map<String, Object> config) {
        return BlockDefinition.builder().type("retry").config(new HashMap<>(config)).build();
    }

    /** A retry in AI mode, written out as Traceworks requires. */
    private static BlockDefinition aiRetry(Map<String, Object> config) {
        BlockDefinition block = retry(config);
        block.getConfig().put("execution_mode", "ai");
        return block;
    }

    private static StepResult step(StepStatus status, String output) {
        return StepResult.builder().stepIndex(0).status(status).output(output).build();
    }

    @Test
    void supports_retryType() {
        assertThat(executor.supports("retry")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void missingExecutionModeFails() {
        StepResult result = executor.execute(retry(Map.of("command", "npm test", "max_retries", 1, "delay", 0)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("execution_mode is required (shell or ai)")
                .isEqualTo(ExecutionMode.REQUIRED_MESSAGE);
        assertThat(result.getCompletedAt()).isNotNull();
        verifyNoInteractions(commands);
    }

    @Test
    void execute_unknownExecutionMode_failsInsteadOfRunningAsAi() {
        BlockDefinition block = retry(Map.of("command", "npm test", "max_retries", 1, "delay", 0, "execution_mode", "shel"));

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown execution_mode 'shel'");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_successOnFirstAttempt_returnsCompleted() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "passed"));

        StepResult result = executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 3, "delay", 0)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_failsThenSucceeds_retriesAndReturnsCompleted() {
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(StepStatus.FAILED, "failed"), step(StepStatus.COMPLETED, "passed"));

        StepResult result = executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 3, "delay", 0)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_allAttemptsFail_returnsLastFailedResult() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 2, "delay", 0)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        // 1 initial + 2 retries = 3 attempts
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_cancelledDuringRetry_stopsEarly() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            context.cancel(); // cancel after the first attempt
            return step(StepStatus.FAILED, "error");
        });

        StepResult result = executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 5, "delay", 0)), context);

        assertThat(result.getStatus()).as("a cancelled attempt is no failure to retry").isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getMetadata()).containsEntry("attempts", 1);
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
    }

    /** A cancel during the wait between two attempts ends the wait at once, and no further attempt starts. */
    @Test
    void execute_cancelDuringTheWait_endsTheWait_andStartsNoAttempt() {
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(StepStatus.FAILED, "error"));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.cancel();
        });

        long start = System.nanoTime();
        StepResult result = executor.execute(retry(Map.of(
                "command", "flaky", "max_retries", 1, "delay", 30, "execution_mode", "shell")), context);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("the cancel came at about 300 ms, the wait is 30 s").isLessThan(5_000);
        assertThat(result.getStatus()).as("the retries the cancel cut short").isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getMetadata()).containsEntry("attempts", 1);
        verify(commands, times(1)).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A run whose cancel flag was set without waking anyone still ends the wait within about a second. */
    @Test
    void execute_stopFlagSetDuringTheWait_endsTheWaitWithinASecond() {
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(StepStatus.FAILED, "error"));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.getCancelled().set(true);
        });

        long start = System.nanoTime();
        StepResult result = executor.execute(retry(Map.of(
                "command", "flaky", "max_retries", 1, "delay", 30, "execution_mode", "shell")), context);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).isLessThan(5_000);
        assertThat(result.getMetadata()).containsEntry("attempts", 1);
        verify(commands, times(1)).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** The doubled wait never wraps around: it saturates instead of turning negative or short. */
    @Test
    void waitSeconds_neverOverflows() {
        assertThat(RetryBlockExecutor.waitSeconds(5, false, 1)).isEqualTo(5);
        assertThat(RetryBlockExecutor.waitSeconds(5, true, 1)).isEqualTo(5);
        assertThat(RetryBlockExecutor.waitSeconds(5, true, 3)).isEqualTo(20);
        assertThat(RetryBlockExecutor.waitSeconds(5, true, 33)).isEqualTo(5L << 32);
        assertThat(RetryBlockExecutor.waitSeconds(5, true, 100)).isEqualTo(Long.MAX_VALUE);
        assertThat(RetryBlockExecutor.waitSeconds(Integer.MAX_VALUE, true, 40)).isEqualTo(Long.MAX_VALUE);
        assertThat(RetryBlockExecutor.waitSeconds(-1, true, 2)).isZero();
    }

    @Test
    void execute_defaultRetries_usesThree() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.FAILED, "error"));

        executor.execute(aiRetry(Map.of("command", "npm test", "delay", 0)), context);

        // 1 initial + 3 default retries = 4 attempts
        verify(commands, times(4)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_shellMode_usesShellExecution() {
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), eq(300), any()))
                .thenReturn(step(StepStatus.FAILED, "error"), step(StepStatus.COMPLETED, "passed"));

        StepResult result = executor.execute(retry(Map.of(
                "command", "npm test", "max_retries", 3, "delay", 0, "execution_mode", "shell")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(2)).shellStep(any(), anyInt(), eq(300), any());
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_piProvider_passesProviderIdToRequest() {
        context.setProvider("PI");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 1, "delay", 0)), context);

        verify(commands).aiStep(argThat(req -> "PI".equals(req.providerId())), any());
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequest() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 1, "delay", 0)), context);

        verify(commands).aiStep(argThat(req -> req.providerId() == null), any());
    }

    @Test
    void execute_maxRetriesZero_executesExactlyOnce() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.FAILED, "err"));

        StepResult result = executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 0, "delay", 0)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        // max_retries=0 means 1 attempt total (no retries)
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_maxRetriesOne_executesAtMostTwice_offByOneCheck() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.FAILED, "err"));

        executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 1, "delay", 0)), context);

        // 1 initial + 1 retry = exactly 2 attempts (off-by-one boundary)
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_commandExecutorThrowsException_propagatesUpwards() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenThrow(new RuntimeException("boom"));

        BlockDefinition block = aiRetry(Map.of("command", "npm test", "max_retries", 3, "delay", 0));

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void supports_returnsFalseForNonRetryTypes() {
        assertThat(executor.supports("shell")).isFalse();
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("agentic")).isFalse();
        assertThat(executor.supports("")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    @Test
    void execute_eachAttempt_sendsFreshRequest_contextIsolation() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.FAILED, "err"));

        executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 2, "delay", 0)), context);

        // Each retry must send a request: the count proves no shared mutable state blocks retries
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_shellMode_untrustedVariable_isRefused() {
        context.setUntrustedVariable("pkg", "left-pad; id");

        assertThatThrownBy(() -> executor.execute(retry(Map.of(
                "command", "npm install {{ pkg }}", "max_retries", 1, "delay", 0, "execution_mode", "shell")), context))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void execute_aiMode_untrustedVariable_staysRawInPrompt() {
        context.setUntrustedVariable("pkg", "left-pad; id");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(aiRetry(Map.of("command", "Review {{ pkg }}", "max_retries", 0, "delay", 0)), context);

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), any());
        assertThat(captor.getValue().prompt()).isEqualTo("Review left-pad; id");
    }

    @Test
    void execute_shellMode_taintedPreviousOutput_isRefused() {
        context.setUntrustedVariable("u", "z");   // webhook payload: the run is tainted
        context.recordOutput(null, "pkg; id");

        assertThatThrownBy(() -> executor.execute(retry(Map.of(
                "command", "npm install {{ previous_output }}", "max_retries", 1, "delay", 0,
                "execution_mode", "shell")), context))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("PI");
        context.setModel("gpt-5");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 1, "delay", 0,
                "provider", "PI", "model", "anthropic/opus")), context);

        verify(commands).aiStep(argThat(req -> "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())),
                any());
    }

    @Test
    void execute_recordsAttemptsAndMaxAttempts_keepingInnerAiMetadata() {
        StepResult failed = step(StepStatus.FAILED, "failed");
        StepResult success = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("passed").build();
        success.setMetadata(Map.of(
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                "provider", "CLAUDE", "requestedModel", "opus"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(failed, failed, success);

        StepResult result = executor.execute(aiRetry(Map.of("command", "npm test", "max_retries", 3, "delay", 0)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata())
                .containsEntry("attempts", 3)
                .containsEntry("maxAttempts", 4)
                .containsEntry("provider", "CLAUDE")
                .containsKey("aiRequest");
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_shellMode_innerImmutableMetadata_doesNotThrow() {
        StepResult shellFailed = step(StepStatus.FAILED, "no");
        shellFailed.setMetadata(Map.of("exitCode", 1, "executionMode", "shell"));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any())).thenReturn(shellFailed);

        StepResult result = executor.execute(retry(Map.of(
                "command", "false", "max_retries", 1, "delay", 0, "execution_mode", "shell")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("attempts", 2).containsEntry("maxAttempts", 2)
                .containsEntry("exitCode", 1);
    }
}
