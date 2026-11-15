package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Casting of a decision step: who answers an {@code approval} or {@code human-input} block.
 *
 * <p>The config key {@value #ACTOR_KEY} names the actor: {@code human} (default, the block behaves as it always did),
 * {@code ai} (one AI call in the role answers the step) or {@code shadow} (the AI answers first, the person is still
 * asked and sees the proposal; both answers go into the step metadata). The key may be a placeholder
 * ({@code {{ actor_po }}}), so a run variable casts the roles; in a run with untrusted input a placeholder is never
 * resolved, exactly like {@code mode}/{@code auto_answer}: otherwise a foreign payload could cast itself as the
 * approver. {@value #ACTOR_PROMPT_KEY} carries the role instructions. A literal {@code ai} in such a run becomes
 * {@code shadow}: the question or form is resolved with that input, and an AI deciding alone could be talked into the
 * answer by the payload; the person decides instead.
 *
 * <p>{@code ai} and {@code shadow} are always available: Traceworks has no modules that could switch them off.
 *
 * <p>The AI call goes through the run's AI providers like a {@code simple} block ({@link AiStepCall}): provider, model
 * and effort from the block, else from the workflow, and the usual {@code aiRequest} step metadata. The call gets no
 * tools: a decision needs none, and a role that could edit files would be an agent, not an actor.
 */
class StepActors {

    static final String ACTOR_KEY = "actor";
    static final String ACTOR_PROMPT_KEY = "actor_prompt";
    /** Step metadata: who answered, {@code human}, {@code ai} or {@code shadow}. */
    static final String ANSWERED_BY = "answeredBy";
    /** Step metadata of a shadow step: the AI's proposal. */
    static final String SHADOW_ANSWER = "shadowAnswer";
    /**
     * Step metadata of a shadow step: whether the person's answer equals the AI's proposal. Absent when no person
     * answered (timeout, cancel, default value): that is neither agreement nor disagreement.
     */
    static final String AGREED = "agreed";
    /**
     * Step metadata of a shadow step whose AI call failed: there was no proposal to agree with, so {@value #AGREED}
     * is left out.
     */
    static final String SHADOW_CALL_FAILED = "shadowCallFailed";

    private static final String DEFAULT_ROLE = "You answer this step in place of the person who would normally "
            + "decide it. Decide on the basis given below; do not ask questions back.";

    enum Actor {
        HUMAN, AI, SHADOW;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The outcome of one AI call in the role: the answer text (stripped) and the call's step metadata. */
    record AiAnswer(String text, boolean failed, Map<String, Object> metadata) {}

    private final AiStepCall aiCall;

    StepActors() {
        this(new AiStepCall());
    }

    StepActors(AiStepCall aiCall) {
        this.aiCall = aiCall;
    }

    /**
     * The actor of a block: {@code human} unless the config says {@code ai} or {@code shadow}. A placeholder is
     * resolved only in a run without untrusted input and only to a known value; anything else is {@code human}
     * ({@link ApprovalBlockExecutor#resolveSwitch}). In a run with untrusted input {@code ai} becomes {@code shadow},
     * so a person decides a question that the input helped to word.
     */
    static Actor resolve(BlockDefinition block, ExecutionContext context) {
        String raw = ApprovalBlockExecutor.resolveSwitch(BlockSettings.of(block).text(ACTOR_KEY), context);
        if (raw == null) return Actor.HUMAN;
        Actor actor = switch (raw.toLowerCase(Locale.ROOT)) {
            case "ai" -> Actor.AI;
            case "shadow" -> Actor.SHADOW;
            default -> Actor.HUMAN;
        };
        if (actor == Actor.AI && context.hasUntrustedInput()) {
            return Actor.SHADOW;
        }
        return actor;
    }

    /**
     * Runs the actor's part of a step: nothing for a person; one AI call for {@code shadow}, whose text becomes the
     * proposal ("" after a failed call); one AI call for {@code ai}, whose text {@code check} turns into the answer a
     * person would give, or throws {@link IllegalArgumentException} with the reason, which fails the step like a failed
     * call does.
     */
    ActorDecision decide(Actor actor, BlockDefinition block, ExecutionContext context, int stepIndex, String task,
                         UnaryOperator<String> check) {
        if (actor == Actor.HUMAN) {
            return ActorDecision.person();
        }
        AiAnswer call = answer(block, context, stepIndex, task);
        if (actor == Actor.SHADOW) {
            // The proposal is recorded as given, valid or not: the person decides either way
            return new ActorDecision(actor, null, null, call, call.failed() ? "" : call.text());
        }
        if (call.failed()) {
            return new ActorDecision(actor, null, failedCallOutput(call), call, null);
        }
        try {
            return new ActorDecision(actor, check.apply(call.text()), null, call, null);
        } catch (IllegalArgumentException e) {
            return new ActorDecision(actor, null, "AI actor answered '" + call.text() + "': " + e.getMessage(), call,
                    null);
        }
    }

    /**
     * One AI call in the role. {@code task} is the resolved question or form plus the answer format; the role
     * instructions ({@value #ACTOR_PROMPT_KEY}, resolved) go in front of it.
     */
    AiAnswer answer(BlockDefinition block, ExecutionContext context, int stepIndex, String task) {
        BlockSettings settings = BlockSettings.of(block);
        String ownRole = settings.text(ACTOR_PROMPT_KEY);
        Integer timeout = settings.number("timeout_seconds");
        String role = ownRole != null && !ownRole.isBlank() ? context.resolveTemplate(ownRole) : DEFAULT_ROLE;
        String prompt = role.strip() + "\n\n" + task.strip();
        StepResult call = aiCall.run(AiStepRequest.builder()
                .workingDirectory(context.getProjectPath())
                .prompt(prompt)
                .stepIndex(stepIndex)
                .systemPrompt(context.getInstructionPrefix())
                .model(context.modelFor(block))
                .timeoutSeconds(timeout)
                .providerId(context.providerFor(block))
                .effort(context.effortFor(block))
                .tools(ToolPolicy.NONE)
                .build(), context);
        String text = call.getOutput() == null ? "" : call.getOutput().strip();
        boolean failed = call.getStatus() != StepStatus.COMPLETED;
        return new AiAnswer(text, failed, call.getMetadata() == null ? Map.of() : call.getMetadata());
    }

    /** Step metadata of an AI-answered step: {@code answeredBy} plus the call's AI keys. */
    static Map<String, Object> aiMetadata(Actor actor, AiAnswer answer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ANSWERED_BY, actor.key());
        if (answer != null) {
            StepResult carrier = new StepResult();
            StepAiMetadata.carryAi(carrier, answer.metadata());
            if (carrier.getMetadata() != null) metadata.putAll(carrier.getMetadata());
        }
        return metadata;
    }

    /**
     * Step metadata of a shadow step: the AI keys, the proposal and whether the person agreed, unless the AI call
     * failed: without a proposal there is nothing to agree with, so {@value #AGREED} is left out.
     */
    static Map<String, Object> shadowMetadata(AiAnswer proposal, String proposalText, boolean agreed) {
        Map<String, Object> metadata = unansweredShadowMetadata(proposal, proposalText);
        if (proposal == null || !proposal.failed()) {
            metadata.put(AGREED, agreed);
        }
        return metadata;
    }

    /**
     * Step metadata of a shadow step no person answered (timeout, cancel, default value): the AI call was made and
     * paid for, so its keys and the proposal stay; {@value #AGREED} is left out.
     */
    static Map<String, Object> unansweredShadowMetadata(AiAnswer proposal, String proposalText) {
        Map<String, Object> metadata = aiMetadata(Actor.SHADOW, proposal);
        metadata.put(SHADOW_ANSWER, proposalText);
        if (proposal != null && proposal.failed()) {
            metadata.put(SHADOW_CALL_FAILED, true);
        }
        return metadata;
    }

    /** The proposal as the person sees it, appended to the question or prompt (Markdown). */
    static String withProposal(String question, String proposal) {
        String shown = proposal == null || proposal.isBlank() ? "(no answer)" : proposal.strip();
        return question + "\n\n---\n**AI proposal (shadow):** "
                + (shown.contains("\n") ? "\n\n```\n" + shown + "\n```" : shown);
    }

    /** A step output naming the failed AI call: the step fails, nobody is asked instead. */
    static String failedCallOutput(AiAnswer answer) {
        return "AI actor call failed: " + (answer.text().isBlank() ? "(no output)" : answer.text());
    }
}
