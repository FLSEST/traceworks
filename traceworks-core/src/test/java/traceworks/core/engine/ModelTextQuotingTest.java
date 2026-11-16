package traceworks.core.engine;

import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.CommandRunner;
import traceworks.core.testing.FakeAiProvider;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.ProcessCommandRunner;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output of an AI step reaches a shell line as one value, never as shell code: runs through the engine with the
 * real blocks, a scripted model and a real bash, so the tests see what the shell made of the value. No block here sets
 * {@code shell_escape_variables}: the analyzer refuses the direct forms before a run, and these are the engine's
 * answer for a run that got past it ({@code --no-check}, a child workflow) and for the paths the analyzer cannot see.
 */
class ModelTextQuotingTest {

    /** A model's answer that would run commands in an unquoted shell line. */
    private static final String HOSTILE = "it's $(echo pwned) `echo pwned`; echo pwned\nsecond line";

    @TempDir
    Path work;

    private final List<String> commandLines = new CopyOnWriteArrayList<>();
    private final ProcessCommandRunner bash = new ProcessCommandRunner();
    private final CommandRunner commands = request -> {
        commandLines.add(request.command());
        return bash.run(request);
    };
    private final FakeAiProvider claude = new FakeAiProvider("CLAUDE");
    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final WorkflowEngine engine = new WorkflowEngine(CoreBlocks.registry(null));

