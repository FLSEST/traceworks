package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** {@code duplicate-block-name}: two blocks of one name that can both run in one run. */
class BlockNameRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static Map<String, Object> nested(String name) {
        return Map.of("type", "shell", "name", name, "config", Map.of("command", "echo"));
    }

    private static List<AnalyzerFinding> apply(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        BlockNameRules.apply(List.of(blocks), out);
        return out;
    }

    @Test
    void twoTopLevelBlocksOfOneName_areAnErrorAtTheLaterOne() {
        List<AnalyzerFinding> out = apply(
                block("shell", "y", Map.of("command", "echo one")),
                block("shell", "x", Map.of("command", "echo")),
                block("shell", "y", Map.of("command", "echo two")),
                block("shell", "y", Map.of("command", "echo three")));

        assertThat(out).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex,
                AnalyzerFinding::blockName).containsExactly(
                tuple("duplicate-block-name", Severity.ERROR, 2, "y"),
                tuple("duplicate-block-name", Severity.ERROR, 3, "y"));
        assertThat(out.getFirst().detail()).contains("{{ block.y.output }}", "its own name");
    }

    @Test
    void blocksWithoutAName_orWithABlankOne_orWithDifferentNames_areNoFinding() {
        List<BlockDefinition> blocks = new ArrayList<>();
        blocks.add(block("shell", null, Map.of("command", "echo")));
        blocks.add(block("shell", null, Map.of("command", "echo")));
        blocks.add(block("shell", " ", Map.of("command", "echo")));
        blocks.add(block("shell", " ", null));
        blocks.add(null);
        blocks.add(block("shell", "Y", Map.of("command", "echo")));
        blocks.add(block("shell", "y", Map.of("command", "echo")));
        List<AnalyzerFinding> out = new ArrayList<>();

        BlockNameRules.apply(blocks, out);

        assertThat(out).isEmpty();
    }

    /** Only one case of a switch runs, so the same name in two of its cases, or in a case and the default, is one name. */
    @Test
    void theSameNameInTwoCasesOfOneSwitch_isNoFinding() {
        assertThat(apply(block("switch", "route", Map.of("expression", "{{ kind }}",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(nested("report"))),
                        Map.of("value", "b", "blocks", List.of(nested("report")))),
                "default", List.of(nested("report")))))).isEmpty();
    }

    @Test
    void theSameNameTwiceInOneCase_isAnError_andNamesWhereItSits() {
        List<AnalyzerFinding> out = apply(
                block("shell", "first", Map.of("command", "echo")),
                block("switch", "route", Map.of("expression", "a",
                        "cases", List.of(Map.of("value", "a", "blocks", List.of(nested("report"), nested("report")))))));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("duplicate-block-name");
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("route");
            assertThat(f.detail()).startsWith("Block 2 › cases[0] › Block 2 \"report\"")
                    .contains("{{ block.report.output }}");
        });
    }

    @Test
    void aCaseBlockNamedLikeATopLevelBlock_orLikeItsOwnSwitch_orLikeACaseBlockOfAnotherSwitch_isAnError() {
        List<AnalyzerFinding> out = apply(
                block("shell", "report", Map.of("command", "echo")),
                block("switch", "route", Map.of("expression", "a",
                        "cases", List.of(Map.of("value", "a", "blocks", List.of(nested("report"), nested("route"))),
                                Map.of("value", "b", "blocks", List.of(nested("other")))))),
                block("switch", "again", Map.of("expression", "a", "default", List.of(nested("other")))));

        assertThat(out).extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex).containsExactly(
                tuple("duplicate-block-name", 1), tuple("duplicate-block-name", 1),
                tuple("duplicate-block-name", 2));
        assertThat(out).extracting(AnalyzerFinding::detail)
                .anyMatch(d -> d.startsWith("Block 2 › cases[0] › Block 1 \"report\""))
                .anyMatch(d -> d.startsWith("Block 2 › cases[0] › Block 2 \"route\""))
                .anyMatch(d -> d.startsWith("Block 3 › default › Block 1 \"other\""));
    }

    /** No executor runs a plain {@code blocks} list (dead-nested-blocks), so its names record nothing. */
    @Test
    void aNameInADeadBlocksList_isNoFinding() {
        assertThat(apply(
                block("shell", "report", Map.of("command", "echo")),
                block("foreach", "loop", Map.of("blocks", List.of(nested("report"), nested("report")))))).isEmpty();
    }

    @Test
    void allNames_holdsTopLevelAndNestedNames() {
        assertThat(BlockNameRules.allNames(List.of(
                block("shell", "a", null),
                block("switch", "route", Map.of("cases", List.of(Map.of("blocks", List.of(nested("inner")))))))))
                .containsExactlyInAnyOrder("a", "route", "inner");
    }
}
