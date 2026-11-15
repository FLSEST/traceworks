package traceworks.core.analyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** What sits on a block but never runs: {@code config.blocks} and fields next to {@code config}. */
class BlockShapeRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private List<AnalyzerFinding> shapeFindings(List<BlockDefinition> blocks) {
        return analyzer.analyze(blocks, null).stream()
                .filter(f -> f.rule().equals("dead-nested-blocks") || f.rule().equals("unknown-block-field")
                        || f.rule().equals("unknown-config-key"))
                .toList();
    }

    @Test
    void foreachWithABlocksList_isAnError_andNotReportedAgainAsUnknownKey() {
        var findings = shapeFindings(List.of(block("foreach", "features", Map.of(
                "items", List.of("a", "b"), "command", "echo {{ item }}", "execution_mode", "shell",
                "blocks", List.of(Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "x")))))));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                .containsExactly(tuple("dead-nested-blocks", Severity.ERROR, 0));
        assertThat(findings.get(0).detail()).contains("config.blocks (foreach) with 1 block entries never runs");
    }

    @Test
    void switchWithCasesAndDefault_isFine_onlyABlocksListNextToThemIsNot() {
        Map<String, Object> inner = Map.of("type", "shell", "config", Map.of("command", "set -euo pipefail\nls"));
        assertThat(shapeFindings(List.of(block("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))), "default", List.of(inner))))))
                .isEmpty();

        assertThat(shapeFindings(List.of(block("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))), "blocks", List.of(inner))))))
                .extracting(AnalyzerFinding::rule).containsExactly("dead-nested-blocks");
    }

    @Test
    void aBlocksListInANestedBlock_namesThePath() {
        Map<String, Object> retry = Map.of("type", "retry", "name", "again",
                "config", Map.of("command", "npm test", "blocks", List.of()));
        var findings = shapeFindings(List.of(block("switch", "route", Map.of("expression", "x",
                "default", List.of(retry)))));

        assertThat(findings).extracting(AnalyzerFinding::rule).containsExactly("dead-nested-blocks");
        assertThat(findings.get(0).detail()).startsWith("Block 1 › default › Block 1 \"again\": config.blocks (retry)");
    }

    @Test
    void onFailureNextToConfig_isAnError() throws Exception {
        List<BlockDefinition> blocks = new ObjectMapper().readValue("""
                [{"type":"shell","name":"review","config":{"command":"set -euo pipefail\\nls"},
                  "on_failure":"continue"}]
                """, new TypeReference<>() {});

        var findings = shapeFindings(blocks);

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(tuple("unknown-block-field", Severity.ERROR));
        assertThat(findings.get(0).detail()).startsWith("on_failure sits next to config instead of inside it");
        assertThat(blocks.get(0).shouldStopOnFailure()).isTrue();
    }

    /** A near spelling of a block field is a warning, any other field is info. */
    @Test
    void aNearSpellingOfABlockField_isAWarning_otherwiseInfo() {
        BlockDefinition b = block("simple", "ask", Map.of("command", "Summarise"));
        b.putUnknownField("Name", "ask");
        b.putUnknownField("note", "only a note");
        b.putUnknownField("skillIds", List.of());

        assertThat(shapeFindings(List.of(b)))
                .extracting(AnalyzerFinding::severity, AnalyzerFinding::detail)
                .containsExactlyInAnyOrder(
                        tuple(Severity.WARNING, "unknown block field Name — no run reads it; did you mean name?"),
                        tuple(Severity.INFO, "unknown block field note — no run reads it (allowed next to config:"
                                + " type, name)"),
                        tuple(Severity.INFO, "unknown block field skillIds — no run reads it (allowed next to config:"
                                + " type, name)"));
    }

    /**
     * The block fields {@code skillIds} and {@code requiredCapabilities}: skills a block asks for refuse the workflow
     * ({@code unsupported-skills} reports them,
     * not this rule again); {@code requiredCapabilities} is a warning, because Traceworks does not read it.
     */
    @Test
    void skillIds_areLeftToTheirOwnRule_requiredCapabilitiesAreAWarning() {
        BlockDefinition b = block("simple", "ask", Map.of("command", "Summarise"));
        b.putUnknownField("skillIds", List.of("skill-1"));
        b.putUnknownField("requiredCapabilities", List.of("browser"));

        assertThat(shapeFindings(List.of(b)))
                .extracting(AnalyzerFinding::severity, AnalyzerFinding::detail)
                .containsExactly(tuple(Severity.WARNING, "requiredCapabilities is not read by Traceworks — the step"
                        + " runs without checking that the capabilities are there; remove it"));
    }

    @Test
    void requiredCapabilities_emptyList_isNotReported() {
        BlockDefinition empty = block("simple", "ask", Map.of("command", "Summarise"));
        empty.putUnknownField("requiredCapabilities", List.of());
        BlockDefinition filled = block("simple", "ask", Map.of("command", "Summarise"));
        filled.putUnknownField("requiredCapabilities", List.of("browser"));

        assertThat(shapeFindings(List.of(empty))).isEmpty();
        assertThat(shapeFindings(List.of(filled))).extracting(AnalyzerFinding::severity)
                .containsExactly(Severity.WARNING);
    }

    @Test
    void aNestedBlockWithAFieldNextToConfig_isReported() {
        Map<String, Object> stray = new HashMap<>();
        stray.put("type", "shell");
        stray.put("name", "cleanup");
        stray.put("config", Map.of("command", "set -euo pipefail\nrm -f x"));
        stray.put("on_failure", "continue");

        var findings = shapeFindings(List.of(block("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(stray)))))));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(tuple("unknown-block-field", Severity.ERROR));
        assertThat(findings.get(0).detail())
                .startsWith("Block 1 › cases[0] › Block 1 \"cleanup\": on_failure sits next to config");
    }

    @Test
    void aWellFormedBlock_hasNoFinding() {
        BlockDefinition b = block("simple", "ask", Map.of("command", "Summarise"));

        assertThat(shapeFindings(List.of(b))).isEmpty();
    }
}