    private RunOutcome run(WorkflowDefinition workflow) {
        return engine.run(workflow, null, RunOptions.builder().runId("run-1").events(new RecordingRunEvents())
                .commandRunner(commands).aiProviders(claude.asProviders()).workflowResolver(workflows)
                .clock(() -> Instant.parse("2026-11-15T08:00:00Z")).workingDirectory(work.toString()).build());
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, id, null, List.of(blocks), null, null, null, null, Map.of(), Map.of(),
                null, null, null);
    }

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    private static BlockDefinition shell(String name, String command) {
        return block("shell", name, Map.of("command", command));
    }

    private static BlockDefinition ask() {
        return block("simple", "ask", Map.of("command", "Say something"));
    }

    private static String output(RunOutcome outcome, int step) {
        StepResult result = outcome.steps().get(step);
        assertThat(result.getStatus()).as("step %d: %s", step, result.getOutput())
                .isEqualTo(traceworks.core.model.StepStatus.COMPLETED);
        return result.getOutput().strip();
    }

    @Test
    void theOutputOfAnAiStep_arrivesAsOneLiteralArgument_byNameByIndexAndAsPreviousOutput() {
        claude.answers(HOSTILE);

        RunOutcome outcome = run(workflow("wf", ask(),
                shell("by_name", "set -- {{ block.ask.output }}; printf '%s|%s' \"$#\" \"$1\""),
                shell("by_index", "set -- {{ step_0_output }}; printf '%s|%s' \"$#\" \"$1\""),
                shell("previous", "true"),
                shell("by_previous", "set -- x {{ previous_output }}; printf '%s' \"$#\"")));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 1)).isEqualTo("1|" + HOSTILE);
        assertThat(output(outcome, 2)).isEqualTo("1|" + HOSTILE);
        assertThat(output(outcome, 4)).as("the empty output of a shell step after the AI step is one argument too")
                .isEqualTo("2");
    }

    /** Every block that asks a model: its output reaches the shell line quoted, whatever the type. */
    @Test
    void theOutputOfEveryBlockTypeThatAsksAModel_isQuoted() {
        Map<String, BlockDefinition> asking = new java.util.LinkedHashMap<>();
        asking.put("simple", ask());
        asking.put("agentic", block("agentic", "ask", Map.of("goal", "Fix it", "max_iterations", 1, "tools", "none")));
        asking.put("foreach", block("foreach", "ask", Map.of("execution_mode", "ai", "items", List.of("a"),
                "command", "Judge {{ item }}")));
        asking.put("retry", block("retry", "ask", Map.of("execution_mode", "ai", "command", "Try it")));
        asking.put("while", block("while", "ask", Map.of("execution_mode", "ai", "condition", "Go on?",
                "command", "Do it", "max_iterations", 1)));
        asking.put("conditional", block("conditional", "ask", Map.of("execution_mode", "ai", "condition", "Is it?",
                "then_command", "Do it", "else_command", "Do the other")));
        asking.put("compress-ai", block("compress-ai", "ask", Map.of("source", "previous")));
        asking.put("switch", block("switch", "ask", Map.of("expression", "a", "cases", List.of(Map.of("value", "a",
                "blocks", List.of(Map.of("type", "simple", "name", "inner", "config", Map.of("command", "Say"))))))));
        asking.put("human-input", block("human-input", "ask", Map.of("question", "Why?", "actor", "ai")));

        asking.forEach((type, block) -> {
            commandLines.clear();
            claude.otherwise("GOAL_STATUS: ACHIEVED\nRATIONALE: x; echo pwned\nACTION:\nSUMMARY: x; echo pwned");

            RunOutcome outcome = run(workflow("wf-" + type, shell("first", "echo text"), block,
                    shell("use", "printf '%s' {{ block.ask.output }} {{ previous_output }}")));

            assertThat(outcome.steps()).as("%s: %s", type, outcome.error()).isNotEmpty();
            String line = commandLines.getLast();
            assertThat(line).as("%s: %s", type, outcome.steps().stream().map(StepResult::getOutput).toList())
                    .startsWith("IFS= read -r -d '' TRACEWORKS_VALUE_1 <<'TRACEWORKS_VALUE_1_END' || :\n")
                    .doesNotContain("{{")
                    .endsWith("\nprintf '%s' \"${TRACEWORKS_VALUE_1}\" \"${TRACEWORKS_VALUE_2}\"");
        });
    }

    @Test
    void aLoopItemMadeFromAModelsAnswer_isQuotedInTheItemsCommandAndInTheLoopBody() {
        claude.answers("a b; $(echo pwned)");

        RunOutcome outcome = run(workflow("wf", ask(),
                block("foreach", "each", Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "printf '%s\\n' {{ block.ask.output }}",
                        "command", "set -- {{ item }}; printf '%s|%s' \"$#\" \"$1\""))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines).containsExactly(
                FakeCommandRunner.value(1, "a b; $(echo pwned)") + "printf '%s\\n' \"${TRACEWORKS_VALUE_1}\"",
                FakeCommandRunner.value(1, "a b; $(echo pwned)") + "set -- \"${TRACEWORKS_VALUE_1}\";"
                        + " printf '%s|%s' \"$#\" \"$1\"");
        assertThat(output(outcome, 1)).contains("1|a b; $(echo pwned)").doesNotContain("1|pwned");
    }

    @Test
    void aModelsAnswer_isQuotedInARunIfTest_andInTheConditionOfAConditional() {
        claude.answers("x; echo pwned");

        RunOutcome outcome = run(workflow("wf", ask(),
                block("shell", "gated", Map.of("run_if", "test {{ block.ask.output }} = 'x; echo pwned'",
                        "command", "echo ran")),
                block("conditional", "branch", Map.of("execution_mode", "shell",
                        "condition", "test {{ block.ask.output }} = 'x; echo pwned'",
                        "then_command", "printf '%s' {{ step_0_output }}", "else_command", "echo else"))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 1)).isEqualTo("ran");
        assertThat(commandLines).contains(
                FakeCommandRunner.value(1, "x; echo pwned") + "test \"${TRACEWORKS_VALUE_1}\" = 'x; echo pwned'",
                FakeCommandRunner.value(1, "x; echo pwned") + "printf '%s' \"${TRACEWORKS_VALUE_1}\"");
    }

    /**
     * A shell step can print what the model wrote, or read a file an agent wrote, without naming a placeholder: from
     * the first call on every output is quoted. What a step recorded before the call is used as before.
     */
    @Test
    void outputsRecordedAfterTheCall_areQuoted_andThoseRecordedBeforeItAreNot() {
        claude.answers("fine");

        RunOutcome outcome = run(workflow("wf",
                shell("before", "echo one two"),
                ask(),
                shell("after", "echo three four"),
                shell("count", "set -- {{ block.before.output }}; printf '%s ' \"$#\";"
                        + " set -- {{ block.after.output }}; printf '%s ' \"$#\";"
                        + " set -- {{ step_0_output }}; printf '%s ' \"$#\";"
                        + " set -- {{ step_2_output }}; printf '%s' \"$#\"")));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 3)).isEqualTo("2 1 2 1");
    }

    /** A workflow that asks no model runs as it always did: the output of a shell step is inserted as it is. */
    @Test
    void aRunWithoutAnAiStep_insertsTheOutputOfAShellStepAsItIs() {
        RunOutcome outcome = run(workflow("wf",
                shell("list", "echo one two three"),
                shell("count", "set -- {{ block.list.output }}; printf '%s ' \"$#\";"
                        + " set -- {{ previous_output }}; printf '%s ' \"$#\";"
                        + " set -- {{ step_0_output }}; printf '%s' \"$#\""),
                block("foreach", "each", Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "echo 'a b'", "command", "set -- {{ item }}; printf '%s' \"$#\""))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 1)).isEqualTo("3 3 3");
        assertThat(commandLines).contains("set -- a b; printf '%s' \"$#\"");
    }

    /** A step whose call fails still asked a model; what it left behind is not known, so the rule is the same. */
    @Test
    void theOutputOfAFailedCall_isQuotedToo() {
        claude.fails(1, "$(echo pwned)");

        RunOutcome outcome = run(workflow("wf",
                block("simple", "ask", Map.of("command", "Say something", "on_failure", "continue")),
                shell("use", "printf '%s' {{ block.ask.output }}")));

        assertThat(commandLines)
                .containsExactly(FakeCommandRunner.value(1, "$(echo pwned)") + "printf '%s' \"${TRACEWORKS_VALUE_1}\"");
        assertThat(outcome.steps().getLast().getOutput()).isEqualTo("$(echo pwned)");
    }

    @Test
    void theOutputOfAChildWorkflowThatAskedAModel_isQuotedInTheParent() {
        claude.answers("child; echo pwned");
        workflows.add("child", workflow("child", ask()));
        workflows.add("plain", workflow("plain", shell("say", "echo one two")));

        RunOutcome outcome = run(workflow("parent",
                block("sub-workflow", "plain", Map.of("workflow_id", "plain")),
                shell("count_plain", "set -- {{ block.plain.output }}; printf '%s' \"$#\""),
                block("sub-workflow", "judged", Map.of("workflow_id", "child")),
                shell("count_judged", "set -- {{ block.judged.output }}; printf '%s|%s' \"$#\" \"$1\"")));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 1)).as("a child without a model: as before").isEqualTo("2");
        assertThat(output(outcome, 3)).isEqualTo("1|child; echo pwned");
    }

    @Test
    void whatAParentHandsToAChildAfterItAskedAModel_isQuotedInTheChild() {
        claude.answers("parent; echo pwned");
        workflows.add("child", workflow("child",
                shell("use", "set -- {{ previous_output }}; printf '%s|%s|' \"$#\" \"$1\";"
                        + " set -- {{ step_0_output }}; printf '%s' \"$#\""),
                shell("own", "echo a b"),
                shell("count_own", "set -- {{ block.own.output }}; printf '%s' \"$#\"")));

        RunOutcome outcome = run(workflow("parent", ask(),
                block("sub-workflow", "child", Map.of("workflow_id", "child"))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines.getFirst()).isEqualTo(FakeCommandRunner.value(1, "parent; echo pwned")
                + FakeCommandRunner.value(2, "parent; echo pwned") + ""
                + "set -- \"${TRACEWORKS_VALUE_1}\"; printf '%s|%s|' \"$#\" \"$1\";"
                + " set -- \"${TRACEWORKS_VALUE_2}\"; printf '%s' \"$#\"");
        assertThat(commandLines.getLast()).as("the child's own output, recorded after the parent's call")
                .startsWith("IFS= read -r -d '' TRACEWORKS_VALUE_1 <<'TRACEWORKS_VALUE_1_END' || :\na b\n")
                .endsWith("set -- \"${TRACEWORKS_VALUE_1}\"; printf '%s' \"$#\"");
    }

    /**
     * An auto_answer filled in from a placeholder is whatever that value was: after an AI step, model text, which
     * stays quoted in the variable. One the workflow writes out is the author's text and is used as before.
     */
    @Test
    void anAutoAnswerFilledInFromAModelsAnswer_staysModelText_andOneWrittenOutIsTrusted() {
        claude.answers("yes; echo pwned", "fine");

        RunOutcome outcome = run(workflow("wf", ask(),
                block("approval", "taken", Map.of("question", "Release?", "mode", "auto",
                        "auto_answer", "{{ block.ask.output }}", "variable_name", "taken")),
                shell("use", "set -- {{ taken }}; printf '%s|%s' \"$#\" \"$1\""),
                block("approval", "written", Map.of("question", "Release?", "mode", "auto", "auto_answer", "Yes",
                        "variable_name", "written")),
                shell("compare", "[ \"{{ written }}\" = Yes ] && echo released")));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 2)).isEqualTo("1|yes; echo pwned");
        assertThat(output(outcome, 4)).isEqualTo("released");
    }

    /**
     * A trusted write takes the mark off a variable, as it takes off the mark of untrusted input: the static items
     * of a loop are the workflow's own text, also in a variable that held a model's answer before.
     */
    @Test
    void aVariableThatHeldModelText_setAgainFromTheWorkflowsOwnText_isTrustedAgain() {
        claude.answers("x y");

        RunOutcome outcome = run(workflow("wf", ask(),
                block("foreach", "from_answer", Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "printf '%s\\n' {{ block.ask.output }}", "command", "echo {{ item }}")),
                block("foreach", "written", Map.of("execution_mode", "shell", "items", List.of("a b"),
                        "command", "echo \"{{ item }}\""))));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines).containsExactly(
                FakeCommandRunner.value(1, "x y") + "printf '%s\\n' \"${TRACEWORKS_VALUE_1}\"",
                FakeCommandRunner.value(1, "x y") + "echo \"${TRACEWORKS_VALUE_1}\"", "echo \"a b\"");
    }

    /** Model text is quoted; it does not make the run one with untrusted input, so an AI actor still decides. */
    @Test
    void aRunThatAskedAModel_carriesNoUntrustedInput_soAnAiActorStillAnswers() {
        claude.answers("draft", "Yes");

        RunOutcome outcome = run(workflow("wf", ask(),
                block("approval", "release", Map.of("question", "Release?", "actor", "ai")),
                shell("use", "printf '%s' {{ approval_answer }}")));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(claude.prompts()).hasSize(2);
        assertThat(output(outcome, 2)).isEqualTo("Yes");
    }
}
