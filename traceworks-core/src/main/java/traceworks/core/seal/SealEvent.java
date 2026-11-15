package traceworks.core.seal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/**
 * One line of a home's seal log: the chain fields {@code seq}, {@code at}, {@code type} and {@code prev}, then the
 * event's own fields.
 */
public record SealEvent(int seq, Instant at, String type, String prev, JsonNode fields) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @throws IllegalArgumentException if the line is no seal event */
    public static SealEvent parse(byte[] line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a seal event: " + e.getMessage(), e);
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("not a seal event: the line is not a JSON object");
        }
        if (!object.path("seq").isInt() || !object.path("at").isTextual() || !object.path("type").isTextual()
                || !object.path("prev").isTextual()) {
            throw new IllegalArgumentException("not a seal event: seq, at, type or prev is missing");
        }
        ObjectNode fields = MAPPER.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            switch (entry.getKey()) {
                case "seq", "at", "type", "prev" -> { }
                default -> fields.set(entry.getKey(), entry.getValue());
            }
        }
        Instant at;
        try {
            at = Instant.parse(object.get("at").asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a seal event: the field at is not a time", e);
        }
        return new SealEvent(object.get("seq").intValue(), at, object.get("type").asText(),
                object.get("prev").asText(), fields);
    }

    /** The text of {@code field}, or {@code null}. */
    public String text(String field) {
        JsonNode value = fields.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
