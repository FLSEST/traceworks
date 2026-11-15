package traceworks.core.engine;

import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ChildRuns;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.OutputBudget;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunParked;
import traceworks.core.port.RunEvent.RunStatusChanged;
import traceworks.core.port.RunEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Runs a workflow: its top-level blocks in order, each through its {@link BlockExecutor}, until the last block, a
 * failed block that stops the run, or a cancel. The engine persists nothing; it reports every step and the end of
 * the run as {@link traceworks.core.port.RunEvent}s and returns the {@link RunOutcome}.
 *
 * <p>Before the first block, the workflow passes the {@link RunAdmission}: a workflow Traceworks would run differently
 * from what it says starts no block and ends {@code FAILED} with an error that
 * begins with {@value RunAdmission#MESSAGE_PREFIX} and names every reason.
 *
 * <p>The run executes on the calling thread. Pause and cancel come from another thread through the
 * {@link RunControl} in the {@link RunOptions}; an interrupt of the calling thread while the run is paused ends it
 * {@code CANCELLED}. Whatever goes wrong in a block, an {@link Error} included, ends the run as {@code FAILED} with
 * the message as its error, and the block's step as {@code FAILED} with the same text as its output; neither the run
 * nor the step stays {@code RUNNING}. After the run's cancel, whatever a block throws ends both {@code CANCELLED},
 * without an error, except a failed record write.
 *
 * <p>The event sink is the run's record, so the engine fails closed when it breaks: a write that fails during the
 * run (also one a block executor caught) starts no further block and ends the run {@code FAILED} with an error that
 * begins with {@value EventLogException#MESSAGE_PREFIX}. A failure while recording the end of the run
 * ({@link RunFinished}) makes {@link #run} throw an {@link EventLogException}.
 *
 * <p>With {@link RunOptions#checkpoints} on, the run reports its state as a {@link Checkpoint} before its first block
 * and after every top-level step that lets it go on (not after one that ends it by failure or cancel), the last one
 * with the number of blocks as its position. {@link #resume} continues a run from such a checkpoint, also in another
 * process.
 *
 * <p>With {@link RunOptions#parkAtQuestions} on, a top-level {@code approval} or {@code human-input} block whose
 * question a person answers alone parks the run: its question is recorded ({@code ApprovalRequested}), the step is not
 * finished (no output, no gate, no {@code StepFinished}), no checkpoint follows, and the run ends {@code PARKED} with
 * {@link RunParked} as its last event instead of {@link RunFinished}. {@link #resume} from the last checkpoint runs
 * that block again and hands it the answer given meanwhile ({@link ResumePoint#answer()}); a step that now asks
 * another question fails with the output {@code the question changed while the run was parked: ...}. A cancel that
 * comes while the question parks wins: the asking step ends as a cancelled question and the run {@code CANCELLED}. A question cast
 * with an AI actor ({@code shadow}, or {@code ai} that asks the person), a question asked inside another block (the
 * plan approval of an {@code agentic} step) and a question in a child run never park; they wait in the process, so
 * the AI is never asked again by a resume.
 *
 * <p>A pause ({@link RunControl#pause()}) makes the run wait in the process between two top-level blocks, reporting
 * {@code PAUSED}, until it is resumed or cancelled. With {@link RunOptions#parkOnPause} on (and checkpoints), it parks
 * the run instead: the run finishes the step in flight, or the item in flight of a top-level {@code foreach}, reports
 * nothing as {@code PAUSED}, and ends {@code PARKED} with a {@link RunParked} of the type
 * {@value RunParked#PAUSE}. Between two blocks, the checkpoint after the last step is where {@link #resume} goes on.
 * Between two items the loop's step is not finished and no checkpoint follows, so the resume runs the loop again,
 * with the items it finished ({@link ResumePoint#items()}). A loop that runs a workflow per item does not park
 * between its items, and a child run neither parks nor waits: both run to their end, and the run parks after the
 * step. A pause during the last block parks nothing: the run ends. A cancel wins over a pause.
 *
 * <p>A block may start another workflow as a child run ({@link ChildRuns}: {@code sub-workflow}, {@code foreach}
 * with {@code workflow_id}). The child runs in-process through this engine, on the parent's thread, with its own run
 * id (a random UUID) and the trigger source {@code SUB_WORKFLOW}. It shares the parent's ports, working directory and
 * {@link RunControl}, and its resolver is the one the parent's resolver gives for it
 * ({@link traceworks.core.port.WorkflowResolver#forChild}); its {@link RunAdmission} refuses a
 * {@code ./images/<name>} when it comes from another package than the run's. Its events go into the parent's sink,
 * each wrapped as a {@link ChildRunEvent} with the child's run id; a failed write there stops the parent too. A child
 * reports no checkpoints: it is resumed with its parent's step.
 *
 * <p>A run and its child runs share one {@link OutputBudget} of {@link RunOptions#outputBudgetBytes}: the input and
 * output of every finished top-level step count against it while the run holds them, a child's until the child has
 * ended, and so does what a loop gathers. Once text did not fit, the step that would have gone beyond fails with a
 * line that names the budget, and the run ends {@code FAILED} after that step, whatever the block's {@code on_failure}
 * says; a child run that ends this way ends its parent too.
 */
public final class WorkflowEngine {

    /** How often a paused run checks whether it may go on. */
    private static final long PAUSE_POLL_MILLIS = 500;

    /** The block types whose question parks the run when it is a top-level block. */
    private static final Set<String> QUESTION_TYPES = Set.of("approval", "human-input");

    /** The block type inside which a pause that parks takes effect between two items, when it is a top-level block. */
    private static final String LOOP_TYPE = "foreach";

    private final BlockTypeRegistry blockTypes;
    private final RunAdmission admission;
    private final RunCompletionHandler completionHandler = new RunCompletionHandler();

    public WorkflowEngine(BlockTypeRegistry blockTypes) {
        this.blockTypes = Objects.requireNonNull(blockTypes, "blockTypes");
        this.admission = new RunAdmission(blockTypes);
    }

    /**
     * Runs the workflow and returns once the run has ended.
     *
     * @param definition the workflow
     * @param inputs     the run's inputs; they win over the workflow's {@code defaultContext}. May be {@code null}.
     * @param options    the run's id, ports, control and working directory
     * @return how the run ended; the same facts as its last event, {@link RunFinished}, or {@link RunParked} for a run
     *         that parked: {@code PARKED}, no gate, no error
     * @throws EventLogException if the end of the run cannot be recorded: the run is over (no block runs any more),
     *                           but its outcome is not in the record, so it must not count as finished
     */
    public RunOutcome run(WorkflowDefinition definition, Map<String, Object> inputs, RunOptions options) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(options, "options");
        return execute(definition, inputs, null, options, false,
                new OutputBudget(options.outputBudgetBytes())).outcome();
    }

    /**
     * Continues a run from a checkpoint and returns once the run has ended: the run passes the {@link RunAdmission}
     * again, gets its context back as the checkpoint kept it (variables, taint, block outputs, step indices, run
     * metadata, remembered approvals), keeps the steps that ran before, and runs the blocks from
     * {@code point.nextPosition()} on. {@code trigger_source} becomes the options' trigger source
     * and {@code started_at} and {@code run_timestamp} the time of the resume. It emits no checkpoint before its first
     * block, because the one it resumes from holds the state it starts with; after each step it emits them as
     * {@link #run} does. The run keeps the id of the options. The point's {@link PresetAnswer} answers the question of
     * its step when that step asks it again, without waiting; the step fails if it now asks another question. A
     * {@code foreach} that was in flight runs again and takes over the items the point holds as finished
     * ({@link ResumePoint#items()}) instead of running them; a loop that runs a workflow per item runs every item.
     *
     * @param definition the workflow the run was started with
     * @param point      the checkpoint's snapshot and position
     * @param options    the run's id, ports, control and working directory in this process
     * @return how the run ended; its steps are the snapshot's followed by the new ones
     * @throws IllegalArgumentException if the position is outside {@code 0..blocks.size()}; nothing is reported then
     * @throws EventLogException        if the end of the run cannot be recorded, as for {@link #run}
     */
    public RunOutcome resume(WorkflowDefinition definition, ResumePoint point, RunOptions options) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(options, "options");
        int blocks = definition.blocks().size();
        if (point.nextPosition() < 0 || point.nextPosition() > blocks) {
            throw new IllegalArgumentException("Cannot resume at block position " + point.nextPosition()
                    + ": the workflow has " + blocks + " blocks, so the position is 0 to " + blocks);
        }
        return execute(definition, null, point, options, false,
                new OutputBudget(options.outputBudgetBytes())).outcome();
    }

    /**
     * What a run resumed from {@code snapshot} holds from its start against its output budget, in bytes
     * ({@link OutputBudget#sizeOf}): the input and output of the steps that ran and the values of the variables they
     * set. A resume that would start beyond its budget can be refused before it runs anything.
     */
    public static long heldOnResume(RunSnapshot snapshot) {
        return ExecutionContextFactory.heldOnResume(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /**
     * Runs a fresh run ({@code point == null}, with {@code inputs}) or a resumed one, until it has ended.
     *
     * @param otherPackage whether the workflow is a child of another package than the run's, which its admission
     *                     judges too
     * @param budget       the output budget of the run, which a child run shares with its parent; what the run holds
     *                     of it is given back when the run has ended
     */
    private Executed execute(WorkflowDefinition given, Map<String, Object> inputs, ResumePoint point,
                             RunOptions options, boolean otherPackage, OutputBudget budget) {
        long held = budget.heldBytes();
        GuardedEvents events = new GuardedEvents(options.events());
        RunControl control = options.runControl();
        RunState run = new RunState(options.runId(), RunStatus.RUNNING);
        ExecutionContext context = null;
        ExecutionContext outer = null;
        boolean attached = false;
        EventLogException thrown = null;
        // The model choices first: from here on the definition names the provider and the model of every AI step
        Map<String, Object> ownInputs = point == null ? inputs : resumedInputs(point, options);
        ModelChoices.Resolution chosen = ModelChoices.resolve(given,
                options.modelInputs() != null ? options.modelInputs() : ownInputs);
        WorkflowDefinition definition = chosen.definition();
        List<String> ofTheRun = new ArrayList<>(chosen.problems());
        ofTheRun.addAll(options.allowedModels().refusals(definition));
        try {
            // false: the run already failed, refused or with a variable without a value
            boolean ready = point == null ? starts(definition, inputs, run, otherPackage, ofTheRun)
                    : resumes(definition, point, run, budget, ofTheRun);
            if (ready && control.isCancelRequested()) {   // cancelled before the run started
                run.setStatus(RunStatus.CANCELLED);
            } else if (ready) {
                context = context(definition, point, run, options, events, budget, chosen.variables());
                if (point != null && point.answer() != null) {
                    PresetAnswer answer = point.answer();
                    context.presetAnswer(answer.stepIndex(), answer.type(), answer.question(), answer.answer());
                }
                if (point != null) {
                    context.recordedItems(point.items());
                }
                outer = control.attach(context);
                attached = true;
                if (point == null) {
                    checkpoint(0, run, context, options, events);
                }
                executeBlocks(definition, point == null ? 0 : point.nextPosition(), run, context, options, events);
            }
        } catch (Throwable t) {
            // Throwable, not Exception: an Error (a class that vanished under a running JVM) must not leave the
            // run RUNNING.
            thrown = t instanceof EventLogException logFailure ? logFailure : null;
            ended(run, t, events, control.isCancelRequested());
        } finally {
            if (attached) {
                control.restore(outer);   // a run that shares the control (sub-workflow) hands it back
            }
        }
        try {
            return new Executed(finish(run, context, events), thrown);
        } finally {
            budget.releaseTo(held);   // a child's steps are dropped now: its parent keeps only its output
        }
    }

    /**
     * The inputs a resumed run picks its model choices from: those of its checkpoint, with the option its start
     * recorded for each choice in place ({@link RunOptions#resumedPicks}). So a run that picked nothing keeps the
     * default it started with, also when the definition's default is another by now.
     */
    private static Map<String, Object> resumedInputs(ResumePoint point, RunOptions options) {
        Map<String, Object> inputs = new HashMap<>(point.snapshot().inputContext());
        inputs.putAll(options.resumedPicks());
        return inputs;
    }

    /**
     * How a run ended.
     *
     * @param logFailure the {@link EventLogException} that ended the run by being thrown (a record write that
     *                   bypassed the run's sink, such as an AI call's {@code ai.*} events); {@code null} otherwise
     */
    private record Executed(RunOutcome outcome, EventLogException logFailure) { }

    /** The context of a fresh run, or the one its checkpoint kept for a resumed run. */
    private ExecutionContext context(WorkflowDefinition definition, ResumePoint point, RunState run,
                                     RunOptions options, GuardedEvents events, OutputBudget budget,
                                     Map<String, String> modelVariables) {
        // A child run picks its model choices from the inputs of the run that started first, not from what a
        // parent hands over: a parent's variables can be set by a step
        RunOptions forChildren = options.modelInputs() != null ? options
                : options.toBuilder().modelInputs(run.getInputContext()).build();
        ExecutionContextFactory factory = new ExecutionContextFactory(options.clock(), options.commandRunner(),
                options.aiProviders(), options.workflowResolver(), childRuns(forChildren, events, budget),
                options.secrets(), budget);
        ExecutionContextFactory.ContextSeed seed = new ExecutionContextFactory.ContextSeed(options.runId(),
                definition.id(), definition.name(), options.workingDirectory(), run.getInputContext(),
                definition.instructions(), definition.model(), definition.provider(), definition.effort(), null,
                options.triggerSource(), events, options.runControl(), options.runPaths(),
                options.workspaceSourceSha(), options.platformData(), modelVariables);
        return point == null ? factory.create(seed) : factory.restore(seed, point.snapshot().context());
    }

    /**
     * The child runs of a run with {@code parent} options: each one runs through this engine with its own run id, the
     * parent's ports, working directory, control and secrets, and its events wrapped into the parent's sink. The
     * admission of a child of another package than the run's refuses its {@code ./images/<name>}
     * ({@link RunAdmission}). A child that ended by a thrown {@link EventLogException} stops the parent: the child's
     * run is recorded as ended, then the failure is registered with the parent's guarded sink and thrown. (The
     * alternative, throwing from {@code ended}, would skip recording the child's end.) The child shares the parent's
     * output {@code budget}.
     */
    private ChildRuns childRuns(RunOptions parent, GuardedEvents parentEvents, OutputBudget budget) {
        return (definition, inputs) -> {
            String childRunId = UUID.randomUUID().toString();
            boolean otherPackage = parent.workflowResolver().fromAnotherPackage(definition);
            RunOptions child = parent.toBuilder()
                    .runId(childRunId)
                    .events(event -> parentEvents.emit(new ChildRunEvent(childRunId, event)))
                    .workflowResolver(parent.workflowResolver().forChild(definition))
                    .triggerSource(TriggerSource.SUB_WORKFLOW)
                    .checkpoints(false)
                    .parkAtQuestions(false)
                    .build();
            Executed executed = execute(definition, inputs, null, child, otherPackage, budget);
            if (executed.logFailure() != null && parentEvents.failure() == null) {
                // A write that bypassed the sinks: the parent's guard does not know it yet. The child's record is
                // incomplete, so the parent's is too: it stops as for a failed event of the child, even where a
                // block or an on_failure policy would take the child's FAILED. (A failed emitted event is known to
                // the parent's guard already, which stops the parent's loop; the step keeps its child's run id.)
                parentEvents.fail(executed.logFailure());
            }
            RunOutcome outcome = executed.outcome();
            return new ChildRuns.ChildRun(childRunId, outcome.status(), outcome.steps(), outcome.error());
        };
    }

    /**
     * Sets the end of a run the block loop left with an exception: a failed event write wins, because the record is
     * incomplete whatever happened next, also one that bypassed the run's sink (an AI call's {@code ai.*} events) and
     * reached the loop as an {@link EventLogException}, and also after a cancel; any exception while the run's cancel
     * is requested ({@code cancelRequested}) ends the run CANCELLED without an error, as a cancel does otherwise (its
     * step is CANCELLED too, see {@link BlockStepRunner}); anything else FAILED, also a
     * {@link java.util.concurrent.CancellationException} nobody's cancel of the run stands behind: every real cancel
     * sets the run's cancel, and a run CANCELLED without one would record a cancel that did not happen.
     */
    private static void ended(RunState run, Throwable t, GuardedEvents events, boolean cancelRequested) {
        EventLogException logFailure = events.failure() != null ? events.failure()
                : t instanceof EventLogException thrown ? thrown : null;
        if (logFailure != null) {
            run.setStatus(RunStatus.FAILED);
            run.setError(logFailure.getMessage());
        } else if (cancelRequested) {
            run.setStatus(RunStatus.CANCELLED);
        } else {
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            run.setStatus(RunStatus.FAILED);
            run.setError(t.getClass().getSimpleName() + ": " + detail);
        }
    }

    /**
     * Prepares a fresh run: its input context; {@code false} after failing the run before any block: the workflow is
     * refused ({@link RunAdmission}) or a variable has no value.
     */
    private boolean starts(WorkflowDefinition definition, Map<String, Object> inputs, RunState run,
                           boolean otherPackage, List<String> ofTheRun) {
        return admitted(definition, run, otherPackage, ofTheRun) && initialVariables(definition, inputs, run);
    }

    /**
     * Prepares a resumed run: the steps and the input context of its snapshot, whose texts the run holds again and
     * counts against its {@code budget}; {@code false} after failing the run before any block because the workflow is
     * refused ({@link RunAdmission}).
     */
    private boolean resumes(WorkflowDefinition definition, ResumePoint point, RunState run, OutputBudget budget,
                            List<String> ofTheRun) {
        run.setSteps(new ArrayList<>(point.snapshot().steps()));
        run.getSteps().forEach(step -> budget.hold(OutputBudget.sizeOf(step)));
        run.setInputContext(new HashMap<>(point.snapshot().inputContext()));
        return admitted(definition, run, false, ofTheRun);
    }

    /**
     * Whether the workflow may run; fails the run with the refusal if not.
     *
     * @param ofTheRun the reasons of this run: a refused pick of a model choice, a model the operator does not allow
     */
    private boolean admitted(WorkflowDefinition definition, RunState run, boolean otherPackage,
                             List<String> ofTheRun) {
        String refusal = admission.refusal(definition, otherPackage, ofTheRun);
        if (refusal != null) {
            run.setStatus(RunStatus.FAILED);
            run.setError(refusal);
            return false;
        }
        return true;
    }

    /**
     * Sets the run's input context: the workflow's default context merged with the inputs; {@code false} after failing
     * the run when that is not possible: a variable without a value fails the run before any block.
     */
    private static boolean initialVariables(WorkflowDefinition definition, Map<String, Object> inputs,
                                            RunState run) {
        try {
            Map<String, Object> merged = new HashMap<>(definition.defaultContext());
            if (inputs != null) {
                merged.putAll(inputs);
            }
            merged.forEach((name, value) -> {
                if (value == null) {
                    throw new IllegalArgumentException("variable '" + name + "' has no value");
                }
            });
            run.setInputContext(new HashMap<>(Map.copyOf(merged)));   // copyOf: a variable without a name fails too
            return true;
        } catch (RuntimeException e) {
            run.setStatus(RunStatus.FAILED);
            run.setError("Failed to initialize workflow data: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            return false;
        }
    }

    /** The block loop, from the block at {@code firstPosition} on. */
    private void executeBlocks(WorkflowDefinition definition, int firstPosition, RunState run,
                               ExecutionContext context, RunOptions options, GuardedEvents events) {
        BlockStepRunner stepRunner = new BlockStepRunner(new BlockOutputValidator(),
                new RunIfGate(options.commandRunner(), options.clock()), new StepAiPreview(options.aiProviders()),
                options.clock());
        List<BlockDefinition> blocks = definition.blocks();
        for (int position = firstPosition; position < blocks.size(); position++) {
            BlockDefinition block = blocks.get(position);

            if (context.shouldStop()) {
                run.setStatus(RunStatus.CANCELLED);
                break;
            }

            if (context.getOutputBudget().usedUp()) {
                // A child whose parent set a value beyond the budget for it, or a resume beyond a lowered budget
                run.setStatus(RunStatus.FAILED);
                run.setError(context.getOutputBudget().usedUpNote());
                break;
            }

            if (options.parkOnPause()) {
                // The checkpoint before this block holds everything a resume needs. A child run has none, and nobody
                // in this process would let it go on: it neither parks nor waits, its parent parks after its step.
                if (options.checkpoints() && context.isPaused()) {
                    run.setStatus(RunStatus.PARKED);
                    return;
                }
            } else if (!waitWhilePaused(run, context, events) || context.shouldStop()) {
                run.setStatus(RunStatus.CANCELLED);   // interrupted or cancelled while paused: no further block starts
                break;
            }

            BlockExecutor executor = blockTypes.executorFor(block.getType());

            // The step is reported as running before the block starts
            int stepIndex = context.preAllocateStepIndex();
            context.parkQuestionOf(parks(block, options) ? stepIndex : ExecutionContext.NO_STEP);
            context.pauseParksItemsOf(options.parkOnPause() && options.checkpoints()
                    && LOOP_TYPE.equals(block.getType()) ? stepIndex : ExecutionContext.NO_STEP);
            StepResult step = stepRunner.startStep(run, stepIndex, block, position, context);
            stepRunner.runStep(step, block, executor, context);
            events.throwIfFailed();   // a block executor may have caught a failed write

            // A cancel wins over parking: one that came while the question parked ends the run here
            if (context.shouldStop()) {
                run.setStatus(RunStatus.CANCELLED);
                break;
            }

            if (context.parkedRequest() != null || context.pausedInStep() != ExecutionContext.NO_STEP) {
                // The question waits for a person outside the process, or a pause stopped the step's loop between
                // two items: the run has not ended, so no completion
                run.setStatus(RunStatus.PARKED);
                return;
            }

            // A step the output budget failed ends the run whatever on_failure says: each further step would hold more
            if (step.getStatus() == StepStatus.FAILED
                    && (block.shouldStopOnFailure() || context.getOutputBudget().exceeded())) {
                run.setStatus(RunStatus.FAILED);
                // Where the step failed first: a foreach names its failed items, else the output tail
                run.setError(StepFailureSummary.runError(step, block.getType(), context.getSecrets().values()));
                break;
            }
            checkpoint(position + 1, run, context, options, events);
        }
        completionHandler.complete(run, context, definition);
    }

    /** Whether a question of {@code block} parks the run: a top-level question, when the options ask for parking. */
    private static boolean parks(BlockDefinition block, RunOptions options) {
        return options.parkAtQuestions() && QUESTION_TYPES.contains(block.getType());
    }

    /**
     * Reports the run's state before the block at {@code nextPosition}, when the options ask for checkpoints: its
     * context, its steps so far and its input context.
     */
    private static void checkpoint(int nextPosition, RunState run, ExecutionContext context, RunOptions options,
                                   RunEvents events) {
        if (options.checkpoints()) {
            events.emit(new Checkpoint(nextPosition,
                    new RunSnapshot(context.state(), run.getSteps(), run.getInputContext())));
        }
    }

    /**
     * Parks the block loop while the run is paused: PAUSED is reported once, then the thread polls until the run is
     * resumed or cancelled and reports RUNNING again after a resume.
     *
     * @return false when the thread was interrupted while it waited: the run ends, and the thread keeps its interrupt
     *         flag
     */
    private static boolean waitWhilePaused(RunState run, ExecutionContext context, RunEvents events) {
        if (!context.isPaused() || context.shouldStop()) {
            return true;
        }
        run.setStatus(RunStatus.PAUSED);
        events.emit(new RunStatusChanged(RunStatus.PAUSED));
        while (context.isPaused() && !context.shouldStop()) {
            try {
                Thread.sleep(PAUSE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (!context.shouldStop()) {
            run.setStatus(RunStatus.RUNNING);
            events.emit(new RunStatusChanged(RunStatus.RUNNING));
        }
        return true;
    }

    /**
     * Reports the end of the run, or that it parked, and returns its outcome; throws {@link EventLogException} if the
     * report fails. A parked run has no gate yet: the completion that writes it did not run.
     */
    private static RunOutcome finish(RunState run, ExecutionContext context, RunEvents events) {
        if (run.getStatus() == RunStatus.PARKED) {
            ApprovalRequest parked = context.parkedRequest();
            RunOutcome outcome = new RunOutcome(RunStatus.PARKED, run.getSteps(), Map.of(), null);
            // At a question, or by a pause: inside a loop (its step is not finished) or between two blocks (no step)
            events.emit(parked != null ? new RunParked(parked.getStepIndex(), parked.getType())
                    : new RunParked(context.pausedInStep(), RunParked.PAUSE));
            return outcome;
        }
        Map<String, Object> gate = new LinkedHashMap<>();
        if (context != null) {
            for (String key : GateAggregation.METADATA_KEYS) {
                Object value = context.getRunMetadata().get(key);
                if (value != null) {
                    gate.put(key, value);
                }
            }
        }
        RunOutcome outcome = new RunOutcome(run.getStatus(), run.getSteps(), gate, run.getError());
        events.emit(new RunFinished(outcome.status(), outcome.gate(), outcome.error()));
        return outcome;
    }
}
