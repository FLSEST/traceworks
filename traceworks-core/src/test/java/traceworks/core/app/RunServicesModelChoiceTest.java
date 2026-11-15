package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.engine.AllowedModels;
import traceworks.core.model.ModelPick;
import traceworks.core.model.RunStatus;
import traceworks.core.testing.FakeAiCli;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static traceworks.core.app.AppFixture.CLAUDE_TAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A run of a package with a model choice, through the services: the start check asks for the image and the
 * credential of the option that was picked and of no other, a pick outside the options and a model the operator does
 * not allow are refused before a run exists, {@code run.started} names the pick, and a resumed run keeps it.
 */
class RunServicesModelChoiceTest {

    /** One AI step that refers to the choice {@code judge}: Claude by default, Pi with {@code astra}. */
    private static final Path JUDGE = Path.of("/pkg/judge");
    /** An approval, then the same AI step. */
    private static final Path ASKED = Path.of("/pkg/asked");
    private static final String PI_TAG = "traceworks/pi:aaaaaaaaaaaa";
    private static final String OPENAI_KEY = "sk-proj-Openai_key-0123456789";

    private static final String CHOICES = """
            "requires": ["ai.model_choice"],
            "model_choices": {"judge": {"default": "fable", "options": {
              "fable": {"provider": "CLAUDE", "model": "claude-fable-5-2"},
              "astra": {"provider": "PI", "model": "openai/gpt-6-astra"}}}},""";

    private final AppFixture fx = new AppFixture();
    private final FakeAiCli pi = FakeAiCli.pi().credentialNamesFor("openai/gpt-6-astra", "OPENAI_API_KEY")
            .credentialNamesFor(null, "ANTHROPIC_API_KEY");

    RunServicesModelChoiceTest() {
        fx.settings = OperatorSettings.defaults();
        fx.clis.add(pi);
        fx.packages.put(JUDGE, """
                {"id": "wf-judge", "name": "Judge", %s "blocks": [
                  {"type": "simple", "name": "verdict", "config": {"command": "Judge it", "model_choice": "judge"}}
                ]}""".formatted(CHOICES));
        fx.packages.put(ASKED, """
                {"id": "wf-asked", "name": "Asked", %s "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"]}},
                  {"type": "simple", "name": "verdict", "config": {"command": "Judge it", "model_choice": "judge"}}
                ]}""".formatted(CHOICES));
    }

    /** The runtime has the Pi image and the egress image, and the environment an OpenAI key; nothing of Claude. */
    private void withPiOnly() {
        fx.withRuntime();
        fx.containers.present(PI_TAG, "sha256:" + "d".repeat(64));
        fx.containers.present(AppFixture.EGRESS_TAG, AppFixture.EGRESS_ID);
        fx.environment.put("OPENAI_API_KEY", OPENAI_KEY);
    }

    private static StartRequest request(Path pkg, Map<String, String> inputs) {
        return new StartRequest(pkg, inputs, AppFixture.WORKSPACE, false, false);
    }

    private JsonNode started(String runId) {
        return fx.ofType(runId, "run.started").getFirst().fields();
    }

