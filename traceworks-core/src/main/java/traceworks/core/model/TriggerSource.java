package traceworks.core.model;

import java.util.Map;

/** What started a workflow run. Recorded on every run (provenance). */
public enum TriggerSource {
    /** A person started the run from the command line. */
    MANUAL(false),
    /** A person resumed a parked or interrupted run. */
    RESUME(false),
    /** Another program started the run: an embedding application or the MCP server. */
    API(false),
    /** The run is an entry of a chain; its origin is the source that started the chain. */
    CHAIN(true),
    /** The run is a sub-workflow; its origin is the source of the parent run. */
    SUB_WORKFLOW(true);

    /** Reserved context key carrying the parent's effective source into chains and sub-workflows. */
    public static final String PARENT_CONTEXT_KEY = "__parent_trigger_source";

    /**
     * Context key carrying the chain run into its entry runs: the placeholder {@code {{ chain_run_id }}}.
     * Set by the chain executor after the chain's own input, so it is never taken from the input.
     */
    public static final String CHAIN_RUN_CONTEXT_KEY = "chain_run_id";

    private final boolean inheritsFromParent;

    TriggerSource(boolean inheritsFromParent) {
        this.inheritsFromParent = inheritsFromParent;
    }

    /** True for the sources whose origin is the source of the parent run (chains and sub-workflows). */
    public boolean inheritsFromParent() {
        return inheritsFromParent;
    }

    /**
     * The source that started the outermost run: the source itself, or, for chains and sub-workflows, the
     * parent's source carried in {@link #PARENT_CONTEXT_KEY}. Returns null when that is missing or unreadable.
     */
    public TriggerSource effective(Map<String, ?> inputContext) {
        if (!inheritsFromParent) {
            return this;
        }
        TriggerSource parent = parse(inputContext != null ? inputContext.get(PARENT_CONTEXT_KEY) : null);
        return parent != null && !parent.inheritsFromParent ? parent : null;
    }

    /** Effective source of a running workflow, read from its context variables (see {@code trigger_source}). */
    public static TriggerSource effectiveOfRun(Map<String, ?> runVariables) {
        TriggerSource own = parse(runVariables.get("trigger_source"));
        return own != null ? own.effective(runVariables) : null;
    }

    /**
     * The chain run a new run belongs to: {@link #CHAIN_RUN_CONTEXT_KEY} from the context, only for
     * {@link #CHAIN}, the one source the chain executor starts with. Any other run may carry the key (a resumed
     * run keeps its context, a sub-workflow inherits its parent's), but it was not started by that chain run:
     * {@code null}.
     */
    public static String chainRunIdOf(TriggerSource source, Map<String, ?> inputContext) {
        if (source != CHAIN || inputContext == null) {
            return null;
        }
        return inputContext.get(CHAIN_RUN_CONTEXT_KEY) instanceof String id && !id.isBlank() ? id : null;
    }

    /** The source of that name, or {@code null} for {@code null} and for a name that is no source. */
    public static TriggerSource parse(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
