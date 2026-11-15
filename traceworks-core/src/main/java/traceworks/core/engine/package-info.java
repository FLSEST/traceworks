/**
 * The execution engine: the block loop ({@link traceworks.core.engine.WorkflowEngine}), the
 * step runner around each block, the run completion (status and gate aggregation) and the contracts block
 * executors implement. The engine persists nothing; it reports what happens as
 * {@link traceworks.core.port.RunEvent}s.
 */
package traceworks.core.engine;
