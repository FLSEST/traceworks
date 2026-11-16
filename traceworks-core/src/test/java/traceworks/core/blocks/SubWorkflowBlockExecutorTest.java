package traceworks.core.blocks;

import traceworks.core.context.ChildRuns;
import traceworks.core.context.ChildRuns.ChildRun;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.BlockTypeRegistry;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.PlatformVariables;
import traceworks.core.engine.RunControl;
import traceworks.core.engine.RunOptions;
import traceworks.core.engine.RunOutcome;
import traceworks.core.engine.WorkflowEngine;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunStatusChanged;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.port.WorkflowResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code sub-workflow} block. These tests resolve the child through a mocked {@link WorkflowResolver} and run
 * it through a mocked {@link ChildRuns}; the tests at the end run parent and child through the real {@link
 * WorkflowEngine}.
 */
@ExtendWith(MockitoExtension.class)
class SubWorkflowBlockExecutorTest {

    @Mock
    private WorkflowResolver resolver;

    @Mock
    private ChildRuns childRuns;

    private SubWorkflowBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new SubWorkflowBlockExecutor();
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .workflowResolver(resolver)
                .childRuns(childRuns)
                .build();
    }

    private static WorkflowDefinition workflow(String id, String name, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, name, null, List.of(blocks), null, null, null, null, Map.of(), Map.of(),
                null, null, null);
    }

    private static BlockDefinition subWorkflowBlock(String workflowId) {
        return BlockDefinition.builder().type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", workflowId))).build();
    }

    /** The child "sub-wf-1" ends with {@code status} after {@code steps}; returns its definition. */
    private WorkflowDefinition childEnds(RunStatus status, StepResult... steps) {
        WorkflowDefinition child = workflow("sub-wf-1", "Sub Workflow");
        when(resolver.resolve("sub-wf-1")).thenReturn(child);
        when(childRuns.run(eq(child), anyMap())).thenReturn(new ChildRun("sub-run-1", status, List.of(steps), null));
        return child;
    }

    /** Runs the block against a completed child and returns the inputs handed to it. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> childContextOf(ExecutionContext parent) {
        WorkflowDefinition child = childEnds(RunStatus.COMPLETED);

        executor.execute(subWorkflowBlock("sub-wf-1"), parent);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(childRuns).run(eq(child), captor.capture());
        return captor.getValue();
    }

    private static StepResult step(int index, StepStatus status, String output) {
        return StepResult.builder().stepIndex(index).status(status).output(output).build();
    }

    /**
     * The status lines of a child's steps cut each output at 500 characters; the cut never falls inside
     * one of the run's secret values, which the record then could not redact.
     */
    @Test
    void theOutputOfAFailedChild_neverCutsASecretInTwo() {
        String secret = "s3cr3t-d3ploy-t0ken";
        ExecutionContext withSecret = ExecutionContext.builder().containerId("container-1").workflowRunId("run-1")
                .workflowResolver(resolver).childRuns(childRuns).secrets(Map.of("DEPLOY_TOKEN", secret)).build();
        childEnds(RunStatus.FAILED, step(0, StepStatus.COMPLETED, "x".repeat(490) + secret + "y".repeat(100)),
                step(1, StepStatus.FAILED, null));

        StepResult result = executor.execute(subWorkflowBlock("sub-wf-1"), withSecret);

        assertThat(result.getOutput()).contains("[Step 0] COMPLETED: " + "x".repeat(490) + "...")
                .doesNotContain("s3cr3t").doesNotContain("t0ken");
    }

    @Test
    void supports_subWorkflow_returnsTrue() {
        assertThat(executor.supports("sub-workflow")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void execute_missingWorkflowId_throwsException() {
        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>())
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("workflow_id");
    }

    /** Legacy keys are converted by the import, never accepted natively. */
    @Test
    void execute_legacyCamelCaseWorkflowId_isRefusedWithTheReason() {
        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflowId", "sub-wf-1")))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("'workflow_id'")
                .hasMessageContaining("legacy key 'workflowId'");
        verifyNoInteractions(resolver, childRuns);
    }

    @Test
    void execute_maxDepthExceeded_returnsFailed() {
        List<String> parentIds = new ArrayList<>(List.of("w1", "w2", "w3", "w4", "w5"));
        context.getVariables().put("__parent_workflow_ids", parentIds);

        StepResult result = executor.execute(subWorkflowBlock("w6"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Maximum sub-workflow depth");
    }

    @Test
    void execute_circularReference_returnsFailed() {
        List<String> parentIds = new ArrayList<>(List.of("w1", "w2"));
        context.getVariables().put("__parent_workflow_ids", parentIds);

        StepResult result = executor.execute(subWorkflowBlock("w1"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Circular");
    }

    @Test
    void execute_success_returnsCompleted() {
        childEnds(RunStatus.COMPLETED, step(0, StepStatus.COMPLETED, "sub-output"));

        StepResult result = executor.execute(subWorkflowBlock("sub-wf-1"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("sub-output");
        assertThat(result.getMetadata()).containsEntry("subWorkflowId", "sub-wf-1");
        assertThat(result.getMetadata()).containsEntry("subRunId", "sub-run-1");
    }

    @Test
    void execute_childCompletedWithErrors_completesWithTheChildStatusAndAWarning() {
        childEnds(RunStatus.COMPLETED_WITH_ERRORS, step(0, StepStatus.FAILED, "check red"),
                step(1, StepStatus.COMPLETED, "child done"));

        StepResult result = executor.execute(subWorkflowBlock("sub-wf-1"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("child done");
        assertThat(result.getMetadata())
                .containsEntry("subRunStatus", "COMPLETED_WITH_ERRORS")
                .containsEntry("subRunWarning",
                        "Sub-workflow run ended COMPLETED_WITH_ERRORS: a checked step failed in it, see the child run");
    }

    @Test
    void execute_cleanChild_carriesNoWarning() {
        childEnds(RunStatus.COMPLETED);

        assertThat(executor.execute(subWorkflowBlock("sub-wf-1"), context).getMetadata())
                .doesNotContainKey("subRunWarning");
    }

    /** The step only copies the child's output; gate_status markers stay the child run's business. */
    @Test
    void execute_success_marksTheStepAsDerivedOutput() {
        childEnds(RunStatus.COMPLETED,
                step(0, StepStatus.COMPLETED, "gate_status: RED\ntodo_item: Child finding | HIGH | bug | desc"));

        StepResult result = executor.execute(subWorkflowBlock("sub-wf-1"), context);

        assertThat(result.getOutput()).contains("gate_status: RED");
        assertThat(result.getMetadata()).containsEntry("derivedOutput", true);
    }

    @Test
    void execute_subWorkflowFails_stillMarksTheStepAsDerivedOutput() {
        childEnds(RunStatus.FAILED);

        StepResult result = executor.execute(subWorkflowBlock("sub-wf-1"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("derivedOutput", true);
    }

    @Test
    void execute_subWorkflowFails_returnsFailed() {
        childEnds(RunStatus.FAILED);

        StepResult result = executor.execute(subWorkflowBlock("sub-wf-1"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_passesParentContextToChild() {
        context.getVariables().put("my_var", "my_value");
        context.setPreviousOutput("prev-output");

        Map<String, Object> childContext = childContextOf(context);

        assertThat(childContext).containsEntry("my_var", "my_value");
        assertThat(childContext).containsEntry("previous_output", "prev-output");
        assertThat((List<String>) childContext.get("__parent_workflow_ids")).contains("sub-wf-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_childInheritsTheParentsEffectiveOrigin() {
        // parent is a chain entry started by another program
        context.getVariables().put("trigger_source", "CHAIN");
        context.getVariables().put(TriggerSource.PARENT_CONTEXT_KEY, "API");
        WorkflowDefinition child = childEnds(RunStatus.COMPLETED);

        executor.execute(subWorkflowBlock("sub-wf-1"), context);
        context.getVariables().put("trigger_source", "bogus");
        executor.execute(subWorkflowBlock("sub-wf-1"), context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(childRuns, times(2)).run(eq(child), captor.capture());
        assertThat(captor.getAllValues().get(0)).containsEntry(TriggerSource.PARENT_CONTEXT_KEY, "API");
        // unreadable origin: the key is dropped so the child counts as unattended
        assertThat(captor.getAllValues().get(1)).doesNotContainKey(TriggerSource.PARENT_CONTEXT_KEY);
    }

    @Test
    void execute_taintedParent_handsTheTaintToTheChild() {
        context.setUntrustedVariable("branch", "main; id");
        context.recordOutput("scan", "out");

        assertThat(childContextOf(context))
                .containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch"))
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME, "scan"));
    }

    @Test
    void execute_cleanParent_handsNoTaintKeysToTheChild() {
        context.recordOutput("scan", "out");

        assertThat(childContextOf(context)).doesNotContainKeys(
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE);
    }

    /** An output tainted by platform data in an AI prompt reaches the child too. */
    @Test
    void execute_outputTaintedByAPlatformPrompt_reachesTheChild() {
        context.setUntrustedPlatformVariable("previous_report", "last report");
        context.resolvePromptTemplate("Assess: {{ previous_report }}");
        context.recordOutput("analysis", "a | b");

        assertThat(childContextOf(context)).containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                List.of("analysis", ExecutionContext.PREVIOUS_OUTPUT_NAME));
    }

    /**
     * The parent's platform data does not go into the child's inputs: the child would carry it as its own input. Its
     * own variables, goal_id included, do go.
     */
    @Test
    void execute_handsTheParentsVariablesToTheChild_butNotItsPlatformData() {
        context.setUntrustedPlatformVariable("previous_report", "Report of the parent run");
        context.setUntrustedPlatformVariable("open_todos", "<open_todos>\n- t-1 [HIGH] Title\n</open_todos>");
        context.setUntrustedPlatformVariable("request_metrics", "FACT request_metrics_routes_measured: 12");
        context.setUntrustedPlatformVariable("audit_findings", "<audit_findings>\n</audit_findings>");
        context.setUntrustedPlatformVariable("goal_status", "Goal on track");
        context.setUntrustedPlatformVariable("goal_guard_item_1", "Item");
        context.setVariable("goal_id", "g-1");
        context.setVariable("my_var", "my_value");

        Map<String, Object> child = childContextOf(context);

        assertThat(child).doesNotContainKeys(PlatformVariables.NAMES.toArray(String[]::new))
                .doesNotContainKey("goal_guard_item_1")
                .containsEntry("goal_id", "g-1")
                .containsEntry("my_var", "my_value");
        assertThat(child).as("a clean parent hands over no taint").doesNotContainKeys(
                ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE);
    }

    /**
     * The values stay behind, their taint does not: platform data the parent put into a shell command line or into
     * the prompt behind the output it passes on still colours the child.
     */
    @Test
    @SuppressWarnings("unchecked")
    void execute_platformDataTheParentUsed_stillTaintsTheChild_withoutItsValues() {
        context.setUntrustedPlatformVariable("previous_report", "Report; rm -rf /");
        context.resolveShellValue("echo {{ previous_report }}");
        context.resolvePromptTemplate("Assess: {{ previous_report }}");
        context.recordOutput("assessment", "a | b");

        Map<String, Object> child = childContextOf(context);

        assertThat(child).doesNotContainKey("previous_report")
                .containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true)
                .containsEntry("previous_output", "a | b");
        assertThat((List<String>) child.get(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE))
                .contains("assessment", ExecutionContext.PREVIOUS_OUTPUT_NAME);
    }

    /** workflow_id may name the child through a trusted variable. */
    /** {@code subWorkflowId} is the child's id, subWorkflowRef the reference as written. */
    @Test
    void execute_relativeReference_recordsTheChildIdAndTheReference() {
        WorkflowDefinition child = workflow("sub-wf-1", "Sub Workflow");
        when(resolver.resolve("../child")).thenReturn(child);
        when(childRuns.run(eq(child), anyMap())).thenReturn(new ChildRun("sub-run-1", RunStatus.COMPLETED, List.of(), null));

        StepResult result = executor.execute(subWorkflowBlock("../child"), context);

        assertThat(result.getMetadata()).containsEntry("subWorkflowId", "sub-wf-1")
                .containsEntry("subWorkflowRef", "../child");
    }

    @Test
    void execute_workflowIdFromATrustedVariable_runsThatWorkflow() {
        context.setVariable("child_workflow_id", "sub-wf-1");
        childEnds(RunStatus.COMPLETED);

        StepResult result = executor.execute(subWorkflowBlock("{{ child_workflow_id }}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("subWorkflowId", "sub-wf-1");
    }

    /** Outside input must not pick which workflow runs. */
    @Test
    void execute_workflowIdFromAnUntrustedVariableOrOutput_isRefused() {
        context.setUntrustedVariable("target", "sub-wf-1");
        for (String id : List.of("{{ target }}", "{{ previous_output }}", "{{ block.scan.output }}", "{{ unknown }}")) {
            StepResult result = executor.execute(subWorkflowBlock(id), context);

            assertThat(result.getStatus()).as(id).isEqualTo(StepStatus.FAILED);
        }
        verifyNoInteractions(resolver, childRuns);
    }

    @Test
    void execute_unreadableParentIds_failsTheStepInsteadOfThrowing() {
        context.getVariables().put("__parent_workflow_ids", "not-a-list");

        StepResult result = executor.execute(subWorkflowBlock("sub-wf-1"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verifyNoInteractions(childRuns);
    }

    /** The run record is the event log: a child whose events cannot be written fails the parent, never the step. */
    @Test
    void execute_eventLogFailureInTheChild_propagates() {
        WorkflowDefinition child = workflow("sub-wf-1", "Sub Workflow");
        when(resolver.resolve("sub-wf-1")).thenReturn(child);
        when(childRuns.run(eq(child), anyMap())).thenThrow(new EventLogException(new IOException("disk full")));

        assertThatThrownBy(() -> executor.execute(subWorkflowBlock("sub-wf-1"), context))
                .isInstanceOf(EventLogException.class)
                .hasMessageContaining("disk full");
    }

    // --- parent and child through the real engine ---

    private final List<RunEvent> parentEvents = new CopyOnWriteArrayList<>();
    private final RunControl control = new RunControl();

    private static BlockDefinition shell(String name, String command) {
        return BlockDefinition.builder().type("shell").name(name)
                .config(new HashMap<>(Map.of("command", command))).build();
    }

    private RunOutcome runParent(WorkflowDefinition parent, WorkflowResolver workflows, CommandRunner commands) {
        return new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache"))).run(parent, Map.of(),
                RunOptions.builder().runId("parent-run").events(parentEvents::add).runControl(control)
                        .workflowResolver(workflows).commandRunner(commands).workingDirectory("/workspace").build());
    }

    private static WorkflowResolver only(WorkflowDefinition child) {
        return ref -> {
            if (!ref.equals(child.id())) {
                throw new IllegalArgumentException("unknown workflow " + ref);
            }
            return child;
        };
    }

    /** The events of the child run, unwrapped. */
    private List<RunEvent> childEvents(String childRunId) {
        return parentEvents.stream().filter(ChildRunEvent.class::isInstance).map(ChildRunEvent.class::cast)
                .filter(e -> e.runId().equals(childRunId)).map(ChildRunEvent::event).toList();
    }

    /**
     * Cancelling the parent cancels the child it waits for: they share the run control, and the child is no
     * separately started run that the parent polls.
     */
    @Test
    void execute_parentCancelledWhileTheChildRuns_cancelsTheChild() {
        WorkflowDefinition child = workflow("child", "Child", shell("first", "first"), shell("second", "second"));
        List<String> ran = new CopyOnWriteArrayList<>();
        CommandRunner commands = request -> {
            ran.add(request.command());
            if (request.command().equals("first")) {
                control.cancel();
            }
            return new CommandResult(0, "out " + request.command(), "", 1, false);
        };

        RunOutcome outcome = runParent(workflow("parent", "Parent", subWorkflowBlock("child"), shell("after", "after")),
                only(child), commands);

        assertThat(ran).containsExactly("first");
        StepResult subStep = outcome.steps().getFirst();
        assertThat(subStep.getStatus()).as("the cancel ended its child").isEqualTo(StepStatus.CANCELLED);
        assertThat(subStep.getMetadata()).containsEntry("subRunStatus", "CANCELLED");
        assertThat(childEvents((String) subStep.getMetadata().get("subRunId")))
                .filteredOn(StepFinished.class::isInstance).map(StepFinished.class::cast)
                .extracting(StepFinished::status).as("the child's step ended before the cancel took effect")
                .containsExactly(StepStatus.COMPLETED);
        assertThat(childEvents((String) subStep.getMetadata().get("subRunId")))
                .last().isInstanceOfSatisfying(RunFinished.class,
                        finished -> assertThat(finished.status()).isEqualTo(RunStatus.CANCELLED));
        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
    }

    /**
     * A pause while the child runs pauses the child between its blocks (shared run control); the parent's step waits
     * until the child ends.
     */
    @Test
    void execute_pauseWhileTheChildRuns_pausesTheChild_andTheStepWaitsUntilItEnds() {
        WorkflowDefinition child = workflow("child", "Child", shell("first", "first"), shell("second", "second"));
        CommandRunner commands = request -> {
            if (request.command().equals("first")) {
                control.pause();
            }
            return new CommandResult(0, "out " + request.command(), "", 1, false);
        };
        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache"))).run(
                workflow("parent", "Parent", subWorkflowBlock("child")), Map.of(),
                RunOptions.builder().runId("parent-run").runControl(control).workflowResolver(only(child))
                        .commandRunner(commands).workingDirectory("/workspace")
                        .events(event -> {
                            parentEvents.add(event);
                            if (event instanceof ChildRunEvent(String id, RunStatusChanged changed)
                                    && changed.status() == RunStatus.PAUSED) {
                                control.resume();   // whoever paused lets it go on
                            }
                        }).build());

        StepResult subStep = outcome.steps().getFirst();
        assertThat(subStep.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(subStep.getMetadata()).containsEntry("subRunStatus", "COMPLETED");
        assertThat(subStep.getOutput()).isEqualTo("out second");
        List<RunEvent> childEvents = childEvents((String) subStep.getMetadata().get("subRunId"));
        assertThat(childEvents).filteredOn(RunStatusChanged.class::isInstance)
                .containsExactly(new RunStatusChanged(RunStatus.PAUSED), new RunStatusChanged(RunStatus.RUNNING));
        assertThat(parentEvents).as("the parent itself stays RUNNING in its step")
                .noneMatch(RunStatusChanged.class::isInstance);
        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
    }

    /** Child events are never dropped: they reach the parent's sink, each wrapped with the child's run id. */
    @Test
    void execute_childEvents_reachTheParentsSinkWithTheChildRunId() {
        WorkflowDefinition child = workflow("child", "Child", shell("build", "make"));
        CommandRunner commands = request -> new CommandResult(0, "built", "", 1, false);

        RunOutcome outcome = runParent(workflow("parent", "Parent", subWorkflowBlock("child")), only(child), commands);

        String childRunId = (String) outcome.steps().getFirst().getMetadata().get("subRunId");
        assertThat(childRunId).isNotBlank().isNotEqualTo("parent-run");
        List<RunEvent> childEvents = childEvents(childRunId);
        assertThat(childEvents).first().isEqualTo(new StepStarted(0, "build", "shell", 0, false));
        assertThat(childEvents).filteredOn(StepFinished.class::isInstance).singleElement()
                .extracting(e -> ((StepFinished) e).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(childEvents).last().isInstanceOfSatisfying(RunFinished.class,
                finished -> assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED));
        assertThat(parentEvents).filteredOn(StepStarted.class::isInstance)
                .as("the child's steps are not the parent's").containsExactly(new StepStarted(0, null, "sub-workflow", 0, false));
        assertThat(parentEvents).last().isInstanceOf(RunFinished.class);
    }

    /** The child is a run of its own: SUB_WORKFLOW, its own id, the parent's chain, and the parent's taint. */
    @Test
    void execute_childIsARunOfItsOwn_andKeepsTheParentsTaint() {
        WorkflowDefinition child = workflow("child", "Child",
                BlockDefinition.builder().type("shell").name("show").config(new HashMap<>(Map.of(
                        "values", Map.of("BRANCH", "{{ branch }}"),
                        "command", "echo {{ trigger_source }} {{ workflow_run_id }} \"$BRANCH\""))).build(),
                shell("in_a_line", "echo {{ branch }}"));
        List<String> commandsRun = new CopyOnWriteArrayList<>();
        CommandRunner commands = request -> {
            commandsRun.add(request.command() + " " + request.values());
            return new CommandResult(0, "ok", "", 1, false);
        };

        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache"))).run(
                workflow("parent", "Parent", subWorkflowBlock("child")),
                Map.of("branch", "main; id", ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch")),
                RunOptions.builder().runId("parent-run").events(parentEvents::add).workflowResolver(only(child))
                        .commandRunner(commands).workingDirectory("/workspace").build());

        String childRunId = (String) outcome.steps().getFirst().getMetadata().get("subRunId");
        assertThat(commandsRun).as("the child gets the parent's untrusted value as a variable, and refuses it in a line")
                .containsExactly("echo SUB_WORKFLOW " + childRunId + " \"$BRANCH\" {BRANCH=main; id}");
        assertThat(outcome.steps().getFirst().getOutput()).contains("shell line refused, nothing ran: {{ branch }}");
    }

    /** A child that may not start (unknown workflow) fails the step with the reason, not the run. */
    @Test
    void execute_unknownChild_failsTheStepWithTheResolversReason() {
        WorkflowDefinition other = workflow("other", "Other");

        RunOutcome outcome = runParent(workflow("parent", "Parent", subWorkflowBlock("child")), only(other),
                request -> new CommandResult(0, "", "", 1, false));

        assertThat(outcome.steps().getFirst().getOutput())
                .isEqualTo("Sub-workflow execution failed: unknown workflow child");
        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(parentEvents).noneMatch(ChildRunEvent.class::isInstance);
    }

    /**
     * A child that cannot start (a parent variable without a value, as a JSON {@code null} in a human-input form leaves
     * one) fails before any step; the reason reaches the parent's sub-workflow step and foreach item.
     */
    @Test
    void execute_childThatCannotStart_showsTheReasonInTheParentStep() {
        BlockExecutor nullSetter = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                context.setVariable("note", null);
                return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED)
                        .output("set").build();
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of("set-null");
            }
        };
        WorkflowDefinition child = workflow("child", "Child", shell("build", "make"));
        BlockDefinition subWorkflow = subWorkflowBlock("child");
        subWorkflow.getConfig().put("on_failure", "continue");
        BlockDefinition foreach = BlockDefinition.builder().type("foreach")
                .config(new HashMap<>(Map.of("items", List.of("a"), "workflow_id", "child"))).build();
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(nullSetter, new SubWorkflowBlockExecutor(),
                new ForEachBlockExecutor(), new ShellBlockExecutor(Path.of("/tool-cache"))));

        RunOutcome outcome = new WorkflowEngine(registry).run(
                workflow("parent", "Parent", BlockDefinition.builder().type("set-null").config(new HashMap<>()).build(),
                        subWorkflow, foreach),
                Map.of(), RunOptions.builder().runId("parent-run").events(parentEvents::add)
                        .workflowResolver(only(child)).workingDirectory("/workspace").build());

        String reason = "Error: Failed to initialize workflow data: variable 'note' has no value";
        assertThat(outcome.steps()).hasSize(3);
        assertThat(outcome.steps().get(1).getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.steps().get(1).getOutput()).contains(reason);
        assertThat(outcome.steps().get(2).getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.steps().get(2).getOutput()).contains("[a]: Sub-workflow completed with status: FAILED")
                .contains(reason);
    }

    /**
     * A child whose first step succeeded with output and whose next block then throws instead of returning a step (a
     * shell block without a command): the throwing step ends FAILED with the reason as its output, so the parent step
     * shows the reason, not the first step's output.
     */
    @Test
    void execute_childFailingAfterASuccessfulStep_showsTheOutputAndTheReason() {
        WorkflowDefinition child = workflow("child", "Child", shell("build", "make"),
                BlockDefinition.builder().type("shell").name("broken").config(new HashMap<>()).build());

        RunOutcome outcome = runParent(workflow("parent", "Parent", subWorkflowBlock("child")), only(child),
                request -> new CommandResult(0, "built", "", 1, false));

        StepResult subStep = outcome.steps().getFirst();
        assertThat(subStep.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(subStep.getOutput())
                .isEqualTo("WorkflowExecutionException: Shell block requires a 'command' in config");
    }

    /** A child the run admission refuses starts no step; the parent step shows why. */
    @Test
    void execute_refusedChild_showsTheRefusal() {
        WorkflowDefinition child = workflow("child", "Child", shell("build", "make"),
                BlockDefinition.builder().type("nope").config(new HashMap<>()).build());

        RunOutcome outcome = runParent(workflow("parent", "Parent", subWorkflowBlock("child")), only(child),
                request -> new CommandResult(0, "built", "", 1, false));

        StepResult subStep = outcome.steps().getFirst();
        assertThat(subStep.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(subStep.getOutput()).contains("Error: workflow refused: Block 2: unknown block type \"nope\"");
        assertThat(subStep.getOutput()).doesNotContain("built");
    }

    /**
     * A sink that fails during the child stops the parent fail-closed, with the first failure as its error; the child
     * ends FAILED with the same error, not with the failure wrapped a second time.
     */
    @Test
    void execute_sinkFailsDuringTheChild_failsTheParentWithTheFirstWriteFailure() {
        WorkflowDefinition child = workflow("child", "Child", shell("build", "make"));
        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache"))).run(
                workflow("parent", "Parent", subWorkflowBlock("child"), shell("after", "after")), Map.of(),
                RunOptions.builder().runId("parent-run").workflowResolver(only(child))
                        .commandRunner(request -> new CommandResult(0, "ok", "", 1, false))
                        .events(event -> {
                            if (event instanceof ChildRunEvent(String id, StepFinished finished)) {
                                throw new IllegalStateException("disk full");
                            }
                            parentEvents.add(event);
                        }).build());

        String firstFailure = EventLogException.MESSAGE_PREFIX + "IllegalStateException: disk full";
        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo(firstFailure);
        assertThat(outcome.steps()).as("no block starts after the failed write").hasSize(1);
        String childRunId = (String) outcome.steps().getFirst().getMetadata().get("subRunId");
        assertThat(childEvents(childRunId)).last().isEqualTo(new RunFinished(RunStatus.FAILED, Map.of(), firstFailure));
    }
}
