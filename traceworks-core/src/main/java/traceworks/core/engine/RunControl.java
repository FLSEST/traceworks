package traceworks.core.engine;

import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Pause, resume and cancel of a run, from any thread. Pass it in {@link RunOptions} and keep a reference to control
 * the run while {@link WorkflowEngine#run} executes it.
 *
 * <ul>
 *   <li><b>Pause</b> takes effect between two top-level blocks: the run reports {@code PAUSED} and waits until it is
 *       resumed or cancelled. A run with {@link RunOptions#parkOnPause} does not wait: it ends {@code PARKED} there,
 *       or between two items of a top-level {@code foreach}, and {@link WorkflowEngine#resume} goes on from its last
 *       checkpoint, in another process if need be.</li>
 *   <li><b>Cancel</b> stops the run: no further block starts (also when the run is paused), the process the current
 *       step runs is killed and an open approval is answered with a cancellation. A cancel before the run started is
 *       honoured: no block runs.</li>
 *   <li><b>Answer</b> resolves the approval ({@link #answer(String)}) or human input ({@link #answerInput(Map)})
 *       the run waits for. Where several callers answer (a terminal, the inbox), each answers the one question it
 *       names ({@link #nameWaiting}, {@link #answer(Question, String, Runnable)}), and who gave an answer is applied
 *       when the answer resolves its question.</li>
 * </ul>
 *
 * <p>A run shares its control with the child runs it starts (a {@code sub-workflow}, a {@code foreach} with
 * {@code workflow_id}): they share the pause and cancel flags. While the child runs, a cancel stops the child (its
 * process is killed, its open approval cancelled) and the parent, which starts no further block. A pause takes effect
 * between two blocks of the child, which reports {@code PAUSED}; the parent stays in its step and reports nothing until
 * the child ends. (With {@link RunOptions#parkOnPause} the child does not wait: it runs to its end, and the parent
 * parks after its step.) When the child ends, the engine hands the control back to the parent, so a later cancel reaches the
 * parent again.
 */
public final class RunControl {

    /** The {@link ApprovalRequest#getType() type} of an approval's question. */
    private static final String APPROVAL = "approval";
    /** The {@link ApprovalRequest#getType() type} of a human input's question. */
    private static final String HUMAN_INPUT = "human-input";

    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile ExecutionContext context;
    /** The question that was given a name last; {@code null} when the run waited on nothing then. */
    private volatile Named named;

    /** Asks the run to wait before its next block, or to park there ({@link RunOptions#parkOnPause}). */
    public void pause() {
        paused.set(true);
    }

    /** Lets a paused run go on. */
    public void resume() {
        paused.set(false);
    }

    /** Stops the run: no further block starts, the running process is killed, an open approval is cancelled. */
    public void cancel() {
        cancelled.set(true);
        ExecutionContext running = context;
        if (running != null) {
            running.cancel();
        }
    }

    /**
     * Answers the approval the run waits for: an {@code approval} step, or the approval gate of an {@code agentic}
     * step. The answer must be one of the offered options, compared case-insensitively; the step receives it as
     * offered. While a child run runs, its approval is the one answered.
     *
     * <p>A caller holding this control answers in-process; a later version also takes answers as events in the run's
     * log.
     *
     * @throws IllegalStateException    if no approval is waiting: the run waits for nothing, for a human input, or the
     *                                  approval was answered, timed out or cancelled meanwhile
     * @throws IllegalArgumentException if the approval does not offer {@code answer}; the approval stays open
     */
    public void answer(String answer) {
        answerWaiting(null, APPROVAL, request -> request.acceptedAnswer(answer), null);
    }

    /**
     * Answers the human input the run waits for, with a value per field: a single input takes the value of its
     * {@code variable_name}, a form one value per field (an absent field is empty). While a child run runs, its human
     * input is the one answered.
     *
     * <p>A caller holding this control answers in-process; a later version also takes answers as events in the run's
     * log.
     *
     * @param values the value per field, keyed by the field's variable name; values are passed on as text
     * @throws IllegalStateException    if no human input is waiting: the run waits for nothing, for an approval, or the
     *                                  input was answered, timed out or cancelled meanwhile
     * @throws IllegalArgumentException if {@code values} names a field the input does not have, or a single input gets
     *                                  no value; the input stays open
     */
    public void answerInput(Map<String, Object> values) {
        Objects.requireNonNull(values, "values");
        answerWaiting(null, HUMAN_INPUT, request -> request.inputAnswer(values), null);
    }

/**
     * The name of one question, as the run's record names it.
     *
     * @param run  the run of the record that asks it: the run itself, or a child run
     * @param step the step that asks it
     * @param seq  the sequence number of the line that recorded the question
     */
    public record Question(String run, int step, int seq) {}

    /** The question that has a name: the run that asks it and the request it waits on. */
    private record Named(Question name, ExecutionContext context, ApprovalRequest request) {}

    /**
     * Gives the question the run waits on now its name. Whoever records the question calls this while the question
     * is recorded, on the thread that asked it, so the name belongs to exactly that question. When the run waits on
     * nothing (the question was answered from the record, or parks the run), no question has a name.
     */
    public void nameWaiting(Question name) {
        Objects.requireNonNull(name, "name");
        ExecutionContext running = context;
        ApprovalRequest waiting = running != null ? running.getCurrentApprovalRequest() : null;
        named = waiting == null ? null : new Named(name, running, waiting);
    }

    /**
     * Answers the approval {@code question} names, and no other: an answer sent to a question whose wait ended never
     * reaches the question that opened after it. Otherwise as {@link #answer(String)}.
     *
     * @param whenResolved what goes with the answer, such as who gave it: runs only when the answer resolves the
     *                     question, before the waiting step can take the answer, and never for an answer that came
     *                     as the question timed out or took its default. It must not wait for the run
     * @throws IllegalStateException    if {@code question} is not the open question, or as {@link #answer(String)}
     * @throws IllegalArgumentException as {@link #answer(String)}
     */
    public void answer(Question question, String answer, Runnable whenResolved) {
        Objects.requireNonNull(question, "question");
        answerWaiting(question, APPROVAL, request -> request.acceptedAnswer(answer), whenResolved);
    }

    /**
     * Answers the human input {@code question} names, and no other. Otherwise as {@link #answerInput(Map)}.
     *
     * @param whenResolved as for {@link #answer(Question, String, Runnable)}
     * @throws IllegalStateException    if {@code question} is not the open question, or as {@link #answerInput(Map)}
     * @throws IllegalArgumentException as {@link #answerInput(Map)}
     */
    public void answerInput(Question question, Map<String, Object> values, Runnable whenResolved) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(values, "values");
        answerWaiting(question, HUMAN_INPUT, request -> request.inputAnswer(values), whenResolved);
    }

    /**
     * Resolves the question of {@code type} with the answer {@code toAnswer} makes of it: the question {@code name}
     * names, or, without a name, the one the attached run waits on. The question is named when it is resolved, so an
     * answer never reaches a question that opened meanwhile.
     */
    private void answerWaiting(Question name, String type, Function<ApprovalRequest, String> toAnswer,
                               Runnable whenResolved) {
        String noneWaiting = "No " + label(type) + " is waiting: ";
        ExecutionContext running;
        ApprovalRequest waiting;
        if (name == null) {
            running = context;
            waiting = running != null ? running.getCurrentApprovalRequest() : null;
            if (waiting == null) {
                throw new IllegalStateException(noneWaiting + "the run waits for no answer");
            }
        } else {
            Named open = named;
            if (open == null || !open.name().equals(name)) {
                throw new IllegalStateException(noneWaiting + "the question it answers is no longer open");
            }
            running = open.context();
            waiting = open.request();
        }
        if (!type.equals(waiting.getType())) {
            String method = HUMAN_INPUT.equals(waiting.getType()) ? "answerInput" : "answer";
            throw new IllegalStateException(noneWaiting + "the run waits for " + article(waiting.getType()) + " "
                    + label(waiting.getType()) + "; answer it with " + method);
        }
        if (!running.resolveApproval(waiting, toAnswer.apply(waiting), whenResolved)) {
            throw new IllegalStateException(noneWaiting + "it was answered, timed out or cancelled meanwhile");
        }
    }

    private static String label(String type) {
        return HUMAN_INPUT.equals(type) ? "human input" : "approval";
    }

    private static String article(String type) {
        return HUMAN_INPUT.equals(type) ? "a" : "an";
    }

    public boolean isPaused() {
        return paused.get();
    }

    public boolean isCancelRequested() {
        return cancelled.get();
    }

    /** The flags the run's {@link ExecutionContext} shares with this control. */
    ExecutionContext.Builder bind(ExecutionContext.Builder builder) {
        return builder.paused(paused).cancelled(cancelled);
    }

    /**
     * From now on a cancel also kills the context's process; a cancel that came earlier is applied at once.
     *
     * @return the context attached before, such as the parent of a child run; {@code null} for none. The engine
     *         passes it to {@link #restore} when the run ends.
     */
    ExecutionContext attach(ExecutionContext running) {
        ExecutionContext previous = this.context;
        this.context = running;
        if (cancelled.get()) {
            running.cancel();
        }
        return previous;
    }

    /** Hands the control back to the context attached before a run ({@link #attach}); a cancel that came is applied. */
    void restore(ExecutionContext previous) {
        this.context = previous;
        if (previous != null && cancelled.get()) {
            previous.cancel();
        }
    }
}
