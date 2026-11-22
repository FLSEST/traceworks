package traceworks.core.context;

import traceworks.core.model.ContextState;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The waiting state of a run at an approval: the open promise, the question currently
 * being waited on, and the answers the run has remembered.
 *
 * <p>Pure move out of {@link ExecutionContext} - the same mechanics, just in one
 * place instead of spread over three fields and five methods. Following the example of
 * {@link ExecutionContextTaint}: one state, one class.
 *
 * <p>An answer names the question it answers ({@link #resolve(ApprovalRequest, String)}); there
 * is no answer to "whatever is open". A question whose wait timed out is closed, so a late answer is refused instead
 * of going nowhere.
 *
 * <p>A question can also be answered outside the process. The question of the step the engine lets park
 * ({@link #parkQuestionOf}) is not opened when a person answers it alone: it is kept as the parked request
 * ({@link #parked()}) and its future fails with a {@link RunParkedException}, so the step unwinds and the run ends
 * parked. A {@link ApprovalRequest#isShadow() shadow} question waits in the process instead. When the run is resumed,
 * the answer given meanwhile ({@link #preset}) answers that step's question at once if the step asks it unchanged and
 * the answer is one it offers, and fails it with a {@link PresetAnswerException} otherwise.
 */
class ApprovalGate {

    /** An answer given while the run was parked, and the question it was given to. */
    /** @param keepWhole the run's secret values, which a message that quotes the question keeps whole */
    private record Preset(int stepIndex, String type, String question, String answer,
                          java.util.Collection<String> keepWhole) {}

    private final AtomicReference<CompletableFuture<String>> pending = new AtomicReference<>();
    private final AtomicReference<ApprovalRequest> currentRequest = new AtomicReference<>();
    private final Map<String, String> memory = new ConcurrentHashMap<>();
    /** The step whose question parks the run instead of waiting; {@link ExecutionContext#NO_STEP} for none. */
    private volatile int parkingStep = ExecutionContext.NO_STEP;
    /** The question the run parked at; {@code null} while it has not parked. */
    private volatile ApprovalRequest parked;
    /** The question the preset answer was taken by last. */
    private volatile ApprovalRequest presetUsed;
    /** The answer given while the run was parked, until the question it answers is asked again. */
    private volatile Preset preset;
    /**
     * Who displays the waiting step: {@code true} as soon as a question is open,
     * {@code false} when the waiting ends - both on the run's thread, never on the one that answers.
     */
    private volatile Consumer<Boolean> waitListener;

    void onWait(Consumer<Boolean> listener) {
        this.waitListener = listener;
    }

    /** The open question, {@code null} when none is open. A value, not the gate's state: only the gate changes it. */
    ApprovalRequest currentRequest() {
        return currentRequest.get();
    }

    /** The promise of a new question; a test overrides it to step into the wait. */
    CompletableFuture<String> promise() {
        return new CompletableFuture<>();
    }

    /**
     * Opens the question and returns the promise of its answer. A preset answer for the question, or parking at its
     * step (unless the question is a shadow question), returns a future that is already complete instead, and opens
     * nothing.
     */
    CompletableFuture<String> request(ApprovalRequest request) {
        CompletableFuture<String> answered = takePreset(request);
        if (answered != null) {
            return answered;   // answered while the run was parked: nobody waits, so the step does not show waiting
        }
        if (request.getStepIndex() == parkingStep && !request.isShadow()) {
            parked = request;
            notifyWaiting(true);   // the step waits for a person, outside the process
            return CompletableFuture.failedFuture(new RunParkedException(
                    "The run parked at the question of step " + request.getStepIndex()));
        }
        CompletableFuture<String> future = promise();
        synchronized (this) {   // question and promise change together, so an answer never meets one without the other
            currentRequest.set(request);
            pending.set(future);
        }
        notifyWaiting(true);
        return future;
    }

    /**
     * Waits for the answer; afterwards the step shows again that it is running - also on timeout or cancel. A
     * question that timed out or whose wait was interrupted is closed: an answer to it is refused, not lost. An answer
     * that arrived after the wait timed out but before the question was closed wins over the timeout, because its
     * sender was told it arrived.
     */
    String await(CompletableFuture<String> future, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        boolean parks = false;
        try {
            return answerOf(future, timeout, unit);
        } catch (RunParkedException e) {
            parks = true;   // the step goes on waiting, outside the process: it does not run again here
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PresetAnswerException refused) {
                throw refused;   // the step's own failure, not a failed wait
            }
            throw e;
        } catch (TimeoutException e) {
            close(future);   // from here on no answer reaches the question
            if (future.isDone() && !future.isCompletedExceptionally()) {
                return future.getNow(null);
            }
            throw e;
        } finally {
            close(future);
            if (!parks) {
                notifyWaiting(false);
            }
        }
    }

    /**
     * The answer of {@code future}, or the exception it was completed with. A future that was completed with a
     * {@link CancellationException} (a cancel, or the {@link RunParkedException} of a parked run) throws that
     * exception itself from {@code get} on Java 21, and on a later JDK a new {@code CancellationException} that only
     * has it as its cause. The caller tells a parked run from a cancelled one by the exception, so it gets the one
     * the future holds on every JDK.
     */
    private static String answerOf(CompletableFuture<String> future, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(timeout, unit);
        } catch (CancellationException e) {
            if (e.getCause() instanceof CancellationException completedWith) {
                throw completedWith;
            }
            throw e;
        }
    }

    /** Closes the question of {@code future} if it is still open; a later question stays open. */
    private synchronized void close(CompletableFuture<String> future) {
        if (pending.compareAndSet(future, null)) {
            currentRequest.set(null);
        }
    }

    private void notifyWaiting(boolean waiting) {
        Consumer<Boolean> listener = waitListener;
        if (listener != null) listener.accept(waiting);
    }

    /**
     * Answers {@code question} if it is still the open one: an answer meant for a question that was answered, timed
     * out or cancelled in the meantime never reaches the next question.
     *
     * @return whether the answer reached the waiting block
     */
    boolean resolve(ApprovalRequest question, String answer) {
        return resolve(question, answer, null);
    }

    /**
     * As {@link #resolve(ApprovalRequest, String)}, with what goes with the answer (who gave it): {@code whenResolved}
     * runs only when the answer reaches the question, and then before the waiting block can take the answer. The
     * wait's end (a timeout, a cancel) closes the question under the same lock, so an answer that comes at that very
     * moment either resolves the question, with what goes with it, or does neither.
     *
     * <p>The answer reaches the question only as the question takes it ({@link ApprovalRequest#acceptedAnswer}): an
     * approval's option in its spelling, a human input's value that the rules of its fields take. It is checked
     * before the lock is taken, since a workflow's pattern may take its time.
     *
     * @param whenResolved runs under the gate's lock, so it must not wait for the run; {@code null} for nothing
     * @throws IllegalArgumentException if the question does not take the answer; it stays open
     */
    boolean resolve(ApprovalRequest question, String answer, Runnable whenResolved) {
        String accepted = question == null ? answer : question.acceptedAnswer(answer);
        return resolveChecked(question, accepted, whenResolved);
    }

    private synchronized boolean resolveChecked(ApprovalRequest question, String answer, Runnable whenResolved) {
        if (question == null || currentRequest.get() != question) {
            return false;
        }
        CompletableFuture<String> future = pending.get();
        if (future != null && !future.isDone() && whenResolved != null) {
            whenResolved.run();
        }
        pending.set(null);
        currentRequest.set(null);
        return future != null && future.complete(answer);
    }

    /** Cancels an open promise - the waiting block runs into the CancellationException. */
    synchronized void cancel() {
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            future.completeExceptionally(new CancellationException("Execution cancelled"));
        }
        currentRequest.set(null);
    }

    /** Lets the question of step {@code stepIndex} park the run; {@link ExecutionContext#NO_STEP} lets none. */
    void parkQuestionOf(int stepIndex) {
        parkingStep = stepIndex;
    }

    /** Whether {@code request} took the preset answer, the one given while the run was parked. */
    boolean answeredFromPreset(ApprovalRequest request) {
        return presetUsed == request;
    }

    /** The question the run parked at; {@code null} while it has not parked. */
    ApprovalRequest parked() {
        return parked;
    }

    /** The answer given while the run was parked, for the question of type {@code type} at step {@code stepIndex}. */
    void preset(int stepIndex, String type, String question, String answer, java.util.Collection<String> keepWhole) {
        preset = new Preset(stepIndex, type, question, answer, keepWhole);
    }

    /**
     * The preset answer as a completed future when {@code request} is the question it answers (same step and type),
     * and then used up. An approval receives it as one of its options, in the option's spelling, and a human input
     * as the rules of its fields take it, as an answer in the process does ({@link ApprovalRequest#acceptedAnswer}).
     * A failed future ({@link PresetAnswerException}) when that step now asks another question, the answer is not
     * one of its options, or the rules of the input refuse it (a record of an earlier version, a package changed
     * since); {@code null} when it answers another step.
     */
    private CompletableFuture<String> takePreset(ApprovalRequest request) {
        Preset answer = preset;
        if (answer == null || answer.stepIndex() != request.getStepIndex()
                || !Objects.equals(answer.type(), request.getType())) {
            return null;
        }
        preset = null;
        presetUsed = request;
        if (!Objects.equals(answer.question(), request.getQuestion())) {
            return CompletableFuture.failedFuture(new QuestionChangedException(answer.question(), answer.keepWhole()));
        }
        try {
            return CompletableFuture.completedFuture(request.acceptedAnswer(answer.answer()));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new PresetAnswerException("the answer given while the run was parked "
                    + ("human-input".equals(request.getType()) ? "is refused: " : "is not an offered option: ")
                    + e.getMessage()));
        }
    }

    void remember(String question, String answer) {
        memory.put(question, answer);
    }

    String remembered(String question) {
        return memory.get(question);
    }

    /**
     * The remembered answers, as part of the run's state ({@link ContextState}). The open question and its promise
     * are not: they belong to the process that waits for the answer.
     */
    Map<String, String> state() {
        return Map.copyOf(memory);
    }

    /** Replaces the remembered answers with those of a run's state. */
    void restore(Map<String, String> remembered) {
        memory.clear();
        memory.putAll(remembered);
    }
}
