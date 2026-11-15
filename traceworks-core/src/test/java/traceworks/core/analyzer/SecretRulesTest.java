package traceworks.core.analyzer;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code invalid-secret-name}: a secret name the run admission refuses, found before a run. */
class SecretRulesTest {

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition block = new BlockDefinition();
        block.setType(type);
        block.setName("deploy");
        block.setConfig(new HashMap<>(config));
        return block;
    }

    private static List<AnalyzerFinding> findings(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        SecretRules.apply(List.of(blocks), out);
        return out;
    }

    @Test
    void reservedSecretNames_areRefused() {
        List<AnalyzerFinding> found = findings(block("shell", Map.of("command", "./deploy",
                "secrets", List.of("PATH", "TRACEWORKS_X", "LC_ALL", "DOCKER_HOST", "TINI_VERBOSITY",
                        "DEPLOY_TOKEN"))));

        assertThat(found).extracting(AnalyzerFinding::rule).containsOnly("invalid-secret-name");
        assertThat(found).extracting(AnalyzerFinding::severity).containsOnly(AnalyzerFinding.Severity.ERROR);
        assertThat(found).extracting(AnalyzerFinding::detail).hasSize(5)
                .anySatisfy(d -> assertThat(d).contains("\"PATH\" is reserved"))
                .anySatisfy(d -> assertThat(d).contains("\"TRACEWORKS_X\" is reserved"))
                .anySatisfy(d -> assertThat(d).contains("\"LC_ALL\" is reserved"))
                .anySatisfy(d -> assertThat(d).contains("\"DOCKER_HOST\" is reserved"))
                .anySatisfy(d -> assertThat(d).contains("\"TINI_VERBOSITY\" is reserved"));
    }

    /** Every credential name of an AI provider is reserved, taken from the one list of them. */
    @Test
    void credentialNames_areRefused() {
        List<String> names = List.of("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_OAUTH_TOKEN",
                "OPENAI_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY");

        List<AnalyzerFinding> found = findings(block("shell", Map.of("command", "./deploy", "secrets", names)));

        assertThat(AiCli.credentialNamesOfAllProviders())
                .containsExactlyInAnyOrderElementsOf(names);
        assertThat(found).hasSize(6).allSatisfy(f -> {
            assertThat(f.rule()).isEqualTo("invalid-secret-name");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.detail()).contains("credential of an AI provider").contains("under another name");
        });
    }

    @Test
    void placeholdersAndNamesThatAreNoNames_areReported_alsoInNestedBlocks() {
        Map<String, Object> switchConfig = Map.of("expression", "x", "default", List.of(Map.of("type", "shell",
                "config", Map.of("command", "ls", "secrets", List.of("{{ name }}")))));

        List<AnalyzerFinding> found = findings(
                block("shell", Map.of("command", "ls", "secrets", List.of("lower_case", "9LIVES"))),
                block("retry", Map.of("command", "ls", "execution_mode", "shell", "secrets", "TOKEN")),
                block("switch", switchConfig));

        assertThat(found).extracting(AnalyzerFinding::blockIndex).containsExactly(0, 0, 1, 2);
        assertThat(found.get(3).detail()).startsWith("Block 3 > default › Block 1: ").contains("placeholder");
        assertThat(found.get(2).detail()).contains("not a list");
    }

    @Test
    void validNames_reportNothing() {
        assertThat(findings(block("shell", Map.of("command", "ls", "secrets", List.of("DEPLOY_TOKEN", "_X1")))))
                .isEmpty();
    }

    @Test
    void theAnalyzer_runsTheRule() {
        WorkflowDefinition workflow = new WorkflowDefinition("wf", "Wf", null, List.of(block("shell",
                Map.of("command", "ls", "secrets", List.of("HOME")))), null, null, null, null, null, null, null,
                null, null);

        assertThat(new WorkflowAnalyzer(CoreBlocks.registry(null)).analyze(workflow))
                .extracting(AnalyzerFinding::rule).contains("invalid-secret-name");
    }
}
