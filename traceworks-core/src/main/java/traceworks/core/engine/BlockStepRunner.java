package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.context.OutputBudget;
import traceworks.core.context.UntrustedInput;
import traceworks.core.gate.GateStatusOutputParser;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.Clock;
import traceworks.core.port.RunEvent.GateReported;
import traceworks.core.port.RunEvent.OutputRecorded;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.port.RunEvent.StepWaiting;
import traceworks.core.port.RunEvents;
import traceworks.core.util.ProcessTracking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a single top-level block of a workflow: the running step reported at once, the actual execution including
 * output validation and retries, and copying the result into the step, the execution context and the run's events.
 * A step whose work the run's cancel ended is {@code CANCELLED}, not {@code FAILED} ({@link CancelledSteps}).
 *
 * <p>The order inside {@link #runStep} is behaviour: {@code run_if} ({@link RunIfGate}), execute, validation retry
 * loop, final check (all with the run's process tracker bound) → the output budget → step fields → context variables
 * → events. The decision whether a failed step stops the run stays in {@link WorkflowEngine}, because it controls the
 * block loop.
 */
final class BlockStepRunner {

    private final BlockOutputValidator blockOutputValidator;
    private final RunIfGate runIfGate;
    private final StepAiPreview aiPreview;
    private final Clock clock;

    BlockStepRunner(BlockOutputValidator blockOutputValidator, RunIfGate runIfGate, StepAiPreview aiPreview,
                    Clock clock) {
        this.blockOutputValidator = blockOutputValidator;
        this.runIfGate = runIfGate;
        this.aiPreview = aiPreview;
        this.clock = clock;
    }

    /**
     * Creates the RUNNING step, adds it to the run and reports it, so a viewer shows the step at once.
     * {@code blockPosition} is the block's place in the top-level block list, stored as metadata {@code blockIndex}:
     * the step index runs ahead of it once a wrapper block has run (see {@code StepIndexCounter}), so only this key
     * maps a step to its block. A step that will call the AI names provider, model and effort from the start
     * ({@link StepAiPreview}).
     */
    StepResult startStep(RunState run, int stepIndex, BlockDefinition block, int blockPosition,
                         ExecutionContext context) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(clock.now());
        step.setStatus(StepStatus.RUNNING);
        step.setBlockName(block.getName());
        step.setBlockType(block.getType());
        Map<String, Object> metadata = new HashMap<>(aiPreview.of(block, context));
        labelBlock(metadata, block);
        metadata.put("blockIndex", blockPosition);
        step.setMetadata(metadata);
        run.getSteps().add(step);
        context.getEvents().emit(new StepStarted(stepIndex, block.getName(), block.getType(),
                blockPosition, StepModes.usesAi(block)));
        return step;
    }

    /**
     * WAITING_APPROVAL while the run waits for a person, RUNNING again afterwards, reported as
     * {@link StepWaiting}. Runs in the run's own thread. A failing event sink is not swallowed: the failure reaches
     * the waiting block, and the engine stops the run (see {@link WorkflowEngine}).
     */
    void markWaiting(ExecutionContext context, StepResult step, boolean waiting) {
        StepStatus status = waiting ? StepStatus.WAITING_APPROVAL : StepStatus.RUNNING;
        if (step.getStatus() == status) return;
        step.setStatus(status);
        context.getEvents().emit(new StepWaiting(step.getStepIndex(), waiting));
    }

    /**
     * What the run view shows about the step's block: type, name and the step note {@code config.description} as
     * {@code blockDescription}. Written at start and again after the run, which replaces the metadata.
     */
    static void labelBlock(Map<String, Object> metadata, BlockDefinition block) {
        metadata.put("blockType", block.getType());
        if (block.getName() != null && !block.getName().isBlank()) {
            metadata.put("blockName", block.getName());
        }
        Object note = block.getConfig() != null ? block.getConfig().get("description") : null;
        if (note instanceof String text && !text.isBlank()) {
            metadata.put("blockDescription", text);
        }
    }

    /** The output of a step whose question parked the run when a cancel won over the park. */
    static final String CANCELLED_WHILE_PARKING = "cancelled while waiting for an answer";

    /**
     * Executes the block (with validation and retry) and copies the result into {@code step} and the context. A step
     * whose question parked the run ({@link ExecutionContext#parkedRequest()}) keeps its waiting status and reports
     * neither output, gate nor end, unless the run was cancelled meanwhile: then it ends {@code CANCELLED} as a
     * cancelled question. A step inside whose loop a pause parked the run ({@link ExecutionContext#pausedInStep()})
     * stays running the same way; after a cancel it ends {@code CANCELLED}, also when its loop looked at the cancel
     * before it came and reported part of its items as completed. A result the run's cancel ended is
     * {@code CANCELLED} too ({@link CancelledSteps}), unless the output budget fails the step: that is no cancel.
     */
    void runStep(StepResult step, BlockDefinition block, BlockExecutor executor, ExecutionContext context) {
        // The processes the block starts, nested blocks included, are registered with the run so a cancel kills
        // them. While an approval or human input is open the step shows WAITING_APPROVAL, nested blocks included.
        StepResult result;
        context.onApprovalWait(waiting -> markWaiting(context, step, waiting));
        try {
            result = ProcessTracking.bind(context.processTracker(),
                    () -> runIfGate.skipIfUnmet(block, context)
                            .orElseGet(() -> executeValidated(step, block, executor, context)));
        } catch (RuntimeException | Error e) {
            failStep(step, block, context, e);
            throw e;
        } finally {
            context.onApprovalWait(null);
        }
        if (context.parkedRequest() != null) {
            if (!context.shouldStop()) {
                return;   // the step waits for a person outside the process: it has no result yet and does not finish
            }
            // A cancel won over the park: the question was never asked of anyone, the wait was cancelled
            result.setOutput(CANCELLED_WHILE_PARKING);
            result.setStatus(StepStatus.CANCELLED);
        }
        if (context.pausedInStep() != ExecutionContext.NO_STEP) {
            if (!context.shouldStop()) {
                return;   // a pause parked the run inside the step's loop: it has no result yet and does not finish
            }
            // A cancel won over the pause: the loop ran only part of its items, whatever it reported before the cancel
            result.setStatus(StepStatus.CANCELLED);
        }
        CancelledSteps.mark(result, context);
        holdText(result, context);

        step.setInput(result.getInput());
        step.setOutput(result.getOutput());
        step.setStatus(result.getStatus());
        step.setCompletedAt(result.getCompletedAt());
        step.setDurationMs(result.getDurationMs());
        Object blockIndex = step.getMetadata() != null ? step.getMetadata().get("blockIndex") : null;
        Map<String, Object> stepMetadata = result.getMetadata() != null
                ? new HashMap<>(result.getMetadata())
                : new HashMap<>();
        labelBlock(stepMetadata, block);
        if (blockIndex != null) {
            stepMetadata.put("blockIndex", blockIndex);
        }
        // A wrapper in AI mode hands on what a model wrote: its gate markers do not count (GateSources)
        if (GateSources.ignoresMarkers(block)) {
            stepMetadata.put(GateSources.METADATA_KEY, false);
        }
        // A block that reads foreign content: instructions aimed at an AI in its input or output
        if (UntrustedInput.declared(block.getConfig())) {
            List<Map<String, Object>> suspects = new ArrayList<>(UntrustedInput.scan(step.getInput(), "input",
                    context.secretValues()));
            suspects.addAll(UntrustedInput.scan(step.getOutput(), "output", context.secretValues()));
            if (!suspects.isEmpty()) {
                stepMetadata.put(UntrustedInput.METADATA_KEY, suspects);
            }
        }
        step.setMetadata(stepMetadata);
        // recordOutput/setDerivedVariable, not the plain setters: in a run that carries untrusted input the output
        // is tainted and gets shell-escaped downstream.
        context.recordOutput(block.getName(), step.getOutput());
        context.setDerivedVariable(StepOutputs.name(step.getStepIndex()),
                step.getOutput() != null ? step.getOutput() : "");
        context.getSourceSteps().record(block.getName(), step);

        reportFinished(step, block, context.getEvents());
    }

    /**
     * Ends the step of a block whose executor threw (a setting of the wrong type, a refused mount, a failed AI call):
     * {@code FAILED} with the error as its output, recorded like any finished step, so the record never shows it
     * {@code RUNNING} after the run has ended. The engine then ends the run with the same error. After the run's
     * cancel, whatever the exception, the step ends {@code CANCELLED} with {@value CancelledSteps#WHILE_RUNNING} as its
     * output instead: the cancel ended its work. A JVM {@link Error} adds a line that names it
     * ({@link CancelledSteps#whileRunning}), since a cancel does not cause one. A failed record write ({@link EventLogException}) leaves the step as it
     * is: nothing more can be recorded.
     */
    private void failStep(StepResult step, BlockDefinition block, ExecutionContext context, Throwable error) {
        if (error instanceof EventLogException) {
            return;
        }
        if (context.shouldStop()) {
            step.setOutput(CancelledSteps.whileRunning(error));
            step.setStatus(StepStatus.CANCELLED);
        } else {
            String detail = error.getMessage() != null ? error.getMessage() : error.getClass().getName();
            step.setOutput(error.getClass().getSimpleName() + ": " + detail);
            step.setStatus(StepStatus.FAILED);
        }
        step.setCompletedAt(clock.now());
        step.setDurationMs(java.time.Duration.between(step.getStartedAt(), step.getCompletedAt()).toMillis());
        try {
            reportFinished(step, block, context.getEvents());
        } catch (RuntimeException failedWrite) {
            error.addSuppressed(failedWrite);   // the engine sees the failed write through its guarded sink
        }
    }

    /**
     * Counts the step's input and output against the run's {@link OutputBudget}: the run holds them until it ends. A
     * step whose text does not fit fails with the budget's note after its whole output, and so does a step inside which
     * a loop or a child run stopped for the budget. Its run error ends with the note, after the summary of where it
     * failed when it wrote one (a {@code foreach}'s failed items). The engine then ends the run.
     */
    private static void holdText(StepResult result, ExecutionContext context) {
        OutputBudget budget = context.getOutputBudget();
        budget.take(OutputBudget.sizeOf(result));
        if (budget.exceeded()) {
            Object summary = result.getMetadata() != null ? result.getMetadata().get(StepFailureSummary.KEY) : null;
            budget.fail(result);
            StepFailureSummary.put(result, summary instanceof String own && !own.isBlank()
                    ? own + "\n" + budget.note() : budget.note());
        }
    }

    /**
     * The step's output, the gate it reported (unless its output is no gate source, see {@link GateSources}) and its
     * end.
     */
    private static void reportFinished(StepResult step, BlockDefinition block, RunEvents events) {
        events.emit(new OutputRecorded(step.getStepIndex(), block.getName(), step.getOutput()));
        if (GateSources.counts(step)) {
            GateStatusOutputParser.parse(step.getOutput()).ifPresent(gate -> events.emit(new GateReported(
                    step.getStepIndex(), gate.status(), gate.category(), gate.description())));
        }
        events.emit(new StepFinished(step.getStepIndex(), block.getName(), block.getType(), step.getStatus(),
                step.getInput(), step.getDurationMs() != null ? step.getDurationMs() : 0L, step.getMetadata()));
    }

    /**
     * Runs the block, re-running it while output validation fails and retries are configured. A retry runs the whole
     * block again, under the step's own index: a fresh index would leave a gap, so later blocks'
     * {@code {{ step_N_output }}} and the events would carry a foreign index. How often it ran and why each attempt
     * was rejected goes into the step metadata.
     */
    private StepResult executeValidated(StepResult step, BlockDefinition block, BlockExecutor executor,
                                        ExecutionContext context) {
        blockOutputValidator.readSettings(block);   // a wrong validation fails the block before it runs
        StepResult result = executor.execute(block, context);
        if (context.pausedInStep() != ExecutionContext.NO_STEP) {
            return result;   // the loop stopped for a pause: what it has is no output to validate or retry
        }
        int validationAttempts = 0;
        int maxValidationAttempts = blockOutputValidator.maxRetries(block);
        List<String> rejections = new ArrayList<>();
        while (result.getStatus() == StepStatus.COMPLETED && validationAttempts < maxValidationAttempts) {
            var validation = blockOutputValidator.validate(block, result.getOutput());
            if (validation.passed()) break;
            if (!blockOutputValidator.shouldRetry(block)) {
                result.setStatus(StepStatus.FAILED);
                String existing = result.getOutput() == null ? "" : result.getOutput();
                result.setOutput(existing + "\n[VALIDATION] " + validation.reason());
                break;
            }
            rejections.add(validation.reason());
            validationAttempts++;
            context.getStepIndices().rewindTo(step.getStepIndex());
            result = executor.execute(block, context);
        }
        if (result.getStatus() == StepStatus.COMPLETED) {
            var finalCheck = blockOutputValidator.validate(block, result.getOutput());
            if (!finalCheck.passed()) {
                result.setStatus(StepStatus.FAILED);
                String existing = result.getOutput() == null ? "" : result.getOutput();
                result.setOutput(existing + "\n[VALIDATION] failed after " + validationAttempts + " retries: " + finalCheck.reason());
                rejections.add(finalCheck.reason());
            }
        }
        if (validationAttempts > 0) {
            Map<String, Object> metadata = result.getMetadata() != null
                    ? new HashMap<>(result.getMetadata())
                    : new HashMap<>();
            metadata.put("validationAttempts", validationAttempts + 1);
            metadata.put("validationFailures", List.copyOf(rejections));
            result.setMetadata(metadata);
        }
        return result;
    }
}
