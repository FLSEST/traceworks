package traceworks.core.blocks;

import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.SourceStep;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
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

/**
 * The {@code switch} block. Every expression uses {@code {{ var }}}, never the second syntax {@code ${var}}:
 * Traceworks does not resolve {@code ${var}} and refuses an expression that uses it ({@link #dollarSyntaxIsRefused}).
 */
@ExtendWith(MockitoExtension.class)
class SwitchBlockExecutorTest {

    @Mock
    private WrapperCommands commands;

    private SwitchBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new SwitchBlockExecutor(commands);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
        context.setProjectPath("/test/project");
    }

    private static StepResult step(int index, StepStatus status, String output) {
        return StepResult.builder().stepIndex(index).status(status).output(output).build();
    }

    private static BlockDefinition switchOf(Map<String, Object> config) {
        return BlockDefinition.builder().type("switch").config(config).build();
    }

    @Test
    void supports_switchType() {
        assertThat(executor.supports("switch")).isTrue();
        assertThat(executor.supports("conditional")).isFalse();
        assertThat(executor.supports("simple")).isFalse();
    }

    /**
     * Traceworks does not resolve {@code ${status}} in a switch expression. Instead of matching no case
     * and silently taking the default, the block is refused before any case runs.
     */
    @Test
    void dollarSyntaxIsRefused() {
        context.setVariable("status", "success");

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy app"))))));
        config.put("default", List.of(Map.of("type", "simple", "config", Map.of("command", "fallback"))));

        assertThatThrownBy(() -> executor.execute(switchOf(config), context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("The switch expression uses ${status}, which Traceworks does not resolve; write"
                        + " {{ status }}");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_expressionResolvesFromContextVariable() {
        context.setVariable("status", "success");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "deployed"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy app")))),
                Map.of("value", "failure", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "rollback"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("deployed");
        assertThat(result.getInput()).isEqualTo("switch (success)");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "success");
    }

    @Test
    void execute_exactMatchSelectsCorrectCase() {
        context.setVariable("env", "staging");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "staging deploy done"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ env }}");
        config.put("cases", List.of(
                Map.of("value", "production", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy prod")))),
                Map.of("value", "staging", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy staging")))),
                Map.of("value", "development", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy dev"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("staging deploy done");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "staging");
    }

    @Test
    void execute_pipeSeparatedValuesMatch() {
        context.setVariable("status", "critical");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "alert sent"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "log success")))),
                Map.of("value", "error|critical", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "send alert"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("alert sent");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "error|critical");
    }

    @Test
    void execute_defaultCaseWhenNoMatch() {
        context.setVariable("status", "unknown");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "default handler executed"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "celebrate"))))));
        config.put("default", List.of(
                Map.of("type", "simple", "config", Map.of("command", "handle unknown"))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("default handler executed");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "default");
    }

    @Test
    void execute_noDefaultAndNoMatch_completedWithNoMatchOutput() {
        context.setVariable("status", "unknown");

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "celebrate"))))));
        // no default

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("No matching case");
        assertThat(result.getOutput()).contains("unknown");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    @Test
    void execute_nestedBlocksExecuteCorrectly() {
        context.setVariable("action", "build");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "compiled"), step(2, StepStatus.COMPLETED, "tested"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ action }}");
        config.put("cases", List.of(
                Map.of("value", "build", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "mvn compile")),
                        Map.of("type", "simple", "config", Map.of("command", "mvn test"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("compiled");
        assertThat(result.getOutput()).contains("tested");
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_shellModeNestedBlocks() {
        context.setVariable("mode", "cleanup");
        when(commands.shellStep(any(), anyInt(), eq(300), any())).thenReturn(step(1, StepStatus.COMPLETED, "cleaned up"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ mode }}");
        config.put("cases", List.of(
                Map.of("value", "cleanup", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "rm -rf tmp/"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("cleaned up");
        verify(commands).shellStep(any(), anyInt(), eq(300), any());
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_nestedBlockFailure_propagatesFailedStatus() {
        context.setVariable("status", "deploy");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.FAILED, "deployment error"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(
                Map.of("value", "deploy", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy now"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("deployment error");
    }

    /** Same fail-closed on_failure rule as the top level: only "continue" runs the next case block. */
    @ParameterizedTest
    @CsvSource({"fail, 1", "stop, 1", "Continue, 2", "continue, 2"})
    void execute_nestedBlockFailure_onlyContinueRunsTheNextBlock(String onFailure, int expectedCalls) {
        context.setVariable("status", "deploy");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.FAILED, "deployment error"), step(1, StepStatus.COMPLETED, "notified"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(
                Map.of("value", "deploy", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy now", "on_failure", onFailure)),
                        Map.of("type", "simple", "config", Map.of("command", "notify"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commands, times(expectedCalls)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_doubleBraceExpressionSyntax() {
        context.setVariable("level", "warning");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "warning logged"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ level }}");
        config.put("cases", List.of(
                Map.of("value", "warning", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "log warning"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "warning");
    }

    @Test
    void execute_literalExpression_matchesDirectly() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "always");
        config.put("cases", List.of(
                Map.of("value", "always", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run always"))))));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "ran"));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "always");
    }

    @Test
    void execute_cancelledDuringExecution_stopsEarly() {
        context.setVariable("action", "multi");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            context.cancel();
            return step(1, StepStatus.COMPLETED, "first done");
        });

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ action }}");
        config.put("cases", List.of(
                Map.of("value", "multi", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "step one")),
                        Map.of("type", "simple", "config", Map.of("command", "step two")),
                        Map.of("type", "simple", "config", Map.of("command", "step three"))))));

        StepResult result = executor.execute(switchOf(config), context);

        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getStatus()).as("two case blocks never ran").isEqualTo(StepStatus.CANCELLED);
    }

    /** A case block the cancel ended is cancelled, not failed: on_failure: continue does not go on past it. */
    @Test
    void execute_aCaseBlockTheCancelEnded_cancelsTheSwitch() {
        context.setVariable("action", "multi");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            context.cancel();
            return step(1, StepStatus.FAILED, "terminated");
        });
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ action }}");
        config.put("cases", List.of(Map.of("value", "multi", "blocks", List.of(
                Map.of("type", "simple", "name", "one",
                        "config", Map.of("command", "step one", "on_failure", "continue")),
                Map.of("type", "simple", "config", Map.of("command", "step two"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        assertThat(context.getSourceSteps().find("one").orElseThrow().status()).isEqualTo(StepStatus.CANCELLED);
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
    }

    /** A switch whose case blocks all ran has done its work, also when the cancel came while the last one ran. */
    @Test
    void execute_aCancelWhileTheLastCaseBlockCompletes_leavesTheSwitchCompleted() {
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "first done"))
                .thenAnswer(inv -> {
                    context.cancel();
                    return step(2, StepStatus.COMPLETED, "second done");
                });
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "multi");
        config.put("cases", List.of(Map.of("value", "multi", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "step one")),
                Map.of("type", "simple", "config", Map.of("command", "step two"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("first done\nsecond done");
        assertThat(result.getDurationMs())
                .isEqualTo(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
    }

    /** The last case block the cancel ended cancels the switch, though no block was left out. */
    @Test
    void execute_theLastCaseBlockTheCancelEnded_cancelsTheSwitch() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            context.cancel();
            return step(1, StepStatus.FAILED, "terminated");
        });
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "one");
        config.put("cases", List.of(Map.of("value", "one", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "step one"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
    }

    /** A case output the output budget left out: the gate lines left in the switch's output may be cut, none counts. */
    @Test
    void execute_aCaseOutputBeyondTheOutputBudget_makesTheSwitchNoGateSource() {
        context = ExecutionContext.builder().containerId("container-1").workflowRunId("run-1")
                .outputBudget(new traceworks.core.context.OutputBudget(30)).build();
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "gate_status: GREEN"), step(2, StepStatus.COMPLETED,
                        "x".repeat(40)));
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "go");
        config.put("cases", List.of(Map.of("value", "go", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "check")),
                Map.of("type", "simple", "config", Map.of("command", "gate_status: RED"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getOutput()).startsWith("gate_status: GREEN").doesNotContain("xxx");
        assertThat(result.getMetadata()).containsEntry(traceworks.core.engine.GateSources.METADATA_KEY, false);
    }

    @Test
    void matchesValue_pipeSeparated_matchesAnyAlternative() {
        assertThat(executor.matchesValue("error", "error|critical|fatal")).isTrue();
        assertThat(executor.matchesValue("critical", "error|critical|fatal")).isTrue();
        assertThat(executor.matchesValue("fatal", "error|critical|fatal")).isTrue();
        assertThat(executor.matchesValue("warning", "error|critical|fatal")).isFalse();
    }

    @Test
    void matchesValue_exactMatch() {
        assertThat(executor.matchesValue("success", "success")).isTrue();
        assertThat(executor.matchesValue("success", "failure")).isFalse();
    }

    @Test
    void resolveExpression_dollarSyntax_staysLiteral() {
        context.setVariable("myVar", "resolved");
        assertThat(executor.resolveExpression("${myVar}", context)).isEqualTo("${myVar}");
    }

    @Test
    void resolveExpression_braceSyntax() {
        context.setVariable("myVar", "resolved");
        assertThat(executor.resolveExpression("{{ myVar }}", context)).isEqualTo("resolved");
    }

    @Test
    void resolveExpression_undefinedVariable_staysLiteral() {
        assertThat(executor.resolveExpression("{{ undefined }}", context)).isEqualTo("{{ undefined }}");
    }

    @Test
    void resolveExpression_nullExpression_returnsEmpty() {
        assertThat(executor.resolveExpression(null, context)).isEmpty();
    }

    @Test
    void execute_piProvider_passesProviderIdToRequest() {
        context.setProvider("PI");
        context.setVariable("action", "run");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "done"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ action }}");
        config.put("cases", List.of(
                Map.of("value", "run", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do work"))))));

        executor.execute(switchOf(config), context);

        verify(commands).aiStep(argThat(req -> "PI".equals(req.providerId())), any());
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequest() {
        context.setVariable("action", "run");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "done"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ action }}");
        config.put("cases", List.of(
                Map.of("value", "run", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do work"))))));

        executor.execute(switchOf(config), context);

        verify(commands).aiStep(argThat(req -> req.providerId() == null), any());
    }

    @Test
    void should_executeFirstCase_when_multipleCasesMatch() {
        context.setVariable("flag", "a");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "first branch ran"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ flag }}");
        config.put("cases", List.of(
                Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "first command")))),
                Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "second command"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("first branch ran");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "a");
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void should_executeLastCase_when_onlyLastMatches() {
        context.setVariable("phase", "third");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "third branch ran"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ phase }}");
        config.put("cases", List.of(
                Map.of("value", "first", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run first")))),
                Map.of("value", "second", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run second")))),
                Map.of("value", "third", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run third"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("third branch ran");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "third");
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void should_returnSuccess_when_noDefaultAndNoMatch() {
        context.setVariable("state", "irrelevant");

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ state }}");
        config.put("cases", List.of(
                Map.of("value", "alpha", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "alpha cmd")))),
                Map.of("value", "beta", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "beta cmd"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    @Test
    void should_notThrowNpe_when_expressionResolvesToNull() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", null);
        config.put("cases", List.of(
                Map.of("value", "some-value", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void should_notThrowNpe_when_casesIsNull() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "some-value");
        config.put("cases", null);

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void should_notThrowNpe_when_caseValueIsNull() {
        context.setVariable("state", "actual");

        Map<String, Object> nullValueCase = new HashMap<>();
        nullValueCase.put("value", null);
        nullValueCase.put("blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "should not run"))));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ state }}");
        config.put("cases", List.of(nullValueCase));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void should_isolateContextBetweenBranches_when_differentCasesMatchInSequentialRuns() {
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "branch-A-output"))
                .thenReturn(step(2, StepStatus.COMPLETED, "branch-B-output"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ branch }}");
        config.put("cases", List.of(
                Map.of("value", "A", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do A")))),
                Map.of("value", "B", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do B"))))));
        BlockDefinition block = switchOf(config);

        context.setVariable("branch", "A");
        StepResult firstResult = executor.execute(block, context);

        context.setVariable("branch", "B");
        StepResult secondResult = executor.execute(block, context);

        assertThat(firstResult.getMetadata()).containsEntry("matchedCase", "A");
        assertThat(firstResult.getOutput()).isEqualTo("branch-A-output");
        assertThat(firstResult.getOutput()).doesNotContain("branch-B-output");

        assertThat(secondResult.getMetadata()).containsEntry("matchedCase", "B");
        assertThat(secondResult.getOutput()).isEqualTo("branch-B-output");
        assertThat(secondResult.getOutput()).doesNotContain("branch-A-output");
    }

    @Test
    void execute_nestedBlockProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("PI");
        context.setModel("gpt-5");
        context.setVariable("action", "run");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "done"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ action }}");
        config.put("cases", List.of(
                Map.of("value", "run", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of(
                                "command", "do work", "provider", "PI", "model", "anthropic/opus"))))));

        executor.execute(switchOf(config), context);

        verify(commands).aiStep(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())), any());
    }

    @Test
    void execute_nestedShellBlock_untrustedVariable_isSingleQuoted() {
        context.setUntrustedVariable("payload", "x; id");
        context.setVariable("status", "success");
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(Map.of("value", "success", "blocks", List.of(
                Map.of("type", "shell", "config", Map.of("command", "echo {{ payload }}"))))));

        executor.execute(switchOf(config), context);

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), anyInt(), any());
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo(FakeCommandRunner.value(1, "x; id") + "echo \"${TRACEWORKS_VALUE_1}\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_carriesAiRequestFromFirstNestedBlock() {
        context.setVariable("status", "success");
        StepResult blockResult = step(1, StepStatus.COMPLETED, "deployed");
        blockResult.setMetadata(Map.of(
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                "provider", "CLAUDE", "requestedModel", "opus"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(Map.of("value", "success", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "deploy app"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getMetadata())
                .containsEntry("matchedCase", "success")
                .containsEntry("expression", "success")
                .containsEntry("provider", "CLAUDE");
        assertThat((Map<String, Object>) result.getMetadata().get("aiRequest")).containsEntry("model", "opus");
    }

    @Test
    void execute_noMatch_keepsExpressionAndMatchedCaseNoneInMutableMap() {
        context.setVariable("status", "unknown");
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ status }}");
        config.put("cases", List.of(Map.of("value", "success", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "deploy app"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getMetadata()).containsEntry("matchedCase", "none").containsEntry("expression", "unknown");
        result.getMetadata().put("x", 1);
    }

    @Test
    void execute_nestedCompressBlock_isRejectedWithoutAnyCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "compress", "config", Map.of("source", "x", "stages", List.of()))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getOutput()).contains("Block type 'compress' is not supported in switch");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_nestedDocumentBlock_isRejectedNamingTheBlockWithoutAnyCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "document", "name", "report", "config", Map.of("command", "write it"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("document").contains("report");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_nestedBlockWithoutType_isRejectedInsteadOfRunningAsAiCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("name", "typeless", "config", Map.of("command", "Analyse the repository"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("typeless").contains("(no type)");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_nestedBlockWithBlankType_isRejectedInsteadOfRunningAsAiCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "  ", "config", Map.of("command", "Analyse the repository"))))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
    }

    /**
     * The case block's name is not passed on to the AI request: an AI step request has no name. What reaches the
     * model is the case block's own command.
     */
    @Test
    void execute_nestedNamedSimpleBlock_runsItsOwnCommandAsThePrompt() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "reviewed"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "review", "config", Map.of("command", "Check this"))))));

        executor.execute(switchOf(config), context);

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), any());
        assertThat(captor.getValue().prompt()).isEqualTo("Check this");
    }

    @Test
    void deserializeBlock_keepsTheNestedBlockName() {
        BlockDefinition nested = executor.deserializeBlock(
                Map.of("type", "simple", "name", "review", "config", Map.of("command", "check")));

        assertThat(nested.getType()).isEqualTo("simple");
        assertThat(nested.getName()).isEqualTo("review");
        assertThat(nested.getConfig()).containsEntry("command", "check");
    }

    /** A name on a case block also makes its output addressable downstream. */
    @Test
    void execute_namedCaseBlock_outputIsAvailableUnderItsName() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "reviewed"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "review", "config", Map.of("command", "Check this"))))));

        executor.execute(switchOf(config), context);

        assertThat(context.getBlockOutput("review")).isEqualTo("reviewed");
        assertThat(context.resolveTemplate("{{ block.review.output }}")).isEqualTo("reviewed");
    }

    /**
     * A name that resolves as {{ block.&lt;name&gt;.output }} also works as the {@code source} of a compression
     * block, with the case step's own facts in the header.
     */
    @Test
    void execute_namedCaseBlock_isRegisteredAsCompressionSource() {
        StepResult caseResult = StepResult.builder().stepIndex(1).status(StepStatus.FAILED)
                .output("broken").durationMs(2_000L).metadata(Map.of("blockType", "shell", "exitCode", 1)).build();
        when(commands.shellStep(any(), anyInt(), anyInt(), any())).thenReturn(caseResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "shell", "name", "scan", "config", Map.of("command", "check"))))));

        executor.execute(switchOf(config), context);

        SourceStep source = context.getSourceSteps().find("scan").orElseThrow();
        assertThat(source.blockName()).isEqualTo("scan");
        assertThat(source.status()).isEqualTo(StepStatus.FAILED);
        assertThat(source.exitCode()).isEqualTo(1);
        assertThat(source.blockType()).isEqualTo("shell");
    }

    /** End to end: a switch case output, then compress with the case name as source. */
    @Test
    void execute_namedCaseBlock_canBeCompressedByAFollowingCompressBlock() {
        StepResult caseResult = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                .output(("Line A\n").repeat(5) + "Line B\n").durationMs(1_000L).build();
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(caseResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "finding", "config", Map.of("command", "analysis"))))));
        executor.execute(switchOf(config), context);

        StepResult compressed = new CompressBlockExecutor().execute(
                BlockDefinition.builder().type("compress").name("finding_compact")
                        .config(Map.of("source", "finding",
                                "stages", List.of(Map.of("op", "lines.dedupe", "count", true))))
                        .build(),
                context);

        assertThat(compressed.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(compressed.getOutput()).contains("## finding").contains("5× Line A").contains("Line B");
    }

    @Test
    void execute_namedBlocksInTheDefaultBranch_areRecordedTooAndKeepTheirOwnOutput() {
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "collected"), step(2, StepStatus.COMPLETED, "summarised"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "nothing-matches");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "config", Map.of("command", "skip"))))));
        config.put("default", List.of(
                Map.of("type", "simple", "name", "collect", "config", Map.of("command", "collect")),
                Map.of("type", "simple", "name", "summarise", "config", Map.of("command", "summarise"))));

        executor.execute(switchOf(config), context);

        assertThat(context.getBlockOutput("collect")).isEqualTo("collected");
        assertThat(context.getBlockOutput("summarise")).isEqualTo("summarised");
    }

    @Test
    void execute_unnamedCaseBlock_recordsNoNamedOutput() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "done"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "config", Map.of("command", "work"))))));

        executor.execute(switchOf(config), context);

        assertThat(context.getBlockOutputs()).isEmpty();
    }

    /**
     * Case blocks run in sequence and each one moves {@code previous_output}, like the iterations of a while loop: a
     * second block in the same case sees the first one's output, not the step before the switch.
     */
    @Test
    void execute_caseWithTwoBlocks_theSecondSeesTheFirstsOutputAsPreviousOutput() {
        context.setPreviousOutput("before the switch");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "first"), step(2, StepStatus.COMPLETED, "second"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "Start: {{ previous_output }}")),
                Map.of("type", "simple", "config", Map.of("command", "Then: {{ previous_output }}"))))));

        executor.execute(switchOf(config), context);

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands, times(2)).aiStep(captor.capture(), any());
        assertThat(captor.getAllValues().getFirst().prompt()).isEqualTo("Start: before the switch");
        assertThat(captor.getAllValues().get(1).prompt()).isEqualTo("Then: first");
    }

    /**
     * Block names are meant to be unique per workflow. When they are not, the last writer wins: a case block
     * overwrites the output an earlier top-level block stored under the same name. The other direction (the
     * switch's own aggregate overwriting its child) is pinned in {@code BlockStepRunnerTest}.
     */
    @Test
    void execute_caseBlockNameCollidesWithAnEarlierBlock_theCaseBlockWins() {
        context.recordOutput("report", "old");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "new"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks", List.of(
                Map.of("type", "simple", "name", "report", "config", Map.of("command", "write"))))));

        executor.execute(switchOf(config), context);

        assertThat(context.getBlockOutput("report")).isEqualTo("new");
    }

    /** Taint is sticky: a case output in an untrusted run is shell-escaped downstream like any other. */
    @Test
    void execute_namedCaseBlockInUntrustedRun_outputIsRecordedAsTainted() {
        context.setUntrustedVariable("payload", "harmless");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "a; id"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "scan", "config", Map.of("command", "scan"))))));

        executor.execute(switchOf(config), context);

        assertThat(context.resolveShellTemplate("echo {{ block.scan.output }}", false))
                .isEqualTo(FakeCommandRunner.value(1, "a; id") + "echo \"${TRACEWORKS_VALUE_1}\"");
    }

    @Test
    void execute_nestedNamedShellBlock_passesTheNameToTheStepBlock() {
        when(commands.shellStep(any(), anyInt(), anyInt(), any())).thenReturn(step(1, StepStatus.COMPLETED, "done"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "shell", "name", "cleanup", "config", Map.of("command", "rm -rf tmp/"))))));

        executor.execute(switchOf(config), context);

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), anyInt(), any());
        assertThat(captor.getValue().getName()).isEqualTo("cleanup");
    }

    /** The nested AI form counts too: platform data in a nested {@code simple} prompt taints the case output. */
    @Test
    void execute_platformDataInANestedPrompt_taintsTheRecordedCaseOutput() {
        context.setUntrustedPlatformVariable("previous_report", "last report");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(step(1, StepStatus.COMPLETED, "a | b"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks", List.of(Map.of(
                "type", "simple", "name", "analysis",
                "config", Map.of("command", "Assess: {{ previous_report }}"))))));

        executor.execute(switchOf(config), context);

        assertThat(context.resolveShellTemplate("echo {{ block.analysis.output }}", false))
                .isEqualTo(FakeCommandRunner.value(1, "a | b") + "echo \"${TRACEWORKS_VALUE_1}\"");
    }

    /**
     * An AI block typically ends its answer with a newline. The switch compares the trimmed expression (the case
     * values are trimmed already), so "GREEN\n" hits the case GREEN instead of silently falling into default, while
     * input and metadata keep the raw value.
     */
    @Test
    void execute_aiOutputWithTrailingNewline_hitsTheCaseAndKeepsTheRawValueVisible() {
        context.setBlockOutput("light", "GREEN\n");
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any()))
                .thenReturn(step(1, StepStatus.COMPLETED, "good"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ block.light.output }}");
        config.put("cases", List.of(Map.of("value", "GREEN", "blocks",
                List.of(Map.of("type", "shell", "config", Map.of("command", "echo good"))))));
        config.put("default", List.of(Map.of("type", "shell", "config", Map.of("command", "echo unknown"))));

        StepResult result = executor.execute(switchOf(config), context);

        assertThat(result.getMetadata()).containsEntry("matchedCase", "GREEN").containsEntry("expression", "GREEN\n");
        assertThat(result.getInput()).isEqualTo("switch (GREEN\n)");
        ArgumentCaptor<BlockDefinition> ran = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(ran.capture(), anyInt(), anyInt(), any());
        assertThat(ran.getValue().getConfig()).containsEntry("command", "echo good");
    }

    @Test
    void matchesValue_ignoresWhitespaceAroundTheExpressionButNotInsideIt() {
        assertThat(executor.matchesValue("GREEN\n", "GREEN")).isTrue();
        assertThat(executor.matchesValue("  critical \r\n", "error|critical")).isTrue();
        assertThat(executor.matchesValue("GREEN\nTwo findings", "GREEN")).isFalse();
        assertThat(executor.matchesValue(null, "GREEN")).isFalse();
    }
}
