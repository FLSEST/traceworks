package traceworks.adapters.ai;

import org.junit.jupiter.api.Test;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiCli.AiCall;
import traceworks.core.port.AiCli.AiInvocation;
import traceworks.core.port.LocalModels;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Pi call to a model server of the operator's ({@code local_models}): what Pi is started with, the provider
 * configuration Traceworks writes for it, and what the call gets and reaches. The configuration is compared with
 * what Pi's documentation says of {@code models.json} (Pi 1.5.2: {@code models.md}, "Configure a compatible
 * endpoint", and {@code models-configuration.md}); no Pi ran for this test.
 */
class PiCliLocalModelTest {

    private static final String URL = "http://llm.internal:8080/v1";
    private static final List<String> HARDENING = List.of("pi", "-p", "--no-session", "--no-context-files", "--mode",
            "json", "--offline", "--no-extensions", "--no-mcp", "--no-approve", "--no-skills", "--no-prompt-templates",
            "--no-themes");

    private static LocalModels local(String secret) {
        return new LocalModels(Map.of("llm", new LocalModels.Backend("llm", URL, LocalModels.OPENAI_COMPLETIONS,
                List.of("qwen2.5-coder:7b", "Qwen/Qwen2.5-Coder-7B"), secret)));
    }

    private final PiCli cli = new PiCli(local(null));
    private final PiCli withSecret = new PiCli(local("LLM_KEY"));

    private static AiCall call(String systemPrompt, String model) {
        return new AiCall(AiCall.Kind.ACTION, "Do it", systemPrompt, model, null, ToolPolicy.NONE, null, 60);
    }

    private static String modelsJson(AiInvocation invocation) {
        return new String(Base64.getDecoder().decode(invocation.environment().get(PiModelsLaunch.VARIABLE)),
                StandardCharsets.UTF_8);
    }

    @Test
    void aLocalCall_startsPiBehindTheScriptThatWritesItsConfiguration_withEveryOptionOfAProviderCall() {
        AiInvocation invocation = cli.invocation(call(null, "llm/qwen2.5-coder:7b"));

        assertThat(invocation.args()).containsExactly("sh", "-c", PiModelsLaunch.SCRIPT, "sh",
                "sh", "-c", PiCli.STREAM_FILTER, "sh",
                "pi", "-p", "--no-session", "--no-context-files", "--mode", "json", "--offline", "--no-extensions",
                "--no-mcp", "--no-approve", "--no-skills", "--no-prompt-templates", "--no-themes",
                "--provider", "traceworks-local", "--model", "qwen2.5-coder:7b", "--thinking", "medium",
                "--system-prompt", "", "--append-system-prompt", "", "--no-tools");
        assertThat(invocation.args()).as("nothing of Pi's hardening is gone").containsAll(HARDENING);
        assertThat(invocation.environment()).containsOnlyKeys(PiModelsLaunch.VARIABLE);
        assertThat(invocation.stdin()).isEqualTo("Do it");
        assertThat(String.join(" ", invocation.args())).as("the server is on no command line")
                .doesNotContain("llm.internal").doesNotContain("--extension").doesNotContain("builtin:");
    }

    /** Pi's documentation: a provider with {@code baseUrl}, {@code api}, {@code apiKey} and {@code models[].id}. */
    @Test
    void theConfiguration_isOneProviderOfPisModelsJson_withTheServerAndTheOneModelOfTheCall() {
        assertThat(modelsJson(cli.invocation(call(null, "llm/qwen2.5-coder:7b")))).isEqualTo(
                "{\"providers\":{\"traceworks-local\":{\"baseUrl\":\"http://llm.internal:8080/v1\","
                        + "\"api\":\"openai-completions\",\"apiKey\":\"none\","
                        + "\"models\":[{\"id\":\"qwen2.5-coder:7b\"}]}}}");
        assertThat(modelsJson(cli.invocation(call(null, "llm/Qwen/Qwen2.5-Coder-7B"))))
                .as("the other model the server lists is not in the file of this call")
                .contains("\"models\":[{\"id\":\"Qwen/Qwen2.5-Coder-7B\"}]").doesNotContain("qwen2.5-coder:7b");
    }

