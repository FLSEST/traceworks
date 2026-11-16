package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.testing.FakeAiCli;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What {@code check} says about a workflow's model choices and the blocks that refer to them. */
class ModelChoiceRulesTest {

    private static final String REFUSAL = "The Pi model ollama/llama3 names the backend ollama, and so on";

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer().withAiClis(List.of(FakeAiCli.claude(),
            FakeAiCli.pi().unknownModel("ollama/llama3", REFUSAL)));

    private static Map<String, Object> choice(String defaultOption, String... nameProviderModel) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (int i = 0; i < nameProviderModel.length; i += 3) {
            options.put(nameProviderModel[i], Map.of("provider", nameProviderModel[i + 1], "model",
                    nameProviderModel[i + 2]));
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("default", defaultOption);
        choice.put("options", options);
        return choice;
    }

    private static Map<String, Object> judge() {
        return choice("fable", "fable", "CLAUDE", "claude-fable-5-2", "astra", "PI", "openai/gpt-6-astra");
    }

    private static BlockDefinition simple(String name, Map<String, Object> config) {
        return BlockDefinition.builder().type("simple").name(name).config(config).build();
    }

    private static WorkflowDefinition workflow(Map<String, Object> choices, List<String> requires,
                                               BlockDefinition... blocks) {
        return new WorkflowDefinition("id-1", "test", null, Arrays.asList(blocks), null, null, null, null, null,
                null, requires, "traceworks-workflow", 1, choices);
    }

    private List<AnalyzerFinding> findings(WorkflowDefinition workflow) {
        return analyzer.analyze(workflow);
    }

    @Test
    void aDeclaredChoiceAStepRefersTo_hasNoFinding_andItsVariablesAreKnown() {
        List<AnalyzerFinding> found = findings(workflow(Map.of("judge", judge()), List.of("ai.model_choice"),
                simple("verdict", Map.of("command", "Judge as {{ judge }}: {{ judge_provider }} {{ judge_model }}",
                        "model_choice", "judge"))));

        assertThat(found).isEmpty();
        assertThat(WorkflowAnalyzer.unreadInputs(workflow(Map.of("judge", judge()), List.of("ai.model_choice"),
                simple("verdict", Map.of("command", "Judge", "model_choice", "judge"))), List.of("judge", "jugde")))
                .as("the input that picks is read; a typo of it is not").hasSize(1).first().asString()
                .startsWith("input \"jugde\" is read by no block");
    }

    @Test
    void anOptionWithAnUnknownProvider_orAnUnknownPiBackend_isAnError() {
        List<AnalyzerFinding> found = findings(workflow(Map.of("judge", choice("a", "a", "CLAUDE", "m",
                "b", "GEMINI", "g", "c", "PI", "ollama/llama3")), List.of("ai.model_choice"),
                simple("verdict", Map.of("command", "Judge", "model_choice", "judge"))));

        assertThat(found).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::detail)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("unknown-provider", Severity.ERROR,
                                "model_choices.judge.options.b: provider \"GEMINI\" is not one of CLAUDE, PI —"
                                        + " Traceworks refuses every AI step of a run that picks this option"),
                        org.assertj.core.groups.Tuple.tuple("unknown-model-backend", Severity.ERROR,
                                "model_choices.judge.options.c: " + REFUSAL));
    }

    @Test
    void aReferenceToWhatIsNotDeclared_aBrokenDefault_andAMissingRequirement_areErrors() {
        List<AnalyzerFinding> found = findings(workflow(Map.of("judge", choice("gpt", "a", "CLAUDE", "m")),
                List.of(), simple("verdict", Map.of("command", "Judge", "model_choice", "jugde"))));

        assertThat(found).filteredOn(f -> f.severity() == Severity.ERROR)
                .extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex, AnalyzerFinding::detail)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("invalid-model-choice", -1, "model_choices.judge.default"
                                + " \"gpt\" is none of the options (a); Traceworks refuses the workflow"),
                        org.assertj.core.groups.Tuple.tuple("invalid-model-choice", 0, "model_choice \"jugde\" is not"
                                + " declared in model_choices (declared: judge); Traceworks refuses the workflow"),
                        org.assertj.core.groups.Tuple.tuple("missing-requirement", -1, "model_choices needs"
                                + " \"ai.model_choice\" in requires — add it to the workflow's requires; Traceworks"
                                + " refuses the workflow"));
    }

    @Test
    void aChoiceNoBlockRefersTo_isAWarning_andSoIsTheRequirementWithoutAChoice() {
        assertThat(findings(workflow(Map.of("judge", judge()), List.of("ai.model_choice"),
                simple("verdict", Map.of("command", "Judge")))))
                .extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("unused-model-choice", Severity.WARNING));
        assertThat(findings(workflow(null, List.of("ai.model_choice"), simple("verdict", Map.of("command", "Judge")))))
                .extracting(AnalyzerFinding::rule, AnalyzerFinding::detail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("unused-requirement", "requires names"
                        + " \"ai.model_choice\", but no step uses it (the workflow declares no model_choices) —"
                        + " remove it"));
    }

    /** check refuses what the admission refuses: a block that sets a variable of a choice, names that collide. */
    @Test
    void aBlockThatSetsAVariableOfAChoice_andChoicesThatShareAVariable_areErrors() {
        Map<String, Object> choices = new LinkedHashMap<>();
        choices.put("judge", judge());
        choices.put("judge_model", choice("a", "a", "CLAUDE", "m"));
        List<AnalyzerFinding> found = findings(workflow(choices, List.of("ai.model_choice"),
                simple("verdict", Map.of("command", "Judge", "model_choice", "judge")),
                simple("other", Map.of("command", "Judge", "model_choice", "judge_model")),
                BlockDefinition.builder().type("approval").name("ok").config(Map.of("question", "Go?",
                        "variable_name", "judge_provider")).build()));

        assertThat(found).filteredOn(f -> f.rule().equals("invalid-model-choice"))
                .extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex, AnalyzerFinding::detail)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Severity.ERROR, -1, "model_choices.judge_model: the run"
                                + " sets the variable \"judge_model\" for the model choice \"judge\" too — give one"
                                + " of the two choices another name; Traceworks refuses the workflow"),
                        org.assertj.core.groups.Tuple.tuple(Severity.ERROR, 2, "the block sets the variable"
                                + " \"judge_provider\", which the run sets for the model choice \"judge\" — give the"
                                + " block's variable another name; Traceworks refuses the workflow"));
    }

    /** Pi has no web tool: a step with web may not refer to a choice a run can pick a Pi option of. */
    @Test
    void webOnAStepWhoseChoiceHasAPiOption_isRefused() {
        List<AnalyzerFinding> found = findings(workflow(Map.of("judge", judge()),
                List.of("ai.model_choice", "ai.web"),
                simple("look", Map.of("command", "Look it up", "model_choice", "judge", "web", "search"))));

        assertThat(found).filteredOn(f -> f.rule().equals("unsupported-web")).hasSize(1);
    }

    /** A placeholder in provider stays what it was; one in model is refused and points to a declared choice. */
    @Test
    void aPlaceholderInProviderOrModel_isAnError() {
        List<AnalyzerFinding> found = findings(workflow(null, null,
                simple("p", Map.of("command", "Ask", "provider", "{{ p }}")),
                simple("m", Map.of("command", "Ask", "model", "claude-{{ m }}"))));

        assertThat(found).filteredOn(f -> f.severity() == Severity.ERROR)
                .extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("unknown-provider", 0),
                        org.assertj.core.groups.Tuple.tuple("placeholder-in-model", 1));
    }
}
