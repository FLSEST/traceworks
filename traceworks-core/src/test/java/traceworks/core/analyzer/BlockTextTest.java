package traceworks.core.analyzer;

import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlockTextTest {

    private static BlockDefinition block(Map<String, Object> config) {
        return block("simple", config);
    }

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setConfig(config);
        return b;
    }

    @Test
    void nullBlockAndNullConfig_areEmpty() {
        assertThat(BlockText.allStrings(null)).isEmpty();
        assertThat(BlockText.joined(block(null))).isEmpty();
        assertThat(BlockText.directValue(null, "x")).isNull();
        assertThat(BlockText.directValue(block(null), "x")).isNull();
        assertThat(BlockText.instructions(null)).isEmpty();
        assertThat(BlockText.instructions(block(null))).isEmpty();
        assertThat(BlockText.instructions(block(null, Map.of("command", "x")))).isEmpty();
    }

    @Test
    void collectsStringsFromMapsListsAndSets_inOrder() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("command", "one");
        config.put("timeout_seconds", 30);
        config.put("cases", List.of(Map.of("blocks", List.of(Map.of("command", "two")))));
        config.put("tags", Set.of("three"));

        assertThat(BlockText.allStrings(block(config))).containsExactly("one", "two", "three");
        assertThat(BlockText.joined(block(config))).isEqualTo("one\ntwo\nthree");
    }

    @Test
    void depthBeyondTwelveLevels_isCutOff() {
        Object allowed = "ok";
        for (int i = 0; i < 11; i++) {
            allowed = List.of(allowed);
        }
        Object tooDeep = "gone";
        for (int i = 0; i < 12; i++) {
            tooDeep = List.of(tooDeep);
        }
        // + 1 level for the config map itself
        assertThat(BlockText.allStrings(block(Map.of("a", allowed)))).containsExactly("ok");
        assertThat(BlockText.allStrings(block(Map.of("a", tooDeep)))).isEmpty();
    }

    @Test
    void aCyclicConfig_terminates() {
        List<Object> self = new ArrayList<>();
        self.add("x");
        self.add(self);
        assertThat(BlockText.allStrings(block(Map.of("loop", self)))).isNotEmpty().allMatch("x"::equals);
    }

    @Test
    void directValue_onlyForStrings() {
        BlockDefinition b = block(Map.of("command", "ls", "timeout_seconds", 10));
        assertThat(BlockText.directValue(b, "command")).isEqualTo("ls");
        assertThat(BlockText.directValue(b, "timeout_seconds")).isNull();
        assertThat(BlockText.directValue(b, "missing")).isNull();
    }

    /** Per type the key the executor reads: not prompt before command, goal for agentic. */
    @Test
    void instructions_readPerTypeTheKeyOfTheExecutor() {
        assertThat(BlockText.instructions(block(Map.of("prompt", "p", "command", "c")))).containsExactly("c");
        assertThat(BlockText.instructions(block(Map.of("command", 1)))).isEmpty();
        assertThat(BlockText.instructions(block("agentic", Map.of("goal", "g", "success_criteria", "s"))))
                .containsExactly("g");
        assertThat(BlockText.instructions(block("conditional",
                Map.of("condition", "x", "then_command", "t", "else_command", "e")))).containsExactly("t", "e");
        assertThat(BlockText.instructions(block("foreach", Map.of("command", "Summarise {{ item }}",
                "execution_mode", "ai")))).containsExactly("Summarise {{ item }}");
    }

    /** What runs as shell or asks a human does not go to the AI with workflow.instructions. */
    @Test
    void shellCommandsAndQuestionsToHumans_areNoAiTask() {
        assertThat(BlockText.instructions(block("shell", Map.of("command", "ls")))).isEmpty();
        assertThat(BlockText.instructions(block("foreach", Map.of("command", "wc -l {{ item }}",
                "execution_mode", "shell")))).isEmpty();
        assertThat(BlockText.instructions(block("human-input", Map.of("prompt", "Which language?")))).isEmpty();
        assertThat(BlockText.instructions(block("approval", Map.of("question", "Deploy?")))).isEmpty();
        assertThat(BlockText.instructions(block("compress-ai", Map.of("source", "previous",
                "instruction", "Shorten")))).isEmpty();
    }

    /**
     * The tasks of nested blocks, through the same walker as the type and execution_mode check (switch cases,
     * default, a blocks list): per block name, otherwise per type.
     */
    @Test
    void nestedInstructions_readNestedBlocksPerType() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("expression", "{{ env }}");
        config.put("cases", List.of(
                Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "name", "notes", "config", Map.of("command", "Notes")),
                        Map.of("type", "shell", "config", Map.of("command", "ls")))),
                Map.of("value", "b", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "as shell", "execution_mode", "shell"))))));
        config.put("default", List.of(Map.of("type", "simple", "config", Map.of("command", "Default"))));

        assertThat(BlockText.nestedInstructions(block("switch", config)))
                .containsExactly(Map.entry("notes", List.of("Notes")), Map.entry("simple", List.of("Default")));
        assertThat(BlockText.instructions(block("switch", config))).isEmpty();
        assertThat(BlockText.nestedInstructions(null)).isEmpty();
        assertThat(BlockText.nestedInstructions(block("simple", Map.of("command", "x")))).isEmpty();
    }

    /**
     * The prompt keys are config keys of their type in the block config schema, and every type has a schema: the
     * analyzer and the schema cannot drift apart.
     */
    @Test
    void promptKeys_areConfigKeysOfTheirTypeInTheSchema() {
        BlockConfigSchema schema = BlockConfigSchema.get();
        BlockText.PROMPT_KEYS.forEach((type, keys) -> {
            assertThat(schema.knowsType(type)).as("schema for %s", type).isTrue();
            assertThat(schema.ownKeys(type)).as("keys of %s", type).containsAll(keys);
        });
    }
}
