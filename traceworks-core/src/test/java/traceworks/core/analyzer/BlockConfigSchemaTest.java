package traceworks.core.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.blocks.CompressAiBlockExecutor;
import traceworks.core.blocks.CompressBlockExecutor;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.blocks.compress.CompressionException;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.model.BlockSettings;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema mapping per block type from the shipped schema file. */
class BlockConfigSchemaTest {

    private static BlockConfigSchema parse(String json) throws IOException {
        return BlockConfigSchema.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** Small schema: one AI type, one non-AI type, one common and one shared key. */
    private static final String MINI = """
            {
              "onFailure": {"values": [{"value": "stop"}, {"value": "continue"}]},
              "commonFields": {"label": {}, "provider": {}},
              "ai": {"blockTypes": ["simple"], "fields": {"model": {}, "provider": {"values": [{"value": "CLAUDE"}], "ignoreCase": true}}},
              "sharedFields": {
                "mode": {"values": [{"value": "ai"}, {"value": "shell"}], "legacy": "claude"},
                "image": {"onlyWith": {"mode": "shell"}}
              },
              "blockTypes": {
                "simple": {"required": ["command"], "fields": {"command": {}, "model": {"legacyFor": "x"}}},
                "loop": {"fields": {"mode": "#mode", "image": "#image"}}
              }
            }
            """;

    @Nested
    class ShippedSchema {

        private final BlockConfigSchema schema = BlockConfigSchema.get();

        @Test
        void knowsTheThirteenCoreBlockTypes() {
            assertThat(schema.blockTypes()).containsExactlyInAnyOrder(
                    "simple", "shell", "retry", "conditional", "foreach", "while", "switch", "agentic",
                    "approval", "sub-workflow", "human-input", "compress", "compress-ai");
            assertThat(BlockConfigSchema.get()).isSameAs(schema);
        }

        /** The schema covers exactly the types the engine runs, so no type goes unchecked and none is described in vain. */
        @Test
        void blockTypes_areTheTypesOfTheCoreRegistry() {
            assertThat(schema.blockTypes()).containsExactlyInAnyOrderElementsOf(CoreBlocks.registry(null).knownTypes());
            assertThat(schema.aiBlockTypes()).allSatisfy(type -> assertThat(schema.knowsType(type)).as(type).isTrue());
        }

        @Test
        void unknownTypes_haveNoSchema() {
            assertThat(schema.knowsType(null)).isFalse();
            assertThat(schema.knowsType("SIMPLE")).isFalse();
            assertThat(schema.knowsType("todo-create")).isFalse();
            assertThat(schema.fieldsFor("does-not-exist")).isEmpty();
            assertThat(schema.fieldsFor(null)).isEmpty();
            assertThat(schema.ownKeys(null)).isEmpty();
            assertThat(schema.requiredKeys("does-not-exist")).isEmpty();
        }

        @Test
        void everyTypeEndsWithOnFailure_andCarriesTheCommonKeys() {
            for (String type : schema.blockTypes()) {
                List<String> keys = List.copyOf(schema.fieldsFor(type).keySet());
                assertThat(keys).as(type).endsWith("on_failure").containsAll(schema.commonKeys());
            }
        }

        @Test
        void aiKeys_onlyForAiBlockTypes() {
            assertThat(schema.aiKeys()).contains("provider", "model", "effort");
            assertThat(schema.fieldsFor("simple")).containsKeys("provider", "model", "effort");
            assertThat(schema.fieldsFor("shell")).doesNotContainKeys("model", "effort");
            assertThat(schema.aiBlockTypes()).contains("simple", "agentic", "compress-ai").doesNotContain("shell");
            // the one list of the providers Traceworks knows (unknown-provider reads it)
            assertThat(schema.aiField("provider").values()).containsExactlyInAnyOrder("CLAUDE", "PI");
            assertThat(schema.aiField("provider").ignoreCase()).isTrue();
            assertThat(schema.aiField("command")).isNull();
        }

        @Test
        void requiredKeysPerType() {
            // Tool mode: exactly one of command and container_args
            assertThat(schema.requiredKeys("shell")).isEmpty();
            assertThat(schema.requiredOneOfKeys("shell")).containsExactly("command", "container_args");
            assertThat(schema.requiredKeys("switch")).containsExactly("expression", "cases");
            assertThat(schema.requiredKeys("compress")).containsExactly("source", "stages");
            assertThat(schema.requiredKeys("approval")).isEmpty();
        }

        @Test
        void ownKeys_withoutAiCommonKeysAndOnFailure() {
            assertThat(schema.ownKeys("simple")).contains("command").doesNotContain("model", "label", "on_failure");
        }

        /**
         * {@code execution_mode} has exactly the values the engine accepts: the legacy alias {@code claude}
         * is not among them.
         */
        @Test
        void sharedFields_areResolved() {
            var mode = schema.fieldsFor("retry").get("execution_mode");
            assertThat(mode.values()).containsExactly("ai", "shell");
            assertThat(mode.legacy()).isNull();
            assertThat(mode.allows("claude")).isFalse();
            assertThat(mode.allowedValues()).containsExactlyInAnyOrder(ExecutionMode.AI, ExecutionMode.SHELL);

            var image = schema.fieldsFor("foreach").get("container_image");
            assertThat(image.onlyWith()).containsEntry("execution_mode", "shell");
        }

        @Test
        void legacyName_andCase() {
            assertThat(schema.fieldsFor("sub-workflow").get("workflowId").legacyFor()).isEqualTo("workflow_id");
            var effort = schema.fieldsFor("simple").get("effort");
            assertThat(effort.ignoreCase()).isTrue();
            assertThat(effort.allows(" high ")).isTrue();
            assertThat(effort.allows("turbo")).isFalse();
        }

        /** {@code network} is a Traceworks key: a shell step, or a wrapper's command in shell mode, gets network access. */
        @Test
        void network_isAKeyOfShellAndOfTheWrappersInShellMode() {
            assertThat(schema.fieldsFor("shell")).containsKey("network");
            assertThat(schema.fieldsFor("shell").get("network").onlyWith()).isEmpty();
            for (String wrapper : List.of("retry", "conditional", "foreach", "while")) {
                assertThat(schema.fieldsFor(wrapper).get("network").onlyWith()).as(wrapper)
                        .containsExactly(Map.entry("execution_mode", "shell"));
            }
        }

        /** {@code workspace_writable} is a Traceworks key: without it, a step sees the workspace read-only. */
        @Test
        void workspaceWritable_isAKeyOfShellAndOfTheWrappers_andToolsOfTheirAiStep() {
            assertThat(schema.fieldsFor("shell")).containsKey("workspace_writable");
            assertThat(schema.fieldsFor("shell").get("workspace_writable").onlyWith()).isEmpty();
            assertThat(schema.fieldsFor("shell").get("workspace_writable").topLevelOnly()).isFalse();
            for (String wrapper : List.of("retry", "conditional", "foreach", "while")) {
                assertThat(schema.fieldsFor(wrapper).get("workspace_writable").onlyWith()).as(wrapper).isEmpty();
                assertThat(schema.fieldsFor(wrapper).get("tools").onlyWith()).as(wrapper)
                        .containsExactly(Map.entry("execution_mode", "ai"));
            }
        }
    }

    @Nested
    class Field {

        @Test
        void withoutFixedValues_everythingIsAllowed() {
            var field = new BlockConfigSchema.Field("command", List.of(), null, false, null, Map.of());

            assertThat(field.allows("anything")).isTrue();
            assertThat(field.allowedValues()).isEmpty();
        }

        @Test
        void withoutIgnoreCase_comparesExactly() {
            var field = new BlockConfigSchema.Field("status", List.of("GREEN", "RED"), null, false, null, Map.of());

            assertThat(field.allows("GREEN")).isTrue();
            assertThat(field.allows("green")).isFalse();
            assertThat(field.allows(" GREEN")).isFalse();
        }

        @Test
        void ignoreCase_alsoIgnoresSurroundingSpace() {
            var field = new BlockConfigSchema.Field("provider", List.of("CLAUDE"), null, true, null, Map.of());

            assertThat(field.allows(" claude ")).isTrue();
            assertThat(field.allows("pi")).isFalse();
        }

        @Test
        void aLegacyValue_isAllowed_andComesLast() {
            var field = new BlockConfigSchema.Field("mode", List.of("ai", "shell"), "claude", false, null, Map.of());

            assertThat(field.allowedValues()).containsExactly("ai", "shell", "claude");
            assertThat(field.allows("claude")).isTrue();
            assertThat(field.values()).containsExactly("ai", "shell");
        }
    }

    /** The types and ranges in the shipped file, held against what the run checks. */
    @Nested
    class ShippedRanges {

        private final JsonNode blockTypes = shippedJson().path("blockTypes");

        @Test
        void compress_maxCharsAndTimeout_areTheRangesTheRunChecks() {
            JsonNode fields = blockTypes.path("compress").path("fields");

            assertRange(fields.path("max_chars"), "max_chars", CompressBlockExecutor::maxChars);
            assertRange(fields.path("timeout_seconds"), "timeout_seconds", CompressBlockExecutor::timeoutSeconds);
        }

        @Test
        void compressAi_maxChars_isTheRangeTheRunChecks() {
            assertRange(blockTypes.path("compress-ai").path("fields").path("max_chars"), "max_chars",
                    CompressAiBlockExecutor::maxChars);
        }

        /** compress-ai's timeout has no default (the provider's): absent is null, the bounds are the run's. */
        @Test
        void compressAi_timeout_isTheRangeTheRunChecks() {
            JsonNode field = blockTypes.path("compress-ai").path("fields").path("timeout_seconds");
            int min = field.path("minimum").asInt();
            int max = field.path("maximum").asInt();

            assertThat(CompressAiBlockExecutor.timeoutSeconds(Map.of())).isNull();
            assertThat(CompressAiBlockExecutor.timeoutSeconds(Map.of("timeout_seconds", max))).isEqualTo(max);
            assertThatThrownBy(() -> CompressAiBlockExecutor.timeoutSeconds(Map.of("timeout_seconds", min - 1)))
                    .isInstanceOf(CompressionException.class);
            assertThatThrownBy(() -> CompressAiBlockExecutor.timeoutSeconds(Map.of("timeout_seconds", max + 1)))
                    .isInstanceOf(CompressionException.class);
        }

        /**
         * Every whole-number setting outside compress has the range the run reads it with ({@link BlockSettings#range}),
         * in every block type and in {@code validation}, and every range the run has is stated in the schema.
         */
        @Test
        void everyWholeNumber_hasTheRangeTheRunReadsItWith() {
            java.util.Set<String> seen = new java.util.HashSet<>();
            BlockConfigSchema schema = BlockConfigSchema.get();
            for (String type : schema.blockTypes()) {
                for (BlockConfigSchema.Field field : allFields(schema.fieldsFor(type).values())) {
                    if (!field.types().contains("integer")) continue;
                    if ("compress".equals(type) || "compress-ai".equals(type) && "max_chars".equals(field.key())) {
                        continue;   // compress and compress-ai check these themselves (the tests above)
                    }
                    assertThat(field.range()).as(type + "." + field.key()).isEqualTo(BlockSettings.range(field.key()));
                    seen.add(field.key());
                }
            }
            assertThat(seen).containsExactlyInAnyOrderElementsOf(BlockSettings.rangedKeys());
        }

        /** Every key states a type; a list of objects or texts states its entries' type where the run reads them. */
        @Test
        void everyKey_statesItsType() {
            BlockConfigSchema schema = BlockConfigSchema.get();
            for (String type : schema.blockTypes()) {
                for (BlockConfigSchema.Field field : allFields(schema.fieldsFor(type).values())) {
                    if (field.legacyFor() != null) continue;
                    assertThat(field.types()).as(type + "." + field.key()).isNotEmpty();
                }
            }
            assertThat(schema.fieldsFor("switch").get("cases").itemType()).isEqualTo("object");
            assertThat(schema.fieldsFor("shell").get("mounts").itemType()).isEqualTo("string");
            assertThat(schema.fieldsFor("foreach").get("items").types()).containsExactly("array", "string");
        }

        private static List<BlockConfigSchema.Field> allFields(java.util.Collection<BlockConfigSchema.Field> fields) {
            List<BlockConfigSchema.Field> all = new java.util.ArrayList<>();
            for (BlockConfigSchema.Field field : fields) {
                all.add(field);
                all.addAll(allFields(field.fields().values()));
            }
            return all;
        }

        @Test
        void noJsonSchemaReference_sinceNothingResolvesOne() throws IOException {
            try (InputStream in = shippedStream()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("\"$ref\"");
            }
        }

        /** The default, both bounds and the values just outside them, as the run treats them. */
        private static void assertRange(JsonNode field, String key, ToIntFunction<Map<String, Object>> run) {
            assertThat(field.has("minimum")).as(key + " has a minimum").isTrue();
            assertThat(field.has("maximum")).as(key + " has a maximum").isTrue();
            int min = field.path("minimum").asInt();
            int max = field.path("maximum").asInt();

            assertThat(run.applyAsInt(Map.of())).isEqualTo(field.path("default").asInt());
            assertThat(run.applyAsInt(Map.of(key, min))).isEqualTo(min);
            assertThat(run.applyAsInt(Map.of(key, max))).isEqualTo(max);
            assertThatThrownBy(() -> run.applyAsInt(Map.of(key, min - 1))).isInstanceOf(CompressionException.class);
            assertThatThrownBy(() -> run.applyAsInt(Map.of(key, max + 1))).isInstanceOf(CompressionException.class);
        }

        private static JsonNode shippedJson() {
            try (InputStream in = shippedStream()) {
                return new ObjectMapper().readTree(in);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static InputStream shippedStream() {
            return BlockConfigSchema.class.getClassLoader().getResourceAsStream(BlockConfigSchema.RESOURCE);
        }
    }

    @Nested
    class Parse {

        @Test
        void assemblesKeysInTheEditorOrder() throws IOException {
            var schema = parse(MINI);

            // own keys, then AI keys, then common ones, last on_failure; the first source keeps duplicates
            assertThat(schema.fieldsFor("simple")).containsOnlyKeys("command", "model", "provider", "label", "on_failure");
            assertThat(List.copyOf(schema.fieldsFor("simple").keySet()))
                    .containsExactly("command", "model", "provider", "label", "on_failure");
            assertThat(schema.fieldsFor("simple").get("model").legacyFor()).isEqualTo("x");
            assertThat(schema.fieldsFor("simple").get("provider").ignoreCase()).isTrue();
            assertThat(schema.fieldsFor("simple").get("on_failure").values()).containsExactly("stop", "continue");
        }

        @Test
        void aNonAiType_getsNoAiKeys() throws IOException {
            var schema = parse(MINI);

            assertThat(List.copyOf(schema.fieldsFor("loop").keySet()))
                    .containsExactly("mode", "image", "label", "provider", "on_failure");
            assertThat(schema.fieldsFor("loop").get("provider").values()).isEmpty();
            assertThat(schema.requiredKeys("loop")).isEmpty();
        }

        @Test
        void resolvesReferencesToSharedFields() throws IOException {
            var schema = parse(MINI);

            var mode = schema.fieldsFor("loop").get("mode");
            assertThat(mode.key()).isEqualTo("mode");
            assertThat(mode.values()).containsExactly("ai", "shell");
            assertThat(mode.legacy()).isEqualTo("claude");
            assertThat(schema.fieldsFor("loop").get("image").onlyWith()).containsExactly(Map.entry("mode", "shell"));
        }

        @Test
        void anUnknownReference_failsLoudly() {
            String json = """
                    {"blockTypes": {"loop": {"fields": {"mode": "#missing"}}}}
                    """;

            assertThatThrownBy(() -> parse(json))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("#missing").hasMessageContaining("mode");
        }

        @Test
        void aSchemaWithoutBlockTypes_failsLoudly() {
            assertThatThrownBy(() -> parse("{\"blockTypes\": {}}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no block types");
        }

        @Test
        void returnedCollections_areUnmodifiable() throws IOException {
            var schema = parse(MINI);

            assertThatThrownBy(() -> schema.blockTypes().add("new")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> schema.ownKeys("simple").add("new")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> schema.requiredKeys("simple").add("new")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> schema.aiBlockTypes().add("new")).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
