package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every value of a secret, wherever it occurs, becomes the name of the secret; nothing of it is left. */
class RedactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void redact_replacesEveryOccurrence_longestFirst_withTheName() {
        Redactor redactor = new Redactor(Map.of("A", "abcdefgh1", "B", "abcdefgh"));

        assertThat(redactor.redact("x abcdefgh1 abcdefgh")).isEqualTo("x [redacted:A] [redacted:B]");
        assertThat(text(redactor.redact(bytes("x abcdefgh1 abcdefgh")))).isEqualTo("x [redacted:A] [redacted:B]");
    }

    @Test
    void redact_worksOnBytes_alsoInsideJsonEscapedText() throws Exception {
        Redactor redactor = new Redactor(Map.of("TOKEN", "s3cr3t-t0ken"));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("output", "line \"one\"\n\tthe token is s3cr3t-t0ken\\ \u0001");
        state.put("count", 3);
        byte[] json = MAPPER.writeValueAsBytes(state);

        byte[] redacted = redactor.redact(json);

        assertThat(text(redacted)).doesNotContain("s3cr3t-t0ken").contains("[redacted:TOKEN]");
        assertThat(MAPPER.readTree(redacted).path("output").asText())
                .isEqualTo("line \"one\"\n\tthe token is [redacted:TOKEN]\\ \u0001");
    }

    /**
     * A value with a character JSON escapes is escaped once more in every layer of JSON (a step's JSON
     * output inside a checkpoint), so no exact match finds it; a value of digits only can stand in a JSON number,
     * where a replacement breaks the JSON. Both are refused, and so are values too short to be told from other text.
     */
    @Test
    void aValueJsonWouldEscape_orOfDigitsOnly_orTooShort_isRefused_withoutShowingIt() {
        for (String value : List.of("pa\"ss-word1", "pa\\ss-word1", "pa\nss-word1", "pa\u0001ss-word1",
                "pa\u007fss-word1", "1234567890", "short-1")) {
            assertThatThrownBy(() -> new Redactor(Map.of("PASSWORD", value))).as(value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PASSWORD").hasMessageNotContaining(value);
        }
        assertThat(Redactor.problem("pa\"ss-word1")).isEqualTo(Redactor.ValueProblem.ESCAPED);
        assertThat(Redactor.problem("pa\u001fss-word1")).as("every control character JSON escapes")
                .isEqualTo(Redactor.ValueProblem.ESCAPED);
        assertThat(Redactor.problem("1234567890")).isEqualTo(Redactor.ValueProblem.DIGITS);
        assertThat(Redactor.problem("short-1")).isEqualTo(Redactor.ValueProblem.TOO_SHORT);
        assertThat(Redactor.problem("x[redacted:A]y")).isEqualTo(Redactor.ValueProblem.MARKER);
        assertThat(Redactor.problem("12345-678")).as("not only digits").isNull();
    }

    /** A secret without a value is refused by its name, before any value is looked at. */
    @Test
    void aSecretWithoutAValue_isRefused_namingIt() {
        Map<String, String> secrets = new java.util.HashMap<>();
        secrets.put("PASSWORD", null);

        assertThatThrownBy(() -> new Redactor(secrets)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Secret PASSWORD has no value");
    }

    /**
     * A value that reads as a JSON number can stand in a number node, which the redactor keeps, or a replacement in a
     * blob would break the JSON: it is refused like a digits-only one. A value that only looks partly like one is not.
     */
    @Test
    void aValueThatReadsAsAJsonNumber_isRefusedLikeDigitsOnly() {
        for (String value : List.of("1234.5678", "-12345678", "1.5e+300", "-0.000001", "123456E7", "00001234")) {
            assertThat(Redactor.problem(value)).as(value).isEqualTo(Redactor.ValueProblem.DIGITS);
        }
        for (String value : List.of("12345-678", "1234.5678.9", "+12345678", "0x1234abcd", "1234.5678e", "v1234.5678",
                "1234 5678")) {
            assertThat(Redactor.problem(value)).as(value).isNotEqualTo(Redactor.ValueProblem.DIGITS);
        }
    }

    /**
     * Shell outputs are trimmed, so a value with whitespace at an edge would be recorded without it,
     * where no exact match finds it; such a value is refused, inner spaces are fine.
     */
    @Test
    void aValueWithWhitespaceAtAnEdge_isRefused() {
        for (String value : List.of("abcdefgh1 ", " abcdefgh1", "abcdefgh1\u2003")) {
            assertThat(Redactor.problem(value)).as("[%s]", value).isEqualTo(Redactor.ValueProblem.WHITESPACE);
            assertThatThrownBy(() -> new Redactor(Map.of("PASSWORD", value))).isInstanceOf(
                    IllegalArgumentException.class).hasMessageContaining("whitespace");
        }
        assertThat(Redactor.problem("abcd efgh1")).isNull();
    }

    @Test
    void overlappingOccurrences_areReplacedTogether_soNoPartOfEitherIsLeft() {
        Redactor redactor = new Redactor(Map.of("LONG", "cdefghijkl", "SHORT", "abcdefgh"));

        assertThat(redactor.redact("<abcdefghijkl>")).as("named after the longest occurrence")
                .isEqualTo("<[redacted:LONG]>");
        assertThat(redactor.redact("<abcdefghabcdefghijkl>")).isEqualTo("<[redacted:SHORT][redacted:LONG]>");
    }

    @Test
    void aValueThatOverlapsItself_leavesNothingOfItself() {
        Redactor redactor = new Redactor(Map.of("REPEAT", "abababab"));

        assertThat(redactor.redact("x ababababab y")).isEqualTo("x [redacted:REPEAT] y");
        assertThat(redactor.redact("abababababababab")).isEqualTo("[redacted:REPEAT]");
    }

    @Test
    void adjacentOccurrences_stayApart() {
        Redactor redactor = new Redactor(Map.of("A", "abcdefgh"));

        assertThat(redactor.redact("abcdefghabcdefgh")).isEqualTo("[redacted:A][redacted:A]");
    }

    @Test
    void twoNamesWithTheSameValue_theFirstInSortOrderIsNamed() {
        Redactor redactor = new Redactor(Map.of("ZETA", "same-value-1", "ALPHA", "same-value-1"));

        assertThat(redactor.redact("is same-value-1")).isEqualTo("is [redacted:ALPHA]");
    }

    @Test
    void aValueThatContainsTheReplacementText_isRefused_withoutShowingIt() {
        assertThatThrownBy(() -> new Redactor(Map.of("A", "[redacted:A]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A").hasMessageContaining("[redacted:");
        assertThatThrownBy(() -> new Redactor(Map.of("B", "x[redacted:hidden-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("hidden-1");
    }

    @Test
    void anEmptyValueOrName_isRefused() {
        assertThatThrownBy(() -> new Redactor(Map.of("A", ""))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Redactor.problem("")).isEqualTo(Redactor.ValueProblem.TOO_SHORT);
        assertThatThrownBy(() -> new Redactor(Map.of(" ", "value-123"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textWithoutAValue_comesBackAsItIs() {
        Redactor redactor = new Redactor(Map.of("A", "abcdefgh"));
        byte[] content = bytes("nothing here");
        String text = "nothing here either, café ø 😀";

        assertThat(redactor.redact(content)).isSameAs(content);
        assertThat(redactor.redact(text)).isSameAs(text);
        assertThat(redactor.contains(content)).isFalse();
        assertThat(redactor.redact((String) null)).isNull();
    }

    @Test
    void nonAsciiValues_areFoundAsTheirUtf8Bytes() {
        Redactor redactor = new Redactor(Map.of("ACCENTS", "café-crème-ø"));

        assertThat(redactor.redact("says café-crème-ø 😀")).isEqualTo("says [redacted:ACCENTS] 😀");
        assertThat(redactor.contains(bytes("…café-crème-ø…"))).isTrue();
    }

    @Test
    void none_changesNothing() {
        Redactor none = Redactor.none();

        assertThat(none.isEmpty()).isTrue();
        assertThat(none.redact("abcdefgh")).isEqualTo("abcdefgh");
        assertThat(none.contains(bytes("abcdefgh"))).isFalse();
    }

    @Test
    void theFieldsOfALine_areRedactedValueByValue_keysIncluded_blobReferencesKept() throws Exception {
        Redactor redactor = new Redactor(Map.of("HEX", "0123456789abcdef"));
        String hash = "ff0123456789abcdef" + "e".repeat(46);
        ObjectNode fields = (ObjectNode) MAPPER.readTree("""
                {"name":"step 0123456789abcdef","nested":{"list":["a 0123456789abcdef b",3,true,null]},
                 "0123456789abcdef":"key","blob":{"sha256":"%s","size":12},"n":12}""".formatted(hash));
        String before = fields.toString();

        JsonNode redacted = redactor.redactFields(fields);

        assertThat(redacted.path("name").asText()).isEqualTo("step [redacted:HEX]");
        assertThat(redacted.path("nested").path("list").get(0).asText()).isEqualTo("a [redacted:HEX] b");
        assertThat(redacted.path("nested").path("list").get(1).asInt()).isEqualTo(3);
        assertThat(redacted.has("[redacted:HEX]")).isTrue();
        assertThat(redacted.path("blob").path("sha256").asText()).as("a blob reference must keep naming its blob")
                .isEqualTo(hash);
        assertThat(fields.toString()).as("the fields given are not changed").isEqualTo(before);
    }

    /** Only a blob reference keeps its hash; any other field called sha256 is run data like the rest. */
    @Test
    void aFieldNamedSha256_thatIsNoBlobReference_isRedacted() throws Exception {
        Redactor redactor = new Redactor(Map.of("TOKEN", "s3cr3t-t0ken"));
        ObjectNode fields = (ObjectNode) MAPPER.readTree("""
                {"fields":[{"name":"sha256","sha256":"s3cr3t-t0ken"}],
                 "short":{"sha256":"s3cr3t-t0ken","size":12},
                 "extra":{"sha256":"%s","size":1,"note":"s3cr3t-t0ken"}}""".formatted("a".repeat(64)));

        JsonNode redacted = redactor.redactFields(fields);

        assertThat(redacted.toString()).doesNotContain("s3cr3t-t0ken");
        assertThat(redacted.path("fields").get(0).path("sha256").asText()).isEqualTo("[redacted:TOKEN]");
        assertThat(redacted.path("short").path("sha256").asText()).as("not 64 hex digits: no blob reference")
                .isEqualTo("[redacted:TOKEN]");
    }

    /** A value of any kind is redacted, also inside maps and lists and in the text of a number. */
    @Test
    void redactValue_redactsStringsMapsListsAndOtherValuesByTheirText() {
        Redactor redactor = new Redactor(Map.of("TOKEN", "s3cr3t-t0ken", "NUM", "12345-678"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("s3cr3t-t0ken", List.of("x s3cr3t-t0ken", 3));
        nested.put("n", 12345678L);

        assertThat(redactor.redactValue("a s3cr3t-t0ken")).isEqualTo("a [redacted:TOKEN]");
        assertThat(redactor.redactValue(nested)).isEqualTo(Map.of("[redacted:TOKEN]",
                List.of("x [redacted:TOKEN]", 3), "n", 12345678L));
        assertThat(redactor.redactValue(new StringBuilder("y 12345-678"))).isEqualTo("y [redacted:NUM]");
        assertThat(redactor.redactValue(null)).isNull();
        assertThat(redactor.redactValue(true)).isEqualTo(true);
    }

    /**
     * A shell placeholder writes a {@code '} as {@code '\\''}, a form with a backslash that JSON escapes once more in every
     * layer, so the record could not find the value in every layer: a value with {@code '} is refused, naming the rule.
     */
    @Test
    void aValueWithASingleQuote_isRefused_namingTheRule() {
        assertThat(Redactor.problem("pa'ss-w0rd-x9")).isEqualTo(Redactor.ValueProblem.QUOTE);
        assertThat(Redactor.problem("'pass-word1")).isEqualTo(Redactor.ValueProblem.QUOTE);
        assertThatThrownBy(() -> new Redactor(Map.of("PASSWORD", "pa'ss-w0rd-x9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PASSWORD").hasMessageContaining("with '")
                .hasMessageContaining("shell placeholder").hasMessageNotContaining("pa'ss-w0rd-x9");
    }

    /**
     * JSON escapes a character on its own (a lone surrogate) in every layer, so no exact match would find a value
     * that holds one: it is refused like {@code "}. A character outside the BMP, a pair of surrogates, is written as
     * it is and allowed, and so is NBSP, which JSON does not escape either. U+2028 and U+2029 are not escaped, but
     * refused for another reason ({@link #aValueThatTheCleaningOfUntrustedTextChanges_isRefused}).
     */
    @Test
    void aValueWithALoneSurrogate_isRefused_aCharacterOutsideTheBmpIsNot() {
        for (String value : List.of("pa\uD800ss-word1", "pa\uDFFFss-word1", "pass-word1\uD83D", "\uDD11pass-word1")) {
            assertThat(Redactor.problem(value)).as(value).isEqualTo(Redactor.ValueProblem.ESCAPED);
        }
        for (String value : List.of("key-\uD83D\uDD11-0123456789", "pa\u00A0ss-word1")) {
            assertThat(Redactor.problem(value)).as(value).isNull();
        }
        for (String value : List.of("pa\u2028ss-word1", "pa\u2029ss-word1")) {
            assertThat(Redactor.problem(value)).as(value).isEqualTo(Redactor.ValueProblem.UNTRUSTED_TEXT);
        }
    }

    @Test
    void toString_namesTheSecrets_neverTheirValues() {
        assertThat(new Redactor(Map.of("TOKEN", "abcdefgh-value")).toString()).contains("TOKEN")
                .doesNotContain("abcdefgh-value");
    }


    /**
     * A blob reference keeps its hash, and anything of that shape counts as one. So a secret that is itself 64 hex
     * digits (a common token format) would stay in clear text there: such a "reference" is redacted like any object.
     */
    @Test
    void aBlobReferenceWhoseHashIsASecretValue_isRedacted() throws Exception {
        String hex = "0123456789abcdef".repeat(4);   // made up: 64 hex digits, as many tokens are
        Redactor redactor = new Redactor(Map.of("TOKEN", hex));
        ObjectNode fields = (ObjectNode) MAPPER.readTree("""
                {"options":[{"sha256":"%s","size":1}],"blob":{"sha256":"%s","size":12}}"""
                .formatted(hex, "e".repeat(64)));

        JsonNode redacted = redactor.redactFields(fields);

        assertThat(redacted.toString()).doesNotContain(hex);
        assertThat(redacted.path("options").get(0).path("sha256").asText()).isEqualTo("[redacted:TOKEN]");
        assertThat(redacted.path("blob").path("sha256").asText()).isEqualTo("e".repeat(64));
    }

    /**
     * Text from a tool or a model is cleaned ({@code UntrustedText}) before the redactor sees it: runs of whitespace
     * become one space, line breaks beyond the ASCII ones too, and a marker such as {@code gate_status:} loses its
     * colon. A value that the cleaning changes would be recorded in the changed form, where no exact match finds
     * it, so it is refused.
     */
    @Test
    void aValueThatTheCleaningOfUntrustedTextChanges_isRefused() {
        for (String value : List.of("correct  horse battery", "correct \u00a0horse", "line\u0085break-1",
                "line\u2028break-1", "line\u2029break-1", "a-todo_item: b-1", "x-gate_status:GREEN",
                "GATE-STATUS `:` red", "pre</open_todos>post")) {
            assertThat(Redactor.problem(value)).as("[%s]", value).isEqualTo(Redactor.ValueProblem.UNTRUSTED_TEXT);
            assertThat(traceworks.core.util.UntrustedText.inline(value, 1000)).isNotEqualTo(value);
            assertThatThrownBy(() -> new Redactor(Map.of("PASSWORD", value))).as("[%s]", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PASSWORD").hasMessageNotContaining(value);
        }
        // What the cleaning leaves as it is, is a value like any other
        for (String value : List.of("correct horse battery", "user:pass-word1", "gate_status-1", "caf\u00e9-cr\u00e8me",
                "a<b>c-d-e-f", "\ud83d\ude00-smile-1")) {
            assertThat(Redactor.problem(value)).as("[%s]", value).isNull();
            assertThat(traceworks.core.util.UntrustedText.inline(value, 1000)).isEqualTo(value);
        }
    }

    /** A short value inside a longer one ends before it: the rest of the longer one must not be left behind. */
    @Test
    void aValueThatLiesInsideALongerOne_leavesNothingOfTheLongerOne() {
        Redactor redactor = new Redactor(Map.of("LONG", "abcdefghijklmnop", "SHORT", "defghijk"));

        assertThat(redactor.redact("x abcdefghijklmnop y")).isEqualTo("x [redacted:LONG] y");
        assertThat(new String(redactor.redact("abcdefghijklmnop".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)).isEqualTo("[redacted:LONG]");
    }

    @Test
    void overlappingOccurrencesOfTheSameLength_areNamedAfterTheOneThatStartsFirst() {
        Redactor redactor = new Redactor(Map.of("ZETA", "abcdefgh", "ALPHA", "efghijkl"));

        assertThat(redactor.redact("abcdefghijkl")).isEqualTo("[redacted:ZETA]");
    }

    /** Text that differs from a value in one character, whichever it is, holds no secret and is not touched. */
    @Test
    void textThatDiffersFromAValueInOneCharacter_isLeftAsItIs() {
        String value = "abcdefgh";
        Redactor redactor = new Redactor(Map.of("A", value));

        for (int i = 0; i < value.length(); i++) {
            String nearMiss = value.substring(0, i) + "X" + value.substring(i + 1);
            assertThat(redactor.redact("see " + nearMiss + " here")).as("character %d differs", i)
                    .isEqualTo("see " + nearMiss + " here");
            assertThat(redactor.contains(nearMiss.getBytes(StandardCharsets.UTF_8))).as("character %d differs", i)
                    .isFalse();
        }
    }
}
