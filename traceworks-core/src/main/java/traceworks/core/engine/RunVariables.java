package traceworks.core.engine;

import java.util.Set;

/**
 * The names of the variables the engine sets on every run itself: the one list, for the context factory, which sets
 * them after it has read the inputs so that no input replaces one, and for the analyzer, which knows them as variables
 * a workflow may name without setting them.
 */
public final class RunVariables {

    /** The run variables of every run. */
    public static final Set<String> NAMES = Set.of("workflow_run_id", "workflow_id", "workflow_name",
            "project_path", "execution_path", "scratch_dir", "workspace_source_sha", "model", "provider", "effort",
            "started_at", "run_timestamp", "trigger_source");

    /** Where a step finds the package's files; set on a run that has run paths, which every run of a package has. */
    public static final String PACKAGE_DIR = "package_dir";

    private RunVariables() {
    }
}
