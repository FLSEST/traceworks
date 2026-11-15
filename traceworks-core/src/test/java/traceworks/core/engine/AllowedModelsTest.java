package traceworks.core.engine;

import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static traceworks.core.engine.ModelChoicesTest.block;
import static org.assertj.core.api.Assertions.assertThat;

/** The operator's {@code allowed_models}: which provider and model pairs may run. */
class AllowedModelsTest {

    private static WorkflowDefinition workflow(String provider, String model,
                                               traceworks.core.model.BlockDefinition... blocks) {
        return new WorkflowDefinition("wf-1", "Allowed", null, List.of(blocks), null, model, provider, null, Map.of(),
                Map.of(), null, null, null);
    }

    @Test
    void withoutTheSetting_everyModelMayRun() {
        assertThat(AllowedModels.ANY.restricted()).isFalse();
        assertThat(AllowedModels.ANY.allows("PI", "anything")).isTrue();
        assertThat(AllowedModels.ANY.refusals(workflow(null, null,
                block("simple", "ask", Map.of("command", "Ask"))))).isEmpty();
    }

    @Test
    void anEntryAllowsItsProvidersModels_aStarStandsForAnyText() {
        AllowedModels allowed = new AllowedModels(List.of(new AllowedModels.Entry("CLAUDE", "claude-fable-*"),
                new AllowedModels.Entry("pi", null)));

        assertThat(allowed.allows("claude", "claude-fable-5-2")).isTrue();
        assertThat(allowed.allows("CLAUDE", "claude-opus-5-5")).isFalse();
        assertThat(allowed.allows("CLAUDE", "xclaude-fable-5")).as("the pattern covers the whole name").isFalse();
        assertThat(allowed.allows("CLAUDE", null)).as("the default model is not known").isFalse();
        assertThat(allowed.allows("PI", "openai/gpt-6-astra")).isTrue();
        assertThat(allowed.allows("PI", null)).as("an entry without model allows the default too").isTrue();
        assertThat(new AllowedModels(List.of(new AllowedModels.Entry("CLAUDE", "a.b")))
                .allows("CLAUDE", "axb")).as("only the star is a wildcard").isFalse();
    }

    /** An entry without a star names one model: a longer name that starts with it is another model. */
    @Test
    void anEntryWithoutAStar_allowsExactlyThatModel() {
        AllowedModels allowed = new AllowedModels(List.of(new AllowedModels.Entry("CLAUDE", "claude-fable-5")));

        assertThat(allowed.allows("CLAUDE", "claude-fable-5")).isTrue();
        assertThat(allowed.allows("CLAUDE", "claude-fable-5-2")).isFalse();
        assertThat(allowed.allows("CLAUDE", "claude-fable-5x")).isFalse();
    }

    @Test
    void aStepWhoseModelIsNotAllowed_isAReasonThatNamesTheSetting() {
        AllowedModels allowed = new AllowedModels(List.of(new AllowedModels.Entry("CLAUDE", "claude-fable-5-2")));

        List<String> reasons = allowed.refusals(workflow("CLAUDE", "claude-fable-5-2",
                block("simple", "fine", Map.of("command", "Ask")),
                block("simple", "opus", Map.of("command", "Ask", "model", "claude-opus-5-5")),
                block("simple", "pi", Map.of("command", "Ask", "provider", "PI")),
                block("shell", "sh", Map.of("command", "true", "model", "ignored")),
                block("switch", "route", Map.of("expression", "a", "default", List.of(Map.of("type", "simple",
                        "name", "inner", "config", Map.of("command", "Ask", "model", "other")))))));

        assertThat(reasons).containsExactly(
                "Block 2 \"opus\": the model claude-opus-5-5 of CLAUDE may not run in this home: allowed_models in"
                        + " traceworks.json allows CLAUDE claude-fable-5-2",
                "Block 3 \"pi\": the default model of PI may not run in this home: allowed_models in"
                        + " traceworks.json allows CLAUDE claude-fable-5-2",
                "Block 5 \"route\" › default › Block 1 \"inner\": the model other of CLAUDE may not run in this"
                        + " home: allowed_models in traceworks.json allows CLAUDE claude-fable-5-2");
    }

    @Test
    void aStepWithoutAModel_needsAnEntryForEveryModelOfItsProvider() {
        AllowedModels allowed = new AllowedModels(List.of(new AllowedModels.Entry("CLAUDE", "claude-*")));

        assertThat(allowed.refusals(workflow(null, null, block("simple", "ask", Map.of("command", "Ask")))))
                .hasSize(1).first().asString().contains("the default model of CLAUDE, which Traceworks does not"
                        + " know (name the model in the workflow, or allow every model of the provider with an"
                        + " entry without model)");
        assertThat(new AllowedModels(List.of()).refusals(workflow(null, null,
                block("simple", "ask", Map.of("command", "Ask", "model", "m"))))).hasSize(1).first().asString()
                .endsWith("allows no model");
    }
}
