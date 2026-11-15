package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.gate.GateExpectation;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;

import java.util.List;

/**
 * Post-processes a finished block loop: writes the gate verdict ({@link GateAggregation}, with the gate-missing
 * fallback and the untrusted-instruction findings) into the context's run metadata and resolves RUNNING to
 * COMPLETED or COMPLETED_WITH_ERRORS. A gate never changes the run status.
 */
final class RunCompletionHandler {

    /** Category of an expected but missing gate verdict. */
    static final String GATE_MISSING_CATEGORY = "gate-missing";
    /** Category of a step with {@code untrusted_input} that found an instruction aimed at an AI. */
    static final String UNTRUSTED_INSTRUCTION_CATEGORY = "untrusted-instruction";
    static final String GATE_MISSING_DESCRIPTION = "The workflow expects a gate verdict, the run delivered none";

    void complete(RunState run, ExecutionContext context, WorkflowDefinition definition) {
        GateAggregation gate = GateAggregation.of(markerSteps(run.getSteps()));
        if (gate != null) {
            gate.writeTo(context);
        }

        boolean failedChecks = hasFailedCheckedCommand(run);
        resolveFinalStatus(run, failedChecks);
        boolean runSucceeded = run.getStatus() == RunStatus.COMPLETED
                || run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS;

        // An expected but missing verdict is not a success: a check workflow whose AI step fails silently runs
        // through and could otherwise not be told apart from a workflow without a gate. Only for a successful run;
        // a FAILED one already carries its verdict in the run status.
        if (gate == null && runSucceeded && GateExpectation.isExpected(definition)) {
            gate = GateAggregation.missing();
            gate.writeTo(context);
        }
        GateAggregation flagged = GateAggregation.withUntrustedInstructions(gate, run.getSteps());
        if (flagged != gate) {
            flagged.writeTo(context);
        }
    }

    private static boolean hasFailedCheckedCommand(RunState run) {
        return run.getSteps().stream().anyMatch(step ->
                step.getStatus() == StepStatus.FAILED && step.getMetadata() != null
                        && Boolean.TRUE.equals(step.getMetadata().get("checkedCommand")));
    }

    /** A run still RUNNING after the block loop is done: with a failed checked_command it completed with errors. */
    private static void resolveFinalStatus(RunState run, boolean failedChecks) {
        if (run.getStatus() == RunStatus.RUNNING) {
            run.setStatus(failedChecks ? RunStatus.COMPLETED_WITH_ERRORS : RunStatus.COMPLETED);
        }
    }

    /** The steps whose output may set the run's gate ({@link GateSources}). */
    static List<StepResult> markerSteps(List<StepResult> steps) {
        return steps.stream().filter(GateSources::counts).toList();
    }
}
