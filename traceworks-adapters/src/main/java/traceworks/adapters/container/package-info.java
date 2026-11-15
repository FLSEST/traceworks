/**
 * The container runtime: the {@code docker} or {@code podman} client behind the core's
 * {@link traceworks.core.port.ContainerRuntime} port. It finds a working runtime
 * ({@link traceworks.adapters.container.RuntimeDetection}), checks a step's own mounts against the
 * host paths no container may see ({@link traceworks.adapters.container.MountGuard}), and starts
 * each container with secure defaults no workflow or operator setting removes
 * ({@link traceworks.adapters.container.CliContainerRuntime}).
 */
package traceworks.adapters.container;
