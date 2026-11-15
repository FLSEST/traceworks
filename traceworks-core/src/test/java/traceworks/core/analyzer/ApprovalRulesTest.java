package traceworks.core.analyzer;

import traceworks.core.compat.GermanWords;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRulesTest {

    /** The German "no" that workflows offer; it counts as a rejection by default. */
    private static final String GERMAN_NO = GermanWords.APPROVAL_REJECT_ANSWER;

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> run(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        ApprovalRules.apply(List.of(blocks), out);
        return out;
    }

    @Test
    void onNoFail_withTheDefaultOptions_isNoFinding() {
        // the default options contain "No", which counts as a rejection
        assertThat(run(block("approval", "approve", Map.of("on_no", "fail")))).isEmpty();
    }

    @Test
    void ownOptionsWithoutARejection_areAnError() {
        var findings = run(block("approval", "approve",
                Map.of("on_no", "fail", "options", List.of("Yes", "Maybe"))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("on-no-without-reject-option");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.detail()).contains("on_no: fail").contains("Maybe");
        });
    }

    @Test
    void onNoSkip_isCheckedTheSameWay() {
        assertThat(run(block("approval", "a", Map.of("on_no", "skip", "options", List.of("Ok")))))
                .hasSize(1);
    }

    @Test
    void anotherOnNoValueOrNoValue_isNoFinding() {
        assertThat(run(block("approval", "a", Map.of("on_no", "continue", "options", List.of("Ok"))))).isEmpty();
        assertThat(run(block("approval", "a", Map.of("options", List.of("Ok"))))).isEmpty();
    }

    @Test
    void optionNames_areComparedIgnoringCaseAndSurroundingSpace() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail", "options", List.of("Yes", " NO "))))).isEmpty();
        assertThat(run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Yes", " " + GERMAN_NO.toUpperCase(Locale.ROOT) + " "))))).isEmpty();
    }

    @Test
    void rejectOptions_makeAnOwnOptionARejection() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Go", "Stop"), "reject_options", List.of("stop"))))).isEmpty();
    }

    @Test
    void rejectOptionsWithoutAMatchingOption_areAFinding_withTheRejectionsInTheText() {
        var findings = run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Go", GERMAN_NO), "reject_options", List.of("Stop"))));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("Stop"));
    }

    @Test
    void aRejectingAutoAnswer_makesOnNoApply() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Yes"), "auto_answer", "No")))).isEmpty();
    }

    @Test
    void aNonRejectingAutoAnswer_staysAFinding() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Yes"), "auto_answer", "Yes")))).hasSize(1);
    }

    @Test
    void nonStringOptions_areIgnored() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail", "options", List.of(1, 2))))).hasSize(1);
    }

    @Test
    void aBlockWithoutConfig_orANullEntry_isSkipped() {
        assertThat(run(block("approval", "a", null))).isEmpty();
        List<BlockDefinition> withNull = new ArrayList<>();
        withNull.add(null);
        List<AnalyzerFinding> out = new ArrayList<>();
        ApprovalRules.apply(withNull, out);
        assertThat(out).isEmpty();
    }

    @Test
    void aNestedApproval_isReportedOnTheOuterBlock_withIndexAndName() {
        var inner = Map.of("type", "approval", "name", "inner",
                "config", Map.of("on_no", "skip", "options", List.of("Yes")));
        var findings = run(
                block("shell", "before", Map.of("command", "echo")),
                block("foreach", "loop", Map.of("blocks", List.of(inner))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("loop › inner");
        });
    }

    @Test
    void aNestedApprovalWithoutName_usesTheOuterName() {
        var inner = Map.of("type", "approval",
                "config", Map.of("on_no", "fail", "options", List.of("Yes")));
        var findings = run(block("while", "w", Map.of("body", inner)));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.blockName()).isEqualTo("w"));
    }

    @Test
    void nestedDeeperThanTheMaximumDepth_isNotReported() {
        Object nested = Map.of("type", "approval",
                "config", Map.of("on_no", "fail", "options", List.of("Yes")));
        for (int i = 0; i < 20; i++) nested = Map.of("child", nested);

        assertThat(run(block("foreach", "f", Map.of("blocks", nested)))).isEmpty();
    }
}
