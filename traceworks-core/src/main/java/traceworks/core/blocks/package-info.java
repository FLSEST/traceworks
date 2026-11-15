/**
 * The block executors of the core and their registry, {@code CoreBlocks.registry}: {@code shell}
 * (with the container tool mode, {@code checked_command} and the SARIF fact sheet), {@code simple}, {@code agentic}
 * (the Reason-Act-Observe loop, {@code Agentic*}), {@code compress} (with its stages in {@code compress}),
 * {@code compress-ai}, the control-flow blocks {@code retry}, {@code conditional}, {@code switch}, {@code while} and
 * {@code foreach}, the blocks a person answers, {@code approval} and {@code human-input} (or an AI actor:
 * {@code StepActors}), and {@code sub-workflow}. The control-flow blocks run their inner commands as shell or AI steps
 * of their own ({@code InnerStep}), through {@code WrapperCommands}.
 * Executors reach the run's ports through the {@link traceworks.core.context.ExecutionContext}:
 * commands go through its {@link traceworks.core.port.CommandRunner}, model calls through its
 * {@link traceworks.core.port.AiProviders}, other workflows through its
 * {@link traceworks.core.port.WorkflowResolver} and child runs
 * ({@link traceworks.core.context.ChildRuns}), and step timestamps come from its
 * {@link traceworks.core.port.Clock}. This package depends on the engine, never the other way round.
 *
 * <p>Two executors touch the host file system themselves, because no port covers it yet: the tool
 * mode of {@code shell} creates its output directory and the per-image cache directory and reads the SARIF files the
 * tool wrote ({@code ContainerToolStep}, {@code SarifFactSheet}), and {@code compress-ai} gives each model call a
 * fresh, empty working directory. Everything else goes through the ports.
 */
package traceworks.core.blocks;
