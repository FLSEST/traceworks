package traceworks.core.engine;

import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The gate of a run that ends outside the engine, from the steps of its last checkpoint. */
class RunGateTest {

    @Test
    void stepsWithGateMarkers_giveTheVerdictAsCompletionWritesIt() {
        List<StepResult> steps = List.of(
                step(0, "gate_status: GREEN\ngate_category: build\ngate_description: built"),
                step(1, "gate_status: YELLOW\ngate_category: lint\ngate_description: 3 warnings"));

        Map<String, Object> gate = RunGate.ofSteps(steps);

        assertThat(gate).containsEntry("gate_status", "YELLOW").containsEntry("gate_category", "lint")
                .containsEntry("gate_description", "3 warnings");
        assertThat(gate.keySet()).containsExactly("gate_status", "gate_category", "gate_description",
                "gate_summary");
        assertThat(gate.get("gate_summary")).isInstanceOfSatisfying(Map.class,
                summary -> assertThat(summary).containsEntry("highest", "YELLOW"));
    }

    @Test
    void stepsWithoutGateMarkers_giveNoVerdict_notEvenAMissingOne() {
        assertThat(RunGate.ofSteps(List.of(step(0, "built")))).isEmpty();
        assertThat(RunGate.ofSteps(List.of())).isEmpty();
    }

    private static StepResult step(int index, String output) {
        return StepResult.builder().stepIndex(index).output(output).status(StepStatus.COMPLETED).build();
    }
}
