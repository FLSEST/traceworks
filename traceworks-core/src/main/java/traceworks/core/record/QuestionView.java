package traceworks.core.record;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.port.BlobRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A question a step asked a person ({@code approval.requested}) and the answer recorded for it, if any.
 *
 * @param seq      the seq of the request
 * @param kind     {@code approval} or {@code human-input}
 * @param fields   the fields of a human-input form; {@code null} for a single input
 * @param parks    whether the run parked at this question
 * @param preset   whether it was answered at once from the answer given while the run was parked
 * @param answered the answer; {@code null} while the question is open
 */
public record QuestionView(int seq, int step, String kind, String blockId, BlobRef question, List<String> options,
                           List<String> rejectOptions, String inputType, BlobRef defaultValue, boolean required,
                           String pattern, String variable, List<Map<String, Object>> fields, boolean parks, boolean preset, Answer answered) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QuestionView {
        options = List.copyOf(options);
        rejectOptions = List.copyOf(rejectOptions);
        fields = fields == null ? null : List.copyOf(fields);
    }

    /**
     * The question an {@code approval.requested} line asks, not yet answered.
     *
     * @throws IllegalArgumentException if the event is not an {@code approval.requested} of a step
     */
    public static QuestionView of(RecordedEvent requested) {
        if (!RecordTypes.APPROVAL_REQUESTED.equals(requested.type()) || requested.step() == null) {
            throw new IllegalArgumentException("Not the question of a step: seq " + requested.seq() + " is "
                    + requested.type());
        }
        JsonNode f = requested.fields();
        JsonNode fieldsNode = f.path("fields");
        List<Map<String, Object>> fields = fieldsNode.isArray()
                ? MAPPER.convertValue(fieldsNode, new TypeReference<>() { }) : null;
        return new QuestionView(requested.seq(), requested.step(), text(f, "kind"), text(f, "block_id"),
                requested.blob("question"), strings(f.path("options")), strings(f.path("reject_options")),
                text(f, "input_type"), requested.blob("default"), f.path("required").asBoolean(), text(f, "pattern"),
                text(f, "variable"), fields, f.path("parks").asBoolean(), f.path("preset").asBoolean(), null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }

    /**
     * An {@code approval.answered}.
     *
     * @param actor   who answered; {@code null} when the event did not say
     * @param source  how the answer arrived, such as {@code cli} or {@code resume}; {@code null} when not said
     * @param request the id of the inbox request that delivered it; {@code null} when none
     * @param at      when it was recorded
     * @param given   for the answer a resumed step received ({@code source} {@code resume}): the answer given while
     *                the run was parked, with who gave it, when, how and the comment; {@code null} otherwise
     */
    public record Answer(int seq, int step, String kind, BlobRef answer, String actor, String source, String request,
                         BlobRef comment, java.time.Instant at, Answer given) {}

    QuestionView withAnswer(Answer answer) {
        return new QuestionView(seq, step, kind, blockId, question, options, rejectOptions, inputType, defaultValue,
                required, pattern, variable, fields, parks, preset, answer);
    }
}
