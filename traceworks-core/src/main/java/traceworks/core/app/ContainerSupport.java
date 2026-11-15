package traceworks.core.app;

import traceworks.core.port.BuiltInImageSource;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.HostDirectories;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the use cases need of the machine to run steps in containers.
 *
 * @param runtime     finds the container runtime ({@code podman} or {@code docker}); empty when the machine
 *                    has none. Asked when a use case needs it: at the start of a run, a resume and a cancel
 * @param builtIns    the built-in images, whose tags the run resolves the {@code shell} image and the AI images to
 * @param directories the real paths of the run's own directories, and whether they may be mounted
 */
public record ContainerSupport(Supplier<Optional<ContainerRuntime>> runtime, BuiltInImageSource builtIns,
                               HostDirectories directories) {

    public ContainerSupport {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(builtIns, "builtIns");
        Objects.requireNonNull(directories, "directories");
    }
}
