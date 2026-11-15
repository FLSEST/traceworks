package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void writeThenParse_keepsTheValue_andWritesPrintableAsciiOnly() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "line\nbreak ‮ \"quoted\" \\");
        value.put("list", List.of(1L, true, "x"));
        value.put("none", null);

        String json = Json.write(value);

        assertThat(json).isEqualTo("{\"text\":\"line\\nbreak \\u202e \\\"quoted\\\" \\\\\",\"list\":[1,true,\"x\"],"
                + "\"none\":null}");
        assertThat(Json.parse(json)).isEqualTo(value);
    }

    @Test
    void parse_refusesDeepNestingAndTrailingText() {
        assertThatThrownBy(() -> Json.parse("[".repeat(Json.MAX_DEPTH + 2))).hasMessageContaining("nested deeper than");
        assertThatThrownBy(() -> Json.parse("{} {}")).hasMessageContaining("more after the value");
        assertThatThrownBy(() -> Json.parse("{\"a\":}")).hasMessageContaining("unexpected '}'");
    }
}
