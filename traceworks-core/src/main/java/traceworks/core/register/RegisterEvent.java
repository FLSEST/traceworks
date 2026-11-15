package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/**
 * One line of a register's log: the chain fields {@code seq}, {@code at}, {@code type}, {@code register} and
 * {@code prev}, then the event's own fields.
 */
public record RegisterEvent(int seq, Instant at, String type, String register, String prev, JsonNode fields,
                            byte[] raw) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @throws IllegalArgumentException if the line is no register event */
    public static RegisterEvent parse(byte[] line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a register event: " + e.getMessage(), e);
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Not a register event: the line is not a JSON object");
        }
        for (String required : new String[] {"seq", "at", "type", "register", "prev"}) {
            if (!object.hasNonNull(required)) {
                throw new IllegalArgumentException("Not a register event: the field " + required + " is missing");
            }
        }
        ObjectNode fields = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = object.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            switch (entry.getKey()) {
                case "seq", "at", "type", "register", "prev" -> { }
                default -> fields.set(entry.getKey(), entry.getValue());
            }
        }
        Instant at;
        try {
            at = Instant.parse(object.get("at").asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a register event: the field at is not a time", e);
        }
        return new RegisterEvent(object.get("seq").asInt(), at, object.get("type").asText(),
                object.get("register").asText(), object.get("prev").asText(), fields, line.clone());
    }

    /** The text of {@code field}, or {@code null}. */
    public String text(String field) {
        JsonNode value = fields.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
