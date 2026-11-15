package traceworks.core.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.app.AnswerDelivery.Attribution;
import traceworks.core.engine.RunControl;
import traceworks.core.engine.RunOutcome;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.StoredRun;
import traceworks.core.record.QuestionView;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One run executing in this process, written by its holder of the writer lock. The engine runs on the calling thread
 * with the recorder as its event sink. Meanwhile an {@link InboxWatcher} delivers the requests of other processes, an
 * asker (if any) asks each question on a thread of its own, and the caller's listener gets every event through an
 * {@link EventFeed}. Answers reach the run one at a time through {@link AnswerDelivery}. Nothing of it outlives
 * {@link #close}, and nothing is appended after the run's end.
 */
final class RunSession implements AutoCloseable {

    private static final ObjectMapper FORM = new ObjectMapper();
    /** How long {@link #close} waits for an asker that was interrupted. */
    private static final long ASKER_JOIN_MILLIS = 5_000;

    private final StoredRun run;
    private final RunRecorder recorder;
    private final String actor;
    private final QuestionAsker asker;
    private final RunControl control = new RunControl();
    private final AnswerDelivery delivery;
    private final EventFeed feed;
    private final InboxWatcher watcher;
    /** The thread asking each question, by the seq of its {@code approval.requested}. */
    private final Map<Integer, Thread> askers = new ConcurrentHashMap<>();
    /** The question the run waits for in the process; {@code null} when none. */
    private volatile OpenQuestion open;
    private volatile boolean closed;

    /** Where a question waits: the run of the record that asks it, its step, and the seq it was asked at. */
    private record OpenQuestion(String run, int step, int seq) {}

    /**
     * @param actor    who runs the run here; answers on the terminal are attributed to them
     * @param listener gets every event appended from now on; {@code null} for none
     * @param asker    asks the questions on a terminal; {@code null} for none
     */
    RunSession(StoredRun run, RunRecorder recorder, String actor, Consumer<RecordedEvent> listener,
               QuestionAsker asker) {
        this.run = run;
        this.recorder = recorder;
        this.actor = actor;
        this.asker = asker;
        this.delivery = new AnswerDelivery(recorder, control);
        this.feed = listener == null ? null : new EventFeed(listener, "traceworks-events-" + run.runId());
        this.watcher = new InboxWatcher(run.inbox(), this::handle,
                (request, e) -> RunRecords.refuse(recorder, request, "the request could not be handled: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())),
                "traceworks-inbox-" + run.runId());
        recorder.onAppend(this::appended);
    }

    RunControl control() {
        return control;
    }

    /**
     * A resumed run: top-level step {@code step} takes the answer {@code answeredBy} gave while the run was parked;
     * the step's {@code approval.answered} is attributed to them with the source {@code resume}, and its question is
     * asked of nobody.
     */
    void replay(int step, String answeredBy) {
        delivery.replay(step, answeredBy);
    }

    /** Runs the engine on this thread and watches the inbox meanwhile. */
    RunOutcome execute(Supplier<RunOutcome> engine) {
        watcher.start();
        return engine.get();
    }

    /** Asks the run to stop, in the name of {@code by}: {@code run.cancel_requested} first, then the cancel. */
    void requestCancel(String by, String requestId) {
        if (recorder.record(RecordTypes.RUN_CANCEL_REQUESTED, RunRecords.cancelRequested(by, requestId))) {
            control.cancel();
        }
    }

    /**
     * The process is going down and cannot wait for the run: the record stops where it is, then the step in flight
     * is ended as a cancel ends it (its process killed, its waits and its open question ended). Nothing of that is
     * recorded, no cancel either, so the run is left {@code INTERRUPTED} and a resume goes on with it.
     */
    void interrupt() {
        recorder.stop();
        control.cancel();
    }

    /**
     * Asks the run to pause, in the name of {@code by}: {@code run.pause_requested} first, then the pause, which
     * parks the run after the step or the loop item in flight. A run that is already asked records nothing more.
     */
    void requestPause(String by, String requestId) {
        if (!control.isPaused()
                && recorder.record(RecordTypes.RUN_PAUSE_REQUESTED, RunRecords.cancelRequested(by, requestId))) {
            control.pause();
        }
    }

    /**
     * Ends the session: no answer is delivered any more, the watcher stops after the request it handles, the askers
     * are interrupted and the listener gets what is queued. An asker that does not return within a few seconds of
     * its interrupt is left behind; it can no longer answer.
     */
    @Override
    public void close() {
        closed = true;
        delivery.end();
        watcher.close();
        askers.values().forEach(Thread::interrupt);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASKER_JOIN_MILLIS);
        for (Thread thread : askers.values()) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left > 0) {
                try {
                    thread.join(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (feed != null) {
            feed.close();
        }
    }

    // ---- what the record says ----

    /** Sees every line the recorder appends, on the appending thread, which holds the recorder: nothing blocks here. */
    private void appended(RecordedEvent event) {
        delivery.appended(event);
        switch (event.type()) {
            case RecordTypes.APPROVAL_REQUESTED -> {
                // The step a resume replays asks its question again only to take the recorded answer
                if (!delivery.isReplayed(event) && event.step() != null) {
                    OpenQuestion question = new OpenQuestion(event.run(), event.step(), event.seq());
                    open = question;
                    // The question the run waits on now is the one this line records: from here on it has its name
                    control.nameWaiting(new RunControl.Question(event.run(), event.step(), event.seq()));
                    if (asker != null && !closed) {
                        ask(QuestionView.of(event), question);
                    }
                }
            }
            case RecordTypes.STEP_WAITING -> {
                if (!event.fields().path("waiting").asBoolean()) {
                    questionClosed(event);
                }
            }
            case RecordTypes.APPROVAL_ANSWERED, RecordTypes.STEP_FINISHED -> questionClosed(event);
            default -> { }
        }
        if (feed != null) {
            feed.offer(event);
        }
    }

    /** The open question's wait ended (answered, timed out, cancelled): its asker no longer needs to ask. */
    private void questionClosed(RecordedEvent event) {
        OpenQuestion question = open;
        if (question != null && question.run().equals(event.run())
                && Objects.equals(question.step(), event.step())) {
            open = null;
            Thread asking = askers.get(question.seq());
            if (asking != null) {
                asking.interrupt();
            }
        }
    }

    // ---- the terminal ----

    private void ask(QuestionView question, OpenQuestion where) {
        Thread thread = Thread.ofPlatform().daemon().name("traceworks-ask-" + run.runId() + "-" + where.seq())
                .unstarted(() -> askOnce(question, where));
        askers.put(where.seq(), thread);
        thread.start();
    }

    /** Asks once; an answer the run refuses, or none, leaves the question to the inbox. */
    private void askOnce(QuestionView question, OpenQuestion where) {
        Optional<String> answer;
        try {
            answer = asker.ask(question);
        } catch (RuntimeException e) {
            return;   // the terminal failed; the inbox can still answer
        }
        if (answer.isEmpty() || closed) {
            return;
        }
        try {
            delivery.deliver(new Attribution(actor, "terminal", null, null), () -> requireOpen(where),
                    (control, attribute) -> give(control, question, answer.get(),
                            new RunControl.Question(where.run(), where.step(), where.seq()), attribute));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Answered meanwhile, or not an answer the question takes: the question stays with the inbox
        } catch (InterruptedException e) {
            // The question closed or the run ended while this answer waited for its turn
        }
    }

    private void requireOpen(OpenQuestion where) {
        if (!where.equals(open)) {
            throw new IllegalStateException("the question is no longer open");
        }
    }

    /** Gives an answer encoded as the step receives it: an option, the text of a single input, a form's JSON. */
    private static void give(RunControl control, QuestionView question, String encoded, RunControl.Question name,
                             Runnable attribute) {
        if (!"human-input".equals(question.kind())) {
            control.answer(name, encoded, attribute);
            return;
        }
        if (question.fields() == null || question.fields().isEmpty()) {
            if (question.variable() == null) {
                throw new IllegalArgumentException("The human input names no variable");
            }
            control.answerInput(name, Map.of(question.variable(), encoded), attribute);
            return;
        }
        try {
            control.answerInput(name, FORM.readValue(encoded,
                    new TypeReference<LinkedHashMap<String, Object>>() { }), attribute);
        } catch (IOException e) {
            throw new IllegalArgumentException("A form takes a JSON object of its fields: " + e.getMessage(), e);
        }
    }

    // ---- the inbox ----

    private void handle(InboxRequest request) {
        String kind = request.kind();
        if ("cancel".equals(kind)) {
            requestCancel(request.actor(), request.id());
            return;
        }
        if (Pausing.KIND.equals(kind)) {
            if (control.isPaused()) {
                RunRecords.refuse(recorder, request, "the run is already asked to pause");
            } else {
                requestPause(request.actor(), request.id());
            }
            return;
        }
        if (!"approval".equals(kind) && !"human-input".equals(kind)) {
            RunRecords.refuse(recorder, request, "unknown request kind '" + kind + "'");
            return;
        }
        try {
            delivery.deliver(new Attribution(request.actor(), "inbox", request.id(), request.comment()),
                    () -> requireQuestion(request), (control, attribute) -> give(control, request, attribute));
        } catch (IllegalStateException | IllegalArgumentException e) {
            RunRecords.refuse(recorder, request, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // the session ends; the request's sender sees the run's end
        }
    }

    /**
     * An answer from the inbox reaches only the question it was sent for: the same run of the record, the same step
     * and the same {@code approval.requested}, still open. This check, in the delivery's turn, refuses a late answer
     * with what the record says; {@link RunControl} then answers the question the request names and no other, so
     * a question that opens between this check and the answer never gets it.
     */
    private void requireQuestion(InboxRequest request) {
        if (request.run() == null || request.step() == null || request.questionSeq() == null) {
            throw new IllegalStateException("the request names no question it answers");
        }
        OpenQuestion question = open;
        if (question == null || !question.run().equals(request.run()) || question.step() != request.step()
                || question.seq() != request.questionSeq()) {
            throw new IllegalStateException("the question it answers is no longer open");
        }
    }

    private static void give(RunControl control, InboxRequest request, Runnable attribute) {
        RunControl.Question name = new RunControl.Question(request.run(), request.step(), request.questionSeq());
        if ("approval".equals(request.kind())) {
            if (request.answer() == null) {
                throw new IllegalArgumentException("An approval takes one of its options; the request has none");
            }
            control.answer(name, request.answer(), attribute);
        } else {
            control.answerInput(name, new LinkedHashMap<>(request.values()), attribute);
        }
    }
}
