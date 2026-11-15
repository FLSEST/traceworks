package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code execution_mode} of foreach, while, conditional, retry and switch blocks (and blocks nested in switch
 * cases): {@code "ai"} (the provider comes from {@code provider} on the block or workflow) or {@code "shell"}. Any
 * other value is rejected: a typo like {@code "shel"} must not silently run as AI. That includes the legacy alias
 * {@code "claude"} for {@code "ai"}: a legacy alias is never accepted (docs/workflow-format.md).
 *
 * <p>{@link #isShell} reads a missing value as {@code "ai"}. The blocks of {@link #REQUIRED_FOR} must write the
 * value out ({@link #isMissing}): read as {@code "ai"}, a missing value would make a condition ask a model although
 * the workflow never said so. The run admission refuses such a workflow, and the blocks
 * themselves fail with {@link #REQUIRED_MESSAGE}.
 */
public final class ExecutionMode {

    public static final String KEY = "execution_mode";
    public static final String AI = "ai";
    public static final String SHELL = "shell";
    /**
     * A legacy name of {@link #AI} in existing workflows, from before the provider was selectable. Refused like any
     * unknown value; write {@link #AI}.
     */
    public static final String LEGACY_CLAUDE = "claude";
    /** Why a block that must write out its execution mode fails without one. */
    public static final String REQUIRED_MESSAGE = "execution_mode is required (shell or ai)";
    /**
     * The block types that must write out their execution mode; a {@code foreach} with {@code workflow_id} runs no
     * command per item and needs none.
     */
    public static final Set<String> REQUIRED_FOR = Set.of("conditional", "retry", "while", "foreach");

    private static final Set<String> VALID = Set.of(AI, SHELL);

    private ExecutionMode() {
    }

    /**
     * @return true for {@code "shell"}; false for {@code "ai"} or no value
     * @throws IllegalArgumentException for any other value
     */
    public static boolean isShell(Map<String, Object> config) {
        Object raw = config == null ? null : config.get(KEY);
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof String value) || !VALID.contains(value)) {
            throw new IllegalArgumentException(invalidMessage(raw));
        }
        return SHELL.equals(value);
    }

    /**
     * Whether {@code config} writes out an {@code execution_mode}. Any value counts; {@link #isShell} rejects an
     * unknown one.
     */
    public static boolean isSet(Map<String, Object> config) {
        return config != null && config.get(KEY) != null;
    }

    /**
     * Whether a block of {@code type} must write out its {@code execution_mode} and {@code config} does not: one of
     * {@link #REQUIRED_FOR} without a value, except a {@code foreach} with a {@code workflow_id}.
     */
    public static boolean isMissing(String type, Map<String, Object> config) {
        if (type == null || !REQUIRED_FOR.contains(type) || isSet(config)) {
            return false;
        }
        return !("foreach".equals(type) && config != null
                && config.get("workflow_id") instanceof String workflowId && !workflowId.isBlank());
    }

    /**
     * What is wrong with the {@code execution_mode} of one config: the message for an unknown value, {@code null} for
     * {@code "ai"}, {@code "shell"} or no value.
     */
    public static String unknownValue(Map<String, Object> config) {
        Object raw = config == null ? null : config.get(KEY);
        return raw == null || raw instanceof String value && VALID.contains(value) ? null : invalidMessage(raw);
    }

    /**
     * Validates {@code execution_mode} in every block, including nested ones: switch {@code cases[].blocks} and
     * {@code default} as well as a plain {@code blocks} list. The tree is walked by {@link NestedBlocks}, the same
     * walker the block-type check uses.
     *
     * @throws IllegalArgumentException naming the first block with an unknown value
     */
    public static void rejectUnknown(List<BlockDefinition> blocks) {
        if (blocks == null) {
            return;
        }
        for (BlockDefinition block : blocks) {
            if (block != null) {
                checkConfig(block.getName() != null ? block.getName() : block.getType(), block.getConfig());
                NestedBlocks.visit(block.getConfig(), nested -> checkConfig(nested.label(), nested.config()));
            }
        }
    }

    private static void checkConfig(String blockLabel, Map<String, Object> config) {
        String problem = unknownValue(config);
        if (problem != null) {
            throw new IllegalArgumentException("Block '" + blockLabel + "': " + problem);
        }
    }

    private static String invalidMessage(Object raw) {
        return "unknown " + KEY + " '" + raw + "' — use \"" + AI + "\" (provider from the block or workflow) or \""
                + SHELL + "\"";
    }
}
