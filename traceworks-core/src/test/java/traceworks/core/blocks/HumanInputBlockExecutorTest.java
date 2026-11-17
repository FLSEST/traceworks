package traceworks.core.blocks;

import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ApprovalRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The {@code human-input} block. These tests read the run's {@link ApprovalRequested} and {@link ApprovalAnswered}
 * events.
 */
@ExtendWith(MockitoExtension.class)
class HumanInputBlockExecutorTest {

    @Mock
    private AiStepCall aiCall;

    private HumanInputBlockExecutor executor;

    private final List<RunEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = new HumanInputBlockExecutor(new StepActors(aiCall));
    }

    @Test
    void supports_humanInput_returnsTrue() {
        assertThat(executor.supports("human-input")).isTrue();
    }

    @Test
    void supports_otherType_returnsFalse() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void execute_textInput_completesWithAnswer() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "What is your name?");
        config.put("input_type", "text");
        config.put("variable_name", "user_name");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("Alice");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("Alice");
        assertThat(result.getInput()).contains("What is your name?");
        assertThat(context.getVariable("user_name")).isEqualTo("Alice");
    }

    @Test
    void execute_selectInput_completesWithSelection() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Choose environment");
        config.put("input_type", "select");
        config.put("variable_name", "env");
        config.put("options", List.of("dev", "staging", "prod"));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("staging");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("staging");
        assertThat(context.getVariable("env")).isEqualTo("staging");
    }

    @Test
    void execute_timeout_withDefaultValue_usesDefault() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");
        config.put("default_value", "fallback");
        config.put("timeout_minutes", 0); // 0 minutes = immediate timeout
        config.put("use_default_on_timeout", true);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("fallback");
        assertThat(context.getVariable("val")).isEqualTo("fallback");
    }

    @Test
    void execute_timeout_withoutDefault_fails() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");
        config.put("default_value", "");
        config.put("timeout_minutes", 0);
        config.put("use_default_on_timeout", true);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Timed out");
    }

    @Test
    void execute_timeout_useDefaultDisabled_fails() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");
        config.put("default_value", "fallback");
        config.put("timeout_minutes", 0);
        config.put("use_default_on_timeout", false);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_emitsTheRequestAndTheAnswer() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter name");
        config.put("variable_name", "name");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("Bob");

        executor.execute(block, context);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOfSatisfying(ApprovalRequested.class, requested -> {
            assertThat(requested.type()).isEqualTo("human-input");
            assertThat(requested.question()).isEqualTo("Enter name");
        });
        assertThat(events.get(1)).isInstanceOfSatisfying(ApprovalAnswered.class,
                answered -> assertThat(answered.answer()).isEqualTo("Bob"));
    }

    @Test
    void execute_emitsTheRequestAndTheAnswer_withEveryField() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Which file?");
        config.put("input_type", "select");
        config.put("variable_name", "file");
        config.put("options", List.of("a.md", "b.md"));
        config.put("default_value", "a.md");
        config.put("required", false);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("b.md");

        var result = executor.execute(block, context);

        int stepIndex = result.getStepIndex();
        assertThat(events).containsExactly(
                new ApprovalRequested(stepIndex, "human-input-" + stepIndex, "human-input", "Which file?",
                        List.of("a.md", "b.md"), List.of(), "select", "a.md", false, null, "file", null),
                new ApprovalAnswered(stepIndex, "human-input", "b.md"));
    }

    @Test
    void execute_timeoutWithDefault_emitsTheDefaultAsTheAnswer() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Name?");
        config.put("default_value", "anon");
        config.put("timeout_minutes", 0);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();

        var result = executor.execute(block, context);

        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isEqualTo(new ApprovalAnswered(result.getStepIndex(), "human-input", "anon"));
    }

    @Test
    void execute_defaultConfig_usesDefaults() {
        // Empty config: all defaults apply
        var config = new HashMap<String, Object>();

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("answer");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getInput()).contains("Please provide input:");
        assertThat(context.getVariable("user_input")).isEqualTo("answer");
    }

    @Test
    void execute_cancelledFuture_fails() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithCancelledApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput())
                .isEqualTo("Human input cancelled: java.lang.RuntimeException: cancelled by user");
    }

    /** A preset answer given to another question than the step asks now: the reason alone is the output. */
    @Test
    void execute_presetForAChangedQuestion_failsWithTheReason() {
        var block = BlockDefinition.builder().type("human-input")
                .config(new HashMap<>(Map.of("prompt", "Enter value", "variable_name", "val"))).build();
        var context = buildContext();
        context.presetAnswer(0, "human-input", "Who deploys?", "Operator");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo(
                "the question changed while the run was parked: the answer was given to \"Who deploys?\"");
        assertThat(context.getVariable("val")).isNull();
    }

    /** A question answered while the run was parked is announced as preset, so nobody is asked for it again. */
    @Test
    void execute_withAPresetAnswer_announcesTheRequestAsPreset_andTakesTheAnswer() {
        var block = BlockDefinition.builder().type("human-input")
                .config(new HashMap<>(Map.of("prompt", "Enter value", "variable_name", "val"))).build();
        var context = buildContext();
        context.presetAnswer(0, "human-input", "Enter value", "Operator");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("val")).isEqualTo("Operator");
        assertThat(events.getFirst()).isInstanceOfSatisfying(ApprovalRequested.class,
                requested -> assertThat(requested.preset()).isTrue());
    }

    /** What a person typed into a form is the person's: each field stays trusted, also in a run with untrusted input. */
    @Test
    void execute_aPersonsFormAnswer_inARunWithUntrustedInput_staysTrusted() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Fill in");
        config.put("variable_name", "form");
        config.put("fields", List.of(Map.of("variable_name", "file", "label", "File")));
        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("{\"file\": \"typed.md\"}");
        context.setUntrustedVariable("payload", "from outside");

        executor.execute(block, context);

        assertThat(context.getVariable("file")).isEqualTo("typed.md");
        assertThat(context.isUntrusted("file")).isFalse();
    }

    // --- Multi-field tests ---

    @Test
    void execute_multiField_storesEachFieldAsContextVariable() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Several values, please:");
        config.put("fields", List.of(
                Map.of("variable_name", "first", "input_type", "text"),
                Map.of("variable_name", "second", "input_type", "text")
        ));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        String json = "{\"first\":\"A\",\"second\":\"B\"}";
        var context = buildContextWithPrecompletedApproval(json);

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo(json);
        assertThat(context.getVariable("first")).isEqualTo("A");
        assertThat(context.getVariable("second")).isEqualTo("B");
    }

    @Test
    void execute_multiField_missingValueDefaultsToEmpty() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Please:");
        config.put("fields", List.of(
                Map.of("variable_name", "name"),
                Map.of("variable_name", "age")
        ));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("{\"name\":\"Operator\"}");

        executor.execute(block, context);

        assertThat(context.getVariable("name")).isEqualTo("Operator");
        assertThat(context.getVariable("age")).isEqualTo("");
    }

    @Test
    void execute_multiField_invalidJson_fallsBackToSingleVariable() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Please:");
        config.put("variable_name", "fallback");
        config.put("fields", List.of(Map.of("variable_name", "x")));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("not-json");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("fallback")).isEqualTo("not-json");
    }

    @Test
    void execute_multiField_requestIncludesFields() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Please:");
        config.put("fields", List.of(Map.of("variable_name", "x", "input_type", "text")));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("{\"x\":\"y\"}");

        executor.execute(block, context);

        assertThat(events.get(0)).isInstanceOfSatisfying(ApprovalRequested.class, requested ->
                assertThat(requested.fields()).containsExactly(Map.of("variable_name", "x", "input_type", "text")));
    }

    @Test
    void execute_singleField_legacyBehavior_unchanged() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Name?");
        config.put("variable_name", "name");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("Operator");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("name")).isEqualTo("Operator");
        assertThat(result.getOutput()).isEqualTo("Operator");
    }

    @Test
    void execute_promptAndDefaultPlaceholders_areResolved() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Finding: {{ block.scan.output }}. Which file?");
        config.put("default_value", "{{ block.scan.output }}.md");
        config.put("timeout_minutes", 15);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("ok");
        context.recordOutput("scan", "report");

        executor.execute(block, context);

        var captor = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(context).requestApproval(captor.capture());
        assertThat(captor.getValue().getQuestion()).isEqualTo("Finding: report. Which file?");
        assertThat(captor.getValue().getDefaultValue()).isEqualTo("report.md");
        assertThat(captor.getValue().getTimeoutMinutes()).isEqualTo(15);
    }

    /** The run record is the event log: a failed write is never turned into a step result. */
    @Test
    void execute_eventLogFailure_propagates_insteadOfFailingTheStep() {
        var block = BlockDefinition.builder().type("human-input")
                .config(new HashMap<>(Map.of("prompt", "Name?"))).build();
        ExecutionContext broken = ExecutionContext.builder().workflowRunId("run-1").events(event -> {
            throw new EventLogException(new IOException("disk full"));
        }).build();

        assertThatThrownBy(() -> executor.execute(block, broken))
                .isInstanceOf(EventLogException.class)
                .hasMessageContaining("disk full");
    }

    @Test
    void execute_eventLogFailureWhileTheWaitEnds_propagates() {
        var block = BlockDefinition.builder().type("human-input")
                .config(new HashMap<>(Map.of("prompt", "Name?"))).build();
        ExecutionContext waiting = ExecutionContext.builder().workflowRunId("run-1").build();
        waiting.onApprovalWait(isWaiting -> {
            if (isWaiting) {
                waiting.resolveApproval(waiting.getCurrentApprovalRequest(), "Operator");
            } else {
                throw new EventLogException(new IOException("disk full"));
            }
        });

        assertThatThrownBy(() -> executor.execute(block, waiting))
                .as("the end of the wait could not be recorded: never 'Human input cancelled'")
                .isInstanceOf(EventLogException.class);
    }

    // --- Helpers ---

    /**
     * After a timeout nobody answered: the value is the block's default, which may name a step's output. In a run
     * with untrusted input it is as little trusted as that output, so a later shell step quotes it.
     */
    @Test
    void execute_timeoutWithDefault_inARunWithUntrustedInput_leavesTheVariableUntrusted() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Which file?");
        config.put("variable_name", "val");
        config.put("default_value", "{{ block.scan.output }}");
        config.put("timeout_minutes", 0);
        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();
        context.setUntrustedVariable("payload", "from outside");
        context.recordOutput("scan", "$(touch gone)");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("val")).isEqualTo("$(touch gone)");
        assertThat(context.isUntrusted("val")).isTrue();
    }

    /** The same for a form: each field's variable takes its value from the default. */
    @Test
    void execute_timeoutWithDefault_ofAForm_inARunWithUntrustedInput_leavesTheFieldsUntrusted() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Fill in");
        config.put("variable_name", "form");
        config.put("fields", List.of(Map.of("variable_name", "file", "label", "File")));
        config.put("timeout_minutes", 0);
        var block = BlockDefinition.builder().type("human-input").config(config).build();
        for (String defaultValue : List.of("{\"file\": \"{{ block.scan.output }}\"}", "{{ block.scan.output }}")) {
            config.put("default_value", defaultValue);
            var context = buildContextWithNeverCompletingApproval();
            context.setUntrustedVariable("payload", "from outside");
            context.recordOutput("scan", "a b");

            executor.execute(block, context);

            // A default that is a JSON object fills the fields; any other text is kept whole under variable_name
            String filled = defaultValue.startsWith("{\"") ? "file" : "form";
            assertThat(context.getVariable(filled)).isNotNull();
            assertThat(context.isUntrusted(filled)).as(filled).isTrue();
        }
    }

    /** What a person typed is the person's: it stays trusted, also in a run with untrusted input. */
    @Test
    void execute_aPersonsAnswer_inARunWithUntrustedInput_staysTrusted() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Which file?");
        config.put("variable_name", "val");
        config.put("default_value", "{{ block.scan.output }}");
        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("typed.md");
        context.setUntrustedVariable("payload", "from outside");
        context.recordOutput("scan", "$(touch gone)");

        executor.execute(block, context);

        assertThat(context.getVariable("val")).isEqualTo("typed.md");
        assertThat(context.isUntrusted("val")).isFalse();
    }

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .events(events::add)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ExecutionContext buildContextWithPrecompletedApproval(String answer) {
        ExecutionContext context = spy(buildContext());
        doAnswer(inv -> {
            CompletableFuture<String> future = (CompletableFuture<String>) inv.callRealMethod();
            future.complete(answer);
            return future;
        }).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    private ExecutionContext buildContextWithNeverCompletingApproval() {
        ExecutionContext context = spy(buildContext());
        doReturn(new CompletableFuture<String>()).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    private ExecutionContext buildContextWithCancelledApproval() {
        ExecutionContext context = spy(buildContext());
        doAnswer(inv -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("cancelled by user"));
            return future;
        }).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }
}
