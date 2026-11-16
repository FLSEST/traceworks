package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.context.ExecutionContext;
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
 * A protected value never goes into shell code: the output of an AI step, and everything a run records after its
 * first call of a model, refuses a shell line that names it, and reaches a command as a shell variable the block
 * names under {@code values}. Runs through the engine with the real blocks, a scripted model and a real bash. Every
 * hostile value tries to create a file named {@code pwned*} in the working directory; no test may find one. The
 * analyzer is not asked here: these are the engine's answers for a run that got past it, and for what it cannot see.
 */
class ModelTextQuotingTest {

    /** A model's answer that would run commands in a shell line, in every way a shell offers. */
    private static final String HOSTILE = "it's $(touch pwned1) `touch pwned2`; touch pwned3\n\"; touch pwned4; \"\n"
            + "'; touch pwned5; '";

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

    /** A shell block whose command finds the variables of {@code values} set. */
    private static BlockDefinition shell(String name, Map<String, String> values, String command) {
        return block("shell", name, Map.of("values", values, "command", command));
    }

    private static BlockDefinition ask() {
        return block("simple", "ask", Map.of("command", "Say something"));
    }

    private static String output(RunOutcome outcome, int step) {
        StepResult result = outcome.steps().get(step);
        assertThat(result.getStatus()).as("step %d: %s", step, result.getOutput()).isEqualTo(StepStatus.COMPLETED);
        return result.getOutput();
    }

    private List<String> commandLines() {
        return requests.stream().map(CommandRequest::command).toList();
    }

