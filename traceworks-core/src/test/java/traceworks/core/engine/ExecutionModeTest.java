package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionModeTest {

    private static Map<String, Object> mode(Object value) {
        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", value);
        return config;
    }

    /** The legacy alias {@code claude} is converted by the import, never accepted natively. */
    @Test
    void aiIsTheDefault_andTheLegacyClaudeAliasIsRefused() {
        assertThat(ExecutionMode.isShell(Map.of())).isFalse();
        assertThat(ExecutionMode.isShell(null)).isFalse();
        assertThat(ExecutionMode.isShell(mode(null))).isFalse();
        assertThat(ExecutionMode.isShell(mode("ai"))).isFalse();
        assertThat(ExecutionMode.isShell(mode("shell"))).isTrue();
        assertThatThrownBy(() -> ExecutionMode.isShell(mode("claude")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown execution_mode 'claude'");
        BlockDefinition legacy = BlockDefinition.builder().type("while").name("loop")
                .config(new HashMap<>(Map.of("execution_mode", "claude"))).build();
        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(List.of(legacy)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Block 'loop'").hasMessageContaining("'claude'");
    }

    /** Conditional, retry, while and foreach must write the value out; isSet tells whether they did. */
    @Test
    void isSet_onlyForAWrittenOutValue_andTheRequiredMessageNamesBothModes() {
        assertThat(ExecutionMode.isSet(mode("ai"))).isTrue();
        assertThat(ExecutionMode.isSet(mode("shell"))).isTrue();
        assertThat(ExecutionMode.isSet(mode("shel"))).as("an unknown value is set; isShell rejects it").isTrue();
        assertThat(ExecutionMode.isSet(mode(null))).isFalse();
        assertThat(ExecutionMode.isSet(Map.of())).isFalse();
        assertThat(ExecutionMode.isSet(null)).isFalse();
        assertThat(ExecutionMode.REQUIRED_MESSAGE).isEqualTo("execution_mode is required (shell or ai)");
    }

    @Test
    void unknownValuesNoLongerRunSilentlyAsAi() {
        assertThatThrownBy(() -> ExecutionMode.isShell(mode("shel")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown execution_mode 'shel'")
                .hasMessageContaining("\"ai\"").hasMessageContaining("\"shell\"");
        assertThatThrownBy(() -> ExecutionMode.isShell(mode("pi"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionMode.isShell(mode(true))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectUnknownChecksTopLevelAndBlocksNestedInSwitchCases() {
        BlockDefinition ok = BlockDefinition.builder().type("foreach").config(mode("ai")).build();
        assertThatNoException().isThrownBy(() -> ExecutionMode.rejectUnknown(List.of(ok)));
        assertThatNoException().isThrownBy(() -> ExecutionMode.rejectUnknown(null));

        BlockDefinition typo = BlockDefinition.builder().type("retry").name("Tests").config(mode("SHELL")).build();
        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(List.of(ok, typo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Block 'Tests': unknown execution_mode 'SHELL'");

        Map<String, Object> switchConfig = new HashMap<>(Map.of(
                "expression", "{{ mode }}",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "conditional", "config", Map.of("execution_mode", "shell"))))),
                "default", List.of(Map.of("type", "while", "name", "fallback",
                        "config", Map.of("execution_mode", "bash")))));
        BlockDefinition switchBlock = BlockDefinition.builder().type("switch").config(switchConfig).build();
        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(List.of(switchBlock)))
                .hasMessageStartingWith("Block 'fallback': unknown execution_mode 'bash'");
    }

    /** Same containers as the block-type check: a plain {@code blocks} list counts too. */
    @Test
    void rejectUnknownChecksBlocksNestedInAPlainBlocksList() {
        Map<String, Object> foreachConfig = new HashMap<>(Map.of(
                "items", List.of("a"),
                "blocks", List.of(Map.of("type", "shell", "name", "inner",
                        "config", Map.of("execution_mode", "shel")))));
        BlockDefinition foreach = BlockDefinition.builder().type("foreach").config(foreachConfig).build();

        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(List.of(foreach)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Block 'inner': unknown execution_mode 'shel'");
    }

    @Test
    void rejectUnknownRecursesIntoASwitchInsideASwitchCase() {
        Map<String, Object> inner = Map.of("type", "switch", "name", "inner-switch",
                "config", Map.of("cases", List.of(Map.of("value", "x", "blocks", List.of(
                        Map.of("type", "shell", "name", "deep", "config", Map.of("execution_mode", "bash")))))));
        Map<String, Object> outerConfig = new HashMap<>(Map.of(
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner)))));
        BlockDefinition outer = BlockDefinition.builder().type("switch").config(outerConfig).build();

        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(List.of(outer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Block 'deep': unknown execution_mode 'bash'");
    }

    @Test
    void nestedEntriesWithoutAConfigMapAreTolerated() {
        Map<String, Object> switchConfig = new HashMap<>(Map.of(
                "cases", List.of(Map.of("value", "a", "blocks", List.of(Map.of("type", "shell")))),
                "default", "not-a-list"));
        BlockDefinition switchBlock = BlockDefinition.builder().type("switch").config(switchConfig).build();

        assertThatNoException().isThrownBy(() -> ExecutionMode.rejectUnknown(List.of(switchBlock)));
    }
}
