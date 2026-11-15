package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requirements and the tool policy as the analyzer reports them: what the run admission
 * refuses is an {@code ERROR}, a requirement nothing uses is a {@code WARNING}.
 */
class RequirementRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    private static List<AnalyzerFinding> findings(String provider, List<String> requires, BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        RequirementRules.apply(new WorkflowDefinition("wf", "Wf", null, List.of(blocks), null, null, provider, null,
                null, null, requires, null, null), out);
        return out;
    }

    private static List<AnalyzerFinding> findings(List<String> requires, BlockDefinition... blocks) {
        return findings(null, requires, blocks);
    }

    @Test
    void networkWithoutRequirement_isRefused() {
        List<AnalyzerFinding> found = findings(null, block("shell", "fetch", Map.of("command", "curl x",
                "network", true)));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("missing-requirement");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockIndex()).isZero();
            assertThat(f.blockName()).isEqualTo("fetch");
            assertThat(f.detail()).isEqualTo("network: true needs \"container.network\" in requires — add it to the"
                    + " workflow's requires; Traceworks refuses the workflow");
        });
    }

    @Test
    void packageImageWithoutRequirement_isRefused_alsoInASwitchCase() {
        Map<String, Object> switchConfig = Map.of("expression", "x", "default", List.of(Map.of("type", "shell",
                "name", "scan", "config", Map.of("command", "scan", "container_image", "./images/scanner"))));

        List<AnalyzerFinding> found = findings(null, block("shell", "build", Map.of("command", "make")),
                block("switch", "route", switchConfig));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("missing-requirement");
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.detail()).startsWith("Block 2 › default › Block 1 \"scan\": container_image ./images/scanner"
                    + " needs \"package.images\" in requires");
        });
    }

    @Test
    void toolsWithoutRequirement_isRefused() {
        List<AnalyzerFinding> found = findings(null, block("simple", "review", Map.of("command", "Review",
                "tools", "write")));

        assertThat(found).extracting(AnalyzerFinding::rule).containsExactly("missing-requirement");
        assertThat(found.getFirst().detail()).startsWith("tools: write needs \"ai.tools\" in requires");
    }

    /** The AI step of a wrapper takes {@code tools} as a simple block does, and needs the requirement for it. */
    @Test
    void toolsOnAWrappersAiStep_withoutRequirement_isRefused() {
        for (String type : List.of("foreach", "retry", "while", "conditional")) {
            List<AnalyzerFinding> found = findings(null, block(type, "judge", Map.of("command", "Judge",
                    "execution_mode", "ai", "tools", "read")));

            assertThat(found).as(type).extracting(AnalyzerFinding::rule).containsExactly("missing-requirement");
            assertThat(found.getFirst().detail()).as(type).startsWith("tools: read needs \"ai.tools\" in requires");
        }
    }

    @Test
    void toolsOnAWrappersAiStep_withTheRequirement_reportNothing() {
        assertThat(findings(List.of("ai.tools"), block("foreach", "judge", Map.of("command", "Judge",
                "execution_mode", "ai", "tools", "read")))).isEmpty();
    }

    @Test
    void unknownToolsOnAWrappersAiStep_isAnInvalidValue() {
        List<AnalyzerFinding> found = findings(null, block("foreach", "judge", Map.of("command", "Judge",
                "execution_mode", "ai", "tools", "all")));

        assertThat(found).extracting(AnalyzerFinding::rule).containsExactly("invalid-config-value");
    }

    /** A shell step has no tools: the key says nothing there and asks for no requirement. */
    @Test
    void toolsOnAWrappersShellStep_needNoRequirement() {
        assertThat(findings(null, block("foreach", "each", Map.of("command", "true", "execution_mode", "shell",
                "tools", "read")))).extracting(AnalyzerFinding::rule).doesNotContain("missing-requirement");
    }

    // ---- web ----

    @Test
    void webWithoutRequirement_isRefused_onEveryBlockThatOwnsTools() {
        for (String type : List.of("simple", "foreach", "retry", "while", "conditional")) {
            List<AnalyzerFinding> found = findings(null, block(type, "look", Map.of("command", "Look it up",
                    "execution_mode", "ai", "web", "search")));

            assertThat(found).as(type).extracting(AnalyzerFinding::rule).containsExactly("missing-requirement");
            assertThat(found.getFirst().detail()).as(type).startsWith("web: search needs \"ai.web\" in requires");
        }
        assertThat(findings(List.of("ai.tools"), block("agentic", "dig", Map.of("goal", "Dig", "tools", "read",
                "web", "fetch", "web_hosts", List.of("docs.example.org")))))
                .extracting(AnalyzerFinding::detail).singleElement().asString()
                .startsWith("web: fetch needs \"ai.web\" in requires");
    }

    /** Web is a switch of its own: it needs its own requirement, and tools: none next to it needs none. */
    @Test
    void webWithItsRequirement_reportsNothing_andToolsNoneNeedsNoAiTools() {
        assertThat(findings(List.of("ai.web"), block("simple", "look", Map.of("command", "Look it up",
                "tools", "none", "web", "search")))).isEmpty();
        assertThat(findings(List.of("ai.web"), block("simple", "read", Map.of("command", "Read it",
                "web", "fetch", "web_hosts", List.of("*"))))).isEmpty();
        assertThat(findings(List.of("ai.web"), block("simple", "ask", Map.of("command", "Ask", "web", "none"))))
                .extracting(AnalyzerFinding::rule).containsExactly("unused-requirement");
        assertThat(findings(List.of("ai.web"), block("simple", "ask", Map.of("command", "Ask"))).getFirst().detail())
                .isEqualTo("requires names \"ai.web\", but no step uses it (no AI step has web other than none) —"
                        + " remove it");
    }

    @Test
    void aWebValueOrWebHostsThatIsWrong_isAnInvalidValue_andRefused() {
        List<Map<String, Object>> wrong = List.of(
                Map.of("command", "x", "web", "all"),
                Map.of("command", "x", "web", "{{ mode }}"),
                Map.of("command", "x", "web", "fetch"),
                Map.of("command", "x", "web", "search", "web_hosts", List.of("a.example")),
                Map.of("command", "x", "web_hosts", List.of("a.example")),
                Map.of("command", "x", "web", "fetch", "web_hosts", List.of("*", "a.example")),
                Map.of("command", "x", "web", "fetch", "web_hosts", List.of("{{ host }}")),
                Map.of("command", "x", "web", "search", "untrusted_input", false));
        for (Map<String, Object> config : wrong) {
            List<AnalyzerFinding> found = findings(List.of("ai.web"), block("simple", "look", config));

            assertThat(found).as(config.toString()).extracting(AnalyzerFinding::rule)
                    .contains("invalid-config-value");
            assertThat(found).as(config.toString()).filteredOn(f -> f.rule().equals("invalid-config-value"))
                    .extracting(AnalyzerFinding::severity).containsOnly(Severity.ERROR);
        }
    }

    /** Pi has no web tool: a step that runs on it cannot set web, by its own provider or the workflow's. */
    @Test
    void webOnAStepThatRunsOnPi_isRefused() {
        List<AnalyzerFinding> own = findings(List.of("ai.web"), block("simple", "look", Map.of("command", "Look",
                "provider", "pi", "web", "search")));
        List<AnalyzerFinding> inherited = findings("PI", List.of("ai.web"), block("simple", "look",
                Map.of("command", "Look", "web", "search")));
        List<AnalyzerFinding> claudeInAPiWorkflow = findings("PI", List.of("ai.web"), block("simple", "look",
                Map.of("command", "Look", "provider", "CLAUDE", "web", "search")));

        for (List<AnalyzerFinding> found : List.of(own, inherited)) {
            assertThat(found).extracting(AnalyzerFinding::rule).containsExactly("unsupported-web");
            assertThat(found.getFirst().severity()).isEqualTo(Severity.ERROR);
            assertThat(found.getFirst().detail()).startsWith("web: search on a step that runs on PI: Pi has no web"
                    + " tool");
        }
        assertThat(claudeInAPiWorkflow).isEmpty();
    }

    /** A shell step has no web: the key says nothing there. */
    @Test
    void webOnAWrappersShellStep_needsNoRequirement() {
        assertThat(findings(null, block("foreach", "each", Map.of("command", "true", "execution_mode", "shell",
                "web", "search")))).extracting(AnalyzerFinding::rule)
                .doesNotContain("missing-requirement", "unsupported-web");
    }

    @Test
    void unusedRequirement_isAWarning() {
        List<AnalyzerFinding> found = findings(List.of("container.network", "package.images", "ai.tools"),
                block("shell", "build", Map.of("command", "make")),
                block("simple", "ask", Map.of("command", "Summarise", "tools", "none")));

        assertThat(found).extracting(AnalyzerFinding::rule).containsOnly("unused-requirement");
        assertThat(found).extracting(AnalyzerFinding::severity).containsOnly(Severity.WARNING);
        assertThat(found).extracting(AnalyzerFinding::blockIndex).containsOnly(-1);
        assertThat(found).extracting(AnalyzerFinding::detail).containsExactly(
                "requires names \"container.network\", but no step uses it (no shell step has network: true) — remove"
                        + " it",
                "requires names \"package.images\", but no step uses it (no shell step runs a ./images/ image) —"
                        + " remove it",
                "requires names \"ai.tools\", but no step uses it (no AI step has tools other than"
                        + " none) — remove it");
    }

    @Test
    void usedRequirements_reportNothing() {
        assertThat(findings(List.of("container.network", "package.images", "ai.tools"),
                block("shell", "fetch", Map.of("command", "curl x", "network", true)),
                block("retry", "scan", Map.of("command", "scan", "execution_mode", "shell",
                        "container_image", "./images/scanner")),
                block("agentic", "fix", Map.of("goal", "Fix it", "tools", "shell")))).isEmpty();
    }

    @Test
    void agenticWithoutTools_isRefused() {
        List<AnalyzerFinding> found = findings(null, block("agentic", "fix", Map.of("goal", "Fix it")));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("tools-required");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).contains("an agentic block must state tools").endsWith("Traceworks refuses the"
                    + " workflow");
        });
    }

    @Test
    void unknownTools_isAnInvalidValue_thatRefuses() {
        List<AnalyzerFinding> found = findings(null, block("simple", "a", Map.of("command", "x", "tools", "all")));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("invalid-config-value");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).startsWith("tools: \"all\" is not one of none, read, write or shell");
        });
    }

    @Test
    void unrestrictedTools_isAnInvalidValue_thatNamesTheValidOnes() {
        List<AnalyzerFinding> found = findings(null,
                block("simple", "a", Map.of("command", "x", "tools", "unrestricted")));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("invalid-config-value");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).startsWith("tools: \"unrestricted\" is not one of none, read, write or shell");
        });
    }

    @Test
    void codexAsTheWorkflowProvider_isRefused_onceOnTheDefinition() {
        List<AnalyzerFinding> found = findings("CODEX", List.of("ai.tools"),
                block("simple", "review", Map.of("command", "Review", "tools", "read")),
                block("while", "loop", Map.of("command", "x", "execution_mode", "ai")));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unsupported-provider");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(-1);
            assertThat(f.detail()).startsWith("provider CODEX: Codex is not supported; Traceworks runs Claude and Pi");
        });
    }

    @Test
    void codexOnABlockThatMakesAnAiCall_isRefused_inAnyCase() {
        List<AnalyzerFinding> found = findings(null,
                block("simple", "a", Map.of("command", "x", "provider", "codex")),
                block("conditional", "b", Map.of("condition", "ok?", "execution_mode", "ai", "provider", " Codex ")),
                block("shell", "c", Map.of("command", "true")));

        assertThat(found).extracting(AnalyzerFinding::rule).containsExactly("unsupported-provider",
                "unsupported-provider");
        assertThat(found).extracting(AnalyzerFinding::blockName).containsExactly("a", "b");
    }

    @Test
    void mcpServersNonEmpty_isRefused() {
        List<AnalyzerFinding> found = findings(null,
                block("simple", "a", Map.of("command", "x", "mcp_servers", List.of("github"))),
                block("simple", "b", Map.of("command", "x", "mcp_servers", List.of())));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unsupported-mcp-servers");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockName()).isEqualTo("a");
        });
    }

    @Test
    void userMcpServersTrue_isRefused() {
        List<AnalyzerFinding> found = findings(null, block("simple", "a", Map.of("command", "x",
                "user_mcp_servers", true)));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unsupported-mcp-servers");
            assertThat(f.detail()).startsWith("user_mcp_servers: true");
        });
    }

    /** The analyzer reports what the admission refuses: through the whole analyzer, a refused reason is an ERROR. */
    @Test
    void theAnalyzer_runsTheRules_alsoWithoutBlocks() {
        List<AnalyzerFinding> found = new WorkflowAnalyzer().analyze(new WorkflowDefinition("wf", "Wf", null,
                List.of(), null, null, null, null, null, null, List.of("ai.tools"), null, null));

        assertThat(found).extracting(AnalyzerFinding::rule).containsExactly("empty-workflow", "unused-requirement");
    }
}
