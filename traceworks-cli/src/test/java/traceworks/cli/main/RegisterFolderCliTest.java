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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The register folder of an audit run, end to end on the host with real commands: a step reads the files of
 * {@code {{ register_dir }}}, the record names what the run got, and {@code show}, {@code verify}, {@code describe},
 * {@code check} and {@code diff} say their part.
 */
class RegisterFolderCliTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");

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

    private static String json(String text) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(text);
    }

    /**
     * An audit whose first step does {@code known} with the register folder and whose report states one finding per
     * subject, each with a description.
     */
    private Path audit(String known, String... subjects) throws IOException {
        StringBuilder findings = new StringBuilder();
        for (String subject : subjects) {
            if (!findings.isEmpty()) findings.append(",");
            findings.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"Big ").append(subject)
                    .append("\",\"description\":\"Split ").append(subject).append(" in two\"}");
        }
        String report = "printf '%s\\n' '# Report' 'three files are big' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[" + findings + "]}' '-->'";
        Path dir = Files.createDirectories(temp.resolve("audit"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"known\",\"config\":{\"command\":" + json(known) + "}},"
                + "{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":" + json(report) + "}}]}");
        return dir;
    }

    private static final String READ = "set -euo pipefail\n"
            + "echo \"known: $(wc -l < {{ register_dir }}/findings.jsonl)\"\n"
            + "grep -c 'in two' {{ register_dir }}/findings.jsonl || true\n"
            + "cat {{ register_dir }}/previous-report.md\n"
            + "ls {{ register_dir }}";

    private String run(int expectedCode, Path pkg) {
        Result result = exec("run", pkg.toString(), "--allow-host-execution", "--no-input", "--quiet");
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
    void aStepReadsTheFilesOfTheRegister_andTheRecordSaysWhatTheRunGot() throws IOException {
        String first = run(0, audit(READ, "A.java", "B.java", "C.java"));
        String second = run(0, audit(READ, "A.java", "B.java", "C.java"));

        Result firstShown = exec("show", first, "--outputs");
        assertThat(firstShown.out()).as("nothing known yet: the files are there and empty").contains("known: 0")
                .contains("Register  hello before its first line, 4 files handed to the steps");
        Result shown = exec("show", second, "--outputs");
        assertThat(shown.code()).as(shown.err()).isZero();
        assertThat(shown.out()).contains("known: 3", "# Report", "three files are big", "findings.jsonl",
                "items.jsonl", "previous-report.md", "resolved.jsonl").doesNotContain("audit-result:v1 {");
        assertThat(shown.out()).containsPattern("Register  hello at seq \\d+ \\(head [0-9a-f]{12}\\), 4 files handed"
                + " to the steps");
        Path folder = runFolder(second).resolve("register");
        assertThat(Files.readString(folder.resolve("findings.jsonl"))).contains("\"description\":\"Split A.java in"
                + " two\"", "\"run_id\":\"" + first + "\"");
        assertThat(Files.isWritable(folder.resolve("findings.jsonl"))).isFalse();
        assertThat(Files.readString(runFolder(second).resolve("run.html")))
                .containsPattern("<th>register</th><td>hello at seq \\d+ \\(head [0-9a-f]{12}\\), 4 files handed to"
                        + " the steps; head [0-9a-f]{64}</td>");

        Result verified = exec("verify", second);
        assertThat(verified.code()).as(verified.out()).isZero();
        assertThat(verified.out()).containsPattern("register  ok \\(4 files of the register hello at seq \\d+\\)");

        Result diff = exec("diff", first, second);
        assertThat(diff.out()).as("the two runs started from another head of the register")
                .containsPattern("register hello +A before its first line\\n +B at seq \\d+, head [0-9a-f]{64}\\n");
    }

    /**
     * A step on the host can write the folder whatever the permissions of its files say. The run notices before its
     * next command, starts none, and the record no longer verifies.
     */
    @Test
    void aStepThatChangesAFileOfTheFolder_stopsTheRun_andVerifyFails() throws IOException {
        run(0, audit(READ, "A.java"));
        String forging = "chmod u+w {{ register_dir }}/findings.jsonl && : > {{ register_dir }}/findings.jsonl";

        Result result = exec("run", audit(forging, "A.java").toString(), "--allow-host-execution", "--no-input",
                "--quiet");

        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out()).isTrue();
        String id = m.group(1);
        assertThat(result.out()).contains("register hello: not applied");
        Result shown = exec("show", id, "--outputs");
        assertThat(shown.out()).contains("findings.jsonl is not the file the run started with");
        Result verified = exec("verify", id);
        assertThat(verified.code()).isEqualTo(8);
        assertThat(verified.out()).contains("register  the register folder of the run changed: findings.jsonl is not"
                + " the file the run started with", "verification FAILED");
        Result list = exec("findings", "hello", "list");
        assertThat(list.out()).as("the register keeps what it knew").contains("A.java");
    }

    @Test
    void outsideAnAuditWorkflow_thePlaceholderIsAnError_andNoRunStarts() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("plain"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"plain\",\"name\":\"Plain\",\"blocks\":[{\"type\":"
                + "\"shell\",\"name\":\"known\",\"config\":{\"command\":\"set -euo pipefail\\nls {{ register_dir }}\"}}]}");

        Result check = exec("check", dir.toString());
        Result run = exec("run", dir.toString(), "--allow-host-execution", "--no-input", "--quiet");

        assertThat(check.code()).isNotZero();
        assertThat(check.out() + check.err()).contains("register-dir-outside-audit");
        assertThat(run.code()).isNotZero();
        assertThat(Files.exists(home.resolve("runs"))).as("no run was created").isFalse();
    }

    @Test
    void check_acceptsThePlaceholderInAnAudit_andDescribeNamesTheFolder() throws IOException {
        Path pkg = audit(READ, "A.java");

        Result check = exec("check", pkg.toString());
        Result described = exec("describe", pkg.toString());

        assertThat(check.out() + check.err()).doesNotContain("register-dir-outside-audit", "unknown-variable");
        assertThat(check.code()).as(check.out() + check.err()).isZero();
        assertThat(described.out()).contains("Audit     register hello, category example, report block report; its"
                + " shell and tool steps read the register's files in {{ register_dir }}, its AI steps do not");
    }
}
