package traceworks.core.analyzer;

import traceworks.core.compat.GermanWords;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.BlockTypeRegistry;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Every rule triggered once and deliberately not triggered once. */
class WorkflowAnalyzerTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static WorkflowDefinition definition(List<BlockDefinition> blocks, Map<String, Object> metadata,
                                                 List<String> requires) {
        return new WorkflowDefinition("id-1", "test", null, blocks, null, null, null, null, metadata, null, requires,
                "traceworks-workflow", 1);
    }

    private List<String> rules(List<BlockDefinition> blocks, String instructions) {
        return analyzer.analyze(blocks, instructions).stream().map(AnalyzerFinding::rule).toList();
    }

    @Test
    void aReferenceToAnUnknownBlock_isAFinding() {
        var blocks = List.of(
                block("shell", "measure", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("command", "Read {{ block.measrue.output }}")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("unknown-block-reference");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.detail()).contains("measrue");
        });
    }

    @Test
    void aCorrectlySpelledReference_isNoFinding() {
        var blocks = List.of(
                block("shell", "measure", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("command", "Read {{ block.measure.output }}")));

        assertThat(rules(blocks, null)).doesNotContain("unknown-block-reference", "unwired-output");
    }

    @Test
    void aChainVariableInAWorkflowRunningOnItsOwn_isAFinding() {
        var blocks = List.of(
                block("simple", "a", Map.of("command", "Take {{ previous_chain_output }} and summarise")));

        assertThat(rules(blocks, null)).contains("chain-only-placeholder");
    }

    /** Only what a chain actually sets is reported: nothing sets {@code chain_step_output}, not even a chain. */
    @Test
    void onlyVariablesTheChainSets_areChainVariables() {
        var runId = List.of(block("simple", "a", Map.of("command", "Chain run {{ chain_run_id }}")));
        var stepOutput = List.of(block("simple", "a", Map.of("command", "Take {{ chain_step_output }}")));

        assertThat(rules(runId, null)).contains("chain-only-placeholder");
        assertThat(rules(stepOutput, null)).doesNotContain("chain-only-placeholder");
        assertThat(WiringRules.CHAIN_ONLY).containsExactlyInAnyOrder("previous_chain_output", "chain_run_id");
    }

    /** {@code audit_findings} exists only in an audit workflow: the metadata decide. */
    @Test
    void auditFindingsWithoutAnAuditWorkflow_areAFinding_withAValidAuditBlockNot() {
        var blocks = List.of(block("simple", "report", Map.of("command", "Known findings: {{ audit_findings }}")));
        Map<String, Object> audit = Map.of("audit", Map.of(
                "enabled", true, "key", "project.security", "category", "security", "report_block", "report"));

        assertThat(rules(blocks, null)).contains("audit-only-placeholder");
        assertThat(analyzer.analyze(blocks, null, Map.of("gate", Map.of("expected", true))))
                .extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
        assertThat(analyzer.analyze(blocks, null, audit)).extracting(AnalyzerFinding::rule)
                .doesNotContain("audit-only-placeholder");
        assertThat(analyzer.analyze(blocks, null, null)).extracting(AnalyzerFinding::rule)
                .as("without metadata: no audit workflow").contains("audit-only-placeholder");
    }

    /** The public entry point analyzes the definition: its metadata must arrive, or every audit workflow is reported. */
    @Test
    void analyzingADefinition_readsItsMetadata() {
        var blocks = List.of(block("simple", "report", Map.of("command", "Known findings: {{ audit_findings }}")));
        WorkflowDefinition audit = definition(blocks, Map.of("audit", Map.of(
                "enabled", true, "key", "project.security", "category", "security", "report_block", "report")), null);
        WorkflowDefinition plain = definition(blocks, null, null);

        assertThat(analyzer.analyze(audit)).extracting(AnalyzerFinding::rule).doesNotContain("audit-only-placeholder");
        assertThat(analyzer.analyze(plain)).extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
    }

    @Test
    void anUnreadAiOutput_isAFinding() {
        var blocks = List.of(
                block("simple", "analysis", Map.of("command", "Analyze the project in detail")),
                block("simple", "report", Map.of("command", "Write a report about the project")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("unwired-output");
            assertThat(f.blockIndex()).isZero();
        });
    }

    /** Step indices are 0-based: {@code step_0_output} is the first block. */
    @Test
    void outputThroughTheZeroBasedStepIndex_countsAsRead() {
        var blocks = List.of(
                block("simple", "analysis", Map.of("command", "Analyze the project in detail")),
                block("shell", "between", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("command", "Write a report from {{ step_0_output }}")));

        assertThat(rules(blocks, null)).doesNotContain("unwired-output");
    }

    @Test
    void aReferenceToTheFollowingStepIndex_doesNotReadTheBlock() {
        var blocks = List.of(
                block("simple", "analysis", Map.of("command", "Analyze the project in detail")),
                block("shell", "between", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("command", "Write a report from {{ step_1_output }}")));

        assertThat(analyzer.analyze(blocks, null)).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("unwired-output");
            assertThat(f.blockIndex()).isZero();
        });
    }

    /**
     * A shell block whose output nobody reads through a placeholder usually writes a file that the next block reads
     * again; that is legitimate and is not reported.
     */
    @Test
    void anUnreadShellOutput_isNoFinding() {
        var blocks = List.of(
                block("shell", "measure", Map.of("command", "set -euo pipefail\nls > /tmp/list.txt")),
                block("shell", "evaluate", Map.of("command", "set -euo pipefail\nwc -l /tmp/list.txt")));

        assertThat(rules(blocks, null)).doesNotContain("unwired-output");
    }

    @Test
    void theLastBlockAndWrappers_triggerNoDeadOutput() {
        var blocks = List.of(
                block("foreach", "per file", Map.of("items_command", "ls", "command", "do something")),
                block("simple", "end", Map.of("command", "Summarise")));

        assertThat(rules(blocks, null)).doesNotContain("unwired-output");
    }

    @Test
    void aPathWithARealName_isAFinding() {
        var blocks = List.of(block("shell", "build", Map.of("command",
                "set -euo pipefail\ncd /home/erika/project && make test")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("absolute-home-path");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.detail()).contains("erika");
        });
    }

    @Test
    void shellWithoutErrorAbort_isAFinding() {
        var without = List.of(block("shell", "build", Map.of("command", "make test")));
        var with = List.of(block("shell", "build", Map.of("command", "set -euo pipefail\nmake test")));

        assertThat(rules(without, null)).contains("missing-strict-mode");
        assertThat(rules(with, null)).doesNotContain("missing-strict-mode");
    }

    @Test
    void aVerbatimRepeatedInstruction_isAFinding() {
        String instructions = "Work only from the fact sheet and never count anything yourself. "
                + "Every number in the report names the FACT line it rests on, otherwise it does not belong there.";
        var blocks = List.of(block("simple", "report", Map.of("command", "Write the report.\n\n" + instructions)));

        assertThat(rules(blocks, instructions)).contains("duplicated-instructions");
    }

    @Test
    void aDatedModelId_isAFinding() {
        var blocks = List.of(block("simple", "a", Map.of(
                "command", "Summarise", "model", "claude-opus-4-6")));

        assertThat(rules(blocks, null)).contains("dated-model-id");
        assertThat(rules(List.of(block("simple", "a", Map.of("command", "Summarise", "model", "opus"))), null))
                .doesNotContain("dated-model-id");
    }

    @Test
    void aNestedBlock_isCheckedToo() {
        var blocks = List.of(block("foreach", "per file", Map.of(
                "items_command", "ls",
                "blocks", List.of(Map.of("type", "shell", "config", Map.of("command", "cd /home/erika/x"))))));

        assertThat(rules(blocks, null)).contains("absolute-home-path");
    }

    @Test
    void aCleanlyDefinedWorkflow_hasNoFindings() {
        var blocks = List.of(
                block("shell", "measure", Map.of("command",
                        "set -euo pipefail\ncd {{ project_path }}\nls docs")),
                block("simple", "report", Map.of("command",
                        "Evaluate {{ block.measure.output }} and summarise.",
                        "model", "opus")));

        assertThat(analyzer.analyze(blocks, null)).isEmpty();
        var fetching = List.of(
                block("shell", "measure", Map.of("command",
                        "set -euo pipefail\ncd {{ project_path }}\nls docs", "network", true)),
                block("simple", "report", Map.of("command",
                        "Evaluate {{ block.measure.output }} and summarise.", "model", "opus", "tools", "read")));
        assertThat(analyzer.analyze(definition(fetching, null, List.of("container.network", "ai.tools")))).isEmpty();
    }

    @Test
    void aWorkflowWithoutBlocks_isAnError() {
        for (var findings : List.of(analyzer.analyze(List.of(), null),
                analyzer.analyze((List<BlockDefinition>) null, null),
                analyzer.analyze(definition(null, null, null)))) {
            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("empty-workflow");
                assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
                assertThat(f.blockIndex()).isEqualTo(-1);
                assertThat(f.blockName()).isNull();
                assertThat(f.detail()).contains("no blocks");
            });
        }
        assertThat(analyzer.analyze((WorkflowDefinition) null)).isEmpty();
    }

    @Test
    void theTyposOfOnePackage_areOneFindingEach() {
        var blocks = List.of(
                block("shell", "y", Map.of("command", "set -euo pipefail\necho one")),
                block("shell", "y", Map.of("command", "set -euo pipefail\necho {{ block.x.output }} "
                        + "{{ block.y.outptu }} {{ nme }} {{ step_7_output }}")));

        assertThat(analyzer.analyze(blocks, null)).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(
                        tuple("unknown-block-reference", AnalyzerFinding.Severity.ERROR),
                        tuple("unknown-block-property", AnalyzerFinding.Severity.ERROR),
                        tuple("step-output-out-of-range", AnalyzerFinding.Severity.ERROR),
                        tuple("duplicate-block-name", AnalyzerFinding.Severity.ERROR),
                        tuple("unknown-variable", AnalyzerFinding.Severity.WARNING));
    }

    @Test
    void theInputsOfARun_areKnownVariables() {
        var workflow = definition(List.of(block("shell", "greet",
                Map.of("command", "set -euo pipefail\necho {{ nme }}"))), null, null);

        assertThat(analyzer.analyze(workflow)).extracting(AnalyzerFinding::rule).containsExactly("unknown-variable");
        assertThat(analyzer.withInputs(Set.of("nme")).analyze(workflow)).isEmpty();
        assertThat(analyzer.analyze(workflow)).as("the analyzer itself is unchanged").hasSize(1);
    }

    @Test
    void findings_comeMostSevereFirst() {
        var blocks = List.of(
                block("shell", "a", Map.of("command", "make test")),
                block("simple", "b", Map.of("command", "Take {{ block.missing.output }}")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).isNotEmpty();
        assertThat(findings.getFirst().severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
    }

    // --- on_no without an option that rejects ---

    @Test
    void onNoWithoutARejectingOption_isAFinding() {
        var blocks = List.of(block("approval", "approve", Map.of(
                "question", "Go on?", "options", List.of("Continue", "Abort"), "on_no", "fail")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("on-no-without-reject-option");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.detail()).contains("Abort");
        });
    }

    @Test
    void onNoWithNoOrRejectOptions_isNoFinding() {
        var blocks = List.of(
                block("approval", "german", Map.of("options", List.of("Yes", GermanWords.APPROVAL_REJECT_ANSWER),
                        "on_no", "fail")),
                block("approval", "own", Map.of("options", List.of("Continue", "Abort"),
                        "reject_options", List.of("Abort"), "on_no", "skip")),
                block("approval", "default", Map.of("on_no", "fail")),
                block("approval", "without-on-no", Map.of("options", List.of("A", "B"))));

        assertThat(rules(blocks, null)).doesNotContain("on-no-without-reject-option");
    }

    @Test
    void aNestedApproval_isCheckedToo() {
        var inner = Map.<String, Object>of("type", "approval", "name", "per-file",
                "config", Map.of("options", List.of("OK", "Stop"), "on_no", "fail"));
        var blocks = List.of(block("foreach", "files", Map.of("items", "a,b", "blocks", List.of(inner))));

        assertThat(analyzer.analyze(blocks, null)).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("on-no-without-reject-option");
            assertThat(f.blockIndex()).isZero();
            assertThat(f.blockName()).isEqualTo("files › per-file");
        });
    }

    // --- items_command without items_source "command" never runs ---

    @Test
    void itemsCommandWithoutItemsSource_isAFinding() {
        var blocks = List.of(
                block("foreach", "editor-default", Map.of("items_command", "ls docs", "command", "wc -l {{ item }}",
                        "execution_mode", "shell")),
                block("foreach", "with-list", Map.of("items_source", "list", "items", List.of("a"),
                        "items_command", "ls docs", "command", "echo {{ item }}", "execution_mode", "shell")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).filteredOn(f -> f.rule().equals("items-command-without-source"))
                .extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::severity)
                .containsExactly(tuple(0, AnalyzerFinding.Severity.ERROR), tuple(1, AnalyzerFinding.Severity.WARNING));
        assertThat(findings.getFirst().detail()).contains("not set").contains("zero items");
    }

    @Test
    void itemsCommandWithItemsSourceCommandOrEmpty_isNoFinding() {
        var blocks = List.of(
                block("foreach", "right", Map.of("items_source", "command", "items_command", "ls docs",
                        "command", "echo {{ item }}")),
                block("foreach", "unfilled", Map.of("items_command", "", "command", "")),
                block("foreach", "list", Map.of("items", List.of("a", "b"), "command", "echo {{ item }}")));

        assertThat(rules(blocks, null)).doesNotContain("items-command-without-source");
    }

    // --- the entry point ---

    /** The definition-level rules run without blocks too: a requirement Traceworks does not know refuses the workflow. */
    @Test
    void analyze_runsTheDefinitionRules_evenWithoutBlocks() {
        assertThat(analyzer.analyze(definition(List.of(), null, List.of("gpu"))))
                .extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex)
                .containsExactly(tuple("unknown-requirement", -1), tuple("empty-workflow", -1));
    }

    /** {@link WorkflowDefinition} keeps a null entry as written: it is reported, and the other blocks are still checked. */
    @Test
    void analyze_reportsANullBlockEntry_andChecksTheOthers() {
        List<BlockDefinition> blocks = new ArrayList<>();
        blocks.add(null);
        blocks.add(block("shell", "s", Map.of("command", "ls", "timeout_second", 5)));

        var findings = analyzer.analyze(definition(blocks, null, null));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex)
                .contains(tuple("unsupported-block-type", 0), tuple("unknown-config-key", 1),
                        tuple("missing-strict-mode", 1));
    }

    /** The block types come from the registry the analyzer is given, by default the core registry. */
    @Test
    void analyze_knowsTheTypesOfItsRegistry() {
        BlockExecutor shellOnly = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of("shell");
            }
        };
        var blocks = List.of(block("simple", "s", Map.of("command", "Summarise")));

        assertThat(new WorkflowAnalyzer(new BlockTypeRegistry(List.of(shellOnly))).analyze(definition(blocks, null, null)))
                .extracting(AnalyzerFinding::rule).containsExactly("unsupported-block-type");
        assertThat(analyzer.analyze(definition(blocks, null, null))).isEmpty();
    }
}
