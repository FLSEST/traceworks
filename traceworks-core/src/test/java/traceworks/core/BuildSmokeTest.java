package traceworks.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSmokeTest {

    @Test
    void jacksonIsOnTheClasspath() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"a\":1}");

        assertThat(node.get("a").asInt()).isEqualTo(1);
    }
}
