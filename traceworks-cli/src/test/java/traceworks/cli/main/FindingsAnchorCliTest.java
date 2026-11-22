package traceworks.cli.main;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anchor of a register in the git ledger says what was written, on the command line: after an audit run and a
 * decision the two lines of {@code anchors/<key>/<project>.jsonl} say the run and its lines, and who decided which
 * finding, and no content of the register. {@code findings <key> verify} and {@code verify} derive the same from the
 * register's log and fail when a line says something else. Audit runs on the host, in a temporary home.
 */
class FindingsAnchorCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;
    private Path home;
    private Path anchors;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        anchors = home.resolve("ledger/anchors/hello/shop.jsonl");
    }

    private Result execIn(Path work, String... args) {
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

    private Result exec(String... args) {
        return execIn(temp, args);
    }

    /** An audit with one finding, whose title and subject are words no anchor may hold. */
    private Path audit() throws IOException {
        String report = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"long-file\",\"subject\":\"secret-plan.txt\","
                + "\"severity\":\"LOW\",\"title\":\"secret-plan.txt is long\"}]}' '-->'\n";
        Path dir = Files.createDirectories(temp.resolve("audit"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + JSON.writeValueAsString(report) + "}}]}");
        return dir;
    }

    /** One audit run on the project shop, then a dismissal of its finding; the id of the run and of the finding. */
    private String[] aRunAndADecision(String... agent) throws IOException {
        Path work = Files.createDirectories(temp.resolve("shop"));
        Result run = execIn(work, "run", audit().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--project", "shop");
        assertThat(run.code()).as(run.out() + run.err()).isZero();
        String runId;
        try (Stream<Path> runs = Files.walk(home.resolve("runs"))) {
            runId = runs.filter(path -> path.getFileName().toString().equals("events.jsonl"))
                    .map(path -> path.getParent().getFileName().toString()).toList().getFirst();
        }
        JsonNode state = JSON.readTree(Files.readAllBytes(home.resolve("registers/hello/shop/state.json")));
        String finding = state.path("findings").path(0).path("id").asText();
        assertThat(finding).hasSize(64);
        List<String> dismiss = new ArrayList<>(List.of("findings", "hello", "dismiss", finding.substring(0, 12),
                "--note", "the plan is no secret"));
        dismiss.addAll(List.of(agent));
        Result dismissed = exec(dismiss.toArray(String[]::new));
        assertThat(dismissed.code()).as(dismissed.out() + dismissed.err()).isZero();
        return new String[] {runId, finding};
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", home.resolve("ledger").toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_AUTHOR_NAME", "Traceworks", "GIT_AUTHOR_EMAIL",
                "traceworks@localhost.invalid", "GIT_COMMITTER_NAME", "Traceworks", "GIT_COMMITTER_EMAIL",
                "traceworks@localhost.invalid", "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"));
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }

    private static String sha256(String content) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Puts {@code changed} in the place of the last anchor line, by a commit as Traceworks writes them in the place
     * of the last one: the history is one that this version could have written, and only what the line says differs.
     */
    private void theLastAnchorSays(String changed) throws Exception {
        List<String> lines = Files.readAllLines(anchors);
        String seq = JSON.readTree(lines.getLast()).path("seq").asText();
        lines.set(lines.size() - 1, changed);
        String content = String.join("\n", lines) + "\n";
        Files.writeString(anchors, content);
        git("commit", "-q", "--amend", "-m", "anchor hello/shop seq " + seq + " sha256:" + sha256(content), "--",
                "anchors/hello/shop.jsonl");
        // what an amended commit leaves behind and a rewritten history would not have
        git("reflog", "expire", "--expire=now", "--all");
        git("gc", "-q", "--prune=now");
    }

    @Test
    void theAnchorsOfARunAndOfADecision_sayWhatWasWritten_andNoContentOfTheRegister() throws Exception {
        String[] ids = aRunAndADecision();

        List<String> lines = Files.readAllLines(anchors);
        assertThat(lines).hasSize(2);
        JsonNode ofTheRun = JSON.readTree(lines.get(0));
        assertThat(ofTheRun.path("lines").toString()).isEqualTo("{\"finding.new\":1,\"register.created\":1,"
                + "\"register.requires\":1,\"report.applied\":1}");
        assertThat(ofTheRun.path("run").asText()).isEqualTo(ids[0]);
        assertThat(ofTheRun.fieldNames()).toIterable().containsExactly("seq", "head", "lines", "run");
        List<String> log = Files.readAllLines(home.resolve("registers/hello/shop/events.jsonl"));
        assertThat(lines.get(1)).as("the decision: the actor and the finding, and no run").isEqualTo("{\"seq\":"
                + (log.size() - 1) + ",\"head\":\"" + sha256(log.getLast()) + "\",\"lines\":{\"finding.dismissed\":1},"
                + "\"actor\":\"tester\",\"decided\":[\"" + ids[1] + "\"]}");
        assertThat(Files.readString(anchors)).doesNotContain("secret-plan").doesNotContain("no secret")
                .doesNotContain("LOW").doesNotContain("long-file");
        assertThat(git("log", "--format=%s", "--", "anchors/hello/shop.jsonl").lines())
                .allMatch(subject -> subject.matches("anchor hello/shop seq \\d+ sha256:[0-9a-f]{64}"));

        Result verify = exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(verify.out()).contains("anchor    ok (seq " + (log.size() - 1)).contains("verified");
        Result all = exec("verify");
        assertThat(all.code()).as(all.out() + all.err()).isZero();
    }

    @Test
    void theAnchorOfADecisionThroughAnAgent_namesTheAgentBesideTheActor() throws Exception {
        String[] ids = aRunAndADecision("--agent", "claude-code");

        JsonNode ofTheDecision = JSON.readTree(Files.readAllLines(anchors).get(1));
        assertThat(ofTheDecision.path("lines").toString())
                .isEqualTo("{\"finding.dismissed\":1,\"register.requires\":1}");
        assertThat(ofTheDecision.path("actor").asText()).isEqualTo("tester");
        assertThat(ofTheDecision.path("agent").asText()).isEqualTo("claude-code");
        assertThat(ofTheDecision.path("decided").toString()).isEqualTo("[\"" + ids[1] + "\"]");
        assertThat(ofTheDecision.has("run")).isFalse();
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    /** A line that says another actor, in a history as Traceworks writes it: only the register's log tells. */
    @Test
    void verify_findsAnAnchorThatSaysSomethingElseThanTheLog_andNamesTheAnchorTheFieldAndWhatTheLogSays()
            throws Exception {
        aRunAndADecision();
        String last = Files.readAllLines(anchors).getLast();
        int seq = JSON.readTree(last).path("seq").asInt();

        theLastAnchorSays(last.replace("\"actor\":\"tester\"", "\"actor\":\"mallory\""));

        String problem = "seq " + seq + ": the anchor in the ledger says actor mallory, and the lines it covers (seq "
                + seq + ") give actor tester";
        Result ofTheRegister = exec("findings", "hello", "verify");
        assertThat(ofTheRegister.code()).as(ofTheRegister.out() + ofTheRegister.err()).isEqualTo(8);
        assertThat(ofTheRegister.out()).contains("anchor    NOT ok").contains(problem)
                .contains("register hello of shop does not verify");
        Result ofTheHome = exec("verify");
        assertThat(ofTheHome.code()).as(ofTheHome.out() + ofTheHome.err()).isEqualTo(8);
        assertThat(ofTheHome.out()).contains("problems (1)").contains("anchors.5.hello.shop  " + problem)
                .contains("verification FAILED");

        // every field is compared: a count, and the finding
        theLastAnchorSays(last.replace("\"finding.dismissed\":1", "\"finding.dismissed\":2")
                .replaceAll("\"decided\":\\[\"[0-9a-f]{64}\"]", "\"decided\":[\"" + "0".repeat(64) + "\"]"));

        Result both = exec("findings", "hello", "verify");
        assertThat(both.code()).isEqualTo(8);
        assertThat(both.out()).contains("says lines finding.dismissed 2, and the lines it covers (seq " + seq
                + ") give finding.dismissed 1").contains("says decided " + "0".repeat(64));
    }

    /** A line with seq and head alone, in the file of a key and a project: no version writes it there. */
    @Test
    void verify_findsAnAnchorWithoutLines_inTheFileOfAProject() throws Exception {
        aRunAndADecision();
        JsonNode last = JSON.readTree(Files.readAllLines(anchors).getLast());

        theLastAnchorSays("{\"seq\":" + last.path("seq").asInt() + ",\"head\":\"" + last.path("head").asText() + "\"}");

        Result verify = exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.out()).contains("seq " + last.path("seq").asInt() + ": the anchor in the ledger does not"
                + " say what was written (it has no lines)");
        assertThat(exec("verify").code()).isEqualTo(8);
    }
}
