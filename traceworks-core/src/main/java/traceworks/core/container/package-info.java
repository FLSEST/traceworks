/**
 * The container policy of the core: what a command may do as a container. {@link
 * traceworks.core.container.ContainerCommandRunner} turns a command request into a fully specified
 * container run (image ID, standard mounts at {@link traceworks.core.container.RunPaths}, network,
 * limits, environment names), and the record keeps what it resolved. It reaches the container runtime only through
 * the port {@link traceworks.core.port.ContainerRuntime} and does no I/O of its own.
 */
package traceworks.core.container;
