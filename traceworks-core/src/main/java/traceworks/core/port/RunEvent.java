package traceworks.core.port;

import traceworks.core.model.RunSnapshot;
import traceworks.core.model.GateStatus;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One thing that happened during a run, as the core reports it through {@link RunEvents}. The records hold the facts
 * of a step and of the run status; a later version adds the events of the run record. The engine emits only top-level
 * steps; a {@code foreach} step reports its items in between ({@link ForEachItemStarted}, {@link ForEachItemFinished}),
 * an {@code agentic} step its loop ({@link AgenticProgress}), and a child run started by a step arrives as
 * {@link ChildRunEvent}s. A run with checkpoints reports its state between two top-level blocks ({@link Checkpoint}).
 * A run's last event is {@link RunFinished}, or {@link RunParked} for a run that parked at a question or by a pause.
 */
public sealed interface RunEvent {

    /**
     * A step began.
     *
     * @param position the block's index in the {@code blocks} of its workflow
     * @param ai       whether the step uses AI (the "AI" or "deterministic" badge)
     */
    record StepStarted(int stepIndex, String blockName, String blockType, int position, boolean ai)
            implements RunEvent {}

    /**
     * A running step waits for a person ({@code waiting}: an approval or human input is open) or runs again after
     * the answer, a timeout or a cancel ({@code !waiting}). While it waits, the step's status is
     * {@code WAITING_APPROVAL}.
     */
    record StepWaiting(int stepIndex, boolean waiting) implements RunEvent {}

