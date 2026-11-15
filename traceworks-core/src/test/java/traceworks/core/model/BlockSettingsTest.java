package traceworks.core.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The typed reads of a block's settings: a wrong value fails with the key, what it must be and what was found. */
class BlockSettingsTest {

    private static BlockSettings shell(Object... keysAndValues) {
        Map<String, Object> config = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            config.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return BlockSettings.of("shell", config);
    }

    @Nested
    class Flags {

        @Test
        void aBoolean_isRead_andAbsentOrNullIsTheDefault() {
            assertThat(shell("network", true).flag("network")).isTrue();
            assertThat(shell("network", false).flag("network", true)).isFalse();
            assertThat(shell().flag("network")).isFalse();
            assertThat(shell("network", null).flag("network", true)).isTrue();
        }

        /** B1: the text "true" was silently false; it is no boolean. */
        @Test
        void theTextTrue_isRefused() {
            assertThatThrownBy(() -> shell("shell_escape_variables", "true").flag("shell_escape_variables"))
                    .isInstanceOf(InvalidSettingException.class)
                    .hasMessage("shell: 'shell_escape_variables' must be true or false, found the text \"true\"");
            assertThatThrownBy(() -> shell("network", 1).flag("network"))
                    .hasMessage("shell: 'network' must be true or false, found 1");
        }
    }

    @Nested
    class Numbers {

        @Test
        void aWholeNumberInRange_isRead() {
            assertThat(shell("timeout_seconds", 5).number("timeout_seconds", 300)).isEqualTo(5);
            assertThat(shell("timeout_seconds", 5L).number("timeout_seconds")).isEqualTo(5);
            assertThat(shell("timeout_seconds", 5.0).number("timeout_seconds")).isEqualTo(5);
            assertThat(shell().number("timeout_seconds", 300)).isEqualTo(300);
            assertThat(shell("timeout_seconds", null).number("timeout_seconds")).isNull();
            assertThat(shell("timeout_seconds", 86_400).number("timeout_seconds")).isEqualTo(86_400);
        }

        /** L1: 0, -3, 0.5, a text, values that would wrap: each an error, none a silent default. */
        @Test
        void outOfRange_notWhole_orNoNumber_isRefused() {
            String must = "shell: 'timeout_seconds' must be a whole number between 1 and 86,400, found ";
            assertThatThrownBy(() -> shell("timeout_seconds", 0).number("timeout_seconds")).hasMessage(must + "0");
            assertThatThrownBy(() -> shell("timeout_seconds", -3).number("timeout_seconds")).hasMessage(must + "-3");
            assertThatThrownBy(() -> shell("timeout_seconds", 0.5).number("timeout_seconds")).hasMessage(must + "0.5");
            assertThatThrownBy(() -> shell("timeout_seconds", "5").number("timeout_seconds", 300))
                    .hasMessage(must + "the text \"5\"");
            assertThatThrownBy(() -> shell("timeout_seconds", 3_000_000_000L).number("timeout_seconds"))
                    .hasMessage(must + "3000000000");
            assertThatThrownBy(() -> shell("timeout_seconds", 4_294_967_297L).number("timeout_seconds"))
                    .hasMessage(must + "4294967297");
            assertThatThrownBy(() -> shell("timeout_seconds", new java.math.BigInteger("100000000000000000000"))
                    .number("timeout_seconds")).isInstanceOf(InvalidSettingException.class);
            assertThatThrownBy(() -> shell("timeout_seconds", Double.NaN).number("timeout_seconds"))
                    .isInstanceOf(InvalidSettingException.class);
        }

        @Test
        void delay_mayBeZero_butNotNegative() {
            assertThat(BlockSettings.of("retry", Map.of("delay", 0)).number("delay", 5)).isZero();
            assertThatThrownBy(() -> BlockSettings.of("retry", Map.of("delay", -1)).number("delay", 5))
                    .hasMessage("retry: 'delay' must be a whole number between 0 and 3,600, found -1");
        }

        @Test
        void aKeyWithoutARange_isAProgrammingError() {
            assertThatThrownBy(() -> shell("x", 1).number("x")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class TextsAndShapes {

        @Test
        void text() {
            assertThat(shell("command", "make").text("command")).isEqualTo("make");
            assertThat(shell().text("command", "x")).isEqualTo("x");
            assertThatThrownBy(() -> shell("command", List.of("echo", "x")).text("command"))
                    .hasMessage("shell: 'command' must be text, found a list");
        }

        @Test
        void listOfTexts() {
            assertThat(shell("mounts", List.of("/a:/b:ro")).texts("mounts")).containsExactly("/a:/b:ro");
            assertThat(shell().texts("mounts", List.of())).isEmpty();
            assertThatThrownBy(() -> shell("mounts", "/a:/b:ro").texts("mounts"))
                    .hasMessage("shell: 'mounts' must be a list of texts, found the text \"/a:/b:ro\"");
            assertThatThrownBy(() -> shell("mounts", Arrays.asList("/a:/b:ro", 5)).texts("mounts"))
                    .hasMessage("shell: 'mounts' must be a list of texts, found a list with the entry 5");
        }

        @Test
        void objects_andNestedSettings() {
            BlockSettings settings = shell("validation", Map.of("min_length", "100"));
            assertThatThrownBy(() -> settings.within("validation").number("min_length"))
                    .hasMessage("shell: 'validation.min_length' must be a whole number between 0 and 2,147,483,647,"
                            + " found the text \"100\"");
            assertThat(shell().within("validation").has("contains")).isFalse();
            assertThatThrownBy(() -> shell("validation", "OK").within("validation"))
                    .hasMessage("shell: 'validation' must be an object, found the text \"OK\"");
            assertThatThrownBy(() -> shell("cases", List.of("a")).objects("cases"))
                    .hasMessage("shell: 'cases' must be a list of objects, found a list with the entry the text \"a\"");
        }

        @Test
        void aLongText_isCutInTheMessage() {
            assertThatThrownBy(() -> shell("network", "x".repeat(100)).flag("network"))
                    .hasMessage("shell: 'network' must be true or false, found the text \"" + "x".repeat(40) + "…\"");
        }
    }

    /** The analyzer's side: the schema's type names, several of them, and types the reader does not know. */
    @Test
    void problem_withTheSchemasTypeNames() {
        assertThat(BlockSettings.problem(List.of("array", "string"), null, null, "a,b")).isNull();
        assertThat(BlockSettings.problem(List.of("array", "string"), null, null, 5))
                .isEqualTo("must be a list or text, found 5");
        assertThat(BlockSettings.problem(List.of("string", "null"), null, null, null)).isNull();
        assertThat(BlockSettings.problem(List.of("string", "null"), null, null, 5))
                .isEqualTo("must be text, found 5");
        assertThat(BlockSettings.problem(List.of("integer"), null, null, 5)).isNull();
        assertThat(BlockSettings.problem(List.of("whatever"), null, null, 5)).isNull();
        assertThat(BlockSettings.problem(List.of(), null, null, 5)).isNull();
    }
}
