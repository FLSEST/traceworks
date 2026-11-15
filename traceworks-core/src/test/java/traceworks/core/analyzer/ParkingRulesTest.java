package traceworks.core.analyzer;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.testing.MapWorkflowResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParkingRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, id, null, List.of(blocks), null, null, null, null, null, null, null, null,
                null);
    }

    private static List<AnalyzerFinding> analyze(WorkflowDefinition workflow, MapWorkflowResolver children) {
        return new WorkflowAnalyzer().analyze(workflow, children);
    }

    private static List<AnalyzerFinding> of(List<AnalyzerFinding> findings, String rule) {
        return findings.stream().filter(f -> f.rule().equals(rule)).toList();
    }

    @Test
    void anApprovalInASubWorkflowChild_isANestedWaitPoint() {
        MapWorkflowResolver children = new MapWorkflowResolver().add("./child", workflow("child",
                block("shell", "prepare", Map.of("command", "set -euo pipefail\ntrue")),
                block("approval", "confirm", Map.of("question", "Go on?"))));
        WorkflowDefinition parent = workflow("parent",
                block("sub-workflow", "child", Map.of("workflow_id", "./child")));

        List<AnalyzerFinding> findings = of(analyze(parent, children), "nested-wait-point");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.WARNING);
            assertThat(f.blockIndex()).isZero();
            assertThat(f.detail()).contains("./child", "confirm", "never parks", "without a terminal");
        });
    }

    @Test
    void childApprovalWithAiActor_isNotReportedAsAskingAPerson() {
        MapWorkflowResolver children = new MapWorkflowResolver().add("./child", workflow("child",
                block("approval", "confirm", Map.of("question", "Go on?", "actor", "ai")),
                block("human-input", "form", Map.of("prompt", "Name?", "actor", "shadow"))));
        WorkflowDefinition parent = workflow("parent",
                block("sub-workflow", "child", Map.of("workflow_id", "./child")));

        assertThat(of(analyze(parent, children), "nested-wait-point")).as("ai never asks; shadow asks a person")
                .singleElement().satisfies(f -> assertThat(f.detail()).contains("form").doesNotContain("confirm"));
    }

    @Test
    void childApprovalWithAiActor_aloneIsNoFinding_andOneWithoutOrWithAPlaceholderActorStillIs() {
        MapWorkflowResolver aiOnly = new MapWorkflowResolver().add("./child", workflow("child",
                block("approval", "confirm", Map.of("question", "Go on?", "actor", "AI"))));
        MapWorkflowResolver human = new MapWorkflowResolver().add("./child", workflow("child",
                block("approval", "confirm", Map.of("question", "Go on?"))));
        MapWorkflowResolver placeholder = new MapWorkflowResolver().add("./child", workflow("child",
                block("approval", "confirm", Map.of("question", "Go on?", "actor", "{{ actor_po }}"))));
        WorkflowDefinition parent = workflow("parent",
                block("sub-workflow", "child", Map.of("workflow_id", "./child")));

        assertThat(of(analyze(parent, aiOnly), "nested-wait-point")).isEmpty();
        assertThat(of(analyze(parent, human), "nested-wait-point")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("confirm"));
        assertThat(of(analyze(parent, placeholder), "nested-wait-point")).hasSize(1);
    }

    @Test
    void aHumanInputTwoChildrenDeep_inAForeachChild_isANestedWaitPoint() {
        MapWorkflowResolver children = new MapWorkflowResolver()
                .add("./outer", workflow("outer", block("sub-workflow", "inner", Map.of("workflow_id", "./inner"))))
                .add("./inner", workflow("inner", block("human-input", "who", Map.of("prompt", "Who?"))));
        WorkflowDefinition parent = workflow("parent", block("foreach", "each",
                Map.of("items", List.of("a"), "workflow_id", "./outer")));

        assertThat(of(analyze(parent, children), "nested-wait-point")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("./outer", "who", "human-input"));
    }

    @Test
    void aChildThatAsksNobody_aChildThatCannotBeResolved_andADynamicReference_areNoFinding() {
        MapWorkflowResolver children = new MapWorkflowResolver()
                .add("./quiet", workflow("quiet", block("shell", "s", Map.of("command", "true"))));
        WorkflowDefinition parent = workflow("parent",
                block("sub-workflow", "quiet", Map.of("workflow_id", "./quiet")),
                block("sub-workflow", "missing", Map.of("workflow_id", "./missing")),
                block("sub-workflow", "dynamic", Map.of("workflow_id", "{{ which }}")));

        assertThat(of(analyze(parent, children), "nested-wait-point")).isEmpty();
        assertThat(children.requested()).doesNotContain("{{ which }}");
    }

    @Test
    void aChildThatRefersToItself_isCheckedOnce() {
        MapWorkflowResolver children = new MapWorkflowResolver().add("./loop", workflow("loop",
                block("sub-workflow", "again", Map.of("workflow_id", "./loop"))));

        assertThat(of(analyze(workflow("parent", block("sub-workflow", "loop", Map.of("workflow_id", "./loop"))),
                children), "nested-wait-point")).isEmpty();
    }

    @Test
    void theAgenticPlanApproval_isANestedWaitPoint_withoutAResolverToo() {
        WorkflowDefinition workflow = workflow("wf",
                block("agentic", "fix", Map.of("goal", "Fix the tests", "require_approval", true)),
                block("agentic", "free", Map.of("goal", "Fix the tests")));

        List<AnalyzerFinding> findings = of(new WorkflowAnalyzer().analyze(workflow), "nested-wait-point");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.blockIndex()).isZero();
            assertThat(f.detail()).contains("plan approval", "never parks");
        });
    }

    @Test
    void aQuestionWithARunVariable_cannotBeAnsweredAfterAPark() {
        WorkflowDefinition workflow = workflow("wf",
                block("approval", "ship", Map.of("question", "Ship the build of {{ run_timestamp }}?")),
                block("human-input", "who", Map.of("prompt", "Started {{started_at}} by {{ trigger_source }}: who?")),
                block("approval", "plain", Map.of("question", "Ship {{ version }}?")),
                block("approval", "ai", Map.of("question", "At {{ run_timestamp }}?", "actor", "shadow")));

        List<AnalyzerFinding> findings = of(new WorkflowAnalyzer().analyze(workflow), "question-changes-on-resume");

        assertThat(findings).extracting(AnalyzerFinding::blockIndex).containsExactly(0, 1);
        assertThat(findings.get(0).severity()).isEqualTo(AnalyzerFinding.Severity.WARNING);
        assertThat(findings.get(0).detail()).contains("run_timestamp", "resume", "the question changed");
        assertThat(findings.get(1).detail()).contains("started_at", "trigger_source");
    }
}
