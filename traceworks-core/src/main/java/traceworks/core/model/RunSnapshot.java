package traceworks.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A run between two top-level blocks, complete enough to continue it in another process: what a checkpoint event
 * ({@code RunEvent.Checkpoint}) carries and {@code WorkflowEngine.resume} starts from. Plain data, so a port and an
 * adapter can hold it; {@code RunSnapshotJson} writes it to the run record and reads it back.
 *
 * @param context      the run's context state: variables, taint, block outputs, step indices, run metadata and
 *                     remembered approvals
 * @param steps        the top-level steps that ran so far, in order; a read-only list
 * @param inputContext the variables the run started with: the workflow's default context merged with the inputs
 */
public record RunSnapshot(ContextState context, List<StepResult> steps, Map<String, Object> inputContext) {

    public RunSnapshot {
        Objects.requireNonNull(context, "context");
        steps = steps == null ? List.of() : List.copyOf(steps);
        inputContext = inputContext == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputContext));
    }
}
