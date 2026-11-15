package traceworks.core.blocks;

import traceworks.core.engine.SecretNames;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockSettings;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.Mount;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Where a shell command runs, read from a block's config: with {@code container_image} in that image with the
 * block's {@code mounts}; without one where the run's command runner runs image-less commands (the {@code shell} image
 * in a container run, with the block's {@code mounts}; the host in a host run, without them). The command starts in the project; the runner maps it into the
 * container and adds the workspace, package and scratch mounts, the workspace read-only unless
 * {@code workspace_writable: true}. With {@code network: true} the command gets network access, and the values of the
 * secrets the block declares in {@code secrets} as environment variables. The one place that builds the request of a
 * {@code shell} block and of a wrapper's inner shell step.
 *
 * @param image             the container image, {@code null} for the run's default
 * @param extraMounts       the block's {@code mounts}, parsed; empty for a command on the host
 * @param network           {@code network: true}
 * @param writableWorkspace {@code workspace_writable: true}
 * @param environment       the values of the block's {@code secrets} by name; {@link #toString()} shows the names only
 */
record ShellTarget(String image, List<Mount> extraMounts, boolean network, boolean writableWorkspace,
                   Map<String, String> environment) {

    ShellTarget {
        extraMounts = List.copyOf(extraMounts);
        environment = Map.copyOf(environment);
    }

    /**
     * The target of a block's config. {@code mount_docker_socket: true} is refused first; the mounts are read only
     * when the command runs in a container: with an image, or in a container run, where a command without one runs in
     * the {@code shell} image. On the host they are ignored.
     *
     * @param resolvedMounts the block's {@code mounts} entries with their placeholders resolved
     * @param containerRun   whether this is a container run ({@code ExecutionContext#isContainerRun})
     * @throws WorkflowExecutionException for {@code mount_docker_socket: true} or a malformed mount
     * @throws traceworks.core.model.InvalidSettingException for a setting of the wrong type
     */
    static ShellTarget of(Map<String, Object> config, Supplier<List<String>> resolvedMounts, boolean containerRun) {
        return of(config, resolvedMounts, containerRun, Map.of());
    }

    /**
     * Like {@link #of(Map, Supplier, boolean)}, in a run with secrets.
     *
     * @param runSecrets the values of the run's secrets by name ({@code ExecutionContext#getSecrets})
     * @throws WorkflowExecutionException also for {@code secrets} that are no list of valid names, or name a secret
     *                                    the run has no value for
     */
    static ShellTarget of(Map<String, Object> config, Supplier<List<String>> resolvedMounts, boolean containerRun,
                          Map<String, String> runSecrets) {
        BlockSettings settings = BlockSettings.of("shell", config);
        MountSpecs.refuseDockerSocket(settings.flag("mount_docker_socket"));
        boolean network = settings.flag("network");
        boolean writableWorkspace = settings.flag("workspace_writable");
        String configured = settings.text("container_image");
        Map<String, String> environment = secretEnvironment(config, runSecrets);
        String image = configured != null && !configured.isBlank() ? configured : null;
        List<Mount> extraMounts = image != null || containerRun ? MountSpecs.parse(resolvedMounts.get()) : List.of();
        return new ShellTarget(image, extraMounts, network, writableWorkspace, environment);
    }

    /**
     * The values of the secrets a block's config declares in {@code secrets}, by name: the environment its command
     * gets, in a shell command and in tool mode alike.
     *
     * @throws WorkflowExecutionException for {@code secrets} that are no list of valid names (the run admission
     *                                    refuses them first), or that name a secret the run has no value for
     */
    static Map<String, String> secretEnvironment(Map<String, Object> config, Map<String, String> runSecrets) {
        List<String> problems = SecretNames.problems(config);
        if (!problems.isEmpty()) {
            throw new WorkflowExecutionException(String.join("; ", problems));
        }
        Map<String, String> environment = new TreeMap<>();
        for (String name : SecretNames.of(config)) {
            String value = runSecrets.get(name);
            if (value == null) {
                throw new WorkflowExecutionException("The secret " + name + " has no value in this run: a run takes"
                        + " the secrets its workflow declares, in nested blocks and in the workflows it names"
                        + " literally in workflow_id, when it starts; a child workflow named by a placeholder"
                        + " cannot declare new ones");
            }
            environment.put(name, value);
        }
        return environment;
    }

    /** The request that runs {@code command} here, starting in the project at {@code projectPath} (a host path). */
    CommandRequest request(String command, String projectPath, int timeoutSeconds) {
        return new CommandRequest(command, projectPath, timeoutSeconds, environment, image, extraMounts, network, null,
                null, writableWorkspace);
    }

    /** The target without the values of its secrets, which only their names stand for. */
    @Override
    public String toString() {
        return "ShellTarget[image=" + image + ", extraMounts=" + extraMounts + ", network=" + network
                + ", writableWorkspace=" + writableWorkspace + ", environment=" + new java.util.TreeSet<>(
                environment.keySet()) + "]";
    }

    /** The step metadata {@code executionMode}: {@code shell} or {@code container:<image>}. */
    String executionMode() {
        return image == null ? "shell" : "container:" + image;
    }
}
