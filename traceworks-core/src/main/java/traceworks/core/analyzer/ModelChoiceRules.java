package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.engine.ModelChoices;
import traceworks.core.engine.RunRequirements;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The options of a workflow's model choices ({@code modelChoices}), judged like the provider and model a step names
 * itself, for every option, since a run may pick any of them:
 * <ul>
 *   <li>{@code unknown-provider} ({@code ERROR}): an option's {@code provider} is none of the providers Traceworks
 *       knows; a run that picks the option fails at the step.</li>
 *   <li>{@code unknown-model-backend} ({@code ERROR}): an option's {@code model} names a backend its provider's CLI
 *       cannot tell the credential of, with the CLI's own message ({@link ModelBackendRules}). Reported only by an
 *       analyzer that was given the CLIs.</li>
 *   <li>{@code unused-model-choice} ({@code WARNING}): a declared choice no block refers to with
 *       {@code model_choice}; a pick of it changes nothing.</li>
 * </ul>
 * A declaration that is not understood, a default that is none of the options and a block that refers to a choice
 * that is not declared are {@code invalid-model-choice}, the run admission's own reasons ({@link RequirementRules}).
 */
final class ModelChoiceRules {

    private ModelChoiceRules() {
    }

    static void apply(WorkflowDefinition workflow, Collection<AiCli> clis, List<AnalyzerFinding> out) {
        BlockConfigSchema.Field providers = TraceworksRules.providerField(BlockConfigSchema.get());
        Set<String> used = ModelChoices.uses(workflow).stream().map(ModelChoices.Use::choice)
                .collect(Collectors.toSet());
        for (ModelChoices.Choice choice : ModelChoices.declared(workflow)) {
            for (ModelChoices.Option option : choice.options()) {
                String where = "modelChoices." + choice.name() + ".options." + option.name();
                if (RunRequirements.isCodex(option.provider())) {
                    continue;   // refused by the admission, as invalid-model-choice
                }
                if (!providers.allows(option.provider())) {
                    out.add(AnalyzerFinding.of("unknown-provider", Severity.ERROR, -1, null, where + ": provider \""
                            + option.provider() + "\" is not one of " + String.join(", ", providers.values())
                            + " — Traceworks refuses every AI step of a run that picks this option"));
                    continue;
                }
                AiCli cli = clis.stream().filter(c -> c.id().equalsIgnoreCase(option.provider())).findFirst()
                        .orElse(null);
                if (cli == null) {
                    continue;
                }
                try {
                    cli.credentialNames(ModelBackendRules.call(option.model()));
                } catch (IllegalArgumentException e) {
                    out.add(AnalyzerFinding.of("unknown-model-backend", Severity.ERROR, -1, null,
                            where + ": " + e.getMessage()));
                }
            }
            if (!used.contains(choice.name())) {
                out.add(AnalyzerFinding.of("unused-model-choice", Severity.WARNING, -1, null, "modelChoices."
                        + choice.name() + " is declared, but no block refers to it with " + ModelChoices.KEY + ": \""
                        + choice.name() + "\" — a pick of it changes nothing; refer to it, or remove it"));
            }
        }
    }
}
