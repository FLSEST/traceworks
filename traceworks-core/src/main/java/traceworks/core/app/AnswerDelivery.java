package traceworks.core.app;

import traceworks.core.engine.RunControl;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

/**
 * Hands answers to the question a running run waits for, one at a time, so that each {@code approval.answered}
 * carries the attribution of the answer it records. A delivery gives the answer to the run with its attribution,
 * which the run applies when the answer resolves its question ({@link RunControl#answer(RunControl.Question, String,
 * Runnable)}), and waits until the run recorded it (or ended); only then may the next delivery give its answer. An
 * answer that comes as its question times out, or takes its default, is refused, and the answer the step records by
 * itself carries nobody's attribution. The answer a
 * resume replays holds the turn until its step took it, so no other answer can take its attribution.
 */
final class AnswerDelivery {

    /** Who gives an answer and how it arrived. */
    record Attribution(String actor, String source, String requestId, String comment, String agent) {

        /** An answer no agent claims. */
        Attribution(String actor, String source, String requestId, String comment) {
            this(actor, source, requestId, comment, null);
        }
    }

    private final RunRecorder recorder;
    private final RunControl control;
    private final Semaphore turn = new Semaphore(1);
    private final Object lock = new Object();
    /** The delivered answer that is not recorded yet; {@code null} when none. Guarded by {@link #lock}. */
    private CountDownLatch recorded;
    /** The step whose answer the resume replays while it holds the turn; {@code null} when none. Guarded. */
    private Integer replayStep;
    /** Whether the run has ended. Guarded by {@link #lock}. */
    private boolean ended;

    AnswerDelivery(RunRecorder recorder, RunControl control) {
        this.recorder = recorder;
        this.control = control;
    }

    /**
     * Before a resumed run starts: the top-level step {@code step} answers its question from the record, and that
     * {@code approval.answered} is attributed to {@code actor} with the source {@code resume}, and to the agent the
     * recorded answer names ({@code null} for none): who resumes is not who answered.
     */
    void replay(int step, String actor, String agent) {
        turn.acquireUninterruptibly();   // nobody delivers before the run has started
        synchronized (lock) {
            replayStep = step;
        }
        recorder.attributeNextAnswer(actor, "resume", null, null, agent);
    }

    /** Whether the line is the question the resumed step asks again to take the replayed answer. */
    boolean isReplayed(RecordedEvent event) {
        synchronized (lock) {
            return replayStep != null && event.parent() == null && Objects.equals(event.step(), replayStep);
        }
    }

    /** Sees every line the recorder appends, on the appending thread. */
    void appended(RecordedEvent event) {
        boolean end = event.parent() == null && (RecordTypes.RUN_FINISHED.equals(event.type())
                || RecordTypes.RUN_PARKED.equals(event.type()));
        boolean answered = RecordTypes.APPROVAL_ANSWERED.equals(event.type());
        synchronized (lock) {
            if ((answered || end) && recorded != null) {
                recorded.countDown();
            }
            if (replayStep != null && (end || event.parent() == null && Objects.equals(event.step(), replayStep)
                    && (answered || RecordTypes.STEP_FINISHED.equals(event.type())))) {
                endReplay(answered);
            }
            if (end) {
                ended = true;
            }
        }
    }

    /** The run has ended: nothing waits for a record any more, and no answer is delivered. */
    void end() {
        boolean replaying;
        synchronized (lock) {
            ended = true;
            if (recorded != null) {
                recorded.countDown();
            }
            replaying = replayStep != null;
            replayStep = null;
        }
        // Outside the lock: an appending thread holds the recorder and then takes the lock, never the other way round
        if (replaying) {
            recorder.clearAnswerAttribution();
            turn.release();
        }
    }

    /**
     * Delivers one answer and returns once the run recorded it.
     *
     * @param check  runs in this delivery's turn before the answer is given; throws to refuse it
     * @param answer gives the answer to the run through its control, with the attribution to apply when the
     *               answer resolves its question
     * @throws IllegalStateException    if the run has ended, or refuses the answer as {@link RunControl} does: no
     *                                  question of that kind is open
     * @throws IllegalArgumentException if the run refuses the answer as {@link RunControl} does: not an offered
     *                                  option, or an unknown field
     * @throws InterruptedException     if interrupted while waiting for its turn; nothing was delivered then
     */
    void deliver(Attribution who, Runnable check, BiConsumer<RunControl, Runnable> answer)
            throws InterruptedException {
        turn.acquire();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            synchronized (lock) {
                if (ended) {
                    throw new IllegalStateException("the run has ended");
                }
                recorded = latch;
            }
            check.run();
            // The attribution is set by the run, at the moment the answer resolves its question: an answer the
            // question's own end (a timeout, its default) wins against leaves no attribution behind
            answer.accept(control,
                    () -> recorder.attributeNextAnswer(who.actor(), who.source(), who.requestId(), who.comment(),
                            who.agent()));
            // The answer is with the run: the turn passes on only once its approval.answered is written (or the
            // run ended), even if this thread is interrupted meanwhile
            awaitUninterruptibly(latch);
        } finally {
            synchronized (lock) {
                recorded = null;
            }
            turn.release();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Called under {@link #lock} by the appending thread, which holds the recorder already: the replayed answer is
     * recorded, or its step or the run ended without one.
     */
    private void endReplay(boolean answered) {
        if (!answered) {
            recorder.clearAnswerAttribution();
        }
        replayStep = null;
        turn.release();
    }
}
