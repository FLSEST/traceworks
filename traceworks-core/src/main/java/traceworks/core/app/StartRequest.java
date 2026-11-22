package traceworks.core.app;

import traceworks.core.model.ProjectKey;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What starts a run.
 *
 * @param packageLocation    the package directory or its {@code workflow.json}
 * @param inputs             the run's inputs; {@code package_dir} is reserved, the engine sets it
 * @param workspace          the directory the steps work in; {@code null} for none
 * @param allowHostExecution whether the operator allows this run to be a host run ({@link RunTarget#HOST}), in
 *                           addition to the setting; a run is a host run only with the consent and where no
 *                           container runtime works
 * @param interactive        whether a person can answer questions in this process; without, a top-level question
 *                           parks the run
 * @param source             the git repository the run works in a checkout of ({@code --source}): an absolute path
 *                           or a URL, checked out into the run's own directory, which is then its workspace;
 *                           {@code null} for none
 * @param inputsFrom the run whose recorded inputs this run takes, by id or prefix; an explicit input overrides;
 *                   {@code null} for none
 * @param ref                the branch, tag or commit of {@code source} to check out; {@code null} for its
 *                           {@code HEAD}
 * @param attended           whether the run counts as attended for the operator's {@code require_seal: unattended}:
 *                           Traceworks has a terminal, or the command line carries the operator's flag for an
 *                           unsealed package
 * @param project            the key of the project the run works on, the operator's word ({@link ProjectKey}),
 *                           from the command line or from the home's {@code projects} setting, which whoever
 *                           builds the request resolves; {@code null} for none. The run of an audit workflow needs
 *                           one: its register is that of its project
 * @param agent              the agent that starts the run under the actor, by the name it gave itself
 *                           ({@link traceworks.core.register.DecisionAgent}); {@code null} for a start no agent
 *                           claims. A name that is none refuses the run
 * @throws IllegalArgumentException if both a workspace and a source are given, a ref without a source, or a project
 *                                  that is no project key
 */
public record StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace,
                           boolean allowHostExecution, boolean interactive, String source, String ref,
                           String inputsFrom, boolean attended, String project, String agent) {

    /** A request no agent claims. */
    public StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace, boolean allowHostExecution,
                        boolean interactive, String source, String ref, String inputsFrom, boolean attended,
                        String project) {
        this(packageLocation, inputs, workspace, allowHostExecution, interactive, source, ref, inputsFrom, attended,
                project, null);
    }

    /** A request of a run that names no project. */
    public StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace, boolean allowHostExecution,
                        boolean interactive, String source, String ref, String inputsFrom, boolean attended) {
        this(packageLocation, inputs, workspace, allowHostExecution, interactive, source, ref, inputsFrom, attended,
                null);
    }

    /** A request of a run nobody attends, as far as {@code require_seal} asks. */
    public StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace, boolean allowHostExecution,
                        boolean interactive, String source, String ref, String inputsFrom) {
        this(packageLocation, inputs, workspace, allowHostExecution, interactive, source, ref, inputsFrom, false);
    }

    /** A request without {@code --inputs-from}. */
    public StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace, boolean allowHostExecution,
                        boolean interactive, String source, String ref) {
        this(packageLocation, inputs, workspace, allowHostExecution, interactive, source, ref, null);
    }

    public StartRequest {
        inputs = inputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        if (source != null && workspace != null) {
            throw new IllegalArgumentException("A run works in a workspace or in a checkout of a source, not both");
        }
        if (ref != null && source == null) {
            throw new IllegalArgumentException("A ref needs a source to check it out of");
        }
        if (project != null) {
            ProjectKey.checked(project);
        }
    }

    /** The same request for the project {@code key}; {@code null} for a run that names none. */
    public StartRequest inProject(String key) {
        return new StartRequest(packageLocation, inputs, workspace, allowHostExecution, interactive, source, ref,
                inputsFrom, attended, key, agent);
    }

    /** The same request, asked for by {@code agent} under the actor; {@code null} for a start no agent claims. */
    public StartRequest by(String agent) {
        return new StartRequest(packageLocation, inputs, workspace, allowHostExecution, interactive, source, ref,
                inputsFrom, attended, project, agent);
    }

    /** A run in {@code workspace}, without a source. */
    public StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace, boolean allowHostExecution,
                        boolean interactive) {
        this(packageLocation, inputs, workspace, allowHostExecution, interactive, null, null);
    }
}
