package traceworks.core.ai;

import java.util.Locale;
import java.util.Map;

/**
 * What an AI call may do besides answering: the {@code tools} key of a {@code simple} or
 * {@code agentic} block. Every other AI call (an AI condition, {@code compress-ai}, an AI actor, the AI step of a
 * wrapper) runs with {@link #NONE}. Each CLI adapter turns a policy into its own tool flags.
 */
public enum ToolPolicy {

    /** No tools: the model only answers. The default. */
    NONE,
    /** Reading and searching files. */
    READ,
    /** Reading, searching and changing files. */
    WRITE,
    /** Reading, searching and changing files, and running shell commands. */
    SHELL;

    /** The block key of the tool policy, on {@code simple} and {@code agentic}. */
    public static final String KEY = "tools";

    /** The values a workflow may write, as a phrase for messages. */
    public static final String VALUES = "none, read, write or shell";

    /**
     * The policy a block's config names in {@value #KEY}: the one parse path, for the executors, the run admission and
     * the analyzer alike.
     *
     * @param config the block's config; {@code null} for none
     * @return {@link #NONE} when the key is absent
     * @throws IllegalArgumentException for a value {@link #parse} refuses
     */
    public static ToolPolicy of(Map<String, Object> config) {
        return parse(config == null ? null : config.get(KEY));
    }

    /** The value as a workflow writes it: the lowercase name. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The policy a block's {@code tools} value names, compared ignoring case and edge spaces.
     *
     * @param configured the value of {@code tools}; {@code null} when the key is absent
     * @return {@link #NONE} for {@code null}
     * @throws IllegalArgumentException for any other value: a string outside the four, a placeholder (never
     *                                  resolved: a run's data must not widen what its model may do), or no string
     */
    public static ToolPolicy parse(Object configured) {
        if (configured == null) {
            return NONE;
        }
        if (configured instanceof String text) {
            String value = text.strip().toLowerCase(Locale.ROOT);
            for (ToolPolicy policy : values()) {
                if (policy.value().equals(value)) {
                    return policy;
                }
            }
        }
        throw new IllegalArgumentException("tools: " + shown(configured) + " is not one of " + VALUES);
    }

    private static String shown(Object configured) {
        return configured instanceof String text ? "\"" + text + "\"" : String.valueOf(configured);
    }
}
