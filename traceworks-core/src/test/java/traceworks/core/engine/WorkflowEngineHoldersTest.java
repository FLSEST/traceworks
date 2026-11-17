package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.context.RecordedItems;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.GateStatus;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.GateReported;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunParked;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.testing.FakeAiProvider;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.RecordingRunEvents;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the blocks that hold a command or a workflow do with a pause, an interrupt and a gate, where the answer is
 * the engine's alone and needs no container:
 *
 * <ul>
 *   <li>a pause inside a {@code while} or a {@code retry} stops neither: the loop runs to its own end, the retry
 *       until an attempt completes or its attempts are used up, and the run parks after that step (docs/cli.md,
 *       "pause"). Only a top-level {@code foreach} parks between two of its passes;</li>
 *   <li>(what an interrupt inside one of them costs is in {@code core.app.InterruptTest}, with a real record and
 *       a real resume, and in {@code PauseIT} with containers;)</li>
 *   <li>a gate a child run reports, by a marker or by a failed {@code checked_command}, is the child's: it is in the
 *       parent's events under the child's run id and sets no gate of the parent (docs/cli.md, "When a step
 *       fails").</li>
 * </ul>
 *
 * Each resume runs in a {@link Session} of its own, as another process would.
 */
@Timeout(30)
class WorkflowEngineHoldersTest {

    private static final Instant NOW = Instant.parse("2026-11-17T09:00:00Z");

    private final MapWorkflowResolver workflows = new MapWorkflowResolver();

    /** Breaks when {@code while} asks the run for a pause between two iterations, as {@code foreach} does. */
    @Test
    void aPauseInsideAWhile_letsTheLoopRunToItsEnd_andTheResumeRunsNoIterationAgain() {
        Session first = new Session();
        AtomicInteger passes = new AtomicInteger();
        first.commands.on("./more", request -> FakeCommandRunner.exit(passes.get() < 3 ? 0 : 1, ""));
        first.commands.on("./pass", request -> {
            if (passes.incrementAndGet() == 1) {
                first.control.pause();   // during the first of three iterations
            }
            return FakeCommandRunner.ok("pass " + passes.get());
        });

        RunOutcome parked = first.run(whileThenAfter());

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        assertThat(first.commands.commands()).as("the pause stopped no iteration")
                .containsExactly("./more", "./pass", "./more", "./pass", "./more", "./pass", "./more");
        assertThat(first.events.of(StepFinished.class)).as("the loop's step is finished, not left open")
                .extracting(StepFinished::status).containsExactly(StepStatus.COMPLETED);
        assertThat(first.events.last()).isEqualTo(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));
        assertThat(first.positions()).containsExactly(0, 1);

        Session second = new Session();
        second.commands.on("./after", "after done");
        RunOutcome resumed = second.resume(whileThenAfter(), first.lastCheckpoint());

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(second.commands.commands()).as("nothing of the loop runs again").containsExactly("./after");
        assertThat(resumed.steps().getFirst().getOutput())
                .isEqualTo("[iteration 1]: pass 1\n[iteration 2]: pass 2\n[iteration 3]: pass 3");
    }

    /** Breaks when {@code retry} stops retrying on a pause, or parks between two attempts. */
    @Test
    void aPauseInsideARetry_letsItRetryUntilAnAttemptCompletes_andParksAfterTheStep() {
        Session first = new Session();
        AtomicInteger attempts = new AtomicInteger();
        first.commands.on("./flaky", request -> {
            if (attempts.incrementAndGet() == 1) {
                first.control.pause();   // during the first attempt, which fails
            }
            return attempts.get() < 3 ? FakeCommandRunner.exit(1, "attempt " + attempts.get() + " fails")
                    : FakeCommandRunner.ok("attempt 3 passes");
        });

        RunOutcome parked = first.run(retryThenAfter());

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        assertThat(first.commands.commands()).as("the pause stopped no attempt")
                .containsExactly("./flaky", "./flaky", "./flaky");
        assertThat(first.events.of(StepFinished.class)).extracting(StepFinished::status)
                .containsExactly(StepStatus.COMPLETED);
        assertThat(first.events.last()).isEqualTo(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));

        Session second = new Session();
        second.commands.on("./after", "after done");
        RunOutcome resumed = second.resume(retryThenAfter(), first.lastCheckpoint());

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(second.commands.commands()).as("no attempt runs again").containsExactly("./after");
    }

    /**
     * Breaks when the step that copies a child's output counts as a gate source ({@code derivedOutput}), or when a
     * child's COMPLETED_WITH_ERRORS becomes the parent's status.
     */
    @Test
    void aGateAChildReports_isTheChilds_andSetsNoGateOfTheParent() {
        workflows.add(workflow("wf-marker", block("shell", "report", Map.of("command", "./report"))));
        workflows.add(workflow("wf-checked", block("shell", "build",
                Map.of("command", "./build", "checked_command", true, "on_failure", "continue"))));
        WorkflowDefinition parent = workflow("wf-parent",
                block("sub-workflow", "marker", Map.of("workflow_id", "wf-marker")),
                block("sub-workflow", "checked", Map.of("workflow_id", "wf-checked")),
                block("foreach", "each", Map.of("items", List.of("a"), "workflow_id", "wf-marker")));
        Session session = new Session();
        session.commands.on("./report", "checked\ngate_status: RED\ngate_category: child\ngate_description: red")
                .on("set -e -o pipefail\n./build", FakeCommandRunner.exit(2, "compiling"));

        RunOutcome outcome = session.run(parent);

        assertThat(outcome.status()).as("a child that completed with errors leaves its step and its parent complete")
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps()).extracting(step -> step.getStatus()).containsOnly(StepStatus.COMPLETED);
        assertThat(outcome.steps().get(0).getOutput()).as("the parent's step holds the child's marker as text")
                .contains("gate_status: RED");
        assertThat(outcome.steps().get(1).getOutput()).contains("gate_status: RED");
        assertThat(outcome.steps().get(1).getMetadata()).containsEntry("subRunStatus", "COMPLETED_WITH_ERRORS");
        assertThat(outcome.gate()).as("no gate of the parent").isEmpty();
        assertThat(session.events.of(GateReported.class)).as("no gate event of the parent's own steps").isEmpty();
        assertThat(session.events.of(RunFinished.class).getLast().gate()).isEmpty();
        List<RunEvent> ofChildren = session.events.all().stream().filter(ChildRunEvent.class::isInstance)
                .map(event -> ((ChildRunEvent) event).event()).toList();
        assertThat(ofChildren).filteredOn(GateReported.class::isInstance)
                .extracting(event -> ((GateReported) event).status())
                .as("each child reported its gate, in the parent's events under its own run id")
                .containsExactly(GateStatus.RED, GateStatus.RED, GateStatus.RED);
        assertThat(ofChildren).filteredOn(RunFinished.class::isInstance)
                .extracting(event -> ((RunFinished) event).status())
                .containsExactly(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS, RunStatus.COMPLETED);
        assertThat(ofChildren).filteredOn(RunFinished.class::isInstance)
                .allSatisfy(event -> assertThat(((RunFinished) event).gate()).containsEntry("gate_status", "RED"));
    }

    private static WorkflowDefinition whileThenAfter() {
        return workflow("wf-while",
                block("while", "loop", Map.of("execution_mode", "shell", "condition_command", "./more",
                        "command", "./pass", "max_iterations", 10)),
                block("shell", "after", Map.of("command", "./after")));
    }

    private static WorkflowDefinition retryThenAfter() {
        return workflow("wf-retry",
                block("retry", "flaky", Map.of("execution_mode", "shell", "command", "./flaky", "max_retries", 3,
                        "delay", 0)),
                block("shell", "after", Map.of("command", "./after")));
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, "Workflow " + id, null, List.of(blocks), null, null, null, null, Map.of(),
                Map.of(), null, null, null);
    }

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    /** One process that runs or resumes the workflow: its own ports, events and control, as the CLI's runs. */
    private final class Session {
        final FakeCommandRunner commands = new FakeCommandRunner();
        final RecordingRunEvents events = new RecordingRunEvents();
        final RunControl control = new RunControl();

        RunOptions options() {
            return RunOptions.builder()
                    .runId("run-1")
                    .events(events)
                    .commandRunner(commands)
                    .aiProviders(new FakeAiProvider("CLAUDE").asProviders())
                    .workflowResolver(workflows)
                    .runControl(control)
                    .clock(() -> NOW)
                    .workingDirectory("/work/project")
                    .checkpoints(true).parkOnPause(true).build();
        }

        RunOutcome run(WorkflowDefinition workflow) {
            return new WorkflowEngine(CoreBlocks.registry(null)).run(workflow, null, options());
        }

        /** Resumes from the checkpoint as the run record keeps it: written to JSON and read back. */
        RunOutcome resume(WorkflowDefinition workflow, Checkpoint checkpoint) {
            RunSnapshot stored = SnapshotTexts.roundTrip(checkpoint.snapshot());
            return new WorkflowEngine(CoreBlocks.registry(null)).resume(workflow,
                    new ResumePoint(stored, checkpoint.nextPosition(), null, RecordedItems.NONE), options());
        }

        Checkpoint lastCheckpoint() {
            return events.of(Checkpoint.class).getLast();
        }

        List<Integer> positions() {
            return events.of(Checkpoint.class).stream().map(Checkpoint::nextPosition).toList();
        }
    }
}
