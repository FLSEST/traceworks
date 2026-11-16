package traceworks.core.blocks;

import traceworks.core.blocks.StepActors.Actor;
import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.PresetAnswerException;
import traceworks.core.engine.ApprovalRejection;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ApprovalRequested;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code approval}: a person (or an AI actor, see {@link StepActors}) answers a question with one of its
 * {@code options} (default {@code Yes}, {@code Yes always}, {@code No}). A rejection ({@code reject_options}, by default
 * {@code No} and the German "no" that existing workflows offer) follows {@code on_no}: {@code continue} (default),
 * {@code skip} or {@code fail}; anything else is approved. The answer goes into {@code variable_name} (default
 * {@code approval_answer}). {@code mode: auto} with {@code auto_answer} answers without asking, and a person's
 * {@code Yes always} answers the same question again in this run.
 *
 * <p>While the question is open, the run's events carry an {@link ApprovalRequested}; a person's answer is recorded as
 * an {@link ApprovalAnswered}. The step waits up to {@code timeout_minutes} (default 30) and fails after it. A failed
 * write of those events is never turned into a step result: the {@link EventLogException} propagates and the engine
 * stops the run.
 */
public class ApprovalBlockExecutor implements BlockExecutor {

    static final String TYPE = "approval";

    private final StepActors actors;

    public ApprovalBlockExecutor() {
        this(new StepActors());
    }

    ApprovalBlockExecutor(StepActors actors) {
        this.actors = actors;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of(TYPE);
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = context.getClock().now();
        BlockSettings settings = BlockSettings.of(block);
        String rawQuestion = settings.text("question", "Approval required");
        List<String> options = settings.texts("options", List.of("Yes", "Yes always", "No"));
        List<String> rejectAnswers = ApprovalRejection.rejectAnswers(settings.texts("reject_options"));
        String rawMode = settings.text("mode");
        String rawAutoAnswer = settings.text("auto_answer");
        String onNo = settings.text("on_no", "continue");
        String variableName = settings.text("variable_name", "approval_answer");
        int timeoutMinutes = settings.number("timeout_minutes", 30);

        // Resolved, so that whoever approves sees the basis instead of {{ block.X.output }}
        String question = context.resolveTemplate(rawQuestion);
        List<String> rejectOptions = ApprovalRejection.rejectingOptions(options, rejectAnswers);
        String mode = "auto".equals(resolveSwitch(rawMode, context)) ? "auto" : "ask";
        String autoAnswer = resolveSwitch(rawAutoAnswer, context);

        // Who answers: a person as always, an AI call in the role, or both (shadow)
        Actor actor = StepActors.resolve(block, context);

        String preset = presetAnswer(mode, autoAnswer, question, context);
        Outcome outcome = preset != null
                ? new Outcome(preset, null, null, false)
                : obtain(block, context, stepIndex, actor, ApprovalRequest.builder()
                        .blockId("approval-" + stepIndex)
                        .question(question)
                        .options(options)
                        .rejectOptions(rejectOptions)
                        .stepIndex(stepIndex)
                        .timeoutMinutes(timeoutMinutes)
                        .build());
        if (outcome.failure() != null) {
            return buildResult(stepIndex, start, question, outcome.failure(), StepStatus.FAILED, outcome.metadata(),
                    context);
        }
        String answer = outcome.answer();

        // "Yes always" is remembered for later steps of this run. Only a person's answer counts: an AI actor's
        // "Yes always" would otherwise answer a later approval cast as human without asking anyone.
        if ("Yes always".equalsIgnoreCase(answer) && !outcome.byAi()) {
            context.rememberApproval(question, answer);
        }
        // A person's answer, a remembered one, an AI actor's (one of the options, as the workflow spells it) and an
        // auto_answer the workflow writes out are trusted. An auto_answer filled in from a placeholder is whatever
        // that value was, a step's output for one: protected when the placeholder is, whatever the state of the run.
        boolean filledIn = preset != null && "auto".equals(mode) && autoAnswer != null
                && rawAutoAnswer != null && rawAutoAnswer.contains("{{");
        if (filledIn) {
            context.setTemplateVariable(variableName, answer, rawAutoAnswer);
        } else {
            context.setVariable(variableName, answer);
        }
        return finish(stepIndex, start, question, answer, rejectAnswers, onNo, outcome.metadata(), context);
    }

    /**
     * The answer of a step nobody preset, or why there is none.
     *
     * @param failure the step output when the step fails before an answer (AI actor, timeout, cancel)
     * @param byAi    the AI actor answered: its "Yes always" is not remembered
     */
    private record Outcome(String answer, String failure, Map<String, Object> metadata, boolean byAi) {}

    /** The AI actor answers, or a person does (in shadow mode with the AI's proposal below the question). */
    private Outcome obtain(BlockDefinition block, ExecutionContext context, int stepIndex, Actor actor,
                           ApprovalRequest request) {
        String question = request.getQuestion();
        List<String> options = request.getOptions();
        ActorDecision decision = actors.decide(actor, block, context, stepIndex, approvalTask(question, options),
                text -> offeredOption(request, text));
        if (decision.failed()) {
            return new Outcome(null, decision.failure(), decision.aiMetadata(), false);
        }
        if (decision.answeredByAi()) {
            return new Outcome(decision.answer(), null, decision.aiMetadata(), true);
        }
        return askPerson(request.toBuilder().question(decision.shown(question))
                .shadow(decision.actor() == Actor.SHADOW).build(), context, decision);
    }

