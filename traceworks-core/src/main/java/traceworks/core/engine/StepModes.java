package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a step uses AI, the fact behind the "deterministic" or "AI" badge of a step in the run record.
 *
 * <p>It is a property of the block as written: {@code agentic} and {@code compress-ai} always use AI, {@code simple}
 * and the control-flow blocks ({@code conditional}, {@code retry}, {@code while}, {@code foreach}) use it unless
 * {@code execution_mode} is {@code shell}, and a {@code foreach} with {@code workflow_id} and no
 * {@code execution_mode} runs a workflow per item and asks no model itself. A {@code switch} uses AI when any of its
 * case or default blocks would, by the same rules. {@code approval} and {@code human-input} use it when the
 * {@code actor} is {@code ai} or {@code shadow}, or still a placeholder, which may resolve to either. Every other
 * block is deterministic. A block with an unknown {@code execution_mode} counts as deterministic: the step fails
 * before it calls anything.
 */
public final class StepModes {

    private static final Set<String> ALWAYS_AI = Set.of("agentic", "compress-ai");
    private static final Set<String> AI_UNLESS_SHELL = Set.of("simple", "conditional", "retry", "while", "foreach");
    private static final Set<String> DECISIONS = Set.of("approval", "human-input");
    private static final String ACTOR_KEY = "actor";

    private StepModes() {
    }

    public static boolean usesAi(BlockDefinition block) {
        return usesAi(block.getType(), block.getConfig());
    }

    private static boolean usesAi(String type, Map<String, Object> config) {
        if (type == null) {
            return false;
        }
        if (ALWAYS_AI.contains(type)) {
            return true;
        }
        if ("foreach".equals(type) && config != null && !ExecutionMode.isSet(config)
                && config.get("workflow_id") instanceof String id && !id.isBlank()) {
            return false;   // one workflow run per item, whose own steps carry their badges
        }
        if (AI_UNLESS_SHELL.contains(type)) {
            // An unknown execution_mode fails the step before it runs anything, so it uses no AI
            return ExecutionMode.unknownValue(config) == null && !ExecutionMode.isShell(config);
        }
        if ("switch".equals(type)) {
            return config != null && switchUsesAi(config);
        }
        if (DECISIONS.contains(type)) {
            return config != null && config.get(ACTOR_KEY) instanceof String actor
                    && ("ai".equalsIgnoreCase(actor) || "shadow".equalsIgnoreCase(actor) || actor.contains("{{"));
        }
        return false;
    }

    /** Whether a case or the default of a switch has a block that would use AI. */
    private static boolean switchUsesAi(Map<String, Object> config) {
        if (config.get("cases") instanceof List<?> cases) {
            for (Object entry : cases) {
                if (entry instanceof Map<?, ?> caseEntry && anyUsesAi(caseEntry.get("blocks"))) {
                    return true;
                }
            }
        }
        return anyUsesAi(config.get("default"));
    }

    @SuppressWarnings("unchecked")
    private static boolean anyUsesAi(Object blocks) {
        if (!(blocks instanceof List<?> list)) {
            return false;
        }
        for (Object block : list) {
            if (block instanceof Map<?, ?> map && map.get("type") instanceof String type
                    && usesAi(type, map.get("config") instanceof Map<?, ?> config
                    ? (Map<String, Object>) config : Map.of())) {
                return true;
            }
        }
        return false;
    }
}
