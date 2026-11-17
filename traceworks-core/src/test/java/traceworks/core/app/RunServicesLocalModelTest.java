package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import traceworks.core.describe.DescriptionText;
import traceworks.core.model.RunStatus;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.Egress;
import traceworks.core.port.LocalModels;
import traceworks.core.record.AiCallView;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeAiCli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;

/**
 * A model server of the operator's ({@code local_models}): a {@code PI} step whose model names a declared backend
 * runs without a credential of any provider, reaches that server and nothing else, and the record says so; a backend
 * nobody declared, a model the server does not list and a secret without a value refuse the run before it starts.
 */
class RunServicesLocalModelTest {

    private static final Path LOCAL = Path.of("/pkg/local");
    private static final String PI_TAG = "traceworks/pi:aaaaaaaaaaaa";
    private static final String URL = "http://llm.internal:8080/v1";
    private static final String SERVER_KEY = "local-server-key-0123456789";

    private final AppFixture fx = new AppFixture();
    private final FakeAiCli pi = FakeAiCli.pi().withoutWeb();

    RunServicesLocalModelTest() {
        fx.clis.add(pi);
        fx.withRuntime();
        fx.containers.present(PI_TAG, "sha256:" + "d".repeat(64));
        fx.containers.present(AppFixture.EGRESS_TAG, AppFixture.EGRESS_ID);
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "first", "", 1, false));
        declare(null);
        fx.packages.put(LOCAL, pkg("llm/qwen2.5-coder:7b"));
    }

    /** The operator's {@code local_models}: one server, {@code llm}, with or without a secret. */
    private void declare(String secret) {
        LocalModels local = new LocalModels(Map.of("llm", new LocalModels.Backend("llm", URL,
                LocalModels.OPENAI_COMPLETIONS, List.of("qwen2.5-coder:7b"), secret)));
        pi.localModels(local);
        fx.settings = OperatorSettings.defaults().withLocalModels(local);
    }

    private static String pkg(String model) {
        return """
                {"id": "wf-local", "name": "Local", "provider": "PI", "blocks": [
                  {"type": "shell", "name": "first", "config": {"command": "echo first"}},
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi", "model": "%s"}}
                ]}""".formatted(model);
    }

    private ContainerRun aiContainer() {
        return fx.containers.runs().stream().filter(run -> run.args().contains("fake-pi")).findFirst().orElseThrow();
    }

    @Test
    void aLocalStep_needsNoProviderCredential_reachesItsServerOnly_andTheRecordNamesTheServer() {
        pi.answers("hi from the local model");

        RunResult result = fx.services().start(request(LOCAL), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        ContainerRun ai = aiContainer();
        assertThat(ai.network()).as("the server alone, which speaks plain HTTP: no provider's host")
                .isEqualTo(Egress.server("llm.internal:8080", true));
        assertThat(ai.network().hosts()).containsExactly("llm.internal:8080");
        assertThat(ai.environment().keySet()).as("no credential of any provider")
                .doesNotContainAnyElementsOf(AiCli.credentialNamesOfAllProviders()).containsExactly("FAKE_CLI");
        assertThat(fx.credentialReads.get()).as("the values are read once, and none is a provider's").isEqualTo(1);

        JsonNode started = fx.ofType(result.runId(), "run.started").getFirst().fields();
        assertThat(started.path("local_models").toString()).isEqualTo("{\"llm\":{\"url\":\"" + URL
                + "\",\"api\":\"openai-completions\",\"models\":[\"qwen2.5-coder:7b\"],\"secret\":null}}");
        JsonNode asked = fx.ofType(result.runId(), "ai.request").getFirst().fields();
        assertThat(asked.path("provider").asText()).isEqualTo("PI");
        assertThat(asked.path("model").asText()).isEqualTo("llm/qwen2.5-coder:7b");
        assertThat(asked.path("local").toString()).isEqualTo("{\"backend\":\"llm\",\"url\":\"" + URL
                + "\",\"api\":\"openai-completions\"}");
        JsonNode command = fx.ofType(result.runId(), "step.command").stream().map(RecordedEvent::fields)
                .filter(fields -> fields.path("network").asText().equals("hosts")).findFirst().orElseThrow();
        assertThat(command.path("network_hosts")).extracting(JsonNode::asText).containsExactly("llm.internal:8080");

        AiCallView call = fx.services().view(result.runId()).steps().get(1).aiCalls().getFirst();
        assertThat(call.local()).isEqualTo(new AiCallView.Local("llm", URL, "openai-completions"));
        assertThat(call.localSummary()).isEqualTo("local backend llm at " + URL);
    }

    /** A run in a home without the setting, and a call to a provider, keep their bytes: no {@code local} anywhere. */
    @Test
    void aProviderCall_andAHomeWithoutTheSetting_recordNothingOfIt() {
        fx.settings = OperatorSettings.defaults();
        pi.localModels(LocalModels.NONE);
        fx.environment.put("ANTHROPIC_API_KEY", AppFixture.ANTHROPIC_KEY);
        fx.packages.put(LOCAL, pkg("sonnet"));
        pi.answers("hi");

        RunResult result = fx.services().start(request(LOCAL), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(result.runId(), "run.started").getFirst().fields().has("local_models")).isFalse();
        assertThat(fx.ofType(result.runId(), "ai.request").getFirst().fields().has("local")).isFalse();
        assertThat(fx.services().view(result.runId()).steps().get(1).aiCalls().getFirst().local()).isNull();
        assertThat(aiContainer().network()).isEqualTo(Egress.hosts(List.of("api.pi.example:443")));
    }

    @Test
    void theSecretOfAServer_reachesItsContainer_andTheRecordRedactsIt() {
        declare("LLM_KEY");
        fx.credentials.put("LLM_KEY", SERVER_KEY);
        fx.environment.put("ANTHROPIC_API_KEY", AppFixture.ANTHROPIC_KEY);
        pi.answers("the key is " + SERVER_KEY);

        RunResult result = fx.services().start(request(LOCAL), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(aiContainer().environment()).as("the server's secret, and no provider's credential though one"
                + " has a value").containsOnlyKeys("LLM_KEY", "FAKE_CLI").containsEntry("LLM_KEY", SERVER_KEY);
        RecordedEvent response = fx.ofType(result.runId(), "ai.response").getFirst();
        assertThat(fx.text(result.runId(), response, "output")).isEqualTo("the key is [redacted:LLM_KEY]");
        assertThat(fx.ofType(result.runId(), "run.started").getFirst().fields().path("local_models").path("llm")
                .path("secret").asText()).as("the name, never the value").isEqualTo("LLM_KEY");
    }

    @Test
    void aSecretWithoutAValue_refusesTheRunBeforeItStarts_namingTheSettingAndTheName() {
        declare("LLM_KEY");

        assertThatThrownBy(() -> fx.services().start(request(LOCAL), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The local backend llm needs the secret LLM_KEY (local_models.llm.secret in"
                        + " traceworks.json), which has no value")
                .hasMessageContaining("traceworks credentials set")
                .hasMessageContaining("under the name LLM_KEY");
        assertThat(fx.store.runIds()).as("no step ran").isEmpty();
    }

    /** A local step nested in a switch case is a local step all the same: its server's secret, no provider credential. */
    @Test
    void aLocalStepNestedInASwitch_needsTheServersSecret_andTheRecordRedactsIt() {
        declare("LLM_KEY");
        fx.credentials.put("LLM_KEY", SERVER_KEY);
        Path nested = Path.of("/pkg/nested-local");
        fx.packages.put(nested, """
                {"id": "wf-nested-local", "name": "Nested local", "provider": "PI", "blocks": [
                  {"type": "switch", "name": "pick", "config": {"expression": "a", "cases": [{"value": "a",
                   "blocks": [{"type": "simple", "name": "ask", "config": {"command": "Say hi",
                   "model": "llm/qwen2.5-coder:7b"}}]}]}}
                ]}""");
        pi.answers("the key is " + SERVER_KEY);

        RunResult result = fx.services().start(request(nested), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(aiContainer().environment()).containsOnlyKeys("LLM_KEY", "FAKE_CLI");
        RecordedEvent response = fx.ofType(result.runId(), "ai.response").getFirst();
        assertThat(fx.text(result.runId(), response, "output")).isEqualTo("the key is [redacted:LLM_KEY]");
    }

    /** A value the record could not redact (here: too short) is refused, not passed on to leak into the record. */
    @Test
    void aSecretOfAServerTheRecordCannotRedact_refusesTheRunBeforeItStarts() {
        declare("LLM_KEY");
        fx.credentials.put("LLM_KEY", "short");

        assertThatThrownBy(() -> fx.services().start(request(LOCAL), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the secret LLM_KEY of a local backend of AI provider")
                .hasMessageContaining("shorter than 8 characters");
        assertThat(fx.store.runIds()).as("no step ran").isEmpty();
    }

    @Test
    void aModelTheServerDoesNotList_refusesTheRunBeforeItStarts() {
        fx.packages.put(LOCAL, pkg("llm/llama3"));

        assertThatThrownBy(() -> fx.services().start(request(LOCAL), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The local backend llm does not serve the model llama3:"
                        + " local_models.llm.models in traceworks.json lists qwen2.5-coder:7b");
        assertThat(fx.store.runIds()).isEmpty();
    }

    @Test
    void aBackendNobodyDeclared_refusesTheRunBeforeItStarts_withTheClisMessage() {
        pi.unknownModel("ollama/llama3", "The Pi model ollama/llama3 names the backend ollama, and Traceworks"
                + " cannot tell which credential that backend takes");
        fx.environment.put("ANTHROPIC_API_KEY", AppFixture.ANTHROPIC_KEY);
        fx.packages.put(LOCAL, pkg("ollama/llama3"));

        assertThatThrownBy(() -> fx.services().start(request(LOCAL), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The Pi model ollama/llama3 names the backend ollama");
        assertThat(fx.store.runIds()).isEmpty();
    }

    /** A step that goes to the provider next to a local one still needs the provider's credential. */
    @Test
    void aProviderStepNextToALocalOne_stillNeedsTheProvidersCredential() {
        fx.packages.put(LOCAL, """
                {"id": "wf-local", "name": "Local", "provider": "PI", "blocks": [
                  {"type": "simple", "name": "local", "config": {"command": "Say hi", "model": "llm/qwen2.5-coder:7b"}},
                  {"type": "simple", "name": "remote", "config": {"command": "Say hi", "model": "sonnet"}}
                ]}""");

        assertThatThrownBy(() -> fx.services().start(request(LOCAL), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("AI provider PI has no credential");

        fx.environment.put("ANTHROPIC_API_KEY", AppFixture.ANTHROPIC_KEY);
        pi.answers("local", "remote");
        RunResult result = fx.services().start(request(LOCAL), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        List<ContainerRun> calls = fx.containers.runs().stream().filter(run -> run.args().contains("fake-pi")).toList();
        assertThat(calls.get(0).network()).isEqualTo(Egress.server("llm.internal:8080", true));
        assertThat(calls.get(0).environment()).doesNotContainKey("ANTHROPIC_API_KEY");
        assertThat(calls.get(1).network()).isEqualTo(Egress.hosts(List.of("api.pi.example:443")));
        assertThat(calls.get(1).environment()).containsEntry("ANTHROPIC_API_KEY", AppFixture.ANTHROPIC_KEY);
        List<RecordedEvent> asked = fx.ofType(result.runId(), "ai.request");
        assertThat(asked.get(0).fields().has("local")).isTrue();
        assertThat(asked.get(1).fields().has("local")).as("a reader tells the two apart").isFalse();
    }

    /** The open AI network and the operator's other hosts are for calls to a provider: a local call gets neither. */
    @Test
    void anOpenAiNetwork_andAiHosts_doNotWidenALocalStep() {
        pi.answers("hi", "hi");
        fx.settings = fx.settings.withAiNetwork(new AiNetwork(true, Map.of()));

        fx.services().start(request(LOCAL), null, null);

        assertThat(aiContainer().network()).isEqualTo(Egress.server("llm.internal:8080", true));

        fx.settings = fx.settings.withAiNetwork(new AiNetwork(false, Map.of("pi", List.of("gateway.example:443"))));
        fx.services().start(request(LOCAL), null, null);

        List<ContainerRun> calls = fx.containers.runs().stream().filter(run -> run.args().contains("fake-pi")).toList();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).network().hosts()).containsExactly("llm.internal:8080");
    }

    @Test
    void describe_namesTheServerOfALocalStep_andItsUrl() {
        List<String> lines = DescriptionText.lines(fx.services().describe(LOCAL));

        assertThat(String.join("\n", lines)).contains("model llm/qwen2.5-coder:7b",
                "model llm/qwen2.5-coder:7b runs on the local backend llm at " + URL
                        + " (local_models in traceworks.json)");
    }

    @Test
    void theNotesOfARun_nameEachServer() {
        assertThat(fx.services().aiImageNotes()).contains("local_models: a step with the model llm/<model> runs"
                + " against " + URL + " and reaches nothing else");
    }
}
