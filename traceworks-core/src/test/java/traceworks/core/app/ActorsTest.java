package traceworks.core.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActorsTest {

    private static final OperatorSettings CONFIGURED = new OperatorSettings(false, "config-actor", List.<Path>of());

    @Test
    void env_winsOverConfig() {
        assertThat(Actors.resolve(CONFIGURED, Map.of("TRACEWORKS_ACTOR", "env-actor"), "alex", "box"))
                .isEqualTo("env-actor");
    }

    @Test
    void config_winsOverUserAtHost() {
        assertThat(Actors.resolve(CONFIGURED, Map.of(), "alex", "box")).isEqualTo("config-actor");
    }

    @Test
    void blankValues_fallThroughToUserAtHost() {
        assertThat(Actors.resolve(new OperatorSettings(false, "  ", List.of()), Map.of("TRACEWORKS_ACTOR", " "),
                "alex", "box")).isEqualTo("alex@box");
    }
}
