package traceworks.core.blocks;

import traceworks.core.blocks.StepActors.Actor;
import traceworks.core.blocks.StepActors.AiAnswer;

import java.util.Map;

/**
 * What the actor of an {@code approval} or {@code human-input} step settled before a person is asked: an AI actor's
 * checked answer, the reason it failed, or, for a person and in shadow mode, nothing yet. Both executors build their
 * step metadata only here, so a new actor or a changed metadata rule is one change instead of one per executor and
 * branch.
 *
 * @param actor        who answers
 * @param answer       the AI actor's answer, already checked and normalized; null when a person answers
 * @param failure      the step output when the AI actor's call failed or its answer did not pass the check
 * @param proposal     the AI call (ai and shadow); null for a person
 * @param proposalText what the person sees in shadow mode ("" after a failed call); null otherwise
 */
record ActorDecision(Actor actor, String answer, String failure, AiAnswer proposal, String proposalText) {

    static ActorDecision person() {
        return new ActorDecision(Actor.HUMAN, null, null, null, null);
    }

    /** The AI actor's call failed or its answer was unusable: the step fails, nobody is asked instead. */
    boolean failed() {
        return failure != null;
    }

    /** The AI actor answered; no person is asked. */
    boolean answeredByAi() {
        return answer != null;
    }

    /** The question or prompt the person sees: in shadow mode with the AI's proposal below it. */
    String shown(String question) {
        return actor == Actor.SHADOW ? StepActors.withProposal(question, proposalText) : question;
    }

    /** Step metadata of an AI actor that answered or failed. */
    Map<String, Object> aiMetadata() {
        return StepActors.aiMetadata(actor, proposal);
    }

    /** Step metadata once a person answered; {@code agreed} tells whether that equals the proposal. */
    Map<String, Object> afterPerson(boolean agreed) {
        return actor == Actor.SHADOW
                ? StepActors.shadowMetadata(proposal, proposalText, agreed)
                : Map.of(StepActors.ANSWERED_BY, Actor.HUMAN.key());
    }

    /**
     * Step metadata when no person answered (timeout, cancel, default value): a shadow step keeps its paid AI call and
     * the proposal, without "agreed"; a person's step carries nothing.
     */
    Map<String, Object> unanswered() {
        return actor == Actor.SHADOW ? StepActors.unansweredShadowMetadata(proposal, proposalText) : null;
    }
}
