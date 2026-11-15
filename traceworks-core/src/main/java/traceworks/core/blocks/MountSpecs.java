package traceworks.core.blocks;

import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.port.CommandRunner.Mount;

import java.util.ArrayList;
import java.util.List;

/**
 * The block's own container mounts, {@code mounts} in the syntax {@code host:container:ro|rw}. The mode is always
 * written out: Docker would make a mount without one writable, Traceworks does not guess. The standard mounts
 * (workspace, package, scratch) are the run's command runner's ({@code core.container.RunPaths}); whether a host path
 * may be mounted at all is the container adapter's decision. A block only declares what it needs.
 */
final class MountSpecs {

    private static final String SYNTAX = "host:container:ro|rw";

    private MountSpecs() {
    }

    /**
     * The mounts of resolved {@code mounts} entries; blank entries are skipped.
     *
     * @throws WorkflowExecutionException for an entry without a mode, or one that is not {@code host:container:ro|rw}
     */
    static List<Mount> parse(List<String> specs) {
        if (specs == null) return List.of();
        List<Mount> mounts = new ArrayList<>();
        for (String spec : specs) {
            if (spec == null || spec.isBlank()) continue;
            mounts.add(parseEntry(spec));
        }
        return mounts;
    }

    private static Mount parseEntry(String spec) {
        String[] parts = spec.split(":", -1);
        boolean paths = (parts.length == 2 || parts.length == 3) && !parts[0].isBlank() && !parts[1].isBlank();
        if (paths && parts.length == 2) {
            throw new WorkflowExecutionException("Mount '" + spec + "' has no mode: end it with :ro (read-only) or"
                    + " :rw (writable)");
        }
        if (!paths || !(parts[2].equals("ro") || parts[2].equals("rw"))) {
            throw new WorkflowExecutionException("Mount '" + spec + "' must be " + SYNTAX);
        }
        return new Mount(parts[0].strip(), parts[1].strip(), parts[2].equals("ro"));
    }

    /**
     * Refuses {@code mount_docker_socket: true}: Traceworks never mounts the Docker socket into a container, because
     * it gives the container control over the host.
     */
    static void refuseDockerSocket(boolean requested) {
        if (requested) {
            throw new WorkflowExecutionException("'mount_docker_socket' is not supported: Traceworks never mounts the "
                    + "Docker socket into a container");
        }
    }
}
