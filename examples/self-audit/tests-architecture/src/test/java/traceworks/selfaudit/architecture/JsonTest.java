package traceworks.selfaudit.architecture;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void writeThenParse_givesTheSameValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "A001");
        value.put("start", 12L);
        value.put("ratio", 0.5);
        value.put("ok", true);
        value.put("none", null);
        value.put("code", "12| if (a < b) {\n13|\t\"x\\y\"\u2028");
        value.put("hits", List.of(Map.of("rule", "path_check"), List.of()));

        String json = Json.write(value);

        assertThat(json).doesNotContain("\n").doesNotContain("\u2028");
        assertThat(Json.parse(json)).isEqualTo(value);
    }

    @Test
    void everythingBeyondPrintableAscii_isEscaped() {
        assertThat(Json.write("a\u0001b\u00e9\u202e\u007f")).isEqualTo("\"a\\u0001b\\u00e9\\u202e\\u007f\"");
        assertThat(Json.parse(Json.write("\u00e9\u202e"))).isEqualTo("\u00e9\u202e");
    }

    @Test
    void parse_readsEscapesAndNumbers() {
        assertThat(Json.parse(" {\"a\": \"\\u00e9\\n\\/\", \"b\": -3, \"c\": 1e2, \"d\": 99999999999999999999} "))
                .isEqualTo(Map.of("a", "\u00e9\n/", "b", -3L, "c", 100.0, "d", 1.0E20));
    }

    @Test
    void parse_refusesBrokenText() {
        for (String broken : List.of("", "{", "{\"a\" 1}", "[1,]", "\"open", "{\"a\":1} x", "tru", "{\"a\":\"\u0001\"}")) {
            assertThatThrownBy(() -> Json.parse(broken)).as(broken).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void parse_refusesDeepNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2);

        assertThatThrownBy(() -> Json.parse(deep)).hasMessageContaining("deeper");
    }

    @Test
    void firstObject_findsTheObjectWithTheKey_inProseOrAFence() {
        String answer = "Here is my verdict {not json}:\n```json\n{\"area\":\"A002\",\"findings\":[]}\n```\nDone.";

        assertThat(Json.firstObject(answer, "area")).contains(Map.of("area", "A002", "findings", List.of()));
    }

    @Test
    void firstObject_skipsAnObjectWithoutTheKey_andIsEmptyWithoutOne() {
        assertThat(Json.firstObject("{\"x\":1} {\"area\":\"A1\"}", "area")).contains(Map.of("area", "A1"));
        assertThat(Json.firstObject("no json here {", "area")).isEmpty();
    }
}
