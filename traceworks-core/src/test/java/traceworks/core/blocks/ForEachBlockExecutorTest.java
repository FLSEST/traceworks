package traceworks.core.blocks;

import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.OutputBudget;
import traceworks.core.context.RecordedItems;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.StepFailureSummary;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.InvalidSettingException;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForEachBlockExecutorTest {

    /** What the runner says about a command whose stdout went beyond the output limit. */
    private static final String NOTE = "The command's standard output exceeded 16 MiB; Traceworks stopped it and"
            + " kept the first 16 MiB";

    @Mock
    private WrapperCommands commands;

    private ForEachBlockExecutor executor;

    private final List<RunEvent> events = new ArrayList<>();
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new ForEachBlockExecutor(commands);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .events(events::add)
                .build();
        context.setProjectPath("/test/project");
    }

    private static StepResult ok(String output) {
        return StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(output).build();
    }

    private static ShellResult printed(String output) {
        return new ShellResult(output, 0, 0);
    }

    /** A foreach in AI mode, written out as Traceworks requires. */
    private static Map<String, Object> aiConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "ai");
        return config;
    }

    private static BlockDefinition foreach(Map<String, Object> config) {
        return BlockDefinition.builder().type("foreach").config(config).build();
    }

    @Test
    void supports_foreachType() {
        assertThat(executor.supports("foreach")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void missingExecutionModeFails() {
        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("file1.js", "file2.js"));
        config.put("command", "eslint {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("execution_mode is required (shell or ai)");
        verifyNoInteractions(commands);
        assertThat(events).isEmpty();
    }

    /**
     * A foreach with {@code workflow_id} runs a sub-workflow per item and no command, so no hidden AI default can
     * apply: it needs no {@code execution_mode}.
     */
    @Test
    void workflowId_needsNoExecutionMode_andRunsOneChildPerItem() {
        SubWorkflowLauncher launcher = mock(SubWorkflowLauncher.class);
        when(launcher.run(eq("wf-child"), any())).thenReturn(
                new SubWorkflowLauncher.Outcome("wf-child", "Child", "sub-run", RunStatus.COMPLETED, "done", true));
        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("a", "b"));
        config.put("workflow_id", "wf-child");

        StepResult result = new ForEachBlockExecutor(commands, launcher).execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("[a]: done\n[b]: done");
        verify(launcher, times(2)).run(eq("wf-child"), any());
        verifyNoInteractions(commands);
    }

    @Test
    void execute_withListItems_iteratesEachItem() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("linted"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("file1.js", "file2.js", "file3.js"));
        config.put("variable_name", "file");
        config.put("command", "eslint {{ file }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_withCommandSource_usesCommandOutput() {
        when(commands.runCommand(eq("git diff --name-only"), eq(300), any())).thenReturn(printed("file1.js\nfile2.js"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "git diff --name-only");
        config.put("variable_name", "file");
        config.put("command", "eslint {{ file }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    /**
     * An items_command whose output went beyond the limit fails the block before the first item: its lines are only
     * the first part of the list, which must never pass for the whole one.
     */
    @Test
    void execute_itemsCommandBeyondTheOutputLimit_failsBeforeTheFirstItem() {
        when(commands.runCommand(eq("find /"), eq(300), any()))
                .thenReturn(new ShellResult("/a\n/b\n" + NOTE, 153, 0, NOTE));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "find /");
        config.put("command", "review {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("items_command failed: " + NOTE);
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        assertThat(events).isEmpty();
    }

    /**
     * An items_command that ran out of time fails the block before the first item: the line that reports the timeout
     * is no item. The runner's mark decides, not the exit code: a command may exit 124 by itself.
     */
    @Test
    void execute_itemsCommandTimesOut_failsBeforeTheFirstItem() {
        when(commands.runCommand(eq("slow"), eq(300), any()))
                .thenReturn(new ShellResult("one\nCommand timed out after 300 seconds", 124, 0, "", true));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "slow");
        config.put("command", "review {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("items_command failed: it timed out after 300 seconds");
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        assertThat(events).isEmpty();
    }

    /** An items_command the runner refused never ran: its refusal text is no item, the block fails before the first. */
    @Test
    void execute_itemsCommandDidNotRun_failsBeforeTheFirstItem() {
        when(commands.runCommand(eq("ls targets"), eq(300), any()))
                .thenReturn(new ShellResult("image traceworks/shell:0123456789ab is not present; run traceworks images build", 125, 0, "", false, "image traceworks/shell:0123456789ab is not present; run traceworks images build"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "ls targets");
        config.put("command", "deploy {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("items_command failed: it did not run: image traceworks/shell:0123456789ab is not present; run traceworks images build");
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        assertThat(events).isEmpty();
    }

    /** An items_command that exits 125, 1 or 74 by itself, without the runner's mark: its lines are the items. */
    @ParameterizedTest
    @ValueSource(ints = {125, 1, 74})
    void execute_itemsCommandExitsLikeARefusalByItself_itsLinesAreTheItems(int exitCode) {
        when(commands.runCommand(eq("odd"), eq(300), any())).thenReturn(new ShellResult("a\nb", exitCode, 0));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "odd");
        config.put("command", "review {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
    }

    /** The list of an items_command counts against the run's budget while the loop runs: one that does not fit runs no item. */
    @Test
    void execute_itemsCommandListBeyondTheBudget_failsBeforeTheFirstItem() {
        OutputBudget budget = new OutputBudget(10);
        ExecutionContext small = ExecutionContext.builder().workflowRunId("run-1").events(events::add)
                .outputBudget(budget).build();
        small.setProjectPath("/test/project");
        when(commands.runCommand(eq("ls"), eq(300), any())).thenReturn(printed("aaaaaaaa\nbbbbbbbb"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "ls");
        config.put("command", "review {{ item }}");

        StepResult result = executor.execute(foreach(config), small);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo(budget.note());
        assertThat(OutputBudget.failedFor(result)).isTrue();
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        assertThat(events).isEmpty();
    }

    /** An item whose variables go beyond the run's budget does not start: no event and no call for it. */
    @Test
    void execute_anItemWhoseVariablesGoBeyondTheBudget_doesNotStart() {
        OutputBudget budget = new OutputBudget(1_000);
        ExecutionContext small = ExecutionContext.builder().workflowRunId("run-1").events(events::add)
                .outputBudget(budget).build();
        small.setProjectPath("/test/project");
        small.countVariables(name -> false, name -> false);   // as the engine does before the first block
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));
        Map<String, Object> config = aiConfig();
        config.put("items", List.of("small", "x".repeat(5_000)));
        config.put("command", "review {{ item }}");

        StepResult result = executor.execute(foreach(config), small);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
        assertThat(events).filteredOn(ForEachItemStarted.class::isInstance).hasSize(1);
    }

    /** The list is held while the items run and given back when the loop ends. */
    @Test
    void execute_itemsCommandList_isHeldWhileTheLoopRuns_andGivenBackAfter() {
        OutputBudget fromCommand = new OutputBudget(10_000);
        OutputBudget fromStatic = new OutputBudget(10_000);
        List<Long> heldDuring = new ArrayList<>();
        when(commands.runCommand(eq("ls"), eq(300), any())).thenReturn(printed("a".repeat(100) + "\n" + "b".repeat(100)));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            heldDuring.add(inv.<ExecutionContext>getArgument(1).getOutputBudget().heldBytes());
            return ok("ok");
        });

        Map<String, Object> listed = aiConfig();
        listed.put("items_source", "command");
        listed.put("items_command", "ls");
        listed.put("command", "review {{ item }}");
        Map<String, Object> fixed = aiConfig();
        fixed.put("items", List.of("a".repeat(100), "b".repeat(100)));
        fixed.put("command", "review {{ item }}");

        executor.execute(foreach(listed), withBudget(fromCommand));
        List<Long> commandHeld = List.copyOf(heldDuring);
        heldDuring.clear();
        executor.execute(foreach(fixed), withBudget(fromStatic));

        assertThat(commandHeld.getFirst()).as("the list of 200 bytes is held beside the item")
                .isEqualTo(heldDuring.getFirst() + 200);
        assertThat(fromCommand.heldBytes()).as("given back at the end").isEqualTo(fromStatic.heldBytes());
    }

    private ExecutionContext withBudget(OutputBudget budget) {
        ExecutionContext withBudget = ExecutionContext.builder().workflowRunId("run-1").outputBudget(budget).build();
        withBudget.setProjectPath("/test/project");
        return withBudget;
    }

    @Test
    void execute_itemsCommandExits124ByItself_isNoTimeout() {
        when(commands.runCommand(eq("odd"), eq(300), any())).thenReturn(new ShellResult("a\nb", 124, 0));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "odd");
        config.put("command", "review {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
    }

    @Test
    void execute_anyItemFails_returnsFailedStatus() {
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(ok("ok"), StepResult.builder().stepIndex(1).status(StepStatus.FAILED).output("error").build());

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("good.js", "bad.js"));
        config.put("command", "lint {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_itemFailsBetweenPassingOnes_summaryNamesItFirst() {
        // the failed item sat between successful ones and must not be cut out of the run error
        StepResult pass = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("GATE PASS").build();
        StepResult broken = StepResult.builder().stepIndex(1).status(StepStatus.FAILED)
                .output("API Error: Connection closed mid-response.\n").build();
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(pass, broken, pass);

        Map<String, Object> config = aiConfig();
        config.put("items", List.of("bad-zwischenahn", "erkner", "etzenrot"));
        config.put("command", "rebuild {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata())
                .containsEntry("failedItemCount", 1)
                .containsEntry("failedItems", List.of("erkner"))
                .containsEntry(StepFailureSummary.KEY,
                        "1/3 items failed: erkner\n[erkner]: API Error: Connection closed mid-response.");
        // the output stays the transcript of every item, in order
        assertThat(result.getOutput()).startsWith("[bad-zwischenahn]: GATE PASS").doesNotContain("items failed");
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void failureSummary_manyFailedItems_staysWithinBudgetAndKeepsTheErrorTail() {
        List<String> failed = IntStream.range(0, 37).mapToObj(i -> "town-" + i).toList();
        String longError = "x".repeat(2_000) + "You've hit your session limit";

        String summary = ForEachFailures.failureSummary(failed, longError, 50, List.of());

        assertThat(summary).startsWith("37/50 items failed: town-0, town-1, ")
                .contains("… (+")
                .contains("\n[town-0]: ")
                .endsWith("You've hit your session limit");
        assertThat(summary.length()).isLessThan(600);
    }

    @Test
    void execute_continueOnError_failedItemDoesNotFailBlock() {
        StepResult failed = StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output("timeout").build();
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(failed, ok("ok"), ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items", List.of("bad-langensalza", "gotha", "erfurt"));
        config.put("command", "rebuild {{ item }}");
        config.put("continue_on_error", true);

        StepResult result = executor.execute(foreach(config), context);

        // the block completes so follow-up blocks run; failures are reported, not swallowed
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("1/3 items failed", "bad-langensalza");
        assertThat(result.getMetadata().get("failedItemCount")).isEqualTo(1);
        assertThat(result.getMetadata().get("failedItems")).isEqualTo(List.of("bad-langensalza"));
        assertThat(result.getMetadata()).as("a COMPLETED step has no run error to explain")
                .doesNotContainKey(StepFailureSummary.KEY);
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    // max_consecutive_failures

    private static StepResult itemResult(StepStatus status, String output) {
        return StepResult.builder().stepIndex(0).status(status).output(output).build();
    }

    private BlockDefinition foreachOver(List<String> items, Map<String, Object> extra) {
        Map<String, Object> config = aiConfig();
        config.put("items", items);
        config.put("command", "rebuild {{ item }}");
        config.putAll(extra);
        return foreach(config);
    }

    @Test
    void execute_threeFailuresInARow_stopsAndNamesTheReason() {
        // 37 of 42 items hit "You've hit your session limit", and foreach called them all
        StepResult quota = itemResult(StepStatus.FAILED, "You've hit your session limit");
        when(commands.aiStep(any(AiStepRequest.class), any()))
                .thenReturn(itemResult(StepStatus.COMPLETED, "ok"), quota, quota, quota);

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e", "f", "g"), Map.of()), context);

        verify(commands, times(4)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata())
                .containsEntry("abortedAfterConsecutiveFailures", 3)
                .containsEntry("skippedItemCount", 3)
                .containsEntry("failedItemCount", 3)
                .containsEntry("totalItems", 7);
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY))
                .startsWith("stopped after 3 consecutive failed items (max_consecutive_failures), 3 items not run\n"
                        + "3/7 items failed: b, c, d\n[b]: You've hit your session limit");
        assertThat(result.getOutput()).endsWith("[max_consecutive_failures] stopped after 3 consecutive failed items"
                + " (max_consecutive_failures), 3 items not run");
    }

    @Test
    void execute_continueOnError_stillStopsAfterThreeFailuresInARow_andFails() {
        StepResult quota = itemResult(StepStatus.FAILED, "You've hit your session limit");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(quota);

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d"), Map.of("continue_on_error", true)), context);

        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getStatus()).as("continue_on_error tolerates failed items, not unrun ones")
                .isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[continue_on_error] 3/4 items failed: a, b, c",
                "[max_consecutive_failures] stopped after 3 consecutive failed items");
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY))
                .startsWith("stopped after 3 consecutive failed items (max_consecutive_failures), 1 item not run\n");
    }

    @Test
    void execute_successBetweenFailures_resetsTheCount() {
        StepResult bad = itemResult(StepStatus.FAILED, "error");
        StepResult good = itemResult(StepStatus.COMPLETED, "ok");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(bad, bad, good, bad, bad, good);

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e", "f"), Map.of("continue_on_error", true)), context);

        verify(commands, times(6)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).doesNotContainKeys("abortedAfterConsecutiveFailures", "skippedItemCount");
    }

    @Test
    void execute_lastItemCompletesTheRun_isNoAbort() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(itemResult(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(foreachOver(List.of("a", "b", "c"), Map.of()), context);

        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getMetadata()).doesNotContainKey("abortedAfterConsecutiveFailures");
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY)).startsWith("3/3 items failed: a, b, c");
    }

    @Test
    void execute_maxConsecutiveFailuresZero_runsEveryItem() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(itemResult(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e"), Map.of("max_consecutive_failures", 0)), context);

        verify(commands, times(5)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).doesNotContainKey("abortedAfterConsecutiveFailures");
    }

    @Test
    void execute_customMaxConsecutiveFailuresAsText_isHonoured() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(itemResult(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e"), Map.of("max_consecutive_failures", 1)), context);

        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getMetadata()).containsEntry("skippedItemCount", 4);
    }

    /** A whole number from 0 to 10,000; text, a negative number or a placeholder fail the block before an item runs. */
    @Test
    void maxConsecutiveFailures_isAWholeNumber_textIsRefused() {
        for (Object wrong : List.of(" 2 ", "{{ limit }}", -1, 2.5, true)) {
            assertThatThrownBy(() -> executor.execute(
                    foreachOver(List.of("a"), Map.of("max_consecutive_failures", wrong)), context))
                    .as(String.valueOf(wrong))
                    .isInstanceOf(InvalidSettingException.class)
                    .hasMessageStartingWith("foreach: 'max_consecutive_failures' must be a whole number between 0 and"
                            + " 10,000, found ");
        }
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_continueOnError_allItemsSucceed_noFailureMetadata() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items", List.of("a", "b"));
        config.put("command", "process {{ item }}");
        config.put("continue_on_error", true);

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).doesNotContain("items failed");
        assertThat(result.getMetadata()).doesNotContainKey("failedItemCount");
    }

    @Test
    void execute_emptyItems_returnsCompleted() {
        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of());
        config.put("command", "lint {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        assertThat(result.getMetadata().get("items")).isEqualTo(List.of());
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_itemsCommand_emptyOutput_iteratesZeroTimes() {
        // an empty items_command output means zero items, not one empty item (that would be a pointless AI call)
        when(commands.runCommand(eq("cat topics.txt"), eq(300), any())).thenReturn(printed(""));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "cat topics.txt");
        config.put("command", "write about {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        assertThat((List<Map<String, Object>>) result.getMetadata().get("items")).isEmpty();
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
        assertThat(events).noneMatch(ForEachItemStarted.class::isInstance);
    }

    @Test
    void execute_itemsCommand_whitespaceOnlyOutput_iteratesZeroTimes() {
        when(commands.runCommand(eq("list-topics"), eq(300), any())).thenReturn(printed("\n  \n\n"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "list-topics");
        config.put("command", "process {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_itemsCommand_blankLinesAreDropped() {
        // blank lines in between and at the end are not items
        when(commands.runCommand(eq("ls"), eq(300), any())).thenReturn(printed("a.txt\n\n   \nb.txt\n"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "ls");
        config.put("command", "lint {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(items).extracting(item -> item.get("item")).containsExactly("a.txt", "b.txt");
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_stringItems_blankLinesAreDropped() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", "alpha\n\nbeta\n");
        config.put("command", "process {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_listItems_blankEntriesAreDropped() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("alpha", "", "  ", "beta"));
        config.put("command", "process {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_cancelledDuringIteration_stopsEarly() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            context.cancel();
            return ok("ok");
        });

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("a", "b", "c", "d"));
        config.put("command", "process {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        verify(commands, times(1)).aiStep(any(AiStepRequest.class), any());
        assertThat(result.getStatus()).as("the cancel cut the loop short").isEqualTo(StepStatus.CANCELLED);
    }

    /** An item the cancel ended is CANCELLED, no failed item: it counts towards no failure and no summary. */
    @Test
    void execute_anItemTheCancelEnded_isCancelled_notAFailedItem() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("fine")).thenAnswer(inv -> {
            context.cancel();
            return StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output("terminated").build();
        });
        Map<String, Object> config = aiConfig();
        config.put("items", List.of("a", "b", "c"));
        config.put("command", "process {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getMetadata()).doesNotContainKeys("failedItemCount", "failedItems",
                traceworks.core.engine.StepFailureSummary.KEY);
        assertThat(events).filteredOn(ForEachItemFinished.class::isInstance).map(ForEachItemFinished.class::cast)
                .extracting(ForEachItemFinished::status).containsExactly(StepStatus.COMPLETED, StepStatus.CANCELLED);
        assertThat(result.getMetadata().get("items")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .extracting(item -> (Object) ((Map<?, ?>) item).get("status")).containsExactly("COMPLETED", "CANCELLED");
    }

    /** A loop whose every item ran before the cancel arrived has done its work. */
    @Test
    void execute_aCancelAfterTheLastItem_leavesTheLoopCompleted() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("one")).thenAnswer(inv -> {
            context.cancel();
            return ok("two");
        });
        Map<String, Object> config = aiConfig();
        config.put("items", List.of("a", "b"));
        config.put("command", "process {{ item }}");

        assertThat(executor.execute(foreach(config), context).getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    /** The cancel ended the last item: every item started, but the loop did not do its work. */
    @Test
    void execute_aCancelThatEndsTheLastItem_cancelsTheLoop() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("one")).thenAnswer(inv -> {
            context.cancel();
            return StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output("terminated").build();
        });
        Map<String, Object> config = aiConfig();
        config.put("items", List.of("a", "b"));
        config.put("command", "process {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        assertThat(events).filteredOn(ForEachItemFinished.class::isInstance).map(ForEachItemFinished.class::cast)
                .extracting(ForEachItemFinished::status).containsExactly(StepStatus.COMPLETED, StepStatus.CANCELLED);
    }

    /** A child run the cancel ended is a cancelled item, not a failed one. */
    @Test
    void execute_aChildRunTheCancelEnded_isACancelledItem() {
        SubWorkflowLauncher launcher = mock(SubWorkflowLauncher.class);
        when(launcher.run(eq("wf-child"), any())).thenAnswer(inv -> {
            context.cancel();
            return new SubWorkflowLauncher.Outcome("wf-child", "Child", "sub-run", RunStatus.CANCELLED, "", false);
        });
        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("a", "b"));
        config.put("workflow_id", "wf-child");

        StepResult result = new ForEachBlockExecutor(commands, launcher).execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.CANCELLED);
        assertThat(result.getMetadata()).doesNotContainKey("failedItems");
        assertThat(events).filteredOn(ForEachItemFinished.class::isInstance).map(ForEachItemFinished.class::cast)
                .extracting(ForEachItemFinished::status).containsExactly(StepStatus.CANCELLED);
    }

    @Test
    void execute_stringItems_splitsByNewline() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", "alpha\nbeta\ngamma");
        config.put("command", "process {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_shellMode_usesShellExecution() {
        when(commands.shellStep(any(), anyInt(), eq(300), any())).thenReturn(ok("ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a", "b"));
        config.put("command", "echo {{ item }}");
        config.put("execution_mode", "shell");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(2)).shellStep(any(), anyInt(), eq(300), any());
        verify(commands, never()).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_shellMode_commandSource_usesShellForItems() {
        when(commands.runCommand(eq("ls *.txt"), eq(300), any())).thenReturn(printed("a.txt\nb.txt"));
        when(commands.shellStep(any(), anyInt(), eq(300), any())).thenReturn(ok("ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "ls *.txt");
        config.put("command", "cat {{ item }}");
        config.put("execution_mode", "shell");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands).runCommand(eq("ls *.txt"), eq(300), any());
        verify(commands, times(2)).shellStep(any(), anyInt(), eq(300), any());
    }

    @Test
    void execute_piProvider_passesProviderIdToRequest() {
        context.setProvider("PI");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");

        executor.execute(foreach(config), context);

        verify(commands).aiStep(argThat(req -> "PI".equals(req.providerId())), any());
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequest() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");

        executor.execute(foreach(config), context);

        verify(commands).aiStep(argThat(req -> req.providerId() == null), any());
    }

    @Test
    void execute_emitsStartedAndFinishedEventsPerItem() {
        context.startStepIndicesAt(4);
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("done"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("x", "y"));
        config.put("command", "handle {{ item }}");

        executor.execute(foreach(config), context);

        assertThat(events).containsExactly(
                new ForEachItemStarted(4, 0, "x", 2),
                new ForEachItemFinished(4, 0, "x", 2, StepStatus.COMPLETED, "done", null),
                new ForEachItemStarted(4, 1, "y", 2),
                new ForEachItemFinished(4, 1, "y", 2, StepStatus.COMPLETED, "done", null));
    }

    /** A failed event write stops the run: the foreach does not turn it into a step result. */
    @Test
    void execute_eventLogFailure_propagates() {
        RuntimeException sinkDown = new EventLogException(new IllegalStateException("disk full"));
        ExecutionContext failing = ExecutionContext.builder().workflowRunId("run-1").projectPath("/test/project")
                .events(event -> {
                    throw sinkDown;
                })
                .build();

        Map<String, Object> config = aiConfig();
        config.put("items", List.of("x"));
        config.put("command", "handle {{ item }}");

        assertThatThrownBy(() -> executor.execute(foreach(config), failing)).isSameAs(sinkDown);
        verifyNoInteractions(commands);
    }

    @Test
    void execute_outputCombinesItemResults() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            AiStepRequest req = inv.getArgument(0);
            return ok("processed " + req.prompt());
        });

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("alpha", "beta"));
        config.put("variable_name", "item");
        config.put("command", "{{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getOutput()).contains("[alpha]:");
        assertThat(result.getOutput()).contains("[beta]:");
    }

    @Test
    void execute_customTimeout_passedToShellMode() {
        when(commands.shellStep(any(), anyInt(), eq(60), any())).thenReturn(ok("ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "echo {{ item }}");
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands).shellStep(any(), anyInt(), eq(60), any());
    }

    @Test
    void execute_customAiTimeout_passedToTheRequest() {
        when(commands.aiStep(argThat(req -> Integer.valueOf(120).equals(req.timeoutSeconds())), any()))
                .thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");
        config.put("timeout_seconds", 120);

        executor.execute(foreach(config), context);

        verify(commands).aiStep(argThat(req -> Integer.valueOf(120).equals(req.timeoutSeconds())), any());
    }

    @Test
    void execute_itemsCommand_templateVariablesResolved() {
        context.setVariable("branch", "main");
        when(commands.runCommand(eq("git diff main --name-only"), eq(300), any())).thenReturn(printed("changed.js"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "git diff {{ branch }} --name-only");
        config.put("command", "lint {{ item }}");

        executor.execute(foreach(config), context);

        verify(commands).runCommand(eq("git diff main --name-only"), eq(300), any());
    }

    @Test
    void execute_noItemsSource_defaultsToList() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        // items_source omitted: defaults to "list"
        config.put("items", List.of("one", "two"));
        config.put("command", "handle {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commands, times(2)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_eachIteration_seesIsolatedItemVariable() {
        // each iteration sees only its own item, never the one of the previous iteration
        List<String> capturedItems = new ArrayList<>();
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            capturedItems.add(String.valueOf(context.getVariable("item")));
            return ok("ok");
        });

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("alpha", "beta", "gamma"));
        config.put("variable_name", "item");
        config.put("command", "echo {{ item }}");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(capturedItems).containsExactly("alpha", "beta", "gamma");
        // item N != item N-1: each iteration overwrites the variable cleanly
        assertThat(capturedItems.get(1)).isNotEqualTo(capturedItems.get(0));
        assertThat(capturedItems.get(2)).isNotEqualTo(capturedItems.get(1));
        verify(commands, times(3)).aiStep(any(AiStepRequest.class), any());
    }

    @Test
    void execute_jsonFieldsWorkInTheCommandToo() {
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));
        Map<String, Object> config = aiConfig();
        config.put("items", List.of("{\"id\": 7, \"tags\": [\"a\"]}"));
        config.put("command", "Build {{ item.id }} with {{ item.tags }} (#{{ item_index }})");

        executor.execute(foreach(config), context);

        ArgumentCaptor<AiStepRequest> request = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(request.capture(), any());
        assertThat(request.getValue().prompt()).isEqualTo("Build 7 with [\"a\"] (#0)");
    }

    @Test
    void execute_fieldsOfThePreviousItem_doNotStandInForMissingOnes() {
        List<Object> seen = new ArrayList<>();
        when(commands.aiStep(any(AiStepRequest.class), any())).thenAnswer(inv -> {
            seen.add(context.getVariable("item.id"));
            return ok("ok");
        });
        Map<String, Object> config = aiConfig();
        config.put("items", List.of("{\"id\": \"auth\"}", "plain"));
        config.put("command", "Build {{ item }}");

        executor.execute(foreach(config), context);

        assertThat(seen).containsExactly("auth", null);
    }

    @Test
    void execute_shellMode_untrustedVariable_isEscapedInItemsCommandAndCommand() {
        context.setUntrustedVariable("dir", "src; id");
        when(commands.runCommand(eq(FakeCommandRunner.value(1, "src; id") + "ls \"${TRACEWORKS_VALUE_1}\""), eq(300), any())).thenReturn(printed("a.txt"));
        when(commands.shellStep(any(), anyInt(), eq(300), any())).thenReturn(ok("ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "ls {{ dir }}");
        config.put("command", "cat {{ dir }}/{{ item }}");
        config.put("execution_mode", "shell");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), eq(300), any());
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo(
                FakeCommandRunner.value(1, "src; id") + FakeCommandRunner.value(2, "a.txt") + "cat \"${TRACEWORKS_VALUE_1}\"/\"${TRACEWORKS_VALUE_2}\"");
    }

    @Test
    void execute_shellMode_platformDataInItemsCommand_escapesTheItems() {
        // previous_report is quoted in the items_command, but its content becomes the items:
        // substituting it into a shell line taints the run, so the items are escaped too
        context.setUntrustedPlatformVariable("previous_report", "report");
        when(commands.runCommand(eq(FakeCommandRunner.value(1, "report") + "echo \"${TRACEWORKS_VALUE_1}\""), eq(300), any())).thenReturn(printed("a.txt; id"));
        when(commands.shellStep(any(), anyInt(), eq(300), any())).thenReturn(ok("ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "echo {{ previous_report }}");
        config.put("command", "cat {{ item }}");
        config.put("execution_mode", "shell");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), eq(300), any());
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo(FakeCommandRunner.value(1, "a.txt; id") + "cat \"${TRACEWORKS_VALUE_1}\"");
    }

    @Test
    void execute_shellMode_staticItems_stayRawInATaintedRun() {
        context.setUntrustedVariable("dir", "src");   // the run is tainted, but the items are static
        when(commands.shellStep(any(), anyInt(), eq(300), any())).thenReturn(ok("ok"));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a.txt"));
        config.put("command", "cat {{ item }}");
        config.put("execution_mode", "shell");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), eq(300), any());
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo("cat a.txt");
    }

    /** Items from a command in a tainted run are untrusted, and so are their JSON fields and their index. */
    @Test
    void execute_itemsFromATaintedCommand_areUntrustedWithTheirFields() {
        context.setUntrustedVariable("branch", "main");
        when(commands.runCommand(any(), anyInt(), any())).thenReturn(printed("{\"id\": \"x; rm\"}\n"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "command");
        config.put("items_command", "list");
        config.put("variable_name", "feature");
        config.put("command", "Build {{ feature.id }}");

        executor.execute(foreach(config), context);

        assertThat(context.isUntrusted("feature")).isTrue();
        assertThat(context.isUntrusted("feature.id")).isTrue();
        assertThat(context.isUntrusted("feature_index")).isTrue();
        assertThat(context.resolveShellTemplate("echo {{ feature.id }}", false)).isEqualTo(FakeCommandRunner.value(1, "x; rm") + "echo \"${TRACEWORKS_VALUE_1}\"");
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("PI");
        context.setModel("gpt-5");
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(ok("ok"));

        Map<String, Object> config = aiConfig();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");
        config.put("provider", "PI");
        config.put("model", "anthropic/opus");

        executor.execute(foreach(config), context);

        verify(commands).aiStep(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_carriesAiRequestFromFirstItemAndRecordsItems() {
        StepResult first = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("a").durationMs(10L).build();
        first.setMetadata(Map.of(
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                "provider", "CLAUDE", "requestedModel", "opus"));
        StepResult second = StepResult.builder().stepIndex(2).status(StepStatus.FAILED).output("b").build();
        second.setMetadata(Map.of("aiRequest", Map.of("provider", "PI"), "provider", "PI"));
        when(commands.aiStep(any(AiStepRequest.class), any())).thenReturn(first, second);

        Map<String, Object> config = aiConfig();
        config.put("items", List.of("alpha", "x".repeat(200)));
        config.put("command", "process {{ item }}");
        config.put("continue_on_error", true);

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata())
                .containsEntry("provider", "CLAUDE")
                .containsEntry("requestedModel", "opus")
                .containsEntry("totalItems", 2)
                .containsEntry("failedItemCount", 1);
        assertThat((Map<String, Object>) result.getMetadata().get("aiRequest")).containsEntry("model", "opus");
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("index", 0).containsEntry("item", "alpha")
                .containsEntry("status", "COMPLETED").containsEntry("durationMs", 10L);
        assertThat(items.get(1)).containsEntry("status", "FAILED").containsEntry("durationMs", 0L);
        String label = (String) items.get(1).get("item");
        assertThat(label).hasSize(123).endsWith("...");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shellMode_recordsItemsWithoutAiRequest() {
        StepResult shellStep = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        shellStep.setMetadata(Map.of("exitCode", 0, "executionMode", "shell"));
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any())).thenReturn(shellStep);

        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("a", "b", "c"));
        config.put("command", "echo {{ item }}");
        config.put("execution_mode", "shell");

        StepResult result = executor.execute(foreach(config), context);

        assertThat(result.getMetadata()).doesNotContainKey("aiRequest").containsEntry("totalItems", 3);
        assertThat((List<Map<String, Object>>) result.getMetadata().get("items")).hasSize(3);
    }

    /** The record keeps a summary of the first 500 items; the loop still runs every item and counts them all. */
    @Test
    @SuppressWarnings("unchecked")
    void execute_moreItemsThanTheRecordKeeps_runsThemAll_andRecordsTheFirst500() {
        StepResult shellStep = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(commands.shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any())).thenReturn(shellStep);
        Map<String, Object> config = new HashMap<>();
        config.put("items", IntStream.range(0, 502).mapToObj(i -> "item-" + i).toList());
        config.put("command", "echo {{ item }}");
        config.put("execution_mode", "shell");

        StepResult result = executor.execute(foreach(config), context);

        verify(commands, times(502)).shellStep(any(BlockDefinition.class), anyInt(), anyInt(), any());
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 502);
        List<Map<String, Object>> recorded = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(recorded).hasSize(500);
        assertThat(recorded.getLast()).containsEntry("item", "item-499");
    }

    // ---- a resumed loop ----

    /** What the record holds as finished: item "x" at index 0, recorded at seq 17. */
    private static final RecordedItems X_IS_RECORDED = (step, index, item) ->
            step == 4 && index == 0 && "x".equals(item) ? new RecordedItems.Completed("done before", 17) : null;

    private static Map<String, Object> shellLoop(String command, String... items) {
        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("items", List.of(items));
        config.put("command", command);
        return config;
    }

    /**
     * A resumed loop takes a recorded item over: it does not run, it is reported finished with the earlier event and
     * no start, and it draws its step index, so the items after it get the indices of one pass.
     */
    @Test
    void resumed_takesARecordedItemOver_withoutRunningIt() {
        context.startStepIndicesAt(4);
        context.recordedItems(X_IS_RECORDED);
        when(commands.shellStep(any(), anyInt(), anyInt(), any())).thenReturn(ok("done now"));

        StepResult result = executor.execute(foreach(shellLoop("handle {{ item }}", "x", "y")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("[x]: done before\n[y]: done now");
        assertThat(events).containsExactly(
                new ForEachItemFinished(4, 0, "x", 2, StepStatus.COMPLETED, "done before", null, 17),
                new ForEachItemStarted(4, 1, "y", 2),
                new ForEachItemFinished(4, 1, "y", 2, StepStatus.COMPLETED, "done now", null));
        // step 4 is the loop, 5 the item taken over, 6 the item that ran
        verify(commands, times(1)).shellStep(any(), eq(6), anyInt(), any());
        assertThat(result.getMetadata()).containsEntry("itemsResumed", 1).containsEntry("totalItems", 2);
    }

    /** The recorded items are for one pass: a loop that runs again in the same run (a validation retry) runs all. */
    @Test
    void resumed_theRecordedItemsAreTakenOnce() {
        context.startStepIndicesAt(4);
        context.recordedItems(X_IS_RECORDED);
        when(commands.shellStep(any(), anyInt(), anyInt(), any())).thenReturn(ok("done now"));
        executor.execute(foreach(shellLoop("handle {{ item }}", "x")), context);
        context.getStepIndices().rewindTo(4);

        StepResult again = executor.execute(foreach(shellLoop("handle {{ item }}", "x")), context);

        assertThat(again.getOutput()).isEqualTo("[x]: done now");
        assertThat(again.getMetadata()).doesNotContainKey("itemsResumed");
    }

    /**
     * Untrusted stays untrusted: platform data in the command of an item taken over marks the run as one that
     * carries untrusted input, as running the item would have.
     */
    @Test
    void resumed_anItemTakenOver_stillTaintsTheRunItsCommandWouldTaint() {
        context.startStepIndicesAt(4);
        context.setUntrustedPlatformVariable("previous_report", "from outside");
        context.recordedItems(X_IS_RECORDED);
        assertThat(context.hasUntrustedInput()).isFalse();

        executor.execute(foreach(shellLoop("handle {{ item }} {{ previous_report }}", "x")), context);

        verify(commands, never()).shellStep(any(), anyInt(), anyInt(), any());
        assertThat(context.hasUntrustedInput()).isTrue();
    }

    /** A loop that runs a workflow per item asks for no recorded item: a child run is resumed with its parent. */
    @Test
    void resumed_aLoopOfChildRuns_takesNothingOver() {
        SubWorkflowLauncher launcher = mock(SubWorkflowLauncher.class);
        when(launcher.run(eq("wf-child"), any())).thenReturn(
                new SubWorkflowLauncher.Outcome("wf-child", "Child", "sub-run", RunStatus.COMPLETED, "done", true));
        context.startStepIndicesAt(4);
        context.recordedItems(X_IS_RECORDED);
        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("x"));
        config.put("workflow_id", "wf-child");

        new ForEachBlockExecutor(commands, launcher).execute(foreach(config), context);

        verify(launcher, times(1)).run(eq("wf-child"), any());
    }
}
