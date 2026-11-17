package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * {@code traceworks run} checks the package before it starts a run (docs/cli.md "run"): an error of {@code check}
 * refuses the run with exit 6 and its findings, a warning is printed once before the start line, {@code --no-check}
 * skips the check, and an input no block reads is named. Host runs of real shell steps in a temporary home.
 */
class RunCheckTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    /** @param all what the command printed, standard output and standard error in the order they were written */
    private record Result(int code, String out, String err, String all) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Path pkg(String name, String defaultContext, String blocks) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"" + name + "\",\"name\":\"" + name
                + " flow\",\"default_context\":" + defaultContext + ",\"blocks\":[" + blocks + "]}");
        return dir;
    }

    /** The package of the evidence: two blocks named {@code y}, the second with four placeholders that never resolve. */
    private Path typos() throws IOException {
        return pkg("typos", "{}", """
                {"type":"shell","name":"y","config":{"command":"echo one"}},
                {"type":"shell","name":"y","config":{"command":"echo {{ block.x.output }} {{ block.y.outptu }} {{ nme }} {{ step_7_output }}"}}""");
    }

    private Path greeting() throws IOException {
        return pkg("greeting", "{\"name\":\"world\"}", """
                {"type":"shell","name":"greet","config":{"command":"echo Hello, {{ name }}"}}""");
    }

    private Result exec(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(new Tee(out, both), true, StandardCharsets.UTF_8),
                new PrintStream(new Tee(err, both), true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"),
                work, null, RuntimeSource.NONE);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
                both.toString(StandardCharsets.UTF_8));
    }

    /** Writes to two streams, so that a test sees a stream on its own and both in the order of their writes. */
    private static final class Tee extends java.io.OutputStream {
        private final ByteArrayOutputStream own;
        private final ByteArrayOutputStream shared;

        Tee(ByteArrayOutputStream own, ByteArrayOutputStream shared) {
            this.own = own;
            this.shared = shared;
        }

        @Override
        public void write(int b) {
            own.write(b);
            synchronized (shared) {
                shared.write(b);
            }
        }

        @Override
        public void write(byte[] bytes, int off, int len) {
            own.write(bytes, off, len);
            synchronized (shared) {
                shared.write(bytes, off, len);
            }
        }
    }

    private Result run(Path pkg, String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--allow-host-execution", "--no-input"));
        args.addAll(List.of(more));
        return exec(args.toArray(String[]::new));
    }

    private static String idOf(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.all()).isTrue();
        return m.group(1);
    }

    private boolean noRunExists() {
        return !Files.exists(home.resolve("runs"));
    }

    // ---- the refusal ----

    @Test
    void run_ofAPackageWithAnError_exits6_printsTheFindingsAsCheckDoes_andCreatesNoRun() throws IOException {
        Path pkg = typos();
        Result check = exec("check", pkg.toString());

        Result run = run(pkg);

        assertThat(check.code()).isEqualTo(6);
        assertThat(run.code()).as(run.all()).isEqualTo(6);
        assertThat(run.out()).as("nothing started").isEmpty();
        List<String> errorsAndWarnings = check.out().lines()
                .filter(line -> line.startsWith("ERROR") || line.startsWith("WARNING")).toList();
        assertThat(errorsAndWarnings).hasSizeGreaterThanOrEqualTo(5);
        assertThat(run.err().lines().toList()).as("the lines of check, without its INFO lines")
                .containsSubsequence(errorsAndWarnings)
                .noneMatch(line -> line.startsWith("INFO"));
        assertThat(run.err())
                .containsPattern("(?m)^ERROR   unknown-block-reference      y \\(block 1\\): ")
                .containsPattern("(?m)^ERROR   unknown-block-property       y \\(block 1\\): ")
                .containsPattern("(?m)^ERROR   step-output-out-of-range     y \\(block 1\\): ")
                .containsPattern("(?m)^ERROR   duplicate-block-name         y \\(block 1\\): ")
                .containsPattern("(?m)^WARNING unknown-variable             y \\(block 1\\): \\{\\{ nme }}")
                .endsWith("traceworks: the run is refused: check reports 4 errors; fix them, or start the run with"
                        + " --no-check" + System.lineSeparator());
        assertThat(noRunExists()).as("no run was created").isTrue();
    }

    @Test
    void run_ofAWorkflowWithoutBlocks_exits6_withOneError() throws IOException {
        Result run = run(pkg("nothing", "{}", ""));

        assertThat(run.code()).as(run.all()).isEqualTo(6);
        assertThat(run.err()).containsPattern("(?m)^ERROR   empty-workflow +the workflow has no blocks")
                .contains("check reports 1 error;");
        assertThat(noRunExists()).isTrue();
    }

    /**
     * A place besides a shell line that takes no step output ({@code protected-value-outside-shell}): the check
     * refuses the run before it exists, and without the check the run itself fails the step, as it always did.
     */
    @Test
    void run_ofAWorkflowIdThatNamesAStepOutput_isRefusedByTheCheck_andWithoutItByTheRun() throws IOException {
        Path pkg = pkg("picked", "{}", """
                {"type":"shell","name":"pick","config":{"command":"set -euo pipefail\\necho child"}},
                {"type":"sub-workflow","name":"start","config":{"workflow_id":"./{{ block.pick.output }}"}}""");

        Result run = run(pkg);

        assertThat(run.code()).as(run.all()).isEqualTo(6);
        assertThat(run.out()).as("nothing started").isEmpty();
        assertThat(run.err())
                .containsPattern("(?m)^ERROR   protected-value-outside-shell start \\(block 1\\): names"
                        + " \\{\\{ block\\.pick\\.output }} in workflow_id, a step output")
                .contains("check reports 1 error;");
        assertThat(noRunExists()).as("no run was created").isTrue();

        Result unchecked = run(pkg, "--no-check");

        assertThat(unchecked.code()).as(unchecked.all()).isEqualTo(3);
        assertThat(unchecked.all()).contains("workflow_id may only use trusted context variables, not"
                + " {{ block.pick.output }}");
    }

    /**
     * Outside text is protected in the runs that get it: the check before a run knows the inputs, so it refuses the
     * run that hands in {@code previous_chain_output}, and lets the run without it start, where the name is text.
     */
    @Test
    void run_withChainOutputInAWorkflowId_isRefusedOnlyWhenTheRunGetsThatInput() throws IOException {
        Path pkg = pkg("chained", "{}", """
                {"type":"sub-workflow","name":"start","config":{"workflow_id":"./{{ previous_chain_output }}"}}""");

        Result handedIn = run(pkg, "--input", "previous_chain_output=child");

        assertThat(handedIn.code()).as(handedIn.all()).isEqualTo(6);
        assertThat(handedIn.err())
                .containsPattern("(?m)^ERROR   protected-value-outside-shell start \\(block 0\\): names"
                        + " \\{\\{ previous_chain_output }} in workflow_id, text from outside the run")
                .contains("check reports 1 error;");
        assertThat(noRunExists()).as("no run was created").isTrue();

        Result alone = run(pkg);

        assertThat(alone.err()).as(alone.all()).doesNotContain("protected-value-outside-shell", "ERROR ");
        assertThat(alone.out()).as("the run started").contains("run started: ");
    }

    @Test
    void run_noCheck_startsTheRun_andPrintsNoFinding() throws IOException {
        Result run = run(typos(), "--no-check");

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.out()).contains("run finished: COMPLETED",
                "output: {{ block.x.output }} {{ block.y.outptu }} {{ nme }} {{ step_7_output }}");
        assertThat(run.err()).doesNotContain("ERROR", "WARNING", "refused");
    }

    /** A mistake in the command line is reported before the package is looked at. */
    @Test
    void run_aUsageError_comesBeforeTheCheck() throws IOException {
        Result run = run(typos(), "--fail-on-gate", "purple");

        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).doesNotContain("ERROR  ");
    }

    @Test
    void run_withASource_isRefusedBeforeTheSourceIsCheckedOut() throws IOException {
        Path source = Files.createDirectories(temp.resolve("no-repository"));

        Result run = exec("run", typos().toString(), "--allow-host-execution", "--no-input", "--source",
                source.toString());

        assertThat(run.code()).as(run.all()).isEqualTo(6);
        assertThat(run.err()).contains("check reports 4 errors");
        assertThat(Files.exists(home.resolve("workspaces"))).as("nothing was cloned").isFalse();
        assertThat(noRunExists()).isTrue();
    }

    @Test
    void run_withInputsFrom_isCheckedLikeAnyRun_andKnowsTheInputsItTakes() throws IOException {
        Path typos = typos();
        String unchecked = idOf(run(typos, "--no-check", "--input", "nme=Anna"));

        Result refused = run(typos, "--inputs-from", unchecked);

        assertThat(refused.code()).as(refused.all()).isEqualTo(6);
        assertThat(refused.err()).contains("check reports 4 errors")
                .as("nme has a value: the earlier run's input").doesNotContain("unknown-variable");
    }

    // ---- warnings ----

    @Test
    void run_withAWarning_printsItOnceBeforeTheStartLine_andRuns() throws IOException {
        Path pkg = pkg("warned", "{\"name\":\"world\"}", """
                {"type":"shell","name":"greet","config":{"command":"echo Hello, {{ nme }}"}}""");

        Result run = run(pkg);

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.err()).containsPattern("(?m)^WARNING unknown-variable             greet \\(block 0\\): "
                        + "\\{\\{ nme }} — nothing sets it \\(did you mean \\{\\{ name }} for \\{\\{ nme }}\\?\\)")
                .as("no INFO line, no refusal").doesNotContain("INFO", "refused", "missing-strict-mode");
        assertThat(run.all().split("unknown-variable", -1)).as("printed once").hasSize(2);
        assertThat(run.all().indexOf("WARNING unknown-variable")).isLessThan(run.all().indexOf("run started: "));
        assertThat(run.out()).contains("output: Hello, {{ nme }}", "run finished: COMPLETED");
    }

    @Test
    void run_quiet_stillPrintsTheWarning() throws IOException {
        Path pkg = pkg("warned", "{}", """
                {"type":"shell","name":"greet","config":{"command":"echo Hello, {{ nme }}"}}""");

        Result run = run(pkg, "--quiet");

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.err()).contains("WARNING unknown-variable");
    }

    /** Under --quiet no start line says where the run runs, so the summary names it. */
    @Test
    void run_quiet_theSummaryNamesWhereTheRunRan() throws IOException {
        Path pkg = pkg("plain", "{}", """
                {"type":"shell","name":"greet","config":{"command":"echo Hello"}}""");

        Result run = run(pkg, "--quiet");

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.out()).doesNotContain("run started: ").containsPattern("(?m)^  ran     on the host$");
    }

    @Test
    void run_withTheInputTheWorkflowNames_printsNoWarning() throws IOException {
        Path pkg = pkg("asks", "{}", """
                {"type":"shell","name":"greet","config":{"command":"echo Hello, {{ who }}"}}""");

        Result run = run(pkg, "--input", "who=Anna");

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(run.out()).contains("output: Hello, Anna");
        assertThat(exec("check", pkg.toString()).out()).as("on its own, the package names a variable nothing sets")
                .contains("WARNING unknown-variable");
    }

    @Test
    void run_withACleanPackage_printsNothingOnStandardError() throws IOException {
        Result run = run(greeting());

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.err()).isEmpty();
    }

    // ---- inputs no block reads ----

    @Test
    void run_withAnInputNoBlockReads_warns_andRunsAllTheSame() throws IOException {
        Result run = run(greeting(), "--input", "nme=Anna", "--input", "name=Berta");

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.err()).isEqualTo("traceworks: warning: input \"nme\" is read by no block and is not in"
                + " default_context, so it changes nothing in this run; did you mean \"name\"?"
                + System.lineSeparator());
        assertThat(run.all().indexOf("traceworks: warning: input")).isLessThan(run.all().indexOf("run started: "));
        assertThat(run.out()).contains("output: Hello, Berta");
    }

    @Test
    void run_noCheck_stillNamesAnInputNoBlockReads() throws IOException {
        Result run = run(greeting(), "--no-check", "--input", "nme=Anna");

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.err()).contains("traceworks: warning: input \"nme\" is read by no block");
    }

    // ---- resume ----

    /** A resume runs the definition recorded at the start, which the start checked or was told not to check. */
    @Test
    void resume_doesNotCheck() throws IOException {
        Path pkg = pkg("parks", "{}", """
                {"type":"approval","name":"go","config":{"question":"Go on?","options":["Yes","No"]}},
                {"type":"shell","name":"after","config":{"command":"echo {{ block.x.output }}"}}""");
        Result refused = run(pkg);
        Result parked = run(pkg, "--no-check");
        String id = idOf(parked);
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();

        Result resumed = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(refused.code()).as(refused.all()).isEqualTo(6);
        assertThat(parked.code()).as(parked.all()).isEqualTo(5);
        assertThat(resumed.code()).as(resumed.all()).isZero();
        assertThat(resumed.err()).doesNotContain("unknown-block-reference", "refused");
        assertThat(resumed.out()).contains("output: {{ block.x.output }}", "run finished: COMPLETED");
    }

    @Test
    void help_namesNoCheck() {
        assertThat(exec("run", "--help").out()).contains("--no-check");
        assertThat(exec("resume", "--help").out()).doesNotContain("--no-check");
    }
}
