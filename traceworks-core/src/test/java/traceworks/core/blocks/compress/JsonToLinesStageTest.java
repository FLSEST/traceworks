package traceworks.core.blocks.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonToLinesStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final JsonToLinesStage stage = new JsonToLinesStage();

    @Test
    void dottedAndHyphenatedFields_booleansAndArrays() {
        StageResult r = stage.apply("[{\"a\":{\"b\":\"x\"},\"c-d\":true,\"l\":[1,2]}]",
                Map.of("template", "{a.b} {c-d} {l}"), guard);

        assertThat(r.output()).isEqualTo("x true [1,2]");
    }

    @Test
    void newlinesInValuesAndTemplate_becomeSpaces_soOneEntryIsOneLine() {
        StageResult r = stage.apply("[{\"t\":\"one\\ntwo\"},{\"t\":\"three\"}]", Map.of("template", "- {t}\n  end"), guard);

        assertThat(r.output()).isEqualTo("- one two   end\n- three   end");
        assertThat(r.note()).isEqualTo("2 entries as lines");
    }

    @Test
    void replacementSpecialCharsInValues_areLiteral() {
        StageResult r = stage.apply("[{\"p\":\"$1 \\\\ C:\\\\tmp\"}]", Map.of("template", "{p}"), guard);

        assertThat(r.output()).isEqualTo("$1 \\ C:\\tmp");
    }

    @Test
    void emptyArray_givesEmptyText_andNestedPath() {
        StageResult empty = stage.apply("{\"items\":[]}", Map.of("path", "items", "template", "{t}"), guard);

        assertThat(empty.output()).isEmpty();
        assertThat(empty.note()).isEqualTo("0 entries as lines");
    }

    @Test
    void validate_templateMissingOrWithoutField() {
        assertThatThrownBy(() -> stage.validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stage json.to_lines: 'template' is missing");
        assertThatThrownBy(() -> stage.validate(Map.of("template", "{ empty }")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("at least one placeholder");
        assertThatThrownBy(() -> stage.validate(Map.of("template", "{t}", "path", 3)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'path' must be text");
    }
}
