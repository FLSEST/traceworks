package traceworks.core.engine;

import traceworks.core.context.BlockAiSettings;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The models that may run in a Traceworks home: the operator's {@value #SETTING} in {@code traceworks.json}. It
 * narrows what a workflow names or a run picks and never widens it: a run with an AI step whose provider and model
 * no entry allows is refused before its first block, whatever the package or the caller says. Without the setting
 * every model may run.
 *
 * @param entries the allowed provider and model pairs; {@code null} for no restriction
 */
public record AllowedModels(List<Entry> entries) {

    /** The key of the setting in {@code traceworks.json}. */
    public static final String SETTING = "allowed_models";

    /** No restriction: the setting is absent. */
    public static final AllowedModels ANY = new AllowedModels(null);

    public AllowedModels {
        entries = entries == null ? null : List.copyOf(entries);
    }

    /**
     * One allowed pair.
     *
     * @param provider the AI provider id, compared ignoring case
     * @param model    the model, in which {@code *} stands for any text; {@code null} for every model of the
     *                 provider, the provider's default included
     */
    public record Entry(String provider, String model) {

        boolean allows(String stepProvider, String stepModel) {
            if (!provider.equalsIgnoreCase(stepProvider.strip())) {
                return false;
            }
            if (model == null) {
                return true;
            }
            // The default model of a provider is not known here, so only an entry for every model allows it
            return stepModel != null && matches(model, stepModel);
        }

        /**
         * Whether {@code text} is {@code glob}, in which each {@code *} stands for any text without a line break.
         * One pass and no regular expression: the text is the workflow's and has no bound of its own, and a
         * pattern with two wildcards that tries again costs a time that grows with the square of its length. The
         * first part must begin the text and the last must end it; each part between is taken at its first place
         * after the part before, which leaves the most room for the rest.
         */
        private static boolean matches(String glob, String text) {
            String[] parts = glob.split("\\*", -1);
            if (parts.length == 1) {
                return glob.equals(text);
            }
            String first = parts[0];
            String last = parts[parts.length - 1];
            if (text.length() < first.length() + last.length() || !text.startsWith(first) || !text.endsWith(last)) {
                return false;
            }
            int from = first.length();
            int end = text.length() - last.length();
            for (int i = 1; i < parts.length - 1; i++) {
                int at = text.indexOf(parts[i], from);
                if (at < 0 || at + parts[i].length() > end) {
                    return false;
                }
                from = at + parts[i].length();
            }
            // What the stars stand for lies between the parts; none of it may be a line break. A part of the entry
            // is the operator's text and has none, so the whole text is looked at
            return text.chars().noneMatch(c -> c == '\n' || c == '\r' || c == '\u0085' || c == ' '
                    || c == ' ');
        }

        /** The entry as a message names it: {@code CLAUDE claude-*}, {@code PI (every model)}. */
        String shown() {
            return provider + (model == null ? " (every model)" : " " + model);
        }
    }

    /** Whether the setting is there. */
    public boolean restricted() {
        return entries != null;
    }

    /**
     * Whether a step may run on {@code provider} with {@code model}.
     *
     * @param model the model the step is called with; {@code null} for the provider's default
     */
    public boolean allows(String provider, String model) {
        return entries == null || entries.stream().anyMatch(entry -> entry.allows(provider, model));
    }

    /**
     * Why a run of the definition is refused: one reason per AI step, nested ones included, whose provider and model
     * are not allowed. The definition is the one the run runs, a picked model choice already in place
     * ({@link ModelChoices#resolve}). A model that is no literal text is not judged here: the admission refuses a
     * placeholder, and a value that is no text fails its step.
     */
    public List<String> refusals(WorkflowDefinition definition) {
        List<String> reasons = new ArrayList<>();
        if (entries == null) {
            return reasons;
        }
        List<BlockDefinition> blocks = definition.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null || block.getConfig() == null) {
                continue;
            }
            String label = "Block " + (i + 1) + named(block.getName());
            judge(definition, block.getType(), block.getConfig(), false, label, reasons);
            NestedBlocks.visit(block.getConfig(), nested -> judge(definition, nested.type(), nested.config(), true,
                    label + " › " + nested.path() + named(nested.name()), reasons));
        }
        return reasons;
    }

    private void judge(WorkflowDefinition definition, String type, Map<String, Object> config, boolean nested,
                       String where, List<String> reasons) {
        if (!RunRequirements.callsAi(type, config, nested)) {
            return;
        }
        String provider = BlockAiSettings.providerOf(config, definition.provider());
        BlockAiSettings.StepModel model = BlockAiSettings.stepModel(config, definition.provider(),
                definition.model());
        if (!model.literal() || allows(provider, model.model())) {
            return;
        }
        reasons.add(where + ": " + refusal(provider, model.model()));
    }

    /** Why {@code provider} with {@code model} may not run, naming the setting and what it allows. */
    public String refusal(String provider, String model) {
        boolean providerListed = entries.stream().anyMatch(entry -> entry.provider().equalsIgnoreCase(provider));
        String what = model != null ? "the model " + model + " of " + provider
                : "the default model of " + provider + (providerListed ? ", which Traceworks does not know (name the"
                + " model in the workflow, or allow every model of the provider with an entry without model)" : "");
        return what + " may not run in this home: " + SETTING + " in traceworks.json allows "
                + (entries.isEmpty() ? "no model" : String.join(", ", entries.stream().map(Entry::shown).toList()));
    }

    private static String named(String name) {
        return name == null || name.isBlank() ? "" : " \"" + name + "\"";
    }
}
