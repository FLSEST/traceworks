package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules on an audit workflow and what it gets from its register ({@link AuditRules}). */
class AuditRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config == null ? null : new HashMap<>(config));
        return b;
    }

    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static WorkflowDefinition workflow(String provider, Map<String, Object> metadata, List<String> requires,
                                               BlockDefinition... blocks) {
        return new WorkflowDefinition("id-1", "test", null, Arrays.asList(blocks), null, null, provider, null,
                metadata, null, requires, "traceworks-workflow", 1);
    }

    private List<AnalyzerFinding> findings(String rule, WorkflowDefinition workflow) {
        return analyzer.analyze(workflow).stream().filter(f -> f.rule().equals(rule)).toList();
    }

    private List<AnalyzerFinding> findings(String rule, BlockDefinition... blocks) {
        return findings(rule, workflow(null, null, null, blocks));
    }

    @Nested
    class AuditWorkflow {

        private final BlockDefinition report = block("shell", "report", config("command", "echo report"));
        private final BlockDefinition reader = block("simple", "compare",
                config("command", "Compare with {{ previous_report }}"));

        private static Map<String, Object> audit(Object... keyValues) {
            Map<String, Object> block = new java.util.LinkedHashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) block.put((String) keyValues[i], keyValues[i + 1]);
            return Map.of("audit", block);
        }

        @Test
        void aBrokenAuditBlock_isAnError_namingEachProblem() {
            assertThat(findings("invalid-audit-config", workflow(null,
                    audit("enabled", true, "key", "k", "category", "c"), null, report)))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.blockIndex()).isEqualTo(-1);
                        assertThat(f.detail()).contains("metadata.audit").contains("report_block");
                    });
            assertThat(findings("invalid-audit-config", workflow(null,
                    audit("enabled", "yes", "key", "k", "category", "c", "report_block", "report"), null, report)))
                    .singleElement().satisfies(f -> assertThat(f.detail()).contains("enabled"));
            assertThat(findings("invalid-audit-config", workflow(null,
                    audit("enabled", true, "key", "k", "category", "c", "report_block", "report"), null, report)))
                    .isEmpty();
            assertThat(findings("invalid-audit-config", workflow(null,
                    audit("enabled", false), null, report))).as("disabled is fine").isEmpty();
        }

        @Test
        void aKeyThatIsNoPathSegment_isAnError() {
            assertThat(findings("invalid-audit-key", workflow(null,
                    audit("enabled", true, "key", "My Audit", "category", "c", "report_block", "report"), null,
                    report))).singleElement().satisfies(f -> assertThat(f.detail()).contains("My Audit")
                    .contains("[a-z0-9][a-z0-9._-]{0,127}"));
        }

        @Test
        void aReportBlockThatNamesNoTopLevelBlock_isAnError() {
            assertThat(findings("report-block-missing", workflow(null,
                    audit("enabled", true, "key", "k", "category", "c", "report_block", "summary"), null, report)))
                    .singleElement().satisfies(f -> assertThat(f.detail()).contains("summary")
                            .contains("names no top-level block"));
            assertThat(findings("report-block-missing", workflow(null,
                    audit("enabled", true, "key", "k", "category", "c", "report_block", "report"), null, report)))
                    .isEmpty();
        }

        /**
         * The step that runs a child copies the child's output, and a gate marker in a copy sets no gate
         * ({@code GateSources}): an audit whose report block is such a step ends without a gate of its report.
         */
        @Test
        void aReportBlockThatRunsAChildWorkflow_getsAHintThatItsMarkersSetNoGate() {
            Map<String, Object> audit = audit("enabled", true, "key", "k", "category", "c", "report_block", "report");
            BlockDefinition child = block("sub-workflow", "report", Map.of("workflow_id", "./report"));
            BlockDefinition loop = block("foreach", "report", Map.of("workflow_id", "./report", "items", List.of("a")));
            BlockDefinition commands = block("foreach", "report", Map.of("execution_mode", "shell",
                    "items", List.of("a"), "command", "echo {{ item }}"));

            assertThat(findings("report-from-child-sets-no-gate", workflow(null, audit, null, child)))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.INFO);
                        assertThat(f.blockName()).isEqualTo("report");
                        assertThat(f.detail()).contains("gate_status", "child");
                    });
            assertThat(findings("report-from-child-sets-no-gate", workflow(null, audit, null, loop))).hasSize(1);
            assertThat(findings("report-from-child-sets-no-gate", workflow(null, audit, null, commands))).isEmpty();
            assertThat(findings("report-from-child-sets-no-gate", workflow(null, audit, null, report))).isEmpty();
            assertThat(findings("report-from-child-sets-no-gate", workflow(null, null, null, child)))
                    .as("no audit, no report block").isEmpty();
        }

        /**
         * Decided 2026-11-11: outside an audit workflow the placeholder is an error, not a literal
         * (docs/workflow-format.md).
         */
        @Test
        void previousReport_outsideAnAuditWorkflow_isAnError() {
            assertThat(findings("previous-report-outside-audit", workflow(null, null, null, report, reader)))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.blockIndex()).isEqualTo(1);
                        assertThat(f.detail()).contains("previous_report").contains("metadata.audit");
                    });
            assertThat(findings("previous-report-outside-audit", workflow(null,
                    audit("enabled", true, "key", "k", "category", "c", "report_block", "report"), null, report,
                    reader))).isEmpty();
            assertThat(findings("previous-report-outside-audit", workflow(null, audit("enabled", false), null, report,
                    reader))).as("an audit that is switched off has no register either").hasSize(1);
            assertThat(findings("previous-report-outside-audit", workflow(null,
                    audit("enabled", true, "key", "k", "category", "c"), null, report, reader)))
                    .as("a broken audit block is invalid-audit-config, not this").isEmpty();
        }

        /** Only the run of an audit workflow has a register folder: anywhere else the placeholder would stay as text. */
        @Test
        void registerDir_outsideAnAuditWorkflow_isAnError() {
            BlockDefinition lister = block("shell", "known",
                    config("command", "wc -l {{ register_dir }}/findings.jsonl"));

            assertThat(findings("register-dir-outside-audit", workflow(null, null, null, report, lister)))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.blockIndex()).isEqualTo(1);
                        assertThat(f.detail()).contains("register_dir").contains("metadata.audit")
                                .contains("under another name").doesNotContain("as a context value");
                    });
            assertThat(findings("register-dir-outside-audit", workflow(null, audit("enabled", false), null, report,
                    lister))).as("an audit that is switched off feeds no register and gets no folder").hasSize(1);
            WorkflowDefinition auditWorkflow = workflow(null,
                    audit("enabled", true, "key", "k", "category", "c", "report_block", "report"), null, report,
                    lister);
            assertThat(findings("register-dir-outside-audit", auditWorkflow)).isEmpty();
            assertThat(findings("unknown-variable", auditWorkflow)).as("the engine sets it").isEmpty();
            assertThat(findings("register-dir-outside-audit", workflow(null,
                    audit("enabled", true, "key", "k", "category", "c"), null, report, lister)))
                    .as("a broken audit block is invalid-audit-config, not this").isEmpty();
        }

        /**
         * The container of an AI call has no register folder, and on a host run the prompt would carry a path of the
         * run folder: in a prompt the placeholder is an error, in an audit workflow too.
         */
        @Test
        void registerDir_inThePromptOfAnAiCall_isAnError() {
            Map<String, Object> audit = audit("enabled", true, "key", "k", "category", "c", "report_block", "report");
            BlockDefinition simple = block("simple", "judge", config("command", "Read {{ register_dir }}/findings.jsonl"));
            BlockDefinition agentic = block("agentic", "fix", config("goal", "Fix it",
                    "success_criteria", "nothing in {{ register_dir }}"));
            BlockDefinition aiLoop = block("retry", "again", config("command", "Look at {{ register_dir }}"));
            BlockDefinition shellLoop = block("retry", "list", config("command", "ls {{ register_dir }}",
                    "execution_mode", "shell"));
            BlockDefinition gated = block("simple", "gated", config("command", "Judge this",
                    "run_if", "test -s {{ register_dir }}/findings.jsonl"));

            assertThat(findings("register-dir-in-ai-prompt", workflow(null, audit, null, report, simple, agentic,
                    aiLoop, shellLoop, gated))).extracting(AnalyzerFinding::blockIndex).containsExactly(1, 2, 3);
            assertThat(findings("register-dir-in-ai-prompt", workflow(null, audit, null, report, simple)))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.detail()).contains("register_dir").contains("AI");
                    });
        }

        @Test
        void anInvalidCatalog_isAnError() {
            WorkflowDefinition wf = workflow(null,
                    audit("enabled", true, "key", "k", "category", "c", "report_block", "report"), null, report);

            assertThat(analyzer.analyze(wf, null, List.of(), List.of(), "{\"format\":2}".getBytes())
                    .stream().filter(f -> f.rule().equals("catalog-invalid")).toList())
                    .singleElement().satisfies(f -> assertThat(f.detail()).contains("catalog.json"));
            assertThat(analyzer.analyze(wf, null, List.of(), List.of(), "{\"format\":1,\"rules\":{}}".getBytes())
                    .stream().filter(f -> f.rule().equals("catalog-invalid")).toList()).isEmpty();
        }
    }

    @Nested
    class ReportBlockRequired {

        private final BlockDefinition reader = block("simple", "compare",
                config("command", "Compare with {{ previous_report }}"));

        @Test
        void previousReportWithoutReportBlock_isAnErrorOnTheReadingBlock() {
            assertThat(findings("report-block-required",
                    workflow(null, null, null, block("shell", "s", config("command", "ls")), reader)))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.blockIndex()).isEqualTo(1);
                        assertThat(f.detail()).contains("metadata.audit.report_block")
                                .contains("the previous report is the output of that block");
                    });
            assertThat(findings("report-block-required", workflow(null,
                    Map.of("audit", Map.of("enabled", true, "report_block", " ")), null, reader))).hasSize(1);
            assertThat(findings("report-block-required", workflow(null,
                    Map.of("audit", "report"), null, reader))).hasSize(1);
        }

        @Test
        void withAReportBlock_orWithoutPreviousReport_isFine() {
            assertThat(findings("report-block-required", workflow(null,
                    Map.of("audit", Map.of("report_block", "compare")), null, reader))).isEmpty();
            assertThat(findings("report-block-required",
                    block("simple", "s", config("command", "Write {{ previous_output }}")))).isEmpty();
        }
    }
}
