package traceworks.core.analyzer;

import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.model.BlockDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the texts of a block configuration, flat and recursive.
 *
 * <p>The analysis has to see nested blocks (switch cases, a dead {@code blocks} list) too: an absolute path or a
 * never-assigned placeholder is just as much an error there as at the top level. Some rules only need the direct
 * values of a block, hence both.
 */
final class BlockText {

    private BlockText() {}

    /** Protection against pathologically deep configs; real definitions stay far below it. */
    private static final int MAX_DEPTH = 12;

    /** Keys whose text runs in the shell when the block is a shell block or in shell mode. */
    static final List<String> SHELL_KEYS =
            List.of("command", "condition", "condition_command", "then_command", "else_command");

    /** Keys that always run in the shell: the foreach item list and the pre-test {@code run_if}. */
    static final List<String> ALWAYS_SHELL_KEYS = List.of("items_command", "run_if");

    /** All strings of the config, nested ones included. */
    static List<String> allStrings(BlockDefinition block) {
        List<String> out = new ArrayList<>();
        collect(block == null ? null : block.getConfig(), out, 0);
        return out;
    }

    /** The combined text of a block, for searches where the structure does not matter. */
    static String joined(BlockDefinition block) {
        return String.join("\n", allStrings(block));
    }

    /** The value of a direct config key as text, or {@code null}. */
    static String directValue(BlockDefinition block, String key) {
        if (block == null || block.getConfig() == null) return null;
        Object value = block.getConfig().get(key);
        return value instanceof String text ? text : null;
    }

    /**
     * Where a block passes its task to the AI: per type the keys its executor reads. Only calls that receive
     * {@code workflow.instructions} as system prompt, so not the condition of conditional and not compress-ai.
     */
    static final Map<String, List<String>> PROMPT_KEYS = Map.of(
            "simple", List.of("command"),
            "agentic", List.of("goal"),
            "retry", List.of("command"),
            "foreach", List.of("command"),
            "while", List.of("command"),
            "conditional", List.of("then_command", "else_command"));

    /** Types whose command runs as a shell with {@code execution_mode: "shell"} instead of as an AI task. */
    private static final Set<String> SHELL_SWITCHABLE = Set.of("retry", "foreach", "while", "conditional");

    /**
     * The task texts a block sends to the AI together with {@code workflow.instructions}, in the order of
     * {@link #PROMPT_KEYS}. Empty for shell commands, questions to humans (approval, human-input) and blocks without
     * an AI call.
     */
    static List<String> instructions(BlockDefinition block) {
        if (block == null || block.getType() == null || block.getConfig() == null) return List.of();
        if (SHELL_SWITCHABLE.contains(block.getType()) && runsAsShell(block.getConfig())) return List.of();
        return promptTexts(block.getType(), block.getConfig());
    }

    /**
     * The task texts of the nested blocks per block name (without a name: per type), in definition order. Walked by
     * the same walker as the type and {@code execution_mode} checks ({@link NestedBlocks}). A simple block in a switch
     * case gets {@code workflow.instructions} as system prompt just the same; with {@code execution_mode: "shell"} it
     * runs as a shell.
     */
    static Map<String, List<String>> nestedInstructions(BlockDefinition block) {
        Map<String, List<String>> texts = new LinkedHashMap<>();
        if (block == null) return texts;
        NestedBlocks.visit(block.getConfig(), nested -> {
            if (nested.type() == null || nested.config() == null || runsAsShell(nested.config())) return;
            List<String> prompts = promptTexts(nested.type(), nested.config());
            if (!prompts.isEmpty()) texts.computeIfAbsent(nested.label(), label -> new ArrayList<>()).addAll(prompts);
        });
        return texts;
    }

    /**
     * Whether the config says {@code execution_mode: "shell"}. Unlike {@link ExecutionMode#isShell} it never throws:
     * an unknown value is {@code invalid-config-value}'s finding, not a reason for the analysis to fail.
     */
    static boolean runsAsShell(Map<String, Object> config) {
        return config != null && ExecutionMode.SHELL.equals(config.get(ExecutionMode.KEY));
    }

    /**
     * The shell texts of one config: {@link #ALWAYS_SHELL_KEYS}, and {@link #SHELL_KEYS} when the block is a shell
     * block or in shell mode. Only non-blank texts.
     */
    static List<String> shellTexts(String type, Map<String, Object> config) {
        List<String> out = new ArrayList<>();
        if (config == null) return out;
        List<String> keys = new ArrayList<>(ALWAYS_SHELL_KEYS);
        if ("shell".equals(type) || runsAsShell(config)) keys.addAll(SHELL_KEYS);
        for (String key : keys) {
            if (config.get(key) instanceof String text && !text.isBlank()) out.add(text);
        }
        return out;
    }

    private static List<String> promptTexts(String type, Map<String, Object> config) {
        List<String> texts = new ArrayList<>();
        for (String key : PROMPT_KEYS.getOrDefault(type, List.of())) {
            if (config.get(key) instanceof String text) texts.add(text);
        }
        return texts;
    }

    private static void collect(Object value, List<String> out, int depth) {
        if (value == null || depth > MAX_DEPTH) return;
        if (value instanceof String text) {
            out.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, out, depth + 1));
        } else if (value instanceof Collection<?> items) {
            items.forEach(item -> collect(item, out, depth + 1));
        }
    }
}
