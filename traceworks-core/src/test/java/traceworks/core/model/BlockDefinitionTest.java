package traceworks.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockDefinitionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldStopOnFailure_defaultsToTrue() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test")))
                .build();

        assertThat(block.shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_stopValue_returnsTrue() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("on_failure", "stop")))
                .build();

        assertThat(block.shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_continueValue_returnsFalse() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("on_failure", "continue")))
                .build();

        assertThat(block.shouldStopOnFailure()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Continue", "CONTINUE", " continue ", "continue\n"})
    void shouldStopOnFailure_continueInAnyCaseOrPadded_returnsFalse(String value) {
        assertThat(blockWith(value).shouldStopOnFailure()).isFalse();
    }

    /** Fail-closed: anything that is not clearly "continue" stops, never continues silently. */
    @ParameterizedTest
    @ValueSource(strings = {"fail", "Stop", "STOP", "abort", "contiue", "", "   ", "continue please"})
    void shouldStopOnFailure_unknownValue_stopsLikeStop(String value) {
        assertThat(blockWith(value).shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_nonStringValue_stops() {
        assertThat(blockWith(Boolean.FALSE).shouldStopOnFailure()).isTrue();
        assertThat(blockWith(0).shouldStopOnFailure()).isTrue();
        assertThat(blockWith(Map.of("mode", "continue")).shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_nullConfig_stops() {
        BlockDefinition block = BlockDefinition.builder().type("simple").config(null).build();

        assertThat(block.shouldStopOnFailure()).isTrue();
    }

    @Test
    void builder_defaults_initializeConfig() {
        BlockDefinition block = BlockDefinition.builder()
                .type("test")
                .build();

        assertThat(block.getConfig()).isNotNull().isEmpty();
        assertThat(block.getName()).isNull();
        assertThat(block.unknownFields()).isEmpty();
    }

    @Test
    void fieldsAreTypeNameAndConfig() {
        assertThat(BlockDefinition.FIELDS).containsExactlyInAnyOrder("type", "name", "config");
    }

    /**
     * A setting next to {@code config} instead of in it is kept, so the analyzer can report it, but no run
     * reads it: the block still stops on failure.
     */
    @Test
    void fieldBesideConfig_isKeptAsUnknownField_andNotApplied() throws Exception {
        BlockDefinition block = mapper.readValue("""
                {"type":"shell","name":"check","config":{"command":"ls"},"on_failure":"continue"}""",
                BlockDefinition.class);

        assertThat(block.getType()).isEqualTo("shell");
        assertThat(block.getName()).isEqualTo("check");
        assertThat(block.getConfig()).containsExactly(Map.entry("command", "ls"));
        assertThat(block.unknownFields()).containsExactly(Map.entry("on_failure", "continue"));
        assertThat(block.shouldStopOnFailure()).isTrue();
    }

    @Test
    void unknownFields_areWrittenBackBesideTheKnownOnes() throws Exception {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(Map.of("command", "ls"))
                .build();
        block.putUnknownField("timeout_seconds", 60);

        JsonNode written = mapper.valueToTree(block);

        assertThat(written.get("type").asText()).isEqualTo("shell");
        assertThat(written.get("config").get("command").asText()).isEqualTo("ls");
        assertThat(written.get("timeout_seconds").asInt()).isEqualTo(60);
        assertThat(written.has("unknownFields")).isFalse();
    }

    /** Even a field named like the internal map is kept, never dropped silently. */
    @Test
    void fieldNamedUnknownFields_isKeptToo() throws Exception {
        BlockDefinition block = mapper.readValue("""
                {"type":"shell","config":{},"unknownFields":{"x":1}}""", BlockDefinition.class);

        assertThat(block.unknownFields()).containsExactly(Map.entry("unknownFields", Map.of("x", 1)));
        assertThat(mapper.valueToTree(block).<JsonNode>get("unknownFields").get("x").asInt()).isEqualTo(1);
    }

    @Test
    void wellFormedBlock_hasNoUnknownFields() throws Exception {
        BlockDefinition block = mapper.readValue("""
                {"type":"simple","name":"ok","config":{"prompt":"x"}}""", BlockDefinition.class);

        assertThat(block.unknownFields()).isEmpty();
    }

    /** Sorted, so a definition hash does not depend on the order of the unknown fields. */
    @Test
    void unknownFields_areSortedByName() {
        BlockDefinition block = BlockDefinition.builder().type("shell").build();
        block.putUnknownField("b", 2);
        block.putUnknownField("a", 1);

        assertThat(block.unknownFields().keySet()).containsExactly("a", "b");
    }

    @Test
    void unknownFields_areReadOnly() {
        BlockDefinition block = BlockDefinition.builder().type("shell").build();
        block.putUnknownField("a", 1);

        assertThatThrownBy(() -> block.unknownFields().put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void userMcpServers_onlyAnExplicitBooleanTrueOptsIn() {
        assertThat(BlockDefinition.builder().config(Map.of("user_mcp_servers", true)).build().userMcpServers())
                .isTrue();
        assertThat(BlockDefinition.userMcpServers(Map.of("user_mcp_servers", "true"))).isFalse();
        assertThat(BlockDefinition.userMcpServers(Map.of())).isFalse();
        assertThat(BlockDefinition.userMcpServers(null)).isFalse();
    }

    private static BlockDefinition blockWith(Object onFailure) {
        Map<String, Object> config = new HashMap<>();
        config.put("on_failure", onFailure);
        return BlockDefinition.builder().type("simple").config(config).build();
    }
}
