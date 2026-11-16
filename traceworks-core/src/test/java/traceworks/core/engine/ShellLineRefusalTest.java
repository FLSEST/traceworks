package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.CommandRunner;
import traceworks.core.testing.FakeAiProvider;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.ProcessCommandRunner;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A value the run quotes (text a model wrote, untrusted input) is one literal argument only as a bare word. Where the
 * author put its placeholder inside quotes, backticks, arithmetic, a heredoc body or a comment, the run's quotes do
 * not hold, and the shell line is refused before any command runs. Runs through the engine with the real blocks, a
 * scripted model and a real bash: the value tries to create files named {@code pwned*} in every way a shell offers,
 * and no test may find one. The analyzer is not asked here: these are the paths it cannot see, and a run started
 * past it.
 */
class ShellLineRefusalTest {

    /** Every way out of a quoting: both quotes, a command substitution in both spellings, a line of its own. */
    private static final String ONE_LINE = "a'; touch pwned1; echo '\"; touch pwned2; echo \"$(touch pwned3)"
            + "`touch pwned4`";
    private static final String HOSTILE = ONE_LINE + "\nEOF\ntouch pwned5\n: <<EOF";

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
        return run(workflow, null);
    }

    private RunOutcome run(WorkflowDefinition workflow, Map<String, Object> inputs) {
        return engine.run(workflow, inputs, RunOptions.builder().runId("run-1").events(new RecordingRunEvents())
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

    private void assertNothingOfTheValueRan(String what) {
        try (Stream<Path> files = Files.list(work)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("pwned")))
                    .as("%s: commands of the value that ran; the shell lines were %s", what, commandLines).isEmpty();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void assertRefused(String what, RunOutcome outcome, String placeholder, String place) {
        assertThat(outcome.status()).as(what).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).as(what).contains("shell line refused, nothing ran: " + placeholder + " stands "
                + place + ", and such a value is text a model wrote or comes from outside the run.");
        assertNothingOfTheValueRan(what);
    }

    /** Each place where the run's quotes do not hold: the line is refused, with the place in the message. */
    @Test
    void aModelsAnswer_whereQuotingDoesNotHold_refusesTheLine_inEveryPlace() {
        Map<String, String> places = new LinkedHashMap<>();
        places.put("echo \"msg: {{ block.ask.output }}\"", "inside double quotes");
        places.put("echo '{{ block.ask.output }}'", "inside single quotes");
        places.put("echo $'{{ block.ask.output }}'", "inside single quotes");
        places.put("echo `echo {{ block.ask.output }}`", "inside backticks");
        places.put("echo \"`echo {{ block.ask.output }}`\"", "inside backticks");
        places.put("echo $(( {{ block.ask.output }} + 1 ))", "inside an arithmetic expression");
        places.put("cat <<EOF\n{{ block.ask.output }}\nEOF", "in the body of a heredoc");
        places.put("cat <<'EOF'\n{{ block.ask.output }}\nEOF", "in the body of a heredoc");
        places.put("true # {{ block.ask.output }}", "in a comment");
        places.put("echo \\{{ block.ask.output }}", "where Traceworks cannot tell how the shell reads it (after a backslash, or in shell code with an"
                + " open quote or bracket)");
        places.put("echo \"$(case a in a) echo \"{{ block.ask.output }}\";; esac)\"", "inside double quotes");
        places.put("echo \"$(echo case) {{ block.ask.output }}\"", "inside double quotes");
        places.put("echo {{ block.ask.output }} \"open", "where Traceworks cannot tell how the shell reads it (after a backslash, or in shell code with an"
                + " open quote or bracket)");

        places.forEach((command, place) -> {
            commandLines.clear();
            claude.answers(HOSTILE);

            RunOutcome outcome = run(workflow("wf", ask(), shell("use", command)));

            assertRefused(command, outcome, "{{ block.ask.output }}", place);
            assertThat(commandLines).as("no command of the step ran: %s", command).isEmpty();
            StepResult step = outcome.steps().getLast();
            assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
            assertThat(step.getOutput()).startsWith("ShellLineRefusedException: shell line refused, nothing ran: ");
        });
    }

    /** The whole message, once. */
    @Test
    void theRefusal_namesThePlaceholderThePlaceAndTheFix() {
        claude.answers(HOSTILE);

        RunOutcome outcome = run(workflow("wf", ask(), shell("use", "echo \"{{ block.ask.output }}\"")));

        assertThat(outcome.steps().getLast().getOutput()).isEqualTo("ShellLineRefusedException: shell line refused,"
                + " nothing ran: {{ block.ask.output }} stands inside double quotes, and such a value is text a model"
                + " wrote or comes from outside the run. The run quotes it itself, which only holds for a bare word:"
                + " there a $(…), a quote or a line break in the value would run as a command. Write the placeholder"
                + " bare (X={{ block.ask.output }}, then \"$X\"), or pass the value through a file or standard input"
                + " (printf '%s\\n' {{ block.ask.output }} > file)");
    }

    /** A bare word, also inside a command substitution, takes the value as one literal argument: it runs. */
    @Test
    void aModelsAnswer_asABareWord_runs_andNothingOfItRuns() {
        claude.answers(HOSTILE);

        RunOutcome outcome = run(workflow("wf", ask(),
                shell("assign", "X={{ block.ask.output }}; printf '%s' \"$X\""),
                shell("substituted", "printf '%s' \"$(printf '%s' {{ block.ask.output }})\""),
                shell("to_file", "printf '%s' {{ block.ask.output }} > answer.txt; cat answer.txt"),
                shell("here_string", "cat <<< {{ block.ask.output }}"),
                shell("in_a_case", "case x in x) printf '%s' {{ block.ask.output }};; esac")));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        for (int step = 1; step <= 5; step++) {
            assertThat(outcome.steps().get(step).getOutput().strip()).as("step %d", step).isEqualTo(HOSTILE);
        }
        assertNothingOfTheValueRan("bare words");
    }

    /** With shell_escape_variables the run quotes every value; a model's answer inside quotes is refused all the same. */
    @Test
    void shellEscapeVariables_doesNotMakeQuotesSafe_theLineIsRefused() {
        claude.answers(HOSTILE);

        RunOutcome outcome = run(workflow("wf", ask(), block("shell", "use", Map.of("shell_escape_variables", true,
                "command", "echo \"{{ block.ask.output }}\" '{{ workflow_id }}'"))));

        assertRefused("escaped block", outcome, "{{ block.ask.output }}", "inside double quotes");
    }

    /** What nothing marks is inserted as before, inside quotes too: a run without AI and untrusted input. */
    @Test
    void aRunWithoutMarkedValues_insertsInsideQuotesAsBefore() {
        RunOutcome outcome = run(workflow("wf", shell("say", "echo one two"),
                shell("use", "echo \"{{ block.say.output }}\" '{{ previous_output }}' \"{{ topic }}\"")),
                Map.of("topic", "release notes"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines.getLast()).isEqualTo("echo \"one two\" 'one two' \"release notes\"");
    }

    // -- the paths the analyzer cannot see -----------------------------------------------------------------------

    @Test
    void aLoopItemThatCameAfterAnAiStep_insideQuotes_isRefused() {
        claude.answers(ONE_LINE);

        RunOutcome outcome = run(workflow("wf", ask(),
                block("foreach", "each", Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "printf '%s\\n' {{ block.ask.output }}",
                        "command", "echo \"item: {{ item }}\""))));

        assertRefused("loop item", outcome, "{{ item }}", "inside double quotes");
        assertThat(commandLines).as("only the items_command ran").hasSize(1);
    }

    @Test
    void theVariableAnAiActorsHumanInputSet_insideQuotes_isRefused() {
        claude.answers(ONE_LINE);

        RunOutcome outcome = run(workflow("wf",
                block("human-input", "why", Map.of("question", "Why?", "actor", "ai", "variable_name", "reason")),
                shell("use", "echo 'reason: {{ reason }}'")));

        assertRefused("AI actor's answer", outcome, "{{ reason }}", "inside single quotes");
        assertThat(commandLines).isEmpty();
    }

    @Test
    void aStepIndexAfterAWrapper_insideQuotes_isRefused() {
        claude.answers(HOSTILE);

        RunOutcome outcome = run(workflow("wf",
                block("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a", "b"),
                        "command", "true")),
                ask(),
                shell("use", "echo \"{{ step_3_output }}\"")));

        assertThat(outcome.steps().get(1).getStepIndex()).as("the AI step's index, past the loop's items").isEqualTo(3);
        assertRefused("step index", outcome, "{{ step_3_output }}", "inside double quotes");
    }

    @Test
    void previousOutputInANestedBlock_insideQuotes_isRefused() {
        claude.answers(HOSTILE);

        RunOutcome outcome = run(workflow("wf", block("switch", "route", Map.of("expression", "a",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "name", "draft", "config", Map.of("command", "Draft")),
                        Map.of("type", "shell", "name", "show", "config",
                                Map.of("command", "echo \"{{ previous_output }}\"")))))))));

        assertRefused("nested previous_output", outcome, "{{ previous_output }}", "inside double quotes");
        assertThat(commandLines).isEmpty();
    }

    /** The parent's check does not judge a child's shell lines: the child's run refuses them. */
    @Test
    void whatAParentHandsToAChild_insideTheChildsQuotes_isRefused_inAGrandchildToo() {
        claude.answers(HOSTILE);
        workflows.add("child", workflow("child", shell("use", "echo \"{{ previous_output }}\"")));
        workflows.add("grandchild", workflow("grandchild", shell("use", "cat <<EOF\n{{ step_0_output }}\nEOF")));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "grandchild"))));

        RunOutcome outcome = run(workflow("parent", ask(),
                block("sub-workflow", "child", Map.of("workflow_id", "child", "on_failure", "continue")),
                block("sub-workflow", "between", Map.of("workflow_id", "between", "on_failure", "continue"))));

        assertThat(outcome.steps().get(1).getOutput()).contains("shell line refused, nothing ran:"
                + " {{ previous_output }} stands inside double quotes");
        assertThat(outcome.steps().get(2).getOutput()).contains("shell line refused, nothing ran:"
                + " {{ step_0_output }} stands in the body of a heredoc");
        assertThat(commandLines).isEmpty();
        assertNothingOfTheValueRan("child and grandchild");
    }

    /** The output of a grandchild that asked a model is model text in the parent of its parent. */
    @Test
    void theOutputOfAGrandchildThatAskedAModel_isQuotedTwoLevelsUp_andRefusedInsideQuotes() {
        claude.answers(HOSTILE);
        workflows.add("grandchild", workflow("grandchild", ask()));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "grandchild"))));

        RunOutcome outcome = run(workflow("parent",
                block("sub-workflow", "between", Map.of("workflow_id", "between")),
                shell("bare", "printf '%s' {{ block.between.output }}"),
                shell("quoted", "echo \"{{ block.between.output }}\"")));

        assertThat(outcome.steps().get(1).getOutput()).isEqualTo(HOSTILE);
        assertRefused("grandchild's answer", outcome, "{{ block.between.output }}", "inside double quotes");
    }

    /** then_command and else_command are shell lines like any other. */
    @Test
    void thenAndElseCommands_quoteAModelsAnswer_andRefuseItInsideQuotes() {
        claude.answers(HOSTILE, HOSTILE);

        RunOutcome bare = run(workflow("wf", ask(), block("conditional", "branch", Map.of("execution_mode", "shell",
                "condition", "false", "then_command", "echo then", "else_command",
                "printf '%s' {{ block.ask.output }}"))));
        assertThat(bare.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(bare.steps().getLast().getOutput()).contains(HOSTILE);

        RunOutcome quoted = run(workflow("wf", ask(), block("conditional", "branch", Map.of("execution_mode", "shell",
                "condition", "true", "then_command", "echo \"{{ block.ask.output }}\"", "else_command", "echo else"))));
        assertRefused("then_command", quoted, "{{ block.ask.output }}", "inside double quotes");
    }

    // -- the taint that was there before: untrusted input and platform data ---------------------------------------

    /** Untrusted input was quoted inside the author's quotes too, where it ran; it is refused the same way now. */
    @Test
    void untrustedInputAndPlatformData_insideQuotes_areRefused_andBareTheyAreOneArgument() {
        Map<String, Object> inputs = Map.of("ticket", HOSTILE, "previous_chain_output", HOSTILE,
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("ticket"));

        RunOutcome bare = run(workflow("wf", shell("use",
                "printf '%s|%s' {{ ticket }} {{ previous_chain_output }}")), inputs);
        assertThat(bare.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(bare.steps().getFirst().getOutput()).isEqualTo(HOSTILE + "|" + HOSTILE);
        assertNothingOfTheValueRan("bare untrusted input");

        assertRefused("untrusted input", run(workflow("wf", shell("use", "echo \"{{ ticket }}\"")), inputs),
                "{{ ticket }}", "inside double quotes");
        assertRefused("platform data", run(workflow("wf", shell("use", "echo '{{ previous_chain_output }}'")),
                inputs), "{{ previous_chain_output }}", "inside single quotes");
        assertRefused("output of a tainted run", run(workflow("wf", shell("first", "printf '%s' {{ ticket }}"),
                shell("use", "cat <<EOF\n{{ previous_output }}\nEOF")), inputs),
                "{{ previous_output }}", "in the body of a heredoc");
    }

    /** A run_if test and an items_command are shell lines too: refused before the test or the list runs. */
    @Test
    void runIfAndItemsCommand_refuseAModelsAnswerInsideQuotes() {
        claude.answers(HOSTILE, HOSTILE);

        assertRefused("run_if", run(workflow("wf", ask(), block("shell", "gated",
                Map.of("run_if", "test \"{{ block.ask.output }}\" = x", "command", "echo ran")))),
                "{{ block.ask.output }}", "inside double quotes");
        assertRefused("items_command", run(workflow("wf", ask(), block("foreach", "each",
                Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "echo \"{{ block.ask.output }}\"", "command", "true")))),
                "{{ block.ask.output }}", "inside double quotes");
        assertThat(commandLines).isEmpty();
    }
}
