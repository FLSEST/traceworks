package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static traceworks.it.McpClient.JSON;
import static traceworks.it.McpClient.text;

/**
 * {@code traceworks mcp}: the jar as the child process of a client. A client starts a run whose steps run in
 * containers, answers the question it parks at, resumes it and reads its record; a server without a switch only
 * reads; a client that goes away mid-run leaves a run the command line goes on with; and a client runs an audit
 * for the project the operator bound to the server's folder and reads and decides in the register of that project.
 */
class McpIT extends IntegrationTest {

    /** The home allows what the servers of these tests are started to change; the switches then open it. */
    @BeforeEach
    void theHomeAllowsRunsAndAnswers() {
        traceworks.configure(Map.of("mcp", Map.of("allow", List.of("run", "answer"))));
    }

    private String packages() {
        return traceworks.packageDir("approval").getParent().toString();
    }

    private static ObjectNode run(String id) {
        return JSON.createObjectNode().put("run", id);
    }

    /** Asks {@code show} until the run has the status, as a client follows a run. */
    private static JsonNode showUntil(McpClient mcp, String id, String status) throws InterruptedException {
        JsonNode shown = null;
        for (int i = 0; i < 1200; i++) {
            shown = mcp.call("show", run(id));
            if (status.equals(shown.path("structuredContent").path("status").asText())) {
                return shown;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("run " + id + " did not become " + status + ": " + shown);
    }

    @Test
    @Timeout(600)
    void aClient_startsARunInContainers_answersItsQuestion_resumesIt_andReadsTheRecord() throws Exception {
        try (McpClient mcp = traceworks.mcp("--allow-run", "--allow-answer", "--packages", packages())
                .initialize("it-client")) {
            JsonNode started = mcp.call("run", JSON.createObjectNode()
                    .put("package", traceworks.packageDir("approval").toString()));
            assertThat(started.path("isError").asBoolean()).as(text(started) + mcp.stderr()).isFalse();
            String id = started.path("structuredContent").path("run_id").asText();

            JsonNode parked = showUntil(mcp, id, "PARKED");
            JsonNode question = parked.path("structuredContent").path("question");
            assertThat(question.path("kind").asText()).isEqualTo("approval");
            assertThat(question.path("options")).extracting(JsonNode::asText).containsExactly("Yes", "No");
            assertThat(question.path("answer_with").asText()).isEqualTo("approve");

            JsonNode approved = mcp.call("approve", run(id).put("answer", "Yes"));
            assertThat(approved.path("isError").asBoolean()).as(text(approved)).isFalse();
            JsonNode resumed = mcp.call("resume", run(id));
            assertThat(resumed.path("isError").asBoolean()).as(text(resumed)).isFalse();
            JsonNode done = showUntil(mcp, id, "COMPLETED");
            JsonNode events = mcp.call("events", run(id).put("limit", 1));
            JsonNode verified = mcp.call("verify", run(id));

            assertThat(text(done)).startsWith("[traceworks] ").contains("released");
            assertThat(events.path("structuredContent").path("events").path(0).path("type").asText())
                    .isEqualTo("run.started");
            assertThat(verified.path("structuredContent").path("exit_code").asInt()).as(text(verified)).isZero();
            assertThat(mcp.end(120)).as(mcp.stderr()).isZero();

            RunFolder folder = traceworks.folder(id);
            JsonNode start = folder.only("run.started");
            assertThat(start.path("actor").asText()).isEqualTo(Traceworks.ACTOR);
            assertThat(start.path("agent").asText()).isEqualTo("it-client");
            assertThat(start.path("execution").asText()).isEqualTo("container");
            JsonNode answered = folder.ownEvents("approval.answered").getFirst();
            assertThat(answered.path("actor").asText()).isEqualTo(Traceworks.ACTOR);
            assertThat(answered.path("agent").asText()).isEqualTo("it-client");
            assertThat(folder.only("run.resumed").path("agent").asText()).isEqualTo("it-client");
            assertThat(folder.output("released")).isEqualTo("released after Yes");
            assertThat(folder.manifest().path("status").asText()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @Timeout(600)
    void aServerWithoutASwitch_readsWhatTheCommandLineLeft_andChangesNothing() {
        String id = traceworks.run("approval").runId();
        try (McpClient mcp = traceworks.mcp("--packages", packages()).initialize("it-reader")) {
            List<String> tools = new ArrayList<>();
            mcp.request("tools/list", null).path("result").path("tools")
                    .forEach(tool -> tools.add(tool.path("name").asText()));
            JsonNode shown = mcp.call("show", run(id));
            JsonNode described = mcp.call("describe", JSON.createObjectNode()
                    .put("package", traceworks.packageDir("approval").toString()));
            JsonNode refused = mcp.request("tools/call", JSON.createObjectNode().put("name", "approve")
                    .set("arguments", run(id).put("answer", "Yes")));

            assertThat(tools).contains("runs", "show", "events", "verify", "describe")
                    .doesNotContain("run", "resume", "cancel", "approve", "answer", "findings_decide");
            assertThat(shown.path("structuredContent").path("status").asText()).isEqualTo("PARKED");
            assertThat(shown.path("structuredContent").path("question").path("kind").asText()).isEqualTo("approval");
            assertThat(text(described)).contains("prepare", "release");
            assertThat(refused.path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(refused.path("error").path("message").asText()).contains("--allow-answer");
            assertThat(mcp.end(60)).as(mcp.stderr()).isZero();
        }
        assertThat(traceworks.folder(id).ownEvents("approval.answered")).isEmpty();
    }

    /** The run of a server ends with it; what it leaves is a parked run the command line takes over. */
    @Test
    @Timeout(600)
    void aClientThatGoesAwayMidRun_leavesAParkedRun_thatTheCommandLineTakesOver() throws Exception {
        String id;
        try (McpClient mcp = traceworks.mcp("--allow-run", "--packages", packages()).initialize("it-client")) {
            JsonNode started = mcp.call("run", JSON.createObjectNode()
                    .put("package", traceworks.packageDir("approval").toString()));
            id = started.path("structuredContent").path("run_id").asText();
            assertThat(id).as(text(started) + mcp.stderr()).hasSize(36);

            assertThat(mcp.end(180)).as(mcp.stderr()).isZero();
        }

        RunFolder folder = traceworks.folder(id);
        assertThat(folder.types()).as("the run parked: at its question, or before it after the step in flight")
                .contains("run.parked").doesNotContain("run.finished");
        Result cancelled = traceworks.exec("cancel", id);
        assertThat(cancelled.code()).as(cancelled.describe()).isZero();
        assertThat(folder.manifest().path("status").asText()).isEqualTo("CANCELLED");
    }

    /**
     * An audit through the server, its steps in containers ({@code examples/audit-hello}): while the folder of the
     * server is bound to no project the run is refused, also with a project the client names, and nothing is
     * written; once the operator bound the folder, the run feeds the register of that project, and the tools on a
     * register read and decide in it and in no other.
     */
    @Test
    @Timeout(600)
    void aClient_runsAnAuditForAProject_andReadsAndDecidesInTheRegisterOfThatProject() throws Exception {
        traceworks.configure(Map.of("mcp", Map.of("allow", List.of("run", "decide"))));
        Files.writeString(traceworks.workspace().resolve("a.txt"), "line\n".repeat(12), StandardCharsets.UTF_8);
        Path audit = traceworks.examplePackage("audit-hello");
        String id;
        String finding;
        try (McpClient mcp = traceworks.mcp("--allow-run", "--allow-decide", "--packages",
                audit.getParent().toString()).initialize("it-client")) {
            JsonNode refused = mcp.call("run", JSON.createObjectNode().put("package", audit.toString()));
            assertThat(refused.path("isError").asBoolean()).as(text(refused) + mcp.stderr()).isTrue();
            assertThat(refused.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
            assertThat(text(refused)).contains("the register hello", "bound to no project");
            JsonNode named = mcp.call("run", JSON.createObjectNode().put("package", audit.toString())
                    .put("project", "shop"));
            assertThat(named.path("structuredContent").path("exit_code").asInt()).as(text(named)).isEqualTo(6);
            assertThat(traceworks.folders()).as("no run exists").isEmpty();
            assertThat(traceworks.home().resolve("ledger")).doesNotExist();
            assertThat(traceworks.home().resolve("registers")).doesNotExist();

            // The operator binds the folder; the server reads the setting at the next call
            traceworks.configure(Map.of("mcp", Map.of("allow", List.of("run", "decide")),
                    "projects", Map.of("shop", traceworks.workspace().toString())));
            JsonNode started = mcp.call("run", JSON.createObjectNode().put("package", audit.toString()));
            assertThat(started.path("isError").asBoolean()).as(text(started) + mcp.stderr()).isFalse();
            assertThat(started.path("structuredContent").path("project").asText()).isEqualTo("shop");
            id = started.path("structuredContent").path("run_id").asText();
            JsonNode done = showUntil(mcp, id, "COMPLETED");
            assertThat(done.path("structuredContent").path("project").asText()).isEqualTo("shop");
            assertThat(done.path("structuredContent").path("actor").asText()).isEqualTo(Traceworks.ACTOR);

            // The record ends before the run writes its manifest and feeds its register: a client asks again
            JsonNode registers = mcp.call("findings_registers", null);
            for (int i = 0; i < 600 && registers.path("structuredContent").path("registers").isEmpty(); i++) {
                Thread.sleep(100);
                registers = mcp.call("findings_registers", null);
            }
            assertThat(text(registers)).contains("hello  shop  example  OPEN 1");
            assertThat(registers.path("structuredContent").path("registers").path(0).path("register").asText())
                    .isEqualTo("hello");
            assertThat(registers.path("structuredContent").path("registers").path(0).path("projects"))
                    .extracting(JsonNode::asText).containsExactly("shop");
            JsonNode listed = mcp.call("findings_list", JSON.createObjectNode().put("register", "hello"));
            assertThat(listed.path("isError").asBoolean()).as(text(listed)).isFalse();
            assertThat(listed.path("structuredContent").path("findings")).hasSize(1);
            assertThat(listed.path("structuredContent").path("findings").path(0).path("subject").asText())
                    .isEqualTo("a.txt");
            finding = listed.path("structuredContent").path("findings").path(0).path("id").asText();
            JsonNode other = mcp.call("findings_list", JSON.createObjectNode().put("register", "hello")
                    .put("project", "blog"));
            assertThat(other.path("isError").asBoolean()).isTrue();
            assertThat(other.path("structuredContent").path("exit_code").asInt()).as(text(other)).isEqualTo(6);
            JsonNode dismissed = mcp.call("findings_decide", JSON.createObjectNode().put("register", "hello")
                    .put("id", finding).put("decision", "dismiss")
                    .put("note", "a fixture of the test"));
            assertThat(dismissed.path("isError").asBoolean()).as(text(dismissed)).isFalse();
            assertThat(text(dismissed)).contains("in the register hello of shop");
            JsonNode verified = mcp.call("findings_verify", JSON.createObjectNode().put("register", "hello"));
            assertThat(verified.path("structuredContent").path("exit_code").asInt()).as(text(verified)).isZero();
            assertThat(verified.path("structuredContent").path("verified").asBoolean()).isTrue();
            // A run of another project, started by the operator while the server runs, is none the client knows
            Path blog = Files.createDirectories(traceworks.workspace().resolveSibling("blog"));
            Result ofBlog = traceworks.exec("run", traceworks.packageDir("approval").toString(), "--workspace",
                    blog.toString(), "--no-input", "--project", "blog");
            assertThat(ofBlog.runId()).as(ofBlog.describe()).hasSize(36);
            JsonNode hidden = mcp.call("show", run(ofBlog.runId()));
            assertThat(hidden.path("isError").asBoolean()).as(text(hidden)).isTrue();
            assertThat(text(hidden)).contains("No run matches");
            assertThat(mcp.call("runs", null).toString()).contains(id).doesNotContain(ofBlog.runId());
            assertThat(mcp.end(120)).as(mcp.stderr()).isZero();
        }

        RunFolder folder = traceworks.folder(id);
        JsonNode start = folder.only("run.started");
        assertThat(start.path("project").asText()).isEqualTo("shop");
        assertThat(start.path("agent").asText()).isEqualTo("it-client");
        assertThat(start.path("execution").asText()).isEqualTo("container");
        assertThat(traceworks.home().resolve("registers/hello/shop/events.jsonl")).isRegularFile();
        Result shown = traceworks.exec("findings", "hello", "show", finding, "--project", "shop");
        assertThat(shown.code()).as(shown.describe()).isZero();
        assertThat(shown.out()).contains("DISMISSED", "through the agent it-client");
        Result register = traceworks.exec("findings", "hello", "verify", "--project", "shop");
        assertThat(register.code()).as(register.describe()).isZero();
    }
}
