package traceworks.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static traceworks.cli.mcp.McpSession.JSON;
import static traceworks.cli.mcp.McpSession.args;

/**
 * What the server tells a client about writing a workflow: the guide, the format, the examples the jar carries and
 * the prompt that has a model design one. All of it reads, and none of it reads the home.
 */
class McpGuideTest {

    /** The repository, seen from the module the tests run in. */
    private static final Path REPOSITORY = Path.of("..");

    @TempDir
    Path temp;
    private McpSession mcp;
    private Path work;

    @BeforeEach
    void aServerThatOnlyReads() throws IOException {
        Path home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
        mcp = new McpSession(home, work, Map.of()).modern("author");
    }

    @AfterEach
    void endTheServer() {
        mcp.close();
    }

    private static String withoutSpace(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String textOf(JsonNode result) {
        return result.path("structuredContent").path("text").asText();
    }

    @Test
    void guide_withoutATopic_saysHowToTurnATaskIntoAWorkflow() {
        JsonNode guide = mcp.call("guide", null);

        assertThat(guide.path("isError").asBoolean()).isFalse();
        assertThat(guide.path("structuredContent").path("topic").asText()).isEqualTo("authoring");
        assertThat(guide.path("structuredContent").path("topics")).extracting(JsonNode::asText)
                .containsExactly("authoring", "format", "registers");
        assertThat(textOf(guide)).contains("deterministic", "container", "check", "describe", "approval",
                "execution_mode", "workflow.json");
        // The combination is the point: a script finds, a model judges each item, a script checks, a person decides
        assertThat(textOf(guide)).contains("Where does a model add something no script can",
                "The pattern that combines both", "items_command", "once per item", "self-audit/architecture");
        // A second trial copied the size of the self-audits: a tool of 1500 lines, and no time left to check it
        assertThat(textOf(guide)).contains("take their cut, not their size", "as soon as the `workflow.json` stands");
        // What the first trial with a real client got wrong: decisions per finding belong in a register, a person
        // sees no path of a container, and a package does not change while its run waits
        assertThat(textOf(guide)).contains("register", "audit-hello", "traceworks:audit-result:v1",
                "does not see the paths of a container", "Do not change the package while a run of it waits");
        // A later trial kept a decision when the license of a dependency changed, and fetched a URL a POM named
        assertThat(textOf(guide)).contains("holds what the decision is about",
                "never ask an address that the data names");
        assertThat(guide.path("structuredContent").has("next_cursor")).isFalse();
    }

    /**
     * A register is kept per key and project, and an audit run without a project is refused. A model that writes
     * an audit must know that the run names the project and that the key is the operator's word, or it makes one
     * up, and a run under the key of another project feeds that project's register.
     */
    @Test
    void guide_saysThatAnAuditRunNeedsItsProject_andThatTheKeyIsTheOperatorsWord() {
        String guide = textOf(mcp.call("guide", null)).replaceAll("\\s+", " ");

        assertThat(guide).contains("A register is kept per `key` and per project",
                "The package names no project; the run does",
                "The run of an audit needs the project it works on, and the project is the operator's word",
                "\"projects\": {\"shop\": \"/srv/shop\"}", "You cannot name a project",
                "leave the argument `project` of the tools out", "Do not look for a way around it",
                "they reach the registers of the project the operator bound to the folder this server works in",
                "`findings_verify` checks one of them", "`findings_assign`", "only when the person said so");
    }

    @Test
    void guide_namesEveryBlockTypeTheEngineRuns() {
        String guide = textOf(mcp.call("guide", args("topic", "authoring")));

        for (String type : List.of("shell", "simple", "agentic", "retry", "conditional", "switch", "while", "foreach",
                "approval", "human-input", "sub-workflow", "compress", "compress-ai")) {
            assertThat(guide).as(type).contains("`" + type + "`");
        }
    }

    @Test
    void theWorkflowTheGuideShows_isOneThatCheckAcceptsWithoutAWord() throws IOException {
        String guide = textOf(mcp.call("guide", null));
        int start = guide.indexOf("```json\n") + 8;
        String workflow = guide.substring(start, guide.indexOf("```", start))
                .replace("<a UUID of this workflow>", "5b2f4c1e-8a3d-4e6f-9b1a-2c3d4e5f6a7b");
        Path pkg = Files.createDirectories(work.resolve("licenses"));
        Files.writeString(pkg.resolve("workflow.json"), workflow);

        JsonNode checked = mcp.call("check", args("package", pkg.toString()));

        assertThat(checked.path("isError").asBoolean()).as(checked.toString()).isFalse();
        assertThat(checked.path("structuredContent").path("findings")).as(checked.toString()).isEmpty();
    }

    @Test
    void guide_pagesThroughTheWholeDocumentsOfTheRepository() throws IOException {
        pagesThrough("format", "docs/workflow-format.md");
        pagesThrough("registers", "docs/registers.md");
    }

    @Test
    void guide_search_givesOnlyThePartsOfATopicThatHoldEveryWord() throws IOException {
        JsonNode found = mcp.call("guide", args("topic", "format", "search", "Execution_Mode foreach"));
        JsonNode nothing = mcp.call("guide", args("topic", "format", "search", "zeppelin"));
        JsonNode paged = mcp.call("guide", args("topic", "format", "search", "the", "cursor", "10"));
        JsonNode blank = mcp.call("guide", args("topic", "format", "search", "  "));

        assertThat(found.path("isError").asBoolean()).as(found.toString()).isFalse();
        int matches = found.path("structuredContent").path("matches").asInt();
        assertThat(matches).isBetween(1, 20);
        String text = textOf(found);
        assertThat(text.length()).isLessThan(Files.readString(REPOSITORY.resolve("docs/workflow-format.md")).length() / 4);
        assertThat(text.split("\n\n")).hasSize(matches).allSatisfy(part ->
                assertThat(part.toLowerCase(java.util.Locale.ROOT)).contains("execution_mode", "foreach"));
        assertThat(nothing.path("isError").asBoolean()).isFalse();
        assertThat(nothing.path("structuredContent").path("matches").asInt()).isZero();
        assertThat(textOf(nothing)).contains("nothing");
        assertThat(paged.path("isError").asBoolean()).isTrue();
        assertThat(blank.path("isError").asBoolean()).isTrue();
    }

    @Test
    void guide_search_thatFindsMoreThanAPage_saysSo_andStaysWithinThePage() {
        JsonNode many = mcp.call("guide", args("topic", "format", "search", "the"));

        assertThat(textOf(many).length()).isLessThanOrEqualTo(McpGuide.PAGE_CHARS + 200);
        assertThat(many.path("structuredContent").path("cut").asBoolean()).isTrue();
        assertThat(textOf(many)).contains("narrow");
    }

    private void pagesThrough(String topic, String document) throws IOException {
        StringBuilder format = new StringBuilder();
        String cursor = null;
        int pages = 0;
        do {
            ObjectNode arguments = args("topic", topic);
            if (cursor != null) {
                arguments.put("cursor", cursor);
            }
            JsonNode page = mcp.call("guide", arguments);
            assertThat(page.path("isError").asBoolean()).as(page.toString()).isFalse();
            assertThat(textOf(page).length()).isLessThanOrEqualTo(McpTools.MAX_TEXT);
            format.append(textOf(page));
            cursor = page.path("structuredContent").path("next_cursor").asText(null);
            pages++;
        } while (cursor != null && pages < 50);

        assertThat(pages).isGreaterThan(1);
        assertThat(cursor).isNull();
        assertThat(withoutSpace(format.toString())).as(topic)
                .isEqualTo(withoutSpace(Files.readString(REPOSITORY.resolve(document))));
    }

    @Test
    void guide_refusesATopicItDoesNotHave_andACursorBeyondTheText() {
        JsonNode unknown = mcp.call("guide", args("topic", "../cli"));
        JsonNode beyond = mcp.call("guide", args("topic", "authoring", "cursor", "99999999"));

        assertThat(unknown.path("isError").asBoolean()).isTrue();
        assertThat(unknown.path("structuredContent").path("text").asText()).contains("authoring", "format",
                "registers");
        assertThat(beyond.path("isError").asBoolean()).isTrue();
    }

    @Test
    void examples_listsThePackagesTheJarCarries_eachWithWhatItShows() {
        JsonNode list = mcp.call("examples", null);

        assertThat(list.path("isError").asBoolean()).isFalse();
        JsonNode examples = list.path("structuredContent").path("examples");
        assertThat(examples).extracting(e -> e.path("name").asText()).containsExactly("hello", "ai-hello",
                "audit-hello", "self-audit/build", "self-audit/architecture", "self-audit/security",
                "self-audit/security-changes");
        assertThat(examples).allSatisfy(example -> {
            assertThat(example.path("shows").asText()).isNotBlank();
            assertThat(example.path("files")).extracting(JsonNode::asText).startsWith("workflow.json", "README.md");
        });
        // The two small ones come whole, so that a client sees a script and the envelope of an audit
        assertThat(examples.path(0).path("files")).extracting(JsonNode::asText)
                .containsExactly("workflow.json", "README.md", "scripts/count-files.sh");
        assertThat(examples.path(2).path("files")).extracting(JsonNode::asText).containsExactly("workflow.json",
                "README.md", "catalog.json", "scripts/measure.sh", "scripts/report.sh");
    }

    @Test
    void examples_handsOutTheFilesAsTheRepositoryHasThem() throws IOException {
        for (JsonNode example : mcp.call("examples", null).path("structuredContent").path("examples")) {
            String name = example.path("name").asText();
            for (JsonNode listed : example.path("files")) {
                String file = listed.asText();
                ObjectNode arguments = args("name", name, "file", file);
                StringBuilder whole = new StringBuilder();
                String cursor = null;
                int pages = 0;
                do {
                    if (cursor != null) {
                        arguments.put("cursor", cursor);
                    }
                    JsonNode page = mcp.call("examples", arguments);
                    assertThat(page.path("isError").asBoolean()).as(name + "/" + file + page).isFalse();
                    whole.append(textOf(page));
                    cursor = page.path("structuredContent").path("next_cursor").asText(null);
                    pages++;
                } while (cursor != null && pages < 50);

                assertThat(withoutSpace(whole.toString())).as(name + "/" + file).isEqualTo(withoutSpace(
                        Files.readString(REPOSITORY.resolve("examples").resolve(name).resolve(file))));
            }
        }
    }

    @Test
    void examples_withoutAFile_givesTheWorkflow() throws IOException {
        JsonNode hello = mcp.call("examples", args("name", "hello"));

        assertThat(hello.path("structuredContent").path("file").asText()).isEqualTo("workflow.json");
        assertThat(JSON.readTree(textOf(hello)).path("name").asText()).isEqualTo("Hello");
    }

    @Test
    void examples_refusesWhatTheJarDoesNotCarry() {
        JsonNode unknown = mcp.call("examples", args("name", "self-audit/testing"));
        JsonNode escaped = mcp.call("examples", args("name", "../../docs"));
        JsonNode otherFile = mcp.call("examples", args("name", "ai-hello", "file", "scripts/count-files.sh"));
        JsonNode fileAlone = mcp.call("examples", args("file", "README.md"));

        assertThat(List.of(unknown, escaped, otherFile, fileAlone))
                .allSatisfy(refused -> assertThat(refused.path("isError").asBoolean()).as(refused.toString()).isTrue());
        assertThat(textOf(unknown)).contains("hello", "self-audit/security");
    }

    @Test
    void thePromptToDesignAWorkflow_carriesTheTaskOfThePerson_andTheWayOfWorking() {
        ObjectNode get = JSON.createObjectNode().put("name", "design_workflow");
        get.set("arguments", args("task", "Check the licenses of our dependencies\nbefore each release, 100% of them"));
        JsonNode prompt = mcp.request("prompts/get", get).path("result");
        String text = prompt.path("messages").path(0).path("content").path("text").asText();

        assertThat(prompt.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(text).contains("Check the licenses of our dependencies\nbefore each release, 100% of them",
                "guide", "examples", "check", "describe", "deterministic",
                "where a model adds what no script can", "a register for a decision per finding");
        assertThat(text).contains("Start no run");
    }

    @Test
    void thePromptToDesignAWorkflow_refusesATaskThatIsNoText() {
        for (String bad : List.of("", "  ", "a\u0000b", "esc\u001b[31m", "x".repeat(2001))) {
            ObjectNode get = JSON.createObjectNode().put("name", "design_workflow");
            get.set("arguments", args("task", bad));

            assertThat(mcp.request("prompts/get", get).path("error").path("code").asInt())
                    .as(bad.length() > 20 ? "long" : bad).isEqualTo(-32602);
        }
    }

    @Test
    void theInstructionsOfTheServer_pointAtTheGuide() {
        assertThat(McpServer.INSTRUCTIONS).contains("'guide'", "'examples'");
    }

    /** What a client knows before its first call: a register has a project, and the project is not the client's to choose. */
    @Test
    void theInstructionsOfTheServer_sayThatARegisterIsKeptPerProject_andWhoseWordTheProjectIs() {
        assertThat(McpServer.INSTRUCTIONS).contains("A register is kept per audit and project",
                "'findings_registers'", "the project the operator bound to the folder this server works in",
                "You cannot name another", "You see the runs of that project and the runs that name none",
                "'findings_verify'");
    }
}
