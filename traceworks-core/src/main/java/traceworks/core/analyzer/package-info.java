/**
 * The workflow analyzer behind {@code traceworks check}: deterministic rules over a
 * {@link traceworks.core.model.WorkflowDefinition}, without an AI call and without running anything.
 * The entry point is {@link traceworks.core.analyzer.WorkflowAnalyzer#analyze}; each finding is an
 * {@link traceworks.core.analyzer.AnalyzerFinding} with a stable rule key. The config key rules read
 * the block config schema shipped as the classpath resource {@code block-config-schema.json}; the block types come from
 * the engine's block registry. This package depends on the model, the context, the engine and the blocks, and nothing
 * depends on it.
 */
package traceworks.core.analyzer;
