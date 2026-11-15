package traceworks.core.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.model.BlockSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The config keys of every block type the engine runs, read from {@value #RESOURCE} on the classpath. A second,
 * hand-maintained list in the rules would be exactly the drift this file prevents; its test holds the block types
 * against the core registry.
 *
 * <p>The fields of a block type are assembled in a fixed order: the keys
 * of the type, then the AI keys (only for AI block types), then the common ones, finally {@code on_failure}.
 */
public final class BlockConfigSchema {

    static final String RESOURCE = "block-config-schema.json";

    /**
     * A config key.
     *
     * @param values       allowed values, empty = free value
     * @param legacy       old value that remains valid, otherwise {@code null}
     * @param ignoreCase   the executor compares case-insensitively
     * @param legacyFor    old name of another key, otherwise {@code null}
     * @param onlyWith     the executor only reads the key if these keys have these values
     *                     (e.g. {@code container_image} of a retry block only with {@code execution_mode: shell})
     * @param topLevelOnly the key only takes effect on a top-level block, not on a nested one (tool mode of the shell
     *                     block)
     * @param types        the schema's {@code type}: the type names a value may have ({@link BlockSettings#problem});
     *                     empty = any
     * @param itemType     the type of a list's entries ({@code items.type}), otherwise {@code null}
     * @param range        {@code minimum} and {@code maximum} of a whole number, otherwise {@code null}
     * @param fields       the keys of an object value ({@code validation}), empty for any other key
     */
    public record Field(String key, List<String> values, String legacy, boolean ignoreCase, String legacyFor,
                        Map<String, String> onlyWith, boolean topLevelOnly, List<String> types, String itemType,
                        BlockSettings.Range range, Map<String, Field> fields) {

        public Field(String key, List<String> values, String legacy, boolean ignoreCase, String legacyFor,
                     Map<String, String> onlyWith) {
            this(key, values, legacy, ignoreCase, legacyFor, onlyWith, false);
        }

        public Field(String key, List<String> values, String legacy, boolean ignoreCase, String legacyFor,
                     Map<String, String> onlyWith, boolean topLevelOnly) {
            this(key, values, legacy, ignoreCase, legacyFor, onlyWith, topLevelOnly, List.of(), null, null, Map.of());
        }

        /** What is wrong with the value's type or range, as {@link BlockSettings#problem} says it; else {@code null}. */
        public String typeProblem(Object value) {
            return BlockSettings.problem(types, itemType, range, value);
        }

        /** Whether the value is one of the allowed ones; always true without fixed values. */
        public boolean allows(String value) {
            if (values.isEmpty()) return true;
            for (String allowed : allowedValues()) {
                if (ignoreCase ? allowed.equalsIgnoreCase(value.strip()) : allowed.equals(value)) return true;
            }
            return false;
        }

        /** Allowed values including the legacy value. */
        public List<String> allowedValues() {
            if (legacy == null) return values;
            List<String> all = new ArrayList<>(values);
            all.add(legacy);
            return all;
        }
    }

    private record Spec(List<String> required, List<String> requiredOneOf, Map<String, Field> fields) {}

    private static final class Holder {
        private static final BlockConfigSchema INSTANCE = load();
    }

    private final Map<String, Spec> blockTypes;
    private final Set<String> aiBlockTypes;
    private final Map<String, Field> aiFields;
    private final Map<String, Field> commonFields;
    private final Field onFailure;

    private BlockConfigSchema(JsonNode root) {
        Map<String, JsonNode> shared = new LinkedHashMap<>();
        root.path("sharedFields").properties().forEach(e -> shared.put(e.getKey(), e.getValue()));

        this.onFailure = field("on_failure", root.path("onFailure"), shared);
        this.commonFields = fields(root.path("commonFields"), shared);
        this.aiFields = fields(root.path("ai").path("fields"), shared);
        Set<String> ai = new LinkedHashSet<>();
        root.path("ai").path("blockTypes").forEach(t -> ai.add(t.asText()));
        this.aiBlockTypes = Collections.unmodifiableSet(ai);

        Map<String, Spec> types = new LinkedHashMap<>();
        root.path("blockTypes").properties().forEach(e -> {
            List<String> required = new ArrayList<>();
            e.getValue().path("required").forEach(r -> required.add(r.asText()));
            List<String> oneOf = new ArrayList<>();
            e.getValue().path("requiredOneOf").forEach(r -> oneOf.add(r.asText()));
            types.put(e.getKey(), new Spec(List.copyOf(required), List.copyOf(oneOf),
                    fields(e.getValue().path("fields"), shared)));
        });
        if (types.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " has no block types");
        }
        this.blockTypes = Collections.unmodifiableMap(types);
    }

    /** The schema from the classpath, loaded once. A missing file means a broken build, which must fail loudly. */
    public static BlockConfigSchema get() {
        return Holder.INSTANCE;
    }

    static BlockConfigSchema parse(InputStream json) throws IOException {
        return new BlockConfigSchema(new ObjectMapper().readTree(json));
    }

    private static BlockConfigSchema load() {
        try (InputStream in = BlockConfigSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the block config schema " + RESOURCE, e);
        }
    }

    /** Whether a schema exists for the block type. For other types the analyzer checks no keys. */
    public boolean knowsType(String type) {
        return type != null && blockTypes.containsKey(type);
    }

    public Set<String> blockTypes() {
        return blockTypes.keySet();
    }

    /** All keys the type knows, in the editor's order; empty for unknown types. */
    public Map<String, Field> fieldsFor(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        if (spec == null) return Map.of();
        Map<String, Field> all = new LinkedHashMap<>(spec.fields());
        if (aiBlockTypes.contains(type)) aiFields.forEach(all::putIfAbsent);
        commonFields.forEach(all::putIfAbsent);
        all.put(onFailure.key(), onFailure);
        return all;
    }

    /** The keys of the type itself, without AI keys, common keys and {@code on_failure}. */
    public Set<String> ownKeys(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        return spec == null ? Set.of() : spec.fields().keySet();
    }

    public List<String> requiredKeys(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        return spec == null ? List.of() : spec.required();
    }

    /** Keys of which exactly one must be set (shell: {@code command} or {@code container_args}); otherwise empty. */
    public List<String> requiredOneOfKeys(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        return spec == null ? List.of() : spec.requiredOneOf();
    }

    public Set<String> aiBlockTypes() {
        return aiBlockTypes;
    }

    public Set<String> aiKeys() {
        return aiFields.keySet();
    }

    /** The AI key {@code key} ({@code provider}, {@code model}, {@code effort}); {@code null} for another key. */
    public Field aiField(String key) {
        return aiFields.get(key);
    }

    public Set<String> commonKeys() {
        return commonFields.keySet();
    }

    private static Map<String, Field> fields(JsonNode node, Map<String, JsonNode> shared) {
        Map<String, Field> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), field(e.getKey(), e.getValue(), shared)));
        return Collections.unmodifiableMap(out);
    }

    /** A field; the text {@code "#name"} refers to {@code sharedFields.name}. */
    private static Field field(String key, JsonNode node, Map<String, JsonNode> shared) {
        if (node.isTextual() && node.asText().startsWith("#")) {
            JsonNode resolved = shared.get(node.asText().substring(1));
            if (resolved == null) {
                throw new IllegalStateException(RESOURCE + ": unknown sharedField " + node.asText() + " at " + key);
            }
            node = resolved;
        }
        List<String> values = new ArrayList<>();
        node.path("values").forEach(v -> values.add(v.path("value").asText()));
        Map<String, String> onlyWith = new LinkedHashMap<>();
        node.path("onlyWith").properties().forEach(e -> onlyWith.put(e.getKey(), e.getValue().asText()));
        List<String> types = new ArrayList<>();
        JsonNode type = node.path("type");
        if (type.isTextual()) types.add(type.asText());
        type.forEach(t -> types.add(t.asText()));
        BlockSettings.Range range = node.has("minimum") || node.has("maximum")
                ? new BlockSettings.Range(node.path("minimum").asLong(Integer.MIN_VALUE),
                        node.path("maximum").asLong(Integer.MAX_VALUE))
                : null;
        return new Field(key, List.copyOf(values), textOrNull(node.path("legacy")),
                node.path("ignoreCase").asBoolean(false), textOrNull(node.path("legacyFor")),
                Collections.unmodifiableMap(onlyWith), node.path("topLevelOnly").asBoolean(false),
                List.copyOf(types), textOrNull(node.path("items").path("type")), range,
                fields(node.path("fields"), shared));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }
}
