package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * What the metadata of a finished {@code switch} step says about its case.
 *
 * @param matched the step's {@code matchedCase}: the value of the case, {@code default}, or {@code none}
 * @param ran     whether a case ran. {@code none} is what the step says when no case ran, and may be the value of a
 *                case that did. The step says which ({@code caseMatched}); a record of an earlier version does not,
 *                and there only a case ran a command or an AI call
 */
public record SwitchCase(String matched, boolean ran) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Reads the case from the text of a step's metadata blob.
     *
     * @param step         the step the metadata belongs to
     * @param metadataJson the text of its metadata blob
     * @return the case; empty when the text is no JSON (a deleted or damaged blob) or names no case
     */
    public static Optional<SwitchCase> of(StepView step, String metadataJson) {
        String matched;
        JsonNode ran;
        try {
            JsonNode metadata = JSON.readTree(metadataJson);
            matched = metadata.path("matchedCase").isTextual() ? metadata.path("matchedCase").asText() : null;
            ran = metadata.path("caseMatched");
        } catch (IOException e) {
            return Optional.empty();
        }
        if (matched == null) {
            return Optional.empty();
        }
        boolean noCase = ran.isBoolean() ? !ran.asBoolean()
                : matched.equals("none") && step.commands().isEmpty() && step.aiCalls().isEmpty();
        return Optional.of(new SwitchCase(matched, !noCase));
    }
}
