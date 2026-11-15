package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Wiring rules directly, without the sorting and the other rules of the WorkflowAnalyzer. */
class WiringRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> apply(BlockDefinition... blocks) {
        return apply(Map.of(), blocks);
    }

    private static List<AnalyzerFinding> apply(Map<String, Object> metadata, BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(List.of(blocks), metadata, out);
        return out;
    }

    @Test
    void anEmptyList_yieldsNothing() {
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(List.of(), Map.of(), out);
        assertThat(out).isEmpty();
    }

    @Test
    void anUnknownBlockReference_namesIndexNameAndReference() {
        List<AnalyzerFinding> out = apply(
                block("shell", "a", Map.of("command", "echo")),
                block("shell", "b", Map.of("command", "cat {{ block.x.output }} {{ block.a.output }}")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unknown-block-reference");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("b");
            assertThat(f.detail()).contains("{{ block.x.output }}").contains("\"x\"");
        });
    }

    @Test
    void aReferenceInANestedConfigCounts_otherBlockPlaceholdersDoNot() {
        List<AnalyzerFinding> out = apply(block("foreach", "loop", Map.of(
                "blocks", List.of(Map.of("config", Map.of("command", "{{ block.missing.output }}"))),
                "note", "{{ block.missing.status }}")));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("unknown-block-reference");
    }

    @Test
    void aChainVariable_isReportedOncePerBlock() {
        List<AnalyzerFinding> out = apply(block("shell", null, Map.of(
                "command", "echo {{ chain_run_id }} {{ chain_run_id }} {{ previous_chain_output }}")));

        assertThat(out).extracting(AnalyzerFinding::rule)
                .containsExactly("chain-only-placeholder", "chain-only-placeholder");
        assertThat(out).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.blockName()).isNull();
        });
        assertThat(out).extracting(AnalyzerFinding::detail)
                .anyMatch(d -> d.contains("chain_run_id"))
                .anyMatch(d -> d.contains("previous_chain_output"));
    }

    // --- audit-only-placeholder ---------------------------------------------------------------------------------

    private static final Map<String, Object> AUDIT_META = Map.of("audit", Map.of(
            "enabled", true, "key", "project.security", "category", "security", "reportBlock", "report"));

    @Test
    void auditFindingsWithoutAuditMetadata_areOneFindingPerBlock() {
        List<AnalyzerFinding> out = apply(
                block("shell", "collect", Map.of("command", "set -euo pipefail\necho {{ audit_findings }}")),
                block("simple", "report", Map.of("command", "Known: {{ audit_findings }} and {{audit_findings}}")));

        assertThat(out).filteredOn(f -> f.rule().equals("audit-only-placeholder")).hasSize(2).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.detail()).contains("{{ audit_findings }}").contains("metadata.audit");
        }).extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::blockName)
                .containsExactly(tuple(0, "collect"), tuple(1, "report"));
    }

    @Test
    void auditFindingsWithAValidAuditBlock_areNoFinding() {
        assertThat(apply(AUDIT_META, block("simple", "report", Map.of("command", "Known: {{ audit_findings }}"))))
                .extracting(AnalyzerFinding::rule).doesNotContain("audit-only-placeholder");
    }

    /** Decided as at run time: a disabled or incomplete audit block does not bring the variable. */
    @Test
    void auditFindingsWithADisabledOrIncompleteAuditBlock_areAFinding() {
        Map<String, Object> disabled = Map.of("audit", Map.of(
                "enabled", false, "key", "project.security", "category", "security", "reportBlock", "report"));
        Map<String, Object> withoutReportBlock = Map.of("audit", Map.of(
                "enabled", true, "key", "project.security", "category", "security"));
        Map<String, Object> otherSchemaVersion = Map.of("audit", Map.of("enabled", true, "key", "project.security",
                "category", "security", "reportBlock", "report", "schemaVersion", 2));
        BlockDefinition report = block("simple", "report", Map.of("command", "Known: {{ audit_findings }}"));

        assertThat(apply(disabled, report)).extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
        assertThat(apply(withoutReportBlock, report)).extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
        assertThat(apply(otherSchemaVersion, report)).extracting(AnalyzerFinding::rule)
                .contains("audit-only-placeholder");
    }

    @Test
    void auditFindingsInANestedBlockCount_anotherPlaceholderDoesNot() {
        List<AnalyzerFinding> out = apply(block("foreach", "per finding", Map.of(
                "blocks", List.of(Map.of("config", Map.of("command", "{{ audit_findings }}"))),
                "note", "{{ audit_findings_count }} {{ open_todos }}")));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("audit-only-placeholder");
    }

    @Test
    void anUnreadAiOutput_namesBothWaysToReadItInTheDetail() {
        List<AnalyzerFinding> out = apply(
                block("agentic", "plan", Map.of("goal", "Plan it")),
                block("shell", "x", Map.of("command", "echo")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unwired-output");
            assertThat(f.blockIndex()).isZero();
            assertThat(f.detail()).contains("block.plan.output");
        });
    }

    @Test
    void anUnnamedAiBlock_namesNoWayByName() {
        List<AnalyzerFinding> out = apply(
                block("simple", "  ", Map.of("command", "a")),
                block("shell", "x", Map.of("command", "echo")));

        assertThat(out).singleElement().extracting(AnalyzerFinding::detail)
                .asString().doesNotContain("block.");
    }

    @Test
    void previousOutputInTheDirectlyFollowingBlock_countsAsRead() {
        assertThat(apply(
                block("simple", "a", Map.of("command", "x")),
                block("shell", "b", Map.of("command", "echo {{ previous_output }}")))).isEmpty();
    }

    @Test
    void aReferenceByNameInALaterBlock_countsAsRead() {
        assertThat(apply(
                block("simple", "a", Map.of("command", "Analyze x")),
                block("shell", "b", Map.of("command", "echo")),
                block("shell", "c", Map.of("command", "echo {{ block.a.output }}")))).isEmpty();
    }

    @Test
    void previousOutputFurtherOn_doesNotCountForTheBlockBeforeThePreviousOne() {
        List<AnalyzerFinding> out = apply(
                block("compress-ai", "a", Map.of("source", "previous")),
                block("shell", "b", Map.of("command", "echo")),
                block("shell", "c", Map.of("command", "echo {{ previous_output }}")));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("unwired-output");
    }

    @Test
    void nonAiBlocks_andBlocksWithoutType_areNotChecked() {
        assertThat(apply(
                block(null, "a", Map.of()),
                block("shell", "b", Map.of("command", "echo")),
                block("approval", "c", Map.of("question", "ok?")),
                block("shell", "d", Map.of("command", "echo")))).isEmpty();
    }

    @Test
    void aWrapperWithAiInside_isNotChecked() {
        assertThat(apply(
                block("retry", "r", Map.of("block", Map.of("type", "simple"))),
                block("sub-workflow", "s", Map.of()),
                block("shell", "t", Map.of("command", "echo")))).isEmpty();
    }

    @Test
    void aBlockWithoutConfig_orANullEntry_isNoError() {
        assertThat(apply(
                block("shell", "a", null),
                block("shell", "b", null))).isEmpty();
        List<BlockDefinition> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(block("simple", "s", Map.of("command", "{{ block.missing.output }}")));
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(withNull, Map.of(), out);
        assertThat(out).extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex)
                .containsExactly(tuple("unknown-block-reference", 1));
    }

    @Test
    void aReferenceToABlockInASwitchCase_isKnown() {
        List<AnalyzerFinding> out = apply(
                block("switch", "route", Map.of("expression", "a", "cases", List.of(Map.of("value", "a",
                        "blocks", List.of(Map.of("type", "shell", "name", "inner",
                                "config", Map.of("command", "echo"))))))),
                block("shell", "after", Map.of("command", "echo {{ block.inner.output }}")));

        assertThat(out).isEmpty();
    }

    @Test
    void anUnknownPropertyOfAKnownBlock_isAnError() {
        List<AnalyzerFinding> out = apply(
                block("shell", "y", Map.of("command", "echo")),
                block("shell", "z", Map.of("command", "echo {{ block.y.outptu }} {{ block.y.outptu }}")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unknown-block-property");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("z");
            assertThat(f.detail()).contains("{{ block.y.outptu }}", "\"outptu\"", "{{ block.y.output }}");
        });
    }

    @Test
    void aPropertyOfABlockInASwitchCase_orOfABlockWithADotInItsName_isJudgedByTheLongestName() {
        List<AnalyzerFinding> out = apply(
                block("switch", "route", Map.of("expression", "a", "default", List.of(
                        Map.of("type", "shell", "name", "inner", "config", Map.of("command", "echo"))))),
                block("shell", "a.b", Map.of("command", "echo")),
                block("shell", "a", Map.of("command", "echo")),
                block("shell", "reader", Map.of("command",
                        "echo {{ block.inner.status }} {{ block.a.b.output }} {{ block.a.b.exit }}")));

        assertThat(out).extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex)
                .containsExactly(tuple("unknown-block-property", 3), tuple("unknown-block-property", 3));
        assertThat(out).extracting(AnalyzerFinding::detail)
                .anyMatch(d -> d.contains("{{ block.inner.status }}") && d.contains("\"status\""))
                .anyMatch(d -> d.contains("{{ block.a.b.exit }}") && d.contains("\"exit\"")
                        && d.contains("{{ block.a.b.output }}"));
    }

    @Test
    void aPropertyOfAnUnknownBlock_orAVariableOfThatName_isNoUnknownProperty() {
        assertThat(apply(block("shell", "y", Map.of("command", "echo {{ block.other.status }} {{ block.y }}"))))
                .isEmpty();

        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(List.of(block("shell", "y", Map.of("command", "echo {{ block.y.label }}"))), Map.of(),
                Map.of("block.y.label", "set as a variable"), out);
        assertThat(out).isEmpty();
    }

    @Test
    void aStepOutputBeyondTheLastBlock_isAnError() {
        List<AnalyzerFinding> out = apply(
                block("shell", "a", Map.of("command", "echo")),
                block("shell", "b", Map.of("command", "echo {{ step_0_output }} {{ step_7_output }} {{step_2_output}}")));

        assertThat(out).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                .containsExactly(tuple("step-output-out-of-range", Severity.ERROR, 1),
                        tuple("step-output-out-of-range", Severity.ERROR, 1));
        assertThat(out.getFirst().detail()).contains("{{ step_7_output }}", "2 blocks", "{{ step_1_output }}");
        assertThat(out.getLast().detail()).contains("{{ step_2_output }}");
    }

    @Test
    void aStepOutputOfABlockThatExists_isNoFinding_alsoTheLastOne() {
        assertThat(apply(
                block("shell", "a", Map.of("command", "echo")),
                block("shell", "b", Map.of("command", "echo {{ step_0_output }} {{ step_1_output }}")))).isEmpty();
    }

    /** foreach, while, retry, conditional and switch draw one further index per inner step: later indices move. */
    @Test
    void aStepOutputBeyondTheBlocks_isNotJudged_whenAnEarlierBlockDrawsFurtherIndices() {
        for (String wrapper : List.of("foreach", "while", "retry", "conditional", "switch", "telepathy")) {
            assertThat(apply(
                    block(wrapper, "w", Map.of("execution_mode", "shell", "command", "echo")),
                    block("shell", "a", Map.of("command", "echo")),
                    block("shell", "b", Map.of("command", "echo {{ step_7_output }}")))).as(wrapper).isEmpty();
        }
        List<BlockDefinition> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(block("shell", "b", Map.of("command", "echo {{ step_7_output }}")));
        withNull.add(block("shell", "c", Map.of("command", "echo")));
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(withNull, Map.of(), out);
        assertThat(out).isEmpty();
    }

    @Test
    void aStepOutputBeyondTheBlocks_isAnError_whenOnlyTheLastBlockDrawsFurtherIndices() {
        List<AnalyzerFinding> out = apply(
                block("shell", "a", Map.of("command", "echo")),
                block("foreach", "loop", Map.of("execution_mode", "shell", "items", List.of("x"),
                        "command", "echo {{ step_2_output }}")));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("step-output-out-of-range");
    }

    @Test
    void aStepNumberTooLongForANumber_isOutOfRangeToo() {
        assertThat(apply(block("shell", "a", Map.of("command", "echo {{ step_99999999999999999999_output }}"))))
                .extracting(AnalyzerFinding::rule).containsExactly("step-output-out-of-range");
    }

    /** {@code audit_items} is set where {@code audit_findings} is: only for an audit workflow. */
    @Test
    void auditItemsWithoutAuditMetadata_areAFinding_andWithAValidAuditBlock_none() {
        BlockDefinition facts = block("shell", "facts", Map.of("command", "set -euo pipefail\necho {{ audit_items }}"));

        assertThat(apply(facts)).filteredOn(f -> f.rule().equals("audit-only-placeholder")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("{{ audit_items }}").contains("metadata.audit"));
        assertThat(apply(AUDIT_META, facts)).extracting(AnalyzerFinding::rule).doesNotContain("audit-only-placeholder");
    }
}
