package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The AI settings a step is about to run with, for the running step: {@code provider},
 * {@code requestedModel}/{@code model} and {@code aiRequest} with the effort, resolved as the executors resolve them
 * (block, then workflow, then provider default; see {@code BlockAiSettings}). The finished step's metadata replaces
 * these keys: with the model that actually answered, or without them when no AI call ran after all (an empty
 * foreach).
 */
final class StepAiPreview {

    /** Blocks that always call the AI. */
    private static final Set<String> ALWAYS_AI = Set.of("simple", "agentic", "compress-ai");
    /** Wrappers whose condition or command goes to the AI unless they run in shell mode. */
    private static final Set<String> AI_MODE_WRAPPERS = Set.of("foreach", "while", "conditional", "retry");

    private final AiProviders providers;

    StepAiPreview(AiProviders providers) {
        this.providers = providers;
    }

    /**
     * Empty for a block without an AI call of its own. Never throws, an invalid {@code execution_mode} or an unknown
     * provider included: the running step must exist before the executor reports the broken block as a failed step.
     */
    Map<String, Object> of(BlockDefinition block, ExecutionContext context) {
        try {
            if (!callsAi(block)) return Map.of();
            AiProvider provider = providers.resolve(context.providerFor(block));
            if (provider == null) return Map.of();
            String model = context.modelFor(block);
            // no effort for a provider that ignores it
            boolean effortApplies = provider.getCapabilities() != null && provider.getCapabilities().supportsEffort();
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("provider", provider.getId());
            if (model != null && !model.isBlank()) {
                preview.put("requestedModel", model);
                preview.put("model", model);
            }
            preview.put(StepAiMetadata.AI_REQUEST, StepAiMetadata.aiRequest(provider.getId(), model,
                    effortApplies ? context.effortFor(block) : null));
            return preview;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** foreach with {@code workflow_id} runs children instead of an AI call; switch leaves it to its cases. */
    static boolean callsAi(BlockDefinition block) {
        String type = block.getType();
        if (ALWAYS_AI.contains(type)) return true;
        if (!AI_MODE_WRAPPERS.contains(type)) return false;
        Map<String, Object> config = block.getConfig() != null ? block.getConfig() : Map.of();
        if ("foreach".equals(type) && config.get("workflow_id") instanceof String id && !id.isBlank()) return false;
        return !ExecutionMode.isShell(config);
    }
}
