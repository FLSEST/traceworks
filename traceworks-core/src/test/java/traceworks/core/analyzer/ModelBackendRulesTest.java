package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.testing.FakeAiCli;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code unknown-model-backend}: a model, written as a literal, whose backend the provider's CLI cannot tell, so that
 * its call would be refused when the step runs. {@code check} says so before the run.
 */
class ModelBackendRulesTest {

    private static final String REFUSAL = "The Pi model ollama/llama3 names the backend ollama, and so on";

    private final FakeAiCli pi = FakeAiCli.pi().unknownModel("ollama/llama3", REFUSAL);
    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer().withAiClis(List.of(FakeAiCli.claude(), pi));

    private static BlockDefinition simple(String name, Map<String, Object> config) {
        return BlockDefinition.builder().type("simple").name(name).config(config).build();
    }

    private static WorkflowDefinition workflow(String provider, String model, BlockDefinition... blocks) {
        return new WorkflowDefinition("id-1", "test", null, Arrays.asList(blocks), null, model, provider, null,
                null, null, List.of("ai"), "traceworks-workflow", 1);
    }

    private List<AnalyzerFinding> findings(WorkflowAnalyzer with, WorkflowDefinition workflow) {
        return with.analyze(workflow).stream().filter(f -> f.rule().equals("unknown-model-backend")).toList();
    }

    @Test
    void aLiteralModelWhoseBackendTheCliCannotTell_isAnError_withTheClisMessage() {
        List<AnalyzerFinding> found = findings(analyzer, workflow(null, null,
                simple("ask", Map.of("command", "Say hi", "provider", "PI", "model", "ollama/llama3")),
                simple("fine", Map.of("command", "Say hi", "provider", "PI", "model", "openai/gpt-5.5"))));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().severity()).isEqualTo(Severity.ERROR);
        assertThat(found.getFirst().blockIndex()).isZero();
        assertThat(found.getFirst().detail()).isEqualTo(REFUSAL);
    }

    /** The workflow's provider and model are those of a step that names neither. */
    @Test
    void theWorkflowsModel_isTheModelOfAStepWithoutOne() {
        List<AnalyzerFinding> found = findings(analyzer, workflow("PI", "ollama/llama3",
                simple("ask", Map.of("command", "Say hi")),
                simple("own", Map.of("command", "Say hi", "model", "openai/gpt-5.5")),
                simple("claude", Map.of("command", "Say hi", "provider", "CLAUDE"))));

        assertThat(found).extracting(AnalyzerFinding::blockIndex).as("a step on another provider does not take the"
                + " workflow's model").containsExactly(0);
    }

    @Test
    void aNestedBlock_isNamedWithItsPath() {
        BlockDefinition choose = BlockDefinition.builder().type("switch").name("choose").config(Map.of(
                "expression", "{{ kind }}", "cases", List.of(Map.of("value", "a", "blocks", List.of(Map.of("type",
                        "simple", "name", "inner", "config", Map.of("command", "Say hi", "provider", "PI", "model",
                                "ollama/llama3"))))))).build();

        List<AnalyzerFinding> found = findings(analyzer, workflow(null, null, choose));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().detail()).endsWith(": " + REFUSAL).contains("inner");
    }

    /**
     * What this rule does not judge: a placeholder ({@code placeholder-in-model} refuses it), another provider, an
     * analyzer without CLIs.
     */
    @Test
    void aModelFromAPlaceholder_anotherProvider_andNoClis_reportNothing() {
        WorkflowDefinition placeholder = workflow(null, null,
                simple("ask", Map.of("command", "Say hi", "provider", "PI", "model", "{{ model }}")));
        assertThat(analyzer.analyze(placeholder)).filteredOn(f -> f.rule().equals("placeholder-in-model"))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("model \"{{ model }}\" holds a placeholder, which no run"
                            + " resolves").contains("modelChoices").endsWith("; Traceworks refuses the workflow");
                });
        assertThat(findings(analyzer, workflow(null, null,
                simple("ask", Map.of("command", "Say hi", "provider", "PI", "model", "{{ model }}")),
                simple("claude", Map.of("command", "Say hi", "model", "ollama/llama3")),
                BlockDefinition.builder().type("shell").name("sh").config(Map.of("command", "echo",
                        "model", "ollama/llama3", "provider", "PI")).build()))).isEmpty();
        assertThat(findings(new WorkflowAnalyzer(), workflow(null, null,
                simple("ask", Map.of("command", "Say hi", "provider", "PI", "model", "ollama/llama3"))))).isEmpty();
    }
}
