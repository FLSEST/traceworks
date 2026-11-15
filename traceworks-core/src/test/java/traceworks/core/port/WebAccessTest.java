package traceworks.core.port;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebAccessTest {

    private static Map<String, Object> config(Object... keysAndValues) {
        Map<String, Object> config = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            config.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return config;
    }

    @Test
    void withoutTheKey_orWithNone_aStepHasNoWeb() {
        assertThat(WebAccess.of(null)).isEqualTo(WebAccess.NONE);
        assertThat(WebAccess.of(config("command", "x"))).isEqualTo(WebAccess.NONE);
        assertThat(WebAccess.of(config("web", " None "))).isEqualTo(WebAccess.NONE);
        assertThat(WebAccess.NONE.enabled()).isFalse();
        assertThat(WebAccess.NONE.value()).isEqualTo("none");
        assertThat(WebAccess.NONE.hosts()).isEmpty();
    }

    @Test
    void search_runsAtTheProvider_andNeedsNoHosts() {
        WebAccess search = WebAccess.of(config("web", "search"));

        assertThat(search.mode()).isEqualTo(WebAccess.Mode.SEARCH);
        assertThat(search.enabled()).isTrue();
        assertThat(search.value()).isEqualTo("search");
        assertThat(search.hosts()).isEmpty();
    }

    @Test
    void fetch_namesItsHosts_aHostWithoutAPortIsPort443() {
        WebAccess fetch = WebAccess.of(config("web", "FETCH", "web_hosts",
                List.of("Docs.Example.org", "b.example:8443", "docs.example.org:443")));

        assertThat(fetch.mode()).isEqualTo(WebAccess.Mode.FETCH);
        assertThat(fetch.hosts()).containsExactly("b.example:8443", "docs.example.org:443");
        assertThat(fetch.anyHost()).isFalse();
    }

    @Test
    void fetch_withAStarAlone_isAnyHost() {
        WebAccess fetch = WebAccess.of(config("web", "fetch", "web_hosts", List.of("*")));

        assertThat(fetch.hosts()).containsExactly("*");
        assertThat(fetch.anyHost()).isTrue();
    }

    @Test
    void aValueThatIsNoMode_aPlaceholderIncluded_isRefused() {
        for (Object bad : new Object[] {"all", "", "{{ mode }}", true, 1, List.of("search")}) {
            assertThatThrownBy(() -> WebAccess.of(config("web", bad))).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("web: ").hasMessageContaining("is not one of none, search or fetch");
        }
    }

    @Test
    void fetchWithoutHosts_andHostsWithoutFetch_areRefused() {
        assertThatThrownBy(() -> WebAccess.of(config("web", "fetch"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("web: fetch needs web_hosts");
        assertThatThrownBy(() -> WebAccess.of(config("web", "fetch", "web_hosts", List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("web: fetch needs web_hosts");
        for (Map<String, Object> config : List.of(config("web_hosts", List.of("a.example")),
                config("web", "none", "web_hosts", List.of("a.example")),
                config("web", "search", "web_hosts", List.of("*")))) {
            assertThatThrownBy(() -> WebAccess.of(config)).as(config.toString())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("web_hosts is only read with web: fetch");
        }
    }

    @Test
    void hostsThatAreNoHosts_aStarMixedWithNames_andPlaceholders_areRefused() {
        for (Object bad : new Object[] {"a.example", List.of("*", "a.example"), List.of("*.example"),
                List.of("https://a.example"), List.of("a.example/path"), List.of("a.example:0"),
                List.of("{{ host }}"), List.of(5), List.of(""), List.of("[::1]:443"), List.of("a.example:")}) {
            assertThatThrownBy(() -> WebAccess.of(config("web", "fetch", "web_hosts", bad))).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("web_hosts");
        }
    }

    /** What a model reads from the web is untrusted: a step cannot say otherwise. */
    @Test
    void untrustedInputFalse_togetherWithWeb_isRefused_notOverridden() {
        assertThatThrownBy(() -> WebAccess.of(config("web", "search", "untrusted_input", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("untrusted_input: false").hasMessageContaining("web: search");
        assertThatThrownBy(() -> WebAccess.of(config("web", "search", "untrusted_input", "no")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WebAccess.of(config("web", "search", "untrusted_input", true)).enabled()).isTrue();
        assertThat(WebAccess.of(config("web", "search", "untrusted_input", "TRUE")).enabled()).isTrue();
        assertThat(WebAccess.of(config("web", "none", "untrusted_input", false))).isEqualTo(WebAccess.NONE);
    }

    @Test
    void aRecordWithHostsButNoFetch_cannotBeMade() {
        assertThatThrownBy(() -> new WebAccess(WebAccess.Mode.SEARCH, List.of("a.example:443")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebAccess(WebAccess.Mode.FETCH, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
