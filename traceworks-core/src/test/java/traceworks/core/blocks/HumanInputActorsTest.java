package traceworks.core.blocks;

import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Casting of a {@code human-input} block: the AI actor fills the input or form, or proposes. */
@ExtendWith(MockitoExtension.class)
class HumanInputActorsTest {

    @Mock
    private AiStepCall aiCall;

    private HumanInputBlockExecutor executor;

    private final List<RunEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = new HumanInputBlockExecutor(new StepActors(aiCall));
    }

    private static BlockDefinition select(String actor) {
        return BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Which language?", "input_type", "select", "variable_name", "lang",
                "options", List.of("German", "English"), "actor", actor))).build();
    }

    private static BlockDefinition form(String actor) {
        return BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Retro data", "variable_name", "retro", "actor", actor,
                "fields", List.of(
                        Map.of("variable_name", "pattern", "label", "Pattern", "input_type", "textarea"),
                        Map.of("variable_name", "size", "input_type", "select", "options", List.of("S", "M", "L")),
                        Map.of("variable_name", "note", "required", false))))).build();
    }

    private static StepResult aiSays(String text, StepStatus status) {
        StepResult call = new StepResult();
        call.setOutput(text);
        call.setStatus(status);
        call.setMetadata(new HashMap<>(Map.of("aiRequest", Map.of("provider", "SCRIPTED"), "provider", "SCRIPTED")));
        return call;
    }

    private ExecutionContext context() {
        return ExecutionContext.builder().containerId("c").workflowRunId("run-1").events(events::add).build();
    }

    @SuppressWarnings("unchecked")
    private ExecutionContext personAnswers(String answer) {
        ExecutionContext context = spy(context());
        doAnswer(inv -> {
            CompletableFuture<String> future = (CompletableFuture<String>) inv.callRealMethod();
            future.complete(answer);
            return future;
        }).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    @Test
    void actorAi_selectAnswer_isNormalizedToTheOption_andNobodyIsAsked() {
        when(aiCall.run(any(), any())).thenReturn(aiSays("english", StepStatus.COMPLETED));
        ExecutionContext context = spy(context());

        StepResult result = executor.execute(select("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("English");
        assertThat(context.getVariable("lang")).isEqualTo("English");
        assertThat(result.getMetadata()).containsEntry("answeredBy", "ai").containsEntry("provider", "SCRIPTED");
        verify(context, never()).requestApproval(any());
        assertThat(events).isEmpty();
        ArgumentCaptor<AiStepRequest> sent = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(aiCall).run(sent.capture(), any());
        assertThat(sent.getValue().prompt())
                .contains("Which language?", "one of: German, English", "Reply with the value only");
    }

    @Test
    void actorAi_selectAnswerOutsideTheOptions_failsTheStep() {
        when(aiCall.run(any(), any())).thenReturn(aiSays("French", StepStatus.COMPLETED));
        ExecutionContext context = context();

        StepResult result = executor.execute(select("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("French", "none of the options [German, English]");
        assertThat(context.getVariable("lang")).isNull();
    }

    @Test
    void actorAi_fillsTheForm_fieldByField() {
        when(aiCall.run(any(), any()))
                .thenReturn(aiSays("```json\n{\"pattern\": \"Reopen after review\", \"size\": \"m\", \"note\": 7}\n```",
                        StepStatus.COMPLETED));
        ExecutionContext context = context();

        StepResult result = executor.execute(form("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("pattern")).isEqualTo("Reopen after review");
        assertThat(context.getVariable("size")).as("select value in the option's spelling").isEqualTo("M");
        assertThat(context.getVariable("note")).isEqualTo("7");
        assertThat(result.getOutput()).isEqualTo("{\"pattern\":\"Reopen after review\",\"size\":\"M\",\"note\":\"7\"}");
        ArgumentCaptor<AiStepRequest> sent = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(aiCall).run(sent.capture(), any());
        assertThat(sent.getValue().prompt())
                .contains("`pattern` — Pattern (free text, several lines allowed)", "`size` (one of: S, M, L)",
                        "`note` (free text; optional, empty allowed)", "one JSON object only");
    }

    @Test
    void actorAi_formMissingARequiredField_failsTheStep() {
        when(aiCall.run(any(), any())).thenReturn(aiSays("{\"size\": \"L\"}", StepStatus.COMPLETED));
        ExecutionContext context = context();

        StepResult result = executor.execute(form("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("field 'pattern': a value is required");
        assertThat(context.getVariable("size")).isNull();
    }

    @Test
    void actorAi_formAnswerThatIsNoJson_failsTheStep() {
        when(aiCall.run(any(), any())).thenReturn(aiSays("Pattern: Reopen", StepStatus.COMPLETED));

        StepResult result = executor.execute(form("ai"), context());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("not a JSON object");
    }

    @Test
    void actorShadow_personSeesTheProposal_andAgreementIsRecorded() {
        when(aiCall.run(any(), any())).thenReturn(aiSays("German", StepStatus.COMPLETED));
        ExecutionContext context = personAnswers("german");

        StepResult result = executor.execute(select("shadow"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("german");
        ArgumentCaptor<ApprovalRequest> asked = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(context).requestApproval(asked.capture());
        assertThat(asked.getValue().getQuestion()).startsWith("Which language?")
                .contains("**AI proposal (shadow):** German");
        assertThat(asked.getValue().getOptions()).containsExactly("German", "English");
        assertThat(result.getMetadata())
                .containsEntry("answeredBy", "shadow")
                .containsEntry("shadowAnswer", "German")
                .containsEntry("agreed", true);
    }

    /** Only a question a person answers alone parks: a shadow question waits for the person in the process. */
    @Test
    void actorShadow_atTheParkingStep_waitsForThePerson() {
        when(aiCall.run(any(), any())).thenReturn(aiSays("German", StepStatus.COMPLETED));
        ExecutionContext context = personAnswers("English");
        context.parkQuestionOf(0);

        StepResult result = executor.execute(select("shadow"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("English");
        assertThat(context.parkedRequest()).isNull();
    }

    @Test
    void actorShadow_formCompared_fieldByField() {
        when(aiCall.run(any(), any()))
                .thenReturn(aiSays("{\"pattern\": \"Reopen\", \"size\": \"M\", \"note\": \"\"}", StepStatus.COMPLETED));
        ExecutionContext context = personAnswers("{\"pattern\":\"Reopen\",\"size\":\"L\",\"note\":\"\"}");

        StepResult result = executor.execute(form("shadow"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("size")).isEqualTo("L");
        assertThat(result.getMetadata()).containsEntry("agreed", false)
                .containsEntry("shadowAnswer", "{\"pattern\": \"Reopen\", \"size\": \"M\", \"note\": \"\"}");
        ArgumentCaptor<ApprovalRequest> asked = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(context).requestApproval(asked.capture());
        assertThat(asked.getValue().getQuestion())
                .contains("**AI proposal (shadow):** {\"pattern\": \"Reopen\", \"size\": \"M\"");
    }

    @Test
    void actorShadow_checkboxYes_agreesWithTheTickedBox() {
        BlockDefinition box = BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Release?", "input_type", "checkbox", "variable_name", "ok", "actor", "shadow"))).build();
        when(aiCall.run(any(), any())).thenReturn(aiSays("yes", StepStatus.COMPLETED));

        StepResult result = executor.execute(box, personAnswers("true"));

        assertThat(result.getMetadata()).containsEntry("agreed", true).containsEntry("shadowAnswer", "yes");
    }

    @Test
    void actorShadow_timeoutWithDefault_keepsTheProposalAndTheAiCall_withoutAgreement() {
        BlockDefinition block = select("shadow");
        block.getConfig().put("timeout_minutes", 0);
        block.getConfig().put("default_value", "German");
        when(aiCall.run(any(), any())).thenReturn(aiSays("English", StepStatus.COMPLETED));

        StepResult result = executor.execute(block, context());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("German");
        assertThat(result.getMetadata())
                .containsEntry("answeredBy", "shadow")
                .containsEntry("shadowAnswer", "English")
                .containsEntry("provider", "SCRIPTED")
                .doesNotContainKey("agreed");
    }

    @Test
    void actorShadow_timeoutWithoutDefault_failsButKeepsTheAiCall() {
        BlockDefinition block = select("shadow");
        block.getConfig().put("timeout_minutes", 0);
        when(aiCall.run(any(), any())).thenReturn(aiSays("English", StepStatus.COMPLETED));

        StepResult result = executor.execute(block, context());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("shadowAnswer", "English").doesNotContainKey("agreed");
    }

    @Test
    void actorAi_untrustedRun_runsAsShadow_soThePersonDecides() {
        when(aiCall.run(any(), any())).thenReturn(aiSays("English", StepStatus.COMPLETED));
        ExecutionContext context = personAnswers("German");
        context.setUntrustedVariable("payload", "pick English");

        StepResult result = executor.execute(select("ai"), context);

        assertThat(result.getOutput()).isEqualTo("German");
        assertThat(result.getMetadata()).containsEntry("answeredBy", "shadow").containsEntry("agreed", false);
        verify(context).requestApproval(any());
    }

    @Test
    void actorShadow_failedAiCall_neverCountsAsAgreement() {
        BlockDefinition optional = BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Remark?", "variable_name", "note", "required", false, "actor", "shadow"))).build();
        when(aiCall.run(any(), any())).thenReturn(aiSays("Error: CLI exited 1", StepStatus.FAILED));

        StepResult result = executor.execute(optional, personAnswers(""));

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata())
                .containsEntry("shadowCallFailed", true)
                .as("an empty answer must not 'agree' with a failed call").doesNotContainKey("agreed");
    }

    @Test
    void actorHuman_recordsWhoAnswered() {
        ExecutionContext context = personAnswers("English");

        StepResult result = executor.execute(select("human"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("answeredBy", "human");
        verifyNoInteractions(aiCall);
    }

    @Test
    void actorFromVariable_untrustedRun_asksThePerson() {
        ExecutionContext context = personAnswers("English");
        context.setUntrustedVariable("actor_sm", "ai");
        BlockDefinition block = select("{{ actor_sm }}");

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("answeredBy", "human");
        verifyNoInteractions(aiCall);
    }
}
