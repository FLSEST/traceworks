/**
 * AI calls through the command path: {@code CliAiProvider} runs an {@code AiCli} as a container command
 * of the run, through the run's command runner. Kept apart from {@code core.ai}, whose data types the AI port uses, so
 * that the port and its users never depend on each other.
 */
package traceworks.core.ai.cli;
