package traceworks.core.analyzer;

import traceworks.core.engine.RunRequirements;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.testing.MapWorkflowResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChildProviderRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, id, null, List.of(blocks), null, null, null, null, null, null, List.of(),
                null, null);
    }

    private static BlockDefinition ask(String provider) {
        return block("simple", "ask", Map.of("prompt", "Say hi", "provider", provider));
    }

    private static List<AnalyzerFinding> unsupported(WorkflowDefinition parent, MapWorkflowResolver children) {
        return new WorkflowAnalyzer().analyze(parent, children).stream()
                .filter(f -> f.rule().equals(RunRequirements.UNSUPPORTED_PROVIDER)).toList();
    }

    @Test
    void aChildThatNamesCodex_isAnError_atTheBlockThatRunsIt() {
        MapWorkflowResolver children = new MapWorkflowResolver()
                .add("./codex", workflow("wf-codex", ask("codex")))
                .add("./claude", workflow("wf-claude", ask("CLAUDE")));
        WorkflowDefinition parent = workflow("parent",
                block("shell", "prepare", Map.of("command", "true")),
                block("sub-workflow", "other", Map.of("workflow_id", "./codex")),
                block("foreach", "fine", Map.of("workflow_id", "./claude", "items", "a")));

        assertThat(unsupported(parent, children)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.detail()).contains("the child workflow wf-codex (./codex)", "Block 1 \"ask\"",
                    RunRequirements.CODEX_REFUSAL, "refuses the run at its start");
        });
    }

    @Test
    void aGrandchildThatNamesCodex_isReportedAtTheBlockThatLeadsToIt() {
        MapWorkflowResolver children = new MapWorkflowResolver()
                .add("./same", workflow("wf-same", block("sub-workflow", "deeper", Map.of("workflow_id", "./deep"))))
                .add("./deep", workflow("wf-deep", ask("CODEX")));
        WorkflowDefinition parent = workflow("parent", block("sub-workflow", "same", Map.of("workflow_id", "./same")));

        assertThat(unsupported(parent, children)).singleElement().satisfies(f -> {
            assertThat(f.blockIndex()).isZero();
            assertThat(f.detail()).contains("the child workflow wf-deep:", RunRequirements.CODEX_REFUSAL);
        });
    }

    @Test
    void withoutAResolver_theChildrenAreNotLookedInto() {
        WorkflowDefinition parent = workflow("parent", block("sub-workflow", "other", Map.of("workflow_id", "./x")));

        assertThat(new WorkflowAnalyzer().analyze(parent, null))
                .noneMatch(f -> f.rule().equals(RunRequirements.UNSUPPORTED_PROVIDER));
    }
}
