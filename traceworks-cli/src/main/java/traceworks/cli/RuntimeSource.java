package traceworks.cli;

import traceworks.adapters.config.HomeLayout;
import traceworks.adapters.container.RuntimeDetection;
import traceworks.core.port.ContainerRuntime;

import java.util.Objects;
import java.util.Optional;

/**
 * Where the commands get their container runtime from: the machine ({@link #MACHINE}), or one a test gives.
 */
@FunctionalInterface
public interface RuntimeSource {

    /**
     * The runtime to use; empty when there is none.
     *
     * @param configured     {@code container_runtime} of the operator config: {@code podman}, {@code docker}, or
     *                       {@code null} for the first of them that works
     * @param traceworksHome the Traceworks home, whose owner every container runs as, with the places of its runs,
     *                       ledger and registers, which no step may mount
     * @param outputLimit    how many bytes of each stream of a client call the runtime keeps: the operator's
     *                       {@code output_limit_mib}, as the host runner keeps of a host command
     */
    Optional<ContainerRuntime> find(String configured, HomeLayout traceworksHome, int outputLimit);

    /** The runtime of this machine: the configured one if it works, else podman, else docker. */
    RuntimeSource MACHINE = (configured, home, limit) -> RuntimeDetection.detect(configured, home, limit)
            .map(r -> r);

    /** No container runtime at all. */
    RuntimeSource NONE = (configured, home, limit) -> Optional.empty();

    /** Always {@code runtime}, whatever is configured. */
    static RuntimeSource of(ContainerRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return (configured, home, limit) -> Optional.of(runtime);
    }
}
