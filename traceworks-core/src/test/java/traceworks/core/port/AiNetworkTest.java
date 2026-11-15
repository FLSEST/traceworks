package traceworks.core.port;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiNetworkTest {

    @Test
    void byDefault_anAiCallReachesItsProvidersHostsOnly() {
        AiNetwork network = AiNetwork.RESTRICTED;

        assertThat(network.mode()).isEqualTo("restricted");
        assertThat(network.hosts()).isEmpty();
        assertThat(network.egress("CLAUDE", List.of("api.anthropic.com:443")))
                .contains(Egress.hosts(List.of("api.anthropic.com:443")));
    }

    @Test
    void theOperatorsHostsOfACli_joinItsProvidersHosts_andNoOtherClis() {
        AiNetwork network = new AiNetwork(false, Map.of("claude", List.of("Proxy.Corp.example:8443", "b.example:443",
                "b.example:443"), "pi", List.of("llm.corp.example:443")));

        assertThat(network.hosts()).containsExactly(
                Map.entry("claude", List.of("b.example:443", "proxy.corp.example:8443")),
                Map.entry("pi", List.of("llm.corp.example:443")));
        assertThat(network.egress("CLAUDE", List.of("api.anthropic.com:443"))).contains(Egress.hosts(
                List.of("api.anthropic.com:443", "b.example:443", "proxy.corp.example:8443")));
        assertThat(network.egress("pi", List.of())).contains(Egress.hosts(List.of("llm.corp.example:443")));
    }

    /** A call with no host at all has no egress: its caller refuses it, since listed hosts need at least one. */
    @Test
    void aCallWithoutAnyHost_hasNoEgress() {
        assertThat(AiNetwork.RESTRICTED.egress("PI", List.of())).isEqualTo(Optional.empty());
    }

    @Test
    void open_isTheRuntimesDefaultNetwork_whateverTheHosts() {
        AiNetwork open = new AiNetwork(true, Map.of("claude", List.of("b.example:443")));

        assertThat(open.mode()).isEqualTo("open");
        assertThat(open.egress("CLAUDE", List.of("api.anthropic.com:443"))).contains(Egress.OPEN);
        assertThat(open.egress("PI", List.of())).contains(Egress.OPEN);
    }

    @Test
    void aCliThatDoesNotExist_andAHostThatIsNone_areRefused() {
        assertThatThrownBy(() -> new AiNetwork(false, Map.of("codex", List.of("a.example:443"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codex")
                .hasMessageContaining("claude, pi");
        assertThatThrownBy(() -> new AiNetwork(false, Map.of("claude", List.of("*.example:443"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("*.example:443")
                .hasMessageContaining("host:port");
        assertThat(AiNetwork.modeProblem("restricted")).isNull();
        assertThat(AiNetwork.modeProblem("open")).isNull();
        assertThat(AiNetwork.modeProblem("OPEN")).isNotNull();
        assertThat(AiNetwork.modeProblem(null)).isNotNull();
    }
}
