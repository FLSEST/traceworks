package traceworks.core.engine;

import java.util.Set;

/**
 * The variable names that only the system around a run sets, never a workflow or an input
 * (docs/workflow-format.md): the platform data ({@code open_todos}, {@code previous_report}, ...), the goal guard
 * variables ({@value #GOAL_GUARD_PREFIX}...) and the isolated-run paths ({@value #EXECUTION_PATH_PREFIX}...). A run
 * never takes
 * one of them from an input: a value handed in, or handed over by a parent run, would otherwise become a trusted
 * variable, unquoted in shell blocks, although platform data is untrusted. Traceworks sets only the register's data
 * of an audit workflow; a workflow that names another of them keeps the placeholder.
 *
 * <p>{@code audit_items} is in the list because a register sets it (docs/registers.md, "Items"), and for the same
 * reason no input may.
 *
 * <p>The one list: the context factory ignores these names in an input, and a {@code foreach} {@code context} entry
 * may not name them, because the child would drop the value.
 */
public final class PlatformVariables {

    /** The platform data names. */
    public static final Set<String> NAMES = Set.of("previous_report", "open_todos", "request_metrics",
            "audit_findings", "audit_items", "todo_details", "goal_status");

    /** Prefix of the goal guard variables ({@code goal_guard_status}, {@code goal_guard_item_1}, ...). */
    public static final String GOAL_GUARD_PREFIX = "goal_guard_";

    /** Prefix of the paths of isolated run workspaces ({@code execution_path_<name>}); Traceworks sets none. */
    public static final String EXECUTION_PATH_PREFIX = "execution_path_";

    private PlatformVariables() {
    }

    /** Whether {@code name} is one of these variables. */
    public static boolean contains(String name) {
        return NAMES.contains(name) || name.startsWith(GOAL_GUARD_PREFIX) || name.startsWith(EXECUTION_PATH_PREFIX);
    }
}
