package traceworks.core.context;

import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.WorkflowDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Starts another workflow as a child of the running run: the {@code sub-workflow} block and {@code foreach} with
 * {@code workflow_id} use it. The engine gives every run's context one; a context built without one refuses every
 * child.
 *
 * <p>The engine runs the child in-process, on the calling thread, through itself: with its own run id, the trigger
 * source {@code SUB_WORKFLOW}, the parent's ports, working directory and run control (a cancel or a pause reaches the
 * child), and its events delivered into the parent's event sink, each wrapped as a {@code ChildRunEvent} with the
 * child's run id. Depth limit, cycle check and the hand-over of variables and taint are the caller's job
 * ({@code core.blocks.SubWorkflowLauncher}).
 */
@FunctionalInterface
public interface ChildRuns {

    /**
     * Runs {@code definition} as a child and returns once it has ended.
     *
     * @param inputs the child's inputs: what the parent hands over, its taint keys included
     * @return how the child ended
     * @throws RuntimeException an {@code EventLogException} when the child's end could not be recorded; the caller
     *                          lets it propagate, so the parent fails closed
     */
    ChildRun run(WorkflowDefinition definition, Map<String, Object> inputs);

    /**
     * How a child run ended.
     *
     * @param runId  the child's run id
     * @param status its final status
     * @param steps  its top-level steps in the order they ran; a read-only list
     * @param error  why it failed, {@code null} otherwise
     * @param modelAsked whether it asked a model, itself or through a child of its own: its output may then hold
     *                   text a model wrote, and the parent quotes it in a shell line
     */
    record ChildRun(String runId, RunStatus status, List<StepResult> steps, String error, boolean modelAsked) {
        public ChildRun {
            Objects.requireNonNull(runId, "runId");
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        /** A child run that asked no model. */
        public ChildRun(String runId, RunStatus status, List<StepResult> steps, String error) {
            this(runId, status, steps, error, false);
        }
    }
}
