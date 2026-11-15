package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.BlobRef;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/**
 * One line of the run record, parsed.
 *
 * @param parent the parent run id; {@code null} for an event of the root run
 * @param fields the fields after the common ones
 * @param raw    the line as it is in the log, without the line feed: the bytes the next event's {@code prev} hashes
 */
public record RecordedEvent(int seq, Instant at, String type, String run, String parent, String prev,
                            ObjectNode fields, byte[] raw) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @throws IllegalArgumentException if the line is not an event
     */
    public static RecordedEvent parse(byte[] line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not an event: " + e.getMessage(), e);
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Not an event: the line is not a JSON object");
        }
        for (String required : new String[] {"seq", "at", "type", "run", "prev"}) {
            if (!object.hasNonNull(required)) {
                throw new IllegalArgumentException("Not an event: the field " + required + " is missing");
            }
        }
        ObjectNode fields = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = object.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            switch (entry.getKey()) {
                case "seq", "at", "type", "run", "parent", "prev" -> { }
                default -> fields.set(entry.getKey(), entry.getValue());
            }
        }
        Instant at;
        try {
            at = Instant.parse(object.get("at").asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not an event: the field at is not a time", e);
        }
        return new RecordedEvent(object.get("seq").asInt(), at, object.get("type").asText(),
                object.get("run").asText(), object.hasNonNull("parent") ? object.get("parent").asText() : null,
                object.get("prev").asText(), fields, line.clone());
    }

    /** The step index of the event; {@code null} when it has none. */
    public Integer step() {
        JsonNode step = fields.get("step");
        return step != null && step.isInt() ? step.intValue() : null;
    }

    /** The blob a field points to; {@code null} when the field is absent, {@code null} or not a blob. */
    public BlobRef blob(String field) {
        return reference(fields.get(field));
    }

    /**
     * The blob {@code node} points to: an object with a {@code sha256} and a {@code size}; {@code null} for anything
     * else. The one rule by which the readers of a record and verify take a node for a blob reference.
     */
    static BlobRef reference(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("sha256") || !node.has("size")) {
            return null;
        }
        return new BlobRef(node.get("sha256").asText(), node.get("size").asLong());
    }
}
