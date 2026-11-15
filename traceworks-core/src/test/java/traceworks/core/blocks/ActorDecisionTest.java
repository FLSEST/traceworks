package traceworks.core.blocks;

import traceworks.core.blocks.StepActors.Actor;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The shared actor step of approval and human-input. */
class ActorDecisionTest {

    private final AiStepCall aiCall = mock(AiStepCall.class);
    private final StepActors actors = new StepActors(aiCall);
    private final ExecutionContext context = ExecutionContext.builder().containerId("c").workflowRunId("run-1").build();
    private final BlockDefinition block = BlockDefinition.builder().type("approval").config(new HashMap<>()).build();

    private void aiSays(String text, StepStatus status) {
        StepResult call = new StepResult();
        call.setOutput(text);
        call.setStatus(status);
        call.setMetadata(new HashMap<>(Map.of("aiRequest", Map.of("provider", "CLAUDE"), "provider", "CLAUDE")));
        when(aiCall.run(any(), any())).thenReturn(call);
    }

    @Test
    @DisplayName("a person's step makes no AI call and records who answered")
    void person() {
        ActorDecision decision = actors.decide(Actor.HUMAN, block, context, 0, "task", text -> text);

        assertThat(decision.failed()).isFalse();
        assertThat(decision.answeredByAi()).isFalse();
        assertThat(decision.shown("Deploy?")).isEqualTo("Deploy?");
        assertThat(decision.afterPerson(true)).containsExactly(Map.entry("answeredBy", "human"));
        assertThat(decision.unanswered()).isNull();
        verifyNoInteractions(aiCall);
    }

    @Test
    @DisplayName("the AI actor's text passes the check and becomes the answer")
    void aiAnswers() {
        aiSays(" yes ", StepStatus.COMPLETED);

        ActorDecision decision = actors.decide(Actor.AI, block, context, 0, "task", text -> text.strip().toUpperCase());

        assertThat(decision.answeredByAi()).isTrue();
        assertThat(decision.answer()).isEqualTo("YES");
        assertThat(decision.aiMetadata()).containsEntry("answeredBy", "ai").containsEntry("provider", "CLAUDE");
    }

    @Test
    @DisplayName("an answer the check rejects fails the step with the AI's text and the reason")
    void aiAnswerRejected() {
        aiSays("maybe", StepStatus.COMPLETED);

        ActorDecision decision = actors.decide(Actor.AI, block, context, 0, "task", text -> {
            throw new IllegalArgumentException("none of the options [Yes, No]");
        });

        assertThat(decision.failed()).isTrue();
        assertThat(decision.failure()).isEqualTo("AI actor answered 'maybe': none of the options [Yes, No]");
        assertThat(decision.answeredByAi()).isFalse();
    }

    @Test
    @DisplayName("a failed AI call fails an ai step and is never checked")
    void aiCallFails() {
        aiSays("Error: CLI exited 1", StepStatus.FAILED);

        ActorDecision decision = actors.decide(Actor.AI, block, context, 0, "task", text -> {
            throw new AssertionError("must not be checked");
        });

        assertThat(decision.failure()).isEqualTo("AI actor call failed: Error: CLI exited 1");
    }

    @Test
    @DisplayName("shadow: the proposal goes below the question, agreement only when a person answered")
    void shadow() {
        aiSays("Yes", StepStatus.COMPLETED);

        ActorDecision decision = actors.decide(Actor.SHADOW, block, context, 0, "task", text -> text);

        assertThat(decision.failed()).isFalse();
        assertThat(decision.answeredByAi()).isFalse();
        assertThat(decision.shown("Deploy?")).startsWith("Deploy?").contains("**AI proposal (shadow):** Yes");
        assertThat(decision.afterPerson(false)).containsEntry("answeredBy", "shadow")
                .containsEntry("shadowAnswer", "Yes").containsEntry("agreed", false);
        assertThat(decision.unanswered()).containsEntry("shadowAnswer", "Yes").doesNotContainKey("agreed");
    }

    @Test
    @DisplayName("shadow with a failed call: empty proposal, still asks, never agrees")
    void shadowCallFails() {
        aiSays("Error: boom", StepStatus.FAILED);

        ActorDecision decision = actors.decide(Actor.SHADOW, block, context, 0, "task", text -> text);

        assertThat(decision.failed()).as("the person decides anyway").isFalse();
        assertThat(decision.proposalText()).isEmpty();
        assertThat(decision.afterPerson(true)).containsEntry("shadowCallFailed", true).doesNotContainKey("agreed");
    }
}
