/**
 * Running commands directly on the host, without a container: the
 * {@link traceworks.adapters.host.HostCommandRunner} and the environment hygiene of the processes it
 * starts. Host execution is an operator exception ({@code allow_host_execution}); the secure default is the container
 * runtime adapter ({@link traceworks.adapters.container}). Its client processes share this
 * package's allowlist ({@link traceworks.adapters.host.ChildProcessEnv}) and its large-script
 * helper ({@link traceworks.adapters.host.BashScript}).
 */
package traceworks.adapters.host;
