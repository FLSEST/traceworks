package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether running a block list can call an AI model, by the rules the executors follow:
 *
 * <ul>
 * <li>{@code simple}, {@code agentic} and {@code compress-ai} always do.</li>
 * <li>{@code foreach}, {@code while}, {@code conditional} and {@code retry} do unless they carry
 *     {@code execution_mode: shell} ({@link ExecutionMode}). An unknown value counts as AI.</li>
 * <li>{@code switch} only compares; a case block does when it is a {@code simple} block without
 *     {@code execution_mode: shell} (the switch runs nothing but shell and simple blocks).</li>
 * <li>{@code sub-workflow} counts as a model call: the workflow it starts is not part of this definition, so nothing
 *     here can promise that it runs without AI. The same holds for a {@code foreach} with {@code workflow_id},
 *     whatever its {@code execution_mode}.</li>
 * </ul>
 *
 * <p>Only the engine's own provider calls are visible here; a shell command that starts an AI CLI by itself is not.
 */
public final class ModelUse {

    private static final Set<String> MODEL_TYPES = Set.of("simple", "agentic", "compress-ai");
    private static final Set<String> COMMAND_WRAPPERS = Set.of("foreach", "while", "conditional", "retry");

    private ModelUse() {
    }

    /** True when at least one block of the list can call an AI model. */
    public static boolean needsAi(List<BlockDefinition> blocks) {
        if (blocks == null) {
            return false;
        }
        for (BlockDefinition block : blocks) {
            if (block != null && callsModel(block.getType(), block.getConfig())) {
                return true;
            }
        }
        return false;
    }

    private static boolean callsModel(String type, Map<String, Object> config) {
        if (type == null) {
            return false;
        }
        if (MODEL_TYPES.contains(type)) {
            return true;
        }
        if ("sub-workflow".equals(type) || startsWorkflowPerItem(type, config)) {
            return true;
        }
        if (COMMAND_WRAPPERS.contains(type)) {
            return !runsAsShell(config);
        }
        return "switch".equals(type) && aCaseBlockCallsModel(config);
    }

    private static boolean startsWorkflowPerItem(String type, Map<String, Object> config) {
        return "foreach".equals(type) && config != null
                && config.get("workflow_id") instanceof String id && !id.isBlank();
    }

    /** Exactly the blocks a switch can run: {@code cases[].blocks} and {@code default}. */
    private static boolean aCaseBlockCallsModel(Map<String, Object> switchConfig) {
        if (switchConfig == null) {
            return false;
        }
        List<Object> runnable = new ArrayList<>();
        if (switchConfig.get("cases") instanceof List<?> cases) {
            for (Object entry : cases) {
                if (entry instanceof Map<?, ?> caseEntry && caseEntry.get("blocks") instanceof List<?> blocks) {
                    runnable.addAll(blocks);
                }
            }
        }
        if (switchConfig.get("default") instanceof List<?> defaultBlocks) {
            runnable.addAll(defaultBlocks);
        }
        for (Object item : runnable) {
            if (item instanceof Map<?, ?> nested && "simple".equals(nested.get("type"))
                    && !runsAsShell(nested.get("config") instanceof Map<?, ?> config ? asConfig(config) : null)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asConfig(Map<?, ?> config) {
        return (Map<String, Object>) config;
    }

    private static boolean runsAsShell(Map<String, Object> config) {
        try {
            return ExecutionMode.isShell(config);
        } catch (IllegalArgumentException unknownMode) {
            return false;
        }
    }
}
