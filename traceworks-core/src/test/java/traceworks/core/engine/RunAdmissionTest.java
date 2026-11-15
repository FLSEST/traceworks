package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check before the first block: a workflow that would run differently from what it says is refused as a
 * whole. A refused run executes no block, reports no step and ends {@code FAILED} with
 * every reason in its error; its last event is still {@link RunFinished}.
 */
class RunAdmissionTest {

    /** The core block types; the test executor runs them all by recording the block. */
    private static final Set<String> TYPES = Set.of("shell", "simple", "agentic", "retry", "conditional", "switch",
            "while", "foreach", "approval", "human-input", "sub-workflow", "compress", "compress-ai");

    private static final String ISOLATED = "workspace.mode is isolated — in Traceworks the operator isolates a run,"
            + " not the workflow: remove the setting and start the run with --source";

    private final List<BlockDefinition> executed = new ArrayList<>();
    private final RecordingRunEvents events = new RecordingRunEvents();
    private final WorkflowEngine engine = new WorkflowEngine(new BlockTypeRegistry(List.of(new BlockExecutor() {
        @Override
        public StepResult execute(BlockDefinition block, ExecutionContext context) {
            executed.add(block);
            return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED).output("ok")
                    .build();
        }

        @Override
        public Set<String> supportedTypes() {
            return TYPES;
        }
    })));

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    private static WorkflowDefinition workflow(List<BlockDefinition> blocks) {
        return workflow(blocks, Map.of(), null, WorkflowDefinition.FORMAT, WorkflowDefinition.FORMAT_VERSION);
    }

    private static WorkflowDefinition workflow(List<BlockDefinition> blocks, Map<String, Object> metadata,
                                               List<String> requires, String format, Integer formatVersion) {
        return new WorkflowDefinition("wf-1", "Admission", null, blocks, null, null, null, null, metadata, Map.of(),
                requires, format, formatVersion);
    }

    private RunOutcome run(WorkflowDefinition definition) {
        return engine.run(definition, Map.of(), RunOptions.builder().runId("run-1").events(events).build());
    }

    /** Runs the workflow and checks that it was refused with exactly these reasons, before anything ran. */
    private void assertRefused(WorkflowDefinition definition, String... reasons) {
        RunOutcome outcome = run(definition);

        String error = "workflow refused: " + String.join("; ", reasons);
        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo(error);
        assertThat(outcome.steps()).isEmpty();
        assertThat(executed).as("no block runs").isEmpty();
        assertThat(events.all()).as("no StepStarted, only the end of the run")
                .containsExactly(new RunFinished(RunStatus.FAILED, Map.of(), error));
    }

    @Test
    void aValidWorkflow_isAdmitted() {
        Map<String, Object> switchConfig = Map.of("expression", "{{ status }}",
                "cases", List.of(Map.of("value", "ok", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "echo ok"))))),
                "default", List.of(Map.of("type", "simple", "config", Map.of("command", "Explain"))));
        BlockDefinition withEmptySkills = block("simple", "ask", Map.of("command", "Summarise"));
        withEmptySkills.putUnknownField("skillIds", List.of());
        WorkflowDefinition definition = workflow(List.of(
                block("shell", "build", Map.of("command", "make", "workspace", Map.of("mode", "project"))),
                withEmptySkills,
                block("retry", "tests", Map.of("command", "make test", "execution_mode", "shell")),
                block("foreach", "children", Map.of("items", List.of("a"), "workflow_id", "wf-child")),
                block("switch", "route", switchConfig)),
                Map.of("workspace", Map.of("mode", "project")), List.copyOf(WorkflowDefinition.KNOWN_REQUIREMENTS),
                WorkflowDefinition.FORMAT, WorkflowDefinition.FORMAT_VERSION);

        RunOutcome outcome = run(definition);

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executed).extracting(BlockDefinition::getName)
                .containsExactly("build", "ask", "tests", "children", "route");
    }

    // ---- requirements ----

    private static WorkflowDefinition requiring(List<String> requires, BlockDefinition... blocks) {
        return workflow(List.of(blocks), Map.of(), requires, WorkflowDefinition.FORMAT,
                WorkflowDefinition.FORMAT_VERSION);
    }

    private static WorkflowDefinition withProvider(String provider, List<String> requires, BlockDefinition... blocks) {
        return new WorkflowDefinition("wf-1", "Admission", null, List.of(blocks), null, null, provider, null,
                Map.of(), Map.of(), requires, WorkflowDefinition.FORMAT, WorkflowDefinition.FORMAT_VERSION);
    }

    @Test
    void networkWithoutRequirement_isRefused() {
        assertRefused(requiring(null,
                        block("shell", "fetch", Map.of("command", "curl x", "network", true)),
                        block("retry", "again", Map.of("command", "curl y", "execution_mode", "shell",
                                "network", true))),
                "Block 1 \"fetch\": network: true needs \"container.network\" in requires — add it to the"
                        + " workflow's requires");
    }

    /** A setting that no shell step reads asks for nothing: the AI step of a retry has no network setting. */
    @Test
    void networkWhereNoShellStepReadsIt_needsNoRequirement() {
        RunOutcome outcome = run(requiring(null, block("retry", "ask", Map.of("command", "Is it up?",
                "execution_mode", "ai", "network", true))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void packageImageWithoutRequirement_isRefused() {
        Map<String, Object> switchConfig = Map.of("expression", "{{ status }}",
                "cases", List.of(Map.of("value", "ok", "blocks", List.of(Map.of("type", "shell", "name", "scan",
                        "config", Map.of("command", "scan", "container_image", "./images/scanner"))))));

        assertRefused(requiring(List.of("container.network"),
                        block("shell", "fetch", Map.of("command", "curl x", "network", true)),
                        block("switch", "route", switchConfig)),
                "Block 2 \"route\" › cases[0] › Block 1 \"scan\": container_image ./images/scanner needs"
                        + " \"package.images\" in requires — add it to the workflow's requires");
    }

    @Test
    void toolsWithoutRequirement_isRefused() {
        assertRefused(requiring(null,
                        block("simple", "summary", Map.of("command", "Summarise", "tools", "none")),
                        block("simple", "review", Map.of("command", "Review", "tools", "read"))),
                "Block 2 \"review\": tools: read needs \"ai.tools\" in requires — add it to the workflow's requires");
    }

    @Test
    void declaredRequirements_areAdmitted() {
        RunOutcome outcome = run(requiring(List.of("container.network", "package.images", "ai.tools"),
                block("shell", "fetch", Map.of("command", "curl x", "network", true)),
                block("shell", "scan", Map.of("command", "scan", "container_image", "./images/scanner")),
                block("agentic", "fix", Map.of("goal", "Fix it", "tools", "shell"))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executed).hasSize(3);
    }

    /**
     * The credential names of the AI providers are no secret names: a credential reaches only its
     * provider's containers, never a step that names it.
     */
    @Test
    void aCredentialName_isRefusedAsASecretName() {
        assertRefused(requiring(null, block("shell", "deploy", Map.of("command", "./deploy",
                        "secrets", List.of("ANTHROPIC_API_KEY", "DEPLOY_TOKEN")))),
                "Block 1 \"deploy\": secrets: \"ANTHROPIC_API_KEY\" is reserved — it is the credential of an AI"
                        + " provider, which only that provider's containers get; store the value under another name,"
                        + " such as DEPLOY_ANTHROPIC_API_KEY");
    }

    // ---- the tool policy ----

    @Test
    void agenticWithoutTools_isRefused() {
        assertRefused(requiring(null, block("agentic", "fix", Map.of("goal", "Fix the tests"))),
                "Block 1 \"fix\": an agentic block must state tools (none, read, write or shell) —"
                        + " Traceworks gives it no tools it does not name, and without tools its actions change"
                        + " nothing");
    }

    /** The run admission refuses what the analyzer reports for web: one copy of the rules. */
    @Test
    void web_withoutItsRequirement_isRefused() {
        assertRefused(requiring(List.of(),
                        block("simple", "look", Map.of("command", "x", "web", "search"))),
                "Block 1 \"look\": web: search needs \"ai.web\" in requires — add it to the workflow's requires");
    }

    @Test
    void web_onPi_withoutHosts_orNextToUntrustedInputFalse_isRefused() {
        assertRefused(requiring(List.of("ai.web"),
                        block("simple", "a", Map.of("command", "x", "web", "search", "provider", "PI")),
                        block("simple", "b", Map.of("command", "x", "web", "fetch")),
                        block("simple", "c", Map.of("command", "x", "web", "search", "untrusted_input", false))),
                "Block 1 \"a\": web: search on a step that runs on PI: Pi has no web tool — remove web, or run the"
                        + " step on CLAUDE",
                "Block 2 \"b\": web: fetch needs web_hosts, the hosts a page may be fetched from: a list such as"
                        + " [\"docs.example.org\"], or [\"*\"] for any public host",
                "Block 3 \"c\": untrusted_input: false next to web: search — what a model reads from the web is"
                        + " untrusted, so a step with web always runs as with untrusted_input: true; remove"
                        + " untrusted_input: false");
    }

    @Test
    void unknownTools_isRefused_alsoAPlaceholder() {
        assertRefused(requiring(List.of("ai.tools"),
                        block("simple", "a", Map.of("command", "x", "tools", "all")),
                        block("agentic", "b", Map.of("goal", "x", "tools", "{{ tools }}"))),
                "Block 1 \"a\": tools: \"all\" is not one of none, read, write or shell",
                "Block 2 \"b\": tools: \"{{ tools }}\" is not one of none, read, write or shell");
    }

    @Test
    void unrestrictedTools_isRefused_asAnyOtherUnknownValue() {
        assertRefused(requiring(List.of("ai.tools"),
                        block("simple", "a", Map.of("command", "x", "tools", "unrestricted")),
                        block("agentic", "b", Map.of("goal", "x", "tools", "Unrestricted", "provider", "PI"))),
                "Block 1 \"a\": tools: \"unrestricted\" is not one of none, read, write or shell",
                "Block 2 \"b\": tools: \"Unrestricted\" is not one of none, read, write or shell");
    }

    @Test
    void codexAsTheWorkflowProvider_isRefused_onceWhateverTheBlocks() {
        assertRefused(withProvider("CODEX", List.of("ai.tools"),
                        block("simple", "review", Map.of("command", "Review", "tools", "read")),
                        block("conditional", "check", Map.of("condition", "ok?", "then_command", "y",
                                "execution_mode", "ai"))),
                "provider CODEX: Codex is not supported; Traceworks runs Claude and Pi — use CLAUDE or PI");
    }

    @Test
    void codexOnABlock_isRefused_inAnyCase() {
        assertRefused(requiring(List.of("ai.tools"),
                        block("simple", "review", Map.of("command", "Review", "tools", "read", "provider", "CODEX")),
                        block("approval", "go", Map.of("question", "Go?", "actor", "ai", "provider", "codex")),
                        block("compress-ai", "short", Map.of("source", "review", "provider", "CLAUDE"))),
                "Block 1 \"review\": provider CODEX: Codex is not supported; Traceworks runs Claude and Pi — use"
                        + " CLAUDE or PI",
                "Block 2 \"go\": provider codex: Codex is not supported; Traceworks runs Claude and Pi — use CLAUDE"
                        + " or PI");
    }

    @Test
    void mcpServersNonEmpty_isRefused() {
        assertRefused(requiring(null,
                        block("simple", "a", Map.of("command", "x", "mcp_servers", List.of("github"))),
                        block("simple", "b", Map.of("command", "x", "mcp_servers", List.of())),
                        block("agentic", "c", Map.of("goal", "x", "tools", "none", "mcp_servers", "github"))),
                "Block 1 \"a\": mcp_servers: only [] is accepted — Traceworks gives AI steps no MCP servers yet",
                "Block 3 \"c\": mcp_servers: only [] is accepted — Traceworks gives AI steps no MCP servers yet");
    }

    @Test
    void userMcpServersTrue_isRefused() {
        assertRefused(requiring(null,
                        block("agentic", "fix", Map.of("goal", "x", "tools", "none", "user_mcp_servers", true)),
                        block("simple", "ask", Map.of("command", "x", "user_mcp_servers", false))),
                "Block 1 \"fix\": user_mcp_servers: true — Traceworks gives AI steps none of the MCP servers of the"
                        + " user's CLI configuration; remove it");
    }

    /** A simple block in a switch case is a simple block: its tools count, and need the requirement. */
    @Test
    void toolsOfASimpleSwitchCase_needTheRequirement() {
        Map<String, Object> switchConfig = Map.of("expression", "{{ status }}",
                "default", List.of(Map.of("type", "simple", "name", "review", "config",
                        Map.of("command", "Review", "tools", "read"))));

        assertRefused(requiring(null, block("switch", "route", switchConfig)),
                "Block 1 \"route\" › default › Block 1 \"review\": tools: read needs \"ai.tools\" in requires —"
                        + " add it to the workflow's requires");
    }

    /** A definition may have neither format marker nor version. */
    @Test
    void aDefinitionWithoutFormatMarkerAndVersion_isAdmitted() {
        RunOutcome outcome = run(workflow(List.of(block("shell", "build", Map.of("command", "make"))), Map.of(), null,
                null, null));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executed).hasSize(1);
    }

    @Test
    void anUnknownFormat_isRefused() {
        assertRefused(workflow(List.of(block("shell", "build", Map.of("command", "make"))), Map.of(), null,
                "other-workflow", null),
                "format \"other-workflow\" is not traceworks-workflow");
    }

    @Test
    void aNewerFormatVersion_isRefused() {
        assertRefused(workflow(List.of(block("shell", "build", Map.of("command", "make"))), Map.of(), null,
                WorkflowDefinition.FORMAT, 2),
                "formatVersion 2 is newer than this version of Traceworks reads (1)");
    }

    @Test
    void anUnknownRequirement_isRefused() {
        assertRefused(workflow(List.of(block("shell", "build", Map.of("command", "make"))), Map.of(),
                Arrays.asList("ai.tools", "gpu", null), WorkflowDefinition.FORMAT, 1),
                "requires names \"gpu\", which Traceworks does not know (known: container.network, package.images,"
                        + " ai.tools, ai.web, ai.model_choice)",
                "requires names null, which Traceworks does not know (known: container.network, package.images,"
                        + " ai.tools, ai.web, ai.model_choice)");
    }

    @Test
    void anUnknownBlockType_isRefused_alsoWithoutTypeOrAsANullBlock() {
        assertRefused(workflow(Arrays.asList(
                        block("shell", "build", Map.of("command", "make")),
                        block("shel", "typo", Map.of("command", "make")),
                        block(null, null, Map.of("command", "make")),
                        null)),
                "Block 2 \"typo\": unknown block type \"shel\" (known: " + String.join(", ", sorted(TYPES)) + ")",
                "Block 3: no block type",
                "Block 4: not a block (null)");
    }

    @Test
    void anUnknownBlockTypeInASwitchCase_isRefused() {
        Map<String, Object> switchConfig = Map.of("expression", "{{ status }}",
                "cases", List.of(Map.of("value", "ok", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "echo ok")),
                        Map.of("type", "integration", "name", "notify", "config", Map.of())))));

        assertRefused(workflow(List.of(block("switch", "route", switchConfig))),
                "Block 1 \"route\" › cases[0] › Block 2 \"notify\": unknown block type \"integration\" (known: "
                        + String.join(", ", sorted(TYPES)) + ")");
    }

    @Test
    void anInvalidExecutionMode_isRefused_alsoInANestedBlock() {
        Map<String, Object> switchConfig = Map.of("expression", "{{ status }}",
                "default", List.of(Map.of("type", "simple", "config", Map.of("command", "x",
                        "execution_mode", "bash"))));

        assertRefused(workflow(List.of(
                        block("while", "loop", Map.of("command", "x", "execution_mode", "claude")),
                        block("switch", null, switchConfig))),
                "Block 1 \"loop\": unknown execution_mode 'claude' — use \"ai\" (provider from the block or"
                        + " workflow) or \"shell\"",
                "Block 2 › default › Block 1: unknown execution_mode 'bash' — use \"ai\" (provider from the block or"
                        + " workflow) or \"shell\"");
    }

    @Test
    void aMissingExecutionMode_isRefused_whereTheBlockNeedsOne() {
        assertRefused(workflow(List.of(
                        block("conditional", "check", Map.of("condition", "x", "then_command", "y")),
                        block("retry", null, Map.of("command", "make test")),
                        block("while", "loop", Map.of("command", "x", "condition_command", "y")),
                        block("foreach", "each", Map.of("items", List.of("a"), "command", "echo {{ item }}")),
                        block("foreach", "children", Map.of("items", List.of("a"), "workflow_id", "wf-child")))),
                "Block 1 \"check\": execution_mode is required (shell or ai)",
                "Block 2: execution_mode is required (shell or ai)",
                "Block 3 \"loop\": execution_mode is required (shell or ai)",
                "Block 4 \"each\": execution_mode is required (shell or ai)");
    }

    @Test
    void skillIds_areRefused() {
        BlockDefinition withSkills = block("simple", "ask", Map.of("command", "Summarise"));
        withSkills.putUnknownField("skillIds", List.of("skill-1", "skill-2"));

        assertRefused(workflow(List.of(withSkills)),
                "Block 1 \"ask\": skillIds [skill-1, skill-2] — Traceworks has no skills, so the step would run"
                        + " without them");
    }

    @Test
    void anIsolatedWorkspaceInTheMetadata_isRefused() {
        assertRefused(workflow(List.of(block("shell", "build", Map.of("command", "make"))),
                        Map.of("workspace", Map.of("mode", "isolated")), null, WorkflowDefinition.FORMAT, 1),
                ISOLATED);
    }

    /** The first block's workspace setting is read when the metadata has none. */
    @Test
    void anIsolatedWorkspaceOnTheFirstBlock_isRefused() {
        assertRefused(workflow(List.of(
                        block("shell", "build", Map.of("command", "make", "workspace", Map.of("mode", "isolated"))))),
                ISOLATED);
    }

    @Test
    void aDollarPlaceholderInASwitchExpression_isRefused() {
        Map<String, Object> switchConfig = Map.of("expression", "${status}",
                "cases", List.of(Map.of("value", "ok", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "echo ok"))))));

        assertRefused(workflow(List.of(block("switch", "route", switchConfig))),
                "Block 1 \"route\": the switch expression uses ${status}, which Traceworks does not resolve; write"
                        + " {{ status }}");
    }

    /** Docker makes a mount without a mode writable; Traceworks wants it written out, nested blocks included. */
    @Test
    void aMountWithoutMode_isRefused_alsoInANestedBlock() {
        Map<String, Object> switchConfig = Map.of("expression", "{{ status }}",
                "default", List.of(Map.of("type", "shell", "config", Map.of("command", "ls",
                        "container_image", "alpine:3", "mounts", List.of("{{ out }}:/out")))));

        assertRefused(workflow(List.of(
                        block("shell", "box", Map.of("command", "ls", "container_image", "alpine:3",
                                "mounts", List.of("/srv:/data", "/c:/c:rw", "/ro:/ro:ro"))),
                        block("switch", "route", switchConfig))),
                "Block 1 \"box\": the mount \"/srv:/data\" has no mode — write \"/srv:/data:ro\" (read-only) or"
                        + " \"/srv:/data:rw\" (writable)",
                "Block 2 \"route\" › default › Block 1: the mount \"{{ out }}:/out\" has no mode — write"
                        + " \"{{ out }}:/out:ro\" (read-only) or \"{{ out }}:/out:rw\" (writable)");
    }

    /** A secret reaches the step as an environment variable, so a name the step or the client sets is refused. */
    @Test
    void reservedSecretNames_areRefused() {
        Map<String, Object> switchConfig = Map.of("expression", "{{ status }}",
                "default", List.of(Map.of("type", "shell", "config", Map.of("command", "ls",
                        "secrets", List.of("LC_ALL", "OK_TOKEN")))));

        assertRefused(workflow(List.of(
                        block("shell", "deploy", Map.of("command", "./deploy",
                                "secrets", List.of("PATH", "TRACEWORKS_X", "DEPLOY_TOKEN"))),
                        block("switch", "route", switchConfig))),
                "Block 1 \"deploy\": secrets: \"PATH\" is reserved — the step's environment or the container"
                        + " client uses it; choose another name",
                "Block 1 \"deploy\": secrets: \"TRACEWORKS_X\" is reserved — names that start with TRACEWORKS_"
                        + " are Traceworks' own; choose another name",
                "Block 2 \"route\" › default › Block 1: secrets: \"LC_ALL\" is reserved — names that start with"
                        + " LC_ set the locale; choose another name");
    }

    /** A secret name is literal: a placeholder would let run data pick which value a step gets. */
    @Test
    void secretsThatAreNoNames_areRefused() {
        assertRefused(workflow(List.of(
                        block("shell", "one", Map.of("command", "ls",
                                "secrets", List.of("{{ which }}", "my-token", 5))),
                        block("retry", "two", Map.of("command", "ls", "execution_mode", "shell",
                                "secrets", "DEPLOY_TOKEN")))),
                "Block 1 \"one\": secrets: \"{{ which }}\" is a placeholder — secret names are literal",
                "Block 1 \"one\": secrets: \"my-token\" is not a secret name — use capital letters, digits and _,"
                        + " not starting with a digit",
                "Block 1 \"one\": secrets: 5 is not a name",
                "Block 2 \"two\": secrets is not a list of names — write it as [\"NAME\"]");
    }

    @Test
    void everyReasonIsReported_inDefinitionOrder() {
        BlockDefinition withSkills = block("simple", "ask", Map.of("command", "Summarise"));
        withSkills.putUnknownField("skillIds", List.of("skill-1"));
        WorkflowDefinition definition = workflow(List.of(
                        block("shel", "typo", Map.of("command", "make")),
                        block("retry", "tests", Map.of("command", "make test")),
                        withSkills),
                Map.of("workspace", Map.of("mode", "isolated")), List.of("gpu"), "other", 3);

        assertRefused(definition,
                "format \"other\" is not traceworks-workflow",
                "formatVersion 3 is newer than this version of Traceworks reads (1)",
                "requires names \"gpu\", which Traceworks does not know (known: container.network, package.images,"
                        + " ai.tools, ai.web, ai.model_choice)",
                ISOLATED,
                "Block 1 \"typo\": unknown block type \"shel\" (known: " + String.join(", ", sorted(TYPES)) + ")",
                "Block 2 \"tests\": execution_mode is required (shell or ai)",
                "Block 3 \"ask\": skillIds [skill-1] — Traceworks has no skills, so the step would run without"
                        + " them");
    }

    private static List<String> sorted(Set<String> types) {
        return types.stream().sorted().toList();
    }

    /** {@code network: false} asks for no network, so it needs nothing in {@code requires}. */
    @Test
    void networkFalse_needsNoRequirement() {
        RunOutcome outcome = run(requiring(null, block("shell", "build", Map.of("command", "make", "network", false))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executed).hasSize(1);
    }

    private static Map<String, Object> audit(String key, String reportBlock) {
        return Map.of("audit", Map.of("enabled", true, "key", key, "category", "security", "reportBlock",
                reportBlock));
    }

    private static WorkflowDefinition auditing(Map<String, Object> metadata, BlockDefinition... blocks) {
        return workflow(List.of(blocks), metadata, null, WorkflowDefinition.FORMAT, WorkflowDefinition.FORMAT_VERSION);
    }

    /** A broken audit block is not ignored silently: Traceworks refuses the workflow and names what is wrong. */
    @Test
    void anAuditBlockWithAKeyThatIsNoRegisterKey_isRefused_namingTheProblem() {
        assertRefused(auditing(audit("Bad Key", "report"), block("shell", "report", Map.of("command", "make"))),
                "metadata.audit.key 'Bad Key' is no register key: it must match "
                        + traceworks.core.model.AuditConfig.KEY);
    }

    @Test
    void anAuditBlockThatIsNoObject_isRefused() {
        assertRefused(auditing(Map.of("audit", "yes"), block("shell", "report", Map.of("command", "make"))),
                "metadata.audit must be an object");
    }

    /** The report of an audit is the output of a top-level block: a name no block has would feed the register nothing. */
    @Test
    void anAuditWhoseReportBlockNamesNoTopLevelBlock_isRefused() {
        assertRefused(auditing(audit("architecture", "report"), block("shell", "build", Map.of("command", "make"))),
                "metadata.audit.reportBlock 'report' names no top-level block");
    }

    /** A null block beside an audit is one more reason, not a failure of the check. */
    @Test
    void anAuditWithANullBlock_isRefused_withTheReasons() {
        assertRefused(workflow(Arrays.asList(null, block("shell", "report", Map.of("command", "make"))),
                        audit("architecture", "report"), null, WorkflowDefinition.FORMAT,
                        WorkflowDefinition.FORMAT_VERSION),
                "Block 1: not a block (null)");
    }

    /** The start check of a child takes only its Codex reasons: its other problems wait for its own admission. */
    @Test
    void codexReasons_areOnlyTheCodexOnes() {
        WorkflowDefinition child = requiring(null,
                block("simple", "review", Map.of("command", "Review", "tools", "read")),
                block("simple", "other", Map.of("command", "Other", "provider", "codex")));

        assertThat(RunAdmission.codexReasons(child)).containsExactly("Block 2 \"other\": provider codex: Codex is not"
                + " supported; Traceworks runs Claude and Pi — use CLAUDE or PI");
    }

    @Test
    void anAuditWhoseReportBlockIsATopLevelBlock_isAdmitted() {
        RunOutcome outcome = run(auditing(audit("architecture", "report"),
                block("shell", "report", Map.of("command", "make"))));

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
    }
}
