package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.BlockTypeRegistry;
import traceworks.core.engine.PlatformVariables;
import traceworks.core.engine.RunOptions;
import traceworks.core.engine.WorkflowEngine;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * foreach's {@code context} may not forge a value the engine sets for every run: every variable a run starts with
 * besides its inputs is refused, so a new one cannot slip past. Pinned against the engine itself, which is the one
 * that sets them.
 */
class SubWorkflowReservedNamesTest {

    @Test
    void everyVariableTheEngineSetsIsReserved() {
        Set<String> setByTheEngine = new HashSet<>();
        BlockExecutor probe = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                setByTheEngine.addAll(context.getVariables().keySet());
                return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED).build();
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of("probe");
            }
        };
        WorkflowDefinition workflow = new WorkflowDefinition("wf", "Probe", null,
                List.of(BlockDefinition.builder().type("probe").config(new HashMap<>()).build()), null, null, null,
                null, Map.of(), Map.of(), null, null, null);

        new WorkflowEngine(new BlockTypeRegistry(List.of(probe))).run(workflow, Map.of("feature_id", "x"),
                RunOptions.builder().runId("run-1").build());

        setByTheEngine.remove("feature_id");
        assertThat(setByTheEngine).isNotEmpty();
        for (String name : setByTheEngine) {
            assertThat(SubWorkflowLauncher.reservedReason(name, "item", PlatformVariables.NAMES))
                    .as(name).isNotNull();
        }
        assertThat(SubWorkflowLauncher.reservedReason("previous_output", "item", Set.of())).isNotNull();
        assertThat(SubWorkflowLauncher.reservedReason("feature_id", "item", Set.of())).isNull();
        assertThat(SubWorkflowLauncher.reservedReason("item_name", "item", Set.of())).isNull();
    }

    /**
     * One list, both ways: the engine ignores an input exactly when a mapping may not name it as platform data. A
     * mapping to a name the child ignores would be dropped silently; a refused name the child would read would refuse
     * a working workflow.
     */
    @Test
    void theEngineIgnoresAnInput_exactlyWhenAMappingMayNotNameItAsPlatformData() {
        List<String> names = new ArrayList<>(PlatformVariables.NAMES);
        names.addAll(List.of("goal_guard_status", "goal_guard_item_1", "execution_path_main", "feature_id", "goal_id",
                "report", "execution_paths"));
        Map<String, Object> inputs = new HashMap<>();
        names.forEach(name -> inputs.put(name, "forged"));
        Set<String> seen = new HashSet<>();
        BlockExecutor probe = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                seen.addAll(context.getVariables().keySet());
                return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED).build();
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of("probe");
            }
        };
        WorkflowDefinition workflow = new WorkflowDefinition("wf", "Probe", null,
                List.of(BlockDefinition.builder().type("probe").config(new HashMap<>()).build()), null, null, null,
                null, Map.of(), Map.of(), null, null, null);

        new WorkflowEngine(new BlockTypeRegistry(List.of(probe))).run(workflow, inputs,
                RunOptions.builder().runId("run-1").build());

        Set<String> platformNames = new SubWorkflowLauncher().platformVariableNames();
        for (String name : names) {
            boolean ignoredByTheEngine = !seen.contains(name);
            boolean refusedAsPlatformData = SubWorkflowLauncher.reservedReason(name, "item", platformNames) != null;
            assertThat(refusedAsPlatformData).as(name).isEqualTo(ignoredByTheEngine);
        }
        assertThat(seen).as("ordinary names are read").contains("feature_id", "goal_id", "report", "execution_paths");
        assertThat(seen).as("platform names are ignored")
                .doesNotContain("open_todos", "goal_guard_item_1", "execution_path_main");
    }
}
