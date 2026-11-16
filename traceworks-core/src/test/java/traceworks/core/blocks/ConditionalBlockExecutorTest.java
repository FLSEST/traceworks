package traceworks.core.blocks;

import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConditionalBlockExecutorTest {

    /** What the runner says about a command whose stdout went beyond the output limit. */
    private static final String NOTE = "The command's standard output exceeded 16 MiB; Traceworks stopped it and"
            + " kept the first 16 MiB";

    @Mock
    private WrapperCommands commands;

    private ConditionalBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new ConditionalBlockExecutor(commands);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
        context.setProjectPath("/test/project");
    }

    private static AiStepCall.Answer answer(String text) {
        return new AiStepCall.Answer(text, 0, null);
    }

    private static StepResult step(StepStatus status, String output) {
        return StepResult.builder().stepIndex(1).status(status).output(output).build();
    }

    private static ShellResult exit(int code) {
        return new ShellResult("", code, 0);
    }

    /** A conditional in AI mode, written out as Traceworks requires. */
    private static Map<String, Object> aiConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "ai");
        return config;
    }

    private static BlockDefinition conditional(Map<String, Object> config) {
        return BlockDefinition.builder().type("conditional").config(config).build();
    }

    @Test
    void supports_conditionalType() {
        assertThat(executor.supports("conditional")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void missingExecutionModeFails() {
        Map<String, Object> config = new HashMap<>();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("execution_mode is required (shell or ai)");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_conditionTrue_executesThenBranch() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any())).thenReturn(answer("true"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "deployed"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");
        config.put("else_command", "echo 'skipping'");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("deployed");
        assertThat(result.getInput()).contains("true");
    }

    /** A branch the cancel ended is cancelled. */
    @Test
    void execute_aBranchTheCancelEnded_isCancelled() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any())).thenReturn(answer("true"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            context.cancel();
            return step(StepStatus.FAILED, "terminated");
        });
        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getOutput()).isEqualTo("terminated");
    }

    /** A shell condition the cancel killed (exit 130) is no "false": no branch runs, and no SKIPPED either. */
    @Test
    void execute_aShellConditionTheCancelKilled_takesNoBranch_andIsCancelled() {
        when(commands.runCommand(anyString(), anyInt(), any())).thenAnswer(inv -> {
            context.cancel();
            return exit(130);
        });
        Map<String, Object> config = new HashMap<>(Map.of("execution_mode", "shell", "condition", "./check",
                "then_command", "./deploy"));

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getOutput()).isEqualTo(
                traceworks.core.engine.CancelledSteps.WHILE_RUNNING);
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    @Test
    void execute_conditionFalse_executesElseBranch() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any())).thenReturn(answer("false"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "skipped"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");
        config.put("else_command", "echo 'skipping'");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getOutput()).isEqualTo("skipped");
    }

    @Test
    void execute_conditionTrue_noElseBranch_skips() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any())).thenReturn(answer("false"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "deploy");
        // no else_command

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).contains("else");
    }

    @Test
    void execute_ambiguousResponse_notTreatedAsTrue() {
        // Edge case: the response contains "true" but means false
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any()))
                .thenReturn(answer("The condition is false, not true"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "skipped"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");
        config.put("else_command", "echo 'skipping'");

        StepResult result = executor.execute(conditional(config), context);

        // evaluates as false (runs the else branch), not as true
        assertThat(result.getInput()).contains("false");
        assertThat(result.getOutput()).isEqualTo("skipped");
    }

    @Test
    void execute_conditionFalse_noThenDefined_butThenWouldExecute() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any())).thenReturn(answer("true"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "always true");
        // no then_command
        config.put("else_command", "echo 'else'");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).contains("then");
    }

    @Test
    void execute_piProvider_passesProviderIdToRequests() {
        context.setProvider("PI");
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "x == 1");
        config.put("then_command", "do something");

        executor.execute(conditional(config), context);

        verify(commands).ask(argThat(req -> "PI".equals(req.providerId())), any());
        verify(commands).aiStep(argThat(req -> "PI".equals(req.providerId())), any());
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequests() {
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "x == 1");
        config.put("then_command", "do something");

        executor.execute(conditional(config), context);

        verify(commands).ask(argThat(req -> req.providerId() == null), any());
        verify(commands).aiStep(argThat(req -> req.providerId() == null), any());
    }

    @Test
    void execute_shellMode_exitCodeZero_executesThenBranch() {
        when(commands.runCommand(eq("test -f /tmp/x"), anyInt(), any())).thenReturn(exit(0));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(StepStatus.COMPLETED, "then-shell-output"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "test -f /tmp/x");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("then-shell-output");
        assertThat(result.getInput()).contains("true");
        verify(commands, never()).ask(any(AiStepRequest.class), any());
    }

    /** The branch command honours container_image like retry, foreach, while and switch. */
    @Test
    void execute_shellMode_passesContainerImageAndMountsToTheBranchCommand() {
        when(commands.runCommand(anyString(), anyInt(), any())).thenReturn(exit(0));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "true");
        config.put("then_command", "node --version");
        config.put("container_image", "automation-agent-node:latest");
        config.put("mount_docker_socket", false);
        config.put("mounts", List.of("/data:/data:ro"));

        executor.execute(conditional(config), context);

        ArgumentCaptor<BlockDefinition> shellBlock = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(shellBlock.capture(), anyInt(), anyInt(), any());
        assertThat(shellBlock.getValue().getType()).isEqualTo("shell");
        assertThat(shellBlock.getValue().getConfig())
                .containsEntry("command", "node --version")
                .containsEntry("container_image", "automation-agent-node:latest")
                .containsEntry("mount_docker_socket", false)
                .containsEntry("mounts", List.of("/data:/data:ro"));
    }

    @Test
    void execute_shellMode_withoutContainerImage_staysOnTheHost() {
        when(commands.runCommand(anyString(), anyInt(), any())).thenReturn(exit(0));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "true");
        config.put("then_command", "echo then");

        executor.execute(conditional(config), context);

        ArgumentCaptor<BlockDefinition> shellBlock = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(shellBlock.capture(), anyInt(), anyInt(), any());
        assertThat(shellBlock.getValue().getConfig()).containsOnlyKeys("command");
    }

    @Test
    void execute_shellMode_nonZeroExit_executesElseBranch() {
        when(commands.runCommand(anyString(), anyInt(), any())).thenReturn(exit(1));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(StepStatus.COMPLETED, "else-shell"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "test -f /tmp/x");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getOutput()).isEqualTo("else-shell");
        assertThat(result.getInput()).contains("false");
    }

    /**
     * A condition whose output went beyond the limit fails the block and runs no branch: the runner stopped it, so
     * its exit code is not the condition's answer.
     */
    @Test
    void execute_shellMode_conditionBeyondTheOutputLimit_failsAndRunsNoBranch() {
        when(commands.runCommand(anyString(), anyInt(), any()))
                .thenReturn(new ShellResult("y\n" + NOTE, 153, 0, NOTE));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "yes");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getInput()).isEqualTo("if (yes) → evaluation failed");
        assertThat(result.getOutput()).isEqualTo("The condition failed: " + NOTE);
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition that ran out of time fails the block and runs no branch; the runner's mark decides. */
    @Test
    void execute_shellMode_conditionTimesOut_failsAndRunsNoBranch() {
        when(commands.runCommand(anyString(), anyInt(), any()))
                .thenReturn(new ShellResult("Command timed out after 300 seconds", 124, 0, "", true));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "sleep 999");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getInput()).isEqualTo("if (sleep 999) → evaluation failed");
        assertThat(result.getOutput()).isEqualTo("The condition failed: it timed out after 300 seconds");
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition the runner refused never ran: the block fails with the reason and runs no branch. */
    @Test
    void execute_shellMode_conditionDidNotRun_failsAndRunsNoBranch() {
        when(commands.runCommand(anyString(), anyInt(), any())).thenReturn(new ShellResult(
                "image traceworks/shell:0123456789ab is not present; run traceworks images build", 125, 0, "", false, "image traceworks/shell:0123456789ab is not present; run traceworks images build"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "test -f failed");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getInput()).isEqualTo("if (test -f failed) → evaluation failed");
        assertThat(result.getOutput()).isEqualTo("The condition failed: it did not run: image traceworks/shell:0123456789ab is not present; run traceworks images build");
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    /** A condition that exits 125, 1 or 74 by itself is false: without the runner's mark the else branch runs. */
    @ParameterizedTest
    @ValueSource(ints = {125, 1, 74})
    void execute_shellMode_conditionExitsLikeARefusalByItself_takesTheElseBranch(int exitCode) {
        when(commands.runCommand(anyString(), anyInt(), any())).thenReturn(exit(exitCode));
        when(commands.shellStep(any(), anyInt(), anyInt(), any())).thenReturn(step(StepStatus.COMPLETED, "else-shell"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "odd");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getOutput()).isEqualTo("else-shell");
        assertThat(result.getInput()).contains("false");
    }

    /** A condition that exits 124 by itself is false: the else branch runs. */
    @Test
    void execute_shellMode_conditionExits124ByItself_takesTheElseBranch() {
        when(commands.runCommand(anyString(), anyInt(), any())).thenReturn(exit(124));
        when(commands.shellStep(any(), anyInt(), anyInt(), any())).thenReturn(step(StepStatus.COMPLETED, "else-shell"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "odd");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getOutput()).isEqualTo("else-shell");
        assertThat(result.getInput()).contains("false");
    }

    @Test
    void execute_shellMode_falsy_noElseDefined_skips() {
        when(commands.runCommand(anyString(), anyInt(), any())).thenReturn(exit(1));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "false");
        config.put("then_command", "echo then");
        // no else_command

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).contains("else");
        verify(commands, never()).shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any());
    }

    @Test
    void execute_resolvesVariablesInCondition() {
        context.setVariable("branch", "main");
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("TRUE"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == '{{ branch }}'");
        config.put("then_command", "deploy");

        StepResult result = executor.execute(conditional(config), context);

        // the condition is resolved before it reaches the model
        verify(commands).ask(argThat(req ->
                req.prompt().contains("branch == 'main'") && !req.prompt().contains("{{ branch }}")), any());
        assertThat(result.getInput()).contains("branch == 'main'");
    }

    @Test
    void execute_resolvesVariablesInThenCommand() {
        context.setVariable("env", "production");
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("TRUE"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "done"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "always");
        config.put("then_command", "deploy to {{ env }}");

        executor.execute(conditional(config), context);

        // the then_command is resolved before it runs
        verify(commands).aiStep(argThat(req -> "deploy to production".equals(req.prompt())), any());
    }

    @Test
    void execute_customTimeout_passedToShellExecutor() {
        when(commands.runCommand(anyString(), eq(99), any())).thenReturn(exit(0));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), eq(99), any()))
                .thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "true");
        config.put("then_command", "echo ok");
        config.put("timeout_seconds", 99);

        executor.execute(conditional(config), context);

        verify(commands).runCommand(anyString(), eq(99), any());
        verify(commands).shellStep(any(BlockDefinition.class), anyInt(), eq(99), any());
    }

    @Test
    void execute_customTimeout_passedToAiExecutor() {
        when(commands.ask(argThat(req -> req != null && Integer.valueOf(77).equals(req.timeoutSeconds())), any()))
                .thenReturn(answer("TRUE"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "x == 1");
        config.put("then_command", "do");
        config.put("timeout_seconds", 77);

        executor.execute(conditional(config), context);

        verify(commands).aiStep(argThat(req -> Integer.valueOf(77).equals(req.timeoutSeconds())), any());
    }

    @Test
    void execute_truthyResponseWithWhitespaceAndLowercase_treatedAsTrue() {
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("  true\n"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "then-ran"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "x");
        config.put("then_command", "do");
        config.put("else_command", "skip");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getOutput()).isEqualTo("then-ran");
        assertThat(result.getInput()).contains("true");
    }

    @Test
    void execute_setsDurationAndTimestamps() {
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("FALSE"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "x");
        config.put("then_command", "do");
        // no else: SKIPPED

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getCompletedAt()).isAfterOrEqualTo(result.getStartedAt());
    }

    @Test
    void execute_propagatesFailedStepStatus() {
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("TRUE"));
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(StepStatus.FAILED, "error: deploy broken"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "x");
        config.put("then_command", "deploy");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("error");
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel_forConditionAndBranch() {
        context.setProvider("PI");
        context.setModel("gpt-5");
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("true"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "x == 1");
        config.put("then_command", "do something");
        config.put("provider", "PI");
        config.put("model", "anthropic/opus");

        executor.execute(conditional(config), context);

        verify(commands).ask(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())), any());
        verify(commands).aiStep(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())), any());
    }

    @Test
    void execute_shellMode_untrustedVariableInCondition_isSingleQuoted() {
        context.setUntrustedVariable("branch", "main; id");
        when(commands.runCommand(eq(FakeCommandRunner.value(1, "main; id") + "test \"${TRACEWORKS_VALUE_1}\" = main"), anyInt(), any())).thenReturn(exit(1));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "test {{ branch }} = main");
        config.put("then_command", "echo then");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getInput()).contains("false");
        verify(commands).runCommand(eq(FakeCommandRunner.value(1, "main; id") + "test \"${TRACEWORKS_VALUE_1}\" = main"),
                anyInt(), any());
    }

    @Test
    void execute_shellMode_escapeOption_quotesPreviousOutputInConditionAndBranch() {
        context.recordOutput("audit", "line one\nVERIFICATION_PASSED");
        when(commands.runCommand(eq("echo 'line one\nVERIFICATION_PASSED' | grep -q VERIFICATION_PASSED"), anyInt(),
                any())).thenReturn(exit(0));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("shell_escape_variables", true);
        config.put("condition", "echo {{ previous_output }} | grep -q VERIFICATION_PASSED");
        config.put("then_command", "printf %s {{ previous_output }}");

        executor.execute(conditional(config), context);

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), anyInt(), any());
        assertThat(captor.getValue().getConfig().get("command"))
                .isEqualTo("printf %s 'line one\nVERIFICATION_PASSED'");
    }

    @Test
    void execute_aiMode_untrustedVariable_staysRawInPrompt() {
        context.setUntrustedVariable("branch", "main; id");
        when(commands.ask(any(AiStepRequest.class), any())).thenReturn(answer("FALSE"));

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch is {{ branch }}");
        config.put("then_command", "deploy");

        executor.execute(conditional(config), context);

        verify(commands).ask(argThat(req -> req.prompt().contains("branch is main; id")), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_recordsConditionMetAndCarriesAiRequestFromCondition() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any()))
                .thenReturn(new AiStepCall.Answer("TRUE", 0, Map.of(
                        "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                        "provider", "CLAUDE", "requestedModel", "opus")));
        StepResult thenResult = step(StepStatus.COMPLETED, "deployed");
        thenResult.setMetadata(Map.of("aiRequest", Map.of("provider", "PI"), "provider", "PI"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(thenResult);

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getOutput()).isEqualTo("deployed");
        assertThat(result.getMetadata()).containsEntry("conditionMet", true).containsEntry("provider", "CLAUDE");
        assertThat((Map<String, Object>) result.getMetadata().get("aiRequest")).containsEntry("model", "opus");
    }

    @Test
    void execute_noBranch_skippedButCarriesAiRequestFromCondition() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any()))
                .thenReturn(new AiStepCall.Answer("FALSE", 0, Map.of(
                        "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                        "provider", "CLAUDE", "requestedModel", "opus")));

        Map<String, Object> config = aiConfig();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");

        StepResult result = executor.execute(conditional(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getMetadata()).containsEntry("conditionMet", false)
                .containsEntry("provider", "CLAUDE").containsKey("aiRequest");
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_shellMode_dockerSocket_isRefusedBeforeTheConditionRuns() {
        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "true");
        config.put("then_command", "docker ps");
        config.put("container_image", "docker:cli");
        config.put("mount_docker_socket", true);

        assertThatThrownBy(() -> executor.execute(conditional(config), context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("mount_docker_socket");
        verifyNoInteractions(commands);
    }

    // on_condition_error

    private static BlockDefinition aiConditional(String onConditionError) {
        Map<String, Object> config = aiConfig();
        config.put("condition", "are there open findings?");
        config.put("then_command", "work through them");
        config.put("else_command", "report nothing to do");
        if (onConditionError != null) config.put("on_condition_error", onConditionError);
        return conditional(config);
    }

    private void conditionCallFails() {
        when(commands.ask(argThat(req -> req.prompt().contains("Evaluate")), any()))
                .thenReturn(new AiStepCall.Answer("Claude AI usage limit reached", 1, null));
    }

    @Test
    void conditionError_byDefault_failsTheStep_withoutRunningABranch() {
        conditionCallFails();

        StepResult result = executor.execute(aiConditional(null), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput())
                .isEqualTo("Condition evaluation failed (exit code 1: Claude AI usage limit reached)");
        assertThat(result.getMetadata())
                .containsEntry("conditionError", "exit code 1: Claude AI usage limit reached")
                .doesNotContainKey("conditionMet");
        assertThat(result.getCompletedAt()).isNotNull();
        verify(commands, never()).aiStep(any(), any());
    }

    @Test
    void conditionError_unknownValue_failsLikeTheDefault() {
        conditionCallFails();

        assertThat(executor.execute(aiConditional("ignore"), context).getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commands, never()).aiStep(any(), any());
    }

    @Test
    void conditionError_false_takesTheElseBranch_andKeepsTheError() {
        conditionCallFails();
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(StepResult.builder().status(StepStatus.COMPLETED).output("nothing to do").build());

        StepResult result = executor.execute(aiConditional("false"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("nothing to do");
        assertThat(result.getInput()).endsWith("→ false (evaluation failed, on_condition_error: false)");
        assertThat(result.getMetadata())
                .containsEntry("conditionMet", false)
                .containsEntry("conditionError", "exit code 1: Claude AI usage limit reached");
        ArgumentCaptor<AiStepRequest> branch = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(branch.capture(), any());
        assertThat(branch.getValue().prompt()).isEqualTo("report nothing to do");
    }

    @Test
    void conditionError_jsonBooleanFalse_meansFalse_andTrueMeansTheDefault() {
        conditionCallFails();
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(StepResult.builder().status(StepStatus.COMPLETED).output("nothing to do").build());
        BlockDefinition unquotedFalse = aiConditional(null);
        unquotedFalse.getConfig().put("on_condition_error", false);
        BlockDefinition unquotedTrue = aiConditional(null);
        unquotedTrue.getConfig().put("on_condition_error", true);

        assertThat(executor.execute(unquotedFalse, context).getOutput()).isEqualTo("nothing to do");
        assertThat(executor.execute(unquotedTrue, context).getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commands, times(1)).aiStep(any(), any());
    }

    @Test
    void conditionError_skip_skipsTheBlock() {
        conditionCallFails();

        StepResult result = executor.execute(aiConditional("skip"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).isEqualTo("Condition evaluation failed (exit code 1: Claude AI usage limit reached)"
                + " — block skipped (on_condition_error: skip)");
        verify(commands, never()).aiStep(any(), any());
    }

    @Test
    void conditionError_providerThrows_isAFailedEvaluationToo() {
        when(commands.ask(any(), any())).thenThrow(new WorkflowExecutionException(
                "AI call failed (project: /test/project)", new IOException("pi not found")));

        StepResult result = executor.execute(aiConditional("skip"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).contains("pi not found");
    }

    /** A typo in provider is refused, never settled as a false or skipped condition. */
    @Test
    void conditionError_unknownProvider_failsTheStep_whateverOnConditionError() {
        when(commands.ask(any(), any())).thenThrow(new AiStepCall.ProviderNotAvailable(
                new IllegalArgumentException("unknown AI provider 'NOPE'; configured: CLAUDE")));

        for (String onError : List.of("false", "skip")) {
            BlockDefinition block = aiConditional(onError);
            block.getConfig().put("provider", "NOPE");

            StepResult result = executor.execute(block, context);

            assertThat(result.getStatus()).as(onError).isEqualTo(StepStatus.FAILED);
            assertThat(result.getOutput()).as(onError)
                    .isEqualTo("Condition evaluation failed (unknown AI provider 'NOPE'; configured: CLAUDE)");
            assertThat(result.getMetadata()).as(onError).doesNotContainKey("conditionMet");
        }
        verify(commands, never()).aiStep(any(), any());
    }

    @Test
    void conditionError_inACancelledRun_takesNoBranch_evenWithFalse() {
        conditionCallFails();
        context.cancel();

        StepResult result = executor.execute(aiConditional("false"), context);

        assertThat(result.getStatus()).as("the cancel ended the evaluation").isEqualTo(StepStatus.CANCELLED);
        verify(commands, never()).aiStep(any(), any());
    }

    @Test
    void conditionError_doesNotApplyInShellMode_whereANonZeroExitIsFalse() {
        BlockDefinition block = aiConditional("skip");
        block.getConfig().put("execution_mode", "shell");
        when(commands.runCommand(any(), anyInt(), any())).thenReturn(exit(1));
        when(commands.shellStep(any(), anyInt(), anyInt(), any()))
                .thenReturn(StepResult.builder().status(StepStatus.COMPLETED).output("else ran").build());

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("else ran");
    }
}
