package traceworks.core.engine;

import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The run while the engine executes it: status, steps so far and error. It is held in memory only; nothing here is
 * persisted, the events are the record.
 */
final class RunState {

    private final String id;
    private RunStatus status;
    private List<StepResult> steps = new ArrayList<>();
    private String error;
    /** The variables the run started with: the workflow's default context merged with the inputs. */
    private Map<String, Object> inputContext;

    RunState(String id, RunStatus status) {
        this.id = id;
        this.status = status;
    }

    String getId() { return id; }
    RunStatus getStatus() { return status; }
    void setStatus(RunStatus status) { this.status = status; }
    List<StepResult> getSteps() { return steps; }
    void setSteps(List<StepResult> steps) { this.steps = steps; }
    String getError() { return error; }
    void setError(String error) { this.error = error; }
    Map<String, Object> getInputContext() { return inputContext; }
    void setInputContext(Map<String, Object> inputContext) { this.inputContext = inputContext; }
}
