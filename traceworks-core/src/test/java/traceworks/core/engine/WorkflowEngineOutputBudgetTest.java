package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.context.OutputBudget;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.RunEvent.Checkpoint;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import traceworks.core.port.RunEvent.OutputRecorded;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.RecordingRunEvents;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run's output budget ({@link RunOptions#outputBudgetBytes}, {@link OutputBudget}) with the real core blocks: what
 * counts, which step fails and how, that the run ends whatever {@code on_failure} says, that a child run shares the
 * budget and gives back what it held, and that a resumed run counts the texts of its checkpoint. Every size here is in
 * bytes: a shell step adds its command (its input) and its output.
 */
class WorkflowEngineOutputBudgetTest {

    private static final Instant NOW = Instant.parse("2026-11-09T08:00:00Z");
    private static final String NOTE_100 = new OutputBudget(100).note();

    private final FakeCommandRunner commands = new FakeCommandRunner();
    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final RecordingRunEvents events = new RecordingRunEvents();
    private final WorkflowEngine engine = new WorkflowEngine(CoreBlocks.registry(null));

    private RunOptions.Builder options(long budget) {
        return RunOptions.builder()
                .runId("run-1")
                .events(events)
                .commandRunner(commands)
                .workflowResolver(workflows)
                .clock(() -> NOW)
                .workingDirectory("/work/project")
                .outputBudgetBytes(budget);
    }

