package traceworks.core.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolPolicyTest {

    @Test
    void parse_absent_isNone() {
        assertThat(ToolPolicy.parse(null)).isEqualTo(ToolPolicy.NONE);
    }

    @Test
    void parse_eachValue_ignoringCaseAndEdgeSpaces() {
        assertThat(ToolPolicy.parse("none")).isEqualTo(ToolPolicy.NONE);
        assertThat(ToolPolicy.parse("read")).isEqualTo(ToolPolicy.READ);
        assertThat(ToolPolicy.parse("Write")).isEqualTo(ToolPolicy.WRITE);
        assertThat(ToolPolicy.parse(" SHELL ")).isEqualTo(ToolPolicy.SHELL);
    }

    /** What the workflow file says: the lowercase name. */
    @Test
    void value_isTheLowercaseName() {
        assertThat(ToolPolicy.READ.value()).isEqualTo("read");
        assertThat(ToolPolicy.values()).extracting(ToolPolicy::value)
                .containsExactly("none", "read", "write", "shell");
    }

    /** The one parse path of a block's config, for the executors, the admission and the analyzer alike. */
    @Test
    void of_readsTheToolsKeyOfAConfig() {
        assertThat(ToolPolicy.KEY).isEqualTo("tools");
        assertThat(ToolPolicy.of(Map.of("tools", "shell"))).isEqualTo(ToolPolicy.SHELL);
        assertThat(ToolPolicy.of(Map.of("command", "x"))).isEqualTo(ToolPolicy.NONE);
        assertThat(ToolPolicy.of(null)).isEqualTo(ToolPolicy.NONE);
        assertThatThrownBy(() -> ToolPolicy.of(Map.of("tools", "all"))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A value outside the four ({@code unrestricted}, which only Codex took, included), a placeholder or another type
     * is refused, naming the values: never a guess.
     */
    @Test
    void parse_anythingElse_isRefusedNamingTheValues() {
        for (Object value : List.of("all", "unrestricted", "", "{{ tools }}", true, 3, List.of("Read"))) {
            assertThatThrownBy(() -> ToolPolicy.parse(value)).as("%s", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("none, read, write or shell");
        }
    }
}
