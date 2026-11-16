package traceworks.core.describe;

import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.LoadedPackage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionTextTest {

    private static final String SAMPLE = """
            {"id":"wf-1","name":"Deploy","description":"Builds\\nand ships.","default_context":{"target":"staging"},
             "requires":["container.network"],
             "metadata":{"audit":{"enabled":true,"key":"deploys","category":"ops","report_block":"report"}},
             "blocks":[
              {"type":"shell","name":"build","config":{"command":"make build\\nmake more","network":true,
               "secrets":["DEPLOY_TOKEN"]}},
              {"type":"simple","name":"judge","config":{"command":"Judge it.","provider":"claude","model":"opus",
               "tools":"read"}},
              {"type":"switch","name":"route","config":{"expression":"{{ target }}","cases":[
                {"value":"prod","blocks":[{"type":"shell","name":"careful","config":{"command":"echo careful"}}]}],
                "default":[{"type":"shell","config":{"command":"echo fast"}}]}},
              {"type":"approval","name":"publish","config":{"question":"Publish?"}},
              {"type":"sub-workflow","name":"after","config":{"workflow_id":"../after"}},
              {"type":"shell","name":"report","config":{"command":"cat report.json"}}]}""";

    private static List<String> lines(String json, Map<String, String> children) {
        return DescriptionText.lines(Described.of(json, children));
    }

    @Test
    void theText_isTheHeaderTheInputsTheBlocksAndTheCounts() {
        WorkflowDefinition definition = Described.definition(SAMPLE);
        List<String> lines = DescriptionText.lines(WorkflowDescriber.describe(new LoadedPackage(
                Path.of("/packages/deploy"), definition, new byte[0], "abc123", "c0ffee", true), null));

        assertThat(lines).containsSubsequence(
                "Workflow  Deploy (wf-1)",
                "  Builds and ships.",
                "Package   /packages/deploy",
                "Digest    abc123",
                "Commit    c0ffee (with uncommitted changes)",
                "",
                "Inputs",
                "  target = staging",
                "Requires  container.network",
                "Audit     register deploys, category ops, report block report",
                "",
                "Blocks",
                "  0   build                    shell",
                "      make build",
                "      shell: network open, workspace read-only, secrets DEPLOY_TOKEN, image: the run's shell image",
                "  1   judge                    simple (claude opus)",
                "      Judge it.",
                "      ai: provider claude, model opus, tools read, web none, workspace read-only",
                "  2   route                    switch",
                "      case \"prod\":",
                "        -   careful                  shell",
                "            echo careful",
                "      default:",
                "        -   (unnamed)                shell",
                "            echo fast",
                "  3   publish                  approval",
                "      Publish?",
                "      person: a person is asked",
                "  4   after                    sub-workflow",
                "      ../after",
                "      workflow ../after: not shown: no other workflow can be looked up here",
                "  5   report                   shell",
                "",
                "8 blocks: 1 AI step, 1 shell step with network, 1 step that asks a person; 1 sub-workflow not shown,"
                        + " its blocks not counted");
        assertThat(lines.get(lines.size() - 2)).startsWith("The operator's settings decide the rest:");
        assertThat(lines.getLast()).isEqualTo("Nothing was checked: 'traceworks check <package>' says what a run"
                + " would refuse.");
    }

    @Test
    void aWorkflowWithoutInputsRequirementsOrAudit_saysSoBriefly() {
        List<String> lines = lines("{\"id\":\"wf\",\"name\":\"Bare\",\"blocks\":[]}", Map.of());

        assertThat(lines).contains("Workflow  Bare (wf)", "Inputs    none", "Requires  nothing", "Blocks    none",
                "0 blocks: 0 AI steps, 0 shell steps with network, 0 steps that ask a person");
        assertThat(lines).noneMatch(line -> line.startsWith("Audit") || line.startsWith("Commit"));
    }

    @Test
    void aResolvedChild_isIndentedBelowItsBlock() {
        List<String> lines = lines("""
                {"id":"wf","blocks":[{"type":"sub-workflow","name":"after","config":{"workflow_id":"../after"}}]}""",
                Map.of("../after", """
                        {"id":"after-1","name":"After","blocks":[
                         {"type":"shell","name":"notify","config":{"command":"notify"}}]}"""));

        assertThat(lines).containsSubsequence(
                "  0   after                    sub-workflow",
                "      ../after",
                "      workflow After (after-1):",
                "        0   notify                   shell",
                "            notify",
                "            shell: network none, workspace read-only, image: the run's shell image");
    }

    /** A line is one line: text of the package cannot add lines of its own to the description. */
    @Test
    void textWithLineBreaks_staysOnItsLine() {
        List<String> lines = lines("""
                {"id":"wf","name":"Two\\nlines","description":"a\\n\\n  b","blocks":[
                 {"type":"shell","name":"x\\ny","config":{"command":"echo 1","container_image":"img\\nDigest    fake"}}]}""",
                Map.of());

        assertThat(lines).noneMatch(line -> line.contains("\n"));
        assertThat(lines).contains("Workflow  Two lines (wf)", "  a b");
        assertThat(lines).noneMatch(line -> line.startsWith("Digest    fake"));
    }

    @Test
    void anAuditBlockWithProblems_namesThem() {
        List<String> lines = lines("{\"id\":\"wf\",\"metadata\":{\"audit\":{\"enabled\":true,\"key\":\"k\"}},"
                + "\"blocks\":[]}", Map.of());

        assertThat(lines).anyMatch(line -> line.startsWith("Audit     invalid: ")
                && line.contains("metadata.audit.category is missing"));
    }
}
