/**
 * The AI command line tools behind the core's {@link traceworks.core.port.AiCli} port: each turns a
 * call into its CLI's arguments, environment and stdin, and the CLI's output into an outcome
 * ({@link traceworks.adapters.ai.ClaudeCli} and {@link traceworks.adapters.ai.PiCli}).
 * They start nothing: the core runs each call as a command
 * of the run, in the CLI's built-in image. A system prompt is never on a command line: Claude and Pi read it from a
 * file of the container ({@link traceworks.adapters.ai.SystemPromptLaunch}).
 */
package traceworks.adapters.ai;
