package traceworks.core.app;

import traceworks.core.context.ApprovalRequest;
import traceworks.core.model.RunStatus;
import traceworks.core.port.Clock;
import traceworks.core.port.Ids;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.StoredRun;
import traceworks.core.record.QuestionView;
import traceworks.core.record.Redactor;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunView;
import traceworks.core.register.DecisionAgent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The answer use case. A parked run has no writer: the answer is checked against the parked question and recorded
 * at once, as the holder of the writer lock, with the source {@code cli}. A running run belongs to the process that
 * writes it: the answer goes into its inbox, and the answer waits until the record shows what became of it.
 */
final class Answering {

    static final Duration POLL = Duration.ofMillis(100);
    static final Duration WAIT = Duration.ofSeconds(10);

    private final Clock clock;
    private final Ids ids;
    private final String actor;
    private final Duration wait;
    private final boolean hostConsent;
    private final BiFunction<StoredRun, RunView, Redactor> redactors;

    /**
     * @param wait        how long an answer to a running run waits for the run's process to record it
     * @param hostConsent whether the operator config allows host runs; without it, the command that resumes a host
     *                    run needs {@code --allow-host-execution}
     * @param redactors   the redactor a resume of the parked run would record with (its declared secrets and its AI
     *                    providers' credentials), from the run and its view; throws {@link RunRefusedException} with
     *                    the reason the resume would give if the run's package cannot be loaded
     */
    Answering(Clock clock, Ids ids, String actor, Duration wait, boolean hostConsent,
              BiFunction<StoredRun, RunView, Redactor> redactors) {
        this.clock = clock;
        this.ids = ids;
        this.actor = actor;
        this.wait = wait;
        this.hostConsent = hostConsent;
        this.redactors = redactors;
    }

    /** The command that resumes the run, with {@code --allow-host-execution} where it needs the flag. */
    private String resumeCommand(String id, RunView view) {
        return "traceworks resume " + id
                + (RunTarget.resumeNeedsHostFlag(view.execution(), hostConsent) ? " --allow-host-execution" : "");
    }

    /**
     * @throws IllegalArgumentException if the request names an agent with a blank or bad name
     *                                  ({@link DecisionAgent}); nothing is recorded or sent then
     */
    AnswerResult answer(StoredRun run, AnswerRequest given) {
        // Before anything is asked of the run: an answer with a name that is none must not be recorded as a person's
        AnswerRequest request = given.agent() == null ? given : new AnswerRequest(given.kind(), given.step(),
                given.answer(), given.values(), given.comment(),
                DecisionAgent.checked(given.agent(), "The agent of an answer"));
        if (!"approval".equals(request.kind()) && !"human-input".equals(request.kind())) {
            return refused(false, "An answer is for an approval or a human input, not for '" + request.kind() + "'");
        }
        if (run.tryLockWriter()) {
            return answerStopped(run, request);
        }
        RunView view = RunRecords.view(run.runId(), RunRecords.events(run), true);
        if (view.status() == RunStatus.PARKED) {
            return refused(true, "Another process is writing run " + run.runId() + "; try again when it has ended");
        }
        if (RunRecords.isFinal(view.status())) {
            return refused(false, "Run " + run.runId() + " has finished (" + view.status() + "); it takes no answer");
        }
        return answerRunning(run, request);
    }

