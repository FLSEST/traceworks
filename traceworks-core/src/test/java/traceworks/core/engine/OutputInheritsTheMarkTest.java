package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.testing.FakeAiProvider;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.ProcessCommandRunner;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule: an output a block records is protected when any value that went into the block was protected. "Went
 * into" is every template the block resolved, for any purpose, and what a child workflow handed back. One test per
 * block type puts a protected value in by each of the block's template fields and then names the block's output in
 * a shell line, by the block's name, as {@code previous_output} and by its index: each is refused, and nothing of
 * the value runs. Through the engine, with the real blocks and a real bash.
 *
 * <p>Two protected values. Platform data ({@code previous_chain_output}) in a run that asked no model and carries
 * no untrusted input, so that nothing but the rule protects the output of a block without a model. And a variable a
 * parent run handed over as model text ({@code handed}, under {@code __model_keys}): a run that gets one counts as
 * one that asked a model, so there the state of the run protects every output as well.
 */
class OutputInheritsTheMarkTest {

    private static final String HOSTILE = "x; touch pwned";

    private static final List<String> PROTECTED = List.of("{{ previous_chain_output }}", "{{ handed }}");

    private static final List<String> NAMES_OF_THE_OUTPUT =
            List.of("{{ block.b.output }}", "{{ previous_output }}", "{{ step_0_output }}");

    @TempDir
    Path work;

    private final List<CommandRequest> requests = new CopyOnWriteArrayList<>();
    private final ProcessCommandRunner bash = new ProcessCommandRunner();
    private final CommandRunner commands = request -> {
        requests.add(request);
        return bash.run(request);
    };
    private final FakeAiProvider claude = new FakeAiProvider("CLAUDE");
    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final WorkflowEngine engine = new WorkflowEngine(CoreBlocks.registry(null));

    private RunOutcome run(WorkflowDefinition workflow) {
        return run(workflow, Map.of("previous_chain_output", HOSTILE, "handed", HOSTILE,
                ExecutionContext.MODEL_KEYS_VARIABLE, List.of("handed")));
    }

