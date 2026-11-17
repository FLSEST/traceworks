package traceworks.cli.main;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.adapters.record.FileRunStore;
import traceworks.cli.RuntimeSource;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunStart;
import traceworks.core.testing.FakeAiCli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code traceworks show} names what ran inside a step (docs/cli.md, "show and watch"): under a step that started a
 * child run, the child run and its steps, indented, at every level of nesting and per item of a {@code foreach};
 * under a {@code switch}, the case that ran and its blocks. A step with many child runs lists those that did not
 * complete and counts the others, unless {@code --children} asks for each. A run without child runs and switches
 * prints what it always did.
 *
 * <p>Host runs, with no container runtime: what is tested is what {@code show} makes of the record.
 */
class ShowNestedStepsTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");
    private static final String ID = "[0-9a-f]{8}";

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Result exec(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work, null,
                RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    /** Writes the workflow {@code dir/workflow.json} with {@code blocks}, a JSON list without its brackets. */
    private Path workflow(String dir, String blocks) throws IOException {
        Path at = Files.createDirectories(temp.resolve(dir));
        Files.writeString(at.resolve("workflow.json"), "{\"id\":\"" + dir.replace('/', '-') + "\",\"name\":\"" + dir
                + "\",\"default_context\":{\"mode\":\"fast\"},\"blocks\":[" + blocks + "]}");
        return at;
    }

    /** Runs the package on the host and returns what {@code show <run> <more>} prints. */
    private String shown(Path pkg, String... more) {
        Result run = exec("run", pkg.toString(), "--allow-host-execution", "--no-input", "--quiet");
        Matcher id = RUN_ID.matcher(run.out());
        assertThat(id.find()).as(run.out() + run.err()).isTrue();
        List<String> args = new ArrayList<>(List.of("show", id.group(1)));
        args.addAll(List.of(more));
        Result show = exec(args.toArray(String[]::new));
        assertThat(show.code()).as(show.out() + show.err()).isZero();
        return show.out();
    }

    /** The lines of the section {@code Steps}, up to the next empty line. */
    private static List<String> steps(String shown) {
        List<String> lines = shown.lines().toList();
        int from = lines.indexOf("Steps");
        assertThat(from).as(shown).isNotNegative();
        List<String> section = new ArrayList<>();
        for (int i = from + 1; i < lines.size() && !lines.get(i).isEmpty(); i++) {
            section.add(lines.get(i));
        }
        return section;
    }

    /**
     * A view must neither die on one changed blob nor show it as sound: {@code show --outputs} shows a mark in place
     * of an output whose blob does not match its name, and goes on with the other steps.
     */
    @Test
    void aChangedOutputBlob_isMarkedInShow_andTheOtherStepsAreShown() throws IOException {
        Path pkg = workflow("marked", """
                {"type":"shell","name":"first","config":{"command":"echo unique-first-output"}},
                {"type":"shell","name":"second","config":{"command":"echo unique-second-output"}}""");
        Result run = exec("run", pkg.toString(), "--allow-host-execution", "--no-input", "--quiet");
        Matcher id = RUN_ID.matcher(run.out());
        assertThat(id.find()).as(run.out() + run.err()).isTrue();
        Path blob;
        try (Stream<Path> files = Files.walk(home.resolve("runs"))) {
            blob = files.filter(Files::isRegularFile).filter(file -> file.getParent().getFileName().toString()
                    .equals("blobs")).filter(file -> {
                        try {
                            return Files.readString(file).strip().equals("unique-first-output");
                        } catch (IOException e) {
                            return false;
                        }
                    }).findFirst().orElseThrow();
        }
        assertThat(blob.toFile().setWritable(true)).isTrue();   // a blob is stored read-only
        Files.writeString(blob, "forged-output\n");

        Result show = exec("show", id.group(1), "--outputs");

        assertThat(show.code()).as(show.out() + show.err()).isZero();
        assertThat(show.out()).contains("this blob does not match its name", blob.getFileName().toString(),
                "unique-second-output").doesNotContain("forged-output");
    }

    @Test
    void aChildRunsSteps_standUnderTheStepThatStartedIt_atEveryLevel() throws IOException {
        workflow("nested/mid/leaf", """
                {"type":"shell","name":"leaf_step","config":{"command":"echo leaf"}}""");
        workflow("nested/mid", """
                {"type":"shell","name":"mid_step","config":{"command":"echo mid"}},
                {"type":"sub-workflow","name":"deeper","config":{"workflow_id":"./leaf"}}""");
        Path pkg = workflow("nested", """
                {"type":"shell","name":"first","config":{"command":"echo first"}},
                {"type":"sub-workflow","name":"outer","config":{"workflow_id":"./mid"}},
                {"type":"shell","name":"last","config":{"command":"echo last"}}""");

        String shown = shown(pkg);

        List<String> steps = steps(shown);
        assertThat(steps).as(shown).hasSize(8);
        assertThat(steps.get(0)).matches("  0   first                    \\[shell\\]          COMPLETED         \\d+ ms");
        assertThat(steps.get(1)).matches("  1   outer                    \\[sub-workflow\\]   COMPLETED         \\d+ ms");
        assertThat(steps.get(2)).matches("      child run " + ID + " COMPLETED");
        assertThat(steps.get(3))
                .matches("        0   mid_step                 \\[shell\\]          COMPLETED         \\d+ ms");
        assertThat(steps.get(4))
                .matches("        1   deeper                   \\[sub-workflow\\]   COMPLETED         \\d+ ms");
        assertThat(steps.get(5)).matches("            child run " + ID + " COMPLETED");
        assertThat(steps.get(6))
                .matches("              0   leaf_step                \\[shell\\]          COMPLETED         \\d+ ms");
        assertThat(steps.get(7)).matches("  2   last                     \\[shell\\]          COMPLETED         \\d+ ms");
        // The outputs of a child's steps come with --outputs, indented like their step
        assertThat(shown(pkg, "--outputs")).contains("              | leaf", "        | mid", "      | first");
    }

    @Test
    void aForeachOfChildren_namesEachItemsRun_andAChildsGateAndFailedStep() throws IOException {
        workflow("items/item", """
                {"type":"shell","name":"work","config":{"command":"test {{ what }} != bad && echo did {{ what }}"}}""");
        workflow("items/red", """
                {"type":"shell","name":"build","config":{"command":"exit 2","checked_command":true,
                 "check_category":"build","on_failure":"continue"}}""");
        Path pkg = workflow("items", """
                {"type":"foreach","name":"each","config":{"workflow_id":"./item","items":["ok","bad"],
                 "variable_name":"thing","context":{"what":"{{ thing }}"},"on_failure":"continue"}},
                {"type":"sub-workflow","name":"checked","config":{"workflow_id":"./red"}}""");

        List<String> steps = steps(shown(pkg));

        assertThat(steps).hasSize(8);
        assertThat(steps.get(0)).matches("  0   each                     \\[foreach\\]        FAILED            \\d+ ms");
        assertThat(steps.get(1)).matches("      item 1/2  child run " + ID + " COMPLETED");
        assertThat(steps.get(2)).matches("        0   work                     \\[shell\\]          COMPLETED  .*");
        assertThat(steps.get(3)).matches("      item 2/2  child run " + ID + " FAILED");
        assertThat(steps.get(4)).matches("        0   work                     \\[shell\\]          FAILED  .*");
        assertThat(steps.get(5)).matches("  \\d   checked                  \\[sub-workflow\\]   COMPLETED         \\d+ ms");
        assertThat(steps.get(6)).matches("      child run " + ID + " COMPLETED_WITH_ERRORS  gate RED \\(build\\)");
        assertThat(steps.get(7)).matches("        0   build                    \\[shell\\]          FAILED  .*");
    }

    @Test
    void aSwitch_namesTheCaseThatRan_andItsBlocks() throws IOException {
        Path pkg = workflow("switched", """
                {"type":"switch","name":"route","config":{"expression":"{{ mode }}","cases":[
                  {"value":"fast|quick","blocks":[
                    {"type":"shell","name":"case_fast","config":{"command":"echo fast"}},
                    {"type":"shell","config":{"command":"echo more"}}]}],
                  "default":[{"type":"shell","name":"case_default","config":{"command":"echo d"}}]}},
                {"type":"switch","name":"other","config":{"expression":"x{{ mode }}","cases":[
                  {"value":"fast","blocks":[{"type":"shell","name":"never_a","config":{"command":"echo n"}}]}],
                  "default":[{"type":"shell","name":"other_default","config":{"command":"echo d"}}]}},
                {"type":"switch","name":"nothing","config":{"expression":"y","cases":[
                  {"value":"fast","blocks":[{"type":"shell","name":"never_b","config":{"command":"echo n"}}]}]}}""");

        List<String> steps = steps(shown(pkg));

        assertThat(steps).hasSize(6);
        assertThat(steps.get(0)).startsWith("  0   route                    [switch]");
        assertThat(steps.get(1)).isEqualTo("      case fast|quick: case_fast [shell], (unnamed) [shell]");
        assertThat(steps.get(2)).contains("other", "[switch]");
        assertThat(steps.get(3)).isEqualTo("      case default: other_default [shell]");
        assertThat(steps.get(4)).contains("nothing", "[switch]");
        assertThat(steps.get(5)).isEqualTo("      no case matched");
    }

    /** A step with more child runs than fit: those that did not complete in full, the others counted. */
    @Test
    void manyChildRuns_areCounted_andTheOnesThatDidNotCompleteAreListed() throws IOException {
        workflow("many/item", """
                {"type":"shell","name":"work","config":{"command":"test {{ what }} != i7 && echo did {{ what }}"}}""");
        Path pkg = workflow("many", """
                {"type":"foreach","name":"each","config":{"workflow_id":"./item","on_failure":"continue",
                 "items":["i1","i2","i3","i4","i5","i6","i7","i8","i9","i10","i11","i12","i13","i14"],
                 "variable_name":"thing","context":{"what":"{{ thing }}"}}}""");

        List<String> steps = steps(shown(pkg));

        assertThat(steps).hasSize(4);
        assertThat(steps.get(1)).matches("      item 7/14  child run " + ID + " FAILED");
        assertThat(steps.get(2)).matches("        0   work                     \\[shell\\]          FAILED  .*");
        assertThat(steps.get(3)).isEqualTo("      13 more child runs, all COMPLETED (show --children lists each)");

        List<String> all = steps(shown(pkg, "--children"));
        assertThat(all).hasSize(1 + 14 * 2);
        assertThat(all.get(1)).matches("      item 1/14  child run " + ID + " COMPLETED");
        assertThat(all).noneMatch(line -> line.contains("more child runs"));
    }

    /** A run without child runs and switches prints its steps as before: one line each, nothing under them. */
    @Test
    void aRunWithoutChildRunsAndSwitches_printsItsStepsAsBefore() throws IOException {
        Path pkg = workflow("plain", """
                {"type":"shell","name":"one","config":{"command":"echo one"}},
                {"type":"foreach","name":"loop","config":{"execution_mode":"shell","items":["a","b"],
                 "command":"echo {{ item }}"}},
                {"type":"shell","name":"two","config":{"command":"echo two"}}""");

        String shown = shown(pkg);

        List<String> steps = steps(shown);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).matches("  0   one                      \\[shell\\]          COMPLETED         \\d+ ms");
        assertThat(steps.get(1)).matches("  1   loop                     \\[foreach\\]        COMPLETED         \\d+ ms");
        assertThat(steps.get(2)).matches("  \\d   two                      \\[shell\\]          COMPLETED         \\d+ ms");
        assertThat(shown).doesNotContain("child run", "Child ", "case ");
    }

    /** A case whose value is {@code none} is a case that ran, not "no case matched". */
    @Test
    void aCaseCalledNone_isNamedAsACase() throws IOException {
        Path pkg = workflow("none", """
                {"type":"switch","name":"route","config":{"expression":"none","cases":[
                  {"value":"none","blocks":[{"type":"shell","name":"on_none","config":{"command":"echo n"}}]}]}}""");

        List<String> steps = steps(shown(pkg));

        assertThat(steps).hasSize(2);
        assertThat(steps.get(1)).isEqualTo("      case none: on_none [shell]");
    }

    private static final String WORKFLOW = """
            {"id":"wf","name":"Fixed","description":"A fixed record.","blocks":[
              {"type":"shell","name":"build","config":{"command":"make"}},
              {"type":"switch","name":"route","config":{"expression":"x","cases":[
                {"value":"none","blocks":[{"type":"shell","name":"on_none","config":{"command":"echo n"}}]}]}},
              {"type":"sub-workflow","name":"outer","config":{"workflow_id":"./child"}}]}""";

    /** What a test writes into a record with the real recorder, as a run would. */
    private interface Recorded {
        void into(RunRecorder recorder) throws IOException;
    }

    /**
     * Writes a finished run with a fixed id, fixed times and the events of {@code steps}, with the real recorder, so
     * what {@code show} prints of it is the same bytes every time.
     */
    private String fixedRun(String id, Recorded steps) throws IOException {
        WorkflowDefinition definition = new WorkflowDefinition("wf", "Fixed", null, List.of(), null, null, null, null,
                null, null, null, null, null);
        Clock clock = () -> Instant.parse("2026-11-04T10:00:00Z");
        try (StoredRun run = new FileRunStore(home.resolve("runs")).create(id, clock.now())) {
            RunRecorder recorder = RunRecorder.start(run.events(), run.blobs(), clock, id, definition);
            recorder.runStarted(new RunStart("wf", "Fixed", "/pkg", "d", null, null,
                    WORKFLOW.getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "tester", "0.1.0",
                    "container", null, null));
            steps.into(recorder);
            recorder.emit(new RunEvent.RunFinished(RunStatus.COMPLETED, Map.of(), null));
        }
        Result show = exec("show", id);
        assertThat(show.code()).as(show.out() + show.err()).isZero();
        return show.out();
    }

    private static void command(RunRecorder recorder, String command) {
        recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(new CommandRequest(command, "/ws", 60, null, null, null, false));
    }

    /**
     * Every line under a nested step stands as far in as its step: the line of an AI call too, which once came
     * out at the column of a top-level step's, whatever the depth.
     */
    @Test
    void theAiCallOfAChildsStep_standsUnderItsStep() throws IOException {
        String child = "c0000000-0000-0000-0000-000000000001";
        String grandchild = "d0000000-0000-0000-0000-000000000002";

        String shown = fixedRun("00000000-0000-0000-0000-0000000000b1", recorder -> {
            recorder.emit(new RunEvent.StepStarted(0, "outer", "sub-workflow", 2, true));
            recorder.emit(new ChildRunEvent(child, new RunEvent.StepStarted(0, "deeper", "sub-workflow", 0, true)));
            recorder.emit(new ChildRunEvent(child, new ChildRunEvent(grandchild,
                    new RunEvent.StepStarted(0, "ask", "simple", 0, true))));
            AiProviders providers = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(
                    FakeAiCli.claude().answers("fine"),
                    recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:abc",
                    Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
            providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("Review", "opus", null, null, null,
                    null, ToolPolicy.READ, false, null));
            recorder.emit(new ChildRunEvent(child, new ChildRunEvent(grandchild, new RunEvent.StepFinished(0, "ask",
                    "simple", StepStatus.COMPLETED, "Review", 5, Map.of()))));
            recorder.emit(new ChildRunEvent(child, new ChildRunEvent(grandchild,
                    new RunEvent.RunFinished(RunStatus.COMPLETED, Map.of(), null))));
            recorder.emit(new ChildRunEvent(child, new RunEvent.StepFinished(0, "deeper", "sub-workflow",
                    StepStatus.COMPLETED, "", 7, Map.of())));
            recorder.emit(new ChildRunEvent(child, new RunEvent.RunFinished(RunStatus.COMPLETED, Map.of(), null)));
            recorder.emit(new RunEvent.StepFinished(0, "outer", "sub-workflow", StepStatus.COMPLETED, "", 9, Map.of()));
        });

        assertThat(String.join("\n", steps(shown))).as(shown).isEqualTo("""
                  0   outer                    [sub-workflow, ai] COMPLETED         9 ms
                      child run c0000000 COMPLETED
                        0   deeper                   [sub-workflow, ai] COMPLETED         7 ms
                            child run d0000000 COMPLETED
                              0   ask                      [simple, ai]     COMPLETED         5 ms
                                  ai CLAUDE  model opus  tools read  exit 0  100 in / 20 out tokens  0 tool calls
                                  network hosts api.claude.example:443"""
                .indent(2).stripTrailing());
    }

    /**
     * A record of the version before has no {@code caseMatched}: its {@code matchedCase: none} is "no case matched"
     * unless the step ran a command, which only a case does.
     */
    @Test
    void anEarlierRecordsNone_isNoCase_unlessTheStepRanACommand() throws IOException {
        String noCase = fixedRun("00000000-0000-0000-0000-0000000000b2", recorder -> {
            recorder.emit(new RunEvent.StepStarted(1, "route", "switch", 1, false));
            recorder.emit(new RunEvent.StepFinished(1, "route", "switch", StepStatus.COMPLETED, "switch (x)", 3,
                    Map.of("matchedCase", "none")));
        });
        String aCase = fixedRun("00000000-0000-0000-0000-0000000000b3", recorder -> {
            recorder.emit(new RunEvent.StepStarted(1, "route", "switch", 1, false));
            command(recorder, "echo n");
            recorder.emit(new RunEvent.StepFinished(1, "route", "switch", StepStatus.COMPLETED, "switch (none)", 3,
                    Map.of("matchedCase", "none")));
        });

        assertThat(steps(noCase)).last().isEqualTo("      no case matched");
        assertThat(steps(aCase)).last().isEqualTo("      case none: on_none [shell]");
    }

    /**
     * The whole of {@code show} for a run without child runs and switches, byte for byte: what it printed before it
     * learnt to name what ran inside a step. A change to any line of it shows here.
     */
    @Test
    void aRunWithoutChildRunsAndSwitches_isShownByteForByteAsBefore() throws IOException {
        String shown = fixedRun("00000000-0000-0000-0000-0000000000b4", recorder -> {
            recorder.emit(new RunEvent.StepStarted(0, "build", "shell", 0, false));
            command(recorder, "make");
            recorder.emit(new RunEvent.OutputRecorded(0, "build", "built"));
            recorder.emit(new RunEvent.StepFinished(0, "build", "shell", StepStatus.COMPLETED, "make", 12, Map.of()));
        });

        assertThat(shown).isEqualTo(PLAIN);
    }

    private static final String PLAIN = """
            Run       00000000-0000-0000-0000-0000000000b4
            Workflow  Fixed (wf)
            Status    COMPLETED
            Actor     tester
            Started   2026-11-04T10:00:00Z
            Finished  2026-11-04T10:00:00Z
            Package   /pkg
            Seal      unsealed
            Ledger    start noted in the ledger: no (recorded by a version that noted no start)
            AI net    restricted

            Workflow
              A fixed record.
              0   build                    shell                              step 0 COMPLETED
                  make
              1   route                    switch                             did not run
              2   outer                    sub-workflow                       did not run
                  ./child

            Steps
              0   build                    [shell]          COMPLETED         12 ms
            """;

    /**
     * A command of a child's step whose output was cut is marked under that step, as far in as the step, and the
     * line {@code Output} counts it without saying that it is marked nowhere: it is.
     */
    @Test
    void aCutOutputOfAChildsStep_isMarkedUnderItsStep_andTheOutputLineAgrees() throws IOException {
        String child = "c0000000-0000-0000-0000-000000000001";

        String shown = fixedRun("00000000-0000-0000-0000-0000000000b5", recorder -> {
            recorder.emit(new RunEvent.StepStarted(0, "outer", "sub-workflow", 2, false));
            recorder.emit(new ChildRunEvent(child, new RunEvent.StepStarted(0, "work", "shell", 0, false)));
            recorder.recording(request -> new CommandResult(0, "", "", 1, false)
                            .withOutputCut(new traceworks.core.port.CommandRunner.OutputCut(List.of("stdout"), 5000)))
                    .run(new CommandRequest("make", "/ws", 60, null, null, null, false));
            recorder.emit(new ChildRunEvent(child, new RunEvent.StepFinished(0, "work", "shell",
                    StepStatus.COMPLETED, "make", 5, Map.of())));
            recorder.emit(new ChildRunEvent(child, new RunEvent.RunFinished(RunStatus.COMPLETED, Map.of(), null)));
            recorder.emit(new RunEvent.StepFinished(0, "outer", "sub-workflow", StepStatus.COMPLETED, "", 9, Map.of()));
        });

        assertThat(shown).contains("Output    1 command whose output was CUT before the end of its stream: it may"
                + " be incomplete\n");
        assertThat(String.join("\n", steps(shown))).as(shown).isEqualTo("""
                  0   outer                    [sub-workflow]   COMPLETED         9 ms
                      child run c0000000 COMPLETED
                        0   work                     [shell]          COMPLETED         5 ms
                            output CUT     stdout after 5000 ms   <== may be incomplete"""
                .indent(2).stripTrailing());
    }
}
