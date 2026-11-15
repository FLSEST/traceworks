package traceworks.adapters.container;

import traceworks.adapters.config.HomeLayout;
import traceworks.core.port.CommandRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds the container runtime a run uses: the one the operator configured ({@code container_runtime}),
 * or else the first of {@code docker} and {@code podman} on {@code PATH} whose version probe succeeds. Docker comes
 * first because it is the runtime Traceworks is tested with; Podman is driven by the same code but
 * untested, so a machine with both gets the known one.
 */
public final class RuntimeDetection {

    /** The runtimes Traceworks drives, in the order auto-detection tries them. */
    static final List<String> RUNTIMES = List.of("docker", "podman");

    private RuntimeDetection() {
    }

    /** {@link #detect(String, Path, int)} with the default output limit, {@link CommandRunner#OUTPUT_LIMIT_BYTES}. */
    public static Optional<CliContainerRuntime> detect(String configured, Path traceworksHome) {
        return detect(configured, traceworksHome, CommandRunner.OUTPUT_LIMIT_BYTES);
    }

    /**
     * The working runtime: {@code configured} if its probe succeeds; without {@code configured}, the first of
     * {@link #RUNTIMES} whose probe succeeds. Empty when none works.
     *
     * @param configured     {@code podman}, {@code docker}, or {@code null} for auto-detection
     * @param traceworksHome the Traceworks home, whose owner every container runs as
     * @param outputLimit    how many bytes of each stream of a client call the runtime keeps: the operator's
     *                       {@code output_limit_mib}
     * @throws IllegalArgumentException for a {@code configured} runtime other than podman or docker
     */
    public static Optional<CliContainerRuntime> detect(String configured, Path traceworksHome, int outputLimit) {
        return detect(configured, HomeLayout.of(traceworksHome), outputLimit);
    }

    /**
     * {@link #detect(String, Path, int)} for a home whose runs, ledger and registers lie where {@code layout} says:
     * no step of the runtime may mount them.
     */
    public static Optional<CliContainerRuntime> detect(String configured, HomeLayout layout, int outputLimit) {
        if (configured != null && !RUNTIMES.contains(configured)) {
            throw new IllegalArgumentException("container_runtime '" + configured + "' must be podman or docker");
        }
        return detectAmong(configured == null ? RUNTIMES : List.of(configured), layout, outputLimit);
    }

    /** The first of {@code executables} whose probe succeeds, with the default output limit. */
    static Optional<CliContainerRuntime> detectAmong(List<String> executables, Path traceworksHome) {
        return detectAmong(executables, traceworksHome, CommandRunner.OUTPUT_LIMIT_BYTES);
    }

    /** The first of {@code executables} whose probe succeeds, with its version known. */
    static Optional<CliContainerRuntime> detectAmong(List<String> executables, Path traceworksHome,
                                                     int outputLimit) {
        return detectAmong(executables, HomeLayout.of(traceworksHome), outputLimit);
    }

    private static Optional<CliContainerRuntime> detectAmong(List<String> executables, HomeLayout layout,
                                                             int outputLimit) {
        for (String executable : executables) {
            CliContainerRuntime runtime = new CliContainerRuntime(executable, layout, outputLimit);
            if (runtime.probe().isPresent()) {
                return Optional.of(runtime);
            }
        }
        return Optional.empty();
    }
}