    private RunOutcome run(WorkflowDefinition workflow, Map<String, Object> inputs) {
        claude.otherwise("GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: done");
        return engine.run(workflow, inputs,
                RunOptions.builder().runId("run-1").events(new RecordingRunEvents())
                        .commandRunner(commands).aiProviders(claude.asProviders()).workflowResolver(workflows)
                        .clock(() -> Instant.parse("2026-11-16T08:00:00Z")).workingDirectory(work.toString()).build());
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, id, null, List.of(blocks), null, null, null, null, Map.of(), Map.of(),
                null, null, null);
    }

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    /** The block under test, named {@code b}: the first block of the run, so its index is 0. */
    private static BlockDefinition b(String type, Map<String, Object> config) {
        return block(type, "b", config);
    }

    private static BlockDefinition shell(String name, String command) {
        return block("shell", name, Map.of("command", command));
    }

    /**
     * For each protected value and each name of the output: runs the block {@code b} built with the protected
     * placeholder, then a shell line that names the output, and expects the refusal.
     */
    private void assertOutputProtected(String field, Function<String, BlockDefinition> withProtected) {
        for (String value : PROTECTED) {
            for (String name : NAMES_OF_THE_OUTPUT) {
                String what = field + " = " + value + ", then " + name;
                requests.clear();

                RunOutcome outcome = run(workflow("wf", withProtected.apply(value), shell("use", "echo " + name)));

                assertThat(outcome.steps()).as("%s: the block ran and the shell step was reached: %s", what,
                        outcome.error()).hasSize(2);
                assertThat(outcome.status()).as(what).isEqualTo(RunStatus.FAILED);
                assertThat(String.valueOf(outcome.error())).as(what)
                        .contains("shell line refused, nothing ran: " + name);
                assertThat(requests).as(what).noneMatch(request -> request.command().contains(HOSTILE));
                assertNothingOfTheValueRan(what);
            }
        }
    }

    private void assertNothingOfTheValueRan(String what) {
        try (Stream<Path> files = Files.list(work)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("pwned")))
                    .as("%s: commands of the value that ran", what).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String PRINT = "printf '%s' \"$V\"";

    @Test
    void shell() {
        assertOutputProtected("values", p -> b("shell", Map.of("values", Map.of("V", p), "command", PRINT)));
        assertOutputProtected("values of run_if", p -> b("shell", Map.of("values", Map.of("V", p),
                "run_if", "[ -n \"$V\" ]", "command", "echo plain")));
    }

    @Test
    void simple() {
        assertOutputProtected("command", p -> b("simple", Map.of("command", "Summarise " + p)));
    }

    @Test
    void agentic() {
        assertOutputProtected("goal", p -> b("agentic", Map.of("goal", "Fix " + p, "max_iterations", 1,
                "tools", "none")));
        assertOutputProtected("success_criteria", p -> b("agentic", Map.of("goal", "Fix it",
                "success_criteria", p, "max_iterations", 1, "tools", "none")));
    }

    @Test
    void foreach() {
        assertOutputProtected("values (shell items)", p -> b("foreach", Map.of("execution_mode", "shell",
                "items", List.of("one"), "values", Map.of("V", p), "command", PRINT)));
        assertOutputProtected("values of items_command", p -> b("foreach", Map.of("execution_mode", "shell",
                "items_source", "command", "values", Map.of("V", p), "items_command", "echo one; : \"$V\"",
                "command", "echo plain")));
        assertOutputProtected("command (AI items)", p -> b("foreach", Map.of("execution_mode", "ai",
                "items", List.of("one"), "command", "Judge " + p)));
        workflows.add("prints", workflow("prints", block("shell", "print",
                Map.of("values", Map.of("V", "{{ finding }}"), "command", PRINT))));
        assertOutputProtected("context (child per item)", p -> b("foreach", Map.of("items", List.of("one"),
                "workflow_id", "prints", "context", Map.of("finding", p))));
    }

    @Test
    void whileLoop() {
        assertOutputProtected("values (shell)", p -> b("while", Map.of("execution_mode", "shell",
                "values", Map.of("V", p), "condition", "[ ! -e done ] && touch done", "command", PRINT,
                "max_iterations", 2)));
        assertOutputProtected("condition (AI)", p -> b("while", Map.of("execution_mode", "ai",
                "condition", "Go on with " + p + "?", "command", "Do it", "max_iterations", 1)));
        assertOutputProtected("command (AI)", p -> b("while", Map.of("execution_mode", "ai",
                "condition", "Go on?", "command", "Do " + p, "max_iterations", 1)));
    }

    @Test
    void retry() {
        assertOutputProtected("values (shell)", p -> b("retry", Map.of("execution_mode", "shell",
                "values", Map.of("V", p), "command", PRINT)));
        assertOutputProtected("command (AI)", p -> b("retry", Map.of("execution_mode", "ai", "command", "Try " + p)));
    }

    @Test
    void conditional() {
        assertOutputProtected("values (shell)", p -> b("conditional", Map.of("execution_mode", "shell",
                "values", Map.of("V", p), "condition", "[ -n \"$V\" ]", "then_command", PRINT,
                "else_command", "echo other")));
        assertOutputProtected("values of the condition alone", p -> b("conditional", Map.of("execution_mode", "shell",
                "values", Map.of("V", p), "condition", "[ -n \"$V\" ]", "then_command", "echo plain",
                "else_command", "echo other")));
        assertOutputProtected("condition (AI)", p -> b("conditional", Map.of("execution_mode", "ai",
                "condition", "Is " + p + " so?", "then_command", "Do it", "else_command", "Do the other")));
        assertOutputProtected("then_command (AI)", p -> b("conditional", Map.of("execution_mode", "ai",
                "condition", "Is it?", "then_command", "Do " + p, "else_command", "Do " + p)));
    }

    @Test
    void switchBlock() {
        assertOutputProtected("expression", p -> b("switch", Map.of("expression", p, "cases", List.of(
                Map.of("value", "other", "blocks", List.of())), "default", List.of(Map.of("type", "shell",
                "name", "fallback", "config", Map.of("command", "echo fallback"))))));
        assertOutputProtected("a nested block", p -> b("switch", Map.of("expression", "a", "cases", List.of(
                Map.of("value", "a", "blocks", List.of(Map.of("type", "shell", "name", "inner",
                        "config", Map.of("values", Map.of("V", p), "command", PRINT))))))));
    }

    @Test
    void approval() {
        assertOutputProtected("auto_answer", p -> b("approval", Map.of("question", "Release?", "mode", "auto",
                "auto_answer", p)));
        assertOutputProtected("question", p -> b("approval", Map.of("question", "Release " + p + "?",
                "mode", "auto", "auto_answer", "Yes")));
    }

    @Test
    void humanInput() {
        assertOutputProtected("default_value", p -> b("human-input", Map.of("prompt", "Which?",
                "default_value", p, "timeout_minutes", 0, "use_default_on_timeout", true)));
        assertOutputProtected("prompt", p -> b("human-input", Map.of("prompt", "Which of " + p + "?",
                "default_value", "plain", "timeout_minutes", 0, "use_default_on_timeout", true)));
        assertOutputProtected("prompt (an AI actor answers)", p -> b("human-input", Map.of("question", "Why " + p + "?",
                "prompt", "Why " + p + "?", "actor", "ai")));
    }

    /** A compress block takes no template: it reads an output, and an output that is protected marks the run. */
    @Test
    void compressAndCompressAi_ofAProtectedOutput() {
        for (String type : List.of("compress", "compress-ai")) {
            for (String value : PROTECTED) {
                for (String name : List.of("{{ block.c.output }}", "{{ previous_output }}", "{{ step_1_output }}")) {
                    String what = type + " of " + value + ", then " + name;
                    Map<String, Object> config = type.equals("compress")
                            ? Map.of("source", "b", "stages", List.of(Map.of("op", "lines.head", "lines", 5)))
                            : Map.of("source", "b");

                    RunOutcome outcome = run(workflow("wf",
                            b("shell", Map.of("values", Map.of("V", value), "command", PRINT)),
                            block(type, "c", config), shell("use", "echo " + name)));

                    assertThat(outcome.steps()).as("%s: %s", what, outcome.error()).hasSize(3);
                    assertThat(String.valueOf(outcome.error())).as(what)
                            .contains("shell line refused, nothing ran: " + name);
                    assertNothingOfTheValueRan(what);
                }
            }
        }
    }

    // -- child workflows ----------------------------------------------------------------------------------------

    /** Down: what the parent hands over is protected in the child and in a grandchild. */
    @Test
    void subWorkflow_whatAParentBuiltFromAProtectedValue_isProtectedInTheChild_andInAGrandchild() {
        workflows.add("raw", workflow("raw", shell("use", "echo {{ finding }}")));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "raw"))));

        for (String value : PROTECTED) {
            for (String child : List.of("raw", "between")) {
                RunOutcome outcome = run(workflow("parent",
                        block("approval", "set", Map.of("question", "Release?", "mode", "auto", "auto_answer", value,
                                "variable_name", "finding")),
                        block("sub-workflow", "down", Map.of("workflow_id", child))));

                assertThat(outcome.steps().stream().map(StepResult::getOutput).toList().toString())
                        .as("%s into %s", value, child).contains("shell line refused, nothing ran: {{ finding }}");
                assertNothingOfTheValueRan(value + " into " + child);
            }
        }
    }

    /** Up: the output of a child that printed a protected value is protected in the parent, also two levels up. */
    @Test
    void subWorkflow_theOutputOfAChildThatHeldAProtectedValue_isProtectedInTheParent_alsoThroughAGrandchild() {
        workflows.add("prints", workflow("prints", block("shell", "print",
                Map.of("values", Map.of("V", "{{ finding }}"), "command", PRINT))));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "prints"))));
        // a child that takes the chain's output itself, without a variable of the parent
        workflows.add("chain", workflow("chain", block("shell", "print",
                Map.of("values", Map.of("V", "{{ handed }}"), "command", PRINT))));
        workflows.add("chain-between", workflow("chain-between",
                block("sub-workflow", "down", Map.of("workflow_id", "chain"))));

        for (String child : List.of("prints", "between", "chain", "chain-between")) {
            for (String value : PROTECTED) {
                for (String name : List.of("{{ block.down.output }}", "{{ previous_output }}", "{{ step_1_output }}")) {
                    String what = value + " through " + child + ", then " + name;

                    RunOutcome outcome = run(workflow("parent",
                            block("approval", "set", Map.of("question", "Release?", "mode", "auto",
                                    "auto_answer", value, "variable_name", "finding")),
                            block("sub-workflow", "down", Map.of("workflow_id", child)),
                            shell("use", "echo " + name)));

                    assertThat(outcome.steps()).as("%s: %s", what, outcome.error()).hasSize(3);
                    assertThat(outcome.steps().get(1).getOutput()).as(what).contains(HOSTILE);
                    assertThat(String.valueOf(outcome.error())).as(what)
                            .contains("shell line refused, nothing ran: " + name);
                    assertNothingOfTheValueRan(what);
                }
            }
        }
    }

    /**
     * A loop's item from an {@code items_command} that printed a protected value: the item is protected while the
     * loop runs, before the block's output is recorded.
     */
    @Test
    void foreach_anItemFromACommandThatPrintedAProtectedValue_isProtectedInsideTheLoop() {
        for (String value : PROTECTED) {
            RunOutcome outcome = run(workflow("wf", b("foreach", Map.of("execution_mode", "shell",
                    "items_source", "command", "values", Map.of("V", value),
                    "items_command", "printf '%s\\n' \"$V\"", "command", "echo {{ item }}"))));

            assertThat(outcome.status()).as(value).isNotEqualTo(RunStatus.COMPLETED);
            assertThat(outcome.steps().toString() + outcome.error()).as(value)
                    .contains("shell line refused, nothing ran: {{ item }}");
            assertNothingOfTheValueRan("the item of " + value);
        }
    }

    /** What went into one block does not mark the next: a block that took nothing protected records a plain output. */
    @Test
    void aBlockThatTookNothingProtected_recordsAnOutputThatIsInsertedAsBefore() {
        RunOutcome outcome = run(workflow("wf",
                block("approval", "plain", Map.of("question", "Release?", "mode", "auto", "auto_answer", "Yes")),
                shell("first", "echo one two"),
                shell("count", "set -- {{ block.first.output }}; printf '%s' \"$#\""),
                shell("answer", "printf '%s' {{ block.plain.output }}")),
                Map.of("previous_chain_output", HOSTILE));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().get(2).getOutput()).isEqualTo("2");
    }
}
