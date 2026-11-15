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
 */
public record AnswerRequest(String kind, Integer step, String answer, Map<String, String> values, String comment) {

    public AnswerRequest {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
