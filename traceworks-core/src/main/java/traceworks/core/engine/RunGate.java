package traceworks.core.engine;

import traceworks.core.model.StepResult;

import java.util.List;
import java.util.Map;

/**
 * The gate verdict of a run that ends outside the engine: a parked or interrupted run that is cancelled. It is
 * computed from the steps of the run's last checkpoint as run completion computes it ({@link RunCompletionHandler}),
 * because the run metadata of a checkpoint holds no verdict yet. The stand-in for an expected but missing verdict
 * belongs to a successful run only, so it is not part of it.
 */
public final class RunGate {

    private RunGate() {
    }

    /**
     * @param steps the run's top-level steps so far
     * @return {@code gate_status}, {@code gate_category}, {@code gate_description} and {@code gate_summary} as run
     *         completion writes them into the run metadata; empty when no step reported a gate
     */
    public static Map<String, Object> ofSteps(List<StepResult> steps) {
        GateAggregation gate = GateAggregation.withUntrustedInstructions(
                GateAggregation.of(RunCompletionHandler.markerSteps(steps)), steps);
        return gate == null ? Map.of() : gate.metadata();
    }
}
