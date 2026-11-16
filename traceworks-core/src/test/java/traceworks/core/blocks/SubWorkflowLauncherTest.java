package traceworks.core.blocks;

import traceworks.core.context.ChildRuns;
import traceworks.core.context.ChildRuns.ChildRun;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.PlatformVariables;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Starting one child run. These tests mock the run's {@link WorkflowResolver} and {@link ChildRuns}: the child runs
 * in-process and the launcher gets its end back directly.
 */
@ExtendWith(MockitoExtension.class)
class SubWorkflowLauncherTest {

    private static final Set<String> PLATFORM = Set.of("open_todos", "previous_report");

    @Mock private WorkflowResolver resolver;
    @Mock private ChildRuns childRuns;

    private SubWorkflowLauncher launcher;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        launcher = new SubWorkflowLauncher();
        context = ExecutionContext.builder().workflowRunId("parent-run")
                .workflowResolver(resolver).childRuns(childRuns).build();
    }

    private WorkflowDefinition child() {
        WorkflowDefinition child = new WorkflowDefinition("child", "Child", null, List.of(), null, null, null, null,
                Map.of(), Map.of(), null, null, null);
        when(resolver.resolve("child")).thenReturn(child);
        return child;
    }

    private void childEnds(WorkflowDefinition child, RunStatus status, StepResult... steps) {
        when(childRuns.run(eq(child), anyMap())).thenReturn(new ChildRun("child-run", status, List.of(steps), null));
    }

    @Nested
    class OutcomeRecord {

        @Test
        void should_markARefusedOutcome_asNotStartedAndUnsuccessful() {
            SubWorkflowLauncher.Outcome refused = SubWorkflowLauncher.Outcome.refused("w1", "why");

            assertThat(refused.workflowId()).isEqualTo("w1");
            assertThat(refused.output()).isEqualTo("why");
            assertThat(refused.workflowName()).isNull();
            assertThat(refused.subRunStatus()).isNull();
            assertThat(refused.success()).isFalse();
            assertThat(refused.started()).isFalse();
        }

        @Test
        void should_countAsStarted_onceAChildRunExists() {
            var outcome = new SubWorkflowLauncher.Outcome("w1", "W", "r1", RunStatus.FAILED, "out", false);

            assertThat(outcome.started()).isTrue();
        }
    }

    @Nested
    class ReservedReason {

        @Test
        void should_refuseReservedDoubleUnderscoreKeys() {
            assertThat(SubWorkflowLauncher.reservedReason("__parent_workflow_ids", "item", PLATFORM))
                    .isEqualTo("context may not set the reserved key __parent_workflow_ids");
        }

        @Test
        void should_refusePlatformDataAndRunVariables() {
            assertThat(SubWorkflowLauncher.reservedReason("open_todos", "item", PLATFORM))
                    .isEqualTo("context may not set open_todos — the platform sets it for every run");
            assertThat(SubWorkflowLauncher.reservedReason("goal_guard_item_3", "item", PLATFORM))
                    .contains("the platform sets it for every run");
            for (String runVariable : SubWorkflowLauncher.RUN_VARIABLES) {
                assertThat(SubWorkflowLauncher.reservedReason(runVariable, "item", PLATFORM))
                        .as(runVariable).contains("the platform sets it for every run");
            }
        }

        @Test
        void should_refuseTheLoopsOwnItemVariables() {
            assertThat(SubWorkflowLauncher.reservedReason("item", "item", PLATFORM))
                    .isEqualTo("context may not set item — the loop sets it per item");
            assertThat(SubWorkflowLauncher.reservedReason("item_index", "item", PLATFORM))
                    .endsWith("the loop sets it per item");
            assertThat(SubWorkflowLauncher.reservedReason("item.id", "item", PLATFORM))
                    .endsWith("the loop sets it per item");
        }

        @Test
        void should_allowOrdinaryNames() {
            assertThat(SubWorkflowLauncher.reservedReason("branch", "item", PLATFORM)).isNull();
            assertThat(SubWorkflowLauncher.reservedReason("items", "item", PLATFORM)).isNull();
            assertThat(SubWorkflowLauncher.reservedReason("_single", "item", PLATFORM)).isNull();
        }
    }

    @Nested
    class ResolveWorkflowId {

        @Test
        void should_returnALiteralIdStripped() {
            StringBuilder refusal = new StringBuilder();

            assertThat(SubWorkflowLauncher.resolveWorkflowId("  wf-1 ", context, refusal)).isEqualTo("wf-1");
            assertThat(refusal).isEmpty();
        }

        @Test
        void should_resolveATrustedVariable() {
            context.setVariable("target", "wf-2");
            StringBuilder refusal = new StringBuilder();

            assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ target }}", context, refusal)).isEqualTo("wf-2");
            assertThat(refusal).isEmpty();
        }

        @Test
        void should_refusePreviousOutputBlockOutputsAndUntrustedVariables() {
            context.setUntrustedVariable("payload_id", "wf-3");
            for (String key : List.of("previous_output", "block.scan.output", "payload_id")) {
                StringBuilder refusal = new StringBuilder();

                assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ " + key + " }}", context, refusal)).as(key).isNull();
                assertThat(refusal.toString())
                        .isEqualTo("workflow_id may only use trusted context variables, not {{ " + key + " }}");
            }
        }

        @Test
        void should_refuse_when_resolutionIsEmptyOrLeavesAPlaceholder() {
            context.setVariable("empty", "  ");
            StringBuilder empty = new StringBuilder();
            assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ empty }}", context, empty)).isNull();
            assertThat(empty.toString()).isEqualTo("workflow_id '{{ empty }}' does not resolve to a workflow id");

            StringBuilder unknown = new StringBuilder();
            assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ unknown }}", context, unknown)).isNull();
            assertThat(unknown.toString()).contains("does not resolve to a workflow id");
        }
    }

    @Nested
    class Run {

        @Test
        void should_refuse_atTheDepthLimit_withoutLookingUpTheWorkflow() {
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("a", "b", "c", "d", "e")));

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.started()).isFalse();
            assertThat(outcome.output()).isEqualTo("Maximum sub-workflow depth (5) exceeded");
            verifyNoInteractions(resolver, childRuns);
        }

        @Test
        void should_allowOneLevelBelowTheDepthLimit() {
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("a", "b", "c", "d")));
            childEnds(child(), RunStatus.COMPLETED);

            assertThat(launcher.run("child", context).success()).isTrue();
        }

        @Test
        void should_refuseACycle() {
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("a", "child")));

            var outcome = launcher.run("child", context);

            assertThat(outcome.output()).isEqualTo("Circular sub-workflow reference detected: child");
            verifyNoInteractions(childRuns);
        }

        /** A package-relative path names the same workflow as its id: the chain holds ids, so the cycle is caught. */
        @Test
        void should_refuseACycle_throughAnotherReferenceToTheSameWorkflow() {
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("a", "child")));
            WorkflowDefinition child = new WorkflowDefinition("child", "Child", null, List.of(), null, null, null,
                    null, Map.of(), Map.of(), null, null, null);
            when(resolver.resolve("../child-package")).thenReturn(child);

            var outcome = launcher.run("../child-package", context);

            assertThat(outcome.output()).isEqualTo("Circular sub-workflow reference detected: child");
            verifyNoInteractions(childRuns);
        }

        @Test
        void should_failWithoutThrowing_when_parentIdsAreNotAListOfStrings() {
            context.getVariables().put("__parent_workflow_ids", List.of("a", 42));

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("__parent_workflow_ids is not a list of workflow ids");
            verifyNoInteractions(childRuns);
        }

        @Test
        void should_failWithoutName_when_workflowIsUnknown() {
            when(resolver.resolve("child")).thenThrow(new IllegalArgumentException("Workflow not found: child"));

            var outcome = launcher.run("child", context);

            assertThat(outcome.workflowName()).isNull();
            assertThat(outcome.subRunId()).isNull();
            assertThat(outcome.output()).isEqualTo("Sub-workflow execution failed: Workflow not found: child");
        }

        @Test
        void should_keepTheName_when_theChildRunCannotStart() {
            WorkflowDefinition child = child();
            when(childRuns.run(eq(child), anyMap())).thenThrow(new IllegalStateException("refused"));

            var outcome = launcher.run("child", context);

            assertThat(outcome.workflowName()).isEqualTo("Child");
            assertThat(outcome.started()).isFalse();
            assertThat(outcome.output()).isEqualTo("Sub-workflow execution failed: refused");
        }

        @Test
        void should_letAnEventLogFailure_propagate() {
            WorkflowDefinition child = child();
            when(childRuns.run(eq(child), anyMap())).thenThrow(new EventLogException(new IOException("disk full")));

            assertThatThrownBy(() -> launcher.run("child", context)).isInstanceOf(EventLogException.class);
        }

        @Test
        void should_returnTheChildsLastStepOutput_onSuccess() {
            childEnds(child(), RunStatus.COMPLETED,
                    StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("first").build(),
                    StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("last").build());

            var outcome = launcher.run("child", context);

            assertThat(outcome).isEqualTo(new SubWorkflowLauncher.Outcome(
                    "child", "Child", "child-run", RunStatus.COMPLETED, "last", true));
        }

        @Test
        void should_countAChildCompletedWithErrors_asSuccessWithAWarning() {
            // a failed checked step in the child is a warning, not a failure
            childEnds(child(), RunStatus.COMPLETED_WITH_ERRORS);

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.subRunStatus()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
            assertThat(outcome.warning())
                    .isEqualTo("Sub-workflow run ended COMPLETED_WITH_ERRORS: a checked step failed in it, see the child run");
        }

        @Test
        void should_carryNoWarning_forACleanChild() {
            childEnds(child(), RunStatus.COMPLETED);

            assertThat(launcher.run("child", context).warning()).isNull();
        }

        @Test
        void should_reportFailure_forAnyEndStatusOtherThanCompletedOrCompletedWithErrors() {
            childEnds(child(), RunStatus.FAILED);

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.started()).isTrue();
            assertThat(outcome.subRunStatus()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        void should_listStepStatuses_when_lastStepHasNoOutput() {
            childEnds(child(), RunStatus.FAILED,
                    StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("z".repeat(600)).build(),
                    StepResult.builder().stepIndex(1).status(StepStatus.FAILED).output(null).build());

            var outcome = launcher.run("child", context);

            assertThat(outcome.output()).isEqualTo("Sub-workflow completed with status: FAILED\n"
                    + "[Step 0] COMPLETED: " + "z".repeat(500) + "...\n"
                    + "[Step 1] FAILED: \n");
        }

        /**
         * A child that fails before or outside its steps (here: at start) leaves no output of its own; its error is
         * the only reason the parent step can show.
         */
        @Test
        void should_appendTheChildsError_when_itLeftNoOutputOfItsOwn() {
            WorkflowDefinition child = child();
            when(childRuns.run(eq(child), anyMap())).thenReturn(new ChildRun("child-run", RunStatus.FAILED, List.of(),
                    "Failed to initialize workflow data: variable 'note' has no value"));

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).isEqualTo("Sub-workflow completed with status: FAILED\n"
                    + "Error: Failed to initialize workflow data: variable 'note' has no value\n");
        }

        /** An earlier step's output must not hide why the child failed afterwards, outside its steps. */
        @Test
        void should_appendTheChildsError_afterTheLastStepOutput() {
            WorkflowDefinition child = child();
            when(childRuns.run(eq(child), anyMap())).thenReturn(new ChildRun("child-run", RunStatus.FAILED,
                    List.of(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("built").build()),
                    "WorkflowExecutionException: No executor found for block type: nope"));

            var outcome = launcher.run("child", context);

            assertThat(outcome.output()).isEqualTo(
                    "built\nError: WorkflowExecutionException: No executor found for block type: nope\n");
        }

        /** An output that already names the error does not repeat it. */
        @Test
        void should_notRepeatTheChildsError_when_theOutputAlreadyContainsIt() {
            WorkflowDefinition child = child();
            when(childRuns.run(eq(child), anyMap())).thenReturn(new ChildRun("child-run", RunStatus.FAILED,
                    List.of(StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                            .output("deploy failed: disk full on /var").build()),
                    "disk full on /var"));

            assertThat(launcher.run("child", context).output()).isEqualTo("deploy failed: disk full on /var");
        }

        @Test
        void should_reportOnlyTheStatusLine_when_childHasNoSteps() {
            childEnds(child(), RunStatus.CANCELLED);

            assertThat(launcher.run("child", context).output())
                    .isEqualTo("Sub-workflow completed with status: CANCELLED\n");
        }

        @Test
        @SuppressWarnings("unchecked")
        void should_handTheChildTheParentVariables_withoutPlatformData_andWithTheChain() {
            context.setVariable("branch", "main");
            context.setVariable("open_todos", "a long list");
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("root")));
            context.getVariables().put("trigger_source", "API");
            WorkflowDefinition child = child();
            childEnds(child, RunStatus.COMPLETED);

            launcher.run("child", context);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(childRuns).run(eq(child), captor.capture());
            Map<String, Object> handed = captor.getValue();
            assertThat(handed)
                    .containsEntry("branch", "main")
                    .doesNotContainKey("open_todos")
                    .containsEntry("previous_output", "")
                    .containsEntry("__parent_workflow_ids", List.of("root", "child"))
                    .containsEntry(TriggerSource.PARENT_CONTEXT_KEY, "API");
            // the parent's own chain list is not modified
            assertThat(context.getVariables().get("__parent_workflow_ids")).isEqualTo(List.of("root"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void should_passThePreviousOutput_andDropAForgedOrigin_when_parentOriginIsUnknown() {
            context.setPreviousOutput("prev");
            context.getVariables().put(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL");   // no trigger_source of its own
            WorkflowDefinition child = child();
            childEnds(child, RunStatus.COMPLETED);

            launcher.run("child", context);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(childRuns).run(eq(child), captor.capture());
            assertThat(captor.getValue())
                    .containsEntry("previous_output", "prev")
                    .containsEntry("__parent_workflow_ids", List.of("child"))
                    .doesNotContainKey(TriggerSource.PARENT_CONTEXT_KEY);
        }

        /**
         * A child that ended with untrusted input or a tainted output hands that on with its output: the output the
         * parent's block records is tainted, and a shell line of the parent refuses it.
         */
        @Test
        void should_taintTheOutputTheParentRecords_when_theChildEndedTainted() {
            WorkflowDefinition child = child();
            when(childRuns.run(eq(child), anyMap())).thenReturn(new ChildRun("child-run", RunStatus.COMPLETED,
                    List.of(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("x; id").build()),
                    null, false, true));

            var outcome = launcher.run("child", context);
            context.recordOutput("sub", outcome.output());

            assertThat(context.hasUntrustedInput()).isTrue();
            assertThatThrownBy(() -> context.resolveShellTemplate("echo {{ block.sub.output }}", false))
                    .isInstanceOf(traceworks.core.context.ShellLineRefusedException.class);
        }

        /** A child that ended clean, and asked no model, leaves the parent as it was. */
        @Test
        void should_leaveTheOutputTheParentRecordsRaw_when_theChildEndedClean() {
            childEnds(child(), RunStatus.COMPLETED,
                    StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("a | b").build());

            var outcome = launcher.run("child", context);
            context.recordOutput("sub", outcome.output());

            assertThat(context.hasUntrustedInput()).isFalse();
            assertThat(context.modelAsked()).isFalse();
            assertThat(context.resolveShellTemplate("echo {{ block.sub.output }}", false)).isEqualTo("echo a | b");
        }

        /** A context built without child runs refuses them clearly instead of skipping the child. */
        @Test
        void should_failTheChild_when_theRunHasNoChildRuns() {
            ExecutionContext bare = ExecutionContext.builder().workflowRunId("parent-run")
                    .workflowResolver(resolver).build();
            child();

            var outcome = launcher.run("child", bare);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).isEqualTo("Sub-workflow execution failed: No child runs configured for this run");
        }
    }

    @Test
    void should_nameThePlatformDataItNeverHandsToAChild() {
        assertThat(launcher.platformVariableNames()).isEqualTo(PlatformVariables.NAMES)
                .contains("previous_report", "open_todos", "request_metrics", "audit_findings", "audit_items", "todo_details",
                        "goal_status");
    }

    @Test
    void should_capTheDepthAtFive() {
        assertThat(SubWorkflowLauncher.MAX_DEPTH).isEqualTo(5);
    }
}
