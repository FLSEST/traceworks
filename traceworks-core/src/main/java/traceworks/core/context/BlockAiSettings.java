package traceworks.core.context;

import traceworks.core.ai.ReasoningEffort;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;

import java.util.Map;

/**
 * Resolves the AI settings of a block with the precedence
 * <b>block config &gt; workflow level &gt; provider/system default</b>.
 *
 * <p>Config keys: {@value #PROVIDER_KEY}, {@value #MODEL_KEY}, {@value #EFFORT_KEY}. Only AI-capable executors consult
 * these keys. A value that is no text fails the step ({@link BlockSettings}); an effort that is text but none of the
 * known ones is ignored with a warning.
 *
 * <p>A {@code null} result means "no explicit choice": the run's
 * {@link traceworks.core.port.AiProviders} then select the default provider, and the provider uses
 * its default model and effort.
 */
public final class BlockAiSettings {

    private static final System.Logger LOG = System.getLogger(BlockAiSettings.class.getName());

    public static final String PROVIDER_KEY = "provider";

    /**
     * The provider of an AI step whose block names none, when its workflow names none either. The one copy: the run's
     * providers default to it, the start check needs its image and credentials, and the run admission judges its
     * calls with it.
     */
    public static final String DEFAULT_PROVIDER = "CLAUDE";
    public static final String MODEL_KEY = "model";
    public static final String EFFORT_KEY = "effort";

    private BlockAiSettings() {}

    /** Block {@code provider} if set, otherwise the workflow provider (may be null = system default). */
    public static String providerFor(BlockDefinition block, String workflowProvider) {
        String blockProvider = configString(block, PROVIDER_KEY);
        return blockProvider != null ? blockProvider : workflowProvider;
    }

    /**
     * Block {@code model} if set, otherwise the workflow model — unless the block switches to a
     * provider other than the workflow's: model ids are provider-specific ({@code opus} vs.
     * {@code anthropic/opus}), so a workflow model is never carried over to a different provider.
     */
    public static String modelFor(BlockDefinition block, String workflowProvider, String workflowModel) {
        String blockModel = configString(block, MODEL_KEY);
        if (blockModel != null) {
            return blockModel;
        }
        String blockProvider = configString(block, PROVIDER_KEY);
        if (blockProvider != null && !blockProvider.equalsIgnoreCase(workflowProvider)) {
            return null;
        }
        return workflowModel;
    }

    /**
     * Block {@code effort} if valid, otherwise the workflow effort. Invalid block values are
     * ignored with a warning, not escalated — the run proceeds with the workflow effort. The warning does not name
     * the value: java.util.logging prints it to stderr as it is, so a package's terminal escapes (or a secret's value)
     * would reach the terminal; {@code traceworks check} reports it as a warning of its own.
     */
    public static String effortFor(BlockDefinition block, String workflowEffort) {
        String raw = configString(block, EFFORT_KEY);
        if (raw != null) {
            var parsed = ReasoningEffort.parse(raw);
            if (parsed.isPresent()) {
                return parsed.get().cliValue();
            }
            LOG.log(System.Logger.Level.WARNING,
                    "Ignoring an invalid block effort (valid: low, medium, high, xhigh)");
        }
        return workflowEffort;
    }

    /**
     * The provider an AI step of a block uses: the block's {@code provider}, else the workflow's, else
     * {@value #DEFAULT_PROVIDER}; trimmed, as written. For callers that hold only the config map of a block, a nested
     * one included.
     */
    public static String providerOf(Map<String, Object> config, String workflowProvider) {
        if (config != null && config.get(PROVIDER_KEY) instanceof String own && !own.isBlank()) {
            return own.trim();
        }
        return workflowProvider != null && !workflowProvider.isBlank() ? workflowProvider.trim() : DEFAULT_PROVIDER;
    }

    /**
     * The model of an AI step as the definition alone tells it.
     *
     * @param literal whether the definition tells the model: {@code false} for a model with a placeholder, which
     *                no run resolves and the run admission refuses, and for a value that is no text, which fails the
     *                step
     * @param model   the model when {@code literal}; {@code null} for a step that names none
     */
    public record StepModel(boolean literal, String model) {}

    /**
     * The model an AI step of a block is called with, read from the definition, by the rule of {@link #modelFor}:
     * the block's {@code model}, else the workflow's unless the block switches to another provider. For callers that
     * hold only the config map of a block, a nested one included; never throws.
     */
    public static StepModel stepModel(Map<String, Object> config, String workflowProvider, String workflowModel) {
        Object own = config == null ? null : config.get(MODEL_KEY);
        if (own != null && !(own instanceof String)) {
            return new StepModel(false, null);
        }
        String model = own instanceof String text && !text.isBlank() ? text.trim() : null;
        if (model == null) {
            boolean otherProvider = config != null && config.get(PROVIDER_KEY) instanceof String provider
                    && !provider.isBlank() && !provider.trim().equalsIgnoreCase(workflowProvider);
            model = otherProvider || workflowModel == null || workflowModel.isBlank() ? null : workflowModel.trim();
        }
        return new StepModel(model == null || !model.contains("{{"), model);
    }

    /**
     * The block's own text for {@code key}, trimmed; {@code null} when absent or blank.
     *
     * @throws traceworks.core.model.InvalidSettingException when the value is no text: the step
     *                                                                        fails instead of using the workflow's
     */
    private static String configString(BlockDefinition block, String key) {
        if (block == null || block.getConfig() == null) return null;
        String value = BlockSettings.of(block).text(key);
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
