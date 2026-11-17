package traceworks.cli.main;

import traceworks.adapters.images.BuiltInImages;
import traceworks.cli.RuntimeSource;
import traceworks.core.app.Images;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.FakeContainerRuntime;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code traceworks diff <runA> <runB>} end to end: real adapters in a temporary home, runs on the host. */
class DiffCommandTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");
    /** The exit code of two runs that differ. */
    private static final int DIFFERS = 11;

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    private final Map<String, String> env = new HashMap<>(Map.of("TRACEWORKS_ACTOR", "tester"));

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Result exec(String... args) {
        return execWith(RuntimeSource.NONE, args);
    }

    private Result execWith(RuntimeSource runtime, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.copyOf(env), work, null, runtime);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    private Path pkg(String name, String blocks) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"),
                "{\"id\":\"" + name + "\",\"name\":\"" + name + " flow\",\"blocks\":[" + blocks + "]}");
        return dir;
    }

    /** Greets the input {@code name}, then prints three fixed lines. */
    private Path greeting() throws IOException {
        return pkg("greeting", """
                {"type":"shell","name":"greet","config":{"command":"echo one; echo Hello, {{ name }}; echo three"}},
                {"type":"shell","name":"fixed","config":{"command":"echo fixed"}}""");
    }

    private String run(Path pkg, String... more) {
        return run(0, pkg, more);
    }

    private String run(int expectedCode, Path pkg, String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--allow-host-execution", "--no-input",
                "--quiet"));
        args.addAll(List.of(more));
        Result result = exec(args.toArray(String[]::new));
        assertThat(result.code()).as(result.out() + result.err()).isEqualTo(expectedCode);
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out()).isTrue();
        return m.group(1);
    }

    private Path runFolder(String id) throws IOException {
        try (Stream<Path> days = Files.walk(home.resolve("runs"), 2)) {
            return days.filter(p -> p.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
    }

    @Test
    void twoRunsWithTheSameInputs_exit0_withAShortOutput() throws IOException {
        Path pkg = greeting();
        String a = run(pkg, "--input", "name=Anna");
        String b = run(pkg, "--input", "name=Anna");

        Result diff = exec("diff", a.substring(0, 8), b.substring(0, 8));

        assertThat(diff.code()).as(diff.out() + diff.err()).isZero();
        assertThat(diff.out()).contains("Run A     " + a, "Run B     " + b)
                .contains("  same: workflow id, workflow name, package digest, package commit, inputs digest,"
                        + " Traceworks version, execution, ai_network, models, status, gate")
                .contains("  same: name")
                .containsPattern("(?m)^  greet +COMPLETED +same output$")
                .containsPattern("(?m)^  fixed +COMPLETED +same output$")
                .contains("same: run B reproduced run A");
        assertThat(diff.out()).doesNotContain("differs").doesNotContain("Anna");
        assertThat(diff.out().lines().count()).isLessThan(16);
        assertThat(diff.err()).isEmpty();
    }

    @Test
    void aChangedInput_exits11_namesTheInputAndTheStepThatDiffers() throws IOException {
        Path pkg = greeting();
        String a = run(pkg, "--input", "name=Anna");
        String b = run(pkg, "--input", "name=Bert");

        Result diff = exec("diff", a, b);

        assertThat(diff.code()).as(diff.out() + diff.err()).isEqualTo(DIFFERS);
        assertThat(diff.out())
                .containsPattern("(?m)^  inputs digest +A [0-9a-f]{64}\\R +B [0-9a-f]{64}$")
                .containsPattern("(?m)^  name +A \"Anna\"\\R +B \"Bert\"$")
                .containsPattern("(?m)^  greet +COMPLETED +output differs$")
                .containsPattern("(?m)^  fixed +COMPLETED +same output$")
                .contains("differs: run B did not reproduce run A", "  the inputs differ",
                        "  step greet: the output differs");
        assertThat(diff.out()).doesNotContain("Hello, Anna");
    }

    @Test
    void outputs_addsALineDiffOfTheOutputsThatDiffer() throws IOException {
        Path pkg = greeting();
        String a = run(pkg, "--input", "name=Anna");
        String b = run(pkg, "--input", "name=Bert");

        Result diff = exec("diff", a, b, "--outputs");

        assertThat(diff.code()).isEqualTo(DIFFERS);
        assertThat(diff.out()).contains("      |   one\n      | - Hello, Anna\n      | + Hello, Bert\n      |   three\n");
        assertThat(diff.out()).doesNotContain("|   fixed");
    }

    /** A run folder copied out of the home is compared by its path; its outputs are read from the copy. */
    @Test
    void aCopiedRunFolder_isComparedByItsPath() throws IOException {
        Path pkg = greeting();
        String a = run(pkg, "--input", "name=Anna");
        String b = run(pkg, "--input", "name=Bert");
        Path copy = temp.resolve("copies").resolve(b);
        Files.createDirectories(copy.getParent());
        try (Stream<Path> files = Files.walk(runFolder(b))) {
            for (Path file : files.toList()) {
                Path target = copy.resolve(runFolder(b).relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        deleteTree(runFolder(b));

        Result diff = exec("diff", a, copy.toString(), "--outputs");

        assertThat(diff.code()).as(diff.out() + diff.err()).isEqualTo(DIFFERS);
        assertThat(diff.out()).contains("Run B     " + b, "      | - Hello, Anna\n      | + Hello, Bert\n");
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    /** An output over the bound of the line diff is not diffed: the command says so and names how to read it. */
    @Test
    void outputs_overTheBound_saysSoInsteadOfDiffing() throws IOException {
        Path pkg = pkg("big", """
                {"type":"shell","name":"many","config":{"command":"seq {{ from }} {{ to }}"}}""");
        String a = run(pkg, "--input", "from=1", "--input", "to=2100");
        String b = run(pkg, "--input", "from=10001", "--input", "to=12100");

        Result diff = exec("diff", a, b, "--outputs");

        assertThat(diff.code()).isEqualTo(DIFFERS);
        assertThat(diff.out()).contains("too large for a line diff (2100 and 2100 lines)", "show",
                "--full").doesNotContain("| - 1");
    }

    /** A value the record redacted is never printed, in the inputs or anywhere: only whether it is the same. */
    @Test
    void aRedactedInput_isNeverPrinted() throws IOException {
        String secret = "tw-env-s3cr3t-Rk4Wb8Nc1";
        env.put("API_TOKEN", secret);
        Files.createDirectories(home);
        Files.writeString(home.resolve("traceworks.json"), "{\"secret_names\": [\"API_TOKEN\"]}");
        Path pkg = pkg("secret", """
                {"type":"shell","name":"use","config":{"command":"echo used","secrets":["API_TOKEN"]}}""");
        String a = run(pkg, "--input", "note=" + secret);
        String b = run(pkg, "--input", "note=plain");

        Result diff = exec("diff", a, b, "--outputs");

        assertThat(diff.code()).isEqualTo(DIFFERS);
        assertThat(diff.out()).containsPattern("(?m)^  note +differs \\(a redacted value is not shown\\)$")
                .doesNotContain(secret).doesNotContain("[redacted").doesNotContain("plain");
    }

    @Test
    void aChangedPackage_exits11_andAStepOfOneRunOnlyIsListed() throws IOException {
        String a = run(pkg("grow", """
                {"type":"shell","name":"one","config":{"command":"echo 1"}}"""));
        String b = run(pkg("grow", """
                {"type":"shell","name":"one","config":{"command":"echo 1"}},
                {"type":"shell","name":"two","config":{"command":"echo 2"}}"""));

        Result diff = exec("diff", a, b);

        assertThat(diff.code()).isEqualTo(DIFFERS);
        assertThat(diff.out()).containsPattern("(?m)^  package digest +A [0-9a-f]{64}\\R +B [0-9a-f]{64}$")
                .containsPattern("(?m)^  two +only in run B \\(COMPLETED\\)$")
                .contains("  the package digest differs", "  step two: only in run B");
    }

    @Test
    void aRunThatHasNotFinished_isAnError_notADifference() throws IOException {
        String finished = run(greeting(), "--input", "name=Anna");
        String parked = run(5, pkg("ask", """
                {"type":"approval","name":"go","config":{"question":"Go?","options":["Yes","No"]}}"""));

        Result diff = exec("diff", finished, parked);

        assertThat(diff.code()).isEqualTo(6);
        assertThat(diff.err()).contains(parked, "has not finished (PARKED)");
        assertThat(diff.out()).isEmpty();
    }

    @Test
    void aRunThatDoesNotVerify_isAnError_notADifference() throws IOException {
        Path pkg = greeting();
        String a = run(pkg, "--input", "name=Anna");
        String b = run(pkg, "--input", "name=Anna");
        Path manifest = runFolder(b).resolve("manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"COMPLETED\"", "\"FAILED\""));

        Result diff = exec("diff", a, b);

        assertThat(diff.code()).isEqualTo(8);
        assertThat(diff.err()).contains(b, "does not verify", "traceworks verify");
        assertThat(diff.out()).isEmpty();
    }

    @Test
    void anUnknownRun_orAMissingOne_isAUsageError() throws IOException {
        String a = run(greeting(), "--input", "name=Anna");

        assertThat(exec("diff", a, "deadbeef").code()).isEqualTo(2);
        assertThat(exec("diff", a).code()).isEqualTo(2);
        assertThat(exec("diff").code()).isEqualTo(2);
    }

    @Test
    void help_listsTheCommand_itsExitCode_andWhatSameMeans() {
        assertThat(exec("--help").out()).containsPattern("(?m)^  diff ").contains("11 the two runs differ");
        assertThat(exec("diff", "--help").out()).contains("usage: traceworks diff <runA> <runB>", "--outputs",
                "Exits 11");
    }

    private static String builtInTag(String name) {
        return Images.builtInTag(new BuiltInImages().all().stream().filter(image -> image.name().equals(name))
                .findFirst().orElseThrow());
    }

    private static String stream(String answer, int inputTokens) {
        return """
                {"type":"system","subtype":"init","cwd":"/workspace","session_id":"s-1","tools":[]}
                {"type":"assistant","message":{"model":"claude-opus-5-5","content":[{"type":"text","text":"%s"}]}}
                {"type":"result","subtype":"success","result":"%s","usage":{"input_tokens":%d,"output_tokens":3}}
                """.formatted(answer, answer, inputTokens);
    }

    /** An AI step: both calls with what the record has of them, and an answer that differs does not fail the check. */
    @Test
    void anAiStep_printsBothCalls_andADifferentAnswerStillExits0() throws IOException {
        String claudeId = "sha256:" + "c".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime()
                .present(builtInTag("shell"), "sha256:" + "5".repeat(64))
                .present(builtInTag("claude"), claudeId)
                .present(builtInTag("egress"), "sha256:" + "e".repeat(64));
        env.put("ANTHROPIC_API_KEY", "sk-ant-api03-not-a-real-key-0000");
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi","model":"opus"}}""");
        List<String> ids = new ArrayList<>();
        for (String[] answer : new String[][] {{"hi", "12"}, {"hello", "15"}}) {
            runtime.on(claudeId, FakeCommandRunner.ok(stream(answer[0], Integer.parseInt(answer[1]))));
            Result r = execWith(RuntimeSource.of(runtime), "run", pkg.toString(), "--no-input", "--quiet");
            assertThat(r.code()).as(r.out() + r.err()).isZero();
            Matcher m = RUN_ID.matcher(r.out());
            assertThat(m.find()).isTrue();
            ids.add(m.group(1));
        }

        Result diff = exec("diff", ids.get(0), ids.get(1));

        assertThat(diff.code()).as(diff.out() + diff.err()).isZero();
        assertThat(diff.out())
                .containsPattern("(?m)^  ask +\\[ai\\] +COMPLETED +output differs$")
                .contains("      AI call 1  same setup, prompt same, output differs\n"
                        + "        A  CLAUDE  model opus  answered by claude-opus-5-5  tools none  web none"
                        + "  12 in / 3 out tokens  cost -\n"
                        + "        B  CLAUDE  model opus  answered by claude-opus-5-5  tools none  web none"
                        + "  15 in / 3 out tokens  cost -\n")
                .contains("same: run B reproduced run A");
    }

    /** An AI step whose call is the same in both runs shows that call: only more calls than are shown are folded. */
    @Test
    void anAiStepWithOneCallThatIsTheSame_showsTheCall_unfolded() throws IOException {
        String claudeId = "sha256:" + "c".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime()
                .present(builtInTag("shell"), "sha256:" + "5".repeat(64))
                .present(builtInTag("claude"), claudeId)
                .present(builtInTag("egress"), "sha256:" + "e".repeat(64));
        env.put("ANTHROPIC_API_KEY", "sk-ant-api03-not-a-real-key-0000");
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi","model":"opus"}}""");
        runtime.on(claudeId, FakeCommandRunner.ok(stream("hi", 12)));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Result r = execWith(RuntimeSource.of(runtime), "run", pkg.toString(), "--no-input", "--quiet");
            assertThat(r.code()).as(r.out() + r.err()).isZero();
            Matcher m = RUN_ID.matcher(r.out());
            assertThat(m.find()).isTrue();
            ids.add(m.group(1));
        }

        Result diff = exec("diff", ids.get(0), ids.get(1));

        assertThat(diff.code()).as(diff.out() + diff.err()).isZero();
        assertThat(diff.out()).contains("      AI call 1  same setup, prompt same, same output\n")
                .doesNotContain("more AI calls");
    }

    /** A foreach item that is the same item in both runs, with another output, is named as the same item. */
    @Test
    void aForeachItemWithAnotherOutput_isNamedAsTheSameItem() throws IOException {
        Path pkg = pkg("each", """
                {"type":"foreach","name":"each","config":{"execution_mode":"shell","items":["a","b"],
                 "command":"echo {{ item }} {{ n }}"}}""");
        String a = run(pkg, "--input", "n=1");
        String b = run(pkg, "--input", "n=2");

        Result diff = exec("diff", a, b);

        assertThat(diff.code()).as(diff.out() + diff.err()).isEqualTo(DIFFERS);
        assertThat(diff.out()).containsPattern("(?m)^      item 0  same item, COMPLETED, output differs$");
    }

    /** Two audit runs: the findings new and resolved between the states their register recorded after each. */
    @Test
    void twoAuditRuns_listTheFindingsNewAndResolvedBetweenThem() throws IOException {
        String a = run(auditPackage("A.java", "B.java"));
        String b = run(auditPackage("B.java", "C.java"));

        Result diff = exec("diff", a, b);

        assertThat(diff.code()).isEqualTo(DIFFERS);
        assertThat(diff.out()).contains("Findings (register hello)")
                .containsPattern("(?m)^  new +[0-9a-f]{8}  MEDIUM  C\\.java  Big C\\.java$")
                .containsPattern("(?m)^  resolved +[0-9a-f]{8}  MEDIUM  A\\.java  Big A\\.java$")
                .doesNotContain("reopened  ");
    }

    private Path auditPackage(String... subjects) throws IOException {
        StringBuilder findings = new StringBuilder();
        for (String subject : subjects) {
            if (!findings.isEmpty()) {
                findings.append(",");
            }
            findings.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"Big ").append(subject).append("\"}");
        }
        String command = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[" + findings + "]}' '-->'";
        Path dir = Files.createDirectories(temp.resolve("audit"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}]}");
        return dir;
    }
}
