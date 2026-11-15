package traceworks.core.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed reads of a block's settings: the one way a run reads a value from a block's {@code config}. A value of the
 * wrong type, or a whole number out of its range, throws an {@link InvalidSettingException} whose message names the
 * key, what it must be and what was found, such as
 * {@code shell: 'timeout_seconds' must be a whole number between 1 and 86,400, found the text "5"}. A run never
 * casts a setting, so a wrong value fails the block instead of a {@link ClassCastException}, and it never takes a
 * default the author did not write: {@code "true"} is no boolean.
 *
 * <p>An absent key and a key whose value is {@code null} are the same: the reader's default applies. The analyzer's
 * {@code invalid-config-value} uses {@link #problem} with the type the block config schema states for the key, so
 * {@code check} and the run say the same about a value. The ranges of the whole-number settings are here
 * ({@link #range}); the schema states the same ones, which a test holds against this table. {@code compress} and
 * {@code compress-ai} check their {@code max_chars} and {@code timeout_seconds} themselves.
 */
public final class BlockSettings {

    /** The schema's type names this reader knows. */
    public static final String BOOLEAN = "boolean";
    public static final String INTEGER = "integer";
    public static final String STRING = "string";
    public static final String ARRAY = "array";
    public static final String OBJECT = "object";

    /** The range of a whole-number setting, both ends included. */
    public record Range(long min, long max) {

        boolean contains(BigDecimal value) {
            return value.compareTo(BigDecimal.valueOf(min)) >= 0 && value.compareTo(BigDecimal.valueOf(max)) <= 0;
        }

        String describe() {
            return "a whole number between " + fmt(min) + " and " + fmt(max);
        }
    }

    /**
     * The range of every whole-number setting a run reads through this class, by key; the same key has the same range
     * on every block type. The upper ends keep the arithmetic of the readers inside an {@code int} (the agentic
     * block's auto-aligned timeouts, the retry block's backoff) and refuse a value that would wrap.
     */
    private static final Map<String, Range> RANGES = Map.ofEntries(
            Map.entry("timeout_seconds", new Range(1, 86_400)),
            Map.entry("iteration_timeout_seconds", new Range(1, 86_400)),
            Map.entry("total_timeout_seconds", new Range(1, 604_800)),
            Map.entry("max_iterations", new Range(1, 10_000)),
            Map.entry("max_total_tokens", new Range(1, 1_000_000_000)),
            Map.entry("max_repeated_actions", new Range(1, 10_000)),
            Map.entry("max_retries", new Range(0, 100)),
            Map.entry("delay", new Range(0, 3_600)),
            Map.entry("max_consecutive_failures", new Range(0, 10_000)),
            Map.entry("timeout_minutes", new Range(0, 10_080)),
            Map.entry("min_length", new Range(0, Integer.MAX_VALUE)),
            Map.entry("max_length", new Range(0, Integer.MAX_VALUE)));

    /** How much of a wrong text value a message quotes. */
    private static final int SHOWN_CHARS = 40;

    private final String where;
    private final String prefix;
    private final Map<String, Object> config;

    private BlockSettings(String where, String prefix, Map<String, Object> config) {
        this.where = where;
        this.prefix = prefix;
        this.config = config != null ? config : Map.of();
    }

    /** The settings of {@code block}; its type names the block in a message. */
    public static BlockSettings of(BlockDefinition block) {
        return of(block == null ? null : block.getType(), block == null ? null : block.getConfig());
    }

    /** The settings in {@code config} of a block of {@code type} (a nested block, or one the caller holds as a map). */
    public static BlockSettings of(String type, Map<String, Object> config) {
        return new BlockSettings(type == null || type.isBlank() ? "block" : type, "", config);
    }

    /** The range of the whole-number setting {@code key}; {@code null} for a key this class has none for. */
    public static Range range(String key) {
        return RANGES.get(key);
    }

    /** The keys this class has a range for. */
    public static java.util.Set<String> rangedKeys() {
        return RANGES.keySet();
    }

    /**
     * The settings in the object {@code key} ({@code validation}), whose keys a message names as {@code key.inner};
     * empty when absent.
     *
     * @throws InvalidSettingException when the value is no object
     */
    public BlockSettings within(String key) {
        return new BlockSettings(where, prefix + key + ".", object(key));
    }

    /**
     * The settings in entry {@code index} of the list of objects {@code key} ({@code cases}), whose keys a message
     * names as {@code key[index].inner}.
     *
     * @throws InvalidSettingException when {@code key} is no list of objects
     */
    public BlockSettings entry(String key, int index) {
        return new BlockSettings(where, prefix + key + "[" + index + "].", objects(key).get(index));
    }

    /**
     * The value of a setting that may have one of several of the schema's {@code types} (a foreach's {@code items}: a
     * list or a text), {@code null} when absent.
     */
    public Object value(String key, String... types) {
        return checked(key, List.of(types), null, null);
    }

    /** Whether the key has a value ({@code null} counts as absent). */
    public boolean has(String key) {
        return config.get(key) != null;
    }

    /** A boolean setting, {@code false} when absent. */
    public boolean flag(String key) {
        return flag(key, false);
    }

    /** A boolean setting, {@code fallback} when absent. Only {@code true} and {@code false} are booleans. */
    public boolean flag(String key, boolean fallback) {
        Object value = checked(key, List.of(BOOLEAN), null, null);
        return value == null ? fallback : (Boolean) value;
    }

    /** A whole number in the key's {@linkplain #range range}, {@code fallback} when absent. */
    public int number(String key, int fallback) {
        Integer value = number(key);
        return value == null ? fallback : value;
    }

    /** A whole number in the key's {@linkplain #range range}, {@code null} when absent. */
    public Integer number(String key) {
        Range range = RANGES.get(key);
        if (range == null) {
            throw new IllegalStateException("no range for the setting " + key);
        }
        Object value = checked(key, List.of(INTEGER), null, range);
        return value == null ? null : ((Number) value).intValue();
    }

    /** A text setting, {@code null} when absent. */
    public String text(String key) {
        return (String) checked(key, List.of(STRING), null, null);
    }

    /** A text setting, {@code fallback} when absent. */
    public String text(String key, String fallback) {
        String value = text(key);
        return value == null ? fallback : value;
    }

    /** A list of texts, {@code null} when absent. */
    public List<String> texts(String key) {
        List<?> value = (List<?>) checked(key, List.of(ARRAY), STRING, null);
        if (value == null) return null;
        List<String> texts = new ArrayList<>(value.size());
        value.forEach(item -> texts.add((String) item));
        return texts;
    }

    /** A list of texts, {@code fallback} when absent. */
    public List<String> texts(String key, List<String> fallback) {
        List<String> value = texts(key);
        return value == null ? fallback : value;
    }

    /** A list whose entries are objects ({@code cases}, {@code fields}), {@code null} when absent. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> objects(String key) {
        return (List<Map<String, Object>>) checked(key, List.of(ARRAY), OBJECT, null);
    }

    /** An object setting, {@code null} when absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> object(String key) {
        return (Map<String, Object>) checked(key, List.of(OBJECT), null, null);
    }

    /** The value of {@code key} when it has one of {@code types}; else the setting's error. */
    private Object checked(String key, Collection<String> types, String itemType, Range range) {
        Object value = config.get(key);
        String problem = problem(types, itemType, range, value);
        if (problem != null) {
            throw new InvalidSettingException(message(key, problem));
        }
        return value;
    }

    /** The message of a wrong value of {@code key}: {@code shell: 'timeout_seconds' must be ...}. */
    public String message(String key, String problem) {
        return where + ": '" + prefix + key + "' " + problem;
    }

    /**
     * What is wrong with {@code value} for a setting of one of the schema's {@code types}, as the end of a message
     * ({@code must be true or false, found the text "true"}); {@code null} when it is right. {@code null} is always
     * right: it counts as absent.
     *
     * @param types    the schema's type names: {@value #BOOLEAN}, {@value #INTEGER}, {@value #STRING},
     *                 {@value #ARRAY}, {@value #OBJECT} (and {@code null}, which adds nothing); others are not checked
     * @param itemType the type of a list's entries, {@code null} for any
     * @param range    the range of a whole number, {@code null} for any {@code int}
     */
    public static String problem(Collection<String> types, String itemType, Range range, Object value) {
        if (value == null) return null;
        List<String> expected = new ArrayList<>();
        for (String type : types) {
            if (type == null || "null".equals(type)) continue;
            if (fits(type, itemType, range, value)) return null;
            String described = expected(type, itemType, range);
            if (described == null) return null;   // a type this reader does not know: not checked
            expected.add(described);
        }
        if (expected.isEmpty()) return null;
        return "must be " + String.join(" or ", expected) + ", found " + describe(value, itemType);
    }

    private static boolean fits(String type, String itemType, Range range, Object value) {
        return switch (type) {
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Number number && wholeNumberIn(number,
                    range != null ? range : new Range(Integer.MIN_VALUE, Integer.MAX_VALUE));
            case STRING -> value instanceof String;
            case ARRAY -> value instanceof List<?> list
                    && (itemType == null || list.stream().allMatch(item -> fits(itemType, null, null, item)));
            case OBJECT -> value instanceof Map<?, ?>;
            default -> false;
        };
    }

    private static String expected(String type, String itemType, Range range) {
        return switch (type) {
            case BOOLEAN -> "true or false";
            case INTEGER -> (range != null ? range : new Range(Integer.MIN_VALUE, Integer.MAX_VALUE)).describe();
            case STRING -> "text";
            case ARRAY -> switch (itemType == null ? "" : itemType) {
                case STRING -> "a list of texts";
                case OBJECT -> "a list of objects";
                default -> "a list";
            };
            case OBJECT -> "an object";
            default -> null;
        };
    }

    /** A whole number in the range; {@code 1.0} is one, {@code 0.5}, NaN and infinity are none. */
    private static boolean wholeNumberIn(Number number, Range range) {
        BigDecimal exact;
        try {
            exact = new BigDecimal(number.toString());
        } catch (NumberFormatException e) {
            return false;
        }
        return exact.stripTrailingZeros().scale() <= 0 && range.contains(exact);
    }

    /**
     * What a message says was found: {@code the text "5"}, {@code 0.5}, {@code an object}; for a list whose entry
     * has the wrong type, that entry ({@code a list with the entry 5}).
     */
    static String describe(Object value, String itemType) {
        if (value instanceof String text) {
            String shown = text.length() > SHOWN_CHARS ? text.substring(0, SHOWN_CHARS) + "…" : text;
            return "the text \"" + shown + "\"";
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (itemType != null && !fits(itemType, null, null, item)) {
                    return "a list with the entry " + describe(item, null);
                }
            }
            return "a list";
        }
        if (value instanceof Map<?, ?>) return "an object";
        return String.valueOf(value);
    }

    private static String fmt(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }
}