    /** A key is never in the file: Pi resolves {@code $NAME} from its environment when it sends a request. */
    @Test
    void theSecretOfAServer_isNamedInTheConfiguration_andItsValueIsNowhereInTheInvocation() {
        AiInvocation invocation = withSecret.invocation(call(null, "llm/qwen2.5-coder:7b"));

        assertThat(modelsJson(invocation)).as("Pi sends a key as Authorization: Bearer only with authHeader")
                .contains("\"apiKey\":\"$LLM_KEY\",\"authHeader\":true,");
        assertThat(modelsJson(cli.invocation(call(null, "llm/qwen2.5-coder:7b"))))
                .as("a server without a secret gets no header of a key").doesNotContain("authHeader");
        assertThat(invocation.environment()).as("the run passes the value, as every credential's; the CLI names it")
                .containsOnlyKeys(PiModelsLaunch.VARIABLE);
        assertThat(withSecret.credentialNames(call(null, "llm/qwen2.5-coder:7b"))).containsExactly("LLM_KEY");
    }

    @Test
    void theScript_writesTheFileWherePiReadsIt_dropsTheVariable_andStartsTheCli() {
        assertThat(PiModelsLaunch.SCRIPT).isEqualTo("(umask 077 && mkdir -p /tmp/traceworks-pi-agent && printf '%s'"
                + " \"$TRACEWORKS_PI_MODELS\" | base64 -d > /tmp/traceworks-pi-agent/models.json) && unset"
                + " TRACEWORKS_PI_MODELS && PI_CODING_AGENT_DIR=/tmp/traceworks-pi-agent && export"
                + " PI_CODING_AGENT_DIR && exec \"$@\"");
        assertThat(PiModelsLaunch.FILE).as("<agent-dir>/models.json, Pi's configuration.md")
                .isEqualTo(PiModelsLaunch.AGENT_DIR + "/models.json");
    }

    @Test
    void aSystemPrompt_travelsAsBefore_behindTheConfiguration() {
        AiInvocation invocation = cli.invocation(call("Be brief.", "llm/qwen2.5-coder:7b"));

        assertThat(invocation.args().subList(0, 12)).containsExactly("sh", "-c", PiModelsLaunch.SCRIPT, "sh",
                "sh", "-c", SystemPromptLaunch.SCRIPT, "sh", "sh", "-c", PiCli.STREAM_FILTER, "sh");
        assertThat(invocation.environment()).containsOnlyKeys(PiModelsLaunch.VARIABLE, SystemPromptLaunch.VARIABLE);
        assertThat(invocation.args()).contains("--append-system-prompt", SystemPromptLaunch.FILE);
    }

    @Test
    void aLocalCall_getsNoProviderCredential_andReachesItsServerOnly() {
        AiCall call = call(null, "llm/qwen2.5-coder:7b");

        assertThat(cli.local(call)).isPresent();
        assertThat(cli.credentialNames(call)).as("no credential of any provider").isEmpty();
        assertThat(cli.hosts(call)).containsExactly("llm.internal:8080");
        assertThat(cli.backend(call)).isEqualTo("llm");
        assertThat(cli.hosts(call)).doesNotContainAnyElementsOf(PiCli.BACKEND_HOSTS.values());
        assertThat(AiCli.credentialNamesOf("PI")).as("the provider's table is as it was").hasSize(5);
    }

    @Test
    void aModelTheServerDoesNotList_isRefused_alsoWithAThinkingSuffix() {
        for (String model : List.of("llm/llama3", "llm/qwen2.5-coder:7b:high")) {
            AiCall call = call(null, model);
            assertThatThrownBy(() -> cli.credentialNames(call)).as(model)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("The local backend llm does not serve the model ")
                    .hasMessageContaining("local_models.llm.models in traceworks.json lists qwen2.5-coder:7b,"
                            + " Qwen/Qwen2.5-Coder-7B");
            assertThatThrownBy(() -> cli.invocation(call)).as(model).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("The local backend llm does not serve the model ");
        }
    }

    @Test
    void aCallToAProvider_isUntouchedByTheSetting() {
        AiCall provider = call(null, "openai/gpt-5.5");

        assertThat(cli.local(provider)).isEmpty();
        assertThat(cli.local(call(null, null))).isEmpty();
        assertThat(cli.invocation(provider).args()).isEqualTo(new PiCli().invocation(provider).args());
        assertThat(cli.invocation(provider).environment()).isEmpty();
        assertThat(cli.credentialNames(provider)).containsExactly("OPENAI_API_KEY");
        assertThat(cli.hosts(provider)).containsExactly("api.openai.com:443");
    }

    @Test
    void aBackendNobodyDeclared_isRefused_andTheMessageNamesTheSettingAndWhatItDeclares() {
        assertThatThrownBy(() -> cli.credentialNames(call(null, "ollama/llama3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The Pi model ollama/llama3 names the backend ollama, which is no backend of"
                        + " a provider (anthropic, google, openai, openrouter) and no model server that local_models"
                        + " in traceworks.json declares (it declares llm);");
    }
}
