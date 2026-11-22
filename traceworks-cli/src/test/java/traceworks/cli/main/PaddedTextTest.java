package traceworks.cli.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.cli.Forged;
import traceworks.cli.RuntimeSource;

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
 * A text of a workflow, a step or a report that is padded to the terminal's width never starts a terminal line
 * without a mark, in whatever a command prints of it: the hint in it would read as a line of Traceworks.
 */
class PaddedTextTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");

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

    private String run(Path pkg, String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--project", "shop",
                "--allow-host-execution", "--no-input"));
        args.addAll(List.of(more));
        return exec(null, args.toArray(String[]::new));
    }

    private static String idOf(String out) {
        Matcher m = RUN_ID.matcher(out);
        assertThat(m.find()).as(out).isTrue();
        return m.group(1);
    }

    private Path pkg(String dir, String name, String description, ObjectNode... blocks) throws IOException {
        ObjectNode workflow = JSON.createObjectNode().put("id", dir).put("name", name);
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

    @Test
    void theNamesOfAWorkflowAndItsBlocks_inRunShowRunsDescribeAndCheck() throws IOException {
        int n = 0;
        for (String padded : Forged.PADDED) {
            Path pkg = pkg("names" + n++, "N " + padded, "D " + padded, shell("B " + padded, "echo fine"));

            String ran = run(pkg);
            String id = idOf(ran);

            Forged.assertNeverStartsATerminalLine(ran);
            Forged.assertNeverStartsATerminalLine(exec(null, "show", id));
            Forged.assertNeverStartsATerminalLine(exec(null, "runs"));
            Forged.assertNeverStartsATerminalLine(exec(null, "runs", "--long"));
            Forged.assertNeverStartsATerminalLine(exec(null, "describe", pkg.toString()));
            Forged.assertNeverStartsATerminalLine(exec(null, "watch", id));
        }
    }

    @Test
    void theOutputOfAStep_itsGateAndItsError_inRunAndShow() throws IOException {
        int n = 0;
        for (String padded : Forged.PADDED) {
            Path pkg = pkg("out" + n++, "out", null,
                    shell("says", "printf '%s\\n' 'O " + padded + "' 'gate_status: YELLOW' 'gate_category: C "
                            + padded + "' 'gate_description: G " + padded + "'"),
                    shell("fails", "printf '%s\\n' 'E " + padded + "' >&2; exit 3"));

            String ran = run(pkg);
            String id = idOf(ran);

            assertThat(ran).contains("FAILED");
            Forged.assertNeverStartsATerminalLine(ran);
            Forged.assertNeverStartsATerminalLine(exec(null, "show", id));
            Forged.assertNeverStartsATerminalLine(exec(null, "show", id, "--outputs"));
            Forged.assertNeverStartsATerminalLine(exec(null, "show", id, "--full"));
        }
    }

    @Test
    void theQuestionOfAParkedRun_inItsSummary_andTheRefusalOfAnAnswer() throws IOException {
        int n = 0;
        for (String padded : Forged.PADDED) {
            ObjectNode ask = JSON.createObjectNode().put("type", "approval").put("name", "ask");
            ask.putObject("config").put("question", "Q " + padded).putArray("options").add("Yes").add("No " + padded);
            Path pkg = pkg("ask" + n++, "ask", null, ask);

            String parked = run(pkg);
            String id = idOf(parked);

            Forged.assertNeverStartsATerminalLine(parked);
            Forged.assertNeverStartsATerminalLine(exec(null, "approve", id, "--answer", "Maybe"));
            Forged.assertNeverStartsATerminalLine(exec(null, "show", id));
            Forged.assertNeverStartsATerminalLine(exec("Yes\n", "run", pkg.toString(), "--allow-host-execution"));
        }
    }

    @Test
    void anInputAndAnArgument_thatACommandRefuses() throws IOException {
        Path pkg = pkg("plain", "plain", null, shell("say", "echo hello"));
        for (String padded : Forged.PADDED) {
            Forged.assertNeverStartsATerminalLine(exec(null, "show", "no-" + padded));
            Forged.assertNeverStartsATerminalLine(exec(null, "run", pkg.toString(), "--allow-host-execution",
                    "--no-input", "--input", "who " + padded));
        }
    }

    @Test
    void theFindingsOfAReport_inTheSummaryTheListAndTheFinding() throws IOException {
        int n = 0;
        for (String padded : Forged.PADDED) {
            String key = "pad" + n++;
            ObjectNode finding = JSON.createObjectNode().put("ruleKey", "R " + padded).put("subject", "S " + padded)
                    .put("severity", "MEDIUM").put("title", "T " + padded).put("description", "D " + padded)
                    .put("evidence", "V " + padded);
            finding.putArray("tags").add("G " + padded);
            ObjectNode envelope = JSON.createObjectNode().put("snapshotComplete", true);
            envelope.putArray("findings").add(finding);
            Path report = Files.writeString(temp.resolve(key + ".md"), "# Report\n<!-- traceworks:audit-result:v1\n"
                    + JSON.writeValueAsString(envelope) + "\n-->\n");
            ObjectNode workflow = JSON.createObjectNode().put("id", key).put("name", "Audit");
            workflow.putObject("metadata").putObject("audit").put("enabled", true).put("key", key)
                    .put("category", "C " + padded).put("report_block", "report");
            workflow.putArray("blocks").add(shell("report", "cat '" + report + "'"));
            Path folder = Files.createDirectories(temp.resolve(key));
            Files.writeString(folder.resolve("workflow.json"), JSON.writeValueAsString(workflow));

            String ran = run(folder, "--quiet");
            assertThat(ran).as(ran).contains("register " + key);
            String listed = exec(null, "findings", key, "list");
            String id = listed.substring(0, 8);

            Forged.assertNeverStartsATerminalLine(listed);
            Forged.assertNeverStartsATerminalLine(exec(null, "findings", key, "show", id));
            Forged.assertNeverStartsATerminalLine(exec(null, "findings", key, "dismiss", id, "--note",
                    "N " + padded) + exec(null, "findings", key, "show", id));
        }
    }

    @Test
    void theStepsOfTwoRuns_inTheirDiff() throws IOException {
        int n = 0;
        for (String padded : Forged.PADDED) {
            Path pkg = pkg("diff" + n++, "N " + padded, null,
                    shell("B " + padded, "printf '%s\\n' \"$RANDOM$RANDOM O " + padded + "\""));
            String a = idOf(run(pkg, "--quiet"));
            String b = idOf(run(pkg, "--quiet", "--input", "who=W " + padded));

            String diff = exec(null, "diff", a, b);

            assertThat(diff).contains("differs");
            Forged.assertNeverStartsATerminalLine(diff);
        }
    }
}
