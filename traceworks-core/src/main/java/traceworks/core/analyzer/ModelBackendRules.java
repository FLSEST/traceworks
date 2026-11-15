package traceworks.core.analyzer;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.BlockAiSettings;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiCli.AiCall;

import java.util.Collection;
import java.util.List;

/**
 * {@code unknown-model-backend} ({@code ERROR}): an AI step whose model is written as a literal and names a backend
 * its provider's CLI cannot tell the credential of ({@link AiCli#credentialNames}), such as a Pi model
 * {@code ollama/llama3}. The call of such a step is refused when the step runs, after the steps before it; the rule
 * says so before the run, with the CLI's own message, which says how to name the backend.
 *
 * <p>It judges only what the definition tells: a model with a placeholder is refused as such
 * ({@code placeholder-in-model}, {@link traceworks.core.engine.RunRequirements}), a step that
 * refers to a model choice is judged by the choice's options ({@link ModelChoiceRules}), and a step of a provider
 * without a configured CLI is left to {@code unknown-provider} and the start check. An analyzer that was given no
 * CLIs reports nothing.
 */
final class ModelBackendRules {

    private ModelBackendRules() {
    }

    static void apply(WorkflowDefinition workflow, Collection<AiCli> clis, List<AnalyzerFinding> out) {
        if (clis.isEmpty()) return;
        BlockConfigs.walk(workflow.blocks(), block -> {
            if (!TraceworksRules.readsProvider(block.type(), block.config(), block.nested())) return;
            if (block.config().get(traceworks.core.engine.ModelChoices.KEY) != null) return;
            String provider = BlockAiSettings.providerOf(block.config(), workflow.provider());
            AiCli cli = clis.stream().filter(c -> c.id().equalsIgnoreCase(provider)).findFirst().orElse(null);
            BlockAiSettings.StepModel model = BlockAiSettings.stepModel(block.config(), workflow.provider(),
                    workflow.model());
            if (cli == null || !model.literal()) return;
            try {
                cli.credentialNames(call(model.model()));
            } catch (IllegalArgumentException e) {
                out.add(AnalyzerFinding.of("unknown-model-backend", Severity.ERROR, block.index(), block.name(),
                        block.prefix() + e.getMessage()));
            }
        });
    }

    /** A call with {@code model} and nothing else, to ask a CLI what it makes of the model. */
    static AiCall call(String model) {
        return new AiCall(AiCall.Kind.ACTION, "", null, model, null, ToolPolicy.NONE, null, 1);
    }
}
