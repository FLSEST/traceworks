package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ModelPick;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workflow's model choices: the declaration, the pick from the inputs of a run, and the definition the run runs.
 */
class ModelChoicesTest {

    static Map<String, Object> judge() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("fable", Map.of("provider", "CLAUDE", "model", "claude-fable-5-2"));
        options.put("opus", Map.of("provider", "CLAUDE", "model", "claude-opus-5-5"));
        options.put("astra", Map.of("provider", "PI", "model", "openai/gpt-6-astra"));
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("default", "fable");
        choice.put("options", options);
        return choice;
    }

    static WorkflowDefinition workflow(Map<String, Object> choices, Map<String, Object> defaultContext,
                                       BlockDefinition... blocks) {
        return new WorkflowDefinition("wf-1", "Choice", null, List.of(blocks), null, null, null, null, Map.of(),
                defaultContext, List.of("ai.model_choice"), null, null, choices);
    }

    static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    private static List<String> messages(WorkflowDefinition workflow) {
        return ModelChoices.problems(workflow).stream().map(RunRequirements.Problem::message).toList();
    }

    @Test
    void withoutAPick_theDefaultHolds_andTheStepNamesItsProviderAndModel() {
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge", "model_choice", "judge")));

        ModelChoices.Resolution resolved = ModelChoices.resolve(workflow, null);

        assertThat(resolved.problems()).isEmpty();
        assertThat(resolved.picks()).containsExactly(
                new ModelPick("judge", "fable", "CLAUDE", "claude-fable-5-2", true));
        assertThat(resolved.definition().blocks().getFirst().getConfig())
                .containsEntry("provider", "CLAUDE").containsEntry("model", "claude-fable-5-2")
                .containsEntry("command", "Judge").doesNotContainKey("model_choice");
        assertThat(workflow.blocks().getFirst().getConfig()).as("the definition given is not changed")
                .containsEntry("model_choice", "judge").doesNotContainKey("provider");
        assertThat(resolved.variables()).containsExactly(Map.entry("judge", "fable"),
                Map.entry("judge_provider", "CLAUDE"), Map.entry("judge_model", "claude-fable-5-2"));
    }

    @Test
    void theInputOfTheChoicesName_picksAnOption() {
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge", "model_choice", "judge")));

        ModelChoices.Resolution resolved = ModelChoices.resolve(workflow, Map.of("judge", "astra", "other", "x"));

        assertThat(resolved.picks()).containsExactly(
                new ModelPick("judge", "astra", "PI", "openai/gpt-6-astra", false));
        assertThat(resolved.definition().blocks().getFirst().getConfig())
                .containsEntry("provider", "PI").containsEntry("model", "openai/gpt-6-astra");
    }

    @Test
    void aPickThatIsNoneOfTheOptions_isAProblemThatListsThem() {
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge", "model_choice", "judge")));

        ModelChoices.Resolution resolved = ModelChoices.resolve(workflow, Map.of("judge", "gpt"));

        assertThat(resolved.problems()).containsExactly("the model choice \"judge\" has no option \"gpt\": the input"
                + " judge picks one of fable, opus, astra (the default is fable)");
        assertThat(resolved.picks()).isEmpty();
        assertThat(resolved.definition()).isSameAs(workflow);
        assertThat(ModelChoices.resolve(workflow, Map.of("judge", 3)).problems()).hasSize(1).first().asString()
                .contains("that is no text");
        assertThat(ModelChoices.resolve(workflow, Map.of("judge", "")).problems()).hasSize(1);
    }

    @Test
    void aBlockNestedInASwitchCase_getsThePickedOptionToo() {
        Map<String, Object> inner = Map.of("type", "simple", "name", "ask",
                "config", Map.of("command", "Ask", "model_choice", "judge"));
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("switch", "route", Map.of("expression", "a",
                        "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))),
                        "default", List.of(inner))));

        WorkflowDefinition runs = ModelChoices.resolve(workflow, Map.of("judge", "opus")).definition();

        List<String> models = new java.util.ArrayList<>();
        NestedBlocks.visit(runs.blocks().getFirst().getConfig(), nested -> models.add(nested.config().get("provider")
                + " " + nested.config().get("model") + " " + nested.config().containsKey("model_choice")));
        assertThat(models).containsExactly("CLAUDE claude-opus-5-5 false", "CLAUDE claude-opus-5-5 false");
    }

    @Test
    void aDefinitionWithoutAChoice_comesBackAsItIs() {
        WorkflowDefinition workflow = workflow(null, Map.of(),
                block("simple", "ask", Map.of("command", "Ask", "model", "m")));

        ModelChoices.Resolution resolved = ModelChoices.resolve(workflow, Map.of("judge", "astra"));

        assertThat(resolved.definition()).isSameAs(workflow);
        assertThat(resolved.picks()).isEmpty();
        assertThat(resolved.problems()).isEmpty();
        assertThat(ModelChoices.problems(workflow)).isEmpty();
    }

    @Test
    void aDeclarationThatIsNotUnderstoodCompletely_isAProblem() {
        Map<String, Object> noDefault = new LinkedHashMap<>(judge());
        noDefault.put("default", "gpt");
        Map<String, Object> extraKey = new LinkedHashMap<>(judge());
        extraKey.put("fallback", "opus");
        Map<String, Object> badOption = new LinkedHashMap<>();
        badOption.put("default", "a");
        badOption.put("options", Map.of("a", Map.of("provider", "CLAUDE")));
        Map<String, Object> codex = new LinkedHashMap<>();
        codex.put("default", "a");
        codex.put("options", Map.of("a", Map.of("provider", "codex", "model", "{{ m }}")));

        assertThat(messages(workflow(Map.of("judge", noDefault), Map.of())))
                .containsExactly("model_choices.judge.default \"gpt\" is none of the options (fable, opus, astra)");
        assertThat(messages(workflow(Map.of("judge", extraKey), Map.of())))
                .containsExactly("model_choices.judge has the unknown key \"fallback\" (known: default, options)");
        assertThat(messages(workflow(Map.of("judge", badOption), Map.of()))).hasSize(1).first().asString()
                .startsWith("model_choices.judge.options.a.model must be a text");
        assertThat(messages(workflow(Map.of("judge", codex), Map.of()))).hasSize(2)
                .anyMatch(m -> m.startsWith("model_choices.judge.options.a.provider codex: Codex is not supported"))
                .anyMatch(m -> m.startsWith("model_choices.judge.options.a.model holds a placeholder"));
        assertThat(messages(workflow(Map.of("judge", "fable"), Map.of())))
                .containsExactly("model_choices.judge must be an object with default and options");
        assertThat(messages(workflow(Map.of("model", judge()), Map.of()))).hasSize(1).first().asString()
                .startsWith("model_choices.model: a run sets a variable of this name itself");
        assertThat(messages(workflow(Map.of("the judge", judge()), Map.of()))).hasSize(1).first().asString()
                .contains("must be a plain name");
        assertThat(messages(workflow(Map.of("judge", judge()), Map.of("judge", "opus")))).hasSize(1).first()
                .asString().startsWith("model_choices.judge: default_context sets \"judge\" too");
    }

    @Test
    void aBlockThatRefersToWhatIsNotDeclared_orNamesItsOwnModelToo_isAProblem() {
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "typo", Map.of("command", "Ask", "model_choice", "jugde")),
                block("simple", "both", Map.of("command", "Ask", "model_choice", "judge", "model", "m")),
                block("simple", "number", Map.of("command", "Ask", "model_choice", 1)));

        List<RunRequirements.Problem> problems = ModelChoices.problems(workflow);

        assertThat(problems).extracting(RunRequirements.Problem::blockIndex).containsExactly(0, 1, 2);
        assertThat(problems).allMatch(p -> p.refuses() && p.rule().equals("invalid-model-choice"));
        assertThat(problems.get(0).message())
                .isEqualTo("model_choice \"jugde\" is not declared in model_choices (declared: judge)");
        assertThat(problems.get(1).message()).startsWith("model_choice \"judge\" next to model");
        assertThat(ModelChoices.resolve(workflow, null).definition()).as("nothing is replaced in a definition"
                + " with a problem: the admission refuses it").isSameAs(workflow);
        assertThat(messages(workflow(null, Map.of(),
                block("simple", "none", Map.of("command", "Ask", "model_choice", "judge")))))
                .containsExactly("model_choice \"judge\" is not declared in model_choices (the workflow declares"
                        + " none)");
    }

    /**
     * The variables of a pick are the run's word for who judged: no block may set one. Every block that sets a
     * variable by a name the package chooses is judged, and the names a block sets without being told.
     */
    @Test
    void aBlockThatSetsAVariableOfAChoice_isAProblem() {
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge", "model_choice", "judge")),
                block("foreach", "loop", Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "ls", "variable_name", "judge_model", "command", "true")),
                block("foreach", "index", Map.of("execution_mode", "shell", "items", List.of("a"),
                        "variable_name", "judge", "command", "true")),
                block("approval", "ok", Map.of("question", "Go?", "variable_name", "judge_provider")),
                block("human-input", "ask", Map.of("prompt", "Who?", "fields", List.of(
                        Map.of("variable_name", "judge"), Map.of("variable_name", "fine")))),
                block("switch", "route", Map.of("expression", "x", "default", List.of(Map.of("type", "human-input",
                        "name", "inner", "config", Map.of("prompt", "Who?", "variable_name", "judge_model"))))),
                block("foreach", "fine", Map.of("execution_mode", "shell", "items", List.of("a"),
                        "variable_name", "area", "command", "true")));

        List<RunRequirements.Problem> problems = ModelChoices.problems(workflow);

        assertThat(problems).extracting(RunRequirements.Problem::blockIndex).containsExactly(1, 2, 3, 4, 5);
        assertThat(problems).allMatch(p -> p.refuses() && p.rule().equals("invalid-model-choice"));
        assertThat(problems.get(0).message()).isEqualTo("the block sets the variable \"judge_model\", which the run"
                + " sets for the model choice \"judge\" — give the block's variable another name");
        assertThat(problems.get(1).message()).contains("\"judge\"");
        assertThat(problems.get(4).nested()).isNotNull();
    }

    /** A loop also sets {@code <variable_name>_index}, and a block without a name sets its default one. */
    @Test
    void theIndexOfALoop_andTheDefaultVariableOfABlock_countToo() {
        Map<String, Object> options = Map.of("a", Map.of("provider", "CLAUDE", "model", "m"));
        Map<String, Object> choice = Map.of("default", "a", "options", options);
        WorkflowDefinition index = workflow(Map.of("area_index", choice), Map.of(),
                block("foreach", "loop", Map.of("execution_mode", "shell", "items", List.of("a"),
                        "variable_name", "area", "command", "true")));
        WorkflowDefinition standard = workflow(Map.of("item", choice, "user_input", choice), Map.of(),
                block("foreach", "loop", Map.of("execution_mode", "shell", "items", List.of("a"), "command", "true")),
                block("human-input", "ask", Map.of("prompt", "Who?")));

        assertThat(messages(index)).singleElement().asString().startsWith("the block sets the variable"
                + " \"area_index\", which the run sets for the model choice \"area_index\"");
        assertThat(messages(standard)).hasSize(2).anyMatch(m -> m.contains("\"item\""))
                .anyMatch(m -> m.contains("\"user_input\""));
    }

    /** Two choices may not share a variable, and neither {@code default_context} nor an input may name one. */
    @Test
    void namesThatCollide_areProblems_andAnInputNamedLikeAVariableOfAChoiceIsRefused() {
        Map<String, Object> options = Map.of("a", Map.of("provider", "CLAUDE", "model", "m"));
        Map<String, Object> choice = Map.of("default", "a", "options", options);
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("x", choice);
        both.put("x_model", choice);

        assertThat(messages(workflow(both, Map.of()))).containsExactly("model_choices.x_model: the run sets the"
                + " variable \"x_model\" for the model choice \"x\" too — give one of the two choices another name");
        assertThat(messages(workflow(Map.of("judge", judge()), Map.of("judge_model", "m", "judge_provider", "p"))))
                .containsExactlyInAnyOrder(
                        "model_choices.judge: default_context sets \"judge_model\", which the run sets for the choice"
                                + " itself — remove the default_context entry",
                        "model_choices.judge: default_context sets \"judge_provider\", which the run sets for the"
                                + " choice itself — remove the default_context entry");
        WorkflowDefinition judging = workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge", "model_choice", "judge")));
        ModelChoices.Resolution resolved = ModelChoices.resolve(judging, Map.of("judge", "astra", "judge_model",
                "evil-model"));
        assertThat(resolved.problems()).containsExactly("the input judge_model names a variable the run sets for the"
                + " model choice \"judge\": the input judge picks an option, and nothing else sets its provider or"
                + " its model");
        assertThat(resolved.picks()).isEmpty();
    }

    @Test
    void everyPick_isTheDefinitionUnderEachOption() {
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge", "model_choice", "judge")));

        assertThat(ModelChoices.everyPick(workflow))
                .extracting(form -> form.blocks().getFirst().getConfig().get("model"))
                .containsExactly("claude-fable-5-2", "claude-opus-5-5", "openai/gpt-6-astra");
        assertThat(ModelChoices.variableNames(workflow)).containsExactly("judge", "judge_provider", "judge_model");
    }
}