    /**
     * A step ended.
     *
     * @param input      what the step ran: the resolved command or prompt
     * @param durationMs the duration of the step, {@code 0} when unknown
     * @param metadata   a read-only copy of the step's metadata; it keeps {@code null} values, absent is empty
     */
    record StepFinished(int stepIndex, String blockName, String blockType, StepStatus status, String input,
                        long durationMs, Map<String, Object> metadata) implements RunEvent {
        public StepFinished {
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    /**
     * A {@code foreach} step starts one of its items. The items are not steps of their own, so these events are how
     * the run shows which item runs, while the step runs.
     *
     * @param stepIndex  the index of the foreach step
     * @param itemIndex  the index of the item, from 0
     * @param item       the item, as the command sees it
     * @param totalItems how many items the foreach runs over
     */
    record ForEachItemStarted(int stepIndex, int itemIndex, String item, int totalItems) implements RunEvent {}

    /**
     * A {@code foreach} item ended.
     *
     * @param stepIndex  the index of the foreach step
     * @param itemIndex  the index of the item, from 0
     * @param item       the item, as the command sees it
     * @param totalItems how many items the foreach runs over
     * @param status     how the item ended
     * @param output     what the item produced; empty when it produced nothing
     * @param subRunId   the run the item started when the foreach runs a sub-workflow per item ({@code workflow_id});
     *                   {@code null} when the item ran a command
     * @param resumedFrom the seq of the run's earlier {@code foreach.item.finished} this item was taken over from, in
     *                   a resumed run: the item did not run again, and no {@link ForEachItemStarted} came before this
     *                   event. {@code null} for an item that ran
     */
    record ForEachItemFinished(int stepIndex, int itemIndex, String item, int totalItems, StepStatus status,
                               String output, String subRunId, Integer resumedFrom) implements RunEvent {

        /** An item that ran. */
        public ForEachItemFinished(int stepIndex, int itemIndex, String item, int totalItems, StepStatus status,
                                   String output, String subRunId) {
            this(stepIndex, itemIndex, item, totalItems, status, output, subRunId, null);
        }
    }

    /**
     * An {@code agentic} step reports a phase of its Reason-Act-Observe loop while it runs. The iterations are not steps
     * of their own; these events show the loop as it goes, and the step's metadata ({@code iterations}) keeps it.
     *
     * @param stepIndex the index of the agentic step
     * @param type      the phase: {@code agentic_started},
     *                  {@code agentic_reasoning}, {@code agentic_reasoned}, {@code agentic_approval_required},
     *                  {@code agentic_action_compressed}, {@code agentic_acting}, {@code agentic_acted},
     *                  {@code agentic_goal_achieved}, {@code agentic_goal_impossible}, {@code agentic_error},
     *                  {@code agentic_total_timeout}, {@code agentic_iteration_timeout},
     *                  {@code agentic_token_budget_exceeded}, {@code agentic_token_tracking_failed},
     *                  {@code agentic_repeated_actions} or {@code agentic_completed}
     * @param data      the fields of the phase, such as {@code iteration} or {@code action}, in order; a read-only copy,
     *                  absent is empty
     */
    record AgenticProgress(int stepIndex, String type, Map<String, Object> data) implements RunEvent {
        public AgenticProgress {
            data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    /**
     * An event of a child run: a run that a {@code sub-workflow} or a {@code foreach} with {@code workflow_id} started.
     * The child runs in-process with its own run id, and every event it emits reaches the parent's sink wrapped in this
     * record, so none is lost and each names the run it belongs to. A grandchild's events arrive wrapped twice: once for
     * the grandchild, once for the child. The child's last event is its own {@link RunFinished}.
     *
     * @param runId the child's run id; the parent's step names it in its metadata ({@code subRunId})
     * @param event what happened in the child
     */
    record ChildRunEvent(String runId, RunEvent event) implements RunEvent {
        public ChildRunEvent {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(event, "event");
        }
    }

    /** The output a step produced. */
    record OutputRecorded(int stepIndex, String blockName, String output) implements RunEvent {}

    /** A step reported a gate verdict ({@code gate_status}, {@code gate_category}, {@code gate_description}). */
    record GateReported(int stepIndex, GateStatus status, String category, String description)
            implements RunEvent {}

    /**
     * A step waits for a person: an {@code approval} or a {@code human-input}. Absent lists are empty.
     *
     * @param type              {@code approval} or {@code human-input}
     * @param question          the approval question or the human-input prompt
     * @param rejectOptions     the {@code options} that count as a rejection
     * @param inputType         human input only: {@code text}, {@code number}, {@code select} and so on
     * @param defaultValue      human input only
     * @param required          human input only
     * @param validationPattern human input only
     * @param variableName      human input only: the variable that receives the answer
     * @param fields            human input only: the fields of a form, {@code null} for a single input
     * @param parks             whether the run parked at this question
     * @param preset            whether the question was answered at once from the answer given while the run was
     *                          parked (a resume). With neither, the run waits here for an answer from the terminal
     *                          or the inbox
     */
    record ApprovalRequested(int stepIndex, String blockId, String type, String question, List<String> options,
                             List<String> rejectOptions, String inputType, String defaultValue, boolean required,
                             String validationPattern, String variableName, List<Map<String, Object>> fields,
                             boolean parks, boolean preset)
            implements RunEvent {
        /** A question that neither parks the run nor takes a preset answer, such as an agentic plan approval. */
        public ApprovalRequested(int stepIndex, String blockId, String type, String question, List<String> options,
                                 List<String> rejectOptions, String inputType, String defaultValue, boolean required,
                                 String validationPattern, String variableName, List<Map<String, Object>> fields) {
            this(stepIndex, blockId, type, question, options, rejectOptions, inputType, defaultValue, required,
                    validationPattern, variableName, fields, false, false);
        }

        public ApprovalRequested {
            options = options == null ? List.of() : List.copyOf(options);
            rejectOptions = rejectOptions == null ? List.of() : List.copyOf(rejectOptions);
            fields = fields == null ? null : List.copyOf(fields);
        }
    }

    /**
     * The answer to an {@link ApprovalRequested}.
     *
     * @param type {@code approval} or {@code human-input}
     */
    record ApprovalAnswered(int stepIndex, String type, String answer) implements RunEvent {}

    /**
     * The run's state between two top-level blocks, complete enough to resume the run from here in another process
     * ({@code WorkflowEngine.resume}). A run with checkpoints reports one before its first block and one after every
     * top-level step that lets it go on; a child run reports none.
     *
     * @param nextPosition the position, in the workflow's {@code blocks}, of the block that runs next; the number of
     *                     blocks after the last one
     * @param snapshot     the run's state
     */
    record Checkpoint(int nextPosition, RunSnapshot snapshot) implements RunEvent {
        public Checkpoint {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** The run changed its status while it goes on: {@code PAUSED} between two blocks, {@code RUNNING} again. */
    record RunStatusChanged(RunStatus status) implements RunEvent {}

    /**
     * The run parked: it stopped at a point from which another process can go on, and {@code traceworks resume}
     * continues it from its last checkpoint. The last event of a run that parked, instead of {@link RunFinished}. A
     * child run never parks.
     *
     * <p>At a question ({@code approval}, {@code human-input}): a top-level question that a person answers alone (no
     * AI actor) waits for that person outside the process, and the resume brings the answer. The step that asked is
     * not finished, so no {@link StepFinished} follows its {@link ApprovalRequested}.
     *
     * <p>By a pause ({@value #PAUSE}): someone asked the run to pause, and it finished what was in flight first.
     * Between two top-level blocks there is no step ({@link #NO_STEP}); between two items of a top-level
     * {@code foreach} the step is that loop, which is not finished and runs again on the resume, taking over the
     * items it finished.
     *
     * @param stepIndex the index of the step that is not finished: the one whose question the run parked at, or the
     *                  loop a pause parked between two items; {@link #NO_STEP} for a pause between two blocks
     * @param type      {@code approval}, {@code human-input} or {@value #PAUSE}
     */
    record RunParked(int stepIndex, String type) implements RunEvent {

        /** The {@link #type} of a run that parked because it was asked to pause. */
        public static final String PAUSE = "pause";
        /** The {@link #stepIndex} of a run that parked between two blocks: no step is unfinished. */
        public static final int NO_STEP = -1;
    }

    /**
     * The run ended. Always the last event of a run that did not park.
     *
     * @param status the final status
     * @param gate   the run's gate verdict ({@code gate_status}, {@code gate_category}, {@code gate_description},
     *               {@code gate_summary}); empty when no step reported one. A read-only copy that keeps
     *               {@code null} values; absent is empty.
     * @param error  why the run failed, {@code null} otherwise
     */
    record RunFinished(RunStatus status, Map<String, Object> gate, String error) implements RunEvent {
        public RunFinished {
            gate = gate == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(gate));
        }
    }
}