    private RunOutcome run(long budget, BlockDefinition... blocks) {
        return engine.run(workflow("wf", blocks), Map.of(), options(budget).build());
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, "Workflow " + id, null, List.of(blocks), null, null, null, null, Map.of(),
                Map.of(), null, null, null);
    }

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    private static BlockDefinition shell(String name, String command) {
        return block("shell", name, Map.of("command", command, "on_failure", "continue"));
    }

    private static String x(int bytes) {
        return "x".repeat(bytes);
    }

    @Test
    void stepsThatFit_runAsWithoutABudget_upToTheLastByte() {
        commands.on("one", x(40)).on("two", x(54));   // 3 + 40, then 3 + 54: exactly 100

        RunOutcome outcome = run(100, shell("s1", "one"), shell("s2", "two"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps()).extracting(StepResult::getOutput).containsExactly(x(40), x(54));
    }

    @Test
    void theStepThatWouldGoBeyond_failsWithItsWholeOutputAndTheNote_andTheRunEnds_whateverOnFailureSays() {
        commands.on("one", x(40)).on("two", x(55)).on("three", "never");   // 43 + 58 = 101

        RunOutcome outcome = run(100, shell("s1", "one"), shell("s2", "two"), shell("s3", "three"));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).as("s3 never starts, although s2 says on_failure: continue")
                .containsExactly("one", "two");
        StepResult failed = outcome.steps().get(1);
        assertThat(failed.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.getOutput()).isEqualTo(x(55) + "\n" + NOTE_100);
        assertThat(failed.getMetadata()).containsEntry(StepFailureSummary.KEY, NOTE_100);
        assertThat(outcome.error()).isEqualTo("Step 1 (shell) failed: " + NOTE_100);
        assertThat(events.of(OutputRecorded.class)).last()
                .isEqualTo(new OutputRecorded(1, "s2", x(55) + "\n" + NOTE_100));
        assertThat(events.of(StepStarted.class)).extracting(StepStarted::blockName).containsExactly("s1", "s2");
        assertThat(events.last()).isEqualTo(new RunFinished(RunStatus.FAILED, Map.of(),
                "Step 1 (shell) failed: " + NOTE_100));
    }

    @Test
    void aStepsInputCounts_soEmbeddingALargeOutputCanGoBeyond() {
        commands.on("one", x(50)).on("echo ", "ok");   // 3 + 50, then "echo " + 50 + "ok": 110

        RunOutcome outcome = run(100, shell("s1", "one"), shell("s2", "echo {{ step_0_output }}"));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.steps().get(1).getOutput()).isEqualTo("ok\n" + NOTE_100);
    }

    @Test
    void theContextsCopiesOfAnOutput_doNotCountAgain() {
        commands.on("one", x(40)).on("two", x(10));   // the output is also previous_output, step_0_output, block.s1

        RunOutcome outcome = run(56, shell("s1", "one"), shell("s2", "two"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aForeach_stopsAtTheItemThatWouldGoBeyond_keepsWhatItGathered_andTheItemIsRecordedWhole() {
        commands.on("print a", x(30)).on("print b", x(30)).on("print c", x(30)).on("print d", "never")
                .on("after", "never");
        // each item adds "[a]: " + 30 + "\n" = 36 bytes: a and b fit into 100, c does not

        RunOutcome outcome = run(100,
                block("foreach", "loop", Map.of("items", List.of("a", "b", "c", "d"), "command", "print {{ item }}",
                        "execution_mode", "shell", "continue_on_error", true, "on_failure", "continue")),
                shell("after", "after"));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).containsExactly("print a", "print b", "print c");
        StepResult loop = outcome.steps().getFirst();
        assertThat(loop.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(loop.getOutput()).isEqualTo("[a]: " + x(30) + "\n[b]: " + x(30) + "\n" + NOTE_100);
        assertThat(loop.getMetadata()).containsEntry(GateSources.METADATA_KEY, false)
                .containsEntry(StepFailureSummary.KEY, NOTE_100);
        assertThat(events.of(ForEachItemFinished.class)).last()
                .satisfies(item -> {
                    assertThat(item.item()).isEqualTo("c");
                    assertThat(item.output()).as("the item that did not fit, recorded whole").isEqualTo(x(30));
                });
        assertThat(events.of(ForEachItemStarted.class)).extracting(ForEachItemStarted::item)
                .containsExactly("a", "b", "c");
        assertThat(outcome.error()).isEqualTo("Step 0 (foreach) failed: " + NOTE_100);
    }

    @Test
    void aForeachThatFits_givesBackWhatItGathered_beforeItsOutputCountsOnce() {
        commands.on("print ", x(30));
        // 3 items gather 108 bytes beside the item variable (1); then the step's output (107) and input ("foreach item
        // in [3 items]", 25) count once, 133 in all: had the loop kept what it gathered, the step would need 241

        RunOutcome outcome = run(133, block("foreach", "loop", Map.of("items", List.of("a", "b", "c"),
                "command", "print {{ item }}", "execution_mode", "shell")));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aWhile_stopsAtTheIterationThatWouldGoBeyond() {
        commands.on("./scan", x(30));
        // each iteration adds "[iteration 1]: " + 30 + "\n" = 46 bytes: two fit into 100, the third does not

        RunOutcome outcome = run(100, block("while", "loop", Map.of("command", "./scan", "max_iterations", 10,
                "execution_mode", "shell")));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).hasSize(3);
        StepResult loop = outcome.steps().getFirst();
        assertThat(loop.getOutput()).isEqualTo("[iteration 1]: " + x(30) + "\n[iteration 2]: " + x(30) + "\n"
                + NOTE_100);
        assertThat(loop.getMetadata()).containsEntry(GateSources.METADATA_KEY, false)
                .containsEntry("iterationCount", 3);
    }

    @Test
    void aSwitch_stopsAtTheCaseBlockThatWouldGoBeyond() {
        commands.on("first", x(40)).on("second", x(40)).on("third", "never");
        Map<String, Object> config = Map.of("expression", "go", "cases", List.of(Map.of("value", "go", "blocks",
                List.of(Map.of("type", "shell", "name", "c1", "config", Map.of("command", "first")),
                        Map.of("type", "shell", "name", "c2", "config", Map.of("command", "second")),
                        Map.of("type", "shell", "name", "c3", "config", Map.of("command", "third"))))));

        RunOutcome outcome = run(70, block("switch", "pick", config));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).containsExactly("first", "second");
        assertThat(outcome.steps().getFirst().getOutput()).isEqualTo(x(40) + "\n" + new OutputBudget(70).note());
    }

    @Test
    void aChildRunThatGoesBeyond_endsItself_andItsParent_whateverOnFailureSays() {
        workflows.add(workflow("wf-child", shell("c1", "one"), shell("c2", "two")));
        commands.on("one", x(40)).on("two", x(60)).on("after", "never");

        RunOutcome outcome = engine.run(workflow("wf-parent",
                block("sub-workflow", "child", Map.of("workflow_id", "wf-child", "on_failure", "continue")),
                shell("after", "after")), Map.of(), options(100).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).containsExactly("one", "two");
        StepResult child = outcome.steps().getFirst();
        assertThat(child.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(child.getOutput()).contains(NOTE_100);
        assertThat(outcome.error()).isEqualTo("Step 0 (sub-workflow) failed: " + NOTE_100);
    }

    @Test
    void aChildRun_givesBackWhatItHeld_whenItEnds() {
        workflows.add(workflow("wf-child", shell("c1", "big"), shell("c2", "small")));
        commands.on("big", x(60)).on("small", "ok");
        // each child holds 3 + 60 + 5 + 2 = 70 bytes while it runs; three of them at once would not fit into 100

        RunOutcome outcome = engine.run(workflow("wf-parent",
                block("foreach", "loop", Map.of("items", List.of("a", "b", "c"), "workflow_id", "wf-child"))),
                Map.of(), options(100).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().getFirst().getOutput()).isEqualTo("[a]: ok\n[b]: ok\n[c]: ok");
    }

    @Test
    void aResumedRun_countsTheTextsOfItsCheckpoint() {
        WorkflowDefinition workflow = workflow("wf", shell("s1", "one"), shell("s2", "two"));
        commands.on("one", x(80)).on("two", "ok");
        engine.run(workflow, Map.of(), options(OutputBudget.NONE).checkpoints(true).build());
        Checkpoint afterFirst = events.of(Checkpoint.class).stream()
                .filter(checkpoint -> checkpoint.nextPosition() == 1).findFirst().orElseThrow();
        RunSnapshot stored = SnapshotTexts.roundTrip(afterFirst.snapshot());

        RunOutcome small = engine.resume(workflow, new ResumePoint(stored, 1, null), options(84).build());
        RunOutcome enough = engine.resume(workflow, new ResumePoint(stored, 1, null), options(88).build());

        assertThat(small.status()).as("s1 holds 83 bytes, s2 adds 5").isEqualTo(RunStatus.FAILED);
        assertThat(small.steps().getLast().getOutput()).isEqualTo("ok\n" + new OutputBudget(84).note());
        assertThat(enough.status()).isEqualTo(RunStatus.COMPLETED);
    }

    /** Text with a character above U+00FF takes two bytes per character, and counts so. */
    @Test
    void wideText_countsTwoBytesPerCharacter() {
        commands.on("one", "✓".repeat(48)).on("two", "never");   // 3 + 2 x 48 = 99

        RunOutcome fits = run(99, shell("s1", "one"));
        RunOutcome beyond = run(98, shell("s1", "one"));

        assertThat(fits.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(beyond.status()).isEqualTo(RunStatus.FAILED);
    }

    /** A foreach counts the text it will join: one wide item makes every character it gathered take two bytes. */
    @Test
    void aForeach_withOneWideItem_countsAllItsTextAtTwoBytes() {
        commands.on("print a", x(20)).on("print b", "✓").on("print c", x(20));
        // [a]: x20\n is 26 characters, [b]: ✓\n 7, [c]: x20\n 26: 59 at one byte, but 118 at two

        RunOutcome outcome = run(100, block("foreach", "loop", Map.of("items", List.of("a", "b", "c"),
                "command", "print {{ item }}", "execution_mode", "shell")));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.steps().getFirst().getOutput()).isEqualTo("[a]: " + x(20) + "\n[b]: ✓\n" + NOTE_100);
    }

    /** The flag decides: a step that prints the note's line itself is no budget failure. */
    @Test
    void aStepThatPrintsTheNoteItself_butFits_completes() {
        commands.on("cat old.log", NOTE_100);

        RunOutcome outcome = run(1_000, shell("s1", "cat old.log"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().getFirst().getMetadata()).doesNotContainKey(OutputBudget.METADATA_KEY);
    }

    /** The parent's step ends with the note as its last line, after the child's error that holds it too. */
    @Test
    void theParentOfAChildThatWentBeyond_endsItsOutputWithTheNote_andCarriesTheFlag() {
        workflows.add(workflow("wf-child", shell("c1", "one"), shell("c2", "two")));
        commands.on("one", x(40)).on("two", x(60));

        RunOutcome outcome = engine.run(workflow("wf-parent",
                block("sub-workflow", "child", Map.of("workflow_id", "wf-child"))), Map.of(), options(100).build());

        StepResult child = outcome.steps().getFirst();
        assertThat(child.getOutput()).endsWith("\n" + NOTE_100);
        assertThat(child.getMetadata()).containsEntry(OutputBudget.METADATA_KEY, true);
    }

    /** A foreach that also had failed items names them in its run error, then the budget. */
    @Test
    void aForeachWithFailedItems_thatWentBeyond_namesBothInItsRunError() {
        commands.on("print a", FakeCommandRunner.exit(1, x(30))).on("print b", x(30)).on("print c", x(30));

        RunOutcome outcome = run(100, block("foreach", "loop", Map.of("items", List.of("a", "b", "c"),
                "command", "print {{ item }}", "execution_mode", "shell")));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).startsWith("Step 0 (foreach) failed: 1/3 items failed: a\n[a]: ")
                .endsWith("\n" + NOTE_100);
    }

    /** The loop's tally says how it ended: it stays in the output beside the note, although it did not fit. */
    @Test
    void aForeachsTally_staysInItsOutput_whenItDoesNotFit() {
        commands.on("print ", FakeCommandRunner.exit(1, x(30)));
        // the three items take 108 bytes, the tally line 46 more: beyond 120, kept all the same

        RunOutcome outcome = run(120, block("foreach", "loop", Map.of("items", List.of("a", "b", "c"),
                "command", "print {{ item }}", "execution_mode", "shell", "continue_on_error", true)));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.steps().getFirst().getOutput())
                .endsWith("\n[continue_on_error] 3/3 items failed: a, b, c\n" + new OutputBudget(120).note());
    }

    /** A condition that failed says so in the while's output, also when that line does not fit any more. */
    @Test
    void aWhilesConditionLine_staysInItsOutput_whenItDoesNotFit() {
        commands.on("one", x(90)).on("check", new CommandResult(0, x(10), "", 1, false,
                List.of(CommandResult.STDOUT), 1024));

        RunOutcome outcome = run(100, shell("s1", "one"), block("while", "loop", Map.of("command", "./scan",
                "condition_command", "check", "max_iterations", 3, "execution_mode", "shell")));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.steps().get(1).getOutput()).startsWith("[condition] The condition failed: The command's"
                + " standard output exceeded 1 KiB").endsWith("\n" + NOTE_100);
    }

    /**
     * A foreach {@code context} mapping that copies a block output sets a variable of the parent: that copy counts.
     * scan holds 4 + 40, the item "a" 1, the copy 40; the child adds 7 and gives it back; the loop's step then holds
     * 25 + 7: 117 in all, 77 without the copy.
     */
    @Test
    void aContextMappingThatCopiesAnOutput_countsAsTheVariableItSets() {
        workflows.add(workflow("wf-child", shell("c1", "small")));
        commands.on("scan", x(40)).on("small", "ok");
        WorkflowDefinition parent = workflow("wf-parent", shell("scan", "scan"),
                block("foreach", "loop", Map.of("items", List.of("a"), "workflow_id", "wf-child",
                        "context", Map.of("report", "{{ block.scan.output }}"))));

        RunOutcome tight = engine.run(parent, Map.of(), options(116).build());
        RunOutcome enough = engine.run(parent, Map.of(), options(117).build());

        assertThat(tight.status()).isEqualTo(RunStatus.FAILED);
        assertThat(tight.error()).isEqualTo("Step 1 (foreach) failed: " + new OutputBudget(116).note());
        assertThat(enough.status()).isEqualTo(RunStatus.COMPLETED);
    }

    /** A value the parent set beyond the budget for its child: the child starts no block. */
    @Test
    void aChildWhoseParentWentBeyondWithItsMapping_startsNoBlock() {
        workflows.add(workflow("wf-child", shell("c1", "small")));
        commands.on("scan", x(40)).on("small", "never");

        RunOutcome outcome = engine.run(workflow("wf-parent", shell("scan", "scan"),
                block("foreach", "loop", Map.of("items", List.of("a"), "workflow_id", "wf-child",
                        "context", Map.of("report", "{{ block.scan.output }}")))), Map.of(), options(80).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).containsExactly("scan");
        assertThat(events.of(ForEachItemFinished.class)).singleElement()
                .satisfies(item -> assertThat(item.output()).contains(new OutputBudget(80).usedUpNote()));
        assertThat(outcome.steps().get(1).getOutput()).isEqualTo(new OutputBudget(80).note());
    }

    /** A resumed run holds its steps' texts and the variables they set: 44 + 32, and "a" plus the copy, 117. */
    @Test
    void heldOnResume_isTheStepsTextsAndTheVariablesTheySet() {
        workflows.add(workflow("wf-child", shell("c1", "small")));
        commands.on("scan", x(40)).on("small", "ok").on("after", "ok");
        WorkflowDefinition parent = workflow("wf-parent", shell("scan", "scan"),
                block("foreach", "loop", Map.of("items", List.of("a"), "workflow_id", "wf-child",
                        "context", Map.of("report", "{{ block.scan.output }}"))), shell("after", "after"));
        engine.run(parent, Map.of("ticket", "x".repeat(500)), options(OutputBudget.NONE).checkpoints(true).build());
        RunSnapshot afterLoop = events.of(Checkpoint.class).stream()
                .filter(checkpoint -> checkpoint.nextPosition() == 2).findFirst().orElseThrow().snapshot();
        RunSnapshot stored = SnapshotTexts.roundTrip(afterLoop);

        assertThat(WorkflowEngine.heldOnResume(stored)).as("the input ticket does not count").isEqualTo(117);
        assertThat(engine.resume(parent, new ResumePoint(stored, 2, null), options(120).build()).status())
                .as("117 + after's 5 + 2").isEqualTo(RunStatus.FAILED);
        assertThat(engine.resume(parent, new ResumePoint(stored, 2, null), options(124).build()).status())
                .isEqualTo(RunStatus.COMPLETED);
    }

    /**
     * Review C, L7: a value a step set under an input's name (a foreach {@code variable}, a human input's
     * {@code variable_name}) counts after a resume as it did before the park; the input's own value does not.
     */
    @Test
    void heldOnResume_countsAValueAStepSetUnderAnInputsName() {
        String large = "y".repeat(1000);
        RunSnapshot replaced = SnapshotTexts.roundTrip(new RunSnapshot(new ContextState(Map.of("who", large), null,
                Map.of(), null, null, 0, Map.of(), Map.of()), List.of(), Map.of("who", "x")));
        RunSnapshot kept = SnapshotTexts.roundTrip(new RunSnapshot(new ContextState(Map.of("who", "x"), null,
                Map.of(), null, null, 0, Map.of(), Map.of()), List.of(), Map.of("who", "x")));
        commands.on("one", "ok");
        WorkflowDefinition workflow = workflow("wf", shell("s1", "one"));

        assertThat(WorkflowEngine.heldOnResume(replaced)).isEqualTo(OutputBudget.sizeOf(large));
        assertThat(WorkflowEngine.heldOnResume(kept)).isZero();
        assertThat(engine.resume(workflow, new ResumePoint(replaced, 0, null), options(500).build()).status())
                .as("the resume already holds more than its budget").isEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).isEmpty();
        assertThat(engine.resume(workflow, new ResumePoint(kept, 0, null), options(500).build()).status())
                .isEqualTo(RunStatus.COMPLETED);
    }

    /** A resume that already holds more than its budget starts no block, at the engine too. */
    @Test
    void aResumeBeyondItsBudget_startsNoBlock() {
        WorkflowDefinition workflow = workflow("wf", shell("s1", "one"), shell("s2", "two"));
        commands.on("one", x(80)).on("two", "ok");
        engine.run(workflow, Map.of(), options(OutputBudget.NONE).checkpoints(true).build());
        RunSnapshot stored = SnapshotTexts.roundTrip(events.of(Checkpoint.class).stream()
                .filter(checkpoint -> checkpoint.nextPosition() == 1).findFirst().orElseThrow().snapshot());
        int before = commands.commands().size();

        RunOutcome outcome = engine.resume(workflow, new ResumePoint(stored, 1, null), options(50).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo(new OutputBudget(50).usedUpNote());
        assertThat(commands.commands()).as("s2 does not run again").hasSize(before);
    }

    @Test
    void withoutABudget_nothingFails() {
        commands.on("one", x(10_000));

        RunOutcome outcome = engine.run(workflow("wf", shell("s1", "one")), Map.of(), RunOptions.builder()
                .runId("run-1").commandRunner(commands).clock(() -> NOW).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(RunOptions.builder().runId("r").build().outputBudgetBytes()).isEqualTo(OutputBudget.NONE);
    }
}
