package traceworks.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import traceworks.adapters.images.BuiltInImages;
import traceworks.cli.ExitCodes;
import traceworks.cli.RuntimeSource;
import traceworks.cli.main.Main;
import traceworks.core.app.Images;
import traceworks.core.testing.FakeContainerRuntime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static traceworks.cli.mcp.McpSession.JSON;
import static traceworks.cli.mcp.McpSession.args;
import static traceworks.cli.mcp.McpSession.text;

/**
 * {@code traceworks mcp} as a client meets it: the protocol of both eras, the tools a server offers by default and
 * behind each switch, and what a call leaves in the record. The server runs through {@code Main} with pipes; shell
 * steps run on the host of the test, in a temporary home. No container, no model.
 */
class McpServerTest {

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    private final Map<String, String> env = Map.of("TRACEWORKS_ACTOR", "tester");
    private final List<McpSession> sessions = new ArrayList<>();

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
        // The home opens every change; what a server offers is then up to its switches
        Files.writeString(home.resolve("traceworks.json"), McpSession.ALLOW_ALL);
    }

    @AfterEach
    void endTheServers() {
        sessions.forEach(McpSession::close);
    }

    private McpSession server(String... options) {
        McpSession session = new McpSession(home, work, env, McpSession.withPackages(work, options));
        sessions.add(session);
        return session;
    }

    /** A package below the working directory of the server, so the server may read it. */
    private Path pkg(String name, String blocks) throws IOException {
        Path dir = Files.createDirectories(work.resolve(name));
        Files.writeString(dir.resolve("workflow.json"),
                "{\"id\":\"" + name + "\",\"name\":\"" + name + " flow\",\"blocks\":[" + blocks + "]}");
        return dir;
    }

    private Path helloPackage() throws IOException {
        return pkg("hello", "{\"type\":\"shell\",\"name\":\"greet\",\"config\":{\"command\":\"echo hello\"}}");
    }

    private Path approvalPackage() throws IOException {
        return pkg("deploy", """
                {"type":"shell","name":"build","config":{"command":"echo built"}},
                {"type":"approval","name":"publish","config":{"question":"Publish it?","options":["Yes","No"]}},
                {"type":"shell","name":"done","config":{"command":"echo done"}}""");
    }

    /**
     * An audit below the working directory of the server: its one step reports two findings to the register
     * {@code hello}, whatever the workspace holds.
     */
    private Path auditPackage() throws IOException {
        String command = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"A.java\","
                + "\"severity\":\"MEDIUM\",\"title\":\"Big A\"},{\"ruleKey\":\"size\",\"subject\":\"B.java\","
                + "\"severity\":\"LOW\",\"title\":\"Big B\"}]}' '-->'";
        Path audit = Files.createDirectories(work.resolve("audit"));
        Files.writeString(audit.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + JSON.writeValueAsString(command) + "}}]}");
        return audit;
    }

    /** Runs the audit with the command line for {@code project}, in the working directory of the server. */
    private void auditRan(String project) throws IOException {
        cli(0, "run", auditPackage().toString(), "--allow-host-execution", "--no-input", "--quiet", "--project",
                project);
    }

    /** The home opens every change and declares projects: each key with the folder it is bound to. */
    private void declare(Map<String, Path> projects) throws IOException {
        ObjectNode settings = (ObjectNode) JSON.readTree(McpSession.ALLOW_ALL);
        ObjectNode declared = settings.putObject("projects");
        projects.forEach((key, folder) -> declared.put(key, folder.toString()));
        Files.writeString(home.resolve("traceworks.json"), settings.toString());
    }

    /** The id of the first finding the command line lists of the register {@code hello} of {@code project}. */
    private String firstFinding(String project) {
        return cli(0, "findings", "hello", "list", "--project", project).substring(0, 8);
    }

    /** As {@link #cli}, for what the command wrote to standard error. */
    private String cliErr(int expected, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8), env, work, null, RuntimeSource.NONE);
        assertThat(code).as(err.toString()).isEqualTo(expected);
        return err.toString(StandardCharsets.UTF_8);
    }

    private String cli(int expected, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env, work, null, RuntimeSource.NONE);
        assertThat(code).as(out + " " + err).isEqualTo(expected);
        return out.toString(StandardCharsets.UTF_8);
    }

    private List<String> recordLines(String runId, String type) throws IOException {
        try (Stream<Path> folders = Files.walk(home.resolve("runs"), 2)) {
            Path folder = folders.filter(p -> p.getFileName().toString().equals(runId)).findFirst().orElseThrow();
            return Files.readAllLines(folder.resolve("events.jsonl")).stream()
                    .filter(line -> line.contains("\"type\":\"" + type + "\"")).toList();
        }
    }

    private static List<String> toolNames(JsonNode listResponse) {
        List<String> names = new ArrayList<>();
        listResponse.path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    /** Polls {@code show} until the run has the status, as a client would. */
    private JsonNode showUntil(McpSession mcp, String runId, String status) throws InterruptedException {
        JsonNode shown = null;
        for (int i = 0; i < 600; i++) {
            shown = mcp.call("show", args("run", runId));
            if (status.equals(shown.path("structuredContent").path("status").asText())) {
                return shown;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("run " + runId + " did not become " + status + ": " + shown);
    }

    // ---- the protocol ----

    @Test
    void discover_namesTheStatelessRevision_theCapabilities_andTheServer() {
        McpSession mcp = server().modern("probe");

        JsonNode result = mcp.request("server/discover", null).path("result");

        assertThat(result.path("resultType").asText()).isEqualTo("complete");
        assertThat(result.path("supportedVersions")).extracting(JsonNode::asText).containsExactly("2026-07-28");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("capabilities").has("resources")).isTrue();
        assertThat(result.path("capabilities").has("prompts")).isTrue();
        assertThat(result.path("_meta").path("io.modelcontextprotocol/serverInfo").path("name").asText())
                .isEqualTo("traceworks");
        assertThat(result.path("instructions").asText()).contains("data", "never");
        assertThat(mcp.end()).isZero();
    }

    @Test
    void aStatelessRequest_needsNoHandshake_andItsResultSaysItIsComplete() {
        McpSession mcp = server().modern("stateless");

        JsonNode list = mcp.request("tools/list", null).path("result");

        assertThat(list.path("resultType").asText()).isEqualTo("complete");
        assertThat(list.path("tools")).isNotEmpty();
        assertThat(list.path("ttlMs").isNumber()).isTrue();
        assertThat(list.path("cacheScope").asText()).isEqualTo("private");
    }

    @Test
    void aStatelessRequest_ofAnotherRevision_isRefusedWithTheSupportedOnes() {
        McpSession mcp = server().modern("stateless");
        ObjectNode params = JSON.createObjectNode();
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", "1900-01-01");
        meta.putObject("io.modelcontextprotocol/clientCapabilities");

        JsonNode error = mcp.request("tools/list", params).path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32022);
        assertThat(error.path("data").path("supported")).extracting(JsonNode::asText).containsExactly("2026-07-28");
        assertThat(error.path("data").path("requested").asText()).isEqualTo("1900-01-01");
    }

    @Test
    void aStatelessRequest_withoutItsCapabilities_isInvalid() {
        McpSession mcp = server();
        ObjectNode params = JSON.createObjectNode();
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion", "2026-07-28");

        assertThat(mcp.request("tools/list", params).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void theHandshake_ofAnEarlierRevision_isAnswered_andItsResultsCarryNoFieldsOfTheNewOne() {
        McpSession mcp = server();

        JsonNode initialized = mcp.initialize("2025-06-18", "legacy-client");
        JsonNode list = mcp.request("tools/list", null).path("result");

        assertThat(initialized.path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(initialized.path("serverInfo").path("name").asText()).isEqualTo("traceworks");
        assertThat(initialized.path("capabilities").has("tools")).isTrue();
        assertThat(initialized.path("instructions").asText()).isNotBlank();
        assertThat(list.has("resultType")).isFalse();
        assertThat(list.path("tools")).isNotEmpty();
        assertThat(mcp.request("ping", null).path("result").isObject()).isTrue();
    }

    @Test
    void theHandshake_withARevisionTheServerDoesNotKnow_getsTheNewestOfTheEarlierOnes() {
        McpSession mcp = server();

        assertThat(mcp.initialize("2030-01-01", "from-the-future").path("protocolVersion").asText())
                .isEqualTo("2025-11-25");
    }

    @Test
    void aRequestBeforeAnyHandshake_withoutTheMetadata_isRefused() {
        McpSession mcp = server();

        JsonNode error = mcp.request("tools/list", null).path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32600);
        assertThat(error.path("message").asText()).contains("initialize", "2026-07-28");
    }

    @Test
    void aLineThatIsNoJson_anUnknownMethod_andABatch_areProtocolErrors_andTheServerGoesOn() {
        McpSession mcp = server().modern("odd");

        mcp.send("{ this is no json");
        JsonNode parse = mcp.read();
        mcp.send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        JsonNode batch = mcp.read();
        mcp.send("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}");
        JsonNode nullId = mcp.read();
        JsonNode unknown = mcp.request("tools/explode", null);
        mcp.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/unknown\"}");   // a notification gets no answer

        assertThat(parse.path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(parse.path("id").isNull()).isTrue();
        assertThat(batch.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(nullId.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(mcp.request("tools/list", null).path("result").path("tools")).isNotEmpty();
        assertThat(mcp.end()).isZero();
        assertThat(mcp.stderr()).doesNotContain("Exception");
    }

    @Test
    void anUnknownTool_isAProtocolError_andAWrongArgument_isAToolErrorTheModelCanRead() {
        McpSession mcp = server().modern("odd");
        ObjectNode call = JSON.createObjectNode();
        call.put("name", "explode");

        JsonNode unknown = mcp.request("tools/call", call);
        JsonNode missing = mcp.call("show", null);
        JsonNode extra = mcp.call("show", args("run", "abcd1234", "folder", "/etc"));
        JsonNode asPath = mcp.call("show", args("run", "../../etc"));

        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(text(missing)).contains("run");
        assertThat(extra.path("isError").asBoolean()).isTrue();
        assertThat(text(extra)).contains("folder");
        assertThat(asPath.path("isError").asBoolean()).isTrue();
    }

    @Test
    void aCancelledRequest_thatHasNotBegun_getsNoResponse() throws IOException {
        McpSession mcp = server().modern("impatient");
        String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}";
        // One write, so the reader sees the cancel before the worker can begin the second request
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            lines.append("{\"jsonrpc\":\"2.0\",\"id\":\"busy-").append(i)
                    .append("\",\"method\":\"tools/call\",\"params\":{\"name\":\"runs\",\"arguments\":{},")
                    .append(meta).append("}}\n");
        }
        lines.append("{\"jsonrpc\":\"2.0\",\"id\":\"dropped\",\"method\":\"tools/list\",\"params\":{").append(meta)
                .append("}}\n");
        lines.append("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":"
                + "\"dropped\",\"reason\":\"changed my mind\"}}\n");
        lines.append("{\"jsonrpc\":\"2.0\",\"id\":\"last\",\"method\":\"tools/list\",\"params\":{").append(meta)
                .append("}}\n");
        mcp.rawInput().write(lines.toString().getBytes(StandardCharsets.UTF_8));
        mcp.rawInput().flush();

        List<String> ids = new ArrayList<>();
        while (!ids.contains("last")) {
            ids.add(mcp.read().path("id").asText());
        }

        assertThat(ids).hasSize(51).doesNotContain("dropped");
    }

    /**
     * One worker answers the requests in their order, and a start waits until its run exists, which can take as long
     * as the container runtime takes to answer. A cancelled start is not waited for: its response is not sent, the
     * next request is served, and the run starts all the same, since a cancelled call is no cancelled run.
     */
    @Test
    @Timeout(60)
    void aCancelledStart_thatStillWaits_isNotWaitedFor_andTheNextRequestIsServed() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeContainerRuntime runtime = new FakeContainerRuntime()
                .present(builtInTag("shell"), "sha256:" + "5".repeat(64))
                .duringLookup(() -> {
                    asked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        Path hello = helloPackage();
        McpSession mcp = new McpSession(home, work, env, RuntimeSource.of(runtime), "--allow-run", "--packages", work.toString())
                .modern("claude-code");
        sessions.add(mcp);
        String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{},\"io.modelcontextprotocol/clientInfo\":"
                + "{\"name\":\"claude-code\",\"version\":\"1\"}}";
        try {
            mcp.send("{\"jsonrpc\":\"2.0\",\"id\":\"start\",\"method\":\"tools/call\",\"params\":{\"name\":\"run\","
                    + "\"arguments\":{\"package\":" + JSON.writeValueAsString(hello.toString()) + "}," + meta + "}}");
            assertThat(asked.await(20, TimeUnit.SECONDS)).as("the start asks the runtime").isTrue();

            mcp.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":"
                    + "\"start\"}}");
            mcp.send("{\"jsonrpc\":\"2.0\",\"id\":\"next\",\"method\":\"tools/list\",\"params\":{" + meta + "}}");

            assertThat(mcp.read().path("id").asText()).isEqualTo("next");
        } finally {
            release.countDown();
        }
        assertThat(mcp.end()).isZero();
        assertThat(cli(0, "runs")).as("the run started all the same").contains("hello flow");
    }

    private static String builtInTag(String name) {
        return Images.builtInTag(new BuiltInImages().all().stream().filter(image -> image.name().equals(name))
                .findFirst().orElseThrow());
    }

    // ---- limits ----

    @Test
    void aClientThatChangesTooOften_isRefused_andTheRefusalChangesNothing() throws IOException {
        McpSession mcp = server("--allow-decide").modern("claude-code");
        ObjectNode decision = args("register", "none", "id", "abcd1234", "decision", "reopen");
        for (int i = 0; i < CallLimits.CHANGES_PER_MINUTE; i++) {
            assertThat(text(mcp.call("findings_decide", decision))).doesNotContain("at most");
        }

        JsonNode refused = mcp.call("findings_decide", decision);
        JsonNode read = mcp.call("runs", null);

        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).contains("at most " + CallLimits.CHANGES_PER_MINUTE);
        assertThat(read.path("isError").asBoolean()).as(text(read)).isFalse();
    }

    @Test
    void theServerExecutesAFewRunsAtATime_andRefusesTheStartOfOneMore() throws Exception {
        Path asking = pkg("asking", """
                {"type":"sub-workflow","name":"child","config":{"workflow_id":"./inner"}}""");
        pkg("asking/inner", """
                {"type":"approval","name":"publish","config":{"question":"Publish it?","options":["Yes","No"]}}""");
        McpSession mcp = server("--allow-run", "--allow-host-execution").modern("claude-code");
        for (int i = 0; i < McpTools.MAX_RUNS_AT_A_TIME; i++) {
            JsonNode started = mcp.call("run", args("package", asking.toString()));
            assertThat(started.path("structuredContent").path("status").asText()).as(text(started))
                    .isEqualTo("RUNNING");
        }

        JsonNode refused = mcp.call("run", args("package", asking.toString()));

        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).contains("executes " + McpTools.MAX_RUNS_AT_A_TIME + " runs", "show");
        assertThat(cli(0, "runs").lines().filter(line -> line.contains("asking flow")))
                .hasSize(McpTools.MAX_RUNS_AT_A_TIME);
        assertThat(mcp.end()).isZero();
    }

    // ---- what a server offers ----

    @Test
    void byDefault_theServerOffersOnlyToolsThatRead_eachMarkedSo() {
        McpSession mcp = server().modern("reader");

        JsonNode list = mcp.request("tools/list", null);

        assertThat(toolNames(list)).containsExactly("guide", "examples", "describe", "check", "runs", "show",
                "events", "verify", "diff", "findings_registers", "findings_list", "findings_show", "findings_items",
                "findings_verify");
        assertThat(list.path("result").path("tools")).allSatisfy(tool -> {
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).as(tool.toString()).isTrue();
            assertThat(tool.path("description").asText().length()).isGreaterThan(40);
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
            assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean(true)).isFalse();
        });
    }

    @Test
    void eachSwitch_addsItsTools_andNoOther() {
        assertThat(toolNames(server("--allow-run").modern("a").request("tools/list", null)))
                .contains("run", "resume", "pause", "cancel")
                .doesNotContain("approve", "answer", "findings_decide");
        assertThat(toolNames(server("--allow-answer").modern("a").request("tools/list", null)))
                .contains("approve", "answer").doesNotContain("run", "cancel", "findings_decide");
        JsonNode deciding = server("--allow-decide").modern("a").request("tools/list", null);
        assertThat(toolNames(deciding)).contains("findings_decide", "findings_assign")
                .doesNotContain("run", "approve");
        assertThat(deciding.path("result").path("tools")).filteredOn(t -> t.path("name").asText()
                .equals("findings_decide")).allSatisfy(tool ->
                assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isFalse());
    }

    @Test
    void aServerThatOnlyReads_refusesEveryToolThatChanges_andNothingIsStarted() throws IOException {
        Path hello = helloPackage();
        McpSession mcp = server().modern("reader");

        for (String tool : List.of("run", "resume", "pause", "cancel", "approve", "answer", "findings_decide")) {
            ObjectNode call = JSON.createObjectNode();
            call.put("name", tool);
            call.set("arguments", args("package", hello.toString()));
            JsonNode error = mcp.request("tools/call", call).path("error");
            assertThat(error.path("code").asInt()).as(tool).isEqualTo(-32602);
            assertThat(error.path("message").asText()).as(tool).contains("--allow-");
        }

        assertThat(home.resolve("runs")).doesNotExist();
    }

    // ---- the tools that read ----

    @Test
    void describe_andCheck_readAPackage_belowThePackageFolderOnly() throws IOException {
        Path hello = helloPackage();
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.copy(hello.resolve("workflow.json"), outside.resolve("workflow.json"));
        McpSession mcp = server().modern("reader");

        JsonNode described = mcp.call("describe", args("package", "hello"));
        JsonNode checked = mcp.call("check", args("package", hello.toString()));
        JsonNode refused = mcp.call("describe", args("package", outside.toString()));
        JsonNode escaped = mcp.call("check", args("package", "../outside"));

        assertThat(described.path("isError").asBoolean()).as(text(described)).isFalse();
        assertThat(text(described)).contains("greet").startsWith("[traceworks] ");
        assertThat(text(described).lines().findFirst().orElseThrow()).contains("data", "never");
        assertThat(checked.path("structuredContent").path("exit_code").asInt()).isZero();
        assertThat(checked.path("structuredContent").path("findings").isArray()).isTrue();
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).contains("--packages");
        assertThat(escaped.path("isError").asBoolean()).isTrue();
    }

    @Test
    void runsWithoutAPackageFolderOfTheOperators_startNoServer() {
        McpSession mcp = new McpSession(home, work, env, "--allow-run");
        sessions.add(mcp);
        McpSession reader = new McpSession(home, work, env);
        sessions.add(reader);

        assertThat(mcp.end()).isEqualTo(ExitCodes.USAGE);
        assertThat(mcp.stderr()).contains("--allow-run needs --packages");
        // A server that only reads keeps the working directory as its package folder
        assertThat(toolNames(reader.modern("reader").request("tools/list", null))).contains("describe");
    }

    @Test
    void aSwitchTheHomeDoesNotAllow_startsNoServer_andTheRefusalNamesTheLineToAdd() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"mcp\": {\"allow\": [\"run\"]}}");
        McpSession answering = server("--allow-run", "--allow-answer");
        int answeringExit = answering.end();
        Files.delete(home.resolve("traceworks.json"));
        McpSession without = server("--allow-decide");

        assertThat(answeringExit).isEqualTo(ExitCodes.REFUSED);
        assertThat(answering.stderr()).contains("--allow-answer", "\"mcp\": {\"allow\": [\"answer\"]}",
                home.resolve("traceworks.json").toString()).doesNotContain("--allow-run");
        assertThat(without.end()).isEqualTo(ExitCodes.REFUSED);
        assertThat(without.stderr()).contains("--allow-decide", "\"mcp\": {\"allow\": [\"decide\"]}");
    }

    @Test
    void whatTheHomeAllows_isOfferedOnlyWithItsSwitch_andReadingNeedsNoSetting() throws IOException {
        List<String> withTheSetting = toolNames(server().modern("reader").request("tools/list", null));
        Files.delete(home.resolve("traceworks.json"));
        List<String> without = toolNames(server().modern("reader").request("tools/list", null));

        assertThat(withTheSetting).contains("runs").doesNotContain("run", "approve", "findings_decide");
        assertThat(without).isEqualTo(withTheSetting);
    }

    @Test
    void runs_show_events_verify_andDiff_readWhatTheCommandLineLeft() throws IOException {
        Path hello = helloPackage();
        cli(0, "run", hello.toString(), "--allow-host-execution", "--no-input", "--quiet");
        cli(0, "run", hello.toString(), "--allow-host-execution", "--no-input", "--quiet");
        McpSession mcp = server().modern("reader");

        JsonNode firstPage = mcp.call("runs", JSON.createObjectNode().put("limit", 1));
        String newest = firstPage.path("structuredContent").path("runs").path(0).path("run_id").asText();
        JsonNode secondPage = mcp.call("runs", JSON.createObjectNode().put("limit", 1)
                .put("cursor", firstPage.path("structuredContent").path("next_cursor").asText()));
        String oldest = secondPage.path("structuredContent").path("runs").path(0).path("run_id").asText();
        JsonNode shown = mcp.call("show", JSON.createObjectNode().put("run", newest.substring(0, 8))
                .put("outputs", true));
        JsonNode events = mcp.call("events", JSON.createObjectNode().put("run", newest).put("limit", 2));
        JsonNode rest = mcp.call("events", JSON.createObjectNode().put("run", newest)
                .put("after_seq", events.path("structuredContent").path("next_after_seq").asInt()));
        JsonNode verified = mcp.call("verify", args("run", newest));
        JsonNode home = mcp.call("verify", null);
        JsonNode quick = mcp.call("verify", JSON.createObjectNode().put("quick", true));
        JsonNode diff = mcp.call("diff", args("run_a", oldest, "run_b", newest));
        JsonNode unknown = mcp.call("show", args("run", "00000000"));

        assertThat(firstPage.path("structuredContent").path("runs")).hasSize(1);
        assertThat(newest).hasSize(36).isNotEqualTo(oldest);
        assertThat(secondPage.path("structuredContent").has("next_cursor")).isFalse();
        assertThat(shown.path("structuredContent").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(shown.path("structuredContent").path("run_id").asText()).isEqualTo(newest);
        assertThat(text(shown)).contains("greet", "hello");
        assertThat(events.path("structuredContent").path("events")).hasSize(2);
        assertThat(events.path("structuredContent").path("events").path(0).path("type").asText())
                .isEqualTo("run.started");
        assertThat(rest.path("structuredContent").path("events")).isNotEmpty();
        assertThat(rest.path("structuredContent").path("events").path(0).path("seq").asInt())
                .isEqualTo(events.path("structuredContent").path("next_after_seq").asInt() + 1);
        assertThat(rest.path("structuredContent").has("next_after_seq")).isFalse();
        assertThat(verified.path("structuredContent").path("exit_code").asInt()).as(text(verified)).isZero();
        // The check of the home names the runs and registers of every project: it is the operator's
        assertThat(home.path("isError").asBoolean()).as(text(home)).isTrue();
        assertThat(text(home)).contains("the argument run is required");
        assertThat(quick.path("isError").asBoolean()).as(text(quick)).isTrue();
        assertThat(diff.path("isError").asBoolean()).as(text(diff)).isFalse();
        assertThat(diff.path("structuredContent").path("reproduced").asBoolean()).isTrue();
        assertThat(unknown.path("isError").asBoolean()).isTrue();
        assertThat(unknown.path("structuredContent").path("exit_code").asInt()).isEqualTo(2);
    }

    @Test
    void theRecordAndThePageOfARun_areResources() throws IOException {
        cli(0, "run", helloPackage().toString(), "--allow-host-execution", "--no-input", "--quiet");
        McpSession mcp = server().modern("reader");

        JsonNode listed = mcp.request("resources/list", null).path("result");
        JsonNode templates = mcp.request("resources/templates/list", null).path("result");
        String eventsUri = listed.path("resources").path(0).path("uri").asText();
        JsonNode read = mcp.request("resources/read", args("uri", eventsUri)).path("result");
        JsonNode page = mcp.request("resources/read", args("uri", eventsUri.replace("events.jsonl", "run.html")))
                .path("result");
        JsonNode missing = mcp.request("resources/read", args("uri", "traceworks://runs/00000000/events.jsonl"));
        JsonNode foreign = mcp.request("resources/read", args("uri", "file:///etc/passwd"));

        assertThat(listed.path("resources")).hasSize(2);
        assertThat(eventsUri).matches("traceworks://runs/[0-9a-f-]{36}/events\\.jsonl");
        assertThat(templates.path("resourceTemplates")).extracting(t -> t.path("uriTemplate").asText())
                .containsExactly("traceworks://runs/{run}/events.jsonl", "traceworks://runs/{run}/run.html");
        assertThat(read.path("contents").path(0).path("text").asText()).startsWith("{").contains("run.started");
        assertThat(read.path("contents").path(0).path("uri").asText()).isEqualTo(eventsUri);
        assertThat(page.path("contents").path(0).path("mimeType").asText()).isEqualTo("text/html");
        assertThat(page.path("contents").path(0).path("text").asText()).contains("<html");
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(foreign.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void thePrompts_areListed_andFilledWithTheirArgument() {
        McpSession mcp = server().modern("reader");

        JsonNode listed = mcp.request("prompts/list", null).path("result");
        ObjectNode get = JSON.createObjectNode().put("name", "watch_run");
        get.set("arguments", args("run", "abcd1234"));
        JsonNode prompt = mcp.request("prompts/get", get).path("result");
        JsonNode unknown = mcp.request("prompts/get", JSON.createObjectNode().put("name", "nope"));

        assertThat(listed.path("prompts")).extracting(p -> p.path("name").asText())
                .containsExactly("design_workflow", "watch_run", "review_findings");
        assertThat(prompt.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(prompt.path("messages").path(0).path("content").path("text").asText()).contains("abcd1234");
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    /** A client names no project, so the prompt takes none: its register is that of the server's project. */
    @Test
    void thePromptOfARegister_takesNoProject_andSaysWhoseRegisterItIs() {
        McpSession mcp = server().modern("reader");
        ObjectNode get = JSON.createObjectNode().put("name", "review_findings");

        JsonNode listed = mcp.request("prompts/list", null).path("result").path("prompts").path(2);
        get.set("arguments", args("register", "hello"));
        String text = mcp.request("prompts/get", get).path("result").path("messages").path(0).path("content")
                .path("text").asText();

        assertThat(listed.path("arguments")).extracting(a -> a.path("name").asText()).containsExactly("register");
        assertThat(text).contains("register hello", "the project the operator bound to the folder this server works"
                + " in", "do not give the argument project");
    }

    // ---- the tools that change something ----

    @Test
    void run_startsTheRunBesideTheServer_returnsItsId_andTheRecordNamesTheAgent() throws Exception {
        McpSession mcp = server("--allow-run", "--allow-host-execution");
        mcp.initialize("2025-11-25", "claude-code");
        ObjectNode call = args("package", approvalPackage().toString());
        call.putObject("inputs").put("who", "world");

        JsonNode started = mcp.call("run", call);
        String id = started.path("structuredContent").path("run_id").asText();
        JsonNode parked = showUntil(mcp, id, "PARKED");

        assertThat(started.path("isError").asBoolean()).as(text(started)).isFalse();
        assertThat(id).hasSize(36);
        assertThat(parked.path("structuredContent").path("question").path("kind").asText()).isEqualTo("approval");
        assertThat(parked.path("structuredContent").path("question").path("options")).extracting(JsonNode::asText)
                .containsExactly("Yes", "No");
        assertThat(recordLines(id, "run.started")).singleElement().asString()
                .contains("\"actor\":\"tester\"", "\"agent\":\"claude-code\"");
        assertThat(cli(0, "show", id)).contains("started the run through the agent claude-code");
        assertThat(mcp.end()).isZero();
    }

    @Test
    void aRunThroughTheServer_passesTheCheckFirst_andAnErrorStartsNone() throws IOException {
        Path broken = pkg("broken", "");
        McpSession mcp = server("--allow-run", "--allow-host-execution").modern("claude-code");

        JsonNode refused = mcp.call("run", args("package", broken.toString()));

        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).contains("empty-workflow", "refused");
        assertThat(refused.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
        assertThat(home.resolve("runs")).doesNotExist();
    }

    @Test
    void withoutTheOperatorsConsent_noRunStartsOnTheHost() throws IOException {
        McpSession mcp = server("--allow-run").modern("claude-code");

        JsonNode refused = mcp.call("run", args("package", helloPackage().toString()));

        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(refused.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
        assertThat(mcp.request("tools/list", null).toString()).doesNotContain("allow_host_execution");
    }

    @Test
    void aClientWithoutAName_orWithOneThatIsNone_changesNothing_untilTheOperatorNamesTheAgent() throws IOException {
        Path hello = helloPackage();
        McpSession nameless = server("--allow-run", "--allow-host-execution").modern(null);
        McpSession odd = server("--allow-run", "--allow-host-execution").modern("Some Client (v2)");
        McpSession named = server("--allow-run", "--allow-host-execution", "--agent", "night-shift")
                .modern("Some Client (v2)");

        JsonNode noName = nameless.call("run", args("package", hello.toString()));
        JsonNode badName = odd.call("run", args("package", hello.toString()));
        assertThat(home.resolve("runs")).doesNotExist();
        JsonNode started = named.call("run", args("package", hello.toString()));

        assertThat(noName.path("isError").asBoolean()).isTrue();
        assertThat(text(noName)).contains("--agent");
        assertThat(badName.path("isError").asBoolean()).isTrue();
        assertThat(text(badName)).contains("--agent");
        assertThat(started.path("isError").asBoolean()).as(text(started)).isFalse();
        named.end();
        assertThat(recordLines(started.path("structuredContent").path("run_id").asText(), "run.started"))
                .singleElement().asString().contains("\"agent\":\"night-shift\"");
    }

    @Test
    void approve_resume_andCancel_goThroughTheSameUseCases_andEachLineNamesTheAgent() throws Exception {
        Path deploy = approvalPackage();
        String first = cli(5, "run", deploy.toString(), "--allow-host-execution", "--no-input", "--quiet")
                .lines().filter(l -> l.startsWith("run ")).findFirst().orElseThrow().split(" ")[1];
        String second = cli(5, "run", deploy.toString(), "--allow-host-execution", "--no-input", "--quiet")
                .lines().filter(l -> l.startsWith("run ")).findFirst().orElseThrow().split(" ")[1];
        McpSession mcp = server("--allow-run", "--allow-answer", "--allow-host-execution").modern("claude-code");

        JsonNode wrong = mcp.call("approve", args("run", first, "answer", "Maybe"));
        JsonNode approved = mcp.call("approve", args("run", first, "answer", "Yes", "comment", "looks fine"));
        JsonNode resumed = mcp.call("resume", args("run", first));
        JsonNode done = showUntil(mcp, first, "COMPLETED");
        JsonNode cancelled = mcp.call("cancel", args("run", second));

        assertThat(wrong.path("isError").asBoolean()).isTrue();
        assertThat(wrong.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
        assertThat(approved.path("isError").asBoolean()).as(text(approved)).isFalse();
        assertThat(text(approved)).contains("through the agent claude-code");
        assertThat(resumed.path("isError").asBoolean()).as(text(resumed)).isFalse();
        assertThat(done.path("structuredContent").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(recordLines(first, "approval.answered").getFirst())
                .contains("\"actor\":\"tester\"", "\"agent\":\"claude-code\"");
        assertThat(recordLines(first, "run.resumed")).singleElement().asString()
                .contains("\"agent\":\"claude-code\"");
        assertThat(cancelled.path("structuredContent").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(recordLines(second, "run.cancel_requested")).singleElement().asString()
                .contains("\"actor\":\"tester\"", "\"agent\":\"claude-code\"");
        cli(0, "verify", first);
    }

    /**
     * A client's answer is checked as a person's is: the tool runs the command, so a value the field's
     * validation_pattern refuses is an error with the refusal code, nothing is recorded, and the question waits.
     */
    @Test
    void answer_withAValueTheFieldsPatternRefuses_isAnErrorWithExit6_andRecordsNothing() throws Exception {
        Path guarded = pkg("guarded", """
                {"type":"human-input","name":"count","config":{"prompt":"How many?","variable_name":"n",
                 "validation_pattern":"[0-9]+"}},
                {"type":"shell","name":"say","config":{"command":"echo got {{ n }}"}}""");
        String id = cli(5, "run", guarded.toString(), "--allow-host-execution", "--no-input", "--quiet")
                .lines().filter(l -> l.startsWith("run ")).findFirst().orElseThrow().split(" ")[1];
        McpSession mcp = server("--allow-answer", "--allow-host-execution").modern("claude-code");

        JsonNode refused = mcp.call("answer", args("run", id, "value", "abc; rm -rf x"));

        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(refused.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
        assertThat(text(refused)).contains("'n' (validation_pattern)", "does not match the pattern [0-9]+");
        assertThat(recordLines(id, "approval.answered")).isEmpty();
        JsonNode answered = mcp.call("answer", args("run", id, "value", "42"));
        assertThat(answered.path("isError").asBoolean()).as(text(answered)).isFalse();
    }

    @Test
    void theEndOfTheInput_parksARunTheServerStillRuns_soThatTheCommandLineGoesOn() throws Exception {
        Path slow = pkg("slow", """
                {"type":"shell","name":"first","config":{"command":"sleep 1; echo one"}},
                {"type":"shell","name":"second","config":{"command":"echo two"}}""");
        McpSession mcp = server("--allow-run", "--allow-host-execution").modern("claude-code");

        String id = mcp.call("run", args("package", slow.toString())).path("structuredContent").path("run_id")
                .asText();
        int code = mcp.end();

        assertThat(code).isZero();
        String shown = cli(0, "show", id);
        assertThat(shown).contains("PARKED");
        assertThat(recordLines(id, "run.pause_requested")).hasSize(1);
        assertThat(cli(0, "resume", id, "--allow-host-execution", "--no-input", "--quiet")).contains("COMPLETED");
    }

    /**
     * A question inside another block does not park the run: it waits in the process, and a pause waits behind it.
     * Nobody answers it through a server whose client is gone, so the end of the input leaves such a run interrupted,
     * and the command line goes on with it.
     */
    @Test
    void theEndOfTheInput_leavesARunThatWaitsAtAQuestionInTheProcessInterrupted_andTheServerEnds() throws Exception {
        Path asking = pkg("asking", """
                {"type":"sub-workflow","name":"child","config":{"workflow_id":"./inner"}},
                {"type":"shell","name":"done","config":{"command":"echo done"}}""");
        pkg("asking/inner", """
                {"type":"approval","name":"publish","config":{"question":"Publish it?","options":["Yes","No"]}}""");
        McpSession mcp = server("--allow-run", "--allow-host-execution").modern("claude-code");
        String id = mcp.call("run", args("package", asking.toString())).path("structuredContent").path("run_id")
                .asText();
        for (int i = 0; i < 600 && !text(mcp.call("events", args("run", id))).contains("approval.requested"); i++) {
            Thread.sleep(50);
        }
        assertThat(text(mcp.call("events", args("run", id)))).contains("approval.requested", "\"parks\":false");

        int code = mcp.end(20);

        assertThat(code).isZero();
        assertThat(mcp.stderr()).contains("waits for an answer", "traceworks resume");
        assertThat(cli(9, "show", id)).contains("INTERRUPTED");
        assertThat(recordLines(id, "run.pause_requested")).isEmpty();
    }

    @Test
    void findings_areListedInPages_shown_andDecidedThroughTheAgent() throws IOException {
        declare(Map.of("shop", work));
        auditRan("shop");
        McpSession reader = server().modern("reader");
        McpSession decider = server("--allow-decide").modern("claude-code");

        JsonNode registers = reader.call("findings_registers", null);
        JsonNode page = reader.call("findings_list", JSON.createObjectNode().put("register", "hello").put("limit", 1));
        String id = page.path("structuredContent").path("findings").path(0).path("id").asText();
        JsonNode shown = reader.call("findings_show", args("register", "hello", "id", id.substring(0, 8)));
        JsonNode items = reader.call("findings_items", args("register", "hello"));
        JsonNode noNote = decider.call("findings_decide", args("register", "hello", "id", id, "decision", "dismiss"));
        JsonNode dismissed = decider.call("findings_decide", args("register", "hello", "id", id, "decision",
                "dismiss", "note", "generated code"));
        JsonNode noRegister = reader.call("findings_list", args("register", "nope"));

        assertThat(registers.path("structuredContent").path("registers")).extracting(JsonNode::toString)
                .as("each key with the projects it has a register of")
                .containsExactly("{\"register\":\"hello\",\"projects\":[\"shop\"]}");
        assertThat(registers.path("structuredContent").path("project").asText()).isEqualTo("shop");
        assertThat(text(registers)).contains("hello  shop  example  OPEN 2");
        assertThat(page.path("structuredContent").path("findings")).hasSize(1);
        assertThat(page.path("structuredContent").path("total").asInt()).isEqualTo(2);
        assertThat(page.path("structuredContent").path("next_cursor").asText()).isEqualTo("1");
        assertThat(text(shown)).contains("Rule      size");
        assertThat(items.path("isError").asBoolean()).as(text(items)).isFalse();
        assertThat(noNote.path("isError").asBoolean()).isTrue();
        assertThat(dismissed.path("isError").asBoolean()).as(text(dismissed)).isFalse();
        assertThat(text(dismissed)).contains("dismissed", "through the agent claude-code");
        assertThat(cli(0, "findings", "hello", "show", id)).contains("through the agent claude-code");
        assertThat(noRegister.path("isError").asBoolean()).isTrue();
    }

    /**
     * A run that calls its own package as a child is not resumed with a changed package, through the server as on
     * the command line and in its words: the child would run the package as it is now, beside the definition
     * recorded at the start.
     */
    @Test
    void aRunThatCallsItsOwnPackage_isNotResumedWithAChangedPackage_inTheWordsOfTheCommandLine() throws Exception {
        String blocks = """
                {"type":"approval","name":"go","config":{"question":"Go on?","options":["Yes","No"]}},
                {"type":"sub-workflow","name":"again","config":{"workflow_id":".","run_if":"false"}},
                {"type":"shell","name":"say","config":{"command":"echo %s"}}""";
        Path self = pkg("selfcall", blocks.formatted("one"));
        McpSession mcp = server("--allow-run", "--allow-answer", "--allow-host-execution").modern("claude-code");
        String id = mcp.call("run", args("package", self.toString())).path("structuredContent").path("run_id")
                .asText();
        showUntil(mcp, id, "PARKED");
        assertThat(mcp.call("approve", args("run", id, "answer", "Yes")).path("isError").asBoolean()).isFalse();
        pkg("selfcall", blocks.formatted("two"));

        JsonNode refused = mcp.call("resume", args("run", id));
        String ofTheCommandLine = traceworks.cli.Unbroken.of(cliErr(6, "resume", id, "--no-input",
                "--allow-host-execution", "--allow-changed-package")).strip();

        assertThat(refused.path("isError").asBoolean()).as(text(refused)).isTrue();
        assertThat(refused.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
        assertThat(said(refused)).contains("its block 'again' runs the run's own package as a child workflow")
                .contains(ofTheCommandLine);
        assertThat(recordLines(id, "run.resumed")).isEmpty();
    }

    // ---- the project of a run and of a register ----

    /** The lines of a result as the command said them: a line broken for the width of a terminal is one again. */
    private static String said(JsonNode result) {
        return traceworks.cli.Unbroken.of(text(result));
    }

    /** What a refused start must leave: no run, no entry of the ledger and no register. */
    private void assertNothingWritten() {
        assertThat(home.resolve("runs")).doesNotExist();
        assertThat(home.resolve("ledger")).doesNotExist();
        assertThat(home.resolve("registers")).doesNotExist();
    }

    /** Runs the audit with the command line for {@code project}, in a folder of its own that is bound to none. */
    private void auditRanElsewhere(String project) throws IOException {
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere-" + project));
        cli(0, "run", auditPackage().toString(), "--allow-host-execution", "--no-input", "--quiet", "--workspace",
                elsewhere.toString(), "--project", project);
    }

    private byte[] logOf(String project) throws IOException {
        return Files.readAllBytes(home.resolve("registers/hello/" + project + "/events.jsonl"));
    }

    /** The refusal of a server whose folder the operator bound to no project: it names the line to add. */
    private void assertRefusedAsUnbound(JsonNode refused) {
        assertThat(refused.path("isError").asBoolean()).as(text(refused)).isTrue();
        assertThat(refused.path("structuredContent").path("exit_code").asInt()).as(text(refused)).isEqualTo(6);
        assertThat(said(refused)).contains("is bound to no project", "a client of this server cannot name a project",
                "\"projects\": {\"KEY\": \"" + work + "\"}");
    }

    @Test
    void inAFolderBoundToNoProject_anAuditRun_andARunThatNamesAProject_areRefused_andNothingIsWritten()
            throws IOException {
        Path audit = auditPackage();
        Path hello = helloPackage();
        McpSession mcp = server("--allow-run", "--allow-host-execution").modern("claude-code");

        JsonNode unnamed = mcp.call("run", args("package", audit.toString()));
        JsonNode named = mcp.call("run", args("package", audit.toString(), "project", "shop"));
        JsonNode noAudit = mcp.call("run", args("package", hello.toString(), "project", "shop"));

        for (JsonNode refused : List.of(unnamed, named, noAudit)) {
            assertRefusedAsUnbound(refused);
            assertThat(refused.path("structuredContent").has("run_id")).isFalse();
        }
        assertThat(said(unnamed)).contains("the workflow feeds the register hello");
        assertNothingWritten();
    }

    /** A run that is no audit and names no project runs as before, without one. */
    @Test
    void inAFolderBoundToNoProject_aRunThatIsNoAudit_runsWithoutAProject() throws Exception {
        McpSession mcp = server("--allow-run", "--allow-host-execution").modern("claude-code");

        JsonNode started = mcp.call("run", args("package", helloPackage().toString()));
        assertThat(started.path("isError").asBoolean()).as(text(started)).isFalse();
        String id = started.path("structuredContent").path("run_id").asText();
        showUntil(mcp, id, "COMPLETED");

        assertThat(started.path("structuredContent").has("project")).isFalse();
        assertThat(recordLines(id, "run.started")).singleElement().asString().doesNotContain("\"project\"");
    }

    /**
     * The project of a run is the one the operator bound to the folder the server works in. The argument is taken
     * only when it names exactly that project: it never selects one.
     */
    @Test
    void theProjectOfARun_isTheOneTheOperatorBoundToTheFolder_andTheArgumentNeverSelectsOne() throws Exception {
        declare(Map.of("blog", work));
        Path audit = auditPackage();
        McpSession mcp = server("--allow-run", "--allow-host-execution").modern("claude-code");

        JsonNode other = mcp.call("run", args("package", audit.toString(), "project", "shop"));
        boolean nothingYet = !Files.exists(home.resolve("runs")) && !Files.exists(home.resolve("registers"));
        JsonNode started = mcp.call("run", args("package", audit.toString()));
        assertThat(started.path("isError").asBoolean()).as(text(started)).isFalse();
        String id = started.path("structuredContent").path("run_id").asText();
        JsonNode done = showUntil(mcp, id, "COMPLETED");
        JsonNode same = mcp.call("run", args("package", audit.toString(), "project", "blog"));
        // The record ends before a run feeds its register; the server ends when its runs are through
        assertThat(mcp.end()).isZero();

        assertThat(other.path("isError").asBoolean()).isTrue();
        assertThat(other.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
        assertThat(said(other)).contains("is bound to the project 'blog'", "a client of this server cannot choose"
                + " a project", "leave the argument project out");
        assertThat(nothingYet).as("the refused run left nothing").isTrue();
        assertThat(started.path("structuredContent").path("project").asText()).isEqualTo("blog");
        assertThat(done.path("structuredContent").path("project").asText()).isEqualTo("blog");
        assertThat(recordLines(id, "run.started")).singleElement().asString()
                .contains("\"project\":\"blog\"", "\"agent\":\"claude-code\"");
        assertThat(same.path("isError").asBoolean()).as("the project of the folder, named: " + text(same)).isFalse();
        assertThat(home.resolve("registers/hello")).isDirectoryContaining(p -> p.getFileName().toString()
                .equals("blog")).isDirectoryNotContaining(p -> p.getFileName().toString().equals("shop"));
    }

    /**
     * The tools on a register reach the registers of the project bound to the server's folder and no other: the
     * overview lists only those, a call without the argument means that project, and the argument does not open
     * the register of another project, to read or to decide.
     */
    @Test
    void theToolsOnARegister_reachTheRegistersOfTheBoundProjectOnly() throws IOException {
        declare(Map.of("shop", work));
        auditRan("shop");
        auditRanElsewhere("web");
        String id = firstFinding("shop");
        McpSession reader = server().modern("reader");
        McpSession decider = server("--allow-decide").modern("claude-code");
        byte[] logOfWeb = logOf("web");

        JsonNode registers = reader.call("findings_registers", null);
        JsonNode listed = reader.call("findings_list", args("register", "hello"));
        JsonNode named = reader.call("findings_list", args("register", "hello", "project", "shop"));
        JsonNode shown = reader.call("findings_show", args("register", "hello", "id", id));
        JsonNode items = reader.call("findings_items", args("register", "hello"));
        List<JsonNode> ofWeb = List.of(
                reader.call("findings_list", args("register", "hello", "project", "web")),
                reader.call("findings_show", args("register", "hello", "project", "web", "id", id)),
                reader.call("findings_items", args("register", "hello", "project", "web")),
                decider.call("findings_decide", args("register", "hello", "project", "web", "id", id, "decision",
                        "dismiss", "note", "generated code")));
        JsonNode dismissed = decider.call("findings_decide", args("register", "hello", "id", id, "decision",
                "dismiss", "note", "generated code"));

        assertThat(registers.path("isError").asBoolean()).as(text(registers)).isFalse();
        assertThat(text(registers)).contains("hello  shop  example  OPEN 2").doesNotContain("web");
        assertThat(registers.path("structuredContent").path("project").asText()).isEqualTo("shop");
        assertThat(registers.path("structuredContent").path("registers")).extracting(JsonNode::toString)
                .containsExactly("{\"register\":\"hello\",\"projects\":[\"shop\"]}");
        for (JsonNode ofShop : List.of(listed, named)) {
            assertThat(ofShop.path("isError").asBoolean()).as(text(ofShop)).isFalse();
            assertThat(ofShop.path("structuredContent").path("total").asInt()).isEqualTo(2);
        }
        assertThat(text(shown)).contains("Rule      size");
        assertThat(items.path("isError").asBoolean()).as(text(items)).isFalse();
        for (JsonNode refused : ofWeb) {
            assertThat(refused.path("isError").asBoolean()).as(text(refused)).isTrue();
            assertThat(refused.path("structuredContent").path("exit_code").asInt()).as(text(refused)).isEqualTo(6);
            assertThat(said(refused)).contains("is bound to the project 'shop'", "a client of this server cannot"
                    + " choose a project").doesNotContain("A.java", "Big A");
        }
        assertThat(logOf("web")).as("no decision reached the register of another project").isEqualTo(logOfWeb);
        assertThat(dismissed.path("isError").asBoolean()).as(text(dismissed)).isFalse();
        assertThat(said(dismissed)).contains("dismissed " + id + " in the register hello of shop through the agent"
                + " claude-code");
        assertThat(cli(0, "findings", "hello", "list", "--project", "web")).as("the same finding of another project")
                .contains("OPEN       MEDIUM    size  A.java");
    }

    /** In a folder bound to no project no register is read or decided in, also where a key has only one. */
    @Test
    void inAFolderBoundToNoProject_noToolOnARegisterReadsOrDecides() throws IOException {
        auditRan("shop");
        String id = firstFinding("shop");
        byte[] log = logOf("shop");
        McpSession mcp = server("--allow-decide").modern("claude-code");

        List<JsonNode> calls = List.of(
                mcp.call("findings_registers", null),
                mcp.call("findings_list", args("register", "hello")),
                mcp.call("findings_list", args("register", "hello", "project", "shop")),
                mcp.call("findings_show", args("register", "hello", "id", id)),
                mcp.call("findings_items", args("register", "hello")),
                mcp.call("findings_decide", args("register", "hello", "id", id, "decision", "dismiss", "note", "no")),
                mcp.call("findings_decide", args("register", "hello", "project", "shop", "id", id, "decision",
                        "dismiss", "note", "no")));

        for (JsonNode refused : calls) {
            assertRefusedAsUnbound(refused);
            assertThat(text(refused)).doesNotContain("A.java", "Big A", "OPEN");
        }
        assertThat(logOf("shop")).isEqualTo(log);
    }

    /** Every file below the registers of the home with its bytes, without the locks a reader makes. */
    private java.util.SortedMap<String, String> registersTree() throws IOException {
        java.util.SortedMap<String, String> tree = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(home.resolve("registers"))) {
            for (Path file : files.filter(f -> !f.getFileName().toString().endsWith(".lock")).toList()) {
                tree.put(home.relativize(file).toString(), Files.isRegularFile(file)
                        ? java.util.HexFormat.of().formatHex(Files.readAllBytes(file)) : "(folder)");
            }
        }
        return tree;
    }

    /**
     * A register from before registers had projects is of no project, and the tools refuse it in the words of the
     * command line. A client that may decide assigns it, to the project of the server's folder and to no other;
     * what the command refuses (a register that is not from before) comes through, and writes nothing.
     */
    @Test
    void aRegisterFromBeforeProjects_isRefused_untilAClientThatMayDecideAssignsItToTheBoundProject()
            throws IOException {
        declare(Map.of("shop", work));
        traceworks.core.testing.EarlierRegister.writeTo(home.resolve("registers/arch"));
        McpSession reader = server().modern("reader");
        McpSession decider = server("--allow-decide").modern("claude-code");

        JsonNode overview = reader.call("findings_registers", null);
        JsonNode list = reader.call("findings_list", args("register", "arch"));
        JsonNode notOffered = reader.request("tools/call", JSON.createObjectNode().put("name", "findings_assign")
                .set("arguments", args("register", "arch")));
        java.util.SortedMap<String, String> before = registersTree();
        JsonNode other = decider.call("findings_assign", args("register", "arch", "project", "web"));
        boolean untouched = registersTree().equals(before);
        JsonNode assigned = decider.call("findings_assign", args("register", "arch"));
        java.util.SortedMap<String, String> after = registersTree();
        JsonNode again = decider.call("findings_assign", args("register", "arch"));
        JsonNode listed = reader.call("findings_list", JSON.createObjectNode().put("register", "arch")
                .put("all", true));

        assertThat(text(overview)).contains("no registers of the project shop").doesNotContain("arch");
        assertThat(list.path("structuredContent").path("exit_code").asInt()).as(text(list)).isEqualTo(6);
        assertThat(said(list)).contains("is from before registers had projects");
        assertThat(toolNames(reader.request("tools/list", null))).doesNotContain("findings_assign");
        assertThat(toolNames(decider.request("tools/list", null))).contains("findings_assign", "findings_decide");
        assertThat(notOffered.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(notOffered.path("error").path("message").asText()).contains("--allow-decide");
        assertThat(other.path("structuredContent").path("exit_code").asInt()).as(text(other)).isEqualTo(6);
        assertThat(said(other)).contains("a client of this server cannot choose a project");
        assertThat(untouched).as("a refused assignment wrote nothing").isTrue();
        assertThat(assigned.path("isError").asBoolean()).as(text(assigned)).isFalse();
        assertThat(said(assigned)).contains("assigned the register arch to the project shop");
        assertThat(home.resolve("registers/arch/shop/events.jsonl")).isRegularFile();
        assertThat(home.resolve("registers/arch/events.jsonl")).doesNotExist();
        assertThat(again.path("isError").asBoolean()).isTrue();
        assertThat(again.path("structuredContent").path("exit_code").asInt()).as(text(again)).isEqualTo(6);
        assertThat(said(again)).contains("is not from before registers had projects");
        assertThat(registersTree()).as("the refused second assignment wrote nothing").isEqualTo(after);
        assertThat(listed.path("isError").asBoolean()).as("the assigned register is read: " + text(listed)).isFalse();
    }

    /** A register from before that does not verify is not assigned: exit 8, as the command, and nothing is written. */
    @Test
    void aRegisterFromBefore_thatDoesNotVerify_isNotAssigned_andNothingIsWritten() throws IOException {
        declare(Map.of("shop", work));
        traceworks.core.testing.EarlierRegister.writeTo(home.resolve("registers/arch"));
        Path log = home.resolve("registers/arch/events.jsonl");
        Files.writeString(log, Files.readString(log).replaceFirst("\"seq\":2,", "\"seq\":2, "));
        java.util.SortedMap<String, String> before = registersTree();
        McpSession decider = server("--allow-decide").modern("claude-code");

        JsonNode refused = decider.call("findings_assign", args("register", "arch"));

        assertThat(refused.path("isError").asBoolean()).as(text(refused)).isTrue();
        assertThat(refused.path("structuredContent").path("exit_code").asInt()).as(text(refused)).isEqualTo(8);
        assertThat(said(refused)).contains("traceworks findings arch verify");
        assertThat(registersTree()).isEqualTo(before);
    }

    /**
     * The check of one register, of the bound project only: what the command prints and its exit code, 8 for a
     * register that does not hold, which is a verdict and no error. It reads and writes nothing.
     */
    @Test
    void findingsVerify_checksTheRegisterOfTheBoundProject_inTheWordsOfTheCommand_andWritesNothing()
            throws IOException {
        declare(Map.of("shop", work));
        auditRan("shop");
        auditRanElsewhere("web");
        McpSession mcp = server().modern("reader");
        String ofTheCommand = cli(0, "findings", "hello", "verify", "--project", "shop").strip();
        java.util.SortedMap<String, String> before = registersTree();

        JsonNode holds = mcp.call("findings_verify", args("register", "hello"));
        JsonNode ofWeb = mcp.call("findings_verify", args("register", "hello", "project", "web"));
        boolean untouched = registersTree().equals(before);
        Path log = home.resolve("registers/hello/shop/events.jsonl");
        Files.writeString(log, Files.readString(log).replaceFirst("\"seq\":1,", "\"seq\":1, "));
        JsonNode broken = mcp.call("findings_verify", args("register", "hello"));

        assertThat(holds.path("isError").asBoolean()).as(text(holds)).isFalse();
        assertThat(holds.path("structuredContent").path("exit_code").asInt()).isZero();
        assertThat(holds.path("structuredContent").path("verified").asBoolean()).isTrue();
        assertThat(said(holds)).contains(traceworks.cli.Unbroken.of(ofTheCommand));
        assertThat(ofWeb.path("structuredContent").path("exit_code").asInt()).as(text(ofWeb)).isEqualTo(6);
        assertThat(said(ofWeb)).contains("a client of this server cannot choose a project");
        assertThat(untouched).as("a check writes nothing").isTrue();
        assertThat(broken.path("structuredContent").path("exit_code").asInt()).as(text(broken)).isEqualTo(8);
        assertThat(broken.path("isError").asBoolean()).as("a verdict is no error").isFalse();
        assertThat(broken.path("structuredContent").path("verified").asBoolean()).isFalse();
    }

    // ---- the runs a client sees ----

    /** The id of the run whose first line holds {@code marker}; with {@code without}, the one that does not. */
    private String runWith(String marker, boolean holds) throws IOException {
        try (Stream<Path> folders = Files.walk(home.resolve("runs"), 2)) {
            for (Path folder : folders.filter(f -> Files.exists(f.resolve("events.jsonl"))).toList()) {
                if (Files.readAllLines(folder.resolve("events.jsonl")).getFirst().contains(marker) == holds) {
                    return folder.getFileName().toString();
                }
            }
        }
        throw new AssertionError("no such run");
    }

    /**
     * A client sees the runs of the project the operator bound to the server's folder, and the runs that have no
     * project; in a folder bound to none, only the latter. A run of another project is a run the server does not
     * know, in the words of one that does not exist.
     */
    @Test
    void aClient_seesTheRunsOfTheBoundProject_andTheRunsWithoutOne_andNoOther() throws IOException {
        declare(Map.of("shop", work));
        Path hello = helloPackage();
        Path nowhere = Files.createDirectories(temp.resolve("nowhere"));
        cli(0, "run", hello.toString(), "--allow-host-execution", "--no-input", "--quiet", "--workspace",
                Files.createDirectories(temp.resolve("web")).toString(), "--project", "web");
        String web = runWith("\"project\"", true);
        cli(0, "run", hello.toString(), "--allow-host-execution", "--no-input", "--quiet", "--workspace",
                nowhere.toString());
        String none = runWith("\"project\"", false);
        cli(0, "run", hello.toString(), "--allow-host-execution", "--no-input", "--quiet");
        String shop = runWith("\"project\":\"shop\"", true);
        McpSession inShop = server().modern("reader");
        McpSession unbound = new McpSession(home, nowhere, env).modern("reader");
        sessions.add(unbound);

        List<String> listedInShop = new ArrayList<>();
        inShop.call("runs", null).path("structuredContent").path("runs")
                .forEach(run -> listedInShop.add(run.path("run_id").asText()));
        List<String> listedUnbound = new ArrayList<>();
        unbound.call("runs", null).path("structuredContent").path("runs")
                .forEach(run -> listedUnbound.add(run.path("run_id").asText()));
        JsonNode onePerPage = inShop.call("runs", JSON.createObjectNode().put("limit", 1));
        JsonNode nextPage = inShop.call("runs", JSON.createObjectNode().put("limit", 1)
                .put("cursor", onePerPage.path("structuredContent").path("next_cursor").asText()));
        String resources = inShop.request("resources/list", null).toString();
        JsonNode ofWeb = inShop.call("show", args("run", web));
        JsonNode byPrefix = inShop.call("show", args("run", web.substring(0, 8)));
        JsonNode unknown = inShop.call("show", args("run", "00000000-0000-0000-0000-000000000000"));
        JsonNode ofShopUnbound = unbound.call("show", args("run", shop));

        assertThat(listedInShop).containsExactly(shop, none);
        assertThat(listedUnbound).containsExactly(none);
        assertThat(onePerPage.path("structuredContent").path("runs").path(0).path("run_id").asText()).isEqualTo(shop);
        assertThat(nextPage.path("structuredContent").path("runs").path(0).path("run_id").asText()).isEqualTo(none);
        assertThat(nextPage.path("structuredContent").has("next_cursor")).isFalse();
        assertThat(resources).contains(shop, none).doesNotContain(web);
        assertThat(inShop.call("show", args("run", shop)).path("isError").asBoolean()).isFalse();
        assertThat(inShop.call("show", args("run", none)).path("isError").asBoolean()).isFalse();
        for (JsonNode hidden : List.of(ofWeb, byPrefix, ofShopUnbound)) {
            assertThat(hidden.path("isError").asBoolean()).isTrue();
            assertThat(hidden.path("structuredContent").path("exit_code").asInt()).isEqualTo(2);
        }
        assertThat(text(ofWeb).replace(web, "ID")).as("as a run that does not exist")
                .isEqualTo(text(unknown).replace("00000000-0000-0000-0000-000000000000", "ID"));
    }

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    /**
     * The register of the bound project whose log is gone is refused as the command line refuses it, and is not
     * begun anew; with a ledger that cannot be listed too. No call falls back to the register of another project.
     */
    @Test
    void theRegisterOfTheBoundProject_whoseLogIsGone_isRefused_andNoOtherProjectStandsIn() throws IOException {
        declare(Map.of("shop", work));
        auditRan("shop");
        auditRanElsewhere("web");
        String id = firstFinding("web");
        delete(home.resolve("registers/hello/shop"));
        byte[] logOfWeb = logOf("web");
        McpSession mcp = server("--allow-decide").modern("claude-code");

        JsonNode gone = mcp.call("findings_list", args("register", "hello"));
        JsonNode overview = mcp.call("findings_registers", null);
        JsonNode checked = mcp.call("findings_verify", args("register", "hello"));
        Files.delete(home.resolve("ledger/.git/HEAD"));
        JsonNode unlisted = mcp.call("findings_list", args("register", "hello"));
        JsonNode unlistedDecision = mcp.call("findings_decide", args("register", "hello", "id", id, "decision",
                "dismiss", "note", "generated code"));

        assertThat(said(gone)).contains("The ledger holds anchors of the register hello of shop, and its log is"
                + " gone");
        assertThat(said(overview)).contains("hello  shop  refused: ").doesNotContain("web");
        assertThat(overview.path("structuredContent").path("exit_code").asInt()).isEqualTo(6);
        assertThat(overview.path("isError").asBoolean()).as("the list was finished: its line says what is refused")
                .isFalse();
        assertThat(checked.path("structuredContent").path("exit_code").asInt()).as(text(checked)).isEqualTo(8);
        assertThat(said(checked)).as("the check says what is wrong with it").contains("anchor    NOT ok");
        for (JsonNode refused : List.of(gone, unlisted, unlistedDecision)) {
            assertThat(refused.path("isError").asBoolean()).isTrue();
            assertThat(refused.path("structuredContent").path("exit_code").asInt()).as(text(refused)).isEqualTo(6);
            assertThat(text(refused)).doesNotContain("internal error", "A.java");
        }
        assertThat(logOf("web")).as("no decision went to the project that is left").isEqualTo(logOfWeb);
        assertThat(home.resolve("registers/hello/shop")).as("no folder was made for the gone register")
                .doesNotExist();
    }

    // ---- what a run shows ----

    /** A run names its project, who started it and the packages its child runs were loaded from, as show does. */
    @Test
    void runsAndShow_nameTheProject_theActor_andTheCalledPackages_andAResourceNamesTheProject() throws IOException {
        Path child = Files.createDirectories(temp.resolve("child"));
        Files.writeString(child.resolve("workflow.json"), "{\"id\":\"child\",\"name\":\"Child\",\"blocks\":["
                + "{\"type\":\"shell\",\"name\":\"say\",\"config\":{\"command\":\"echo from the child\"}}]}");
        Path parent = pkg("parent", "{\"type\":\"sub-workflow\",\"name\":\"call\",\"config\":{\"workflow_id\":"
                + "\"../../child\"}}");
        declare(Map.of("shop", work));
        cli(0, "run", helloPackage().toString(), "--allow-host-execution", "--no-input", "--quiet", "--workspace",
                Files.createDirectories(temp.resolve("nowhere")).toString());
        cli(0, "run", parent.toString(), "--allow-host-execution", "--no-input", "--quiet");
        McpSession mcp = server().modern("reader");

        JsonNode runs = mcp.call("runs", null).path("structuredContent").path("runs");
        JsonNode shown = mcp.call("show", args("run", runs.path(0).path("run_id").asText()));
        JsonNode plain = mcp.call("show", args("run", runs.path(1).path("run_id").asText()));
        JsonNode resources = mcp.request("resources/list", null).path("result").path("resources");

        assertThat(runs.path(0).path("project").asText()).isEqualTo("shop");
        assertThat(runs.path(0).path("actor").asText()).isEqualTo("tester");
        assertThat(runs.path(1).has("project")).as("a run that named none").isFalse();
        assertThat(runs.path(1).path("actor").asText()).isEqualTo("tester");
        assertThat(text(shown)).contains("Project   shop", "Calls     child");
        JsonNode data = shown.path("structuredContent");
        assertThat(data.path("project").asText()).isEqualTo("shop");
        assertThat(data.path("actor").asText()).isEqualTo("tester");
        assertThat(data.path("called_packages")).hasSize(1);
        assertThat(data.path("called_packages").path(0).path("workflow_id").asText()).isEqualTo("child");
        assertThat(data.path("called_packages").path(0).path("package_dir").asText()).endsWith("child");
        assertThat(data.path("called_packages").path(0).path("package_digest").asText()).matches("[0-9a-f]{64}");
        assertThat(plain.path("structuredContent").has("project")).isFalse();
        assertThat(plain.path("structuredContent").path("called_packages")).isEmpty();
        assertThat(resources.path(0).path("description").asText()).contains("of the project shop");
        assertThat(resources.path(2).path("description").asText()).doesNotContain("project");
    }

    // ---- the command ----

    @Test
    void theCommand_withoutAnInputToRead_isAUsageError_andItsHelpNamesTheSwitches() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"mcp", "--home", home.toString()}, new PrintStream(out), new PrintStream(err),
                env, work, null, RuntimeSource.NONE);

        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(cli(0, "mcp", "--help")).contains("--allow-run", "--allow-answer", "--allow-decide", "--agent",
                "--packages", "--allow-host-execution");
        assertThat(cli(0, "--help")).contains("mcp");
    }

    @Test
    void theOutputOfTheServer_holdsNothingButMessages_alsoWhenACommandPrints() throws IOException {
        cli(0, "run", helloPackage().toString(), "--allow-host-execution", "--no-input", "--quiet");
        McpSession mcp = server().modern("reader");

        mcp.call("runs", null);
        mcp.call("show", args("run", "zzzz"));
        mcp.send("not json at all");
        mcp.read();

        // Every line read so far parsed as JSON (McpSession.read fails otherwise); the rest must too
        assertThat(mcp.call("describe", args("package", "hello")).path("content").path(0).path("type").asText())
                .isEqualTo("text");
        assertThat(mcp.end()).isZero();
    }
}
