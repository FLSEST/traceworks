package traceworks.core.app;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;
import traceworks.core.port.WorkflowResolver;
import traceworks.core.record.Redactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The start check of the AI providers' credentials, a contributor to a run's {@link Preflight}. Each
 * provider the run uses ({@link Images#providers}: a step's own, else its workflow's, else the default, in the
 * definition and the workflows it reaches by a literal {@code workflow_id}) and
 * that has a configured CLI needs at least one of its {@linkplain AiCli#CREDENTIALS credentials}, from
 * {@code credentials.json} or the environment, as a secret does; of those with a value, a call's container gets the ones its CLI names for
 * the call ({@link AiCli#credentialNames}: for Pi those of the backend the call's model belongs to). So a model a
 * step names as a literal needs a credential of its backend at the start too, and so does the model of the option a
 * run picked of a model choice; a placeholder in a model refuses the workflow. A value follows the rules of a secret's ({@link Redactor#problem}), since the record redacts it. The
 * values are read only for a run that uses a provider, so a credential nobody uses never refuses a run. No refusal
 * shows a value.
 *
 * <p>A provider without a configured CLI is not checked here: its steps fail when they run, naming the configured
 * providers.
 */
final class AiCredentials {

    private AiCredentials() {
    }

    /**
     * Adds a line to {@code preflight} for each reason the credentials refuse the run, and returns the credentials the
     * run passes on.
     *
     * @param children finds the workflows the definition reaches by a literal {@code workflow_id}, whose providers
     *                 need credentials too; {@code null} looks into none
     * @param clis     the configured AI CLIs
     * @param values   where the values come from; asked only when the definition uses a provider with a CLI. A
     *                 credentials file that cannot be used refuses the run, reported by the first check that needs
     *                 the values ({@link SecretSource})
     * @return the credentials with a value, by provider id and name; empty when the run is refused
     */
    static Map<String, Map<String, String>> check(WorkflowDefinition definition, WorkflowResolver children,
                                                   List<AiCli> clis, SecretSource values,
                                                   Preflight preflight) {
        return check(definition, children, clis, values, preflight, WorkflowUses.Picks.of(definition, null));
    }

    /**
     * {@link #check(WorkflowDefinition, WorkflowResolver, List, SecretSource, Preflight)} of a run with picked model
     * choices: a step that refers to a choice needs the credential of the picked option's provider and backend, and
     * none of an option the run did not pick.
     *
     * @param picks the run's picks
     */
    static Map<String, Map<String, String>> check(WorkflowDefinition definition, WorkflowResolver children,
                                                   List<AiCli> clis, SecretSource values,
                                                   Preflight preflight, WorkflowUses.Picks picks) {
        List<AiCli> used = new ArrayList<>();
        Map<String, Set<String>> models = WorkflowUses.literalModels(definition, children, picks);
        for (String provider : WorkflowUses.providers(definition, children, picks)) {
            for (AiCli cli : clis) {
                if (cli.id().toLowerCase(Locale.ROOT).equals(provider) && !used.contains(cli)) {
                    used.add(cli);
                }
            }
        }
        if (used.isEmpty()) {
            return Map.of();
        }
        SecretValues source = values.values("the credentials of the AI providers " + String.join(", ", used.stream()
                .map(AiCli::id).toList()), preflight).orElse(null);
        if (source == null) {
            return Map.of();
        }
        List<String> refusals = new ArrayList<>();
        Map<String, Map<String, String>> credentials = new LinkedHashMap<>();
        for (AiCli cli : used) {
            List<String> names = AiCli.credentialNamesOf(cli.id());
            if (names.isEmpty()) {
                refusals.add("AI provider " + cli.id() + " has no credential names in Traceworks (it knows those of "
                        + String.join(", ", new TreeSet<>(AiCli.CREDENTIALS.keySet())) + "), so its CLI can get none");
                continue;
            }
            Map<String, String> found = new TreeMap<>();
            for (String name : names) {
                source.value(name).ifPresent(value -> found.put(name, value));
            }
            if (found.isEmpty()) {
                refusals.add("AI provider " + cli.id() + " has no credential: " + source.whereToPut(false)
                        + ", under one of the names "
                        + String.join(", ", names) + subscriptionHint(cli.id()));
                continue;
            }
            missingForAModel(cli, found.keySet(), models.getOrDefault(cli.id().toLowerCase(Locale.ROOT), Set.of()),
                    source, refusals);
            found.forEach((name, value) -> {
                Redactor.ValueProblem problem = Redactor.problem(value);
                if (problem != null) {
                    refusals.add("the credential " + name + " of AI provider " + cli.id() + " has a value "
                            + problem.what() + " — " + problem.why());
                }
            });
            credentials.put(cli.id(), found);
        }
        if (!refusals.isEmpty()) {
            preflight.refuse(refusals);
            return Map.of();
        }
        return credentials;
    }

    /**
     * A refusal for each model the provider's steps name as a literal ({@code null}: a step without a model) whose
     * call would get none of the credentials that have a value: a CLI of several backends hands a call only the
     * credential of its model's backend ({@link AiCli#credentialNames}), so the step would fail when it runs, after
     * the steps before it. A model the CLI cannot tell the backend of is not judged here: {@code check} reports it
     * ({@code unknown-model-backend}), and its call is refused at the step.
     */
    private static void missingForAModel(AiCli cli, Set<String> withValue, Set<String> models, SecretValues source,
                                         List<String> refusals) {
        for (String model : models) {
            AiCli.AiCall call = new AiCli.AiCall(AiCli.AiCall.Kind.ACTION, "", null, model, null, ToolPolicy.NONE,
                    null, 1);
            List<String> names;
            try {
                names = cli.credentialNames(call);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (names.stream().anyMatch(withValue::contains)) {
                continue;
            }
            String backend = cli.backend(call);
            String what = model != null ? "the model " + model + " of its steps"
                    : "a step that names no model" + (backend == null ? "" : ", which goes to " + backend);
            refusals.add("AI provider " + cli.id() + " has no credential for " + what + ": "
                    + source.whereToPut(false) + ", under " + (names.size() == 1 ? "the name " : "one of the names ")
                    + String.join(", ", names));
        }
    }

    /**
     * How a Claude subscription serves the provider, as the end of a refusal; empty for a provider it cannot serve.
     * A login of the host reaches no container, so the refusal says where a token comes from.
     */
    private static String subscriptionHint(String providerId) {
        String name = AiCli.SUBSCRIPTION_TOKENS.get(providerId.toUpperCase(Locale.ROOT));
        return name == null ? "" : ". For a Claude subscription, 'claude setup-token' on the host prints a token for "
                + name;
    }
}