    /**
     * Asks the person and waits; timeout and cancel fail the step but keep a shadow step's AI call. A preset answer
     * given to another question, or not offered, fails the step with the reason alone ({@link PresetAnswerException}).
     */
    private Outcome askPerson(ApprovalRequest request, ExecutionContext context, ActorDecision decision) {
        CompletableFuture<String> pending = ask(request, context);
        String answer;
        try {
            answer = context.awaitApproval(pending, request.getTimeoutMinutes(), TimeUnit.MINUTES);
        } catch (EventLogException e) {
            throw e;   // the end of the wait could not be recorded: the run stops, the step result does not matter
        } catch (TimeoutException e) {
            return new Outcome(null, "Timed out waiting for approval", decision.unanswered(), false);
        } catch (PresetAnswerException e) {
            return new Outcome(null, e.getMessage(), decision.unanswered(), false);
        } catch (Exception e) {
            return new Outcome(null, "Approval cancelled: " + e.getMessage(), decision.unanswered(), false);
        }
        context.getEvents().emit(new ApprovalAnswered(request.getStepIndex(), TYPE, answer));
        return new Outcome(answer, null, decision.afterPerson(sameOption(answer, decision.proposalText())), false);
    }

    /** The AI actor's answer as one of the offered options (their spelling), or why it is none of them. */
    private static String offeredOption(ApprovalRequest request, String text) {
        try {
            return request.acceptedAnswer(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("none of the options " + request.getOptions(), e);
        }
    }

    /** The step result for an answer: a rejection follows {@code on_no}, anything else is approved. */
    private static StepResult finish(int stepIndex, Instant start, String question, String answer,
                                     List<String> rejectAnswers, String onNo, Map<String, Object> metadata,
                                     ExecutionContext context) {
        if (ApprovalRejection.isRejection(answer, rejectAnswers)) {
            return switch (onNo) {
                case "fail" -> buildResult(stepIndex, start, question,
                        "Approval denied: " + answer, StepStatus.FAILED, metadata, context);
                case "skip" -> buildResult(stepIndex, start, question,
                        "Approval denied, skipping: " + answer, StepStatus.SKIPPED, metadata, context);
                default -> buildResult(stepIndex, start, question,
                        "Approval denied (continuing): " + answer, StepStatus.COMPLETED, metadata, context);
            };
        }
        return buildResult(stepIndex, start, question, "Approved: " + answer, StepStatus.COMPLETED, metadata, context);
    }

    /** The task of the AI actor: the resolved question and the options it may pick from. */
    static String approvalTask(String question, List<String> options) {
        StringBuilder task = new StringBuilder("## Question\n\n").append(question).append("\n\n## Allowed answers\n\n");
        options.forEach(option -> task.append("- ").append(option).append('\n'));
        return task.append("\nReply with exactly one of the allowed answers, spelled as listed, and nothing else.")
                .toString();
    }

    /** Options compare case-insensitively, like {@link ApprovalRequest#acceptedAnswer}. */
    private static boolean sameOption(String answer, String proposal) {
        return answer != null && proposal != null && answer.strip().equalsIgnoreCase(proposal.strip());
    }

    /**
     * The answer without asking anyone: {@code auto_answer} in auto mode, else an answer remembered from "Yes always"
     * in this run; {@code null} when the question has to be asked.
     */
    private static String presetAnswer(String mode, String autoAnswer, String question, ExecutionContext context) {
        if ("auto".equals(mode) && autoAnswer != null) {
            return autoAnswer;
        }
        return context.getRememberedApproval(question);
    }

    /**
     * Opens the question in the run, then announces it: whoever reacts to the {@link ApprovalRequested} finds the
     * question already open. The future carries the answer.
     */
    private static CompletableFuture<String> ask(ApprovalRequest request, ExecutionContext context) {
        CompletableFuture<String> pending = context.requestApproval(request);
        context.getEvents().emit(new ApprovalRequested(request.getStepIndex(), request.getBlockId(), TYPE,
                request.getQuestion(), request.getOptions(), request.getRejectOptions(), null, null, false, null,
                null, null, context.parkedRequest() == request, context.answeredFromPreset(request)));
        return pending;
    }

    private static StepResult buildResult(int stepIndex, Instant start, String input, String output,
                                          StepStatus status, Map<String, Object> metadata, ExecutionContext context) {
        Instant end = context.getClock().now();
        return StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Approval] " + input)
                .output(output)
                .status(status)
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .metadata(metadata == null ? null : new HashMap<>(metadata))
                .build();
    }

    /**
     * {@code mode} and {@code auto_answer} may be set through a variable (such as {@code {{ approval_mode }}} from the
     * default context), so that a workflow switches between interactive and unattended without an edit. Exception: in
     * a run that carries foreign input a placeholder stays unresolved, otherwise a foreign payload could grant the
     * approval itself; then "ask" and no automatic answer apply. {@code actor} is read the same way
     * ({@link StepActors#resolve}).
     */
    static String resolveSwitch(Object raw, ExecutionContext context) {
        if (!(raw instanceof String value)) return null;
        if (value.contains("{{")) {
            if (context.hasUntrustedInput()) return null;
            value = context.resolveTemplate(value);
            if (value.contains("{{")) return null; // the variable is missing: never take a placeholder as the answer
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
