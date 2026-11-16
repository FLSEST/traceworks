package traceworks.core.engine;

import traceworks.core.record.RunSnapshotJson;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ApprovalRequested;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.StepStarted;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.map;

/**
 * Checkpoints and resume: a run with checkpoints on reports its state before the first block and after every top-level
 * step that lets it go on, and a new engine continues the run from such a checkpoint. Every resume here goes through
 * {@link RunSnapshotJson}, as a resume from the run record does, and runs in a {@link Session} of its own: another
 * process with its own command runner, events and control.
 */
class WorkflowEngineResumeTest {

    private static final Instant NOW = Instant.parse("2026-11-03T09:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final TaintProbe probe = new TaintProbe();
    private final List<Session> sessions = new ArrayList<>();

    /** A test that failed while a run waits must not leave the run waiting for 30 minutes. */
    @AfterEach
    void stopTheRuns() {
        sessions.forEach(session -> session.control.cancel());
    }

    @Test
    void checkpoints_comeBeforeTheFirstBlockAndAfterEveryStepThatLetsTheRunGoOn() {
        Session session = new Session();
        session.commands.otherwise(FakeCommandRunner.ok("ok"));

        RunOutcome outcome = session.run(threeShellBlocks());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(session.events.all().stream()
                .filter(e -> e instanceof Checkpoint || e instanceof StepStarted)
                .map(WorkflowEngineResumeTest::label))
                .containsExactly("checkpoint 0", "step 0", "checkpoint 1", "step 1", "checkpoint 2", "step 2",
                        "checkpoint 3");
        assertThat(session.events.of(Checkpoint.class))
                .as("a checkpoint holds the steps that ran before it")
                .extracting(checkpoint -> checkpoint.snapshot().steps().size())
                .containsExactly(0, 1, 2, 3);
        assertThat(session.checkpoint(3).snapshot().context().variables()).containsEntry("workflow_run_id", "run-1");
        assertThat(session.checkpoint(3).snapshot().inputContext()).isEmpty();
    }

    @Test
    void checkpoints_areOffByDefault() {
        Session session = new Session();
        session.commands.otherwise(FakeCommandRunner.ok("ok"));

        RunOutcome outcome = engine().run(threeShellBlocks(), null, session.options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(session.events.of(Checkpoint.class)).isEmpty();
    }

    @Test
    void noCheckpoint_afterAStepThatEndsTheRun() {
        Session session = new Session();
        session.commands.on("./one", "one").on("./two", FakeCommandRunner.exit(1, "boom")).on("./three", "three");

        RunOutcome outcome = session.run(threeShellBlocks());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(session.positions()).containsExactly(0, 1);
    }

    @Test
    void resume_continuesAtThePositionWithTheRestoredVariables() {
        WorkflowDefinition workflow = workflow("wf-echo",
                block("shell", "s1", Map.of("command", "./print-one")),
                block("shell", "s2", Map.of("command", "echo {{ block.s1.output }}")));
        Session first = new Session();
        first.commands.on("./print-one", "one").on("echo ", "echoed");
        first.run(workflow);

        Session second = new Session();
        second.commands.on("echo ", "echoed");
        RunOutcome outcome = second.resume(workflow, first.checkpoint(1));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(second.commands.commands()).as("s1 does not run again").containsExactly("echo one");
        assertThat(outcome.steps()).extracting(StepResult::getBlockName, StepResult::getOutput)
                .containsExactly(tuple("s1", "one"), tuple("s2", "echoed"));
        assertThat(second.events.of(StepStarted.class)).containsExactly(new StepStarted(1, "s2", "shell", 1, false));
        assertThat(second.events.last()).isEqualTo(new RunFinished(RunStatus.COMPLETED, Map.of(), null));
    }

    /**
     * The variables a step set come back after a resume as the step left them: a text, which the checkpoint holds as a
     * blob, and a map, which it holds inline.
     */
    @Test
    void resume_givesBackATextVariableAndAMapVariable() {
        WorkflowDefinition workflow = workflow("wf-vars",
                block("set-variables", "set", Map.of()),
                block("shell", "use", Map.of("command", "echo {{ note }} {{ form }}")));
        Session first = new Session();
        first.commands.on("echo ", "used");
        first.run(workflow);
        RunSnapshot stored = SnapshotTexts.roundTrip(first.checkpoint(1).snapshot());

        Session second = new Session();
        second.commands.on("echo ", "used");
        RunOutcome outcome = second.resume(workflow, first.checkpoint(1));

        assertThat(stored.context().variables()).containsEntry("note", SetVariables.NOTE)
                .containsEntry("form", SetVariables.FORM);
        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(second.commands.commands()).containsExactly(first.commands.commands().getLast())
                .containsExactly("echo a note\nof two lines {name=Flo, tags=[x]}");
    }

    /**
     * A resumed run sets its run variables again from the resume, its trigger source, start time
     * and working directory included; every other variable stays as the checkpoint kept it.
     */
    @Test
    void resume_setsTheRunVariablesAgain() {
        WorkflowDefinition workflow = workflow("wf-when",
                block("shell", "first", Map.of("command", "./first")),
                block("shell", "second", Map.of("command", "echo {{ trigger_source }} {{ started_at }}"
                        + " {{ run_timestamp }} {{ project_path }} {{ ticket }}")));
        Session first = new Session();
        first.commands.on("./first", "one").on("echo ", "seen");
        first.run(workflow, Map.of("ticket", "T-1"));
        Instant resumedAt = Instant.parse("2026-11-04T10:30:00Z");
        RunSnapshot stored = SnapshotTexts.roundTrip(first.checkpoint(1).snapshot());

        Session second = new Session();
        second.commands.on("echo ", "seen");
        RunOutcome outcome = engine().resume(workflow, new ResumePoint(stored, 1, null), second.options()
                .clock(() -> resumedAt).triggerSource(TriggerSource.RESUME).workingDirectory("/work/moved")
                .checkpoints(true).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(first.commands.commands()).last()
                .isEqualTo("echo MANUAL 2026-11-03T09:00:00Z 20261103-090000 /work/project T-1");
        assertThat(second.commands.commands())
                .containsExactly("echo RESUME 2026-11-04T10:30:00Z 20261104-103000 /work/moved T-1");
        assertThat(second.commands.requests()).singleElement()
                .satisfies(request -> assertThat(request.workingDirectory()).isEqualTo("/work/moved"));
    }

    /**
     * The output of an AI step whose prompt carried platform data is tainted (ExecutionContextTaint's
     * {@code substitutedIntoPrompt}): the run from the checkpoint after that step sees the same taint as the run that
     * went on, and its shell line gets the output and the platform data as values the same way.
     */
    /**
     * A run that asked a model keeps that across a resume: the answer recorded before the checkpoint reaches a
     * command of the resumed run as a value, as in the run that went on, and refuses a shell line that names it; so
     * does an output the resumed run records.
     */
    @Test
    void resume_keepsWhatAModelWrote_protected() {
        List<BlockDefinition> first = List.of(
                block("shell", "before", Map.of("command", "list")),
                block("simple", "ask", Map.of("command", "Name the files")),
                block("shell", "after", Map.of("command", "cat")));
        WorkflowDefinition workflow = workflow("wf-model-text", first.get(0), first.get(1), first.get(2),
                block("shell", "use", Map.of("values", Map.of("ASK", "{{ block.ask.output }}",
                        "STEP", "{{ step_1_output }}", "AFTER", "{{ block.after.output }}"),
                        "command", "echo {{ block.before.output }} \"$ASK\"")));
        WorkflowDefinition inALine = workflow("wf-model-text", first.get(0), first.get(1), first.get(2),
                block("shell", "use", Map.of("command", "echo {{ block.ask.output }} {{ block.after.output }}")));
        Session uninterrupted = new Session();
        uninterrupted.claude.answers("a; rm -rf ~");
        uninterrupted.commands.on("list", "one two").on("cat", "three four").on("echo ", "done");
        RunOutcome went = uninterrupted.run(workflow);

        Session resumed = new Session();
        resumed.commands.on("cat", "three four").on("echo ", "done");
        RunOutcome back = resumed.resume(workflow, uninterrupted.checkpoint(2));
        Session refusing = new Session();
        refusing.commands.on("cat", "three four");
        RunOutcome refused = refusing.resume(inALine, uninterrupted.checkpoint(2));

        assertThat(went.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(back.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(uninterrupted.checkpoint(2).snapshot().context().taint().modelText())
                .isEqualTo(new ContextState.ModelText(true, Set.of("step_1_output"), Set.of("ask"), true));
        assertThat(resumed.commands.commands()).containsExactly("cat", "echo one two \"$ASK\"");
        assertThat(resumed.commands.requests().getLast().values()).containsOnly(Map.entry("ASK", "a; rm -rf ~"),
                Map.entry("STEP", "a; rm -rf ~"), Map.entry("AFTER", "three four"));
        assertThat(uninterrupted.commands.requests().getLast()).isEqualTo(resumed.commands.requests().getLast());
        assertThat(refused.status()).as("the answer recorded before the resume, and an output after it")
                .isEqualTo(RunStatus.FAILED);
        assertThat(refused.error()).contains("shell line refused, nothing ran: {{ block.ask.output }},"
                + " {{ block.after.output }} are text a model wrote");
        assertThat(refusing.commands.commands()).containsExactly("cat");
    }

    @Test
    void resume_keepsTheTaint() {
        WorkflowDefinition workflow = workflow("wf-taint",
                block("simple", "ask", Map.of("command", "Summarize {{ previous_chain_output }}")),
                block("taint-probe", "probe", Map.of()),
                block("shell", "use", Map.of("values", Map.of("ASK", "{{ block.ask.output }}",
                        "CHAIN", "{{ previous_chain_output }}"), "command", "echo \"$ASK\" \"$CHAIN\" {{ topic }}")));
        Map<String, Object> inputs = Map.of("previous_chain_output", "chain text", "topic", "release");
        Session uninterrupted = new Session();
        uninterrupted.claude.answers("do this; rm -rf ~");
        uninterrupted.commands.on("echo ", "done");
        RunOutcome went = uninterrupted.run(workflow, inputs);
        TaintSeen wentOn = probe.seen.getFirst();

        Session resumed = new Session();
        resumed.commands.on("echo ", "done");
        RunOutcome back = resumed.resume(workflow, uninterrupted.checkpoint(1));
        TaintSeen afterResume = probe.seen.getLast();

        assertThat(went.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(back.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(probe.seen).hasSize(2);
        assertThat(wentOn.hasUntrustedInput()).as("the tainted output taints the run").isTrue();
        assertThat(wentOn.escaped()).containsEntry("{{ block.ask.output }}", true)
                .containsEntry("{{ previous_output }}", true)
                .containsEntry("{{ previous_chain_output }}", true)
                .containsEntry("{{ topic }}", false);
        assertThat(afterResume).as("the taint after resume").isEqualTo(wentOn);
        assertThat(resumed.commands.requests()).as("the resumed shell line and its values, as the uninterrupted one")
                .isEqualTo(uninterrupted.commands.requests());
        assertThat(resumed.commands.commands()).containsExactly("echo \"$ASK\" \"$CHAIN\" release");
        assertThat(resumed.commands.requests().getFirst().values())
                .containsOnly(Map.entry("ASK", "do this; rm -rf ~"), Map.entry("CHAIN", "chain text"));
    }

    /**
     * A "Yes always" answered before the checkpoint answers the same question after the resume, and the gate of a step
     * before the checkpoint counts in the run's gate. The gate reaches the run metadata only when the run completes,
     * from the steps the snapshot keeps; the run metadata itself survives in the state (ExecutionContextFactoryTest).
     */
    @Test
    void resume_keepsRememberedApprovalsAndRunMetadata() throws Exception {
        WorkflowDefinition workflow = workflow("wf-approve",
                block("approval", "first", Map.of("question", "Deploy?")),
                block("shell", "lint", Map.of("command", "./lint")),
                block("approval", "second", Map.of("question", "Deploy?")),
                block("shell", "tests", Map.of("command", "./test")));
        Session first = new Session();
        first.commands.on("./lint", "gate_status: YELLOW\ngate_category: lint\ngate_description: 3 warnings")
                .on("./test", "gate_status: GREEN\ngate_category: tests\ngate_description: all green");
        Thread firstRun = first.start(() -> first.run(workflow));
        first.events.await(ApprovalRequested.class, WAIT);
        first.control.answer("Yes always");
        firstRun.join(WAIT.toMillis());

        Session second = new Session();
        second.commands.on("./test", "gate_status: GREEN\ngate_category: tests\ngate_description: all green");
        AtomicReference<RunOutcome> outcome = new AtomicReference<>();
        Thread secondRun = second.start(() -> outcome.set(second.resume(workflow, first.checkpoint(2))));
        secondRun.join(WAIT.toMillis());

        assertThat(first.events.of(ApprovalRequested.class)).as("the first run asks once").hasSize(1);
        assertThat(outcome.get()).as("the resumed run ended without waiting").isNotNull();
        assertThat(outcome.get().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(second.events.flattened()).as("the remembered answer is not asked again")
                .noneMatch(ApprovalRequested.class::isInstance);
        assertThat(outcome.get().steps().get(2).getOutput()).contains("Yes always");
        RunFinished finished = (RunFinished) second.events.last();
        assertThat(finished.gate()).containsEntry("gate_status", "YELLOW").containsEntry("gate_category", "lint");
        assertThat(finished.gate().get("gate_summary")).asInstanceOf(map(String.class, Object.class))
                .containsEntry("counts_by_status", Map.of("YELLOW", 1, "GREEN", 1));
    }

    @Test
    void resume_atTheEnd_completesWithoutABlock() {
        Session first = new Session();
        first.commands.otherwise(FakeCommandRunner.ok("ok"));
        first.run(threeShellBlocks());

        Session second = new Session();
        RunOutcome outcome = second.resume(threeShellBlocks(), first.checkpoint(3));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps()).hasSize(3);
        assertThat(second.events.of(StepStarted.class)).isEmpty();
        assertThat(second.commands.commands()).isEmpty();
        assertThat(second.events.all()).containsExactly(new RunFinished(RunStatus.COMPLETED, Map.of(), null));
    }

    @Test
    void resume_outsideTheWorkflow_isRefused() {
        Session first = new Session();
        first.commands.otherwise(FakeCommandRunner.ok("ok"));
        first.run(threeShellBlocks());
        RunSnapshot snapshot = first.checkpoint(3).snapshot();
        Session second = new Session();

        assertThatThrownBy(() -> engine().resume(threeShellBlocks(), new ResumePoint(snapshot, 4, null),
                second.options().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4");
        assertThatThrownBy(() -> engine().resume(threeShellBlocks(), new ResumePoint(snapshot, -1, null),
                second.options().build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(second.events.all()).as("a refused resume reports nothing").isEmpty();
    }

    @Test
    void resume_passesTheRunAdmissionAgain() {
        Session first = new Session();
        first.commands.otherwise(FakeCommandRunner.ok("ok"));
        first.run(threeShellBlocks());
        WorkflowDefinition changed = workflow("wf-three",
                block("shell", "one", Map.of("command", "./one")),
                block("no-such-type", "two", Map.of()),
                block("shell", "three", Map.of("command", "./three")));

        Session second = new Session();
        RunOutcome outcome = second.resume(changed, first.checkpoint(2));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).startsWith(RunAdmission.MESSAGE_PREFIX);
        assertThat(second.events.of(StepStarted.class)).isEmpty();
        assertThat(second.commands.commands()).isEmpty();
    }

    @Test
    void childRuns_emitNoCheckpoints() {
        workflows.add(workflow("wf-child", block("shell", "inner", Map.of("command", "./inner"))));
        Session session = new Session();
        session.commands.on("./inner", "inner done");

        RunOutcome outcome = session.run(workflow("wf-parent",
                block("sub-workflow", "child", Map.of("workflow_id", "wf-child"))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(session.events.of(ChildRunEvent.class)).as("the child ran").isNotEmpty()
                .noneMatch(event -> event.event() instanceof Checkpoint);
        assertThat(session.events.flattened().stream().filter(Checkpoint.class::isInstance).count())
                .as("only the parent's checkpoints").isEqualTo(2);
        assertThat(session.positions()).containsExactly(0, 1);
    }

    private WorkflowEngine engine() {
        return new WorkflowEngine(new BlockTypeRegistry(List.of(new CoreExecutors(), probe, new SetVariables())));
    }

    private static WorkflowDefinition threeShellBlocks() {
        return workflow("wf-three",
                block("shell", "one", Map.of("command", "./one")),
                block("shell", "two", Map.of("command", "./two")),
                block("shell", "three", Map.of("command", "./three")));
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, "Workflow " + id, null, List.of(blocks), null, null, null, null, Map.of(),
                Map.of(), null, null, null);
    }

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    private static String label(RunEvent event) {
        return event instanceof Checkpoint checkpoint
                ? "checkpoint " + checkpoint.nextPosition()
                : "step " + ((StepStarted) event).position();
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

        RunOutcome run(WorkflowDefinition workflow) {
            return run(workflow, null);
        }

        RunOutcome run(WorkflowDefinition workflow, Map<String, Object> inputs) {
            return engine().run(workflow, inputs, options().checkpoints(true).build());
        }

        /** Resumes from the checkpoint as the run record keeps it: written to JSON and read back. */
        RunOutcome resume(WorkflowDefinition workflow, Checkpoint checkpoint) {
            RunSnapshot stored = SnapshotTexts.roundTrip(checkpoint.snapshot());
            return engine().resume(workflow, new ResumePoint(stored, checkpoint.nextPosition(), null),
                    options().checkpoints(true).build());
        }

        Thread start(Runnable run) {
            return Thread.ofVirtual().start(run);
        }

        List<Integer> positions() {
            return events.of(Checkpoint.class).stream().map(Checkpoint::nextPosition).toList();
        }

        Checkpoint checkpoint(int nextPosition) {
            return events.of(Checkpoint.class).stream()
                    .filter(checkpoint -> checkpoint.nextPosition() == nextPosition)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no checkpoint at " + nextPosition + ": " + events.all()));
        }
    }

    /** The core block types, run by the executors of {@link CoreBlocks#registry}. */
    private static final class CoreExecutors implements BlockExecutor {
        private final BlockTypeRegistry core = CoreBlocks.registry(null);

        @Override
        public StepResult execute(BlockDefinition block, ExecutionContext context) {
            return core.executorFor(block.getType()).execute(block, context);
        }

        @Override
        public Set<String> supportedTypes() {
            return core.knownTypes();
        }
    }

    /** A block that sets a text variable ({@code note}) and a map variable ({@code form}). */
    private static final class SetVariables implements BlockExecutor {
        static final String NOTE = "a note\nof two lines";
        static final Map<String, Object> FORM = form();

        private static Map<String, Object> form() {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("name", "Flo");
            form.put("tags", List.of("x"));
            return form;
        }

        @Override
        public StepResult execute(BlockDefinition block, ExecutionContext context) {
            context.setVariable("note", NOTE);
            context.setVariable("form", FORM);
            return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED)
                    .output("set").build();
        }

        @Override
        public Set<String> supportedTypes() {
            return Set.of("set-variables");
        }
    }

    /** What the run's taint looked like to a block: the run-level flag and which placeholders a shell line quotes. */
    private record TaintSeen(boolean hasUntrustedInput, Map<String, Boolean> escaped) {}

    /** A block that looks at the run's taint without changing it. */
    private static final class TaintProbe implements BlockExecutor {
        private static final List<String> PLACEHOLDERS = List.of("{{ block.ask.output }}", "{{ previous_output }}",
                "{{ previous_chain_output }}", "{{ topic }}");

        final List<TaintSeen> seen = new CopyOnWriteArrayList<>();

        @Override
        public StepResult execute(BlockDefinition block, ExecutionContext context) {
            Map<String, Boolean> escaped = new LinkedHashMap<>();
            PLACEHOLDERS.forEach(placeholder -> escaped.put(placeholder, context.namesEscapedValue(placeholder)));
            seen.add(new TaintSeen(context.hasUntrustedInput(), escaped));
            return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED)
                    .output("probed").build();
        }

        @Override
        public Set<String> supportedTypes() {
            return Set.of("taint-probe");
        }
    }
}