    @Test
    void withoutAPick_theDefaultOptionRuns_andTheStartCheckAsksNothingOfTheOtherProvider() {
        fx.withClaude();   // no Pi image, no OpenAI key
        fx.claude.answers("clean");

        RunResult result = fx.services().start(request(JUDGE, Map.of()), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        ContainerRun ai = fx.containers.runs().getFirst();
        assertThat(ai.args()).containsExactly("fake-claude", "--kind", "ACTION", "--tools", "none", "--model",
                "claude-fable-5-2");
        assertThat(ai.environment()).doesNotContainKey("OPENAI_API_KEY");
        JsonNode judge = started(result.runId()).path("model_choices").path("judge");
        assertThat(judge.path("option").asText()).isEqualTo("fable");
        assertThat(judge.path("default").asBoolean()).isTrue();
        assertThat(judge.path("provider").asText()).isEqualTo("CLAUDE");
        assertThat(judge.path("model").asText()).isEqualTo("claude-fable-5-2");
        JsonNode request = fx.ofType(result.runId(), "ai.request").getFirst().fields();
        assertThat(request.path("provider").asText()).isEqualTo("CLAUDE");
        assertThat(request.path("model").asText()).isEqualTo("claude-fable-5-2");
        assertThat(fx.services().view(result.runId()).modelChoices())
                .containsExactly(new ModelPick("judge", "fable", "CLAUDE", "claude-fable-5-2", true));
    }

    @Test
    void thePickedOptionRuns_andTheStartCheckAsksNothingOfTheDefaultsProvider() {
        withPiOnly();   // no Claude image, no Anthropic key
        pi.answers("clean");

        RunResult result = fx.services().start(request(JUDGE, Map.of("judge", "astra")), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        ContainerRun ai = fx.containers.runs().getFirst();
        assertThat(ai.args()).containsExactly("fake-pi", "--kind", "ACTION", "--tools", "none", "--model",
                "openai/gpt-6-astra");
        assertThat(ai.environment()).containsEntry("OPENAI_API_KEY", OPENAI_KEY);
        JsonNode judge = started(result.runId()).path("model_choices").path("judge");
        assertThat(judge.path("option").asText()).isEqualTo("astra");
        assertThat(judge.path("default").asBoolean()).isFalse();
        assertThat(judge.path("provider").asText()).isEqualTo("PI");
        assertThat(fx.claude.calls()).isEmpty();
    }

    /** The other way round: what the picked option needs is asked for, and the run is refused without it. */
    @Test
    void thePickedOptionsImageAndCredential_areWhatTheStartCheckAsksFor() {
        fx.withClaude();

        assertThatThrownBy(() -> fx.services().start(request(JUDGE, Map.of("judge", "astra")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image " + PI_TAG + " is missing")
                .hasMessageContaining("AI provider PI has no credential")
                .hasMessageNotContaining(CLAUDE_TAG).hasMessageNotContaining("AI provider CLAUDE");
        assertThat(fx.store.runIds()).isEmpty();
    }

    @Test
    void aPickOutsideTheOptions_isRefusedBeforeARunExists_withTheOptionsListed() {
        fx.withClaude();

        assertThatThrownBy(() -> fx.services().start(request(JUDGE, Map.of("judge", "gpt")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessage("The run is refused: the model choice \"judge\" has no option \"gpt\": the input judge"
                        + " picks one of fable, astra (the default is fable)");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.containers.runs()).isEmpty();
        assertThat(fx.credentialReads).as("no value was read for a run that is refused for its pick").hasValue(0);
    }

    @Test
    void aWorkflowWithoutAChoice_recordsNoModelChoices() {
        fx.withRuntime();
        fx.containers.on(AppFixture.SHELL_ID, new traceworks.core.port.CommandRunner.CommandResult(0,
                "ok", "", 1, false));

        RunResult result = fx.services().start(AppFixture.request(AppFixture.BUILD), null, null);

        assertThat(started(result.runId()).has("model_choices")).isFalse();
        assertThat(fx.services().view(result.runId()).modelChoices()).isEmpty();
    }

    /** The operator narrows: a pick the package allows is refused when the home does not allow its model. */
    @Test
    void allowedModels_refuseAPickedOrAFixedModelBeforeARunExists_namingTheSetting() {
        fx.withClaude();
        withPiOnly();
        fx.claude.answers("clean");
        fx.settings = OperatorSettings.defaults().withAllowedModels(new AllowedModels(List.of(
                new AllowedModels.Entry("CLAUDE", "claude-fable-*"))));

        assertThatThrownBy(() -> fx.services().start(request(JUDGE, Map.of("judge", "astra")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessage("The run is refused: Block 1 \"verdict\": the model openai/gpt-6-astra of PI may not run"
                        + " in this home: allowed_models in traceworks.json allows CLAUDE claude-fable-*");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.services().start(request(JUDGE, Map.of()), null, null).status())
                .as("the default is allowed").isEqualTo(RunStatus.COMPLETED);

        fx.packages.put(Path.of("/pkg/fixed"), """
                {"id": "wf-fixed", "name": "Fixed", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Ask", "model": "claude-opus-5-5"}}
                ]}""");
        assertThatThrownBy(() -> fx.services().start(request(Path.of("/pkg/fixed"), Map.of()), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the model claude-opus-5-5 of CLAUDE may not run in this home: allowed_models");
    }

    /**
     * The declaration is part of the package, so a seal covers the default and the list of options: a pick within
     * the list changes no file and the seal holds; a package whose list or default changed is refused.
     */
    @Test
    void aSeal_coversTheDeclaration_andAPickWithinTheListDoesNotBreakIt() {
        fx.allowSigner();
        withPiOnly();
        pi.answers("clean");
        fx.services().seals().seal(JUDGE, null);

        RunResult picked = fx.services().start(request(JUDGE, Map.of("judge", "astra")), null, null);

        assertThat(picked.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(started(picked.runId()).path("seal").path("digest").asText()).isNotEmpty();
        assertThat(started(picked.runId()).path("model_choices").path("judge").path("option").asText())
                .isEqualTo("astra");

        fx.packages.put(JUDGE, """
                {"id": "wf-judge", "name": "Judge", %s "blocks": [
                  {"type": "simple", "name": "verdict", "config": {"command": "Judge it", "model_choice": "judge"}}
                ]}""".formatted(CHOICES.replace("\"default\": \"fable\"", "\"default\": \"astra\"")));
        assertThatThrownBy(() -> fx.services().start(request(JUDGE, Map.of()), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("changed since it was sealed");
    }

    /** A resumed run keeps the pick it started with: its start check and its step read the inputs of its start. */
    @Test
    void aResumedRun_keepsThePickItStartedWith() {
        withPiOnly();
        pi.answers("clean");
        RunServices services = fx.services();

        RunResult parked = services.start(request(ASKED, Map.of("judge", "astra")), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        services.answer(parked.runId(), new AnswerRequest("approval", 0, "Yes", null, null));
        RunResult resumed = services.resume(AppFixture.resumeOf(parked.runId()), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.containers.runs().getLast().args()).contains("fake-pi", "openai/gpt-6-astra");
        assertThat(fx.claude.calls()).isEmpty();
    }

    private String asked(String choices) {
        return """
                {"id": "wf-asked", "name": "Asked", %s "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"]}},
                  {"type": "simple", "name": "verdict", "config": {"command": "Judge it", "model_choice": "judge"}}
                ]}""".formatted(choices);
    }

    /**
     * A resume with a changed package goes on with the pick {@code run.started} recorded, not with the default of
     * the package as it is now (it runs the definition recorded at the start, and picks what the start recorded),
     * and {@code run.resumed} names the picks it goes on with.
     */
    @Test
    void aResumeWithAChangedPackage_keepsTheRecordedPick_andRunResumedNamesIt() {
        fx.withClaude();
        withPiOnly();
        fx.claude.answers("clean");
        RunServices services = fx.services();
        RunResult parked = services.start(request(ASKED, Map.of()), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        fx.packages.put(ASKED, asked(CHOICES.replace("\"default\": \"fable\"", "\"default\": \"astra\"")));
        services.answer(parked.runId(), new AnswerRequest("approval", 0, "Yes", null, null));

        RunResult resumed = services.resume(new ResumeRequest(parked.runId(), false, true), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.containers.runs().getLast().args()).contains("fake-claude", "claude-fable-5-2");
        assertThat(pi.calls()).isEmpty();
        JsonNode judge = fx.ofType(parked.runId(), "run.resumed").getFirst().fields().path("model_choices")
                .path("judge");
        assertThat(judge.path("option").asText()).isEqualTo("fable");
        assertThat(judge.path("default").asBoolean()).as("of the definition the run started with, which it runs")
                .isTrue();
        assertThat(judge.path("provider").asText()).isEqualTo("CLAUDE");
        assertThat(judge.path("model").asText()).isEqualTo("claude-fable-5-2");
    }

    /** A run without a choice records no picks in {@code run.resumed}: its bytes are those of earlier versions. */
    @Test
    void aResumeOfARunWithoutAChoice_recordsNoModelChoices() {
        RunServices services = new AppFixture().services();
        AppFixture plain = new AppFixture();
        RunResult parked = plain.services().start(AppFixture.request(AppFixture.DEPLOY), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        plain.services().answer(parked.runId(), new AnswerRequest("approval", 1, "Yes", null, null));
        plain.services().resume(AppFixture.resumeOf(parked.runId()), null, null);

        assertThat(plain.ofType(parked.runId(), "run.resumed").getFirst().fields().has("model_choices")).isFalse();
        assertThat(services).isNotNull();
    }

    /** The operator narrowed after the run parked: the resume is held to the setting of the day it resumes. */
    @Test
    void aResumeAfterTheSettingWasNarrowed_isRefused() {
        withPiOnly();
        RunResult parked = fx.services().start(request(ASKED, Map.of("judge", "astra")), null, null);
        fx.services().answer(parked.runId(), new AnswerRequest("approval", 0, "Yes", null, null));
        fx.settings = OperatorSettings.defaults().withAllowedModels(new AllowedModels(List.of(
                new AllowedModels.Entry("CLAUDE", null))));

        assertThatThrownBy(() -> fx.services().resume(AppFixture.resumeOf(parked.runId()), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("cannot resume: Block 2 \"verdict\": the model openai/gpt-6-astra of PI may"
                        + " not run in this home: allowed_models in traceworks.json allows CLAUDE (every model)");
        assertThat(pi.calls()).isEmpty();
    }

    /** A parent whose child declares the choice: the child is one package below the parent's. */
    private static final Path FAMILY = Path.of("/pkg/family");

    private void family() {
        fx.packages.put(FAMILY, """
                {"id": "wf-family", "name": "Family", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./judge"}}
                ]}""");
        fx.packages.put(FAMILY.resolve("judge"), """
                {"id": "wf-family-judge", "name": "Judge", %s "blocks": [
                  {"type": "simple", "name": "verdict", "config": {"command": "Judge it", "model_choice": "judge"}}
                ]}""".formatted(CHOICES));
    }

    /**
     * The start check of a run whose child workflow has the choice: it asks for the image and the credential of the
     * option the root run's input picks for the child, and of no other.
     */
    @Test
    void theStartCheck_ofAParent_asksForThePickedOptionOfItsChildOnly() {
        family();
        fx.withClaude();   // nothing of Pi
        fx.claude.answers("clean");

        RunResult standard = fx.services().start(request(FAMILY, Map.of()), null, null);

        assertThat(standard.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.containers.runs().getLast().args()).contains("fake-claude", "claude-fable-5-2");
        assertThat(started(standard.runId()).has("model_choices")).as("the parent declares no choice, and a child"
                + " has no run.started").isFalse();
        assertThatThrownBy(() -> fx.services().start(request(FAMILY, Map.of("judge", "astra")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image " + PI_TAG + " is missing")
                .hasMessageContaining("AI provider PI has no credential")
                .hasMessageNotContaining(CLAUDE_TAG).hasMessageNotContaining("AI provider CLAUDE");
        assertThatThrownBy(() -> fx.services().start(request(FAMILY, Map.of("judge", "gpt")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessage("The run is refused: the child workflow wf-family-judge: the model choice \"judge\" has"
                        + " no option \"gpt\": the input judge picks one of fable, astra (the default is fable)");
    }

    /** The operator's list holds for a child workflow's picked model, before the run exists. */
    @Test
    void allowedModels_refuseTheModelOfAChildWorkflow() {
        family();
        fx.withClaude();
        withPiOnly();
        fx.settings = OperatorSettings.defaults().withAllowedModels(new AllowedModels(List.of(
                new AllowedModels.Entry("CLAUDE", null))));

        assertThatThrownBy(() -> fx.services().start(request(FAMILY, Map.of("judge", "astra")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessage("The run is refused: the child workflow wf-family-judge: Block 1 \"verdict\": the model"
                        + " openai/gpt-6-astra of PI may not run in this home: allowed_models in traceworks.json"
                        + " allows CLAUDE (every model)");
        assertThat(fx.store.runIds()).isEmpty();
    }
}
