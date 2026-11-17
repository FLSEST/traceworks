package traceworks.core.app;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An answer to the question a run waits for.
 *
 * @param kind    {@code approval} or {@code human-input}
 * @param step    the step the answer is for; {@code null} for the question the run waits for, whichever step asks it
 * @param answer  the chosen option of an approval; {@code null} for a human input
 * @param values  the values of a human input, by field; empty for an approval
 * @param comment a free comment, recorded with the answer; may be {@code null}
 * @param agent   the name the agent that answers under the actor gave itself
 *                ({@link traceworks.core.register.DecisionAgent}), recorded beside the actor; {@code null} for an
 *                answer no agent claims
 */
public record AnswerRequest(String kind, Integer step, String answer, Map<String, String> values, String comment,
                            String agent) {

    public AnswerRequest {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** An answer no agent claims. */
    public AnswerRequest(String kind, Integer step, String answer, Map<String, String> values, String comment) {
        this(kind, step, answer, values, comment, null);
    }
}
