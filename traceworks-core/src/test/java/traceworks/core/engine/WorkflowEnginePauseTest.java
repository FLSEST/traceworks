package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.context.RecordedItems;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunParked;
import traceworks.core.port.RunEvent.RunStatusChanged;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pause that parks: with {@link RunOptions#parkOnPause} on, a paused run does not wait in the process. It finishes
 * the step or the {@code foreach} item in flight and ends {@code PARKED}, its last event a {@link RunParked} of the
 * type {@code pause}; a resume from its last checkpoint goes on. Each resume here runs in a {@link Session} of its
 * own, as another process would, and the counts of the commands show that nothing ran twice.
 */
@Timeout(30)
class WorkflowEnginePauseTest {

    private static final Instant NOW = Instant.parse("2026-11-03T09:00:00Z");

    private final MapWorkflowResolver workflows = new MapWorkflowResolver();

    @Test
    void aPauseBetweenTwoBlocks_parksTheRun_andTheResumeGoesOn() {
        Session first = new Session();
        first.commands.on("./a", request -> {
            first.control.pause();
            return FakeCommandRunner.ok("a done");
        });

        RunOutcome parked = first.run(threeShells());

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        assertThat(first.events.last()).isEqualTo(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));
        assertThat(first.events.of(RunFinished.class)).isEmpty();
        assertThat(first.events.of(RunStatusChanged.class)).as("it does not wait in the process").isEmpty();
        assertThat(first.events.of(StepFinished.class)).extracting(StepFinished::stepIndex).containsExactly(0);
        assertThat(first.events.of(StepStarted.class)).hasSize(1);
        assertThat(first.positions()).containsExactly(0, 1);

        Session second = new Session();
        second.commands.on("./b", "b done").on("./c", "c done");
        RunOutcome resumed = second.resume(threeShells(), first.lastCheckpoint(), RecordedItems.NONE);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(first.commands.commands()).containsExactly("./a");
        assertThat(second.commands.commands()).containsExactly("./b", "./c");
    }

    /** The step in flight is finished first: a pause during the last block parks nothing. */
    @Test
    void aPauseDuringTheLastBlock_letsTheRunComplete() {
        Session session = new Session();
        session.commands.on("./a", "a done").on("./b", "b done").on("./c", request -> {
            session.control.pause();
            return FakeCommandRunner.ok("c done");
        });

        RunOutcome outcome = session.run(threeShells());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(session.events.last()).isInstanceOf(RunFinished.class);
    }

    @Test
    void aPauseBetweenTwoItems_parksTheLoop_andTheResumeTakesTheFinishedItemsOver() {
        Session first = new Session();
        first.commands.on("./work ", request -> {
            if (request.command().endsWith(" b")) {
                first.control.pause();
            }
            return FakeCommandRunner.ok("did" + request.command().substring("./work".length()));
        });

        RunOutcome parked = first.run(loopThenReport());

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        assertThat(first.events.last()).isEqualTo(new RunParked(0, RunParked.PAUSE));
        assertThat(first.events.of(ForEachItemFinished.class)).extracting(ForEachItemFinished::item)
                .containsExactly("a", "b");
        assertThat(first.events.of(ForEachItemStarted.class)).as("item c did not start").hasSize(2);
        assertThat(first.events.of(StepFinished.class)).as("the loop is not finished").isEmpty();
        assertThat(parked.steps().getFirst().getStatus()).isEqualTo(StepStatus.RUNNING);
        assertThat(first.positions()).containsExactly(0);

        // The resume gets the items as the record of the first process holds them
        List<ForEachItemFinished> recorded = first.events.of(ForEachItemFinished.class);
        RecordedItems items = (step, index, item) -> step == 0 && index < recorded.size()
                && recorded.get(index).item().equals(item)
                ? new RecordedItems.Completed(recorded.get(index).output(), 100 + index) : null;
        Session second = new Session();
        second.commands.on("./work ", request -> FakeCommandRunner.ok(
                "did" + request.command().substring("./work".length()))).on("./report", "reported");
        RunOutcome resumed = second.resume(loopThenReport(), first.lastCheckpoint(), items);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(first.commands.commands()).containsExactly("./work a", "./work b");
        assertThat(second.commands.commands()).containsExactly("./work c", "./work d",
                "./report [a]: did a\n[b]: did b\n[c]: did c\n[d]: did d");
        assertThat(resumed.steps().getFirst().getOutput()).isEqualTo("[a]: did a\n[b]: did b\n[c]: did c\n[d]: did d");
    }

    /** A cancel wins over a pause: the run ends CANCELLED, final, and does not park. */
    @Test
    void aCancelAfterAPause_endsTheRunCancelled() {
        Session session = new Session();
        session.commands.on("./work ", request -> {
            session.control.pause();
            session.control.cancel();
            return FakeCommandRunner.ok("did");
        });

        RunOutcome outcome = session.run(loopThenReport());

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(session.events.last()).isInstanceOf(RunFinished.class);
        assertThat(session.events.of(RunParked.class)).isEmpty();
    }

    /**
     * A child run has no checkpoint to park at, and nobody in the process would let it go on: it does not wait, it
     * runs to its end, and the parent parks before its next block.
     */
    @Test
    void aPauseWhileAChildRuns_letsTheChildEnd_andParksTheParentAfterItsStep() {
        workflows.add(workflow("wf-child",
                block("shell", "one", Map.of("command", "./one")),
                block("shell", "two", Map.of("command", "./two"))));
        WorkflowDefinition parent = workflow("wf-parent",
                block("sub-workflow", "child", Map.of("workflow_id", "wf-child")),
                block("shell", "after", Map.of("command", "./after")));
        Session session = new Session();
        session.commands.on("./one", request -> {
            session.control.pause();
            return FakeCommandRunner.ok("one done");
        }).on("./two", "two done");

        RunOutcome outcome = session.run(parent);

        assertThat(outcome.status()).isEqualTo(RunStatus.PARKED);
        assertThat(session.commands.commands()).containsExactly("./one", "./two");
        assertThat(session.events.flattened()).noneMatch(RunStatusChanged.class::isInstance);
        assertThat(session.events.last()).isEqualTo(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));
        assertThat(session.positions()).containsExactly(0, 1);
    }

    /**
     * A loop that runs a workflow per item takes nothing over on a resume, so parking between its items would cost
     * every finished item again: it runs all its items, and the run parks after the step.
     */
    @Test
    void aPauseInALoopOfChildRuns_finishesTheLoop_andParksAfterIt() {
        workflows.add(workflow("wf-child", block("shell", "work", Map.of("command", "./work {{ item }}"))));
        WorkflowDefinition parent = workflow("wf-parent",
                block("foreach", "each", Map.of("items", List.of("a", "b", "c"), "workflow_id", "wf-child")),
                block("shell", "after", Map.of("command", "./after")));
        Session session = new Session();
        session.commands.on("./work ", request -> {
            session.control.pause();
            return FakeCommandRunner.ok("did");
        });

        RunOutcome outcome = session.run(parent);

        assertThat(outcome.status()).isEqualTo(RunStatus.PARKED);
        assertThat(session.commands.commands()).containsExactly("./work a", "./work b", "./work c");
        assertThat(session.events.of(StepFinished.class)).hasSize(1);
        assertThat(session.events.last()).isEqualTo(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));
    }

    /**
     * A loop in a child run has no checkpoint to park at: it runs all its items, the child ends, and the parent parks
     * after its step.
     */
    @Test
    void aPauseInALoopOfAChildRun_letsTheLoopFinish_andParksTheParentAfterItsStep() {
        workflows.add(workflow("wf-child", block("foreach", "each", Map.of("execution_mode", "shell",
                "items", List.of("a", "b", "c"), "command", "./work {{ item }}"))));
        WorkflowDefinition parent = workflow("wf-parent",
                block("sub-workflow", "child", Map.of("workflow_id", "wf-child")),
                block("shell", "after", Map.of("command", "./after")));
        Session session = new Session();
        session.commands.on("./work ", request -> {
            session.control.pause();
            return FakeCommandRunner.ok("did");
        });

        RunOutcome outcome = session.run(parent);

        assertThat(outcome.status()).isEqualTo(RunStatus.PARKED);
        assertThat(session.commands.commands()).containsExactly("./work a", "./work b", "./work c");
        assertThat(session.events.last()).isEqualTo(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));
    }

    private static WorkflowDefinition threeShells() {
        return workflow("wf-three",
                block("shell", "a", Map.of("command", "./a")),
                block("shell", "b", Map.of("command", "./b")),
                block("shell", "c", Map.of("command", "./c")));
    }

    private static WorkflowDefinition loopThenReport() {
        return workflow("wf-loop",
                block("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a", "b", "c", "d"),
                        "command", "./work {{ item }}")),
                block("shell", "report", Map.of("command", "./report {{ block.each.output }}")));
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
        RunOutcome resume(WorkflowDefinition workflow, Checkpoint checkpoint, RecordedItems items) {
            RunSnapshot stored = SnapshotTexts.roundTrip(checkpoint.snapshot());
            return new WorkflowEngine(CoreBlocks.registry(null)).resume(workflow,
                    new ResumePoint(stored, checkpoint.nextPosition(), null, items), options());
        }

        Checkpoint lastCheckpoint() {
            return events.of(Checkpoint.class).getLast();
        }

        List<Integer> positions() {
            return events.of(Checkpoint.class).stream().map(Checkpoint::nextPosition).toList();
        }
    }
}
