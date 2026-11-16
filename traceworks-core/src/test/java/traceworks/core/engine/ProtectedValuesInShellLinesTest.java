package traceworks.core.engine;

import traceworks.core.testing.FakeCommandRunner;
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
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A value the run has to protect (text a model wrote, untrusted input) never becomes part of a shell line: the line
 * sets a shell variable to it and holds a reference in the placeholder's place, and the shell does not read what a
 * reference expands to as code. Runs through the engine with the real blocks and a real bash. Every hostile value
 * tries to create a file named {@code pwned*} in the working directory; no test may find one, whatever the line
 * looks like and whatever the reader of shell text makes of it. Where a reference cannot stand, the line is refused
 * before anything runs. The analyzer is not asked here.
 */
class ProtectedValuesInShellLinesTest {

    /** Defines {@code recv}, which writes the number of its arguments and its first argument to files. */
    private static final String RECV = "recv() { printf '%s' \"$#\" > argc; printf '%s' \"$1\" > arg1; }\n";

    @TempDir
    Path work;

    @TempDir
    Path scripts;

    private final List<String> commandLines = new CopyOnWriteArrayList<>();

    /**
     * Runs the command with a real bash in {@link #work}, from a file: as with the real runners, which hand it over
     * on stdin, its size is not bound by what one argument may hold.
     */
    private final CommandRunner commands = request -> {
        commandLines.add(request.command());
        try {
            Path script = Files.createTempFile(scripts, "line", ".sh");
            Files.writeString(script, request.command(), StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("/bin/bash", script.toString()).directory(work.toFile())
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandRunner.CommandResult(process.waitFor(), output, "", 0, false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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

    /** The inputs of a run: {@code x} from an untrusted source, {@code t} the workflow's own. */
    private static Map<String, Object> inputs(String x, String t) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("x", x);
        inputs.put("t", t);
        inputs.put(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("x"));
        return inputs;
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

    private String file(String name) {
        try {
            return Files.readString(work.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void clean() {
        commandLines.clear();
        try (Stream<Path> files = Files.list(work)) {
            for (Path path : files.toList()) Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    private void assertRefused(String what, RunOutcome outcome, String placeholder, String place) {
        assertThat(outcome.status()).as(what).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).as(what)
                .contains("shell line refused, nothing ran: " + placeholder + " stands " + place);
        assertNothingOfTheValueRan(what);
    }

    private static String describe(String text) {
        return text.length() > 60 ? text.substring(0, 60) + "… (" + text.length() + " chars)" : text;
    }

    // -- the table: every place a reference can stand, times every hostile value ----------------------------------

    private static final List<String> HOSTILE = List.of(
            "$(touch pwned1)",
            "`touch pwned2`",
            "'; touch pwned3; '",
            "\" ; touch pwned4; \"",
            "x\ntouch pwned5",
            "a b",
            "*",
            "-rf /",
            "",
            "tail\n\n",
            "\\",
            "it's \"both\" $HOME ${HOME} $((1+1)) !! #x",
            "EOF\ntouch pwned6\nEOF",
            "TRACEWORKS_VALUE_1_END\ntouch pwned7\nTRACEWORKS_VALUE_1_END_\ntouch pwned8",
            "abcdefghijklmnop\n".repeat(64 * 1024 / 17 * 16));   // about 1 MiB

    /** Where the receiving command gets the value as one argument, and what exactly it gets. */
    private static Map<String, UnaryOperator<String>> places() {
        Map<String, UnaryOperator<String>> places = new LinkedHashMap<>();
        UnaryOperator<String> asItIs = value -> value;
        UnaryOperator<String> withoutTrailingNewlines = value -> value.replaceFirst("\n+$", "");
        places.put("recv {{ x }}", asItIs);
        places.put("V={{ x }}; recv \"$V\"", asItIs);
        places.put("recv \"{{ x }}\"", asItIs);
        places.put("recv \"pre {{ x }} post\"", value -> "pre " + value + " post");
        places.put("recv \"${unset_variable:-\"{{ x }}\"}\"", asItIs);
        places.put("set_variable=1; recv \"${set_variable:+\"{{ x }}\"}\"", asItIs);
        places.put("recv \"$(printf '%s' {{ x }})\"", withoutTrailingNewlines);
        places.put("recv \"$(cat <<EOF\n{{ x }}\nEOF\n)\"", withoutTrailingNewlines);
        places.put("cat > arg1 <<EOF\n{{ x }}\nEOF\nprintf 1 > argc", value -> value + "\n");
        places.put("cat > arg1 <<-1\n\t{{ x }}\n\t1\nprintf 1 > argc", value -> value + "\n");
        places.put("recv x{{ x }}y", value -> "x" + value + "y");
        places.put("list=(\n  {{ x }}\n)\nrecv \"${list[@]}\"", asItIs);
        places.put("printf '%s' {{ x }} | (IFS= read -r -d '' v; recv \"$v\")", asItIs);
        places.put("recv {{ x }} # {{ x }}", asItIs);
        return places;
    }

    @Test
    void everyPlaceTimesEveryHostileValue_theCommandGetsTheValueByteForByte_andNothingOfItRuns() {
        places().forEach((place, expected) -> {
            for (String value : HOSTILE) {
                clean();
                String what = place + " with " + describe(value);

                RunOutcome outcome = run(workflow("wf", shell("use", RECV + place)), inputs(value, "t"));

                assertThat(outcome.status()).as("%s: %s", what, describe(String.valueOf(outcome.error())))
                        .isEqualTo(RunStatus.COMPLETED);
                assertNothingOfTheValueRan(what);
                assertThat(file("argc")).as("arguments: %s", what).isEqualTo("1");
                assertThat(file("arg1").equals(expected.apply(value))).as("the argument, byte for byte: %s", what)
                        .isTrue();
            }
        });
    }

    /** An argument after the code of {@code bash -c} is a positional parameter of that code: a value, not code. */
    @Test
    void anArgumentAfterTheCodeOfBashC_isAValue() {
        for (String value : HOSTILE.subList(0, HOSTILE.size() - 1)) {   // one argument cannot hold 1 MiB
            clean();
            RunOutcome outcome = run(workflow("wf", shell("use",
                    "bash -c 'printf %s \"$1\" > arg1; printf %s \"$#\" > argc' _ {{ x }}")), inputs(value, "t"));

            assertThat(outcome.status()).as(value).isEqualTo(RunStatus.COMPLETED);
            assertNothingOfTheValueRan(value);
            assertThat(file("argc") + "|" + file("arg1")).isEqualTo("1|" + value);
        }
    }

    /**
     * A trusted value is inserted as it is, so it can change how the shell reads what follows: a quote it opens, a
     * comment it starts, a backslash before the next word. The reference then expands in another place than the
     * reader of shell text saw, or not at all; a command it never becomes.
     */
    @Test
    void nextToATrustedValueThatChangesTheShellsReading_nothingOfTheValueRuns() {
        List<String> trusted = List.of("it's", "say \"hi", "issue #12", "C:\\", "line\nbreak", "$(", "`", "<<EOF");
        List<String> lines = List.of("echo {{ t }} {{ x }}", "echo {{ t }}{{ x }}", "echo \"{{ t }} {{ x }}\"",
                "echo {{ x }} {{ t }} {{ x }}");
        List<String> hostile = List.of("$(touch pwned1)'", "x\ntouch pwned2 #", "$(touch pwned3)\" '",
                "`touch pwned4`", "'; touch pwned5; '", "\n)\ntouch pwned6\n", "EOF\ntouch pwned7");
        for (String t : trusted) {
            for (String line : lines) {
                for (String value : hostile) {
                    clean();
                    run(workflow("wf", shell("use", line)), inputs(value, t));
                    assertNothingOfTheValueRan(line + " with t=" + t + " and x=" + value);
                }
            }
        }
    }

    // -- what a second review broke in the reader of shell text: none of it runs the value now --------------------

    @Test
    void theLinesAReaderOfShellTextGetsWrong_runNothingOfTheValue() {
        String value = "$(touch pwned1)\nEOF\ntouch pwned2\n1\n.E\nA\n.B\nE-F\ntouch pwned3 #\n`touch pwned4`";
        List<String> lines = List.of(
                "echo \"${u:-\"{{ x }}\"}\"",
                "v=1; echo \"${v:+\"{{ x }}\"}\"",
                "cat <<1\n{{ x }}\n1",
                "cat <<.E\n{{ x }}\n.E",
                "cat <<'E-F'\n{{ x }}\nE-F",
                "cat <<A <<.B\n{{ x }}\nA\n{{ x }}\n.B",
                "cat <<EOF\n{{ x }}\n EOF\nEOF",
                "cat <<-EOF\n{{ x }}\n EOF\nEOF",
                "(true)# {{ x }}",
                "echo {{ a'b }} {{ x }} {{ c'd }}",
                "echo {{ a\"b }} {{ x }} {{ c\"d }}",
                "echo $'\\'' x '{{ x }}'",
                "echo \"$(case a in a) echo \"{{ x }}\";; esac)\"",
                "echo \"$(echo case) {{ x }}\"",
                "echo {{ x }} \"open",
                "echo \\{{ x }}",
                "true # {{ x }}",
                "echo `echo {{ x }}`");
        for (String line : lines) {
            clean();
            run(workflow("wf", shell("use", line)), inputs(value, "t"));
            assertNothingOfTheValueRan(line);
        }
    }

    /** Where bash evaluates what a variable holds as arithmetic, or a name it is given: the line is refused. */
    @Test
    void wherTheShellEvaluatesTheContentOfAValue_theLineIsRefused() {
        String value = "a[$(touch pwned1)]";
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("[[ {{ x }} -eq 1 ]]", "in a [[ … ]] with an arithmetic comparison");
        lines.put("[[ 1 -lt {{ x }} ]]", "in a [[ … ]] with an arithmetic comparison");
        lines.put("a[{{ x }}]=1", "in an array subscript or $[ … ]");
        lines.put("echo ${a[{{ x }}]}", "in an array subscript or $[ … ]");
        lines.put("echo \"${a[{{ x }}]}\"", "in an array subscript or $[ … ]");
        lines.put("let v={{ x }}", "in an argument of let");
        lines.put("declare -i v={{ x }}", "in an argument of declare");
        lines.put("declare {{ x }}", "in an argument of declare");
        lines.put("declare -n r={{ x }}", "in an argument of declare");
        lines.put("local -i v={{ x }}", "in an argument of local");
        lines.put("export {{ x }}", "in an argument of export");
        lines.put("echo ${v:{{ x }}}", "in the offset of a ${name:offset}");
        lines.put("echo $[ {{ x }} ]", "in an array subscript or $[ … ]");
        lines.put("echo $(( {{ x }} ))", "inside an arithmetic expression");
        lines.put("echo $(( \"{{ x }}\" + 1 ))", "inside an arithmetic expression");
        lines.put("(( v = {{ x }} ))", "inside an arithmetic expression");
        lines.put("[[ -v {{ x }} ]]", "after [[ -v");
        lines.put("test -v {{ x }}", "after test -v");
        lines.put("[ -v {{ x }} ]", "after [ -v");
        lines.put("printf -v {{ x }} %s y", "after printf -v");
        lines.put("read {{ x }}", "in an argument of read");
        lines.put("unset {{ x }}", "in an argument of unset");
        lines.forEach((line, place) -> {
            clean();
            assertRefused(line, run(workflow("wf", shell("use", line)), inputs(value, "t")), "{{ x }}", place);
            assertThat(commandLines).as("nothing ran: %s", line).isEmpty();
        });
    }

    /** A command that runs its argument, and the place of the command's name: the line is refused. */
    @Test
    void whereACommandRunsItsArgument_orTheValueWouldBeTheCommand_theLineIsRefused() {
        String value = "touch pwned1";
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("eval {{ x }}", "in an argument of eval, which runs its argument as shell code");
        lines.put("eval \"echo {{ x }}\"", "in an argument of eval");
        lines.put("bash -c {{ x }}", "in the code argument of bash -c");
        lines.put("sh -c {{ x }}", "in the code argument of sh -c");
        lines.put("bash -lc \"echo {{ x }}\"", "in the code argument of bash -lc");
        lines.put("/bin/bash -c {{ x }}", "in the code argument of bash -c");
        lines.put("env A=1 sh -c {{ x }}", "in the code argument of sh -c");
        lines.put("python3 -c {{ x }}", "in the code argument of python3 -c");
        lines.put("trap {{ x }} EXIT", "in an argument of trap");
        lines.put("source {{ x }}", "in an argument of source");
        lines.put(". {{ x }}", "in an argument of .");
        lines.put("{{ x }}", "where the command's name stands");
        lines.put("\"{{ x }}\" --help", "where the command's name stands");
        lines.put("true && {{ x }}", "where the command's name stands");
        lines.put("if true; then {{ x }}; fi", "where the command's name stands");
        lines.put("A=1 {{ x }}", "where the command's name stands");
        lines.put("exec {{ x }}", "where the command's name stands");
        lines.put("echo \"$({{ x }})\"", "where the command's name stands");
        lines.put("bash <<EOF\n{{ x }}\nEOF", "in the body of a heredoc that bash reads as its program");
        lines.put("cat <<EOF | sh\n{{ x }}\nEOF", "in the body of a heredoc that sh reads as its program");
        lines.forEach((line, place) -> {
            clean();
            assertRefused(line, run(workflow("wf", shell("use", line)), inputs(value, "t")), "{{ x }}", place);
            assertThat(commandLines).as("nothing ran: %s", line).isEmpty();
        });
    }

    /** Where no reference expands: the line is refused, and says where the placeholder stands. */
    @Test
    void whereNoReferenceExpands_theLineIsRefused() {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("echo '{{ x }}'", "inside single quotes, where the shell expands no reference");
        lines.put("echo $'{{ x }}'", "inside single quotes, where the shell expands no reference");
        lines.put("cat <<'EOF'\n{{ x }}\nEOF", "in the body of a heredoc with a quoted delimiter (<<'EOF')");
        lines.put("cat <<\\EOF\n{{ x }}\nEOF", "in the body of a heredoc with a quoted delimiter (<<'EOF')");
        lines.put("echo \\{{ x }}", "after a backslash, where the shell expands no reference");
        lines.put("echo `echo {{ x }}`", "inside backticks, which the shell reads twice");
        lines.forEach((line, place) -> {
            clean();
            assertRefused(line, run(workflow("wf", shell("use", line)), inputs("$(touch pwned1)", "t")),
                    "{{ x }}", place);
        });
    }

    @Test
    void theRefusal_namesThePlaceholderThePlaceAndTheFix() {
        RunOutcome outcome = run(workflow("wf", shell("use", "echo '{{ x }}'")), inputs("v", "t"));

        StepResult step = outcome.steps().getLast();
        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).isEqualTo("ShellLineRefusedException: shell line refused, nothing ran: {{ x }}"
                + " stands inside single quotes, where the shell expands no reference. Its value is text a model"
                + " wrote or comes from outside the run; the run hands such a value to the shell as a variable and"
                + " writes a reference in the placeholder's place, so it never is shell code, and that needs a place"
                + " where the shell takes a value. Write the placeholder as a bare word or inside double quotes"
                + " (X={{ x }}, then use \"$X\"), or pass the value through a file or standard input (printf '%s\\n'"
                + " {{ x }} > file)");
        assertThat(commandLines).isEmpty();
    }

    // -- what runs, and what the record holds ---------------------------------------------------------------------

    /** The text the step runs is the whole of it: each reference, and before the line the value of each. */
    @Test
    void theStepRunsTheLineWithReferences_afterTheAssignmentsOfTheirValues() {
        claude.answers("it's $(touch pwned1)");

        RunOutcome outcome = run(workflow("wf", ask(), shell("note",
                "printf '%s\\n' {{ block.ask.output }} > \"note for {{ topic }} {{ block.ask.output }}.txt\""
                        + " # {{ previous_output }}")), Map.of("topic", "release"));

        String ran = FakeCommandRunner.value(1, "it's $(touch pwned1)")
                + FakeCommandRunner.value(2, "it's $(touch pwned1)")
                + "printf '%s\\n' \"${TRACEWORKS_VALUE_1}\" > \"note for release ${TRACEWORKS_VALUE_1}.txt\""
                + " # ${TRACEWORKS_VALUE_2}";
        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines).containsExactly(ran);
        assertThat(outcome.steps().getLast().getInput()).as("the step's input in the record").isEqualTo(
                commandLines.getFirst());
        assertThat(file("note for release it's $(touch pwned1).txt")).isEqualTo("it's $(touch pwned1)\n");
        assertNothingOfTheValueRan("the note");
    }

    /** A checked command runs under set -e: setting the variables does not end it. */
    @Test
    void aCheckedCommand_takesAProtectedValueToo() {
        RunOutcome outcome = run(workflow("wf", block("shell", "checked", Map.of("checked_command", true,
                "command", "printf '%s' {{ x }}"))), inputs("a; touch pwned1", "t"));

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines.getFirst()).startsWith("set -e -o pipefail\nIFS= read -r -d '' TRACEWORKS_VALUE_1 ");
        assertThat(outcome.steps().getFirst().getOutput()).contains("a; touch pwned1");
        assertNothingOfTheValueRan("checked command");
    }

    /** The variables are the shell's own: a command the line starts does not find them in its environment. */
    @Test
    void theVariablesAreNotExported() {
        RunOutcome outcome = run(workflow("wf", shell("use", "printf '%s' {{ x }} > /dev/null; env")),
                inputs("the value", "t"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps().getFirst().getOutput()).doesNotContain("TRACEWORKS_VALUE", "the value");
    }

    /** What nothing marks is inserted as before, inside quotes too: a run without AI and untrusted input. */
    @Test
    void aRunWithoutProtectedValues_runsTheLineAsItAlwaysDid() {
        RunOutcome outcome = run(workflow("wf", shell("say", "echo one two"),
                shell("use", "echo \"{{ block.say.output }}\" '{{ previous_output }}' {{ step_0_output }}"
                        + " \"{{ topic }}\" {{ unknown }}"),
                block("shell", "escaped", Map.of("shell_escape_variables", true,
                        "command", "echo {{ block.say.output }} {{ topic }}"))),
                Map.of("topic", "release notes"));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandLines).containsExactly("echo one two",
                "echo \"one two\" 'one two' one two \"release notes\" {{ unknown }}",
                "echo 'one two' 'release notes'");
    }

    /** With shell_escape_variables the other values are quoted in place as before; a protected one is a reference. */
    @Test
    void shellEscapeVariables_quotesTheOtherValuesInPlace_andRefersToAProtectedOne() {
        claude.answers("a; touch pwned1");

        RunOutcome outcome = run(workflow("wf", ask(), block("shell", "use", Map.of("shell_escape_variables", true,
                "command", "printf '%s|' {{ block.ask.output }} {{ topic }} \"{{ block.ask.output }}\""))),
                Map.of("topic", "release notes"));

        assertThat(commandLines).containsExactly(FakeCommandRunner.value(1, "a; touch pwned1") + ""
                + "printf '%s|' \"${TRACEWORKS_VALUE_1}\" 'release notes' \"${TRACEWORKS_VALUE_1}\"");
        assertThat(outcome.steps().getLast().getOutput()).isEqualTo("a; touch pwned1|release notes|a; touch pwned1|");
        assertNothingOfTheValueRan("escaped block");
    }

    // -- the paths the analyzer cannot see ------------------------------------------------------------------------

    private static final String ONE_LINE = "a'; touch pwned1; echo '\"; touch pwned2; echo \"$(touch pwned3)"
            + "`touch pwned4`";
    private static final String MANY_LINES = ONE_LINE + "\nEOF\ntouch pwned5\n: <<EOF";

    @Test
    void aLoopItemThatCameAfterAnAiStep_isOneValueInsideDoubleQuotes_andRefusedInsideSingleQuotes() {
        claude.answers(ONE_LINE, ONE_LINE);
        Map<String, Object> loop = Map.of("execution_mode", "shell", "items_source", "command",
                "items_command", "printf '%s\\n' {{ block.ask.output }}");

        RunOutcome quoted = run(workflow("wf", ask(), block("foreach", "each", with(loop, "command",
                "printf '%s' \"item: {{ item }}\""))));
        assertThat(quoted.status()).as(quoted.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(quoted.steps().getLast().getOutput()).contains("item: " + ONE_LINE);
        assertNothingOfTheValueRan("loop item in double quotes");

        assertRefused("loop item", run(workflow("wf", ask(), block("foreach", "each", with(loop, "command",
                "echo 'item: {{ item }}'")))), "{{ item }}", "inside single quotes");
    }

    private static Map<String, Object> with(Map<String, Object> config, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(config);
        copy.put(key, value);
        return copy;
    }

    @Test
    void theVariableAnAiActorsHumanInputSet_isProtected() {
        claude.answers(ONE_LINE, ONE_LINE);
        BlockDefinition why = block("human-input", "why", Map.of("question", "Why?", "actor", "ai",
                "variable_name", "reason"));

        RunOutcome quoted = run(workflow("wf", why, shell("use", "printf '%s' \"reason: {{ reason }}\"")));
        assertThat(quoted.steps().getLast().getOutput()).isEqualTo("reason: " + ONE_LINE);

        assertRefused("AI actor's answer", run(workflow("wf", why, shell("use", "eval {{ reason }}"))),
                "{{ reason }}", "in an argument of eval");
    }

    @Test
    void aStepIndexAfterAWrapper_andPreviousOutputInANestedBlock_areProtected() {
        claude.answers(MANY_LINES, MANY_LINES);

        RunOutcome byIndex = run(workflow("wf",
                block("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a", "b"),
                        "command", "true")),
                ask(),
                shell("use", "cat <<EOF\n{{ step_3_output }}\nEOF")));
        assertThat(byIndex.steps().get(1).getStepIndex()).as("the AI step's index, past the loop's items").isEqualTo(3);
        assertThat(byIndex.steps().getLast().getOutput().strip()).isEqualTo(MANY_LINES);
        assertNothingOfTheValueRan("step index in a heredoc");

        RunOutcome nested = run(workflow("wf", block("switch", "route", Map.of("expression", "a",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "name", "draft", "config", Map.of("command", "Draft")),
                        Map.of("type", "shell", "name", "show", "config",
                                Map.of("command", "bash -c \"{{ previous_output }}\"")))))))));
        assertRefused("nested previous_output", nested, "{{ previous_output }}", "in the code argument of bash -c");
    }

