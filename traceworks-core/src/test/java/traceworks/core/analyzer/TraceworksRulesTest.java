package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.engine.PlatformVariables;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The rules on what Traceworks refuses or does not provide: where a definition relies on a default Traceworks does
 * not have or on a feature it does not run, where Traceworks refuses a workflow (docs/workflow-format.md), and where a
 * step depends on the host
 * it runs on.
 */
class TraceworksRulesTest {

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
    class ExplicitExecutionMode {

        @Test
        void conditionalRetryWhileAndForeach_withoutExecutionMode_areErrors() {
            var found = findings("explicit-execution-mode",
                    block("conditional", "c", config("condition", "test -f x", "then_command", "echo y")),
                    block("retry", "r", config("command", "make test")),
                    block("while", "w", config("command", "echo", "condition_command", "test -f x")),
                    block("foreach", "f", config("items", List.of("a"), "command", "echo {{ item }}")));

            assertThat(found).extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::severity)
                    .containsExactly(tuple(0, Severity.ERROR), tuple(1, Severity.ERROR), tuple(2, Severity.ERROR),
                            tuple(3, Severity.ERROR));
            assertThat(found.getFirst().detail()).contains("execution_mode").contains("shell or ai")
                    .contains("a workflow states whether a step asks a model");
        }

