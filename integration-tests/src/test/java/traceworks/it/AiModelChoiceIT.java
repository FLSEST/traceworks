package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model choice of a workflow, against the fake CLIs (package {@code ai-model-choice}, docs/cli.md "Model
 * choices"): the run's input picks the option, the step's CLI is started with the picked provider's image and the
 * picked model, the start check asks for the credential of the picked option only, a pick outside the options and a
 * model the operator does not allow start no run, and the record, {@code show} and {@code diff} name the pick. The
 * fake CLIs answer with the arguments they got, so the answer shows the model the CLI was started with.
 */
class AiModelChoiceIT extends AiIntegrationTest {

    private static final String OPENAI_FAKE = "sk-it-fake-openai-9876543210";

    /** About which model a call runs with, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    @Test
    void withoutAPick_theDefaultOptionAnswers_andNoCredentialOfTheOtherProviderIsNeeded() {
        // The sandbox holds ANTHROPIC_API_KEY only: the Pi option's backend, openai, has no credential here
        Result run = traceworks.run("ai-model-choice");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("verdict")).startsWith("fake-claude answered").contains("claude-fable-5-2");
        assertThat(folder.output("who").strip()).isEqualTo("fable CLAUDE claude-fable-5-2");
        JsonNode request = folder.ofStep("ai.request", folder.stepIndex("verdict")).getFirst();
        assertThat(request.path("provider").asText()).isEqualTo("CLAUDE");
        assertThat(request.path("model").asText()).isEqualTo("claude-fable-5-2");
        JsonNode judge = folder.only("run.started").path("model_choices").path("judge");
        assertThat(judge.path("option").asText()).isEqualTo("fable");
        assertThat(judge.path("default").asBoolean()).isTrue();
        assertThat(judge.path("provider").asText()).isEqualTo("CLAUDE");
        assertThat(judge.path("model").asText()).isEqualTo("claude-fable-5-2");
        Result show = traceworks.exec("show", folder.id());
        assertThat(show.out()).contains("Model     judge = fable (the default): CLAUDE claude-fable-5-2");
        assertThat(folder.html()).contains("model choice judge", "fable (the default): CLAUDE claude-fable-5-2");
    }

    @Test
    void thePickedOptionAnswers_withItsProvidersCliAndItsModel_andDiffNamesTheTwoPicks() {
        Result refused = traceworks.run("ai-model-choice", "--input", "judge=astra");
        assertThat(refused.code()).as(refused.describe()).isEqualTo(6);
        assertThat(refused.out() + refused.err()).contains("AI provider PI has no credential for the model openai/gpt-6-astra",
                "OPENAI_API_KEY");
        assertThat(traceworks.folders()).as("the start check refused before a run existed").isEmpty();

        traceworks.credentials(Map.of("OPENAI_API_KEY", OPENAI_FAKE));   // and no Anthropic key: Claude is not asked
        Result run = traceworks.run("ai-model-choice", "--input", "judge=astra");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder astra = traceworks.folder(run);
        assertThat(astra.output("verdict")).startsWith("fake-pi answered").contains("gpt-6-astra");
        assertThat(astra.output("who").strip()).isEqualTo("astra PI openai/gpt-6-astra");
        JsonNode request = astra.ofStep("ai.request", astra.stepIndex("verdict")).getFirst();
        assertThat(request.path("provider").asText()).isEqualTo("PI");
        assertThat(request.path("model").asText()).isEqualTo("openai/gpt-6-astra");
        JsonNode judge = astra.only("run.started").path("model_choices").path("judge");
        assertThat(judge.path("option").asText()).isEqualTo("astra");
        assertThat(judge.path("default").asBoolean()).isFalse();
        for (JsonNode command : astra.events("step.command")) {
            assertThat(command.path("image").asText("")).as("no command of the run ran in Claude's image")
                    .doesNotContain("claude");
        }

        traceworks.credentials(Map.of("ANTHROPIC_API_KEY", FAKE_KEY));
        Result standard = traceworks.run("ai-model-choice");
        assertThat(standard.code()).as(standard.describe()).isZero();
        Result diff = traceworks.exec("diff", astra.id(), traceworks.folder(standard).id());
        assertThat(diff.code()).as(diff.describe()).isEqualTo(11);
        assertThat(diff.out()).contains("the inputs differ", "the model choice judge differs: astra (picked): PI"
                + " openai/gpt-6-astra in run A, fable (the default): CLAUDE claude-fable-5-2 in run B");
    }

    @Test
    void aPickOutsideTheOptions_andAModelTheOperatorDoesNotAllow_startNoRun() {
        Result unknown = traceworks.run("ai-model-choice", "--input", "judge=gpt");

        assertThat(unknown.code()).as(unknown.describe()).isEqualTo(6);
        assertThat(unknown.out() + unknown.err()).contains("the model choice \"judge\" has no option \"gpt\": the input judge picks"
                + " one of fable, astra (the default is fable)");

        traceworks.configure(Map.of("allowed_models", List.of(Map.of("provider", "CLAUDE", "model", "claude-*"))));
        traceworks.credentials(Map.of("ANTHROPIC_API_KEY", FAKE_KEY, "OPENAI_API_KEY", OPENAI_FAKE));
        Result notAllowed = traceworks.run("ai-model-choice", "--input", "judge=astra");

        assertThat(notAllowed.code()).as(notAllowed.describe()).isEqualTo(6);
        assertThat(notAllowed.out() + notAllowed.err()).contains("the model openai/gpt-6-astra of PI may not run in this home:"
                + " allowed_models in traceworks.json allows CLAUDE claude-*");
        assertThat(traceworks.folders()).as("neither refusal left a run").isEmpty();
        Result allowed = traceworks.run("ai-model-choice");
        assertThat(allowed.code()).as(allowed.describe()).isZero();
    }

    @Test
    void describe_listsTheChoice_andCheckHasNothingToSay() {
        Result described = traceworks.exec("describe", traceworks.packageDir("ai-model-choice").toString());
        assertThat(described.code()).as(described.describe()).isZero();
        assertThat(described.out()).contains("Model choices", "  judge  pick with --input judge=<option>; without it:"
                + " fable", "    astra  PI openai/gpt-6-astra", "    used by verdict");

        Result check = traceworks.exec("check", traceworks.packageDir("ai-model-choice").toString());
        assertThat(check.code()).as(check.describe()).isZero();
        // As AiCheckIT allows for every AI package: the last block does not read the output of the one before it
        assertThat(check.out()).doesNotContainPattern("(?m)^ERROR ");
        assertThat(check.out()).doesNotContainPattern("(?m)^WARNING +(?!unwired-output)");
    }
}
