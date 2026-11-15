/**
 * Traceworks core: the domain model, the execution engine, the block executors and the analyzer. The core reaches
 * processes, containers, AI providers, other workflows, the run record and the time through the interfaces in
 * {@code traceworks.core.port}. A few places still touch the file system themselves because no port
 * covers them yet; docs/architecture.md lists them.
 */
package traceworks.core;
