package traceworks.cli.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.cli.Forged;
import traceworks.cli.RuntimeSource;
import traceworks.cli.Unbroken;

import java.io.ByteArrayInputStream;
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
 * What a command prints where a message says its parts: Traceworks' own words whole, however long, and only what is
 * quoted from outside kept from a terminal line's start. And what a list does with a text that does not fit its row.
 */
class MessagePartsCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");
    private static final String SUBJECT = "traceworks-adapters/src/main/java/traceworks/adapters/container/"
            + "MountGuard.java#MountGuard.normalized(String), MountGuard.real(Path), MountGuard.expandHome(String, Path)";
    private static final String TITLE = "path_check finding in traceworks-adapters/src/main/java/traceworks/adapters/"
            + "container/MountGuard.java, MountGuard.normalized(String)";

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    /** What a command printed, on both streams. */
    private String exec(String console, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(out, true, StandardCharsets.UTF_8);
        Main.run(all.toArray(String[]::new), both, both, Map.of("TRACEWORKS_ACTOR", "tester"), work,
                console == null ? null : new ByteArrayInputStream(console.getBytes(StandardCharsets.UTF_8)),
                RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path pkg(String dir, String description, ObjectNode... blocks) throws IOException {
        ObjectNode workflow = JSON.createObjectNode().put("id", dir).put("name", dir);
        if (description != null) {
            workflow.put("description", description);
        }
        workflow.putArray("blocks").addAll(List.of(blocks));
        Path folder = Files.createDirectories(temp.resolve(dir));
        Files.writeString(folder.resolve("workflow.json"), JSON.writeValueAsString(workflow));
        return folder;
    }

    private static ObjectNode shell(String name, String command) {
        ObjectNode block = JSON.createObjectNode().put("type", "shell").put("name", name);
        block.putObject("config").put("command", command);
        return block;
    }

    /** The owner's first complaint: every message longer than about 68 columns was broken at 80. */
    @Test
    void aMessageWithWhatTheOperatorTyped_isOneLine_howeverLong() {
        String path = "/nonexistent/a-rather-long-package-name-to-see-how-the-message-reads";

        assertThat(exec(null, "run", path).lines()).containsExactly("traceworks: no such package: " + path,
                "see 'traceworks run --help'");
        assertThat(exec(null, "frobnicate-" + "x".repeat(80)).lines().findFirst().orElseThrow())
                .isEqualTo("traceworks: unknown command 'frobnicate-" + "x".repeat(80) + "'");
    }

    /** A refusal that holds an option to copy: the advice is Traceworks' own, and whole. */
    @Test
    void aRefusalWithALineToCopy_isPrintedWhole() throws IOException {
        Path pkg = pkg("plain", null, shell("say", "echo hello"));

        String refused = exec(null, "run", pkg.toString(), "--no-input");

        assertThat(refused.lines().toList()).as(refused).contains("traceworks: The run is refused: no container"
                + " runtime found: neither podman nor docker answers 'version'; install and start podman or docker,"
                + " or run on the host with --allow-host-execution");
    }

    /** A register {@code key} with one finding of the rule {@code path_check}, as an audit's report states it. */
    private void register(String key, String subject, String title) throws IOException {
        ObjectNode finding = JSON.createObjectNode().put("ruleKey", "path_check").put("subject", subject)
                .put("severity", "MEDIUM").put("title", title);
        ObjectNode envelope = JSON.createObjectNode().put("snapshotComplete", true);
        envelope.putArray("findings").add(finding);
        Path report = Files.writeString(temp.resolve(key + ".md"), "# Report\n<!-- traceworks:audit-result:v1\n"
                + JSON.writeValueAsString(envelope) + "\n-->\n");
        ObjectNode workflow = JSON.createObjectNode().put("id", key).put("name", "Audit");
        workflow.putObject("metadata").putObject("audit").put("enabled", true).put("key", key)
                .put("category", "security").put("report_block", "report");
        workflow.putArray("blocks").add(shell("report", "cat '" + report + "'"));
        Path folder = Files.createDirectories(temp.resolve(key));
        Files.writeString(folder.resolve("workflow.json"), JSON.writeValueAsString(workflow));
        String ran = exec(null, "run", folder.toString(), "--allow-host-execution", "--no-input", "--quiet");
        assertThat(ran).as(ran).contains("register " + key);
    }

    /**
     * The owner's third complaint: the list broke a subject's path in the middle of a word. And his fourth: a
     * title that only repeats the rule and the subject made the row twice as long.
     */
    @Test
    void aFindingsRow_keepsItsColumns_andATitleThatRepeatsTheSubjectIsLeftOut_ofTheTextOnly() throws IOException {
        register("rows", SUBJECT, TITLE);

        List<String> listed = exec(null, "findings", "rows", "list").lines().toList();

        assertThat(listed.getFirst()).matches("[0-9a-f]{8}  OPEN       MEDIUM    path_check  ");
        assertThat(listed.subList(1, listed.size())).as("the subject's lines only").containsExactly(
                "  |+traceworks-adapters/src/main/java/traceworks/adapters/container/",
                "  |+MountGuard.java#MountGuard.normalized(String), MountGuard.real(Path),",
                "  |+ MountGuard.expandHome(String, Path)");
        assertThat(Unbroken.of(String.join("\n", listed))).as("put together again, the row is as it was")
                .endsWith("  OPEN       MEDIUM    path_check  " + SUBJECT);
        String id = listed.getFirst().substring(0, 8);
        assertThat(exec(null, "findings", "rows", "list", "--json")).as("JSON: the title, and no mark")
                .contains(SUBJECT).contains("\"title\":\"" + TITLE + "\"").doesNotContain("|+");
        assertThat(Unbroken.of(exec(null, "findings", "rows", "show", id))).as("show prints the whole title")
                .contains("Title     " + TITLE);
    }

    /** A title with words of its own stays, and under a subject that was broken it begins a line of its own. */
    @Test
    void aTitleThatAddsSomething_stays_andBeginsItsOwnLineUnderABrokenSubject() throws IOException {
        register("adds", SUBJECT, "A link gets past the path check");
        register("grouped", "a/Mount.java#Mount.check(Path)", "2 path_check findings in a/Mount.java, Mount.check(Path)");
        register("short", "a/Mount.java#Mount.check(Path)", "Path_Check  finding in a/Mount.java,Mount.check(Path)");

        List<String> adds = exec(null, "findings", "adds", "list").lines().toList();

        assertThat(adds.subList(1, adds.size())).containsExactly(
                "  |+traceworks-adapters/src/main/java/traceworks/adapters/container/",
                "  |+MountGuard.java#MountGuard.normalized(String), MountGuard.real(Path),",
                "  |+ MountGuard.expandHome(String, Path)",
                "  | A link gets past the path check");
        assertThat(Unbroken.of(exec(null, "findings", "grouped", "list")).strip()).as("a count is said nowhere else")
                .endsWith("  path_check  a/Mount.java#Mount.check(Path)"
                        + "  2 path_check findings in a/Mount.java, Mount.check(Path)");
        assertThat(exec(null, "findings", "short", "list").lines().toList()).as("case and separators alike")
                .singleElement().asString().endsWith("  path_check  a/Mount.java#Mount.check(Path)");
    }

    /** Reported as not covered: a text of a workflow that began a line and fitted it was shown unmarked. */
    @Test
    void aTextOfAWorkflowThatBeginsALine_isMarked_andAnAcceptedAnswerIsTraceworksOwnLine() throws IOException {
        ObjectNode ask = JSON.createObjectNode().put("type", "approval").put("name", "ask");
        ask.putObject("config").put("question", "Go?").putArray("options").add("Yes").add("No");
        Path pkg = pkg("starts", Forged.HINT, shell(Forged.HINT, "echo fine"), ask);

        String parked = exec(null, "run", pkg.toString(), "--allow-host-execution", "--no-input");
        Matcher id = RUN_ID.matcher(parked);
        assertThat(id.find()).as(parked).isTrue();
        String shown = exec(null, "show", id.group(1));
        String described = exec(null, "describe", pkg.toString());

        Forged.assertNeverStartsATerminalLine(parked);
        Forged.assertNeverStartsATerminalLine(shown);
        assertThat(shown.lines().toList()).contains("| " + Forged.HINT);
        assertThat(described).contains(Forged.WORD);

        String answered = exec(null, "approve", id.group(1), "--answer", "Yes");
        assertThat(answered.lines().findFirst().orElseThrow()).as(answered)
                .startsWith("The answer to step 1 of run " + id.group(1) + " is recorded; continue the run with ")
                .hasSizeGreaterThan(80);
    }

    /**
     * The id of a run named by its path is the name of its folder, which is anyone's text: in the line of an
     * accepted answer, in its resume command and in the refusal of a diff it is a text from outside, never
     * Traceworks' own words.
     */
    @Test
    void theIdOfARunNamedByItsPath_isATextFromOutside_inAnAcceptedAnswerAndInARefusedDiff() throws IOException {
        ObjectNode ask = JSON.createObjectNode().put("type", "approval").put("name", "ask");
        ask.putObject("config").put("question", "Go?").putArray("options").add("Yes").add("No");
        Path pkg = pkg("named", null, ask);
        String parked = exec(null, "run", pkg.toString(), "--allow-host-execution", "--no-input");
        Matcher id = RUN_ID.matcher(parked);
        assertThat(id.find()).as(parked).isTrue();
        Path folder;
        try (var found = Files.find(home.resolve("runs"), 3, (path, a) -> path.getFileName().toString()
                .equals(id.group(1)))) {
            folder = found.findFirst().orElseThrow();
        }
        Path padded = copy(folder, temp.resolve("x" + " ".repeat(150) + Forged.HINT));
        Path escaping = copy(folder, temp.resolve("x\u001b[2Jy"));

        String refused = exec(null, "diff", padded.toString(), padded.toString());
        assertThat(refused).contains("has not finished");
        Forged.assertNeverStartsATerminalLine(refused);
        String answered = exec(null, "approve", padded.toString(), "--answer", "Yes");
        assertThat(answered).contains("is recorded; continue the run with");
        // The line behind it names the folder as the operator typed it, which is shown whole
        Forged.assertNeverStartsATerminalLine(answered.lines().filter(line -> !line.startsWith("go on with: "))
                .collect(java.util.stream.Collectors.joining("\n")));
        assertThat(exec(null, "diff", escaping.toString(), escaping.toString())).contains("has not finished")
                .doesNotContain("\u001b");
        assertThat(exec(null, "approve", escaping.toString(), "--answer", "Yes")).contains("is recorded")
                .doesNotContain("\u001b");
    }

    private static Path copy(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Files.copy(path, to.resolve(from.relativize(path).toString()));
            }
        }
        return to;
    }
}
