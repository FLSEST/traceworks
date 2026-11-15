package traceworks.core.app;

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
 * @throws IllegalArgumentException if both a workspace and a source are given, or a ref without a source
 */
public record StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace,
                           boolean allowHostExecution, boolean interactive, String source, String ref,
                           String inputsFrom, boolean attended) {

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
    }

    /** A run in {@code workspace}, without a source. */
    public StartRequest(Path packageLocation, Map<String, String> inputs, Path workspace, boolean allowHostExecution,
                        boolean interactive) {
        this(packageLocation, inputs, workspace, allowHostExecution, interactive, null, null);
    }
}
