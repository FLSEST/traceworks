package traceworks.core.engine;

import traceworks.core.container.RunPaths;
import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ChildRuns;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.GateStatus;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.GateReported;
import traceworks.core.port.RunEvent.OutputRecorded;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunStatusChanged;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.port.RunEvent.StepWaiting;
import traceworks.core.port.RunEvents;
import traceworks.core.port.WorkflowResolver;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The block loop. These tests assert the {@link RunOutcome} and the emitted {@link RunEvent}s.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-02T08:15:30Z");

    @Mock
    private BlockExecutor simpleBlockExecutor;

    private final RecordingRunEvents events = new RecordingRunEvents();
    private final RunControl control = new RunControl();

    private WorkflowEngine engine;
    private List<BlockDefinition> blocks;
    private Map<String, Object> defaultContext;

    @BeforeEach
    void setUp() {
        lenient().when(simpleBlockExecutor.supports("simple")).thenReturn(true);
        lenient().when(simpleBlockExecutor.supportedTypes()).thenReturn(Set.of("simple"));
        engine = newEngine(List.of(simpleBlockExecutor));
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo 1"))).build(),
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo 2"))).build()));
        defaultContext = Map.of();
    }

    private static WorkflowEngine newEngine(List<BlockExecutor> executors) {
        return new WorkflowEngine(new BlockTypeRegistry(executors));
    }

    private WorkflowDefinition workflow() {
        return new WorkflowDefinition("wf-1", "Test Workflow", null, blocks, null, null, null, null, Map.of(),
                defaultContext, null, null, null);
    }

    private RunOptions.Builder options() {
        return RunOptions.builder().runId("run-1").events(events).runControl(control).clock(() -> NOW)
                .workingDirectory("/workspace/test");
    }

    private RunOutcome run(Map<String, Object> inputs) {
        return run(engine, inputs);
    }

    private RunOutcome run(WorkflowEngine with, Map<String, Object> inputs) {
        return with.run(workflow(), inputs, options().build());
    }

    private static StepResult completed(int index, String output) {
        return StepResult.builder().stepIndex(index).status(StepStatus.COMPLETED).output(output).build();
    }

    private static StepResult failed(int index, String output) {
        return StepResult.builder().stepIndex(index).status(StepStatus.FAILED).output(output).build();
    }

    /** The executor for one block type, the only one in its registry. */
    private static BlockExecutor executorOf(String type) {
        BlockExecutor executor = mock(BlockExecutor.class);
        lenient().when(executor.supports(type)).thenReturn(true);
        lenient().when(executor.supportedTypes()).thenReturn(Set.of(type));
        return executor;
    }

    private void onlyBlock(String type, Map<String, Object> config) {
        blocks = new ArrayList<>(List.of(BlockDefinition.builder().type(type).config(new HashMap<>(config)).build()));
    }

    private RunFinished finished() {
        assertThat(events.last()).as("RunFinished is the last event of a run").isInstanceOf(RunFinished.class);
        return (RunFinished) events.last();
    }

    @Test
    void run_returnsOnlyWhenTheRunHasEnded() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "ok"));

        RunOutcome outcome = run(null);

        // The engine runs synchronously: it returns no RUNNING run that a thread pool executes
        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps()).hasSize(2).allSatisfy(step ->
                assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED));
    }

    @Test
    void run_allBlocksSucceed_completes() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "ok"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.error()).isNull();
        assertThat(finished()).isEqualTo(new RunFinished(RunStatus.COMPLETED, Map.of(), null));
    }

    @Test
    void run_exposesTheTriggerSourceAsAVariable() {
        ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctx.capture())).thenReturn(completed(0, "ok"));

        engine.run(workflow(), null, options().triggerSource(TriggerSource.API).build());

        assertThat(ctx.getValue().getVariable("trigger_source")).isEqualTo("API");
    }

    @Test
    void run_blockFails_withStopOnFailure_fails() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed(0, "error"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isNotNull();
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
    }

    @Test
    void run_blockFails_withContinueOnFailure_continuesExecution() {
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple")
                        .config(new HashMap<>(Map.of("command", "fail", "on_failure", "continue"))).build(),
                BlockDefinition.builder().type("simple")
                        .config(new HashMap<>(Map.of("command", "pass"))).build()));
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed(0, "error"), completed(1, "ok"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        verify(simpleBlockExecutor, times(2)).execute(any(), any());
    }

    /**
     * An unknown on_failure value ("fail" is neither "stop" nor "continue") must stop the run like "stop"; it
     * once silently carried on and the second block ran.
     */
    @Test
    void run_blockFails_withUnknownOnFailureValue_stopsTheRun() {
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple")
                        .config(new HashMap<>(Map.of("command", "fail", "on_failure", "fail"))).build(),
                BlockDefinition.builder().type("simple")
                        .config(new HashMap<>(Map.of("command", "must not run"))).build()));
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed(0, "error"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).startsWith("Step 0 (simple) failed:");
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
    }

    /** A type no executor runs is refused before the run starts, not when the run reaches the block. */
    @Test
    void run_noMatchingExecutor_isRefusedBeforeAnyBlock() {
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo 1"))).build(),
                BlockDefinition.builder().type("unknown-type").config(new HashMap<>()).build()));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error())
                .isEqualTo("workflow refused: Block 2: unknown block type \"unknown-type\" (known: simple)");
        verify(simpleBlockExecutor, never()).execute(any(), any());
    }

    @Test
    void run_blockThrowsError_failsTheRunInsteadOfLeavingItRunning() {
        // A build that rewrites the classes under a running JVM makes lazily loaded classes vanish: blocks die
        // with an Error, not an Exception, and the run must still end FAILED.
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new NoClassDefFoundError("traceworks/core/gate/GateStatusOutputParser$1"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).startsWith("NoClassDefFoundError: ").contains("GateStatusOutputParser");
    }

    @Test
    void run_setsInputContextVariables() {
        ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctx.capture())).thenReturn(completed(0, "ok"));

        run(Map.of("branch", "main", "env", "production"));

        verify(simpleBlockExecutor, times(2)).execute(any(), any());
        assertThat(ctx.getValue().getVariable("branch")).isEqualTo("main");
        assertThat(ctx.getValue().getVariable("env")).isEqualTo("production");
    }

    @Test
    void run_seedsSystemVariables_includingProjectPath() {
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture())).thenReturn(completed(0, "ok"));

        run(null);

        ExecutionContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getVariable("project_path")).isEqualTo("/workspace/test");
        assertThat(ctx.getProjectPath()).isEqualTo("/workspace/test");
        assertThat(ctx.getVariable("workflow_id")).isEqualTo("wf-1");
        assertThat(ctx.getVariable("workflow_name")).isEqualTo("Test Workflow");
        assertThat(ctx.getVariable("workflow_run_id")).isEqualTo("run-1");
        assertThat(ctx.getVariable("started_at")).isEqualTo("2026-11-02T08:15:30Z");
        assertThat(ctx.getVariable("home_dir")).as("not provided").isNull();
        assertThat(ctx.resolveTemplate("cd {{ project_path }}")).isEqualTo("cd /workspace/test");
    }

    /** The run's paths reach the context: a container run's steps see the fixed container paths. */
    @Test
    void run_withRunPaths_seedsTheContainerPaths_andKeepsTheHostPathForTheRunner() {
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture())).thenReturn(completed(0, "ok"));
        RunPaths paths = new RunPaths("/workspace/test", "/pkg", "/scratch-host", true);
        RunOptions options = options().runPaths(paths).build();

        engine.run(workflow(), Map.of(), options);

        ExecutionContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getVariable("project_path")).isEqualTo("/workspace");
        assertThat(ctx.getVariable("package_dir")).isEqualTo("/package");
        assertThat(ctx.getVariable("scratch_dir")).isEqualTo("/scratch");
        assertThat(ctx.getProjectPath()).isEqualTo("/workspace/test");
        assertThat(options.toBuilder().build().runPaths()).as("a child run keeps them").isEqualTo(paths);
    }

    /** The commit of the run's checkout of its source reaches the context, and a child run keeps it. */
    @Test
    void run_withAWorkspaceSourceSha_seedsIt() {
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(completed(0, "ok"));
        String sha = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
        RunOptions options = options().workspaceSourceSha(sha).build();

        engine.run(workflow(), Map.of(), options);

        assertThat(ctxCaptor.getValue().getVariable("workspace_source_sha")).isEqualTo(sha);
        assertThat(options.toBuilder().build().workspaceSourceSha()).as("a child run keeps it").isEqualTo(sha);
    }

    /**
     * No key or token may ever be a template variable: it would resolve into prompts, then into model output,
     * and finally into the run record.
     */
    @Test
    void run_doesNotInjectApiKeyAsTemplateVariable() {
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture())).thenReturn(completed(0, "ok"));

        run(null);

        ExecutionContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getVariable("api_key"))
                .as("api_key must never be a template variable; it would leak into the recorded output")
                .isNull();
        // Templates referencing {{ api_key }} resolve to the literal placeholder, making the leak vector inert.
        assertThat(ctx.resolveTemplate("X-API-Key: {{ api_key }}")).isEqualTo("X-API-Key: {{ api_key }}");
    }

    @Test
    void run_seedsRunTimestamp_inPathSafeFormat() {
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture())).thenReturn(completed(0, "ok"));

        run(null);

        ExecutionContext ctx = ctxCaptor.getValue();
        // yyyyMMdd-HHmmss, no ':' so it is safe as a file or directory name
        assertThat(ctx.getVariable("run_timestamp")).isEqualTo("20261102-081530");
        assertThat(ctx.resolveTemplate("reports/{{ run_timestamp }}/summary.md"))
                .isEqualTo("reports/20261102-081530/summary.md");
    }

    @Test
    void run_emitsTheStepEvents() {
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).input("echo").output("ok").durationMs(100L).build());

        run(null);

        assertThat(events.all()).containsExactly(
                new StepStarted(0, null, "simple", 0, true),
                new OutputRecorded(0, null, "ok"),
                new StepFinished(0, null, "simple", StepStatus.COMPLETED, "echo", 100L, Map.of("blockType", "simple", "blockIndex", 0)),
                new StepStarted(1, null, "simple", 1, true),
                new OutputRecorded(1, null, "ok"),
                new StepFinished(1, null, "simple", StepStatus.COMPLETED, "echo", 100L, Map.of("blockType", "simple", "blockIndex", 1)),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));
    }

    @Test
    void run_mergesDefaultContextWithInputContext() {
        defaultContext = Map.of("branch", "main", "env", "prod");
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture())).thenReturn(completed(0, "ok"));

        run(Map.of("branch", "develop"));

        // branch=develop (overridden) + env=prod (default)
        ExecutionContext captured = ctxCaptor.getValue();
        assertThat(captured.getVariable("branch")).isEqualTo("develop");
        assertThat(captured.getVariable("env")).isEqualTo("prod");
    }

    @Test
    void run_usesDefaultContextWhenNoInputProvided() {
        defaultContext = Map.of("branch", "main");
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture())).thenReturn(completed(0, "ok"));

        run(null);

        assertThat(ctxCaptor.getValue().getVariable("branch")).isEqualTo("main");
    }

    @Test
    void run_lastBlockOutputContainsGateStatus_reportsTheGate() {
        onlyBlock("simple", Map.of("command", "analyze"));
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(completed(0,
                "Analysis done.\n\ngate_status: RED\ngate_category: lint-errors\ngate_description: Found 3 issues"));

        RunOutcome outcome = run(null);

        assertThat(outcome.gate())
                .containsEntry("gate_status", "RED")
                .containsEntry("gate_category", "lint-errors")
                .containsEntry("gate_description", "Found 3 issues");
        assertThat(finished().gate()).isEqualTo(outcome.gate());
    }

    @Test
    void run_lastBlockOutputWithoutGateStatus_reportsNoGate() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "Everything looks fine, no issues."));

        RunOutcome outcome = run(null);

        assertThat(outcome.gate()).isEmpty();
        assertThat(finished().gate()).isEmpty();
    }

    @Test
    void run_endsWithRunFinished() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "ok"));

        run(null);

        assertThat(events.of(RunFinished.class)).hasSize(1);
        assertThat(finished().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void cancellationDuringLastStepWinsOverProcessFailure() {
        blocks = new ArrayList<>(List.of(blocks.getFirst()));
        when(simpleBlockExecutor.execute(any(), any())).thenAnswer(inv -> {
            ((ExecutionContext) inv.getArgument(1)).cancel();
            return failed(0, "terminated");
        });

        RunOutcome outcome = run(Map.of());

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outcome.error()).isNull();
        assertThat(outcome.steps()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).as("the cancel ended its work").isEqualTo(StepStatus.CANCELLED);
            assertThat(step.getOutput()).isEqualTo("terminated");
        });
        assertThat(events.of(StepFinished.class)).extracting(StepFinished::status)
                .containsExactly(StepStatus.CANCELLED);
    }

    /** on_failure: continue continues past a failure, never past a cancel; the run's end is as before. */
    @Test
    void aStepEndedByTheCancel_withContinueOnFailure_startsNoFurtherBlock() {
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple")
                        .config(new HashMap<>(Map.of("command", "long", "on_failure", "continue"))).build(),
                BlockDefinition.builder().type("simple")
                        .config(new HashMap<>(Map.of("command", "must not run"))).build()));
        when(simpleBlockExecutor.execute(any(), any())).thenAnswer(inv -> {
            control.cancel();
            return failed(0, "terminated");
        });

        RunOutcome outcome = run(null);

        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outcome.steps()).extracting(StepResult::getStatus).containsExactly(StepStatus.CANCELLED);
        assertThat(finished()).isEqualTo(new RunFinished(RunStatus.CANCELLED, Map.of(), null));
    }

    /**
     * The run's cancel decides, not the exception's class: an executor that throws anything after the cancel ends its
     * step and the run CANCELLED, without an error, as any other cancel does; the next block does not run.
     */
    @Test
    void anExecutorThrowingAnyExceptionAfterTheCancel_endsTheRunCancelled_withoutAnError() {
        when(simpleBlockExecutor.execute(any(), any())).thenAnswer(inv -> {
            control.cancel();
            throw new IllegalStateException("stream closed");
        });

        RunOutcome outcome = run(null);

        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outcome.error()).isNull();
        assertThat(outcome.steps()).extracting(StepResult::getStatus).containsExactly(StepStatus.CANCELLED);
        assertThat(finished()).isEqualTo(new RunFinished(RunStatus.CANCELLED, Map.of(), null));
    }

    /** Without a cancel, an executor's exception still ends the run FAILED with the error. */
    @Test
    void anExecutorThrowingWithoutACancel_stillEndsTheRunFailed() {
        when(simpleBlockExecutor.execute(any(), any())).thenThrow(new IllegalStateException("stream closed"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("IllegalStateException: stream closed");
        assertThat(outcome.steps()).extracting(StepResult::getStatus).containsExactly(StepStatus.FAILED);
    }

    /** A failed record write ends the run FAILED even after a cancel: the record is incomplete. */
    @Test
    void aFailedRecordWriteAfterTheCancel_stillEndsTheRunFailed() {
        when(simpleBlockExecutor.execute(any(), any())).thenAnswer(inv -> {
            control.cancel();
            throw new EventLogException(new java.io.IOException("disk full"));
        });

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).contains("disk full");
    }

    /** An executor that throws because of the cancel ends the run CANCELLED and its step CANCELLED. */
    @Test
    void anExecutorThrowingAfterTheCancel_endsItsStepCancelled() {
        blocks = new ArrayList<>(List.of(blocks.getFirst()));
        when(simpleBlockExecutor.execute(any(), any())).thenAnswer(inv -> {
            control.cancel();
            throw new java.util.concurrent.CancellationException("Execution cancelled");
        });

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outcome.steps()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo(StepStatus.CANCELLED);
            assertThat(step.getOutput()).isEqualTo("Cancelled: the run was cancelled while this step ran");
        });
    }

    /**
     * A CancellationException although nobody cancelled the run is a failure, of the step and of the run: every real
     * cancel (Ctrl-C, cancel, a refused question, a cancelled child) sets the run's cancel, and a run that ends
     * CANCELLED without one would say in the record that someone cancelled it when nobody did.
     */
    @Test
    void aCancellationWithoutACancelOfTheRun_failsTheRun_withTheErrorOfItsStep() {
        blocks = new ArrayList<>(List.of(blocks.getFirst()));
        when(simpleBlockExecutor.execute(any(), any()))
                .thenThrow(new java.util.concurrent.CancellationException("someone else's future"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("CancellationException: someone else's future");
        assertThat(outcome.steps().getFirst().getStatus()).isEqualTo(StepStatus.FAILED);
    }

    /**
     * A JVM Error during the cancel: the run ends CANCELLED without an error, as for any exception after the cancel,
     * and its step stays CANCELLED, but keeps the Error in its output.
     */
    @Test
    void anErrorAfterTheCancel_endsTheRunCancelled_andItsStepKeepsTheError() {
        blocks = new ArrayList<>(List.of(blocks.getFirst()));
        when(simpleBlockExecutor.execute(any(), any())).thenAnswer(inv -> {
            control.cancel();
            throw new OutOfMemoryError("Java heap space");
        });

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outcome.error()).isNull();
        assertThat(outcome.steps()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo(StepStatus.CANCELLED);
            assertThat(step.getOutput()).isEqualTo("Cancelled: the run was cancelled while this step ran"
                    + "\nEnded by java.lang.OutOfMemoryError: Java heap space");
        });
    }

    @Test
    void run_stepOutputVariables_setCorrectly() {
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(completed(0, "output-from-step-0"), completed(1, "output-from-step-1"));

        run(null);

        // after both blocks the context has the step output variables
        ExecutionContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getVariable("step_0_output")).isEqualTo("output-from-step-0");
        assertThat(ctx.getVariable("step_1_output")).isEqualTo("output-from-step-1");
    }

    @Test
    void run_failedRun_reportsTheError() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed(0, "big error"));

        RunOutcome outcome = run(null);

        assertThat(finished().status()).isEqualTo(RunStatus.FAILED);
        assertThat(finished().error()).isNotNull().isEqualTo(outcome.error());
    }

    @Test
    void run_failedStep_keepsTheErrorTail() {
        String output = "x".repeat(970) + "HTTP 500 Internal Server Error";
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed(0, output));

        RunOutcome outcome = run(null);

        assertThat(outcome.error()).endsWith("HTTP 500 Internal Server Error");
        assertThat(outcome.error()).contains("[... skipped 500 of 1000 characters ...]");
    }

    @Test
    void run_failedStepWithFailureSummary_runErrorStartsWithIt() {
        // a block that knows where it failed (foreach: which item) beats the output cut
        StepResult result = failed(0, "[a]: GATE PASS\n[b]: API Error\n[c]: GATE PASS");
        StepFailureSummary.put(result, "1/3 items failed: b\n[b]: API Error");
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(result);

        RunOutcome outcome = run(null);

        assertThat(outcome.error()).isEqualTo("Step 0 (simple) failed: 1/3 items failed: b\n[b]: API Error");
    }

    @Test
    void run_exceptionDuringExecution_failsWithMessage() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Unexpected failure"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("RuntimeException: Unexpected failure");
    }

    @Test
    void run_yellowGateStatus_completes() {
        onlyBlock("simple", Map.of("command", "check"));
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(completed(0,
                "Check done.\n\ngate_status: YELLOW\ngate_category: warnings\ngate_description: 2 minor issues"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.gate()).containsEntry("gate_status", "YELLOW").containsEntry("gate_category", "warnings");
    }

    @Test
    void run_yellowGateStatus_isReportedAsAnEvent() {
        onlyBlock("simple", Map.of("command", "lint"));
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "gate_status: YELLOW\ngate_category: lint"));

        run(null);

        assertThat(events.of(GateReported.class)).containsExactly(new GateReported(0, GateStatus.YELLOW, "lint", null));
    }

    @Test
    void run_greenGateStatus_completes() {
        onlyBlock("simple", Map.of("command", "check"));
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "All good.\n\ngate_status: GREEN\ngate_category: quality"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void run_multipleBlocks_highestGateSeverityWins() {
        // first block GREEN, second block YELLOW: YELLOW wins
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(
                completed(0, "gate_status: GREEN\ngate_category: tests"),
                completed(1, "gate_status: YELLOW\ngate_category: lint\ngate_description: 2 warnings"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.gate()).containsEntry("gate_status", "YELLOW").containsEntry("gate_category", "lint");
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_aggregatesGateSummaryAcrossSteps() {
        // two blocks with mixed gates: gate_summary aggregates both items and counts
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(
                completed(0, "gate_status: GREEN\ngate_category: tests"),
                completed(1, "gate_status: YELLOW\ngate_category: lint\ngate_description: 2 warnings"));

        RunOutcome outcome = run(null);

        Map<String, Object> summary = (Map<String, Object>) outcome.gate().get("gate_summary");
        assertThat(summary).isNotNull().containsEntry("highest", "YELLOW");
        Map<String, Integer> counts = (Map<String, Integer>) summary.get("counts_by_status");
        assertThat(counts).containsEntry("GREEN", 1).containsEntry("YELLOW", 1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) summary.get("items");
        assertThat(items).hasSize(2);
        assertThat(items).anyMatch(i -> "GREEN".equals(i.get("status")) && "tests".equals(i.get("category")));
        assertThat(items).anyMatch(i -> "YELLOW".equals(i.get("status")) && "lint".equals(i.get("category")));
    }

    @Test
    void run_redGateStatus_stillCompletes() {
        // a RED gate does not change the run status
        onlyBlock("simple", Map.of("command", "scan"));
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(completed(0,
                "gate_status: RED\ngate_category: security\ngate_description: critical vulnerability"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.gate()).containsEntry("gate_status", "RED");
    }

    @Test
    void cancel_duringExecution_cancelsTheRun() {
        // cancel the run mid-flight: after block 0, before block 1
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            control.cancel();
            return completed(0, "ok");
        });

        RunOutcome outcome = run(null);

        verify(simpleBlockExecutor, times(1)).execute(any(BlockDefinition.class), any(ExecutionContext.class));
        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(finished().status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void run_blockThrowsWorkflowExecutionException_failsWithMessage() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new WorkflowExecutionException("custom block failure"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("WorkflowExecutionException: custom block failure");
        assertThat(events.of(RunFinished.class)).hasSize(1);
    }

    @Test
    void run_blockThrowsExceptionWithNullMessage_fallsBackToClassName() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException());

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("RuntimeException: java.lang.RuntimeException");
    }

    @Test
    void run_secondBlockThrows_firstBlockStillReportedAsCompleted() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "ok"))
                .thenThrow(new RuntimeException("second block crashed"));

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("RuntimeException: second block crashed");
        // the first block's step was reported before the crash, the second one ends FAILED instead of staying RUNNING
        assertThat(events.of(StepFinished.class)).extracting(StepFinished::status)
                .containsExactly(StepStatus.COMPLETED, StepStatus.FAILED);
        assertThat(outcome.steps().get(1).getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.steps().get(1).getOutput()).isEqualTo("RuntimeException: second block crashed");
    }

    @Test
    void run_blockThrows_finalStatusIsStillReported() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("boom"));

        run(null);

        assertThat(finished().status()).isEqualTo(RunStatus.FAILED);
        assertThat(finished().error()).isNotNull();
    }

    // -- sub-workflow failure propagation ----------------------------------------------------------------

    @Test
    void run_subWorkflowBlockReturnsFailed_parentRunFails() {
        BlockExecutor subWorkflowExecutor = executorOf("sub-workflow");
        when(subWorkflowExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed(0, "Sub-workflow run-X failed: timeout"));
        onlyBlock("sub-workflow", Map.of("workflow_id", "child-wf"));

        RunOutcome outcome = run(newEngine(List.of(simpleBlockExecutor, subWorkflowExecutor)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).contains("Sub-workflow run-X failed");
    }

    @Test
    void run_subWorkflowBlockThrows_parentRunFails() {
        BlockExecutor subWorkflowExecutor = executorOf("sub-workflow");
        when(subWorkflowExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new WorkflowExecutionException("Sub-workflow timed out after 30 minutes"));
        onlyBlock("sub-workflow", Map.of("workflow_id", "child-wf"));

        RunOutcome outcome = run(newEngine(List.of(simpleBlockExecutor, subWorkflowExecutor)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("WorkflowExecutionException: Sub-workflow timed out after 30 minutes");
    }

    // -- child runs (sub-workflow, foreach with workflow_id) ---------------------------------------------

    /** A child workflow of one {@code probe} block. */
    private static WorkflowDefinition childWorkflow() {
        return new WorkflowDefinition("child-wf", "Child", null,
                List.of(BlockDefinition.builder().type("probe").name("look").config(new HashMap<>()).build()),
                null, null, null, null, Map.of(), Map.of(), null, null, null);
    }

    /**
     * A child runs in-process through the same engine, as a run of its own: its own run id, {@code SUB_WORKFLOW},
     * the inputs it was given. Its events reach the parent's sink, each wrapped with the child's run id, and its
     * outcome comes back to the block that started it.
     */
    @Test
    void run_childRun_isARunOfItsOwn_andItsEventsReachTheParentsSinkWrapped() {
        Map<String, Object> seenByChild = new HashMap<>();
        BlockExecutor probe = executorOf("probe");
        when(probe.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext child = inv.getArgument(1);
            seenByChild.putAll(child.getVariables());
            return completed(child.nextStepIndex(), "child says hi");
        });
        List<ChildRuns.ChildRun> started = new ArrayList<>();
        BlockExecutor starter = executorOf("starter");
        when(starter.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext parent = inv.getArgument(1);
            started.add(parent.getChildRuns().run(childWorkflow(), Map.of("topic", "tests")));
            return completed(parent.nextStepIndex(), "started");
        });
        onlyBlock("starter", Map.of());

        RunOutcome outcome = run(newEngine(List.of(starter, probe)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        ChildRuns.ChildRun child = started.getFirst();
        assertThat(child.runId()).isNotBlank().isNotEqualTo("run-1");
        assertThat(child.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(child.steps()).extracting(StepResult::getOutput).containsExactly("child says hi");
        assertThat(seenByChild).containsEntry("workflow_run_id", child.runId())
                .containsEntry("workflow_id", "child-wf")
                .containsEntry("trigger_source", "SUB_WORKFLOW")
                .containsEntry("topic", "tests")
                .containsEntry("project_path", "/workspace/test");
        List<ChildRunEvent> childEvents = events.of(ChildRunEvent.class);
        assertThat(childEvents).extracting(ChildRunEvent::runId).containsOnly(child.runId());
        List<RunEvent> wrapped = childEvents.stream().map(ChildRunEvent::event).toList();
        assertThat(wrapped).first().isEqualTo(new StepStarted(0, "look", "probe", 0, false));
        assertThat(wrapped).last().isInstanceOf(RunFinished.class);
        assertThat(events.of(StepStarted.class)).as("only the parent's own step is unwrapped")
                .containsExactly(new StepStarted(0, null, "starter", 0, false));
        assertThat(events.last()).isInstanceOf(RunFinished.class);
    }

    /** A child resolves its own references through what the parent's resolver gives it for that child. */
    @Test
    void run_childRun_getsTheResolverThatTheParentsResolverGivesForTheChild() {
        WorkflowResolver forTheChild = ref -> childWorkflow();
        WorkflowResolver parentResolver = new WorkflowResolver() {
            @Override
            public WorkflowDefinition resolve(String workflowRef) {
                return childWorkflow();
            }

            @Override
            public WorkflowResolver forChild(WorkflowDefinition child) {
                return "child-wf".equals(child.id()) ? forTheChild : this;
            }
        };
        List<WorkflowResolver> seen = new ArrayList<>();
        BlockExecutor probe = executorOf("probe");
        when(probe.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext child = inv.getArgument(1);
            seen.add(child.getWorkflowResolver());
            return completed(child.nextStepIndex(), "ok");
        });
        BlockExecutor starter = executorOf("starter");
        when(starter.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext parent = inv.getArgument(1);
            seen.add(parent.getWorkflowResolver());
            parent.getChildRuns().run(parent.getWorkflowResolver().resolve("child-wf"), Map.of());
            return completed(parent.nextStepIndex(), "started");
        });
        onlyBlock("starter", Map.of());

        newEngine(List.of(starter, probe)).run(workflow(), null,
                options().workflowResolver(parentResolver).build());

        assertThat(seen).containsExactly(parentResolver, forTheChild);
    }

    /** A child shares the parent's run control: a cancel during the child ends both, and nothing starts after it. */
    @Test
    void run_childRun_sharesTheParentsControl() {
        BlockExecutor probe = executorOf("probe");
        when(probe.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            control.cancel();
            ExecutionContext child = inv.getArgument(1);
            return completed(child.nextStepIndex(), "cancelled inside");
        });
        List<ChildRuns.ChildRun> started = new ArrayList<>();
        BlockExecutor starter = executorOf("starter");
        when(starter.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext parent = inv.getArgument(1);
            started.add(parent.getChildRuns().run(childWorkflow(), Map.of()));
            return completed(parent.nextStepIndex(), "back");
        });
        blocks = new ArrayList<>(List.of(BlockDefinition.builder().type("starter").config(new HashMap<>()).build(),
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "late"))).build()));

        RunOutcome outcome = run(newEngine(List.of(starter, probe, simpleBlockExecutor)), null);

        assertThat(started.getFirst().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        verify(simpleBlockExecutor, never()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void stepStarted_carriesThePositionAndWhetherTheStepUsesAi() {
        BlockExecutor shellExec = executorOf("shell");
        when(shellExec.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(completed(0, "ok"));
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("shell").name("a").config(new HashMap<>(Map.of("command", "x"))).build(),
                BlockDefinition.builder().type("simple").name("b").config(new HashMap<>(Map.of("prompt", "y"))).build()));
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(completed(1, "ok"));

        run(newEngine(List.of(shellExec, simpleBlockExecutor)), null);

        assertThat(events.of(StepStarted.class)).containsExactly(
                new StepStarted(0, "a", "shell", 0, false),
                new StepStarted(1, "b", "simple", 1, true));
    }

    @Test
    void stepFinished_carriesTheStepMetadata() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("exitCode", 0);
        withNull.put("model", null);
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").metadata(withNull).build());

        RunOutcome outcome = run(null);

        List<StepFinished> finished = events.of(StepFinished.class);
        assertThat(finished).hasSize(2);
        for (int i = 0; i < finished.size(); i++) {
            assertThat(finished.get(i).metadata()).isEqualTo(outcome.steps().get(i).getMetadata());
        }
        assertThat(finished.getFirst().metadata()).containsEntry("exitCode", 0).containsKey("model")
                .containsEntry("blockIndex", 0);
        assertThat(finished.getFirst().metadata()).isUnmodifiable();
    }

    // -- block-type dispatching --------------------------------------------------------------------------

    @Test
    void run_shellBlock_dispatchesToShellExecutor() {
        BlockExecutor shellExec = executorOf("shell");
        when(shellExec.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(completed(0, "ok"));
        onlyBlock("shell", Map.of("command", "echo hi"));

        RunOutcome outcome = run(newEngine(List.of(shellExec)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        verify(shellExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void run_agenticBlock_dispatchesToAgenticExecutor() {
        BlockExecutor agenticExec = executorOf("agentic");
        when(agenticExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "agentic done"));
        onlyBlock("agentic", Map.of("goal", "do something", "tools", "none"));

        RunOutcome outcome = run(newEngine(List.of(agenticExec)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        verify(agenticExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void run_foreachBlock_dispatchesToForeachExecutor() {
        BlockExecutor foreachExec = executorOf("foreach");
        when(foreachExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "foreach done"));
        onlyBlock("foreach", Map.of("items", List.of("a", "b"), "execution_mode", "shell"));

        RunOutcome outcome = run(newEngine(List.of(foreachExec)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        verify(foreachExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void run_conditionalBlock_dispatchesToConditionalExecutor() {
        BlockExecutor conditionalExec = executorOf("conditional");
        when(conditionalExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "condition met"));
        onlyBlock("conditional", Map.of("condition", "true", "execution_mode", "shell"));

        RunOutcome outcome = run(newEngine(List.of(conditionalExec)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        verify(conditionalExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void run_unknownBlockType_runFails() {
        onlyBlock("nonexistent-type", Map.of());

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(events.of(StepStarted.class)).isEmpty();
    }

    @Test
    void run_blockExecutorThrowsRuntimeException_runFails() {
        BlockExecutor flaky = executorOf("simple");
        when(flaky.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Timeout after 30s"));

        RunOutcome outcome = run(newEngine(List.of(flaky)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isNotNull();
    }

    @Test
    void run_foreachBlockReturnsFailure_parentRunFails() {
        BlockExecutor foreachExec = executorOf("foreach");
        when(foreachExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed(0, "iteration 2 failed"));
        onlyBlock("foreach", Map.of("items", List.of("a", "b"), "execution_mode", "shell"));

        RunOutcome outcome = run(newEngine(List.of(foreachExec)), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
    }

    // -- run control -------------------------------------------------------------------------------------

    /** A cancel that arrives before the run started is honoured: no block runs. */
    @Test
    void run_cancelledBeforeItStarts_endsCancelledWithoutRunningABlock() {
        control.cancel();

        RunOutcome outcome = run(null);

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outcome.error()).isNull();
        assertThat(outcome.steps()).isEmpty();
        verify(simpleBlockExecutor, never()).execute(any(), any());
        assertThat(events.all()).containsExactly(new RunFinished(RunStatus.CANCELLED, Map.of(), null));
    }

    /** A run cancelled before it started has no checkpoint: there is nothing a resume could go on from. */
    @Test
    void run_cancelledBeforeItStarts_writesNoCheckpoint() {
        control.cancel();

        RunOutcome outcome = engine.run(workflow(), null, options().checkpoints(true).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(events.all()).containsExactly(new RunFinished(RunStatus.CANCELLED, Map.of(), null));
    }

    /** A run its start refuses says why, also when a cancel came before it started. */
    @Test
    void run_refusedAtItsStart_staysFailedWithItsReason_whenACancelCameBeforeIt() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("branch", null);
        control.cancel();

        RunOutcome outcome = run(inputs);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("Failed to initialize workflow data: variable 'branch' has no value");
    }

    /** A cancel that comes between two blocks, while the checkpoint after the first is written, starts no further one. */
    @Test
    void run_cancelledBetweenTwoBlocks_endsCancelled_andStartsNoFurtherBlock() {
        RecordingRunEvents cancellingSink = new RecordingRunEvents() {
            @Override
            public void emit(RunEvent event) {
                super.emit(event);
                if (event instanceof RunEvent.Checkpoint checkpoint && checkpoint.nextPosition() == 1) {
                    control.cancel();
                }
            }
        };
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "ok"));

        RunOutcome outcome = engine.run(workflow(), null, options().events(cancellingSink).checkpoints(true).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(cancellingSink.last()).isEqualTo(new RunFinished(RunStatus.CANCELLED, Map.of(), null));
    }

    @Test
    void run_aVariableWithoutValue_failsTheRunBeforeAnyBlock() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("branch", null);

        RunOutcome outcome = run(inputs);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("Failed to initialize workflow data: variable 'branch' has no value");
        verify(simpleBlockExecutor, never()).execute(any(), any());
        assertThat(events.all()).containsExactly(new RunFinished(RunStatus.FAILED, Map.of(), outcome.error()));
    }

    @Test
    void run_aVariableWithoutName_failsTheRunBeforeAnyBlock() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(null, "main");

        RunOutcome outcome = run(inputs);

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).startsWith("Failed to initialize workflow data: ");
        verify(simpleBlockExecutor, never()).execute(any(), any());
    }

    @Test
    void run_pausedBetweenBlocks_reportsPausedAndRunningAgain() {
        RecordingRunEvents resumingSink = new RecordingRunEvents() {
            @Override
            public void emit(RunEvent event) {
                super.emit(event);
                if (event instanceof RunStatusChanged changed && changed.status() == RunStatus.PAUSED) {
                    control.resume();
                }
            }
        };
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            control.pause();
            return completed(0, "ok");
        }).thenReturn(completed(1, "ok"));

        RunOutcome outcome = engine.run(workflow(), null, options().events(resumingSink).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumingSink.of(RunStatusChanged.class))
                .containsExactly(new RunStatusChanged(RunStatus.PAUSED), new RunStatusChanged(RunStatus.RUNNING));
        // the pause sits between the first step's finish and the second step's start
        List<RunEvent> all = resumingSink.all();
        assertThat(all.indexOf(new RunStatusChanged(RunStatus.PAUSED)))
                .isGreaterThan(all.indexOf(resumingSink.of(StepFinished.class).getFirst()))
                .isLessThan(all.indexOf(new StepStarted(1, null, "simple", 1, true)));
    }

    /** A run cancelled while it is paused starts no further block. */
    @Test
    void run_cancelledWhilePaused_startsNoFurtherBlock() {
        RecordingRunEvents cancellingSink = new RecordingRunEvents() {
            @Override
            public void emit(RunEvent event) {
                super.emit(event);
                if (event instanceof RunStatusChanged changed && changed.status() == RunStatus.PAUSED) {
                    control.cancel();
                }
            }
        };
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            control.pause();
            return completed(0, "ok");
        });

        RunOutcome outcome = engine.run(workflow(), null, options().events(cancellingSink).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(cancellingSink.of(StepStarted.class)).containsExactly(new StepStarted(0, null, "simple", 0, true));
        assertThat(cancellingSink.of(RunStatusChanged.class)).containsExactly(new RunStatusChanged(RunStatus.PAUSED));
        assertThat(cancellingSink.last()).isEqualTo(new RunFinished(RunStatus.CANCELLED, Map.of(), null));
    }

    /**
     * The thread of a paused run is interrupted (the caller shuts down): the run ends CANCELLED instead of going on as
     * RUNNING, starts no further block, and the thread keeps its interrupt flag.
     */
    @Test
    void run_interruptedWhilePaused_endsCancelledAndKeepsTheInterruptFlag() {
        RecordingRunEvents interruptingSink = new RecordingRunEvents() {
            @Override
            public void emit(RunEvent event) {
                super.emit(event);
                if (event instanceof RunStatusChanged changed && changed.status() == RunStatus.PAUSED) {
                    Thread.currentThread().interrupt();   // the run thread itself waits while paused
                }
            }
        };
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            control.pause();
            return completed(0, "ok");
        });

        RunOutcome outcome;
        boolean interruptFlag;
        try {
            outcome = engine.run(workflow(), null, options().events(interruptingSink).build());
        } finally {
            interruptFlag = Thread.interrupted();   // clears the flag for the next test
        }

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(interruptFlag).as("interrupt flag restored").isTrue();
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(interruptingSink.of(RunStatusChanged.class)).containsExactly(new RunStatusChanged(RunStatus.PAUSED));
        assertThat(interruptingSink.last()).isEqualTo(new RunFinished(RunStatus.CANCELLED, Map.of(), null));
    }

    /**
     * A run shares its control with a child run (sub-workflow). After the child ended, a cancel must reach the
     * parent again: here it cancels the approval the parent's next block waits for.
     */
    @Test
    void run_sharedControl_aCancelAfterAChildRunReachesTheParentAgain() {
        BlockExecutor childLauncher = executorOf("sub-workflow");
        BlockExecutor approval = executorOf("approval");
        WorkflowEngine parentEngine = newEngine(List.of(simpleBlockExecutor, childLauncher, approval));
        RunOptions parentOptions = options().build();
        WorkflowDefinition child = new WorkflowDefinition("wf-child", "Child", null, List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo child"))).build()),
                null, null, null, null, Map.of(), Map.of(), null, null, null);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "child step"));
        when(childLauncher.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            RunOutcome childOutcome = parentEngine.run(child, null,
                    parentOptions.toBuilder().runId("child-1").events(new RecordingRunEvents()).build());
            return completed(0, "child " + childOutcome.status());
        });
        when(approval.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(1);
            var answer = ctx.requestApproval(ApprovalRequest.builder().blockId("approval-1").question("Go on?").build());
            control.cancel();
            return completed(1, ctx.awaitApproval(answer, 2, TimeUnit.SECONDS));
        });
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("sub-workflow").config(new HashMap<>(Map.of("workflow_id", "wf-child"))).build(),
                BlockDefinition.builder().type("approval").config(new HashMap<>(Map.of("question", "Go on?"))).build()));

        RunOutcome outcome = parentEngine.run(workflow(), null, parentOptions);

        assertThat(outcome.steps().getFirst().getOutput()).isEqualTo("child COMPLETED");
        assertThat(outcome.status()).as("the parent's approval was cancelled, not left to time out")
                .isEqualTo(RunStatus.CANCELLED);
    }

    /** After a child run ended, an answer through the shared control reaches the question of the parent's next block. */
    @Test
    void run_sharedControl_anAnswerAfterAChildRunReachesTheParentsQuestion() {
        BlockExecutor childLauncher = executorOf("sub-workflow");
        BlockExecutor approval = executorOf("approval");
        WorkflowEngine parentEngine = newEngine(List.of(simpleBlockExecutor, childLauncher, approval));
        RunOptions parentOptions = options().build();
        WorkflowDefinition child = new WorkflowDefinition("wf-child", "Child", null, List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo child"))).build()),
                null, null, null, null, Map.of(), Map.of(), null, null, null);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "child step"));
        when(childLauncher.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            RunOutcome childOutcome = parentEngine.run(child, null,
                    parentOptions.toBuilder().runId("child-1").events(new RecordingRunEvents()).build());
            return completed(0, "child " + childOutcome.status());
        });
        when(approval.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(1);
            var answer = ctx.requestApproval(ApprovalRequest.builder().blockId("approval-1").question("Go on?")
                    .options(List.of("Yes", "No")).build());
            control.answer("Yes");
            return completed(1, ctx.awaitApproval(answer, 2, TimeUnit.SECONDS));
        });
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("sub-workflow").config(new HashMap<>(Map.of("workflow_id", "wf-child"))).build(),
                BlockDefinition.builder().type("approval").config(new HashMap<>(Map.of("question", "Go on?"))).build()));

        RunOutcome outcome = parentEngine.run(workflow(), null, parentOptions);

        assertThat(outcome.status()).as(String.valueOf(outcome.error())).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps()).extracting(StepResult::getOutput).containsExactly("child COMPLETED", "Yes");
    }

    // -- a failing event sink (fail closed) --------------------------------------------------------------

    @Test
    void run_eventSinkFailsDuringAStep_stopsTheRunFailed() {
        RecordingRunEvents failingOnce = new RecordingRunEvents() {
            private boolean failed;

            @Override
            public void emit(RunEvent event) {
                if (!failed && event instanceof OutputRecorded) {
                    failed = true;
                    throw new IllegalStateException("disk full");
                }
                super.emit(event);
            }
        };
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "ok"));

        RunOutcome outcome = engine.run(workflow(), null, options().events(failingOnce).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).startsWith(EventLogException.MESSAGE_PREFIX)
                .isEqualTo("event log write failed: IllegalStateException: disk full");
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(failingOnce.of(StepStarted.class)).containsExactly(new StepStarted(0, null, "simple", 0, true));
        assertThat(failingOnce.last()).isEqualTo(new RunFinished(RunStatus.FAILED, Map.of(), outcome.error()));
    }

    /**
     * A record write that bypasses the run's sink (an AI call's {@code ai.*} events) and fails ends the run as an
     * event-log failure too, with the same error.
     */
    @Test
    void run_aBlockThatFailsToWriteTheRecord_endsTheRunAsAnEventLogFailure() {
        RecordingRunEvents events = new RecordingRunEvents();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new EventLogException(new IllegalStateException("disk full")));

        RunOutcome outcome = engine.run(workflow(), null, options().events(events).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("event log write failed: IllegalStateException: disk full");
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(events.last()).isEqualTo(new RunFinished(RunStatus.FAILED, Map.of(), outcome.error()));
    }

    /** A run whose end cannot be recorded must not look finished. */
    @Test
    void run_eventSinkFailsOnRunFinished_throws() {
        RunEvents brokenAtTheEnd = event -> {
            if (event instanceof RunFinished) {
                throw new IllegalStateException("disk full");
            }
        };
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(completed(0, "ok"));

        assertThatThrownBy(() -> engine.run(workflow(), null, options().events(brokenAtTheEnd).build()))
                .isInstanceOf(EventLogException.class)
                .hasMessage("event log write failed: IllegalStateException: disk full")
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(simpleBlockExecutor, times(2)).execute(any(), any());
    }

    /** The failure while a step waits is not swallowed, even when the block catches it and goes on. */
    @Test
    void run_eventSinkFailsWhileAStepWaits_stopsTheRunFailed() {
        RecordingRunEvents failingOnWait = new RecordingRunEvents() {
            @Override
            public void emit(RunEvent event) {
                if (event instanceof StepWaiting) {
                    throw new IllegalStateException("disk full");
                }
                super.emit(event);
            }
        };
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(1);
            try {
                ctx.requestApproval(ApprovalRequest.builder().blockId("approval-0").question("Go?").build());
            } catch (EventLogException caught) {
                // a block that catches everything and goes on
            }
            return completed(0, "answered");
        });

        RunOutcome outcome = engine.run(workflow(), null, options().events(failingOnWait).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("event log write failed: IllegalStateException: disk full");
        verify(simpleBlockExecutor, times(1)).execute(any(), any());
        assertThat(failingOnWait.of(StepStarted.class)).containsExactly(new StepStarted(0, null, "simple", 0, true));
        assertThat(failingOnWait.last()).isEqualTo(new RunFinished(RunStatus.FAILED, Map.of(), outcome.error()));
    }

    // -- taint propagation end to end --------------------------------------------------------------------

    @Test
    void run_untrustedInput_blockOutputsAreProtectedInLaterShellTemplates() {
        AtomicInteger calls = new AtomicInteger();
        List<String> resolved = new ArrayList<>();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(1);
            for (String line : List.of("echo {{ previous_output }}", "echo {{ step_0_output }}", "echo {{ branch }}")) {
                try {
                    resolved.add(ctx.resolveShellTemplate(line, false));
                } catch (traceworks.core.context.ShellLineRefusedException e) {
                    resolved.add("refused: " + line);
                }
            }
            return completed(calls.getAndIncrement(), "x; id");
        });

        run(new HashMap<>(Map.of(
                "branch", "main; id",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch"))));

        // block 1 is refused only the untrusted variable, block 2 also the tainted output
        assertThat(resolved.subList(0, 3)).containsExactly("echo ", "echo {{ step_0_output }}",
                "refused: echo {{ branch }}");
        assertThat(resolved.subList(3, 6)).containsExactly("refused: echo {{ previous_output }}",
                "refused: echo {{ step_0_output }}", "refused: echo {{ branch }}");
    }
}
