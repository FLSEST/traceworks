package traceworks.cli.main;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.cli.RuntimeSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
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
}
