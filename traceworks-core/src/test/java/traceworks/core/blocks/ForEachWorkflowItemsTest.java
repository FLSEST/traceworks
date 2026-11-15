package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.StepFailureSummary;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * foreach with {@code workflow_id}: one sub-workflow run per item. It runs no command per item, so it needs no
 * {@code execution_mode}. The test {@code jsonFieldsWorkInTheCommandToo} covers the command path and lives in
 * {@link ForEachBlockExecutorTest}.
 */
@ExtendWith(MockitoExtension.class)
class ForEachWorkflowItemsTest {

    @Mock private WrapperCommands commands;
    @Mock private SubWorkflowLauncher launcher;

    private ForEachBlockExecutor executor;
    private ExecutionContext context;
    private final List<RunEvent> events = new ArrayList<>();
    /** Variables the parent context held at each child start; the context changes afterwards. */
    private final List<Map<String, Object>> seenByChild = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = new ForEachBlockExecutor(commands, launcher);
        context = ExecutionContext.builder().workflowRunId("run-1").events(events::add).build();
        context.setProjectPath("/test/project");
    }

    private void childRuns(boolean... success) {
        int[] call = {0};
        when(launcher.run(anyString(), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(1);
            seenByChild.add(new HashMap<>(ctx.getVariables()));
            int n = call[0]++;
            boolean ok = success[Math.min(n, success.length - 1)];
            return new SubWorkflowLauncher.Outcome(inv.getArgument(0), "Child", "sub-run-" + n,
                    ok ? RunStatus.COMPLETED : RunStatus.FAILED, ok ? "done " + n : "broken " + n, ok);
        });
    }

    private static BlockDefinition foreach(Object items, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("items", items);
        config.put("variable_name", "feature");
        config.put("workflow_id", "wf-child");
        config.putAll(extra);
        return BlockDefinition.builder().type("foreach").config(config).build();
    }

    @Test
    void runsOneChildPerItem_withItemIndexAndJsonFields_andNoCommand() {
        childRuns(true);

        StepResult result = executor.execute(foreach(List.of("{\"id\": \"auth\", \"desc\": \"Login\"}", "plain"),
                Map.of("context", Map.of("feature_id", "{{ feature.id }}"))), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(launcher, times(2)).run(eq("wf-child"), any(ExecutionContext.class));
        verify(commands, never()).aiStep(any(), any());
        assertThat(seenByChild.get(0))
                .containsEntry("feature", "{\"id\": \"auth\", \"desc\": \"Login\"}")
                .containsEntry("feature_index", 0)
                .containsEntry("feature.id", "auth")
                .containsEntry("feature.desc", "Login")
                .containsEntry("feature_id", "auth");
        assertThat(seenByChild.get(1)).containsEntry("feature", "plain").containsEntry("feature_index", 1)
                .doesNotContainKey("feature.id")
                .as("an unresolvable mapping stays literal, as everywhere else")
                .containsEntry("feature_id", "{{ feature.id }}");
        assertThat(result.getOutput()).contains("[plain]: done 1");
    }

    @Test
    void recordsTheChildIdAsSubWorkflowId_andTheReferenceAsSubWorkflowRef() {
        when(launcher.run(any(), any())).thenReturn(new SubWorkflowLauncher.Outcome(
                "child-uuid", "Child", "sub-run-0", RunStatus.COMPLETED, "done", true));

        StepResult result = executor.execute(foreach(List.of("a", "b"), Map.of("workflow_id", "../child")), context);

        assertThat(result.getMetadata()).containsEntry("subWorkflowId", "child-uuid")
                .containsEntry("subWorkflowRef", "../child");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsTheChildRunPerItem_andMarksTheOutputAsCopied() {
        childRuns(true, false);

        StepResult result = executor.execute(foreach(List.of("a", "b"), Map.of("continue_on_error", true)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("derivedOutput", true).containsEntry("subWorkflowId", "wf-child")
                .containsEntry("subWorkflowRef", "wf-child");
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(items).extracting(m -> m.get("subRunId")).containsExactly("sub-run-0", "sub-run-1");
        assertThat(items).extracting(m -> m.get("status")).containsExactly("COMPLETED", "FAILED");
        assertThat(events).filteredOn(ForEachItemFinished.class::isInstance)
                .extracting(e -> ((ForEachItemFinished) e).subRunId()).containsExactly("sub-run-0", "sub-run-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChildCompletedWithErrors_isASuccessfulItem_thatCarriesTheWarning() {
        // same mapping as the sub-workflow block: both go through SubWorkflowLauncher.Outcome
        when(launcher.run(anyString(), any(ExecutionContext.class))).thenReturn(new SubWorkflowLauncher.Outcome(
                "wf-child", "Child", "sub-run-0", RunStatus.COMPLETED_WITH_ERRORS, "done, one check red", true));

        StepResult result = executor.execute(foreach(List.of("a"), Map.of()), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(items.get(0))
                .containsEntry("status", "COMPLETED")
                .containsEntry("subRunWarning",
                        "Sub-workflow run ended COMPLETED_WITH_ERRORS: a checked step failed in it, see the child run");
    }

    @Test
    void failedChildren_countTowardsMaxConsecutiveFailures() {
        childRuns(false);

        StepResult result = executor.execute(foreach(List.of("a", "b", "c", "d", "e"), Map.of()), context);

        verify(launcher, times(3)).run(anyString(), any(ExecutionContext.class));
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("skippedItemCount", 2);
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY))
                .startsWith("stopped after 3 consecutive failed items")
                .contains("[a]: broken 0");
    }

    @Test
    void workflowIdFromATrustedVariable_isResolved() {
        context.setVariable("feature_workflow_id", "wf-from-context");
        childRuns(true);

        executor.execute(foreach(List.of("a"), Map.of("workflow_id", "{{ feature_workflow_id }}")), context);

        verify(launcher).run(eq("wf-from-context"), any(ExecutionContext.class));
    }

    @Test
    void workflowIdFromAnUntrustedVariable_isRefusedBeforeTheFirstItem() {
        context.setUntrustedVariable("target", "wf-any");

        StepResult result = executor.execute(foreach(List.of("a"), Map.of("workflow_id", "{{ target }}")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("trusted context variables").contains("{{ target }}");
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
    }

    @Test
    void workflowIdThatDoesNotResolve_failsTheBlockWithTheReason() {
        StepResult result = executor.execute(foreach(List.of("a"), Map.of("workflow_id", "{{ missing }}")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY)).contains("does not resolve");
        assertThat(result.getMetadata()).containsEntry("totalItems", 1);
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
    }

    @Test
    void itemsFromATaintedCommand_reachTheChildAsUntrusted() {
        context.setUntrustedVariable("branch", "main");
        when(commands.runCommand(eq("list"), anyInt(), any())).thenReturn(new ShellResult("{\"id\": \"x; rm\"}\n", 0, 0));
        childRuns(true);
        Map<String, Object> config = new HashMap<>(Map.of("items_source", "command", "items_command", "list",
                "variable_name", "feature", "workflow_id", "wf-child",
                "context", Map.of("feature_id", "{{ feature.id }}")));

        executor.execute(BlockDefinition.builder().type("foreach").config(config).build(), context);

        assertThat(context.isUntrusted("feature")).isTrue();
        assertThat(context.isUntrusted("feature.id")).isTrue();
        assertThat(context.isUntrusted("feature_id")).isTrue();
        assertThat(seenByChild.get(0)).containsEntry("feature_id", "x; rm");
    }

    @Test
    void commandAndWorkflowIdTogether_failTheBlock() {
        StepResult result = executor.execute(foreach(List.of("a"), Map.of("command", "echo {{ feature }}")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("command and workflow_id exclude each other");
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
        verify(commands, never()).aiStep(any(), any());
    }

    /** {@code context} must not overwrite what the platform or the loop itself owns in the child. */
    @Test
    void contextMappingToAReservedName_failsTheBlockBeforeTheFirstItem() {
        when(launcher.platformVariableNames()).thenReturn(Set.of("open_todos"));
        for (String name : List.of("__parent_workflow_ids", "__untrusted_keys", "open_todos", "previous_output",
                "feature", "feature_index", "feature.id", "workflow_id", "workflow_run_id", "trigger_source")) {
            StepResult result = executor.execute(foreach(List.of("a"), Map.of("context", Map.of(name, "x"))), context);

            assertThat(result.getStatus()).as(name).isEqualTo(StepStatus.FAILED);
            assertThat(result.getOutput()).as(name).contains("context").contains(name);
        }
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
    }
}
