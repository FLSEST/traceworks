package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edges of the protection of a value in a shell line, through the engine with the real blocks and a real bash:
 * a variable built from a template inherits the mark of what went into it, in a fresh run too, and a value that was
 * not delivered fails the block instead of answering a condition.
 */
class ProtectedValueEdgesTest {

    /** Platform data that would run a command in a shell line. */
    private static final String HOSTILE = "x; touch pwned";

    @TempDir
    Path work;

    private final List<CommandRequest> requests = new CopyOnWriteArrayList<>();
    private final ProcessCommandRunner bash = new ProcessCommandRunner();
    /** Runs the request; with {@link #breakDelivery} under a name that bash does not keep, so the delivery fails. */
    private boolean breakDelivery;
    private final CommandRunner commands = request -> {
        requests.add(request);
        if (breakDelivery && !request.values().isEmpty()) {
            Map<String, String> unkept = new LinkedHashMap<>();
            unkept.put("_", request.values().values().iterator().next());
            return bash.run(request.withValues(unkept));
        }
        return bash.run(request);
    };
    private final FakeAiProvider claude = new FakeAiProvider("CLAUDE");
    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final WorkflowEngine engine = new WorkflowEngine(CoreBlocks.registry(null));

    /** A fresh run: no model asked, no untrusted input; its only protected value is the platform data of a chain. */
    private RunOutcome run(WorkflowDefinition workflow) {
        return engine.run(workflow, Map.of("previous_chain_output", HOSTILE),
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

    private static BlockDefinition shell(String name, String command) {
        return block("shell", name, Map.of("command", command));
    }

    private void assertRefused(String what, RunOutcome outcome, String placeholder) {
        assertThat(outcome.status()).as(what).isEqualTo(RunStatus.FAILED);
        assertThat(String.valueOf(outcome.error()) + outcome.steps().stream().map(StepResult::getOutput).toList())
                .as(what).contains("shell line refused, nothing ran: " + placeholder);
        assertNothingOfTheValueRan(what);
    }

    private void assertNothingOfTheValueRan(String what) {
        try (Stream<Path> files = Files.list(work)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("pwned")))
                    .as("%s: commands of the value that ran", what).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -- a variable built from a template inherits the mark ----------------------------------------------------

    @Test
    void aForeachContextEntryBuiltFromPlatformData_isProtected_inTheParent_andInTheChild() {
        workflows.add("raw", workflow("raw", shell("use", "echo {{ finding }}")));
        workflows.add("handed", workflow("handed", block("shell", "use",
                Map.of("values", Map.of("FINDING", "{{ finding }}"), "command", "printf '%s' \"$FINDING\""))));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "raw"))));

        for (String child : List.of("raw", "between")) {
            RunOutcome outcome = run(workflow("parent", block("foreach", "each", Map.of("items", List.of("one"),
                    "workflow_id", child, "context", Map.of("finding", "{{ previous_chain_output }}")))));
            assertThat(outcome.steps().toString() + outcome.error()).as(child)
                    .contains("shell line refused, nothing ran: {{ finding }}");
            assertNothingOfTheValueRan("the child " + child);
        }

        assertRefused("the parent, after the loop", run(workflow("parent",
                block("foreach", "each", Map.of("items", List.of("one"), "workflow_id", "handed",
                        "context", Map.of("finding", "Finding: {{ previous_chain_output }}"))),
                shell("use", "echo {{ finding }}"))), "{{ finding }}");
        assertThat(requests.getLast().values()).as("the child got it as a value")
                .containsOnly(Map.entry("FINDING", "Finding: " + HOSTILE));
    }

    @Test
    void theDefaultOfAHumanInputBuiltFromPlatformData_takenAfterATimeout_isProtected() {
        assertRefused("default_value", run(workflow("wf",
                block("human-input", "ask", Map.of("prompt", "Which one?", "variable_name", "picked",
                        "default_value", "{{ previous_chain_output }}", "timeout_minutes", 0,
                        "use_default_on_timeout", true)),
                shell("use", "echo {{ picked }}"))), "{{ picked }}");
    }

    @Test
    void anAutoAnswerBuiltFromPlatformData_isProtected() {
        assertRefused("auto_answer", run(workflow("wf",
                block("approval", "taken", Map.of("question", "Release?", "mode", "auto",
                        "auto_answer", "{{ previous_chain_output }}", "variable_name", "taken")),
                shell("use", "echo {{ taken }}"))), "{{ taken }}");
    }

    /** What is built from the workflow's own text and trusted values stays trusted, as before. */
    @Test
    void aVariableBuiltFromTrustedParts_isInsertedAsItIs() {
        workflows.add("raw", workflow("raw", shell("use", "set -- {{ finding }}; printf '%s' \"$#\"")));

        RunOutcome outcome = run(workflow("parent", block("foreach", "each", Map.of("items", List.of("one"),
                "workflow_id", "raw", "context", Map.of("finding", "{{ item }} two")))));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(requests.getLast().command()).isEqualTo("set -- one two; printf '%s' \"$#\"");
    }

    // -- a value that was not delivered is no answer -----------------------------------------------------------

    private static final Map<String, String> VALUE = Map.of("CHAIN", "{{ previous_chain_output }}");

    @Test
    void aFailedDeliveryInRunIf_failsTheBlock_andDoesNotSkipIt() {
        breakDelivery = true;

        RunOutcome outcome = run(workflow("wf", block("shell", "guarded",
                Map.of("values", VALUE, "run_if", "[ -n \"$CHAIN\" ]", "command", "touch ran"))));

        StepResult step = outcome.steps().getFirst();
        assertThat(step.getStatus()).as(step.getOutput()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("run_if failed: it did not run").contains("was not delivered");
        assertThat(work.resolve("ran")).doesNotExist();
    }

    @Test
    void aFailedDeliveryInACondition_failsTheBlock_andTakesNoBranch() {
        breakDelivery = true;

        RunOutcome outcome = run(workflow("wf", block("conditional", "branch", Map.of("values", VALUE,
                "execution_mode", "shell", "condition", "[ -n \"$CHAIN\" ]",
                "then", "touch then-ran", "else", "touch else-ran"))));

        StepResult step = outcome.steps().getFirst();
        assertThat(step.getStatus()).as(step.getOutput()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("did not run").contains("was not delivered");
        assertThat(work.resolve("then-ran")).doesNotExist();
        assertThat(work.resolve("else-ran")).doesNotExist();
    }

    @Test
    void aFailedDeliveryInALoopCondition_failsTheBlock_andDoesNotEndTheLoopAsAnAnswer() {
        breakDelivery = true;

        RunOutcome outcome = run(workflow("wf", block("while", "loop", Map.of("values", VALUE,
                "execution_mode", "shell", "condition", "[ -n \"$CHAIN\" ]", "command", "touch body-ran",
                "max_iterations", 2))));

        StepResult step = outcome.steps().getFirst();
        assertThat(step.getStatus()).as(step.getOutput()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("was not delivered");
        assertThat(work.resolve("body-ran")).doesNotExist();
    }

    /** A command that ends with the exit code of a failed delivery by itself ran: it is the condition's answer. */
    @Test
    void aConditionThatExits74ByItself_isAnAnswer_alsoWhenItPrintsWhatLooksLikeTheMarker() {
        RunOutcome outcome = run(workflow("wf", block("shell", "guarded", Map.of("values", VALUE,
                "run_if", "echo \"traceworks:0000: the value of CHAIN was not delivered $CHAIN\" >&2; exit 74",
                "command", "touch ran"))));

        StepResult step = outcome.steps().getFirst();
        assertThat(step.getStatus()).as(step.getOutput()).isEqualTo(StepStatus.SKIPPED);
        assertThat(step.getOutput()).isEqualTo("Skipped (run_if: exit 74)");
    }
}
