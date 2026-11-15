package traceworks.core.app;

import traceworks.core.engine.SecretNames;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;
import traceworks.core.record.Redactor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * The start check of a run's secrets, a contributor to its {@link Preflight}: every name the definition
 * declares ({@link SecretNames#declared}: nested blocks and the workflows it names literally included) must be a
 * valid name and have a value the {@link Redactor} takes ({@link Redactor#problem}: at least
 * {@value Redactor#MIN_VALUE_LENGTH} characters, no {@code "}, {@code \}, {@code '} or control character, not only
 * digits, no {@value Redactor#MARKER}), from {@code credentials.json} or the environment. The values are read only for a run
 * that declares a secret, so a {@code credentials.json} that cannot be used refuses only such a run. No refusal shows
 * a value.
 */
final class Secrets {

    private Secrets() {
    }

    /**
     * Adds a line to {@code preflight} for each reason the secrets refuse the run, and returns the values the run
     * passes on.
     *
     * @param children finds the workflows the definition's blocks name literally; {@code null} looks into none
     * @param values   where the values come from; asked only when the definition declares a secret. A credentials
     *                 file that cannot be used refuses the run, reported by the first check that needs the values
     * @return the value of every declared name by name; empty when the definition declares none or the run is refused
     */
    static Map<String, String> check(WorkflowDefinition definition, WorkflowResolver children,
                                     SecretSource values, Preflight preflight) {
        SortedSet<String> declared = SecretNames.declared(definition, children);
        if (declared.isEmpty()) {
            return Map.of();
        }
        List<String> refusals = new ArrayList<>();
        List<String> valid = new ArrayList<>();
        for (String name : declared) {
            String problem = SecretNames.problem(name);
            if (problem != null) {
                refusals.add("secrets: " + problem);
            } else {
                valid.add(name);
            }
        }
        if (valid.isEmpty()) {
            preflight.refuse(refusals);
            return Map.of();
        }
        boolean invalidNames = !refusals.isEmpty();
        preflight.refuse(refusals);
        refusals.clear();
        SecretValues source = values.values("the values of the secrets " + String.join(", ", valid), preflight)
                .orElse(null);
        if (source == null) {
            return Map.of();
        }
        List<String> missing = new ArrayList<>();
        Map<Redactor.ValueProblem, List<String>> refused = new EnumMap<>(Redactor.ValueProblem.class);
        for (String name : valid) {
            String value = source.value(name).orElse(null);
            Redactor.ValueProblem problem = value == null ? null : Redactor.problem(value);
            if (value == null) {
                missing.add(name);
            } else if (problem != null) {
                refused.computeIfAbsent(problem, p -> new ArrayList<>()).add(name);
            }
        }
        if (!missing.isEmpty()) {
            List<String> kept = missing.stream().filter(source::keptFromTheEnvironment).toList();
            refusals.add("secrets without a value: " + String.join(", ", missing) + " — "
                    + source.whereToPut(true) + (kept.isEmpty() ? "" : ". " + String.join(", ", kept)
                    + (kept.size() == 1 ? " is" : " are") + " set in the environment, but 'secret_names' in"
                    + " traceworks.json does not allow " + (kept.size() == 1 ? "it" : "them")));
        }
        refused.forEach((problem, names) -> refusals.add("secrets with a value " + problem.what() + ": "
                + String.join(", ", names) + " — " + problem.why()));
        if (invalidNames || !refusals.isEmpty()) {
            preflight.refuse(refusals);
            return Map.of();
        }
        return source.used();
    }
}
