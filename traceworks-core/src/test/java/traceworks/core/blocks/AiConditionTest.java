package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.StepFailureSummary;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The AI evaluation of a conditional's or a while's condition, and what a failed one means. */
@ExtendWith(MockitoExtension.class)
class AiConditionTest {

    @Mock
    private WrapperCommands commands;

    private final ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
    private final AiStepRequest request = AiStepRequest.builder().workingDirectory("/p").prompt("true?").build();

    @Test
    void onError_readsTheTextAndTheJsonBoolean_andFailsForEverythingElse() {
        assertThat(AiCondition.OnError.of(null)).isEqualTo(AiCondition.OnError.FAIL);
        assertThat(AiCondition.OnError.of(" False ")).isEqualTo(AiCondition.OnError.FALSE);
        assertThat(AiCondition.OnError.of(false)).isEqualTo(AiCondition.OnError.FALSE);
        assertThat(AiCondition.OnError.of(true)).isEqualTo(AiCondition.OnError.FAIL);
        assertThat(AiCondition.OnError.of("SKIP")).isEqualTo(AiCondition.OnError.SKIP);
        assertThat(AiCondition.OnError.of("fail")).isEqualTo(AiCondition.OnError.FAIL);
        assertThat(AiCondition.OnError.of(3)).isEqualTo(AiCondition.OnError.FAIL);
        assertThat(AiCondition.effective("skip", true)).as("a stopping run never takes a branch")
                .isEqualTo(AiCondition.OnError.FAIL);
        assertThat(AiCondition.effective("skip", false)).isEqualTo(AiCondition.OnError.SKIP);
    }

    @Test
    void ask_answered_carriesTheAiMetadataOfTheCall() {
        when(commands.ask(any(), any())).thenReturn(new AiStepCall.Answer("TRUE", 0, Map.of(
                "aiRequest", Map.of("provider", "CLAUDE"), "provider", "CLAUDE")));
        StepResult target = new StepResult();

        AiCondition condition = AiCondition.ask(commands, request, target, context);

        assertThat(condition.failed()).isFalse();
        assertThat(condition.answer()).isEqualTo("TRUE");
        assertThat(target.getMetadata()).containsEntry("provider", "CLAUDE").doesNotContainKey(AiCondition.ERROR_KEY);
    }

    @Test
    void ask_nullAnswer_isAnEmptyAnswer() {
        when(commands.ask(any(), any())).thenReturn(new AiStepCall.Answer(null, 0, null));

        assertThat(AiCondition.ask(commands, request, new StepResult(), context).answer()).isEmpty();
    }

    @Test
    void ask_longError_isCutTo300Characters() {
        when(commands.ask(any(), any())).thenReturn(new AiStepCall.Answer("x".repeat(400), 2, null));
        StepResult target = new StepResult();

        AiCondition condition = AiCondition.ask(commands, request, target, context);

        assertThat(condition.error()).hasSize(301).startsWith("exit code 2: xxx").endsWith("…");
        assertThat(target.getMetadata()).containsEntry(AiCondition.ERROR_KEY, condition.error());
    }

    /** An unknown provider fails the step whatever on_condition_error says: it is a definition error, no answer. */
    @Test
    void ask_unknownProvider_isRefused_andAlwaysFails() {
        when(commands.ask(any(), any())).thenThrow(new AiStepCall.ProviderNotAvailable(
                new IllegalArgumentException("unknown AI provider 'NOPE'; configured: CLAUDE")));
        StepResult target = new StepResult();

        AiCondition condition = AiCondition.ask(commands, request, target, context);

        assertThat(condition.failed()).isTrue();
        assertThat(condition.refused()).isTrue();
        assertThat(condition.error()).isEqualTo("unknown AI provider 'NOPE'; configured: CLAUDE");
        assertThat(condition.onError("false", false)).isEqualTo(AiCondition.OnError.FAIL);
        assertThat(condition.onError("skip", false)).isEqualTo(AiCondition.OnError.FAIL);
        assertThat(target.getMetadata()).containsEntry(AiCondition.ERROR_KEY, condition.error());
    }

    @Test
    void onError_ofAnAnsweringFailure_followsTheBlock_unlessTheRunStops() {
        AiCondition failed = new AiCondition(null, "exit code 1: limit", false);

        assertThat(failed.refused()).isFalse();
        assertThat(failed.onError("false", false)).isEqualTo(AiCondition.OnError.FALSE);
        assertThat(failed.onError("skip", false)).isEqualTo(AiCondition.OnError.SKIP);
        assertThat(failed.onError("skip", true)).isEqualTo(AiCondition.OnError.FAIL);
    }

    /** An AI question without a prompt is refused before any call. */
    @Test
    void ask_blankPrompt_isRefusedWithoutACall() {
        AiStepRequest blank = AiStepRequest.builder().workingDirectory("/p").prompt(" \n ").build();

        assertThatThrownBy(() -> AiCondition.ask(commands, blank, new StepResult(), context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage(AiCondition.BLANK_MESSAGE);
        verifyNoInteractions(commands);
    }

    @Test
    void ask_failedCallWithoutCauseMessage_namesOnlyTheCall() {
        when(commands.ask(any(), any())).thenThrow(new WorkflowExecutionException("AI call failed (project: /p)"));

        assertThat(AiCondition.ask(commands, request, new StepResult(), context).error())
                .isEqualTo("AI call failed (project: /p)");
    }

    @Test
    void settle_failPutsTheFailureSummary_skipSkips_falseLeavesTheStepAlone() {
        AiCondition failed = new AiCondition(null, "exit code 1: limit", false);
        StepResult failing = new StepResult();
        StepResult skipping = new StepResult();
        StepResult untouched = new StepResult();

        failed.settle(failing, AiCondition.OnError.FAIL);
        failed.settle(skipping, AiCondition.OnError.SKIP);
        failed.settle(untouched, AiCondition.OnError.FALSE);

        assertThat(failing.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(failing.getMetadata()).containsEntry(StepFailureSummary.KEY,
                "Condition evaluation failed (exit code 1: limit)");
        assertThat(skipping.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(untouched.getStatus()).isEqualTo(StepStatus.PENDING);
    }
}
