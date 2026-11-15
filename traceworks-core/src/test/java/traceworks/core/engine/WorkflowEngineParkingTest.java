package traceworks.core.engine;

import traceworks.core.record.RunSnapshotJson;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ApprovalRequested;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.OutputRecorded;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunParked;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepWaiting;
import traceworks.core.testing.FakeAiProvider;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.RecordingRunEvents;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Parking: with {@link RunOptions#parkAtQuestions} on, a top-level {@code approval} or {@code human-input} of the root
 * run that would ask a person records the question and ends the run {@code PARKED}, its last event a
 * {@link RunParked}. A resume from the last checkpoint hands the answer to the asking step as a {@link PresetAnswer}.
 * Questions asked inside another block and in child runs never park; they wait in the process as before. Every resume
 * here goes through {@link RunSnapshotJson}, as a resume from the run record does, and runs in a {@link Session} of
 * its own.
 */
class WorkflowEngineParkingTest {

    private static final Instant NOW = Instant.parse("2026-11-03T09:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final List<Session> sessions = new ArrayList<>();

    /** A test that failed while a run waits must not leave the run waiting for 30 minutes. */
    @AfterEach
    void stopTheRuns() {
        sessions.forEach(session -> session.control.cancel());
    }

    @Test
    void topLevelApproval_parks() throws Exception {
        Session session = new Session();
        session.commands.on("./a", "a done").on("./b", "b done");

        RunOutcome outcome = session.run(approvalBetweenTwoShells(), true);

        assertThat(outcome.status()).isEqualTo(RunStatus.PARKED);
        List<RunEvent> events = session.events.all();
        assertThat(events.get(events.size() - 2)).isInstanceOfSatisfying(ApprovalRequested.class, asked -> {
            assertThat(asked.stepIndex()).isEqualTo(1);
            assertThat(asked.type()).isEqualTo("approval");
            assertThat(asked.question()).isEqualTo("Deploy?");
            assertThat(asked.options()).containsExactly("Yes", "Yes always", "No");
        });
        assertThat(session.events.last()).isEqualTo(new RunParked(1, "approval"));
        assertThat(session.events.of(RunFinished.class)).isEmpty();
        assertThat(session.events.of(StepFinished.class)).extracting(StepFinished::stepIndex).containsExactly(0);
        assertThat(session.events.of(OutputRecorded.class)).extracting(OutputRecorded::stepIndex)
                .containsExactly(0);
        assertThat(session.events.of(StepWaiting.class)).as("the step waits, outside the process")
                .containsExactly(new StepWaiting(1, true));
        assertThat(session.positions()).as("no checkpoint after the parked step").containsExactly(0, 1);
        assertThat(session.commands.commands()).containsExactly("./a");
        assertThat(outcome.steps()).extracting(StepResult::getStepIndex, StepResult::getStatus)
                .containsExactly(tuple(0, StepStatus.COMPLETED), tuple(1, StepStatus.WAITING_APPROVAL));
    }

    @Test
    void topLevelApproval_waits_withoutParking() throws Exception {
        Session session = new Session();
        session.commands.on("./a", "a done").on("./b", "b done");

        RunOutcome outcome = session.answered(() -> engine().run(approvalBetweenTwoShells(), null,
                session.options().build()), () -> session.control.answer("Yes"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(session.commands.commands()).containsExactly("./a", "./b");
        assertThat(session.events.flattened()).noneMatch(RunParked.class::isInstance);
    }

    /**
     * The plan approval of an {@code agentic} step is a question inside another block: it waits in the process. In
     * Traceworks it is the only one; {@code conditional}, {@code retry}, {@code while} and {@code foreach} run
     * commands, and a {@code switch} case runs only {@code shell} and {@code simple} blocks.
     */
    @Test
    void agenticPlanApproval_doesNotPark() throws Exception {
        Session session = new Session();
        session.claude.answers(
                "GOAL_STATUS: IN_PROGRESS\nRATIONALE: The notes are missing\nACTION: Create notes.txt\nSUMMARY: Create",
                "created notes.txt",
                "GOAL_STATUS: ACHIEVED\nRATIONALE: The notes exist\nACTION:\nSUMMARY: Done");
        WorkflowDefinition workflow = new WorkflowDefinition("wf-agentic", "Workflow wf-agentic", null,
                List.of(block("agentic", "agent", Map.of("goal", "Write the notes", "success_criteria",
                        "notes.txt exists", "max_iterations", 3, "require_approval", true, "tools", "write"))), null,
                null, null, null, Map.of(), Map.of(), List.of("ai.tools"), null, null);

        RunOutcome outcome = session.answered(() -> session.runOnThisThread(workflow, true),
                () -> session.control.answer("Yes"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(session.events.of(ApprovalAnswered.class))
                .containsExactly(new ApprovalAnswered(0, "approval", "Yes"));
        assertThat(session.claude.actionRequests()).as("the approved action ran").hasSize(1);
        assertThat(session.events.flattened()).noneMatch(RunParked.class::isInstance);
    }

    @Test
    void approvalInASubWorkflowChild_doesNotPark() throws Exception {
        workflows.add(workflow("wf-child", block("approval", "confirm", Map.of("question", "Go on?"))));
        Session session = new Session();

        RunOutcome outcome = session.answered(() -> session.runOnThisThread(workflow("wf-parent",
                block("sub-workflow", "child", Map.of("workflow_id", "wf-child"))), true),
                () -> session.control.answer("Yes"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().getFirst().getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(session.events.flattened()).noneMatch(RunParked.class::isInstance)
                .anySatisfy(event -> assertThat(event).isEqualTo(new ApprovalAnswered(0, "approval", "Yes")));
    }

    /**
     * Only a question a person answers alone parks. A shadow approval asks its AI first, so it waits for the person in
     * the process: a resume would have to ask the AI again.
     */
    @Test
    void shadowApproval_doesNotPark() throws Exception {
        Session session = new Session();
        session.claude.answers("Yes");
        WorkflowDefinition workflow = workflow("wf-shadow", block("approval", "confirm", Map.of(
                "question", "Deploy?", "options", List.of("Yes", "No"), "actor", "shadow")));

        RunOutcome outcome = session.answered(() -> session.runOnThisThread(workflow, true),
                () -> session.control.answer("Yes"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().getFirst().getOutput()).isEqualTo("Approved: Yes");
        assertThat(session.claude.prompts()).as("the AI proposed once").hasSize(1);
        assertThat(session.events.all()).noneMatch(RunParked.class::isInstance);
    }

    @Test
    void autoModeApproval_doesNotPark() throws Exception {
        Session session = new Session();

        RunOutcome outcome = session.run(workflow("wf-auto", block("approval", "confirm", Map.of(
                "question", "Deploy?", "mode", "auto", "auto_answer", "Yes"))), true);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().getFirst().getOutput()).isEqualTo("Approved: Yes");
        assertThat(session.events.all()).noneMatch(ApprovalRequested.class::isInstance)
                .noneMatch(RunParked.class::isInstance);
    }

    @Test
    void humanInput_parks_andResumesWithThePresetAnswer() throws Exception {
        WorkflowDefinition workflow = whoDeploys();
        Session first = new Session();
        first.commands.on("./a", "a done");
        RunOutcome parked = first.run(workflow, true);
        ApprovalRequested asked = first.events.of(ApprovalRequested.class).getFirst();

        Session second = new Session();
        second.commands.on("echo ", "said");
        RunOutcome outcome = second.resume(workflow, first.lastCheckpoint(),
                new PresetAnswer(1, "human-input", asked.question(), "Flo"));

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        assertThat(first.events.last()).isEqualTo(new RunParked(1, "human-input"));
        assertThat(asked.question()).isEqualTo("Who deploys?");
        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(second.commands.commands()).as("the answer reaches the later step").containsExactly("echo Flo");
        assertThat(second.events.of(ApprovalRequested.class)).as("the resumed step asks again")
                .extracting(ApprovalRequested::stepIndex, ApprovalRequested::question)
                .containsExactly(tuple(1, "Who deploys?"));
        assertThat(second.events.of(ApprovalAnswered.class))
                .containsExactly(new ApprovalAnswered(1, "human-input", "Flo"));
        assertThat(second.events.of(StepWaiting.class)).as("the preset answers at once").isEmpty();
        assertThat(outcome.steps()).extracting(StepResult::getStepIndex, StepResult::getStatus, StepResult::getOutput)
                .containsExactly(tuple(0, StepStatus.COMPLETED, "a done"), tuple(1, StepStatus.COMPLETED, "Flo"),
                        tuple(2, StepStatus.COMPLETED, "said"));
    }

    @Test
    void presetWithAChangedQuestion_failsTheStep() throws Exception {
        WorkflowDefinition workflow = whoDeploys();
        Session first = new Session();
        first.commands.on("./a", "a done");
        first.run(workflow, true);

        Session second = new Session();
        RunOutcome outcome = second.resume(workflow, first.lastCheckpoint(),
                new PresetAnswer(1, "human-input", "other", "Flo"));

        StepResult asking = outcome.steps().get(1);
        assertThat(asking.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(asking.getOutput()).startsWith("the question changed while the run was parked: ")
                .isEqualTo("the question changed while the run was parked: the answer was given to \"other\"");
        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(second.commands.commands()).isEmpty();
        assertThat(second.events.of(ApprovalAnswered.class)).isEmpty();
        assertThat(second.events.last()).isInstanceOf(RunFinished.class);
    }

    @Test
    void presetAnswer_reachesTheStepInTheOptionsSpelling() throws Exception {
        WorkflowDefinition workflow = approvalBetweenTwoShells();
        Session first = new Session();
        first.commands.on("./a", "a done");
        first.run(workflow, true);

        Session second = new Session();
        second.commands.on("./b", "b done");
        RunOutcome outcome = second.resume(workflow, first.lastCheckpoint(),
                new PresetAnswer(1, "approval", "Deploy?", " yes "));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().get(1).getOutput()).isEqualTo("Approved: Yes");
        assertThat(second.events.of(ApprovalAnswered.class))
                .containsExactly(new ApprovalAnswered(1, "approval", "Yes"));
    }

    @Test
    void presetAnswerThatIsNoOption_failsTheStep() throws Exception {
        WorkflowDefinition workflow = approvalBetweenTwoShells();
        Session first = new Session();
        first.commands.on("./a", "a done");
        first.run(workflow, true);

        Session second = new Session();
        RunOutcome outcome = second.resume(workflow, first.lastCheckpoint(),
                new PresetAnswer(1, "approval", "Deploy?", "Maybe"));

        StepResult asking = outcome.steps().get(1);
        assertThat(asking.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(asking.getOutput()).isEqualTo("the answer given while the run was parked is not an offered "
                + "option: Answer 'Maybe' is not one of the offered options [Yes, Yes always, No]");
        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(second.commands.commands()).as("no approval, so the next step does not run").isEmpty();
        assertThat(second.events.of(ApprovalAnswered.class)).isEmpty();
    }

    /** A cancel that lands after the question parked but before the engine looked: the cancel wins. */
    @Test
    void aCancelWhileTheQuestionParks_endsTheRunCancelled() throws Exception {
        Session session = new Session();
        session.commands.on("./a", "a done");
        RecordingRunEvents events = new RecordingRunEvents() {
            @Override
            public synchronized void emit(RunEvent event) {
                super.emit(event);
                if (event instanceof ApprovalRequested) {
                    session.control.cancel();
                }
            }
        };

        RunOutcome outcome = session.answered(() -> engine().run(approvalBetweenTwoShells(), null,
                session.options().events(events).checkpoints(true).parkAtQuestions(true).build()), null);

        assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(events.all()).noneMatch(RunParked.class::isInstance);
        assertThat(events.last()).isInstanceOfSatisfying(RunFinished.class,
                finished -> assertThat(finished.status()).isEqualTo(RunStatus.CANCELLED));
        assertThat(events.of(StepFinished.class)).as("the asking step ends as a cancelled question")
                .extracting(StepFinished::stepIndex, StepFinished::status)
                .containsExactly(tuple(0, StepStatus.COMPLETED), tuple(1, StepStatus.CANCELLED));
        assertThat(events.of(OutputRecorded.class)).filteredOn(output -> output.stepIndex() == 1)
                .extracting(OutputRecorded::output).containsExactly("cancelled while waiting for an answer");
        assertThat(session.commands.commands()).containsExactly("./a");
    }

    @Test
    void resume_parksAgainAtTheNextQuestion() throws Exception {
        WorkflowDefinition workflow = workflow("wf-two-questions",
                block("shell", "a", Map.of("command", "./a")),
                block("approval", "first", Map.of("question", "Build?")),
                block("approval", "second", Map.of("question", "Deploy?")),
                block("shell", "b", Map.of("command", "./b")));
        Session first = new Session();
        first.commands.on("./a", "a done");
        first.run(workflow, true);

        Session second = new Session();
        RunOutcome again = second.resume(workflow, first.lastCheckpoint(),
                new PresetAnswer(1, "approval", "Build?", "Yes"));

        Session third = new Session();
        third.commands.on("./b", "b done");
        RunOutcome outcome = third.resume(workflow, second.lastCheckpoint(),
                new PresetAnswer(2, "approval", "Deploy?", "Yes"));

        assertThat(first.events.last()).isEqualTo(new RunParked(1, "approval"));
        assertThat(again.status()).isEqualTo(RunStatus.PARKED);
        assertThat(second.events.last()).isEqualTo(new RunParked(2, "approval"));
        assertThat(second.positions()).as("a checkpoint after the answered step").containsExactly(2);
        assertThat(again.steps()).extracting(StepResult::getStepIndex, StepResult::getStatus, StepResult::getOutput)
                .containsExactly(tuple(0, StepStatus.COMPLETED, "a done"),
                        tuple(1, StepStatus.COMPLETED, "Approved: Yes"),
                        tuple(2, StepStatus.WAITING_APPROVAL, null));
        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(third.commands.commands()).containsExactly("./b");
    }

    @Test
    void parkedRun_writesNoCompletionGate() throws Exception {
        WorkflowDefinition workflow = workflow("wf-gate",
                block("shell", "lint", Map.of("command", "./lint")),
                block("approval", "confirm", Map.of("question", "Deploy?")));
        Session session = new Session();
        session.commands.on("./lint", "gate_status: YELLOW\ngate_category: lint\ngate_description: 3 warnings");

        RunOutcome outcome = session.run(workflow, true);

        assertThat(outcome.status()).isEqualTo(RunStatus.PARKED);
        assertThat(outcome.gate()).isEmpty();
        assertThat(outcome.error()).isNull();
    }

    private static WorkflowEngine engine() {
        return new WorkflowEngine(CoreBlocks.registry(null));
    }

    private static WorkflowDefinition approvalBetweenTwoShells() {
        return workflow("wf-deploy",
                block("shell", "a", Map.of("command", "./a")),
                block("approval", "q", Map.of("question", "Deploy?")),
                block("shell", "b", Map.of("command", "./b")));
    }

    private static WorkflowDefinition whoDeploys() {
        return workflow("wf-who",
                block("shell", "a", Map.of("command", "./a")),
                block("human-input", "who", Map.of("prompt", "Who deploys?", "variable_name", "who")),
                block("shell", "say", Map.of("command", "echo {{ who }}")));
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, "Workflow " + id, null, List.of(blocks), null, null, null, null, Map.of(),
                Map.of(), null, null, null);
    }

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    /** One process that runs or resumes the workflow: its own ports, events and control, checkpoints on. */
    private final class Session {
        final FakeCommandRunner commands = new FakeCommandRunner();
        final FakeAiProvider claude = new FakeAiProvider("CLAUDE");
        final RecordingRunEvents events = new RecordingRunEvents();
        final RunControl control = new RunControl();

        Session() {
            sessions.add(this);
        }

        RunOptions.Builder options() {
            return RunOptions.builder()
                    .runId("run-1")
                    .events(events)
                    .commandRunner(commands)
                    .aiProviders(claude.asProviders())
                    .workflowResolver(workflows)
                    .runControl(control)
                    .clock(() -> NOW)
                    .workingDirectory("/work/project");
        }

        /** Runs the workflow with checkpoints on; the run must end without an answer. */
        RunOutcome run(WorkflowDefinition workflow, boolean parkAtQuestions) throws InterruptedException {
            return answered(() -> runOnThisThread(workflow, parkAtQuestions), null);
        }

        RunOutcome runOnThisThread(WorkflowDefinition workflow, boolean parkAtQuestions) {
            return engine().run(workflow, null, options().checkpoints(true).parkAtQuestions(parkAtQuestions).build());
        }

        /** Resumes with parking on, from the checkpoint as the run record keeps it: written to JSON and read back. */
        RunOutcome resume(WorkflowDefinition workflow, Checkpoint checkpoint, PresetAnswer answer)
                throws InterruptedException {
            RunSnapshot stored = SnapshotTexts.roundTrip(checkpoint.snapshot());
            return answered(() -> engine().resume(workflow, new ResumePoint(stored, checkpoint.nextPosition(), answer),
                    options().checkpoints(true).parkAtQuestions(true).build()), null);
        }

        /**
         * Runs on another thread and returns how the run ended. With an {@code answer}, it is given once the run asks:
         * the question is open when it is announced. A run that still waits after {@link #WAIT} is cancelled and fails
         * the test, instead of waiting for its 30-minute timeout.
         */
        RunOutcome answered(Supplier<RunOutcome> run, Runnable answer) throws InterruptedException {
            AtomicReference<RunOutcome> outcome = new AtomicReference<>();
            Thread thread = Thread.ofVirtual().start(() -> outcome.set(run.get()));
            if (answer != null) {
                events.await(ApprovalRequested.class, WAIT);
                answer.run();
            }
            if (!thread.join(WAIT)) {
                control.cancel();
                thread.join(WAIT);
                throw new AssertionError("the run still waited after " + WAIT + "; events: " + events.all());
            }
            return outcome.get();
        }

        List<Integer> positions() {
            return events.of(Checkpoint.class).stream().map(Checkpoint::nextPosition).toList();
        }

        Checkpoint lastCheckpoint() {
            return events.of(Checkpoint.class).getLast();
        }
    }
}
