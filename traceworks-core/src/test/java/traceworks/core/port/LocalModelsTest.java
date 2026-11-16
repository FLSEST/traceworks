package traceworks.core.port;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The operator's model servers ({@code local_models}): what a backend may be, and which model names one. */
class LocalModelsTest {

    private static LocalModels.Backend backend(String id, String url) {
        return new LocalModels.Backend(id, url, LocalModels.OPENAI_COMPLETIONS, List.of("qwen2.5-coder:7b"), null);
    }

    @Test
    void aBackend_namesItsServerAsTheProxyListsIt_withThePortOfItsScheme() {
        assertThat(backend("llm", "http://llm.internal:8080/v1").host()).isEqualTo("llm.internal:8080");
        assertThat(backend("llm", "http://10.0.0.7/v1").host()).isEqualTo("10.0.0.7:80");
        assertThat(backend("llm", "https://llm.example.org").host()).isEqualTo("llm.example.org:443");
        assertThat(backend("llm", "http://llm.internal:8080/v1").plainHttp()).isTrue();
        assertThat(backend("llm", "https://llm.example.org/api/v1").plainHttp()).isFalse();
    }

    @Test
    void aModelNamesABackend_byTheIdBeforeItsFirstSlash_andOnlyADeclaredOne() {
        LocalModels local = new LocalModels(Map.of("llm", backend("llm", "http://llm.internal:8080/v1")));

        LocalModels.Use use = local.of("llm/qwen2.5-coder:7b").orElseThrow();
        assertThat(use.backend().id()).isEqualTo("llm");
        assertThat(use.model()).isEqualTo("qwen2.5-coder:7b");
        assertThat(use.listed()).isTrue();
        assertThat(use.problem()).isNull();
        assertThat(local.of("llm/org/model").orElseThrow().model()).as("the rest is the model, slashes included")
                .isEqualTo("org/model");
        assertThat(local.of("ollama/llama3")).as("a backend nobody declared").isEmpty();
        assertThat(local.of("openai/gpt-5")).isEmpty();
        assertThat(local.of("llm")).isEmpty();
        assertThat(local.of("llm/")).isEmpty();
        assertThat(local.of("/llm")).isEmpty();
        assertThat(local.of(null)).isEmpty();
        assertThat(LocalModels.NONE.of("llm/qwen2.5-coder:7b")).isEmpty();
    }