    /** The parent's check does not judge a child's shell lines: the child's run protects what it is handed. */
    @Test
    void whatAParentHandsToAChild_isProtectedInTheChild_andInAGrandchild() {
        claude.answers(MANY_LINES);
        workflows.add("child", workflow("child", shell("use", "printf '%s' \"{{ previous_output }}\"")));
        workflows.add("grandchild", workflow("grandchild", shell("use", "eval \"echo {{ step_0_output }}\"")));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "grandchild"))));

        RunOutcome outcome = run(workflow("parent", ask(),
                block("sub-workflow", "child", Map.of("workflow_id", "child", "on_failure", "continue")),
                block("sub-workflow", "between", Map.of("workflow_id", "between", "on_failure", "continue"))));

        assertThat(outcome.steps().get(1).getOutput()).isEqualTo(MANY_LINES);
        assertThat(outcome.steps().get(2).getOutput()).contains("shell line refused, nothing ran:"
                + " {{ step_0_output }} stands in an argument of eval");
        assertNothingOfTheValueRan("child and grandchild");
    }

    /** The output of a grandchild that asked a model is protected in the parent of its parent. */
    @Test
    void theOutputOfAGrandchildThatAskedAModel_isProtectedTwoLevelsUp() {
        claude.answers(MANY_LINES);
        workflows.add("grandchild", workflow("grandchild", ask()));
        workflows.add("between", workflow("between", block("sub-workflow", "down", Map.of("workflow_id", "grandchild"))));

        RunOutcome outcome = run(workflow("parent",
                block("sub-workflow", "between", Map.of("workflow_id", "between")),
                shell("bare", "printf '%s' {{ block.between.output }}"),
                shell("quoted", "echo '{{ block.between.output }}'")));

        assertThat(outcome.steps().get(1).getOutput()).isEqualTo(MANY_LINES);
        assertRefused("grandchild's answer", outcome, "{{ block.between.output }}", "inside single quotes");
    }

    /** A run_if test, an items_command, a condition and its branches are shell lines like any other. */
    @Test
    void runIfItemsCommandConditionAndBranches_protectAModelsAnswer() {
        claude.answers(MANY_LINES, MANY_LINES, MANY_LINES, MANY_LINES);

        RunOutcome gated = run(workflow("wf", ask(), block("shell", "gated",
                Map.of("run_if", "test \"{{ block.ask.output }}\" != x", "command", "echo ran"))));
        assertThat(gated.steps().getLast().getOutput().strip()).isEqualTo("ran");

        RunOutcome branch = run(workflow("wf", ask(), block("conditional", "branch", Map.of("execution_mode", "shell",
                "condition", "test -n \"{{ block.ask.output }}\"", "then_command",
                "printf '%s' {{ block.ask.output }}", "else_command", "echo else"))));
        assertThat(branch.steps().getLast().getOutput()).contains(MANY_LINES);
        assertNothingOfTheValueRan("run_if, condition, then_command");

        assertRefused("run_if", run(workflow("wf", ask(), block("shell", "gated",
                Map.of("run_if", "eval {{ block.ask.output }}", "command", "echo ran")))),
                "{{ block.ask.output }}", "in an argument of eval");
        assertRefused("items_command", run(workflow("wf", ask(), block("foreach", "each",
                Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "echo '{{ block.ask.output }}'", "command", "true")))),
                "{{ block.ask.output }}", "inside single quotes");
    }

    // -- the places that pass no shell ----------------------------------------------------------------------------

    /** No reference can stand in the host path of a mount or in a child's workflow_id: model text is refused there. */
    @Test
    void aModelsAnswer_choosesNeitherAMountNorAChildWorkflow() {
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
        assertThat(commandLines).isEmpty();
    }
}
