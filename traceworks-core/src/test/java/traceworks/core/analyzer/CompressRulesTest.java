package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The stage rules say what the run does with a compress block: an error where it fails, a warning where it ignores. */
class CompressRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static BlockDefinition compress(Object stages) {
        return block("compress", "squeeze", Map.of("source", "previous", "stages", stages));
    }

    private static Map<String, Object> stage(Object... keysAndValues) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    private List<AnalyzerFinding> findings(BlockDefinition... blocks) {
        return analyzer.analyze(List.of(blocks), null).stream()
                .filter(f -> f.rule().equals("invalid-config-value") || f.rule().equals("unknown-config-key")
                        || f.rule().equals("missing-required-config-key"))
                .toList();
    }

    @Test
    void aValidChain_givesNoFinding() {
        var block = compress(List.of(
                stage("op", "text.normalize"),
                stage("op", "lines.filter", "include", List.of("ERROR"), "before", 1),
                stage("op", "lines.dedupe", "count", true),
                stage("op", "lines.head", "lines", 50),
                stage("op", "text.truncate", "max_chars", 2000, "keep", "head")));
        var withLimits = block("compress", "squeeze", new java.util.HashMap<>(block.getConfig()));
        withLimits.getConfig().put("max_chars", 5000);
        withLimits.getConfig().put("timeout_seconds", 10);

        assertThat(findings(block)).isEmpty();
        assertThat(findings(withLimits)).isEmpty();
    }

    @Test
    void jsonStagesAfterJsonStages_giveNoFinding() {
        assertThat(findings(compress(List.of(
                stage("op", "json.select", "paths", List.of("items")),
                stage("op", "json.limit", "path", "items", "count", 3),
                stage("op", "json.sort", "path", "items", "by", "name", "order", "desc"),
                stage("op", "json.to_lines", "path", "items", "template", "{name}"))))).isEmpty();
    }

    @Test
    void stagesThatIsNoNonEmptyList_isAnError() {
        assertThat(findings(compress(List.of()))).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(tuple("invalid-config-value", Severity.ERROR));
        var text = findings(compress("lines.head"));
        assertThat(text).hasSize(1);
        assertThat(text.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(text.get(0).detail()).contains("'stages' must be a non-empty list of stages")
                .contains("the step fails when it is reached");
    }

    @Test
    void aMissingStagesKey_isLeftToTheRequiredKeyRule() {
        var findings = findings(block("compress", "squeeze", Map.of("source", "previous")));

        assertThat(findings).extracting(AnalyzerFinding::rule).containsExactly("missing-required-config-key");
    }

    @Test
    void aStageThatIsNoObject_isAnError() {
        var findings = findings(compress(List.of("lines.head")));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(tuple("invalid-config-value", Severity.ERROR));
        assertThat(findings.get(0).detail()).contains("Stage 1: must be an object with 'op'");
    }

    @Test
    void anUnknownOrMissingOp_isAnErrorThatListsTheOps() {
        var findings = findings(compress(List.of(stage("op", "lines.hed", "lines", 5), stage("lines", 5))));

        assertThat(findings).hasSize(2).allSatisfy(f -> {
            assertThat(f.rule()).isEqualTo("invalid-config-value");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).contains("allowed: text.normalize");
        });
        assertThat(findings.get(0).detail()).contains("Stage 1: unknown operation 'lines.hed'");
        assertThat(findings.get(1).detail()).contains("Stage 2: unknown operation 'null'");
    }

    @Test
    void aMissingOrInvalidStageSetting_isAnError_forEveryStage() {
        var findings = findings(compress(List.of(
                stage("op", "lines.head"),
                stage("op", "lines.tail", "lines", 0),
                stage("op", "text.truncate", "max_chars", 10),
                stage("op", "text.truncate", "max_chars", 500, "keep", "middle"),
                stage("op", "lines.filter"),
                stage("op", "lines.replace", "pattern", "("),
                stage("op", "markdown.section", "headings", List.of("Intro"), "level", 9),
                stage("op", "json.limit", "path", "a", "count", "many"),
                stage("op", "json.sort", "by", "name", "order", "sideways"),
                stage("op", "json.to_lines", "template", "no placeholder"))));

        assertThat(findings).hasSize(11).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
        assertThat(findings).extracting(AnalyzerFinding::detail).satisfiesExactly(
                d -> assertThat(d).contains("Stage 1 (lines.head): 'lines' is missing"),
                d -> assertThat(d).contains("Stage 2 (lines.tail): 'lines' must be a whole number"),
                d -> assertThat(d).contains("Stage 3 (text.truncate): 'max_chars' must be a whole number"),
                d -> assertThat(d).contains("Stage 4 (text.truncate): 'keep' must be head, tail or head_tail"),
                d -> assertThat(d).contains("Stage 5 (lines.filter): 'include' or 'exclude' needs at least one"),
                d -> assertThat(d).contains("Stage 6 (lines.replace)"),
                d -> assertThat(d).contains("Stage 7 (markdown.section): 'level' must be a whole number"),
                d -> assertThat(d).contains("Stage 8 (json.limit): expects JSON"),
                d -> assertThat(d).contains("Stage 8 (json.limit): 'count' must be a whole number"),
                d -> assertThat(d).contains("Stage 9 (json.sort)"),
                d -> assertThat(d).contains("Stage 10 (json.to_lines): 'template' needs at least one placeholder"));
    }

    @Test
    void aJsonStageAfterATextStage_isAnError() {
        var findings = findings(compress(List.of(
                stage("op", "text.normalize"), stage("op", "json.limit", "count", 3))));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(findings.get(0).detail()).contains("Stage 2 (json.limit): expects JSON, but an earlier stage yields text");
    }

    @Test
    void theBlocksOwnLimitsOutOfRange_areErrors() {
        var findings = findings(block("compress", "squeeze", Map.of("source", "previous",
                "stages", List.of(stage("op", "text.normalize")), "max_chars", 100, "timeout_seconds", 601)));

        assertThat(findings).extracting(AnalyzerFinding::severity).containsExactly(Severity.ERROR, Severity.ERROR);
        assertThat(findings.get(0).detail()).contains("'max_chars' must be a whole number between 300 and 1,000,000");
        assertThat(findings.get(1).detail()).contains("'timeout_seconds' must be a whole number between 1 and 600");
    }

    @Test
    void aSettingTheOpDoesNotRead_isAWarning_withASuggestion() {
        var findings = findings(compress(List.of(
                stage("op", "lines.head", "lines", 5, "count", 3),
                stage("op", "text.truncate", "max_chars", 500, "maxchars", 9),
                stage("op", "text.normalize", "lines", 1))));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsOnly(tuple("unknown-config-key", Severity.WARNING));
        assertThat(findings).extracting(AnalyzerFinding::detail).satisfiesExactly(
                d -> assertThat(d).contains("Stage 1 (lines.head): unknown setting count").contains("has no effect")
                        .doesNotContain("did you mean"),
                d -> assertThat(d).contains("Stage 2 (text.truncate): unknown setting maxchars")
                        .contains("did you mean max_chars?"),
                d -> assertThat(d).contains("Stage 3 (text.normalize): unknown setting lines"));
    }

    @Test
    void aFlagThatIsNoBoolean_isAWarning() {
        var findings = findings(compress(List.of(stage("op", "lines.dedupe", "count", "yes", "consecutive_only", false))));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).rule()).isEqualTo("invalid-config-value");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(findings.get(0).detail()).contains("Stage 1 (lines.dedupe): count: \"yes\" is not a valid value");
    }

    @Test
    void twoFlagsThatAreNoBoolean_comeInTheStagesOrder() {
        var findings = findings(compress(List.of(stage("op", "lines.dedupe", "consecutive_only", "no", "count", "yes"))));

        assertThat(findings).extracting(AnalyzerFinding::detail).satisfiesExactly(
                d -> assertThat(d).contains("consecutive_only: \"no\" is not a valid value"),
                d -> assertThat(d).contains("count: \"yes\" is not a valid value"));
    }

    @Test
    void aCompressBlockInASwitchCase_isCheckedAndNamesItsPath() {
        Map<String, Object> inner = Map.of("type", "compress", "name", "squeeze",
                "config", Map.of("source", "previous", "stages", List.of(stage("op", "lines.head"))));
        var sw = block("switch", "route", Map.of("variable", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner)))));

        var findings = findings(sw).stream().filter(f -> f.detail().contains("Stage 1")).toList();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(findings.get(0).detail()).contains("Block 1 › cases[0] › Block 1 \"squeeze\": ")
                .contains("Stage 1 (lines.head): 'lines' is missing");
    }

    /** compress-ai: max_chars (500 to 200,000) and timeout_seconds (1 to 86,400) as its executor checks them. */
    @Test
    void compressAi_limitsOutOfRange_areErrors() {
        var findings = findings(block("compress-ai", "sum", Map.of("source", "previous", "max_chars", 5,
                "timeout_seconds", "60")));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::detail)
                .containsExactly(
                        tuple("invalid-config-value", Severity.ERROR, "compress-ai: 'max_chars' must be a whole number"
                                + " between 500 and 200,000 — the step fails when it is reached"),
                        tuple("invalid-config-value", Severity.ERROR, "compress-ai: 'timeout_seconds' must be a whole"
                                + " number between 1 and 86,400 — the step fails when it is reached"));
        assertThat(findings(block("compress-ai", "sum", Map.of("source", "previous", "max_chars", 500,
                "timeout_seconds", 60)))).isEmpty();
    }
}