        @Test
        void aWrittenOutMode_otherTypes_andForeachWithWorkflowId_areFine() {
            assertThat(findings("explicit-execution-mode",
                    block("conditional", "c", config("condition", "x", "execution_mode", "ai")),
                    block("retry", "r", config("command", "make test", "execution_mode", "shell")),
                    block("while", "w", config("command", "echo", "execution_mode", "claude")),
                    block("foreach", "f", config("items", List.of("a"), "workflow_id", "child")),
                    block("simple", "s", config("command", "Summarise")),
                    block("shell", "sh", config("command", "ls")),
                    block("switch", "sw", config("expression", "x", "cases", List.of())))).isEmpty();
        }
    }

    @Nested
    class UnbalancedPlaceholder {

        @Test
        void unbalancedSpaces_areAnError_oneFindingPerBlock() {
            var found = findings("unbalanced-placeholder",
                    block("shell", "s", config("command", "echo {{x }} {{ y}} {{  z  }} {{ ok }}")),
                    block("simple", "p", config("command", "Read {{ previous_output}}")));

            assertThat(found).extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::severity)
                    .containsExactly(tuple(0, Severity.ERROR), tuple(1, Severity.ERROR));
            assertThat(found.getFirst().detail()).startsWith("{{x }}, {{ y}}, {{  z  }} ")
                    .contains("stays literal").doesNotContain("{{ ok }}");
        }

        @Test
        void bothSpellingsOfTheResolver_andTextThatIsNoPlaceholder_areFine() {
            assertThat(findings("unbalanced-placeholder",
                    block("shell", "s", config("command", "echo {{x}} {{ y }} {{ block.Build Report.output }}\n"
                            + "case \"$X\" in '{{ '*) X=default;; esac")))).isEmpty();
        }

        @Test
        void aNestedPlaceholder_isReportedOnTheOuterBlock() {
            var inner = Map.<String, Object>of("type", "shell", "config", Map.of("command", "echo {{item }}"));

            assertThat(findings("unbalanced-placeholder",
                    block("shell", "first", config("command", "ls")),
                    block("switch", "route", config("expression", "x", "default", List.of(inner)))))
                    .extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::blockName)
                    .containsExactly(tuple(1, "route"));
        }
    }

    @Nested
    class UnknownRequirement {

        @Test
        void theKnownRequirements_areFine() {
            assertThat(findings("unknown-requirement", workflow(null, null,
                    List.of("container.network", "package.images", "ai.tools")))).isEmpty();
        }

        @Test
        void anUnknownRequirement_isAnErrorOnTheDefinition() {
            assertThat(findings("unknown-requirement", workflow(null, null, List.of("gpu", "ai.tools"))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.blockIndex()).isEqualTo(-1);
                        assertThat(f.blockName()).isNull();
                        assertThat(f.detail()).contains("\"gpu\"").contains("container.network, package.images, ai.tools");
                    });
        }

        /** No longer a requirement: a container has no network unless the block asks for it. */
        @Test
        void containerNetworkNone_isUnknown_withAHint() {
            assertThat(findings("unknown-requirement", workflow(null, null, List.of("container.network-none"))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString()
                    .contains("no network is the default");
        }

        @Test
        void aNullEntry_isUnknownToo() {
            assertThat(findings("unknown-requirement", workflow(null, null, Arrays.asList("ai.tools", null))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString().contains("null");
        }
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
                        assertThat(f.detail()).contains("register_dir").contains("metadata.audit");
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

    @Nested
    class UnsupportedBlockType {

        @Test
        void theCoreTypes_areFine() {
            assertThat(findings("unsupported-block-type",
                    block("shell", "a", config("command", "ls")),
                    block("simple", "b", config("command", "x")),
                    block("compress-ai", "c", config("source", "previous")),
                    block("human-input", "d", config()))).isEmpty();
        }

        @Test
        void aTypeKnownByNameThatTraceworksDoesNotRun_isAnError_thatNamesIt() {
            assertThat(findings("unsupported-block-type", block("integration", "jira", config("provider", "jira"))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.detail()).contains("integration is a block type Traceworks does not run");
                    });
        }

        @Test
        void anUnknownType_suggestsANearOne_andAMissingTypeIsReported() {
            assertThat(findings("unsupported-block-type",
                    block("shel", "a", config("command", "ls")),
                    block(null, "b", config()),
                    block("whatever", "c", config())))
                    .extracting(AnalyzerFinding::detail)
                    .satisfiesExactly(
                            d -> assertThat(d).contains("\"shel\"").contains("did you mean shell?"),
                            d -> assertThat(d).contains("no type"),
                            d -> assertThat(d).contains("\"whatever\"").doesNotContain("did you mean"));
        }

        @Test
        void aNestedBlockAndANullEntry_areReported() {
            var inner = Map.<String, Object>of("type", "todo-close", "config", Map.of("id", "x"));
            List<BlockDefinition> blocks = new ArrayList<>();
            blocks.add(block("switch", "route", config("expression", "x", "default", List.of(inner))));
            blocks.add(null);

            var found = findings("unsupported-block-type", workflow(null, null, null,
                    blocks.toArray(new BlockDefinition[0])));

            assertThat(found).extracting(AnalyzerFinding::blockIndex).containsExactly(0, 1);
            assertThat(found).extracting(AnalyzerFinding::detail).satisfiesExactly(
                    d -> assertThat(d).startsWith("Block 1 › default › Block 1: ").contains("todo-close"),
                    d -> assertThat(d).contains("null"));
        }
    }

    @Nested
    class UnprovidedVariable {

        @Test
        void unprovidedVariables_areAWarning_oneFindingPerBlock() {
            var found = findings("unprovided-variable",
                    block("simple", "a", config("command", "Open: {{ open_todos }} {{ todo_title }} {{ goal_status }}")),
                    block("shell", "b", config("command", "echo {{ request_metrics }} {{ project_id }} {{ home_dir }}"
                            + " {{ platform_scripts }} {{ goal_guard_item_1 }}")));

            assertThat(found).extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::severity)
                    .containsExactly(tuple(0, Severity.WARNING), tuple(1, Severity.WARNING));
            assertThat(found.getFirst().detail())
                    .startsWith("{{ open_todos }}, {{ todo_title }}, {{ goal_status }} ")
                    .contains("Traceworks does not provide them", "a run input of the same name is used as is",
                            "otherwise the placeholder stays literal");
            assertThat(found.get(1).detail()).contains("{{ platform_scripts }}", "{{ goal_guard_item_1 }}");
        }

        @Test
        void traceworksVariables_areFine() {
            assertThat(findings("unprovided-variable",
                    block("shell", "a", config("command", "cd {{ project_path }} && echo {{ workflow_run_id }}"
                            + " {{ previous_report }} {{ audit_findings }} {{ audit_items }} {{ todos }} {{ goals }}"
                            + " {{ execution_path }}")))).isEmpty();
        }

        /**
         * No drift from the engine: every name and prefix the engine reserves in {@link PlatformVariables} is reported,
         * except {@code previous_report}, {@code audit_findings} and {@code audit_items}, which Traceworks keeps (the
         * registers bring them) and which their own rules judge.
         */
        @Test
        void everyPlatformVariableOfTheEngine_isReported_exceptTheOnesTraceworksKeeps() {
            Set<String> kept = Set.of("previous_report", "audit_findings", "audit_items");
            assertThat(PlatformVariables.NAMES).as("no input sets what the register sets").contains("audit_items");
            List<String> names = new ArrayList<>(PlatformVariables.NAMES);
            names.add(PlatformVariables.GOAL_GUARD_PREFIX + "status");
            names.add(PlatformVariables.EXECUTION_PATH_PREFIX + "repo");

            for (String name : names) {
                var found = findings("unprovided-variable",
                        block("simple", "s", config("command", "Use {{ " + name + " }}")));
                if (kept.contains(name)) {
                    assertThat(found).as(name).isEmpty();
                } else {
                    assertThat(found).as(name).singleElement().extracting(AnalyzerFinding::detail).asString()
                            .startsWith("{{ " + name + " }} ");
                }
            }
            assertThat(analyzer.analyze(workflow(null, null, null, block("simple", "s",
                    config("command", "Compare {{ previous_report }} with {{ audit_findings }}")))))
                    .extracting(AnalyzerFinding::rule).contains("report-block-required", "audit-only-placeholder");
        }

        @Test
        void aNestedVariable_isReportedOnTheOuterBlock() {
            var inner = Map.<String, Object>of("type", "simple", "config", Map.of("command", "{{ todo_details }}"));

            assertThat(findings("unprovided-variable",
                    block("switch", "route", config("expression", "x", "default", List.of(inner)))))
                    .singleElement().extracting(AnalyzerFinding::blockName).isEqualTo("route");
        }
    }

    @Nested
    class BlankAiPrompt {

        @Test
        void blankPrompts_areAWarning() {
            var inner = Map.<String, Object>of("type", "simple", "name", "inner", "config", Map.of("command", " "));
            var found = findings("blank-ai-prompt",
                    block("simple", "s", config("command", "  ")),
                    block("conditional", "c", config("condition", "", "execution_mode", "ai")),
                    block("retry", "r", config("command", "\n", "execution_mode", "ai")),
                    block("while", "w", config("command", " ", "execution_mode", "ai")),
                    block("foreach", "f", config("command", "\t", "items", List.of("a"), "execution_mode", "ai")),
                    block("switch", "sw", config("expression", "x", "default", List.of(inner))));

            assertThat(found).extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::severity)
                    .containsExactly(tuple(0, Severity.WARNING), tuple(1, Severity.WARNING),
                            tuple(2, Severity.WARNING), tuple(3, Severity.WARNING), tuple(4, Severity.WARNING),
                            tuple(5, Severity.WARNING));
            assertThat(found.get(1).detail()).contains("condition").contains("model");
            assertThat(found.get(5).detail()).startsWith("Block 6 › default › Block 1 \"inner\": ");
        }

        @Test
        void shellMode_aMissingPrompt_aForeachWithWorkflowId_andAWrittenPrompt_areFine() {
            assertThat(findings("blank-ai-prompt",
                    block("conditional", "c", config("condition", " ", "execution_mode", "shell")),
                    block("retry", "r", config("command", "", "execution_mode", "shell")),
                    block("simple", "missing", config()),
                    block("foreach", "f", config("command", " ", "workflow_id", "child", "execution_mode", "ai")),
                    block("simple", "s", config("command", "Summarise")),
                    block("conditional", "unset", config("condition", " ")))).isEmpty();
        }
    }

    @Nested
    class UnknownProvider {

        @Test
        void theKnownProviders_inAnyCase_areFine() {
            assertThat(findings("unknown-provider", workflow("claude", null, null,
                    block("simple", "a", config("command", "x", "provider", "Pi")),
                    block("simple", "b", config("command", "x", "provider", " pi ")),
                    block("agentic", "c", config("goal", "x", "provider", "CLAUDE")),
                    block("simple", "d", config("command", "x", "provider", ""))))).isEmpty();
        }

        @Test
        void anUnknownWorkflowProvider_isAnErrorOnTheDefinition() {
            assertThat(findings("unknown-provider", workflow("gemini", null, null,
                    block("simple", "a", config("command", "x"))))).singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.blockIndex()).isEqualTo(-1);
                        assertThat(f.detail()).contains("\"gemini\"").contains("CLAUDE, PI");
                    });
        }

        @Test
        void anUnknownBlockProvider_aPlaceholder_aNonText_andANestedOne_areErrors() {
            var inner = Map.<String, Object>of("type", "simple", "config", Map.of("command", "x", "provider", "llama"));

            assertThat(findings("unknown-provider",
                    block("simple", "a", config("command", "x", "provider", "openai")),
                    block("compress-ai", "b", config("source", "previous", "provider", "{{ provider }}")),
                    block("simple", "c", config("command", "x", "provider", 5)),
                    block("switch", "d", config("expression", "x", "default", List.of(inner)))))
                    .extracting(AnalyzerFinding::blockIndex).containsExactly(0, 1, 2, 3);
        }

        /** CODEX on a block is refused by a rule of its own, so it is not also an unknown provider. */
        @Test
        void codexOnABlock_isNoUnknownProvider() {
            assertThat(findings("unknown-provider",
                    block("simple", "a", config("command", "x", "provider", "codex")),
                    block("agentic", "b", config("goal", "x", "provider", " CODEX ")))).isEmpty();
        }

        /** A switch never reads provider (its case blocks read their own); invalid-config-value judges the value. */
        @Test
        void aSwitch_readsNoProvider() {
            var inner = Map.<String, Object>of("type", "simple", "config", Map.of("command", "x"));

            assertThat(findings("unknown-provider", block("switch", "route",
                    config("expression", "x", "default", List.of(inner), "provider", "gemini")))).isEmpty();
        }

        /** approval and human-input call a model only for an AI actor: ai, shadow, or a placeholder that may be one. */
        @Test
        void approvalAndHumanInput_readTheProviderOnlyWithAnAiActor() {
            assertThat(findings("unknown-provider",
                    block("approval", "a", config("question", "Go?", "provider", "gemini")),
                    block("approval", "b", config("question", "Go?", "provider", "gemini", "actor", "human")),
                    block("approval", "c", config("question", "Go?", "provider", "gemini", "actor", "ai")),
                    block("human-input", "d", config("prompt", "Name?", "provider", "gemini", "actor", " Shadow ")),
                    block("human-input", "e", config("prompt", "Name?", "provider", "gemini")),
                    block("approval", "f", config("question", "Go?", "provider", "gemini", "actor", "{{ actor_po }}"))))
                    .extracting(AnalyzerFinding::blockIndex).containsExactly(2, 3, 5);
        }

        /** The control-flow blocks ask a model only in ai mode, a foreach only when it runs a command per item. */
        @Test
        void controlFlowBlocks_readTheProviderOnlyInAiMode() {
            assertThat(findings("unknown-provider",
                    block("retry", "a", config("command", "x", "execution_mode", "shell", "provider", "gemini")),
                    block("retry", "b", config("command", "x", "execution_mode", "ai", "provider", "gemini")),
                    block("conditional", "c", config("condition", "x", "execution_mode", "ai", "provider", "gemini")),
                    block("while", "d", config("command", "x", "provider", "gemini")),
                    block("foreach", "e", config("command", "x", "items", List.of("a"), "execution_mode", "ai",
                            "provider", "gemini")),
                    block("foreach", "f", config("workflow_id", "child", "items", List.of("a"), "execution_mode", "ai",
                            "provider", "gemini"))))
                    .extracting(AnalyzerFinding::blockIndex).containsExactly(1, 2, 4);
        }

        /** A shell block does not read provider; unknown-config-key says so, this rule does not judge the value. */
        @Test
        void aProviderOnABlockThatDoesNotReadIt_isLeftToTheKeyRule() {
            assertThat(findings("unknown-provider", block("shell", "s", config("command", "ls", "provider", "x"))))
                    .isEmpty();
        }
    }

    @Nested
    class HostDependentStep {

        @Test
        void aHostToolWithoutAnImage_isAWarning() {
            for (String command : List.of("curl -s https://example.org", "wget -q x", "mvn -B verify", "npm ci",
                    "pip install -r requirements.txt", "docker ps", "X=$(/usr/bin/curl -s x)", "ls | npm run x")) {
                assertThat(findings("host-dependent-step", block("shell", "s", config("command", command))))
                        .as(command).singleElement().satisfies(f -> {
                            assertThat(f.severity()).isEqualTo(Severity.WARNING);
                            assertThat(f.detail()).contains("Traceworks runs steps in containers")
                                    .contains("container_image");
                        });
            }
        }

        @Test
        void anAbsoluteHostPathWithoutAnImage_isAWarning() {
            for (String command : List.of("cat /home/x/notes", "ls /Users/x", "ls /root/.m2", "cd /home")) {
                assertThat(findings("host-dependent-step", block("shell", "s", config("command", command))))
                        .as(command).singleElement().extracting(AnalyzerFinding::detail).asString()
                        .contains("host path");
            }
        }

        /** A blank container_image names no image: the step still runs on the shell image. */
        @Test
        void aBlankImage_isNoImage() {
            assertThat(findings("host-dependent-step",
                    block("shell", "a", config("command", "curl -s x", "container_image", "  ")))).hasSize(1);
        }

        /** Only a line that starts with # is a comment: a command with a comment after it counts. */
        @Test
        void aCommandLineWithACommentAfterIt_counts() {
            assertThat(findings("host-dependent-step",
                    block("shell", "a", config("command", "curl -s x # fetch the list")))).hasSize(1);
        }

        @Test
        void withAnImage_orWithoutHostTools_isFine() {
            assertThat(findings("host-dependent-step",
                    block("shell", "a", config("command", "curl -s x", "container_image", "curlimages/curl:8.10.1")),
                    block("shell", "b", config("command", "ls -la && ./mvnw verify && pip3 --version")),
                    block("shell", "c", config("command", "my-curl-wrapper; ./curl.sh; echo npm-run-all")),
                    block("shell", "d", config("command", "ls /workspace/root https://example.org/home/x")),
                    block("simple", "e", config("command", "Explain what curl -s does in /home/x")))).isEmpty();
        }

        @Test
        void wrappersInShellMode_itemsCommand_andNestedShellBlocks_areChecked() {
            var inner = Map.<String, Object>of("type", "shell", "config", Map.of("command", "docker ps"));

            assertThat(findings("host-dependent-step",
                    block("retry", "a", config("command", "mvn test", "execution_mode", "shell")),
                    block("retry", "b", config("command", "Run mvn test", "execution_mode", "ai")),
                    block("foreach", "c", config("items_command", "curl -s x", "items_source", "command",
                            "command", "Summarise {{ item }}", "execution_mode", "ai")),
                    block("switch", "d", config("expression", "x", "default", List.of(inner))),
                    block("retry", "e", config("command", "mvn test", "execution_mode", "shell",
                            "container_image", "maven:3.9"))))
                    .extracting(AnalyzerFinding::blockIndex).containsExactly(0, 2, 3);
        }

        /** A tool or a path named in a shell comment is not used by the step. */
        @Test
        void aCommentLineOfTheShell_doesNotCount_aCommandLineBesideItDoes() {
            assertThat(findings("host-dependent-step",
                    block("shell", "a", config("command", "# fetched with curl before\n  # see /home/x/notes\nls")),
                    block("shell", "b", config("command", "# build\nmvn -B verify"))))
                    .extracting(AnalyzerFinding::blockIndex).containsExactly(1);
        }
    }

    @Nested
    class UnknownFormat {

        private WorkflowDefinition withFormat(String format, Integer formatVersion) {
            return new WorkflowDefinition("id-1", "test", null, List.of(block("shell", "s", config("command", "ls"))),
                    null, null, null, null, null, null, null, format, formatVersion);
        }

        @Test
        void theTraceworksFormat_andNoMarkerAtAll_areFine() {
            assertThat(findings("unknown-format", withFormat("traceworks-workflow", 1))).isEmpty();
            assertThat(findings("unknown-format", withFormat(null, null))).isEmpty();
        }

        @Test
        void anotherFormat_andANewerVersion_areErrorsOnTheDefinition() {
            assertThat(findings("unknown-format", withFormat("n8n-workflow", 2)))
                    .extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex, AnalyzerFinding::detail)
                    .containsExactly(
                            tuple(Severity.ERROR, -1, "format \"n8n-workflow\" is not traceworks-workflow — Traceworks"
                                    + " refuses the workflow"),
                            tuple(Severity.ERROR, -1, "format_version 2 is newer than this version of Traceworks reads"
                                    + " (1) — it refuses the workflow"));
        }
    }

    @Nested
    class UnsupportedSkills {

        @Test
        void skillIdsOnABlock_areAnError_anEmptyListIsFine() {
            BlockDefinition withSkills = block("simple", "ask", config("command", "Summarise"));
            withSkills.putUnknownField("skillIds", List.of("skill-1"));
            BlockDefinition withoutSkills = block("simple", "other", config("command", "Summarise"));
            withoutSkills.putUnknownField("skillIds", List.of());

            assertThat(findings("unsupported-skills", withoutSkills, withSkills))
                    .extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex, AnalyzerFinding::detail)
                    .containsExactly(tuple(Severity.ERROR, 1, "skillIds [skill-1] — Traceworks has no skills, so the"
                            + " step would run without them, and refuses the workflow; put what the step"
                            + " needs into its prompt"));
        }
    }

    @Nested
    class UnsupportedWorkspaceIsolation {

        private final BlockDefinition build = block("shell", "build", config("command", "make"));

        @Test
        void anIsolatedWorkspace_inTheMetadataOrOnTheFirstBlock_isAnError() {
            assertThat(findings("unsupported-workspace-isolation",
                    workflow(null, Map.of("workspace", Map.of("mode", "isolated")), null, build)))
                    .extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex, AnalyzerFinding::detail)
                    .containsExactly(tuple(Severity.ERROR, -1, "metadata.workspace.mode is isolated — a workflow"
                            + " cannot isolate its own run; the operator isolates a run with"
                            + " run --source, so the workflow is refused: remove the setting and start the run with"
                            + " --source"));

            BlockDefinition first = block("shell", "build", config("command", "make",
                    "workspace", Map.of("mode", "isolated")));
            assertThat(findings("unsupported-workspace-isolation", first))
                    .extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::blockName,
                            f -> f.detail().startsWith("workspace.mode is isolated — "))
                    .containsExactly(tuple(0, "build", true));
        }

        @Test
        void theProjectMode_isFine() {
            assertThat(findings("unsupported-workspace-isolation",
                    workflow(null, Map.of("workspace", Map.of("mode", "project")), null, build))).isEmpty();
        }
    }

    @Nested
    class DollarPlaceholderInSwitch {

        @Test
        void aDollarPlaceholderInASwitchExpression_isAnError() {
            assertThat(findings("dollar-placeholder-in-switch",
                    block("switch", "route", config("expression", "${status}-{{ env }}", "cases", List.of())),
                    block("switch", "fine", config("expression", "{{ status }}", "cases", List.of())),
                    block("shell", "notSwitch", config("command", "echo ${HOME}"))))
                    .extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex, AnalyzerFinding::detail)
                    .containsExactly(tuple(Severity.ERROR, 0, "the switch expression uses ${status}, which Traceworks"
                            + " does not resolve; write {{ status }} — Traceworks refuses the"
                            + " workflow"));
        }
    }

    @Nested
    class DockerSocketRefused {

        @Test
        void whereAShellStepAsksForTheDockerSocket_itIsAnError() {
            var nestedShell = Map.<String, Object>of("type", "shell", "name", "inner",
                    "config", Map.of("command", "docker ps", "mount_docker_socket", true));

            var found = findings("docker-socket-refused",
                    block("shell", "a", config("command", "docker ps", "mount_docker_socket", true)),
                    block("retry", "b", config("command", "docker ps", "execution_mode", "shell",
                            "mount_docker_socket", true)),
                    block("switch", "c", config("expression", "x", "default", List.of(nestedShell))));

            assertThat(found).extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                    .containsExactly(tuple(Severity.ERROR, 0), tuple(Severity.ERROR, 1), tuple(Severity.ERROR, 2));
            assertThat(found.getFirst().detail()).isEqualTo("mount_docker_socket: true — Traceworks never mounts the"
                    + " Docker socket and refuses the step; remove the setting");
            assertThat(found.getLast().detail()).startsWith("Block 3 › default › Block 1 \"inner\": ");
        }

        @Test
        void false_orAStepThatRunsNoShell_isNotReported() {
            assertThat(findings("docker-socket-refused",
                    block("shell", "a", config("command", "ls", "mount_docker_socket", false)),
                    block("retry", "b", config("command", "Run it", "execution_mode", "ai",
                            "mount_docker_socket", true)))).isEmpty();
        }

        /** In a switch case a simple block in shell mode runs as a shell step, so it reads the setting too. */
        @Test
        void aSimpleBlockOfASwitchCaseInShellMode_isReported_atTheTopLevelItIsAnAiStepAndIsNot() {
            var nestedSimple = Map.<String, Object>of("type", "simple", "name", "inner", "config",
                    Map.of("command", "docker ps", "execution_mode", "shell", "mount_docker_socket", true));

            var found = findings("docker-socket-refused",
                    block("simple", "a", config("command", "docker ps", "execution_mode", "shell",
                            "mount_docker_socket", true)),
                    block("switch", "b", config("expression", "x", "default", List.of(nestedSimple))));

            assertThat(found).singleElement().satisfies(f -> {
                assertThat(f.blockIndex()).isEqualTo(1);
                assertThat(f.detail()).startsWith("Block 2 › default › Block 1 \"inner\": mount_docker_socket: true");
            });
        }

        /** A conditional in shell mode is a shell step too, and so is every other wrapper in shell mode. */
        @Test
        void aConditionalInShellMode_isReported() {
            assertThat(findings("docker-socket-refused", block("conditional", "a", config("command", "docker ps",
                    "execution_mode", "shell", "mount_docker_socket", true)))).hasSize(1);
        }

        /** A simple block of a switch case in AI mode is an AI step: it reads no shell setting. */
        @Test
        void aSimpleBlockOfASwitchCaseInAiMode_isNotReported() {
            var nestedSimple = Map.<String, Object>of("type", "simple", "name", "inner", "config",
                    Map.of("command", "Judge it", "execution_mode", "ai", "mount_docker_socket", true));

            assertThat(findings("docker-socket-refused",
                    block("switch", "a", config("expression", "x", "default", List.of(nestedSimple))))).isEmpty();
        }

        /** A switch case runs only shell and simple blocks: a wrapper there never runs, so its setting is not read. */
        @Test
        void aWrapperInsideASwitchCase_isNotReported() {
            var nestedRetry = Map.<String, Object>of("type", "retry", "name", "inner", "config",
                    Map.of("command", "docker ps", "execution_mode", "shell", "mount_docker_socket", true));

            assertThat(findings("docker-socket-refused",
                    block("switch", "a", config("expression", "x", "default", List.of(nestedRetry))))).isEmpty();
        }
    }
}
