package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import traceworks.core.util.SecretReplacer;
import traceworks.core.util.UntrustedText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Replaces the values of a run's secrets in what the record writes with {@code [redacted:<NAME>]}. The
 * recorder sends every string of a line and every blob through it before they are hashed and written.
 *
 * <p><b>Matching.</b> A value is found byte for byte, as its UTF-8 bytes; a string is redacted as its UTF-8 bytes,
 * which for well-formed text is the same as redacting its characters, since UTF-8 matches only at character
 * boundaries. A value holds no character JSON escapes ({@code "}, {@code \}, below U+0020 and a lone surrogate, as
 * the record's JSON writers write it, which put a character outside the BMP as its UTF-8 bytes), so it reads the same
 * in a JSON blob such as a checkpoint, at any depth of JSON in JSON, as in plain text.
 *
 * <p><b>Refused values</b> ({@link #problem}): shorter than {@value #MIN_VALUE_LENGTH}
 * characters (too easy to guess, and replaced where it is no secret); containing {@code "}, {@code \}, a control
 * character below U+0020 or a lone surrogate, which JSON escapes once more in every layer, so no exact match would
 * find it there, or U+007F, at which a cut of text whose values are not known may start
 * ({@code TruncationUtil#secretSafeTail}); containing {@code '}, which a shell placeholder writes as {@code '\''}
 * ({@code ShellQuoting}), a form with a backslash that JSON escapes once more in every layer, so no exact match would
 * find it there either; with whitespace at its start or end, which the shell blocks trim off every output, so the
 * record would hold the value without it; only digits, or a value that reads as a JSON number
 * ({@code -1234.5678}, {@code 1e10}), which can stand in a number, which a line keeps as it is and where a
 * replacement in a blob would break the JSON; that {@code UntrustedText.inline} changes (a run of whitespace,
 * U+0085, U+2028 or U+2029, a marker such as {@code gate_status:} or a block boundary such as
 * {@code <open_todos>}), since a text that is cleaned that way before the redactor sees it would hold the changed
 * form, which is no exact match (the fact sheet of a tool step replaces the values itself, before it cleans, so there
 * the text around a value cannot change it either); containing {@value #MARKER}.
 *
 * <p><b>Overlaps.</b> Every occurrence of every value is found. Occurrences that overlap, of one value or of several,
 * are replaced together by one {@code [redacted:<NAME>]}, so no part of any of them is left; it names the longest of
 * them, on a tie the one that starts first. Occurrences that only touch stay apart. The longest values are tried
 * first at each position. Two names with the same value are replaced by the first name in sort order.
 *
 * <p><b>Replacements.</b> The input is scanned once; what the redactor writes is not scanned again. A value that
 * contains {@value #MARKER} is refused, since the record could not tell it from a replacement, and the replacement of
 * another value could make it up.
 *
 * <p><b>Lines</b> ({@link #redactFields}): every string and field name is redacted; numbers, booleans and
 * {@code null} are kept, and so is a blob reference ({@code {"sha256": <64 hex digits>, "size": <n>}}), which must
 * keep naming its blob. Any other field called {@code sha256} is redacted like the rest.
 *
 * <p>The values are searched with an index of their first bytes, built once, so a line or a blob without a value
 * costs one pass over its bytes. Neither {@link #toString()} nor any message of this class names a value.
 */
public final class Redactor {

    /** How every replacement starts. */
    public static final String MARKER = SecretReplacer.MARKER;

    /** The shortest value a secret may have. */
    public static final int MIN_VALUE_LENGTH = 8;

    /** Only digits, or what reads as a JSON number: a sign, digits, a fraction, an exponent (leading zeros allowed). */
    /** A text that is redaction markers from its first character to its last. */
    private static final Pattern ALL_MARKERS = Pattern.compile("(?:\\[redacted:[^\\]]*\\])+");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    /** Why a value cannot be a secret's; each names the rule, never the value. */
    public enum ValueProblem {
        TOO_SHORT("shorter than " + MIN_VALUE_LENGTH + " characters",
                "a value that short is too easy to guess, and would be redacted where it is no secret"),
        ESCAPED("with \", \\, a control character or a lone surrogate",
                "JSON escapes such a character once more in every layer, so the record could not find the value"),
        QUOTE("with '",
                "a shell placeholder writes it as '\\'', a form the record could not find in every layer"),
        WHITESPACE("with whitespace at its start or end",
                "every shell output is trimmed, so the record would hold the value without it and could not find it"),
        DIGITS("of only digits, or that reads as a JSON number", "such a value can stand in a JSON number, where a"
                + " replacement breaks the record"),
        UNTRUSTED_TEXT("that the cleaning of untrusted text changes (a run of whitespace, U+0085, U+2028 or U+2029,"
                + " a marker such as gate_status: or <open_todos>)",
                "text of a tool or a model is cleaned before it is recorded, so the record would hold the value"
                        + " changed and could not find it"),
        MARKER("containing '" + Redactor.MARKER + "'", "the record could not tell such a value from a redacted one");

        private final String what;
        private final String why;

        ValueProblem(String what, String why) {
            this.what = what;
            this.why = why;
        }

        /** What the value is, as in "a value {@code <what>}". */
        public String what() {
            return what;
        }

        /** Why that is refused. */
        public String why() {
            return why;
        }
    }

    private static final Redactor NONE = new Redactor(Map.of());

    /** Finds the values and writes the replacements: the matching a step uses too, before it rewrites a text. */
    private final SecretReplacer replacer;
    private final Set<String> names;
    /** The values, for a cut that must keep each one whole ({@link #values}). */
    private final List<String> values;

    /**
     * @param namedValues the value of each secret by its name
     * @throws IllegalArgumentException for a blank name or a value with a {@link #problem}; the message names the
     *                                  secret and the rule, never the value
     */
    public Redactor(Map<String, String> namedValues) {
        Objects.requireNonNull(namedValues, "namedValues");
        Map<String, String> sorted = new TreeMap<>();
        namedValues.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A secret needs a name");
            }
            if (value == null) {
                throw new IllegalArgumentException("Secret " + name + " has no value");
            }
            ValueProblem problem = problem(value);
            if (problem != null) {
                throw new IllegalArgumentException("The value of secret " + name + " is refused: a value "
                        + problem.what() + " — " + problem.why());
            }
            sorted.put(name, value);
        });
        this.names = Set.copyOf(sorted.keySet());
        this.values = List.copyOf(new LinkedHashSet<>(sorted.values()));
        this.replacer = new SecretReplacer(sorted);
    }

    /** What is wrong with {@code value} as the value of a secret; {@code null} when nothing. */
    public static ValueProblem problem(String value) {
        if (value.length() < MIN_VALUE_LENGTH) {
            return ValueProblem.TOO_SHORT;
        }
        if (value.chars().anyMatch(c -> c == '"' || c == '\\' || c < 0x20 || c == 0x7f) || loneSurrogate(value)) {
            return ValueProblem.ESCAPED;
        }
        if (value.indexOf('\'') >= 0) {
            return ValueProblem.QUOTE;
        }
        if (!value.equals(value.strip())) {
            return ValueProblem.WHITESPACE;
        }
        if (NUMBER.matcher(value).matches()) {
            return ValueProblem.DIGITS;
        }
        // A tool's text has its values replaced before it is cleaned (SarifFactSheet), whatever the text around
        // them does to them. This refusal stays for any text that is cleaned without that: a value the cleaning
        // changes on its own would be there in the changed form only
        if (!UntrustedText.inline(value, Integer.MAX_VALUE, List.of()).equals(value)) {
            return ValueProblem.UNTRUSTED_TEXT;
        }
        if (value.contains(MARKER)) {
            return ValueProblem.MARKER;
        }
        return null;
    }

    /** Whether {@code value} holds a surrogate that is not one half of a pair: JSON escapes it, UTF-8 has none. */
    private static boolean loneSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** A redactor without secrets: it changes nothing. */
    public static Redactor none() {
        return NONE;
    }

    /**
     * The values this redactor replaces, for a cut in the record that must not split one; never shown,
     * only passed to {@code TruncationUtil}.
     */
    List<String> values() {
        return values;
    }

    /** Whether this redactor has no secret, so that it changes nothing. */
    public boolean isEmpty() {
        return names.isEmpty();
    }

    /**
     * The content with every value replaced.
     *
     * @return {@code content} itself when no value occurs in it; {@code null} for {@code null}
     */
    public byte[] redact(byte[] content) {
        return replacer.replace(content);
    }

    /**
     * The text with every value replaced.
     *
     * @return {@code text} itself when no value occurs in it; {@code null} for {@code null}
     */
    public String redact(String text) {
        return replacer.replace(text);
    }

    /** Whether a value occurs in the content, in any of the forms {@link #redact(byte[])} replaces. */
    public boolean contains(byte[] content) {
        return replacer.occursIn(content);
    }

    /**
     * Any value with every string in it redacted: a string, the keys and values of a map, the elements of a list or
     * an array, and any other value whose text holds a value of a secret, which then becomes its redacted text. A
     * value without a secret in it comes back as it is.
     */
    public Object redactValue(Object value) {
        if (value == null || names.isEmpty()) {
            return value;
        }
        if (value instanceof String text) {
            return redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(redactValue(k), redactValue(v)));
            return copy;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> copy = new ArrayList<>();
            items.forEach(item -> copy.add(redactValue(item)));
            return copy;
        }
        if (value instanceof Object[] items) {
            return redactValue(Arrays.asList(items));
        }
        String text = String.valueOf(value);
        String redacted = redact(text);
        return redacted == text ? value : redacted;
    }

    /**
     * A copy of the fields of a record line with every string redacted, field names included, nested ones too.
     * Numbers, booleans and {@code null} are kept, and so is a blob reference, which must keep naming its blob,
     * unless its hash is a secret's value.
     */
    ObjectNode redactFields(ObjectNode fields) {
        if (names.isEmpty()) {
            return fields;
        }
        return (ObjectNode) redactNode(fields);
    }

    /**
     * Whether a text is made of secret values and nothing else. A blob reference keeps its hash, and anything of the
     * shape {@code {sha256, size}} counts as one, whether the blob exists or not; a "hash" that is a secret's value
     * (a token of 64 hex digits) is no hash to keep. A real hash that merely contains a short secret keeps naming
     * its blob.
     */
    private boolean isAllSecret(String text) {
        String redacted = redact(text);
        return redacted != text && ALL_MARKERS.matcher(redacted).matches();
    }

    private JsonNode redactNode(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            String redacted = redact(text);
            return redacted == text ? node : TextNode.valueOf(redacted);
        }
        if (node.isObject()) {
            if (Blobs.isReference(node) && !isAllSecret(node.get("sha256").textValue())) {
                return node.deepCopy();
            }
            ObjectNode copy = Blobs.MAPPER.createObjectNode();
            node.properties().forEach(e -> copy.set(redact(e.getKey()), redactNode(e.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = Blobs.MAPPER.createArrayNode();
            node.forEach(element -> copy.add(redactNode(element)));
            return copy;
        }
        return node;
    }

    /** The names of the secrets, never their values. */
    @Override
    public String toString() {
        return "Redactor" + new TreeSet<>(names);
    }
}
