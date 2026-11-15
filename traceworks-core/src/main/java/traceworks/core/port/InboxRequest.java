package traceworks.core.port;

import java.time.Instant;
import java.util.Map;

/**
 * A request for a running run, dropped into its inbox by another process. An answer names the question it answers,
 * as the record showed it when the answer was sent, so it never reaches a question that opened later.
 *
 * @param id          the request id, unique per request
 * @param kind        {@code approval}, {@code human-input} or {@code cancel}
 * @param run         the run of the record that asks the question: the run itself or one of its child runs;
 *                    {@code null} for a cancel
 * @param step        the index of the step that asks, in that run; {@code null} for a cancel
 * @param questionSeq the seq of the question's {@code approval.requested}; {@code null} for a cancel
 * @param answer      the chosen option of an approval; {@code null} otherwise
 * @param values      the values of a human-input answer; empty otherwise
 * @param comment     a free comment; may be {@code null}
 * @param actor       who sent the request
 * @param at          when the request was made
 */
public record InboxRequest(
        String id,
        String kind,
        String run,
        Integer step,
        Integer questionSeq,
        String answer,
        Map<String, String> values,
        String comment,
        String actor,
        Instant at) {

    public InboxRequest {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
