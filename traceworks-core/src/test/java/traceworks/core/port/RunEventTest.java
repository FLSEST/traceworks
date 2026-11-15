package traceworks.core.port;

import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.GateStatus;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent.AgenticProgress;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ApprovalRequested;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import traceworks.core.port.RunEvent.GateReported;
import traceworks.core.port.RunEvent.OutputRecorded;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunParked;
import traceworks.core.port.RunEvent.RunStatusChanged;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.port.RunEvent.StepWaiting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEventTest {

    @Test
    void everyEventReachesTheSink_inOrder() {
        List<RunEvent> emitted = new ArrayList<>();
        RunEvents events = emitted::add;

        events.emit(new StepStarted(0, "tests", "shell", 0, false));
        events.emit(new StepWaiting(0, true));
        events.emit(new StepWaiting(0, false));
        events.emit(new ForEachItemStarted(0, 0, "a.txt", 1));
        events.emit(new ForEachItemFinished(0, 0, "a.txt", 1, StepStatus.COMPLETED, "linted", null));
        events.emit(new AgenticProgress(0, "agentic_reasoned", Map.of("iteration", 1)));
        events.emit(new ChildRunEvent("child-run", new StepStarted(0, "build", "shell", 0, false)));
        events.emit(new OutputRecorded(0, "tests", "ok"));
        events.emit(new GateReported(0, GateStatus.GREEN, "tests", "all green"));
        events.emit(new StepFinished(0, "tests", "shell", StepStatus.COMPLETED, "mvn -q verify", 1200L, Map.of()));
        events.emit(new Checkpoint(1, emptySnapshot()));
        events.emit(new RunStatusChanged(RunStatus.PAUSED));
        events.emit(new RunParked(1, "approval"));
        events.emit(new RunFinished(RunStatus.COMPLETED, Map.of("gate_status", "GREEN"), null));

        assertThat(emitted).extracting(RunEventTest::kind)
                .containsExactly("step started", "step waiting", "step waiting", "foreach item started",
                        "foreach item finished", "agentic progress", "child run event", "output", "gate",
                        "step finished", "checkpoint", "run status", "run parked", "run finished");
    }

    @Test
    void runFinished_copiesTheGate_keepsNullValuesAndTreatsAbsentAsEmpty() {
        Map<String, Object> gate = new HashMap<>(Map.of("gate_status", "RED"));
        gate.put("gate_description", null);
        RunFinished finished = new RunFinished(RunStatus.FAILED, gate, "Step 0 (shell) failed: boom");

        gate.put("gate_category", "late");

        assertThat(finished.gate()).containsOnlyKeys("gate_status", "gate_description");
        assertThat(new RunFinished(RunStatus.COMPLETED, null, null).gate()).isEmpty();
    }

    @Test
    void approvalRequested_withoutOptions_hasEmptyLists() {
        ApprovalRequested requested = new ApprovalRequested(2, "approval-2", "approval", "Deploy?", null, null,
                null, null, false, null, null, null);

        assertThat(requested.options()).isEmpty();
        assertThat(requested.rejectOptions()).isEmpty();
        assertThat(requested.fields()).isNull();
    }

    @Test
    void approvalRequested_copiesItsLists() {
        List<String> options = new ArrayList<>(List.of("Yes", "No"));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(Map.of("variable_name", "reason")));
        ApprovalRequested requested = new ApprovalRequested(1, "human-input-1", "human-input", "Why?", options,
                List.of("No"), "text", "", true, null, "user_input", fields);

        options.add("Maybe");
        fields.clear();

        assertThat(requested.options()).containsExactly("Yes", "No");
        assertThat(requested.rejectOptions()).containsExactly("No");
        assertThat(requested.fields()).containsExactly(Map.of("variable_name", "reason"));
    }

    @Test
    void agenticProgress_copiesItsData_inOrder_andTreatsAbsentAsEmpty() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("iteration", 2);
        data.put("action", "make");
        AgenticProgress progress = new AgenticProgress(1, "agentic_acting", data);

        data.put("late", true);

        assertThat(progress.data().keySet()).containsExactly("iteration", "action");
        assertThat(progress.data()).isUnmodifiable();
        assertThat(new AgenticProgress(1, "agentic_started", null).data()).isEmpty();
    }

    @Test
    void childRunEvent_namesTheChildRun_andRequiresBoth() {
        ChildRunEvent wrapped = new ChildRunEvent("child-run", new RunFinished(RunStatus.COMPLETED, null, null));

        assertThat(wrapped.runId()).isEqualTo("child-run");
        assertThat(wrapped.event()).isInstanceOf(RunFinished.class);
        assertThatThrownBy(() -> new ChildRunEvent(null, wrapped.event()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChildRunEvent("child-run", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void checkpoint_requiresASnapshot() {
        assertThatThrownBy(() -> new Checkpoint(0, null)).isInstanceOf(NullPointerException.class);
    }

    private static RunSnapshot emptySnapshot() {
        return new RunSnapshot(new ContextState(Map.of(),
                new ContextState.Taint(Set.of(), Set.of(), Set.of(), false, false, false), Map.of(), null,
                new ContextState.SourceSteps(Map.of(), Set.of(), null), 0, Map.of(), Map.of()), List.of(), Map.of());
    }

    /** The switch has no default branch: it only compiles while it covers every event type. */
    private static String kind(RunEvent event) {
        return switch (event) {
            case StepStarted e -> "step started";
            case StepFinished e -> "step finished";
            case OutputRecorded e -> "output";
            case GateReported e -> "gate";
            case ApprovalRequested e -> "approval requested";
            case ApprovalAnswered e -> "approval answered";
            case StepWaiting e -> "step waiting";
            case ForEachItemStarted e -> "foreach item started";
            case ForEachItemFinished e -> "foreach item finished";
            case AgenticProgress e -> "agentic progress";
            case ChildRunEvent e -> "child run event";
            case Checkpoint e -> "checkpoint";
            case RunStatusChanged e -> "run status";
            case RunParked e -> "run parked";
            case RunFinished e -> "run finished";
        };
    }
}
