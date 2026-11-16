package traceworks.core.blocks;

import traceworks.core.context.ShellLineRefusedException;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.StepFailureSummary;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhileBlockExecutorTest {

    /** What the runner says about a command whose stdout went beyond the output limit. */
    private static final String NOTE = "The command's standard output exceeded 16 MiB; Traceworks stopped it and"
            + " kept the first 16 MiB";

    @Mock
    private WrapperCommands commands;

    private WhileBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new WhileBlockExecutor(commands);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .projectPath("/tmp/project")
                .build();
    }

    private static AiStepCall.Answer answer(String text) {
        return new AiStepCall.Answer(text, 0, null);
    }

    private static ShellResult exit(int code) {
        return new ShellResult("", code, 0);
    }

    private static StepResult step(StepStatus status, String output) {
        return StepResult.builder().status(status).output(output).build();
    }

    private static BlockDefinition whileOf(Map<String, Object> config) {
        return BlockDefinition.builder().type("while").config(config).build();
    }

    @Test
    void supports_whileType_returnsTrue() {
        assertThat(executor.supports("while")).isTrue();
    }

    @Test
    void supports_otherType_returnsFalse() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void missingExecutionModeFails() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "shorten the text");
        config.put("condition_command", "is the text still too long?");
        config.put("max_iterations", 3);

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("execution_mode is required (shell or ai)");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_shellMode_runsIterationsUntilConditionFails() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo hello");
        config.put("condition_command", "test -f /tmp/flag");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        // condition: true the first time, false the second time
        when(commands.runCommand(eq("test -f /tmp/flag"), eq(60), any(), any())).thenReturn(exit(0)).thenReturn(exit(1));
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "hello"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("[iteration 1]");
        verify(commands, times(1)).shellStep(any(), anyInt(), eq(60), any());
    }

    @Test
    void execute_reachesMaxIterations_completesNormally() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 3);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "loop"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("[iteration 1]");
        assertThat(result.getOutput()).contains("[iteration 3]");
        verify(commands, times(3)).shellStep(any(), anyInt(), eq(60), any());
    }

    @Test
    void execute_failedStep_stopsAndReturnsFailed() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "failing command");
        config.put("max_iterations", 5);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commands, times(1)).shellStep(any(), anyInt(), eq(60), any());
    }

    @Test
    void execute_cancelledContext_stopsImmediately() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 100);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        context.cancel();

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).as("the loop did not end by itself").isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getOutput()).isEmpty();
        verifyNoInteractions(commands);
    }

    /** A body the cancel ended is a cancelled iteration, and the loop is cancelled, not completed. */
    @Test
    void execute_aBodyTheCancelEnded_cancelsTheLoop() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 5);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "one"))
                .thenAnswer(inv -> {
                    context.cancel();
                    return step(StepStatus.FAILED, "terminated");
                });

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getMetadata().get("iterations")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .extracting(it -> (Object) ((Map<?, ?>) it).get("status")).containsExactly("COMPLETED", "CANCELLED");
        verify(commands, times(2)).shellStep(any(), anyInt(), eq(60), any());
    }

    /** A condition the cancel killed (exit 130) is no "false": the loop is cancelled. */
    @Test
    void execute_aConditionTheCancelKilled_cancelsTheLoop() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("condition_command", "./more");
        config.put("max_iterations", 5);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);
        when(commands.runCommand(eq("./more"), eq(60), any(), any())).thenAnswer(inv -> {
            context.cancel();
            return exit(130);
        });

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** An AI condition the cancel ended answers nothing, which is no "false": the loop is cancelled. */
    @Test
    void execute_anAiConditionTheCancelEnded_cancelsTheLoop() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "do work");
        config.put("condition_command", "check");
        config.put("max_iterations", 5);
        config.put("execution_mode", "ai");
        when(commands.ask(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            context.cancel();
            return answer("");
        });

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_recordsTheTimeTheLoopTook() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 1);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "done"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getDurationMs())
                .isEqualTo(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
    }

    /** A loop that ran to its limit before the cancel arrived has done its work. */
    @Test
    void execute_aCancelInTheLastIteration_leavesTheLoopCompleted() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 1);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenAnswer(inv -> {
            context.cancel();
            return step(StepStatus.COMPLETED, "done");
        });

        assertThat(executor.execute(whileOf(config), context).getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_aiMode_usesAiExecution() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze code");
        config.put("condition_command", "check condition");
        config.put("max_iterations", 2);
        config.put("execution_mode", "ai");

        when(commands.ask(argThat(req -> "check condition".equals(req.prompt())), any()))
                .thenReturn(answer("true"))
                .thenReturn(answer("false"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "analyzed"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("[iteration 1]");
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_setsInputDescription() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo");
        config.put("max_iterations", 1);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "done"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getInput()).contains("while loop");
        assertThat(result.getInput()).contains("max 1 iterations");
    }

    @Test
    void execute_piProvider_passesProviderIdToRequests() {
        context.setProvider("PI");
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "ai");

        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true")).thenReturn(answer("false"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(whileOf(config), context);

        verify(commands, atLeastOnce()).ask(argThat(req -> "PI".equals(req.providerId())), any());
        verify(commands).aiStep(argThat(req -> "PI".equals(req.providerId())), any());
    }

    @Test
    void execute_shellMode_initialFalseCondition_zeroIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "test -f /tmp/never");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        // condition false on the first call: a non-zero exit code, so the body never runs
        when(commands.runCommand(eq("test -f /tmp/never"), eq(60), any(), any())).thenReturn(exit(1));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getOutput()).isEmpty();
        verify(commands, times(1)).runCommand(eq("test -f /tmp/never"), eq(60), any(), any());
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition whose output went beyond the limit ends the loop and fails the step: it is no answer. */
    @Test
    void execute_shellMode_conditionBeyondTheOutputLimit_failsTheStep() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "check");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        when(commands.runCommand(eq("check"), eq(60), any(), any())).thenReturn(exit(0))
                .thenReturn(new ShellResult("y\n" + NOTE, 153, 0, NOTE));
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "hello"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("[iteration 1]: hello\n[condition] The condition failed: " + NOTE);
        verify(commands, times(1)).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition that ran out of time ends the loop and fails the step; the iterations so far stay. */
    @Test
    void execute_shellMode_conditionTimesOut_failsTheStep() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "check");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        when(commands.runCommand(eq("check"), eq(60), any(), any())).thenReturn(exit(0))
                .thenReturn(new ShellResult("Command timed out after 60 seconds", 124, 0, "", true));
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "hello"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput())
                .isEqualTo("[iteration 1]: hello\n[condition] The condition failed: it timed out after 60 seconds");
        verify(commands, times(1)).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition the runner refused never ran: the loop ends and the step fails; the iterations so far stay. */
    @Test
    void execute_shellMode_conditionDidNotRun_failsTheStep() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "check");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        when(commands.runCommand(eq("check"), eq(60), any(), any())).thenReturn(exit(0))
                .thenReturn(new ShellResult("image traceworks/shell:0123456789ab is not present; run traceworks images build", 125, 0, "", false, "image traceworks/shell:0123456789ab is not present; run traceworks images build"));
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "hello"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo(
                "[iteration 1]: hello\n[condition] The condition failed: it did not run: image traceworks/shell:0123456789ab is not present; run traceworks images build");
        verify(commands, times(1)).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition that exits 125, 1 or 74 by itself is false: without the runner's mark the loop just ends. */
    @ParameterizedTest
    @ValueSource(ints = {125, 1, 74})
    void execute_shellMode_conditionExitsLikeARefusalByItself_endsTheLoop(int exitCode) {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "odd");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        when(commands.runCommand(eq("odd"), eq(60), any(), any())).thenReturn(exit(exitCode));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isNotEqualTo(StepStatus.FAILED);
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition that exits 124 by itself is false: the loop ends without failing. */
    @Test
    void execute_shellMode_conditionExits124ByItself_endsTheLoop() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "odd");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        when(commands.runCommand(eq("odd"), eq(60), any(), any())).thenReturn(exit(124));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isNotEqualTo(StepStatus.FAILED);
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    @Test
    void execute_aiMode_initialFalseCondition_zeroIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "do work");
        config.put("condition_command", "check");
        config.put("max_iterations", 10);
        config.put("execution_mode", "ai");

        // the condition answers "false" right away, so the body never runs
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("false"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getOutput()).isEmpty();
        verify(commands, times(1)).ask(any(AiStepRequest.class), any());
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_aiMode_emptyConditionResult_treatedAsFalse() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "do work");
        config.put("condition_command", "check");
        config.put("max_iterations", 5);
        config.put("execution_mode", "ai");

        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("   "));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getOutput()).isEmpty();
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_aiMode_zeroConditionResult_treatedAsFalse() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "do work");
        config.put("condition_command", "check");
        config.put("max_iterations", 5);
        config.put("execution_mode", "ai");

        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("0"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getOutput()).isEmpty();
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_setsPreviousOutputBetweenIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 3);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        // each iteration returns its own output, so the test can tell them apart
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(
                step(StepStatus.COMPLETED, "output-1"),
                step(StepStatus.COMPLETED, "output-2"),
                step(StepStatus.COMPLETED, "output-3"));

        executor.execute(whileOf(config), context);

        // previous_output reflects the LAST iteration: it was updated each time
        assertThat(context.getPreviousOutput()).isEqualTo("output-3");
    }

    @Test
    void execute_failedStep_outputContainsIterationNumber() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 5);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        // the first two iterations succeed, the third fails: the failure names "iteration 3"
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(
                step(StepStatus.COMPLETED, "ok1"),
                step(StepStatus.COMPLETED, "ok2"),
                step(StepStatus.FAILED, "boom"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[iteration 1]: ok1");
        assertThat(result.getOutput()).contains("[iteration 2]: ok2");
        assertThat(result.getOutput()).contains("[iteration 3]: boom");
        // the loop stopped: no fourth call
        verify(commands, times(3)).shellStep(any(), anyInt(), eq(60), any());
    }

    @Test
    void execute_shellMode_passesCustomTimeoutToCondition() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "check it");
        config.put("max_iterations", 1);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 17);

        when(commands.runCommand(eq("check it"), eq(17), any(), any())).thenReturn(exit(0));
        when(commands.shellStep(any(), anyInt(), eq(17), any())).thenReturn(step(StepStatus.COMPLETED, "done"));

        executor.execute(whileOf(config), context);

        // condition and body both use the configured 17 s timeout
        verify(commands).runCommand(eq("check it"), eq(17), any(), any());
        verify(commands).shellStep(any(), anyInt(), eq(17), any());
    }

    @Test
    void execute_aiMode_passesCustomTimeoutToRequests() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "ai");
        config.put("timeout_seconds", 42);

        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true")).thenReturn(answer("false"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(whileOf(config), context);

        verify(commands, atLeastOnce()).ask(argThat(req -> Integer.valueOf(42).equals(req.timeoutSeconds())), any());
        verify(commands).aiStep(argThat(req -> Integer.valueOf(42).equals(req.timeoutSeconds())), any());
    }

    @Test
    void execute_aiMode_omittedTimeoutSeconds_passesNullTimeout() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "ai");
        // no timeout_seconds: the AI timeout stays null

        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true")).thenReturn(answer("false"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(whileOf(config), context);

        verify(commands, atLeastOnce()).ask(argThat(req -> req.timeoutSeconds() == null), any());
        verify(commands).aiStep(argThat(req -> req.timeoutSeconds() == null), any());
    }

    @Test
    void execute_noConditionCommand_runsExactlyMaxIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo");
        config.put("max_iterations", 4);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);
        // no condition_command: only max_iterations bounds the loop

        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "x"));

        StepResult result = executor.execute(whileOf(config), context);

        verify(commands, times(4)).shellStep(any(), anyInt(), eq(60), any());
        // the condition is never asked
        verify(commands, never()).runCommand(anyString(), anyInt(), any(), any());
        assertThat(result.getOutput()).contains("[iteration 4]");
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequests() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "ai");

        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true")).thenReturn(answer("false"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(whileOf(config), context);

        verify(commands, atLeastOnce()).ask(argThat(req -> req.providerId() == null), any());
        verify(commands).aiStep(argThat(req -> req.providerId() == null), any());
    }

    @Test
    void execute_shellMode_untrustedVariableInConditionAndCommand_isRefused() {
        context.setUntrustedVariable("target", "x; id");
        Map<String, Object> config = new HashMap<>();
        config.put("command", "build {{ target }}");
        config.put("condition_command", "check {{ target }}");
        config.put("max_iterations", 5);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        assertThatThrownBy(() -> executor.execute(whileOf(config), context))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void execute_shellMode_taintedIterationOutput_reachesTheNextIterationAsAValue() {
        context.setUntrustedVariable("u", "z");   // webhook payload: the run is tainted
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(step(StepStatus.COMPLETED, "out; id"));
        Map<String, Object> config = new HashMap<>();
        config.put("values", Map.of("PREVIOUS", "{{ previous_output }}"));
        config.put("command", "echo \"$PREVIOUS\"");
        config.put("max_iterations", 2);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        executor.execute(whileOf(config), context);

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands, times(2)).shellStep(captor.capture(), anyInt(), eq(60), any());
        assertThat(captor.getAllValues().get(1).getConfig()).containsEntry("command", "echo \"$PREVIOUS\"")
                .containsEntry("values", Map.of("PREVIOUS", "out; id"));
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel_forConditionAndBody() {
        context.setProvider("PI");
        context.setModel("gpt-5");
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "ai");
        config.put("provider", "PI");
        config.put("model", "anthropic/opus");

        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true")).thenReturn(answer("false"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        executor.execute(whileOf(config), context);

        verify(commands, atLeastOnce()).ask(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())), any());
        verify(commands).aiStep(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_aiMode_carriesAiRequestFromConditionWhenBodyNeverRuns() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze code");
        config.put("condition_command", "check condition");
        config.put("execution_mode", "ai");

        when(commands.ask(argThat(req -> "check condition".equals(req.prompt())), any()))
                .thenReturn(new AiStepCall.Answer("false", 0, Map.of(
                        "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                        "provider", "CLAUDE", "requestedModel", "opus")));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("provider", "CLAUDE").containsEntry("iterationCount", 0);
        assertThat((Map<String, Object>) result.getMetadata().get("aiRequest")).containsEntry("model", "opus");
        assertThat((List<Map<String, Object>>) result.getMetadata().get("iterations")).isEmpty();
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_recordsIterationsIncludingFailedOne() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze code");
        config.put("max_iterations", 5);
        config.put("execution_mode", "ai");

        StepResult ok = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("ok").durationMs(5L).build();
        ok.setMetadata(Map.of(
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                "provider", "CLAUDE", "requestedModel", "opus"));
        StepResult failed = StepResult.builder().stepIndex(2).status(StepStatus.FAILED).output("boom").build();
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok, failed);

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("provider", "CLAUDE").containsEntry("iterationCount", 2);
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) result.getMetadata().get("iterations");
        assertThat(iterations).hasSize(2);
        assertThat(iterations.get(0)).containsEntry("index", 0).containsEntry("status", "COMPLETED")
                .containsEntry("durationMs", 5L);
        assertThat(iterations.get(1)).containsEntry("index", 1).containsEntry("status", "FAILED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_moreIterationsThanRecorded_countsEveryExecutedIteration() {
        int runs = ForEachBlockExecutor.MAX_RECORDED_ITEMS + 1;
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", runs);
        config.put("execution_mode", "shell");
        when(commands.shellStep(any(), anyInt(), anyInt(), any())).thenReturn(step(StepStatus.COMPLETED, "loop"));

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("iterationCount", runs);
        assertThat((List<Map<String, Object>>) result.getMetadata().get("iterations"))
                .hasSize(ForEachBlockExecutor.MAX_RECORDED_ITEMS);
        verify(commands, times(runs)).shellStep(any(), anyInt(), anyInt(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_failedIterationBeyondTheRecordingLimit_stillCountsAsExecuted() {
        int runs = ForEachBlockExecutor.MAX_RECORDED_ITEMS + 100;
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", runs + 50);
        config.put("execution_mode", "shell");
        StepResult ok = step(StepStatus.COMPLETED, "loop");
        StepResult failed = step(StepStatus.FAILED, "boom");
        int[] calls = {0};
        when(commands.shellStep(any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> ++calls[0] == runs ? failed : ok);

        StepResult result = executor.execute(whileOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("iterationCount", runs);
        assertThat((List<Map<String, Object>>) result.getMetadata().get("iterations"))
                .hasSize(ForEachBlockExecutor.MAX_RECORDED_ITEMS);
        assertThat(result.getOutput()).endsWith("[iteration " + runs + "]: boom");
    }

    // on_condition_error

    private static BlockDefinition aiWhile(String onConditionError) {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "shorten the text");
        config.put("condition_command", "is the text still too long?");
        config.put("max_iterations", 3);
        config.put("execution_mode", "ai");
        if (onConditionError != null) config.put("on_condition_error", onConditionError);
        return whileOf(config);
    }

    @Test
    void conditionError_byDefault_failsTheStep_insteadOfLoopingOnTheErrorText() {
        when(commands.ask(any(), any())).thenReturn(new AiStepCall.Answer("Claude AI usage limit reached", 1, null));

        StepResult result = executor.execute(aiWhile(null), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput())
                .isEqualTo("[condition] Condition evaluation failed (exit code 1: Claude AI usage limit reached)");
        assertThat(result.getMetadata())
                .containsEntry("iterationCount", 0)
                .containsEntry("conditionError", "exit code 1: Claude AI usage limit reached")
                .containsEntry(StepFailureSummary.KEY,
                        "Condition evaluation failed (exit code 1: Claude AI usage limit reached)");
        verify(commands, never()).aiStep(any(), any());
    }

    @Test
    void conditionError_afterAnIteration_failsAndKeepsTheIterationOutput() {
        when(commands.ask(any(), any()))
                .thenReturn(answer("true"))
                .thenReturn(new AiStepCall.Answer("session limit", 1, null));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "shorter"));

        StepResult result = executor.execute(aiWhile("fail"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("[iteration 1]: shorter\n"
                + "[condition] Condition evaluation failed (exit code 1: session limit)");
        assertThat(result.getMetadata()).containsEntry("iterationCount", 1);
    }

    @Test
    void conditionError_false_endsTheLoop_andTheStepCompletes() {
        when(commands.ask(any(), any()))
                .thenReturn(answer("true"))
                .thenReturn(new AiStepCall.Answer("session limit", 1, null));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "shorter"));

        StepResult result = executor.execute(aiWhile("false"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("[iteration 1]: shorter");
        assertThat(result.getMetadata())
                .containsEntry("iterationCount", 1)
                .containsEntry("conditionError", "exit code 1: session limit");
    }

    @Test
    void conditionError_jsonBooleanFalse_endsTheLoopLikeTheText() {
        when(commands.ask(any(), any())).thenReturn(new AiStepCall.Answer("session limit", 1, null));
        BlockDefinition block = aiWhile(null);
        block.getConfig().put("on_condition_error", false);

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("iterationCount", 0);
    }

    @Test
    void conditionError_skip_skipsTheBlock_evenAfterIterationsRan() {
        when(commands.ask(any(), any()))
                .thenReturn(answer("true"))
                .thenReturn(new AiStepCall.Answer("session limit", 1, null));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "shorter"));

        StepResult result = executor.execute(aiWhile("skip"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).isEqualTo("[iteration 1]: shorter\n"
                + "[condition] Condition evaluation failed (exit code 1: session limit)"
                + " — block skipped (on_condition_error: skip)");
    }

    /** A typo in provider must not end the loop COMPLETED with 0 iterations under on_condition_error: false. */
    @Test
    void conditionError_unknownProvider_failsTheStep_whateverOnConditionError() {
        when(commands.ask(any(), any())).thenThrow(new AiStepCall.ProviderNotAvailable(
                new IllegalArgumentException("unknown AI provider 'NOPE'; configured: CLAUDE")));
        BlockDefinition block = aiWhile("false");
        block.getConfig().put("provider", "NOPE");

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput())
                .isEqualTo("[condition] Condition evaluation failed (unknown AI provider 'NOPE'; configured: CLAUDE)");
        assertThat(result.getMetadata()).containsEntry("iterationCount", 0)
                .containsEntry(StepFailureSummary.KEY,
                        "Condition evaluation failed (unknown AI provider 'NOPE'; configured: CLAUDE)");
        verify(commands, never()).aiStep(any(), any());
    }

    /** A blank AI condition is refused before any call, also when a placeholder resolves blank. */
    @Test
    void blankAiCondition_isRefusedWithoutACall() {
        context.setVariable("check", "");
        for (String blank : List.of("   ", "{{ check }}")) {
            BlockDefinition block = aiWhile(null);
            block.getConfig().put("condition_command", blank);

            assertThatThrownBy(() -> executor.execute(block, context)).as(blank)
                    .isInstanceOf(WorkflowExecutionException.class)
                    .hasMessage("The condition is blank; an AI condition needs a prompt");
        }
        verifyNoInteractions(commands);
    }

    @Test
    void conditionError_providerThrows_isAFailedEvaluationToo() {
        when(commands.ask(any(), any())).thenThrow(new WorkflowExecutionException(
                "AI call failed (project: /tmp/project)", new IOException("pi not found")));

        StepResult result = executor.execute(aiWhile(null), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("pi not found");
    }
}
