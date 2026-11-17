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
 * The two spellings of a key of {@code workflow.json} (docs/workflow-format.md, "Key names"), end to end: a definition
 * in the earlier camel-case names is checked, described, run and fed into its register like the same definition with
 * underscores, and one with both spellings of a key is refused by every command that reads it. Host runs of real
 * shell steps in a temporary home.
 */
class KeySpellingTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");

    /** An audit with an input, a model choice no block uses and a report of one finding, in the underscore names. */
    private static final String UNDERSCORE = """
            {
              "format": "traceworks-workflow",
              "format_version": 1,
              "id": "spelling",
              "name": "Spelling",
              "requires": ["ai.model_choice"],
              "default_context": {"subject": "A.java"},
              "model_choices": {"judge": {"default": "fable", "options": {
                  "fable": {"provider": "CLAUDE", "model": "claude-fable-5-2"}}}},
              "metadata": {"audit": {"enabled": true, "key": "spelling", "category": "example",
                                     "report_block": "report", "schema_version": 1}},
              "blocks": [
                {"type": "shell", "name": "report", "config": {"command":
                  "printf '%s\\\\n' '# Report' '<!-- traceworks:audit-result:v1' \
            '{\\"snapshotComplete\\":true,\\"findings\\":[{\\"ruleKey\\":\\"size\\",\\"subject\\":\\"{{ subject }}\\",\
            \\"severity\\":\\"LOW\\",\\"title\\":\\"Big\\"}]}' '-->'"}}
              ]
            }
            """;

    private static final String CAMEL_CASE = UNDERSCORE.replace("format_version", "formatVersion")
            .replace("default_context", "defaultContext").replace("model_choices", "modelChoices")
            .replace("report_block", "reportBlock").replace("schema_version", "schemaVersion");

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    private record Result(int code, String out, String err) {
        String all() {
            return out + err;
        }
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Path pkg(String name, String json) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"), json);
        return dir;
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

    private Result run(Path pkg) {
        return exec("run", pkg.toString(), "--allow-host-execution", "--no-input");
    }

    private static String runId(Result run) {
        Matcher matcher = RUN_ID.matcher(run.out());
        assertThat(matcher.find()).as(run.all()).isTrue();
        return matcher.group(1);
    }

    private static String withoutDigest(String described) {
        return described.replaceAll("(?m)^Digest +[0-9a-f]{64}$", "Digest");
    }

    @Test
    void theCamelCaseNames_areChecked_described_run_andFeedTheRegister_likeTheUnderscoreNames() throws IOException {
        assertThat(CAMEL_CASE).contains("formatVersion", "defaultContext", "modelChoices", "reportBlock",
                "schemaVersion").doesNotContain("format_version", "default_context", "model_choices", "report_block");
        Path camelCase = pkg("camel", CAMEL_CASE);
        Path underscore = pkg("underscore", UNDERSCORE);

        Result check = exec("check", camelCase.toString());
        assertThat(check.code()).as(check.all()).isZero();
        assertThat(check.out()).isEqualTo(exec("check", underscore.toString()).out());
        assertThat(check.out()).as("the choice is declared and unused: the keys are all read")
                .contains("unused-model-choice").contains("model_choices.judge").doesNotContain("ERROR");

        Result describe = exec("describe", camelCase.toString());
        assertThat(describe.code()).as(describe.all()).isZero();
        assertThat(withoutDigest(describe.out())).as("all but the digest, which is over the file as it is")
                .isEqualTo(withoutDigest(exec("describe", underscore.toString()).out()
                        .replace(underscore.toString(), camelCase.toString())));
        assertThat(describe.out()).contains("subject", "A.java", "judge", "claude-fable-5-2", "spelling");
        Result html = exec("describe", camelCase.toString(), "--html", temp.resolve("camel.html").toString());
        assertThat(html.code()).as(html.all()).isZero();
        assertThat(Files.readString(temp.resolve("camel.html"))).contains("A.java", "claude-fable-5-2");

        Result first = run(camelCase);
        assertThat(first.code()).as(first.all()).isZero();
        assertThat(first.out()).contains("register spelling: 1 new, 0 resolved, 0 reopened, 1 open now");
        Result second = run(underscore);
        assertThat(second.code()).as(second.all()).isZero();
        assertThat(second.out()).as("the same register, the same finding")
                .contains("register spelling: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(exec("findings", "spelling", "list").out()).contains("A.java");

        Result show = exec("show", runId(first));
        assertThat(show.code()).as(show.all()).isZero();
        assertThat(show.out()).contains("COMPLETED");
        Result diff = exec("diff", runId(first), runId(second));
        assertThat(diff.all()).as("the record of either spelling is read: diff compares the two runs")
                .doesNotContain("cannot be read");
        assertThat(diff.code()).isIn(0, 11);
    }

    @Test
    void bothSpellingsOfOneKey_areRefusedByEveryCommandThatReadsTheDefinition() throws IOException {
        Path both = pkg("both", UNDERSCORE.replace("\"format_version\": 1,",
                "\"format_version\": 1, \"formatVersion\": 1,"));
        Path audit = pkg("both-audit", UNDERSCORE.replace("\"report_block\": \"report\",",
                "\"report_block\": \"report\", \"reportBlock\": \"other\","));

        for (Path pkg : List.of(both, audit)) {
            Result check = exec("check", pkg.toString());
            Result run = run(pkg);
            Result unchecked = exec("run", pkg.toString(), "--allow-host-execution", "--no-input", "--no-check");
            Result describe = exec("describe", pkg.toString());

            for (Result refused : List.of(check, run, unchecked, describe)) {
                assertThat(refused.code()).as(refused.all()).isEqualTo(6);
                assertThat(refused.all()).contains("duplicate-key-spelling", "two spellings of one key");
            }
        }
        assertThat(exec("check", both.toString()).all()).contains("both format_version and formatVersion");
        assertThat(exec("check", audit.toString()).all())
                .contains("both metadata.audit.report_block and metadata.audit.reportBlock");
        assertThat(exec("runs").out()).as("no run was started").doesNotContain("both");
    }

    /** The definitions above with an approval before the report, so that a run of them parks. */
    private static String parking(String json) {
        String report = "{\"type\": \"shell\", \"name\": \"report\"";
        assertThat(json).contains(report);
        return json.replace(report, "{\"type\": \"approval\", \"name\": \"ok\", \"config\": {\"question\": \"Go?\","
                + " \"options\": [\"Yes\", \"No\"]}},\n    " + report);
    }

    /** The recorded definition of a parked run holds the file as written: all five camel-case names. */
    @Test
    void aParkedRun_ofTheCamelCaseNames_isResumed() throws IOException {
        Path camelCase = pkg("parked", parking(CAMEL_CASE));

        Result parked = run(camelCase);
        assertThat(parked.code()).as(parked.all()).isEqualTo(5);
        String id = runId(parked);
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();
        Result resumed = exec("resume", id, "--allow-host-execution");

        assertThat(resumed.code()).as(resumed.all()).isZero();
        assertThat(resumed.out()).as("report_block and default_context of the recorded definition were read")
                .contains("register spelling: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(exec("findings", "spelling", "list").out()).contains("A.java");
        assertThat(exec("show", id).out()).contains("COMPLETED");
        assertThat(exec("verify", id).code()).isZero();
    }

    /** A package that got both spellings of a key while its run was parked is not loaded, so the run does not go on. */
    @Test
    void aResume_againstAPackageWithBothSpellings_isRefused() throws IOException {
        Path pkg = pkg("respelled", parking(UNDERSCORE));
        Result parked = run(pkg);
        assertThat(parked.code()).as(parked.all()).isEqualTo(5);
        String id = runId(parked);
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();
        Files.writeString(pkg.resolve("workflow.json"), parking(UNDERSCORE).replace("\"format_version\": 1,",
                "\"format_version\": 1, \"formatVersion\": 1,"));

        Result refused = exec("resume", id, "--allow-host-execution", "--allow-changed-package");

        assertThat(refused.code()).as(refused.all()).isEqualTo(6);
        assertThat(refused.all()).contains("duplicate-key-spelling", "both format_version and formatVersion");
        assertThat(exec("findings", "spelling", "list").all()).as("the report step never ran")
                .doesNotContain("A.java");
    }

    @Test
    void seal_sealsEitherSpelling_andRefusesBoth() throws Exception {
        Path key = Files.createDirectories(temp.resolve("keys")).resolve("seal");
        Process keygen = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "test@traceworks",
                "-f", key.toString()).redirectErrorStream(true).start();
        assertThat(keygen.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(keygen.exitValue()).isZero();
        Files.writeString(home.resolve("traceworks.json"), "{\"signing_key\": \"" + key + "\"}");
        Files.writeString(home.resolve("allowed_signers"),
                "release@example.org " + Files.readString(Path.of(key + ".pub")));
        Path camelCase = pkg("sealed-camel", CAMEL_CASE.replace("spelling", "camel"));
        Path underscore = pkg("sealed-underscore", UNDERSCORE);
        Path both = pkg("sealed-both", UNDERSCORE.replace("\"id\": \"spelling\"", "\"id\": \"both\"")
                .replace("\"default_context\":", "\"defaultContext\": {}, \"default_context\":"));

        for (Path pkg : List.of(camelCase, underscore)) {
            Result sealed = exec("seal", pkg.toString());
            assertThat(sealed.code()).as(sealed.all()).isZero();
            assertThat(pkg.resolve("traceworks.seal")).exists();
            Result run = run(pkg);
            assertThat(run.code()).as(run.all()).isZero();
        }
        // The seal is over the file as it is: the other spelling of a key is a change
        Files.writeString(camelCase.resolve("workflow.json"),
                UNDERSCORE.replace("spelling", "camel"));
        Result respelled = run(camelCase);
        assertThat(respelled.code()).as(respelled.all()).isEqualTo(6);
        assertThat(respelled.all()).contains("changed since it was sealed");

        Result refused = exec("seal", both.toString());
        assertThat(refused.code()).as(refused.all()).isEqualTo(6);
        assertThat(refused.all()).contains("duplicate-key-spelling", "both default_context and defaultContext");
        assertThat(both.resolve("traceworks.seal")).doesNotExist();
    }

    private Path parentOf(String name, String childJson) throws IOException {
        Path parent = pkg(name, """
                {"format": "traceworks-workflow", "format_version": 1, "id": "%s", "name": "Parent", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./child"}}]}""".formatted(name));
        Files.createDirectories(parent.resolve("child"));
        Files.writeString(parent.resolve("child/workflow.json"), childJson);
        return parent;
    }

    private static String child(String keys) {
        return """
                {"format": "traceworks-workflow", %s, "id": "child", "name": "Child", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "echo child ran"}}]}""".formatted(keys);
    }

    @Test
    void aChildWorkflow_isReadInEitherSpelling_andOneWithBothFailsTheStepThatCallsIt() throws IOException {
        Result camelCase = run(parentOf("parent-camel", child("\"formatVersion\": 1, \"defaultContext\": {}")));
        Result newer = run(parentOf("parent-newer", child("\"formatVersion\": 2")));
        Path withBoth = parentOf("parent-both", child("\"format_version\": 1, \"formatVersion\": 1"));
        Result both = run(withBoth);
        Result bothUnchecked = exec("run", withBoth.toString(), "--allow-host-execution", "--no-input", "--no-check");

        assertThat(camelCase.code()).as(camelCase.all()).isZero();
        assertThat(newer.code()).as("the camel-case key of the child is read, not ignored: " + newer.all())
                .isNotZero();
        assertThat(newer.all()).as("the child's own admission, at the step that calls it")
                .contains("run started", "format_version 2 is newer");
        // A child that cannot be loaded fails the step that calls it, here as for any child that cannot be read:
        // one with a key Traceworks does not know passes the parent's check and fails its step with the same exit code
        Path unreadable = parentOf("parent-unknown-key", child("\"format_version\": 1, \"nope\": 1"));
        assertThat(exec("check", unreadable.toString()).code()).isZero();
        assertThat(exec("check", withBoth.toString()).code()).isZero();
        Result unknownKey = run(unreadable);
        assertThat(unknownKey.out()).contains("child FAILED");
        assertThat(exec("show", runId(unknownKey), "--full").all()).contains("Unrecognized field \"nope\"");
        for (Result refused : List.of(both, bothUnchecked)) {
            assertThat(refused.code()).as(refused.all()).isEqualTo(unknownKey.code()).isNotZero();
            assertThat(refused.out()).contains("child FAILED").doesNotContain("child ran");
            assertThat(exec("show", runId(refused), "--full").all())
                    .contains("duplicate-key-spelling", "both format_version and formatVersion");
        }
    }
}