    /**
     * No process runs the run, and this one holds its writer lock.
     *
     * @throws RecordDamagedException if the parked question's blob has other content than the record names
     */
    private AnswerResult answerStopped(StoredRun run, AnswerRequest request) {
        List<RecordedEvent> events = RunRecords.events(run);
        RunView view = RunRecords.view(run.runId(), events, false);
        String id = run.runId();
        if (view.status() != RunStatus.PARKED) {
            return refused(false, RunRecords.isFinal(view.status())
                    ? "Run " + id + " has finished (" + view.status() + "); it takes no answer"
                    : "Run " + id + " is " + view.status() + " and waits for no answer; resume it first");
        }
        if (view.paused()) {
            return refused(true, "Run " + id + " is paused and waits for no answer; go on with "
                    + resumeCommand(id, view));
        }
        QuestionView question = view.pendingQuestion();
        if (question == null) {
            return refused(true, "The parked question of run " + id + " is missing from its record");
        }
        if (view.parkedAnswer() != null) {
            return refused(true, "Step " + question.step() + " of run " + id
                    + " is already answered; resume the run with " + resumeCommand(id, view));
        }
        if (!question.kind().equals(request.kind())) {
            return refused(true, "Run " + id + " waits for " + label(question.kind()) + " at step "
                    + question.step() + ", not for " + label(request.kind()));
        }
        if (request.step() != null && request.step() != question.step()) {
            return refused(true, "Run " + id + " waits at step " + question.step() + ", not at step "
                    + request.step());
        }
        if (question.question() != null) {
            // The answer is recorded for the question the record names; a changed question blob refuses it
            RunRecords.checkedContent(run, question.question(), "parked question");
        }
        String encoded;
        try {
            encoded = encode(question, request);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return refused(true, e.getMessage());
        }
        // Redacted as the resume would: what a person types into an answer or its comment may be a secret's value
        Redactor redactor;
        try {
            redactor = redactors.apply(run, view);
        } catch (RunRefusedException e) {
            return refused(true, "Run " + id + " cannot take an answer, its resume would be refused: "
                    + e.getMessage());
        }
        RunRecorder recorder = RunRecords.continueRecord(run, events, RunRecords.RECORDING_ONLY, clock)
                .redacting(redactor);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("step", question.step());
        fields.put("kind", question.kind());
        fields.put("answer", encoded.getBytes(StandardCharsets.UTF_8));
        fields.put("actor", actor);
        if (request.agent() != null) {
            fields.put("agent", request.agent());   // only when an agent said so, as the run's own answers
        }
        fields.put("source", "cli");
        fields.put("request", ids.newRequestId());
        fields.put("comment", request.comment() == null ? null : request.comment().getBytes(StandardCharsets.UTF_8));
        recorder.record(RecordTypes.APPROVAL_ANSWERED, fields);
        List<byte[]> lines = run.events().readAll();
        RunOutputs.writeView(run, RunRecords.view(id, RunRecords.parse(id, lines), false), lines);
        return new AnswerResult(true, true, "The answer to step " + question.step() + " of run " + id
                + " is recorded" + DecisionAgent.through(request.agent()) + "; continue the run with "
                + resumeCommand(id, view));
    }

    /**
     * The answer as the step receives it, checked as a running step checks it: through an {@link ApprovalRequest}
     * with the recorded question's options and fields.
     */
    private static String encode(QuestionView question, AnswerRequest request) {
        ApprovalRequest asked = ApprovalRequest.builder()
                .type(question.kind())
                .stepIndex(question.step())
                .options(question.options())
                .rejectOptions(question.rejectOptions())
                .inputType(question.inputType())
                .variableName(question.variable())
                .required(question.required())
                .validationPattern(question.pattern())
                .fields(question.fields())
                .build();
        if ("human-input".equals(question.kind())) {
            return asked.inputAnswer(values(question.variable(), request));
        }
        if (request.answer() == null) {
            throw new IllegalArgumentException("An approval takes one of its options " + question.options());
        }
        return asked.acceptedAnswer(request.answer());
    }

