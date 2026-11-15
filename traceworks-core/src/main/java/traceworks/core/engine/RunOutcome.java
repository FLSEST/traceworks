package traceworks.core.engine;

import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a run ended: what {@link WorkflowEngine#run} returns once the run is over. It holds the same facts as the
 * run's last event, {@code RunFinished} (or {@code RunParked}), plus the top-level steps.
 *
 * @param status the final status: {@code COMPLETED}, {@code COMPLETED_WITH_ERRORS}, {@code FAILED} or
 *               {@code CANCELLED}; {@code PARKED} for a run that waits for a person outside the process, with no gate
 *               and no error
 * @param steps  the top-level steps in the order they ran; a read-only list
 * @param gate   the run's gate verdict ({@code gate_status}, {@code gate_category}, {@code gate_description},
 *               {@code gate_summary}); empty when no step reported one. A gate never changes the status.
 * @param error  why the run failed, such as {@code "Step 2 (shell) failed: ..."}; {@code null} otherwise
 */
public record RunOutcome(RunStatus status, List<StepResult> steps, Map<String, Object> gate, String error) {

    public RunOutcome {
        steps = steps == null ? List.of() : List.copyOf(steps);
        gate = gate == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(gate));
    }
}
