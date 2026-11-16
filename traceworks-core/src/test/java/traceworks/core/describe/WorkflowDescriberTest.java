package traceworks.core.describe;

import traceworks.core.describe.WorkflowDescription.Block;
import traceworks.core.describe.WorkflowDescription.Line;
import traceworks.core.describe.WorkflowDescription.Section;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.LoadedPackage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDescriberTest {

    private static List<String> facts(Block block, String label) {
        return block.lines().stream().filter(line -> line.label().equals(label)).findFirst().map(Line::facts)
                .orElse(null);
    }

    @Test
    void theHeader_saysWhichWorkflowAndWhichPackage() {
        WorkflowDefinition definition = Described.definition("""
                {"id":"wf-1","name":"Deploy","description":"Builds and ships.","blocks":[]}""");
        WorkflowDescription described = WorkflowDescriber.describe(new LoadedPackage(Path.of("/packages/deploy"),
                definition, new byte[0], "abc123", "c0ffee", true), null);

        assertThat(described.id()).isEqualTo("wf-1");
        assertThat(described.name()).isEqualTo("Deploy");
        assertThat(described.description()).isEqualTo("Builds and ships.");
        assertThat(described.packageDir()).isEqualTo("/packages/deploy");
        assertThat(described.digest()).isEqualTo("abc123");
        assertThat(described.commit()).isEqualTo("c0ffee");
        assertThat(described.uncommittedChanges()).isTrue();
    }

    @Test
    void outsideAGitCheckout_thereIsNoCommit() {
        WorkflowDescription described = Described.of("{\"id\":\"wf\",\"blocks\":[]}");

        assertThat(described.commit()).isNull();
        assertThat(described.uncommittedChanges()).isFalse();
    }

    @Test
    void theInputs_areTheDefaultContextWithItsDefaults_inDefinitionOrder() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","default_context":{"name":"world","retries":3,"tags":["a","b"],"none":null},
                 "requires":["container.network","ai.tools"],"blocks":[]}""");

        assertThat(described.inputs()).containsExactly(Map.entry("name", "world"), Map.entry("retries", "3"),
                Map.entry("tags", "[\"a\",\"b\"]"), Map.entry("none", "null"));
        assertThat(described.requires()).containsExactly("container.network", "ai.tools");
    }

    @Test
    void anAuditWorkflow_namesItsRegister() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","metadata":{"audit":{"enabled":true,"key":"architecture","category":"design",
                 "report_block":"report"}},
                 "blocks":[{"type":"shell","name":"report","config":{"command":"cat r"}}]}""");

        assertThat(described.audit().key()).isEqualTo("architecture");
        assertThat(described.audit().category()).isEqualTo("design");
        assertThat(described.audit().reportBlock()).isEqualTo("report");
        assertThat(described.audit().problems()).isEmpty();
    }

    @Test
    void aWorkflowWithoutAnEnabledAudit_hasNone_andABrokenOneNamesItsProblems() {
        assertThat(Described.of("{\"id\":\"wf\",\"blocks\":[]}").audit()).isNull();
        assertThat(Described.of("{\"id\":\"wf\",\"metadata\":{\"audit\":{\"enabled\":false,\"key\":\"k\"}},"
                + "\"blocks\":[]}").audit()).isNull();

        WorkflowDescription broken = Described.of("""
                {"id":"wf","metadata":{"audit":{"enabled":true,"key":"k"}},"blocks":[]}""");
        assertThat(broken.audit().key()).isEqualTo("k");
        assertThat(broken.audit().problems()).contains("metadata.audit.category is missing");
    }

    @Test
    void aShellStep_saysWhatItMayTouch_theDefaultsIncluded() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"shell","name":"plain","config":{"command":"echo hi\\nsecond"}},
                 {"type":"shell","name":"deploy","config":{"command":"deploy","network":true,"workspace_writable":true,
                  "secrets":["DEPLOY_TOKEN","OTHER"],"container_image":"./images/tools","timeout_seconds":600,
                  "mounts":["/data:/data:ro"]}}]}""");

        Block plain = described.blocks().get(0);
        assertThat(plain.outline().position()).isZero();
        assertThat(plain.outline().name()).isEqualTo("plain");
        assertThat(plain.outline().type()).isEqualTo("shell");
        assertThat(plain.outline().what()).isEqualTo("echo hi");
        assertThat(facts(plain, "shell")).containsExactly("network none", "workspace read-only",
                "image: the run's shell image");

        assertThat(facts(described.blocks().get(1), "shell")).containsExactly("network open", "workspace writable",
                "secrets DEPLOY_TOKEN, OTHER", "image ./images/tools", "mounts /data:/data:ro", "timeout 600 s");
    }

    /** Only the names of secrets exist in a package; no value is ever looked up. */
    @Test
    void aSecret_isNamedAndNeverResolved() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"shell","name":"s","config":{"command":"x","secrets":["API_TOKEN"]}}]}""");

        assertThat(String.join("\n", DescriptionText.lines(described))).contains("secrets API_TOKEN");
    }

    @Test
    void anAiStep_saysProviderModelEffortToolsWebAndUntrustedInput() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","provider":"pi","model":"big","effort":"high","blocks":[
                 {"type":"simple","name":"judge","config":{"command":"Judge this."}},
                 {"type":"simple","name":"read","config":{"command":"Read.","provider":"claude","model":"opus",
                  "effort":"low","tools":"read","web":"fetch","web_hosts":["docs.example.org"],
                  "timeout_seconds":90}},
                 {"type":"agentic","name":"fix","config":{"goal":"Fix it.","provider":"claude","tools":"shell",
                  "workspace_writable":true,"untrusted_input":true,"require_approval":true}}]}""");

        assertThat(facts(described.blocks().get(0), "ai")).containsExactly("provider pi", "model big", "effort high",
                "tools none", "web none");
        assertThat(facts(described.blocks().get(0), "shell")).isNull();
        assertThat(facts(described.blocks().get(1), "ai")).containsExactly("provider claude", "model opus",
                "effort low", "tools read", "web fetch (docs.example.org:443)", "untrusted input",
                "workspace read-only", "timeout 90 s");
        // a model of the workflow is never carried to another provider
        assertThat(facts(described.blocks().get(2), "ai")).containsExactly("provider claude",
                "model: the provider's default", "effort high", "tools shell", "web none", "untrusted input",
                "workspace writable");
        assertThat(facts(described.blocks().get(2), "person")).containsExactly(
                "a person is asked before each action (require_approval)");
    }

    @Test
    void aWorkflowWithoutAProvider_usesTheDefaultOne() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"simple","name":"judge","config":{"command":"Judge."}}]}""");

        assertThat(facts(described.blocks().getFirst(), "ai")).containsExactly("provider CLAUDE (the default)",
                "model: the provider's default", "tools none", "web none");
    }

    @Test
    void aWrapper_isAShellStepOrAnAiStep_byItsExecutionMode() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"retry","name":"flaky","config":{"execution_mode":"shell","command":"make test",
                  "network":true}},
                 {"type":"foreach","name":"each","config":{"execution_mode":"ai","items_source":"list",
                  "items":["a"],"command":"Judge {{ item }}","tools":"read"}}]}""");

        assertThat(facts(described.blocks().get(0), "shell")).contains("network open");
        assertThat(facts(described.blocks().get(0), "ai")).isNull();
        assertThat(facts(described.blocks().get(1), "ai")).contains("tools read");
        assertThat(facts(described.blocks().get(1), "shell")).isNull();
    }

    /**
     * A block runs more than its own command: the test of {@code run_if}, the command that lists a foreach's items, a
     * shell condition and the branches of a conditional. Each is named, since each is a command of the run.
     */
    @Test
    void theCommandsABlockRunsBesidesItsOwn_areNamed() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"foreach","name":"each","config":{"execution_mode":"ai","items_source":"command",
                  "items_command":"ls areas\\nsecond","command":"Judge {{ item }}","run_if":"test -d areas"}},
                 {"type":"while","name":"loop","config":{"execution_mode":"shell","condition":"test -f todo",
                  "command":"work"}},
                 {"type":"conditional","name":"branch","config":{"execution_mode":"shell","condition":"test -f x",
                  "then_command":"echo yes","else_command":"echo no"}},
                 {"type":"while","name":"asked","config":{"execution_mode":"ai","condition":"Is it done?",
                  "command":"Work on."}},
                 {"type":"foreach","name":"static","config":{"execution_mode":"shell","items":["a"],
                  "items_command":"never read","command":"echo {{ item }}"}}]}""");

        assertThat(facts(described.blocks().get(0), "also")).containsExactly(
                "run_if (a shell command without network): test -d areas",
                "items_command (a shell command without network): ls areas");
        assertThat(facts(described.blocks().get(1), "also")).containsExactly(
                "condition (a shell command without network): test -f todo");
        // the condition is the first line shown already
        assertThat(described.blocks().get(2).outline().what()).isEqualTo("test -f x");
        assertThat(facts(described.blocks().get(2), "also")).containsExactly("then: echo yes", "else: echo no");
        assertThat(facts(described.blocks().get(3), "also")).containsExactly("condition (a question to the AI): Is it done?");
        assertThat(facts(described.blocks().get(4), "also")).isNull();
    }

    @Test
    void aQuestion_saysThatAPersonIsAsked_andAnAiActorSaysSo() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"approval","name":"go","config":{"question":"Go?"}},
                 {"type":"human-input","name":"ask","config":{"question":"Which?"}},
                 {"type":"approval","name":"auto","config":{"question":"Go?","actor":"ai"}},
                 {"type":"approval","name":"shadow","config":{"question":"Go?","actor":"shadow"}}]}""");

        assertThat(facts(described.blocks().get(0), "person")).containsExactly("a person is asked");
        assertThat(facts(described.blocks().get(1), "person")).containsExactly("a person is asked");
        assertThat(facts(described.blocks().get(2), "person")).containsExactly(
                "actor ai: an AI answers in place of a person");
        assertThat(facts(described.blocks().get(2), "ai")).contains("provider CLAUDE (the default)", "tools none");
        assertThat(facts(described.blocks().get(3), "person")).containsExactly(
                "actor shadow: a person is asked, an AI call takes part");
        assertThat(described.counts().personSteps()).isEqualTo(3);
        assertThat(described.counts().aiSteps()).isEqualTo(2);
    }

    @Test
    void aSwitch_showsItsCasesAndItsDefault() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"switch","name":"route","config":{"expression":"{{ kind }}",
                 "cases":[{"value":"a|b","blocks":[{"type":"shell","name":"for-a","config":{"command":"echo a",
                   "network":true}}]},
                  {"value":"c","blocks":[{"type":"simple","name":"for-c","config":{"command":"Say c"}}]}],
                 "default":[{"type":"simple","name":"otherwise","config":{"command":"echo d",
                   "execution_mode":"shell"}}]}}]}""");

        Block route = described.blocks().getFirst();
        assertThat(route.sections()).extracting(Section::label).containsExactly("case \"a|b\"", "case \"c\"",
                "default");
        Block forA = route.sections().get(0).blocks().getFirst();
        assertThat(forA.outline().name()).isEqualTo("for-a");
        assertThat(forA.outline().what()).isEqualTo("echo a");
        assertThat(forA.outline().position()).isEqualTo(-1);
        assertThat(facts(forA, "shell")).contains("network open");
        assertThat(facts(route.sections().get(1).blocks().getFirst(), "ai")).contains("tools none");
        // a simple block of a case in shell mode runs as a shell
        assertThat(facts(route.sections().get(2).blocks().getFirst(), "shell")).contains("network none");
        assertThat(described.counts().blocks()).isEqualTo(4);
        assertThat(described.counts().aiSteps()).isEqualTo(1);
        assertThat(described.counts().networkSteps()).isEqualTo(1);
    }

    /** No executor runs a plain {@code config.blocks}: the list is shown as what it is, and counts for nothing. */
    @Test
    void aBlockList_noExecutorRuns_isShownAsNeverRun() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"foreach","name":"each","config":{"execution_mode":"shell",
                 "items":["a"],"command":"echo {{ item }}",
                 "blocks":[{"type":"simple","name":"dead","config":{"command":"never"}}]}}]}""");

        Section dead = described.blocks().getFirst().sections().getFirst();
        assertThat(dead.label()).isEqualTo("blocks");
        assertThat(dead.note()).contains("never run");
        assertThat(dead.blocks().getFirst().outline().name()).isEqualTo("dead");
        assertThat(dead.blocks().getFirst().lines()).isEmpty();
        assertThat(described.counts().blocks()).isEqualTo(1);
        assertThat(described.counts().aiSteps()).isZero();
    }

    @Test
    void aSubWorkflow_isShownWithTheBlocksOfTheWorkflowItResolvesTo() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"sub-workflow","name":"child","config":{"workflow_id":"../child"}},
                 {"type":"foreach","name":"each","config":{"execution_mode":"shell","items":["a"],
                  "workflow_id":"by-id"}}]}""",
                Map.of("../child", """
                        {"id":"child-1","name":"Child","provider":"pi","blocks":[
                         {"type":"simple","name":"inner","config":{"command":"Think."}},
                         {"type":"approval","name":"sure","config":{"question":"Sure?"}}]}""",
                        "by-id", """
                        {"id":"by-id","name":"Per item","blocks":[
                         {"type":"shell","name":"work","config":{"command":"work"}}]}"""));

        Section child = described.blocks().get(0).sections().getFirst();
        assertThat(child.label()).isEqualTo("workflow Child (child-1)");
        assertThat(child.note()).isNull();
        assertThat(child.blocks()).extracting(block -> block.outline().name()).containsExactly("inner", "sure");
        assertThat(child.blocks().get(1).outline().position()).isEqualTo(1);
        // the child's own workflow settings apply to its blocks
        assertThat(facts(child.blocks().getFirst(), "ai")).contains("provider pi");
        assertThat(described.blocks().get(1).sections().getFirst().label()).isEqualTo("workflow Per item (by-id)");
        assertThat(described.counts().blocks()).isEqualTo(5);
        assertThat(described.counts().aiSteps()).isEqualTo(1);
        assertThat(described.counts().personSteps()).isEqualTo(1);
        assertThat(described.counts().notShown()).isZero();
    }

    @Test
    void aSubWorkflowThatCannotBeResolved_isNamedAndSaidNotToBeShown() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"sub-workflow","name":"gone","config":{"workflow_id":"missing"}},
                 {"type":"sub-workflow","name":"late","config":{"workflow_id":"{{ which }}"}}]}""", Map.of());

        Section gone = described.blocks().get(0).sections().getFirst();
        assertThat(gone.label()).isEqualTo("workflow missing");
        assertThat(gone.blocks()).isEmpty();
        assertThat(gone.note()).startsWith("not shown: ").contains("not found");
        Section late = described.blocks().get(1).sections().getFirst();
        assertThat(late.label()).isEqualTo("workflow {{ which }}");
        assertThat(late.note()).isEqualTo("not shown: the reference is a placeholder, resolved when the block runs");
        assertThat(described.counts().notShown()).isEqualTo(2);
        assertThat(described.counts().blocks()).isEqualTo(2);
    }

    @Test
    void aWorkflowThatReachesItselfAgain_isNotRepeated() {
        String self = """
                {"id":"wf","name":"Loop","blocks":[{"type":"sub-workflow","name":"again",
                 "config":{"workflow_id":"wf"}}]}""";

        WorkflowDescription described = Described.of(self, Map.of("wf", self));

        Section again = described.blocks().getFirst().sections().getFirst();
        assertThat(again.blocks()).isEmpty();
        assertThat(again.note()).isEqualTo("not shown: it is the workflow this block is part of");
    }

    /** A package that loads is described whatever its values are; what is wrong is for {@code check} to say. */
    @Test
    void valuesOfTheWrongType_areShownAsInvalid_andNeverFailTheDescription() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"shell","name":"odd","config":{"command":"x","network":"yes","timeout_seconds":"5"}},
                 {"type":"simple","name":"tools","config":{"command":"x","tools":"everything","web":"all"}},
                 {"type":"agentic","name":"untold","config":{"goal":"x"}},
                 {"name":"typeless"}, null]}""");

        assertThat(facts(described.blocks().get(0), "shell")).contains("network: invalid value",
                "timeout: invalid value");
        assertThat(facts(described.blocks().get(1), "ai")).contains("tools: invalid value", "web: invalid value");
        assertThat(facts(described.blocks().get(2), "ai")).contains("tools: not stated");
        assertThat(described.blocks().get(3).outline().type()).isNull();
        assertThat(described.blocks().get(4).outline().name()).isNull();
        assertThat(described.counts().blocks()).isEqualTo(5);
    }

    @Test
    void theCounts_coverBlocksAiStepsNetworkStepsWebAndQuestions() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"shell","name":"a","config":{"command":"x","network":true}},
                 {"type":"shell","name":"b","config":{"command":"x"}},
                 {"type":"simple","name":"c","config":{"command":"x","web":"search"}},
                 {"type":"approval","name":"d","config":{"question":"?"}}]}""");

        assertThat(described.counts()).isEqualTo(new WorkflowDescription.Counts(4, 1, 1, 1, 1, 0));
    }

    /** What the workflow sets for all its AI steps is said once in the header: provider, model and effort. */
    @Test
    void theWorkflowsOwnAiSettings_areNamed_andOnlyTheOnesItSets() {
        WorkflowDescription all = Described.of("""
                {"id":"wf","provider":"pi","model":" sonnet ","effort":"high",
                 "blocks":[{"type":"simple","name":"judge","config":{"command":"Judge."}}]}""");
        WorkflowDescription modelOnly = Described.of("""
                {"id":"wf","model":"opus","blocks":[{"type":"simple","name":"judge","config":{"command":"Judge."}}]}""");
        WorkflowDescription none = Described.of("""
                {"id":"wf","blocks":[{"type":"simple","name":"judge","config":{"command":"Judge."}}]}""");

        assertThat(all.aiDefaults()).containsExactly("provider pi", "model sonnet", "effort high");
        assertThat(modelOnly.aiDefaults()).containsExactly("model opus");
        assertThat(none.aiDefaults()).isEmpty();
    }

    /** {@code run_if} is tested before a top-level block only: in a switch case nothing reads it, so nothing runs. */
    @Test
    void runIfOfABlockInASwitchCase_isNotNamedAsACommandOfTheRun() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"switch","name":"route","config":{"expression":"{{ kind }}",
                 "run_if":"test -d top",
                 "default":[{"type":"shell","name":"inner","config":{"command":"echo d","run_if":"test -d inner"}}]}}]}""");

        Block route = described.blocks().getFirst();
        assertThat(facts(route, "also")).containsExactly("run_if (a shell command without network): test -d top");
        assertThat(facts(route.sections().getFirst().blocks().getFirst(), "also")).isNull();
    }

    /** The workflow's instructions are named by their first line. */
    @Test
    void theInstructions_areNamedByTheirFirstLine() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","instructions":"Answer in English.\\nKeep it short.","blocks":[]}""");

        assertThat(described.instructions()).isEqualTo("Answer in English.");
    }

    /** An agentic step that does not ask before each action is no person step. */
    @Test
    void anAgenticStepWithoutApproval_asksNoPerson() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"agentic","name":"fix","config":{"goal":"Fix it.","tools":"read",
                 "require_approval":false}}]}""");

        assertThat(facts(described.blocks().getFirst(), "person")).isNull();
        assertThat(described.counts().personSteps()).isZero();
    }

    /** A step that asks for the web counts as a web step even when the value is invalid: the run refuses it. */
    @Test
    void anInvalidWebValue_stillCountsAsAWebStep() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"simple","name":"c","config":{"command":"x","web":"all"}}]}""");

        assertThat(described.counts().webSteps()).isEqualTo(1);
    }

    /** An empty list of secrets names none, and is no invalid value. */
    @Test
    void anEmptyListOfSecrets_isNeitherNamedNorInvalid() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[{"type":"shell","name":"s","config":{"command":"x","secrets":[]}}]}""");

        assertThat(facts(described.blocks().getFirst(), "shell")).noneMatch(fact -> fact.startsWith("secrets"));
    }

    @Test
    void secretsThatAreNoList_areShownAsInvalid() {
        WorkflowDescription described = Described.of("""
                {"id":"wf","blocks":[
                 {"type":"shell","name":"odd","config":{"command":"x","secrets":"DEPLOY_LOGIN"}},
                 {"type":"shell","name":"plain","config":{"command":"x"}}]}""");

        assertThat(facts(described.blocks().get(0), "shell")).contains("secrets: invalid value");
        assertThat(facts(described.blocks().get(1), "shell")).noneMatch(fact -> fact.startsWith("secrets"));
    }

    @Test
    void aPackageImage_saysWhatItHolds_fromItsDockerfile() {
        String json = """
                {"id":"wf","blocks":[
                 {"type":"shell","name":"a","config":{"command":"x","container_image":"./images/tools"}},
                 {"type":"shell","name":"b","config":{"command":"x","container_image":"./images/bare"}},
                 {"type":"shell","name":"c","config":{"command":"x","container_image":"./images/none"}}]}""";
        Map<String, traceworks.core.port.PackageImage> images = new java.util.LinkedHashMap<>();
        images.put("tools", new traceworks.core.port.PackageImage("tools", "/p/images/tools", "d1",
                "FROM eclipse-temurin:21-jdk@sha256:abc\nLABEL org.opencontainers.image.description=\"the tool on"
                        + " JDK 21\"\n"));
        images.put("bare", new traceworks.core.port.PackageImage("bare", "/p/images/bare", "d2",
                "FROM debian:bookworm\n"));
        WorkflowDescription described = WorkflowDescriber.describe(new LoadedPackage(Path.of("/packages/sample"),
                Described.definition(json), new byte[0], "d1g35t", null, null, images), null);

        assertThat(facts(described.blocks().get(0), Line.SHELL))
                .contains("image ./images/tools — eclipse-temurin:21-jdk: the tool on JDK 21");
        assertThat(facts(described.blocks().get(1), Line.SHELL)).contains("image ./images/bare — debian:bookworm");
        assertThat(facts(described.blocks().get(2), Line.SHELL)).contains("image ./images/none");
    }

    @Test
    void aPackageImageInAChildOfAnotherPackage_isNotDescribedFromThisPackage() {
        String json = """
                {"id":"wf","blocks":[{"type":"sub-workflow","name":"s","config":{"workflow_id":"child"}}]}""";
        traceworks.core.port.WorkflowResolver children =
                new traceworks.core.port.WorkflowResolver() {
            @Override
            public WorkflowDefinition resolve(String ref) {
                return Described.definition("""
                        {"id":"child","blocks":[{"type":"shell","name":"a","config":{"command":"x",
                         "container_image":"./images/tools"}}]}""");
            }

            @Override
            public boolean fromAnotherPackage(WorkflowDefinition workflow) {
                return true;
            }
        };
        WorkflowDescription described = WorkflowDescriber.describe(new LoadedPackage(Path.of("/packages/sample"),
                Described.definition(json), new byte[0], "d1g35t", null, null, Map.of("tools",
                new traceworks.core.port.PackageImage("tools", "/p/images/tools", "d1",
                        "FROM debian:bookworm\n"))), children);

        Block inChild = described.blocks().get(0).sections().get(0).blocks().get(0);
        assertThat(facts(inChild, Line.SHELL)).contains("image ./images/tools");
    }
}