    /**
     * Another process runs the run: the answer goes into its inbox, bound to the question the record shows open now,
     * and the record tells what became of it. The run's process delivers it only while that question is still the
     * one it waits for, so an answer never reaches a question that opened after it was sent.
     *
     * @throws RequestQueuedException if the run's process has not recorded the request within the wait
     */
    private AnswerResult answerRunning(StoredRun run, AnswerRequest request) {
        List<RecordedEvent> seen = RunRecords.events(run);
        String id = run.runId();
        Asked asked = openQuestion(seen);
        if (asked == null) {
            return refused(false, "Run " + id + " waits for no answer now");
        }
        if (!asked.kind().equals(request.kind())) {
            return refused(false, "Run " + id + " waits for " + label(asked.kind()) + " at step " + asked.step()
                    + ", not for " + label(request.kind()));
        }
        if (request.step() != null && request.step() != asked.step()) {
            return refused(false, "Run " + id + " waits at step " + asked.step() + ", not at step " + request.step());
        }
        String requestId = ids.newRequestId();
        int before = seen.size();
        run.inbox().post(new InboxRequest(requestId, request.kind(), asked.run(), asked.step(), asked.seq(),
                request.answer(), values(asked.variable(), request), request.comment(), actor, clock.now(),
                request.agent()));
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            List<RecordedEvent> events = RunRecords.events(run);
            for (RecordedEvent event : events) {
                if (!requestId.equals(event.fields().path("request").asText(null))) {
                    continue;
                }
                if (RecordTypes.APPROVAL_ANSWERED.equals(event.type())) {
                    // The agent as the run's process recorded it. A process of an earlier version takes the
                    // answer and knows no agent: the sender learns that its name did not arrive
                    String recorded = event.fields().path("agent").asText(null);
                    return new AnswerResult(true, false, "The answer reached run " + run.runId()
                            + DecisionAgent.through(recorded) + (request.agent() == null || recorded != null ? ""
                            : "; the process that runs it recorded no agent (an earlier version of Traceworks):"
                            + " the answer stands as " + actor + "'s alone"));
                }
                if (RecordTypes.REQUEST_REFUSED.equals(event.type())) {
                    return refused(false, event.fields().path("reason").asText());
                }
            }
            for (RecordedEvent event : events.subList(Math.min(before, events.size()), events.size())) {
                boolean parked = RecordTypes.RUN_PARKED.equals(event.type());
                if (event.parent() == null && (parked || RecordTypes.RUN_FINISHED.equals(event.type()))) {
                    return refused(parked, "Run " + run.runId() + (parked ? " parked" : " ended")
                            + " before the answer reached it" + (parked ? "; answer the parked run again" : ""));
                }
            }
            if (!run.writerAlive()) {
                return refused(false, "The process running " + run.runId() + " ended before the answer reached it");
            }
        }
        throw new RequestQueuedException("The answer is requested, not yet recorded: request " + requestId
                + " waits in the inbox of run " + run.runId() + ", whose process has not recorded it within "
                + wait.toSeconds() + " s; see what became of it with traceworks show " + run.runId());
    }

    /** A question the record shows open: where it waits and the seq of its {@code approval.requested}. */
    private record Asked(String run, int step, int seq, String kind, String variable) {}

    /**
     * The newest question the record shows open: asked, and neither answered nor ended (its wait over, its step or
     * its run finished). A park or a resume of the run closes every question; a resumed step asks again.
     */
    private static Asked openQuestion(List<RecordedEvent> events) {
        Map<String, Asked> open = new LinkedHashMap<>();
        for (RecordedEvent event : events) {
            String key = event.run() + "#" + event.step();
            switch (event.type()) {
                case RecordTypes.APPROVAL_REQUESTED -> {
                    if (event.step() != null) {
                        open.put(key, new Asked(event.run(), event.step(), event.seq(),
                                event.fields().path("kind").asText(),
                                event.fields().path("variable").asText(null)));
                    }
                }
                case RecordTypes.STEP_WAITING -> {
                    if (!event.fields().path("waiting").asBoolean()) {
                        open.remove(key);
                    }
                }
                case RecordTypes.APPROVAL_ANSWERED, RecordTypes.STEP_FINISHED -> open.remove(key);
                case RecordTypes.RUN_PARKED, RecordTypes.RUN_RESUMED, RecordTypes.RUN_FINISHED -> {
                    if (event.parent() == null) {
                        open.clear();
                    } else {
                        open.values().removeIf(asked -> asked.run().equals(event.run()));
                    }
                }
                default -> { }
            }
        }
        return open.values().stream().max(Comparator.comparingInt(Asked::seq)).orElse(null);
    }

    /**
     * The values of a human input's answer by variable. A plain {@code answer} text is the value of the question's
     * variable, so a caller with a single input need not know its name.
     */
    private static Map<String, String> values(String variable, AnswerRequest request) {
        if (request.values().isEmpty() && request.answer() != null && variable != null) {
            return Map.of(variable, request.answer());
        }
        return request.values();
    }

    private static AnswerResult refused(boolean parked, String message) {
        return new AnswerResult(false, parked, message);
    }

    private static String label(String kind) {
        return "human-input".equals(kind) ? "a human input" : "an approval";
    }
}
