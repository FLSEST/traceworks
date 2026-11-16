package traceworks.core.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The entry the ledger gets when a run starts, outside the run folder: it says that the run began, and with which
 * first line. A record is matched to its entry by {@code runStarted}, the SHA-256 of its {@code run.started} line,
 * which is also the {@code prev} of the record's second line.
 *
 * @param runId         the run
 * @param startedAt     the time of {@code run.started}, as the line holds it
 * @param runStarted    the SHA-256 of the {@code run.started} line, lowercase hex
 * @param workflowId    the workflow's id
 * @param packageDigest the digest of the package's files
 * @param register      for the run of an audit workflow, the register and the head of its log the run read;
 *                      {@code null} otherwise
 * @param version       the Traceworks version that started the run
 */
public record StartEntry(String runId, String startedAt, String runStarted, String workflowId, String packageDigest,
                         Register register, String version) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The register an audit run read at its start.
     *
     * @param seq  the sequence number of the last line of the register's log; {@code null} when it did not exist yet
     * @param head the SHA-256 of that line; {@code null} when the register did not exist yet
     */
    public record Register(String key, Integer seq, String head) {
    }

    /**
     * The entry of the run whose record starts with {@code line}.
     *
     * @throws IllegalArgumentException if the line is no {@code run.started} of a run that has no parent
     */
    public static StartEntry of(byte[] line) {
        RecordedEvent started = RecordedEvent.parse(line);
        if (!RecordTypes.RUN_STARTED.equals(started.type()) || started.parent() != null) {
            throw new IllegalArgumentException("The first line of the record is no run.started of a run");
        }
        JsonNode raw;
        try {
            raw = MAPPER.readTree(line);
        } catch (IOException e) {
            throw new IllegalArgumentException("The first line of the record cannot be read", e);
        }
        JsonNode fields = started.fields();
        return new StartEntry(started.run(), raw.path("at").asText(null), EventChain.sha256Hex(line),
                fields.path("workflow").path("id").asText(null), fields.path("package").path("digest").asText(null),
                register(fields.path("register")), fields.path("version").asText(null));
    }

    /** The entry as pretty-printed JSON in a fixed field order, ending with a line feed. */
    public byte[] toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("run_id", runId);
        root.put("started_at", startedAt);
        root.put("run_started", runStarted);
        root.put("workflow_id", workflowId);
        root.put("package_digest", packageDigest);
        if (register != null) {
            // Only for the run of an audit workflow
            ObjectNode node = root.putObject("register");
            node.put("key", register.key());
            node.put("seq", register.seq());
            node.put("head", register.head());
        }
        root.put("traceworks_version", version);
        try {
            return (MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The start entry cannot be written", e);
        }
    }

    /** What an entry of the ledger says; empty for bytes that are no start entry. */
    public static Optional<StartEntry> parse(byte[] json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.path("run_id").isTextual() || !root.path("run_started").isTextual()) {
                return Optional.empty();
            }
            return Optional.of(new StartEntry(root.path("run_id").textValue(), root.path("started_at").asText(null),
                    root.path("run_started").textValue(), root.path("workflow_id").asText(null),
                    root.path("package_digest").asText(null), register(root.path("register")),
                    root.path("traceworks_version").asText(null)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Register register(JsonNode node) {
        return !node.isObject() ? null : new Register(node.path("key").asText(null),
                node.path("seq").isInt() ? node.path("seq").intValue() : null, node.path("head").asText(null));
    }
}
