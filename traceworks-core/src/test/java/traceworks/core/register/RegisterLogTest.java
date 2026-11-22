package traceworks.core.register;

import traceworks.core.port.Clock;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.testing.InMemoryRegisterStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The log of a register: lines chained like a run's, from a genesis of its own. */
class RegisterLogTest {

    private final InMemoryRegisterStore store = new InMemoryRegisterStore();
    private final Clock clock = () -> Instant.parse("2026-11-11T10:00:00Z");

    @Test
    void append_chainsLikeARun_withTheRegisterGenesis() {
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            String head0 = log.append("register.created", Map.of("workflow_id", "wf-1", "category", "c"));
            String head1 = log.append("report.rejected", Map.of("run_id", "r1", "reason", "x"));

            List<byte[]> lines = opened.events().readAll();
            assertThat(lines).hasSize(2);
            String first = new String(lines.get(0), StandardCharsets.UTF_8);
            assertThat(first).startsWith("{\"seq\":0,\"at\":\"2026-11-11T10:00:00.000Z\",\"type\":\"register.created\","
                    + "\"register\":\"arch\",\"prev\":\"" + EventChain.genesis(EventChain.REGISTER_GENESIS_PREFIX, "arch")
                    + "\",\"category\":\"c\",\"workflow_id\":\"wf-1\"}");
            assertThat(head0).isEqualTo(EventChain.sha256Hex(lines.get(0)));
            assertThat(new String(lines.get(1), StandardCharsets.UTF_8)).contains("\"seq\":1").contains("\"prev\":\"" + head0 + "\"");
            assertThat(log.head()).isEqualTo(head1);
            assertThat(log.read()).extracting(RegisterEvent::type).containsExactly("register.created", "report.rejected");
        }
    }

    @Test
    void aLogOpenedAgain_continuesAfterItsLastLine() {
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            new RegisterLog(opened, clock).append("register.created", Map.of());
        }
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);
            log.append("report.rejected", Map.of("run_id", "r1"));

            assertThat(log.read()).extracting(RegisterEvent::seq).containsExactly(0, 1);
            assertThat(log.read().get(1).prev()).isEqualTo(EventChain.sha256Hex(opened.events().readAll().get(0)));
        }
    }

    @Test
    void aFieldThatIsNoJsonValue_isRefused() {
        try (RegisterStore.Opened opened = store.openForWrite("arch", "shop")) {
            RegisterLog log = new RegisterLog(opened, clock);

            assertThatThrownBy(() -> log.append("x", Map.of("bad", new Object())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(opened.events().readAll()).isEmpty();
        }
    }

    @Test
    void parse_requiresTheChainFields_andKeepsTheRest() {
        RegisterEvent event = RegisterEvent.parse(("{\"seq\":3,\"at\":\"2026-11-11T10:00:00.000Z\",\"type\":\"t\","
                + "\"register\":\"k\",\"prev\":\"p\",\"a\":1}").getBytes(StandardCharsets.UTF_8));

        assertThat(event.seq()).isEqualTo(3);
        assertThat(event.register()).isEqualTo("k");
        assertThat(event.fields().path("a").asInt()).isEqualTo(1);
        assertThat(event.fields().has("seq")).isFalse();
        assertThatThrownBy(() -> RegisterEvent.parse("{\"seq\":1}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at");
    }
}