    private void assertNothingOfTheValueRan(String what) {
        try (Stream<Path> files = Files.list(work)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("pwned")))
                    .as("%s: commands of the value that ran", what).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void assertRefused(String what, RunOutcome outcome, String placeholder) {
        assertThat(outcome.status()).as(what).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).as(what).contains("shell line refused, nothing ran: " + placeholder + " is text a"
                + " model wrote or text from outside the run");
        assertNothingOfTheValueRan(what);
    }

    // -- the way in: values -------------------------------------------------------------------------------------

    /** The value arrives byte for byte in the variable; the command's text holds no byte of it. */
    @Test
    void aModelsAnswer_reachesACommandAsAVariable_byteForByte_andIsNoPartOfItsText() {
        claude.answers(HOSTILE);

        RunOutcome outcome = run(workflow("wf", ask(), shell("use",
                Map.of("REVIEW", "{{ block.ask.output }}", "TITLE", "Report on {{ topic }}: {{ step_0_output }}"),
                "set -- \"$REVIEW\"; printf '%s|%s|%s' \"$#\" \"$1\" \"$TITLE\"")), Map.of("topic", "the release"));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 1)).isEqualTo("1|" + HOSTILE + "|Report on the release: " + HOSTILE);
        CommandRequest request = requests.getFirst();
        assertThat(request.command()).as("the command as the workflow wrote it")
                .isEqualTo("set -- \"$REVIEW\"; printf '%s|%s|%s' \"$#\" \"$1\" \"$TITLE\"");
        assertThat(request.values()).containsOnly(Map.entry("REVIEW", HOSTILE),
                Map.entry("TITLE", "Report on the release: " + HOSTILE));
        assertThat(outcome.steps().get(1).getInput()).isEqualTo(request.command());
        assertThat(request.script()).as("the text bash parses: letters, digits and \\xHH where the values are")
                .endsWith("\nunset -f TRACEWORKS_VALUE\n" + request.command())
                .contains("\nTRACEWORKS_VALUE REVIEW " + HOSTILE.length() + " 'it\\x27s\\x20\\x24\\x28touch")
                .doesNotContain("$(touch", "`touch", "; touch", "it's");
        assertThat(request.script().lines().filter(line -> line.startsWith("TRACEWORKS_VALUE ")))
                .hasSize(2).allMatch(line -> line.matches("TRACEWORKS_VALUE [A-Z]+ \\d+ '(?:[A-Za-z0-9]|\\\\x[0-9a-f]{2})*'"));
        assertNothingOfTheValueRan("values");
    }

    /** The variables are the shell's own: a command the line starts does not find them in its environment. */
    @Test
    void theVariablesAreNotExported_andAreSetForEveryShellLineOfTheBlock() {
        claude.answers("the answer");

        RunOutcome outcome = run(workflow("wf", ask(),
                block("shell", "gated", Map.of("values", Map.of("ANSWER", "{{ block.ask.output }}"),
                        "run_if", "test \"$ANSWER\" = 'the answer'", "command", "env | grep -c ANSWER; echo \"$ANSWER\"")),
                block("foreach", "each", Map.of("execution_mode", "shell", "items_source", "command",
                        "values", Map.of("ANSWER", "{{ block.ask.output }}", "ITEM", "{{ item }}"),
                        "items_command", "printf '%s\\n' \"$ANSWER\" second",
                        "command", "printf '%s/%s' \"$ANSWER\" \"$ITEM\"")),
                block("conditional", "branch", Map.of("execution_mode", "shell",
                        "values", Map.of("ANSWER", "{{ block.ask.output }}"),
                        "condition", "test -n \"$ANSWER\"", "then_command", "echo then \"$ANSWER\"",
                        "else_command", "echo else"))));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 1).strip()).isEqualTo("0\nthe answer");
        assertThat(output(outcome, 2)).contains("the answer/the answer").contains("the answer/second");
        assertThat(output(outcome, 3)).contains("then the answer");
    }

    /** A name that is no variable name, or a reserved one, fails the step before anything runs; so does a tool step. */
    @Test
    void valuesThatAreNotValid_failTheStep_beforeAnythingRuns() {
        Map<Map<String, Object>, String> invalid = new LinkedHashMap<>();
        invalid.put(Map.of("values", Map.of("lower", "x"), "command", "echo"),
                "values: \"lower\" is not a variable name");
        invalid.put(Map.of("values", Map.of("PATH", "x"), "command", "echo"), "values: \"PATH\" is reserved");
        invalid.put(Map.of("values", Map.of("TRACEWORKS_X", "x"), "command", "echo"),
                "values: \"TRACEWORKS_X\" is reserved");
        invalid.put(Map.of("values", Map.of("IFS", "x"), "command", "echo"), "values: \"IFS\" is reserved");
        invalid.put(Map.of("values", Map.of("X", 5), "command", "echo"), "values: the value of \"X\" is not a text");
        invalid.put(Map.of("values", List.of("X"), "command", "echo"), "values is not an object");
        invalid.put(Map.of("values", Map.of("X", "x"), "container_image", "alpine:3", "container_args", List.of("a")),
                "values: a tool step (container_args) runs no shell");

        assertThat(ShellValues.problems(Map.of("values", Map.of("TOKEN", "x"), "secrets", List.of("TOKEN"))))
                .containsExactly("values: \"TOKEN\" is a secret of this block — a value needs a name of its own");
        invalid.forEach((config, message) -> {
            requests.clear();
            RunOutcome outcome = run(workflow("wf", block("shell", "use", config)));

            assertThat(outcome.status()).as(message).isEqualTo(RunStatus.FAILED);
            assertThat(outcome.error()).contains(message);
            assertThat(requests).as("nothing ran: %s", message).isEmpty();
        });
    }

    // -- the way that is closed: a protected value in a shell line ----------------------------------------------

    /** Every block that asks a model: its output refuses a shell line, wherever the placeholder stands. */
    @Test
    void theOutputOfEveryBlockTypeThatAsksAModel_refusesAShellLine() {
        Map<String, BlockDefinition> asking = new LinkedHashMap<>();
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
            requests.clear();
            claude.otherwise("GOAL_STATUS: ACHIEVED\nRATIONALE: x; touch pwned1\nACTION:\nSUMMARY: x; touch pwned1");

            RunOutcome outcome = run(workflow("wf-" + type, shell("first", "echo text"), block,
                    shell("use", "printf '%s' {{ block.ask.output }}")));

            assertRefused(type, outcome, "{{ block.ask.output }}");
            assertThat(commandLines()).as(type).containsExactly("echo text");
        });
    }

    /** Each kind of shell line, each way a placeholder names the value, with and without shell_escape_variables. */
    @Test
    void aModelsAnswer_refusesEveryKindOfShellLine_byEveryName_quotedOrNot() {
        for (String placeholder : List.of("{{ block.ask.output }}", "{{ step_0_output }}", "{{ previous_output }}")) {
            for (String line : List.of("echo X", "echo \"X\"", "echo 'X'", "true # X", "cat <<'EOF'\nX\nEOF",
                    "V=X; echo \"$V\"", "printf '%s' \"$(printf '%s' X)\"")) {
                for (boolean escape : new boolean[] {false, true}) {
                    String command = line.replace("X", placeholder);
                    Map<String, BlockDefinition> uses = new LinkedHashMap<>();
                    uses.put("command", block("shell", "use", Map.of("command", command,
                            "shell_escape_variables", escape)));
                    uses.put("run_if", block("shell", "use", Map.of("run_if", command, "command", "echo ran",
                            "shell_escape_variables", escape)));
                    uses.put("items_command", block("foreach", "use", Map.of("execution_mode", "shell",
                            "items_source", "command", "items_command", command, "command", "true",
                            "shell_escape_variables", escape)));
                    uses.put("condition", block("conditional", "use", Map.of("execution_mode", "shell",
                            "condition", command, "then_command", "echo then", "shell_escape_variables", escape)));
                    uses.put("then_command", block("conditional", "use", Map.of("execution_mode", "shell",
                            "condition", "true", "then_command", command, "shell_escape_variables", escape)));
                    uses.put("inner step", block("retry", "use", Map.of("execution_mode", "shell",
                            "command", command, "shell_escape_variables", escape)));
                    uses.forEach((kind, use) -> {
                        requests.clear();
                        claude.answers(HOSTILE);
                        String what = kind + ": " + command + (escape ? " (escaped)" : "");

                        assertRefused(what, run(workflow("wf", ask(), use)), placeholder);
                        assertThat(commandLines()).as(what).doesNotContain(command, "echo ran", "echo then");
                    });
                }
            }
        }
    }

    @Test
    void aLoopItemAndAnAiActorsAnswer_refuseAShellLine_andArriveAsValues() {
        claude.answers("a; touch pwned1", "a; touch pwned1", HOSTILE, HOSTILE);
        Map<String, Object> loop = Map.of("execution_mode", "shell", "items_source", "command",
                "values", Map.of("ANSWER", "{{ block.ask.output }}", "ITEM", "{{ item }}"),
                "items_command", "printf '%s\\n' \"$ANSWER\"");

        assertRefused("loop item", run(workflow("wf", ask(), block("foreach", "each",
                with(loop, "command", "echo \"{{ item }}\"")))), "{{ item }}");
        RunOutcome item = run(workflow("wf", ask(), block("foreach", "each",
                with(loop, "command", "printf '%s' \"$ITEM\""))));
        assertThat(output(item, 1)).contains("a; touch pwned1");

        BlockDefinition why = block("human-input", "why", Map.of("question", "Why?", "actor", "ai",
                "variable_name", "reason"));
        assertRefused("AI actor's answer", run(workflow("wf", why, shell("use", "echo {{ reason }}"))),
                "{{ reason }}");
        RunOutcome reason = run(workflow("wf", why, shell("use", Map.of("REASON", "{{ reason }}"),
                "printf '%s' \"$REASON\"")));
        assertThat(output(reason, 1)).isEqualTo(HOSTILE);
        assertNothingOfTheValueRan("loop item and actor's answer");
    }

    private static Map<String, Object> with(Map<String, Object> config, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(config);
        copy.put(key, value);
        return copy;
    }

    /**
     * A shell step can print what the model wrote, or read a file an agent wrote, without naming a placeholder: from
     * the first call on every output is protected. What a step recorded before the call is used as before.
     */
    @Test
    void outputsRecordedAfterTheCall_areProtected_andThoseRecordedBeforeItAreNot() {
        claude.answers("fine", "fine");
        BlockDefinition before = shell("before", "echo one two");
        BlockDefinition after = shell("after", "echo three four");

        RunOutcome earlier = run(workflow("wf", before, ask(), after,
                shell("count", "set -- {{ block.before.output }} {{ step_0_output }}; printf '%s' \"$#\"")));
        assertThat(output(earlier, 3)).isEqualTo("4");

        assertRefused("an output after the call", run(workflow("wf", before, ask(), after,
                shell("count", "set -- {{ block.after.output }}; printf '%s' \"$#\""))), "{{ block.after.output }}");
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
                        "items_command", "echo 'a b'", "command", "set -- {{ item }}; printf '%s' \"$#\"")),
                block("shell", "escaped", Map.of("shell_escape_variables", true,
                        "command", "echo {{ block.list.output }} \"{{ topic }}\" {{ unknown }}"))),
                Map.of("topic", "release notes"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(output(outcome, 1)).isEqualTo("3 3 3");
        assertThat(commandLines()).contains("set -- a b; printf '%s' \"$#\"",
                "echo 'one two three' \"'release notes'\" {{ unknown }}");
        assertThat(requests).allMatch(request -> request.values().isEmpty() && request.script().equals(request.command()));
    }

    /** A step whose call fails still asked a model; what it left behind is not known, so the rule is the same. */
    @Test
    void theOutputOfAFailedCall_isProtectedToo() {
        claude.fails(1, "$(touch pwned1)");

        RunOutcome outcome = run(workflow("wf",
                block("simple", "ask", Map.of("command", "Say something", "on_failure", "continue")),
                shell("use", "printf '%s' {{ block.ask.output }}")));

        assertRefused("failed call", outcome, "{{ block.ask.output }}");
        assertThat(requests).isEmpty();
    }

    @Test
    void theOutputOfAChildThatAskedAModel_isProtectedInTheParent_alsoTwoLevelsUp() {
        claude.answers(HOSTILE, HOSTILE, HOSTILE);
        workflows.add("child", workflow("child", ask()));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "child"))));
        workflows.add("plain", workflow("plain", shell("say", "echo one two")));

        RunOutcome plain = run(workflow("parent",
                block("sub-workflow", "plain", Map.of("workflow_id", "plain")),
                shell("count_plain", "set -- {{ block.plain.output }}; printf '%s' \"$#\"")));
        assertThat(output(plain, 1)).as("a child without a model: as before").isEqualTo("2");

        for (String child : List.of("child", "between")) {
            assertRefused(child, run(workflow("parent",
                    block("sub-workflow", "judged", Map.of("workflow_id", child)),
                    shell("use", "echo {{ block.judged.output }}"))), "{{ block.judged.output }}");
        }
        RunOutcome handed = run(workflow("parent",
                block("sub-workflow", "judged", Map.of("workflow_id", "between")),
                shell("use", Map.of("JUDGED", "{{ block.judged.output }}"), "printf '%s' \"$JUDGED\"")));
        assertThat(output(handed, 1)).isEqualTo(HOSTILE);
    }

    @Test
    void whatAParentHandsToAChildAfterItAskedAModel_isProtectedInTheChild_andInAGrandchild() {
        claude.answers(HOSTILE);
        workflows.add("child", workflow("child",
                shell("use", Map.of("HANDED", "{{ previous_output }}"), "printf '%s' \"$HANDED\"")));
        workflows.add("grandchild", workflow("grandchild", shell("use", "echo \"{{ step_0_output }}\"")));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "grandchild"))));

        RunOutcome outcome = run(workflow("parent", ask(),
                block("sub-workflow", "child", Map.of("workflow_id", "child", "on_failure", "continue")),
                block("sub-workflow", "between", Map.of("workflow_id", "between", "on_failure", "continue"))));

        assertThat(outcome.steps().get(1).getOutput()).isEqualTo(HOSTILE);
        assertThat(outcome.steps().get(2).getOutput()).contains("shell line refused, nothing ran: {{ step_0_output }}");
        assertNothingOfTheValueRan("child and grandchild");
    }

    /** Untrusted input and platform data were quoted in a shell line; they are protected the same way now. */
    @Test
    void untrustedInputAndPlatformData_refuseAShellLine_andArriveAsValues() {
        Map<String, Object> inputs = Map.of("ticket", HOSTILE, "previous_chain_output", HOSTILE,
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("ticket"));

        assertRefused("untrusted input", run(workflow("wf", shell("use", "echo {{ ticket }}")), inputs),
                "{{ ticket }}");
        assertRefused("platform data", run(workflow("wf", block("shell", "use", Map.of("shell_escape_variables", true,
                "command", "echo {{ previous_chain_output }}"))), inputs), "{{ previous_chain_output }}");
        RunOutcome handed = run(workflow("wf",
                shell("first", Map.of("TICKET", "{{ ticket }}", "CHAIN", "{{ previous_chain_output }}"),
                        "printf '%s|%s' \"$TICKET\" \"$CHAIN\""),
                shell("second", "echo {{ previous_output }}")), inputs);
        assertThat(handed.steps().getFirst().getOutput()).isEqualTo(HOSTILE + "|" + HOSTILE);
        assertThat(handed.error()).as("the output of a run that carries untrusted input")
                .contains("shell line refused, nothing ran: {{ previous_output }}");
        assertNothingOfTheValueRan("untrusted input");
    }

    // -- what the mark is, and is not ---------------------------------------------------------------------------

    /**
     * An auto_answer filled in from a placeholder is whatever that value was: after an AI step, model text. One the
     * workflow writes out is the author's text and is used as before.
     */
    @Test
    void anAutoAnswerFilledInFromAModelsAnswer_staysModelText_andOneWrittenOutIsTrusted() {
        claude.answers("yes; touch pwned1", "yes; touch pwned1");
        BlockDefinition taken = block("approval", "taken", Map.of("question", "Release?", "mode", "auto",
                "auto_answer", "{{ block.ask.output }}", "variable_name", "taken"));

        assertRefused("auto_answer", run(workflow("wf", ask(), taken, shell("use", "echo {{ taken }}"))),
                "{{ taken }}");

        RunOutcome written = run(workflow("wf", ask(),
                block("approval", "written", Map.of("question", "Release?", "mode", "auto", "auto_answer", "Yes",
                        "variable_name", "written")),
                shell("compare", "[ \"{{ written }}\" = Yes ] && echo released")));
        assertThat(output(written, 2).strip()).isEqualTo("released");
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
                        "values", Map.of("ANSWER", "{{ block.ask.output }}", "ITEM", "{{ item }}"),
                        "items_command", "printf '%s\\n' \"$ANSWER\"", "command", "echo \"$ITEM\"")),
                block("foreach", "written", Map.of("execution_mode", "shell", "items", List.of("a b"),
                        "command", "echo \"{{ item }}\""))));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines()).endsWith("echo \"a b\"");
    }

    /** Model text is protected; it does not make the run one with untrusted input, so an AI actor still decides. */
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

    /** A model's answer chooses neither a mount, nor an argument of a tool, nor a child workflow. */
    @Test
    void aModelsAnswer_choosesNeitherAMountNorAToolArgumentNorAChildWorkflow() {
        claude.answers("/etc", "child", "--config=/etc/x");
        workflows.add("child", workflow("child", shell("say", "echo child ran")));

        RunOutcome mount = run(workflow("wf", ask(), block("shell", "mounted", Map.of("command", "ls /data",
                "container_image", "alpine:3", "mounts", List.of("{{ block.ask.output }}:/data:ro")))));
        assertThat(mount.status()).isEqualTo(RunStatus.FAILED);
        assertThat(mount.error()).contains("mounts must not contain untrusted values");

        RunOutcome child = run(workflow("wf", ask(),
                block("sub-workflow", "chosen", Map.of("workflow_id", "{{ step_0_output }}"))));
        assertThat(child.steps().getLast().getOutput())
                .contains("workflow_id may only use trusted context variables, not {{ step_0_output }}");

        RunOutcome tool = run(workflow("wf", ask(), block("shell", "tool", Map.of("container_image", "alpine:3",
                "container_args", List.of("scan", "{{ block.ask.output }}")))));
        assertThat(tool.status()).isEqualTo(RunStatus.FAILED);
        assertThat(tool.error()).contains("container_args must not contain untrusted values (untrusted input,"
                + " tainted block output, platform data, text a model wrote): {{ block.ask.output }}");
        assertThat(requests).isEmpty();
    }
}
