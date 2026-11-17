package traceworks.core.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgressTest {

    @Test
    void noneAndOpen_haveNoHosts_andAShellStepsNetworkIsOneOfThem() {
        assertThat(Egress.NONE.hosts()).isEmpty();
        assertThat(Egress.OPEN.hosts()).isEmpty();
        assertThat(Egress.of(true)).isEqualTo(Egress.OPEN);
        assertThat(Egress.of(false)).isEqualTo(Egress.NONE);
        assertThat(Egress.NONE.label()).isEqualTo("none");
        assertThat(Egress.OPEN.label()).isEqualTo("open");
        assertThat(Egress.NONE.any()).isFalse();
        assertThat(Egress.OPEN.any()).isTrue();
    }

    @Test
    void hosts_areLowerCase_sortedAndDistinct() {
        Egress egress = Egress.hosts(List.of("b.example:443", "API.Example:443", "b.example:443", "a.example:8443"));

        assertThat(egress.mode()).isEqualTo(Egress.Mode.HOSTS);
        assertThat(egress.hosts()).containsExactly("a.example:8443", "api.example:443", "b.example:443");
        assertThat(egress.label()).isEqualTo("hosts");
        assertThat(egress.any()).isTrue();
        assertThat(egress).isEqualTo(Egress.hosts(List.of("api.example:443", "a.example:8443", "b.example:443")));
    }

    @Test
    void hosts_needAtLeastOne() {
        assertThatThrownBy(() -> Egress.hosts(List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> new Egress(Egress.Mode.OPEN, List.of("a.example:443")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHost_isANameOrAnIpv4LiteralWithAPort_andNothingElse() {
        for (String valid : List.of("api.anthropic.com:443", "localhost:1", "10.0.0.7:65535", "a:80", "a-b.c:8080")) {
            assertThat(Egress.hostProblem(valid)).as(valid).isNull();
        }
        for (String invalid : List.of("", "api.example", "api.example:", "api.example:0", "api.example:65536",
                "api.example:443x", "*:443", "*", "*.example:443", "-a.example:443", "a.example-:443",
                "a_b.example:443", "https://a.example:443", "a.example:443/path", "[::1]:443", "a b.example:443",
                "a.example:443\n", "a".repeat(254) + ":443")) {
            assertThat(Egress.hostProblem(invalid)).as(invalid).isNotNull();
            if (!invalid.equals(Egress.ANY)) {
                assertThatThrownBy(() -> Egress.hosts(List.of(invalid))).as(invalid)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(Egress.hostProblem(null)).isNotNull();
        assertThat(Egress.hostProblem("API.example:443")).as("upper case is the same host").isNull();
    }

    /** A model server of the operator's: the one host, and whether the proxy forwards plain HTTP to it. */
    @Test
    void aServer_isTheOnlyHost_andAPlainTargetWhenItSpeaksHttp() {
        Egress http = Egress.server("LLM.internal:8080", true);
        Egress https = Egress.server("llm.example.org:443", false);

        assertThat(http.hosts()).containsExactly("llm.internal:8080");
        assertThat(http.plain()).containsExactly("llm.internal:8080");
        assertThat(http.restricted()).isTrue();
        assertThat(https.plain()).isEmpty();
        assertThat(Egress.hosts(List.of("api.example:443")).plain()).as("no host of a provider is a plain target")
                .isEmpty();
        assertThat(http).isNotEqualTo(Egress.hosts(List.of("llm.internal:8080")));
        assertThatThrownBy(() -> new Egress(Egress.Mode.HOSTS, List.of("a.example:443"), List.of("b.example:80")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not one of the egress hosts");
        assertThatThrownBy(() -> new Egress(Egress.Mode.HOSTS, List.of("*", "a.example:443"), List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Egress(Egress.Mode.OPEN, List.of(), List.of("a.example:80")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The star of a step's web_hosts is one of the hosts, next to the names; no operator setting can bring it. */
    @Test
    void theStar_isAHostAmongTheOthers_butNoHostAnOperatorCanName() {
        Egress egress = Egress.hosts(List.of("api.example:443", "*", "*"));

        assertThat(egress.hosts()).containsExactly("*", "api.example:443");
        assertThat(Egress.hostProblem("*")).isNotNull();
        assertThatThrownBy(() -> new AiNetwork(false, java.util.Map.of("claude", List.of("*"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
