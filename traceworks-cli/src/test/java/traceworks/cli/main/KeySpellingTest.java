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
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
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
}