    @Test
    void aModelItsBackendDoesNotList_isAProblemThatNamesTheSettingAndTheList() {
        LocalModels local = new LocalModels(Map.of("llm", backend("llm", "http://llm.internal:8080/v1")));

        LocalModels.Use use = local.of("llm/llama3").orElseThrow();

        assertThat(use.listed()).isFalse();
        assertThat(use.problem()).isEqualTo("The local backend llm does not serve the model llama3:"
                + " local_models.llm.models in traceworks.json lists qwen2.5-coder:7b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai", "google", "openrouter"})
    void theBackendOfAProvider_isNoIdOfALocalBackend(String id) {
        assertThatThrownBy(() -> backend(id, "http://llm.internal/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"" + id + "\" is the backend of a provider")
                .hasMessageContaining("local-" + id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Llm", "1llm", "llm_a", "llm.a", "llm/a", "-llm", "a b",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void anIdOutsideTheGrammar_isRefused(String id) {
        assertThatThrownBy(() -> backend(id, "http://llm.internal/v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be [a-z][a-z0-9-]{0,31}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:11434/v1", "http://127.0.0.1:11434/v1", "http://127.1/v1",
            "https://app.localhost/v1", "http://0.0.0.0:8080"})
    void thisMachinesLoopback_isRefused_andTheMessageSaysWhatToWriteInstead(String url) {
        assertThatThrownBy(() -> backend("llm", url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the url of the local backend llm names this machine's loopback")
                .hasMessageContaining("which the container of an AI step cannot reach")
                .hasMessageContaining("Let the server listen on an address of this machine that containers reach")
                .hasMessageContaining("such as http://192.168.1.20");
    }

    @ParameterizedTest
    @ValueSource(strings = {"llm.internal:8080", "ftp://llm.internal/v1", "http://user:pw@llm.internal/v1",
            "http://llm.internal/v1?key=1", "http://llm.internal/v1#x", "http://[::1]:8080/v1", "http://LLM.internal/v1",
            "http://llm.internal:0/v1", "http://llm.internal:70000/v1", "http://llm.internal/v1/",
            "http://llm.internal/a b", "http://llm.internal/$HOME", "http://llm_internal/v1", "http://", " http://a/v1",
            "http://llm.internal//v1", "http://llm.internal/v1/%2e", "http://llm.internal/../v1",
            "http://llm.internal/v1/.."})
    void aUrlThatIsNoPlainBaseUrl_isRefused(String url) {
        assertThatThrownBy(() -> backend("llm", url)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the url of the local backend llm ");
    }

    @Test
    void theApi_isOneTraceworksCanConfigure_andNoOther() {
        assertThatThrownBy(() -> new LocalModels.Backend("llm", "http://llm.internal/v1", "anthropic-messages",
                List.of("m"), null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the api of the local backend llm must be one of openai-completions, not"
                        + " \"anthropic-messages\"");
        assertThatThrownBy(() -> new LocalModels.Backend("llm", "http://llm.internal/v1", null, List.of("m"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be one of");
    }

    @Test
    void theModels_areAClosedList_ofNamesNoCliReadsAsSomethingElse() {
        assertThatThrownBy(() -> new LocalModels.Backend("llm", "http://llm.internal/v1",
                LocalModels.OPENAI_COMPLETIONS, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lists no model");
        assertThatThrownBy(() -> new LocalModels.Backend("llm", "http://llm.internal/v1",
                LocalModels.OPENAI_COMPLETIONS, List.of("a", "a"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lists the model a twice");
        for (String model : List.of("", "-m", "@file", "a b", "a//b", "a/-b", "a/", "a/@b", "a*", "$(id)", "m\n",
                "a".repeat(129))) {
            assertThatThrownBy(() -> new LocalModels.Backend("llm", "http://llm.internal/v1",
                    LocalModels.OPENAI_COMPLETIONS, List.of(model), null))
                    .as(model).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("a model of the local backend llm ");
        }
        assertThatThrownBy(() -> new LocalModels.Backend("llm", "http://llm.internal/v1",
                LocalModels.OPENAI_COMPLETIONS, List.of("qwen:high"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ends in :high, which Pi reads as a thinking level");
        assertThat(new LocalModels.Backend("llm", "http://llm.internal/v1", LocalModels.OPENAI_COMPLETIONS,
                List.of("qwen2.5-coder:7b", "Llama-3.3_70b+q4", "Qwen/Qwen2.5-Coder-7B"), null).models())
                .containsExactly("qwen2.5-coder:7b", "Llama-3.3_70b+q4", "Qwen/Qwen2.5-Coder-7B");
    }

    @Test
    void theSecret_isASecretName_andNoNameAProviderOrTheCliReads() {
        assertThat(new LocalModels.Backend("llm", "http://llm.internal/v1", LocalModels.OPENAI_COMPLETIONS,
                List.of("m"), "LLM_KEY").secret()).isEqualTo("LLM_KEY");
        for (String name : List.of("llm_key", "1KEY", "", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
                "TRACEWORKS_SYSTEM_PROMPT", "PI_CODING_AGENT_DIR", "NODE_OPTIONS", "HTTPS_PROXY", "PATH",
                "LD_PRELOAD")) {
            assertThatThrownBy(() -> new LocalModels.Backend("llm", "http://llm.internal/v1",
                    LocalModels.OPENAI_COMPLETIONS, List.of("m"), name))
                    .as(name).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("the secret of the local backend llm ");
        }
    }

    @Test
    void theBackends_areKeptByTheirId_sorted() {
        LocalModels local = new LocalModels(Map.of("zeta", backend("zeta", "http://z.internal"),
                "alpha", backend("alpha", "http://a.internal")));

        assertThat(local.ids()).containsExactly("alpha", "zeta");
        assertThat(local.isEmpty()).isFalse();
        assertThat(LocalModels.NONE.isEmpty()).isTrue();
        assertThatThrownBy(() -> new LocalModels(Map.of("other", backend("llm", "http://a.internal"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
