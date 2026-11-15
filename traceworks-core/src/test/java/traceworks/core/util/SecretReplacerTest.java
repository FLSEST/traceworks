package traceworks.core.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The naming and the bookkeeping of the replacer; the matching itself is tested with the record's redactor. */
class SecretReplacerTest {

    @Test
    void replacesEachValueByItsName() {
        SecretReplacer replacer = new SecretReplacer(Map.of("FIRST", "first-value-1", "SECOND", "second-value-2"));

        assertThat(replacer.replace("a first-value-1 b second-value-2 c"))
                .isEqualTo("a [redacted:FIRST] b [redacted:SECOND] c");
        assertThat(replacer.replacements()).containsExactly("[redacted:FIRST]", "[redacted:SECOND]");
        assertThat(replacer.isEmpty()).isFalse();
    }

    @Test
    void aTextWithoutAValue_comesBackAsItIs() {
        SecretReplacer replacer = new SecretReplacer(Map.of("FIRST", "first-value-1"));
        String text = "nothing here";

        assertThat(replacer.replace(text)).isSameAs(text);
        assertThat(replacer.replace((String) null)).isNull();
        assertThat(SecretReplacer.none().replace(text)).isSameAs(text);
        assertThat(SecretReplacer.none().isEmpty()).isTrue();
        assertThat(SecretReplacer.none().replacements()).isEmpty();
    }

    /** A run holds a credential of an AI provider under a key no step can declare; the name is its last part. */
    @Test
    void ofKeys_namesAReplacementAfterTheLastPartOfTheKey() {
        Map<String, String> byKey = new LinkedHashMap<>();
        byKey.put("SCAN_VALUE", "scan-value-1");
        byKey.put("credential:claude:ANTHROPIC_API_KEY", "provider-value-2");

        SecretReplacer replacer = SecretReplacer.ofKeys(byKey);

        assertThat(replacer.replace("scan-value-1 and provider-value-2"))
                .isEqualTo("[redacted:SCAN_VALUE] and [redacted:ANTHROPIC_API_KEY]");
        assertThat(replacer.toString()).isEqualTo("SecretReplacer[ANTHROPIC_API_KEY, SCAN_VALUE]")
                .doesNotContain("scan-value-1");
    }

    /** A declared secret and a credential of the same name, with different values: both are replaced. */
    @Test
    void ofKeys_replacesBothValuesOfOneName() {
        SecretReplacer replacer = SecretReplacer.ofKeys(Map.of("API_VALUE", "declared-value-1",
                "credential:pi:API_VALUE", "provider-value-2"));

        assertThat(replacer.replace("declared-value-1 provider-value-2"))
                .isEqualTo("[redacted:API_VALUE] [redacted:API_VALUE]");
        assertThat(replacer.replacements()).containsExactly("[redacted:API_VALUE]");
    }

    @Test
    void anEmptyValue_isLeftOut() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("EMPTY", "");
        values.put("FIRST", "first-value-1");

        assertThat(new SecretReplacer(values).replace("a first-value-1")).isEqualTo("a [redacted:FIRST]");
    }
}
