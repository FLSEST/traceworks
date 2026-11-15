package traceworks.core.seal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The seal of a package: the file {@value #FILE_NAME} in the package directory. It names the workflow, the seal
 * digest (the package's digest and those of the packages it calls), who sealed it and when, and carries an SSH
 * signature over exactly these four. The file is not part of the package digest, so it must hold nothing else: it is
 * read only when it is byte for byte what {@link #toJson} writes.
 *
 * @param workflowId the {@code id} of the package's workflow
 * @param digest     the seal digest, 64 lowercase hex characters
 * @param signer     who sealed it, as the operator's actor names them; the key in the signature decides, not this
 * @param sealedAt   when, to the millisecond
 * @param signature  the armored SSH signature over {@link #payload}
 * @throws IllegalArgumentException for a blank field, a digest that is none, or a line feed or control character in
 *                                  the workflow id or the signer
 */
public record Seal(String workflowId, String digest, String signer, Instant sealedAt, String signature) {

    /** The name of the seal file in a package directory. */
    public static final String FILE_NAME = "traceworks.seal";
    /** The most bytes a seal file may have. */
    public static final int MAX_BYTES = 64 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> FIELDS =
            List.of("version", "workflow_id", "digest", "signer", "sealed_at", "signature");
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public Seal {
        requireLine("workflow_id", workflowId);
        requireLine("signer", signer);
        if (digest == null || !DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("the digest of a seal is 64 lowercase hex characters");
        }
        if (sealedAt == null) {
            throw new IllegalArgumentException("a seal needs the time sealed_at");
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("a seal needs a signature");
        }
        sealedAt = sealedAt.truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireLine(String field, String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("the " + field + " of a seal must not be blank or hold a line feed or"
                    + " another control character");
        }
    }

    /** What the signature covers: {@link #payload(String, String, String, Instant)} of this seal's fields. */
    public byte[] payload() {
        return payload(workflowId, digest, signer, sealedAt);
    }

    /** The text a seal's signature is made over, one field per line after the format's name. */
    public static byte[] payload(String workflowId, String digest, String signer, Instant sealedAt) {
        return ("traceworks-seal-v1\nworkflow_id=" + workflowId + "\ndigest=" + digest + "\nsigner=" + signer
                + "\nsealed_at=" + AT.format(sealedAt.truncatedTo(ChronoUnit.MILLIS)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The seal file: one field per line in a fixed order, ending with a line feed. */
    public byte[] toJson() {
        return ("{\n  \"version\": 1,\n  \"workflow_id\": " + quoted(workflowId) + ",\n  \"digest\": " + quoted(digest)
                + ",\n  \"signer\": " + quoted(signer) + ",\n  \"sealed_at\": " + quoted(AT.format(sealedAt))
                + ",\n  \"signature\": " + quoted(signature) + "\n}\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String quoted(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a seal: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads a seal file.
     *
     * @throws IllegalArgumentException if it is no seal, or not written as {@link #toJson} writes it
     */
    public static Seal parse(byte[] json) {
        if (json.length > MAX_BYTES) {
            throw new IllegalArgumentException("the seal is larger than the limit of " + MAX_BYTES / 1024 + " KiB");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("the seal is not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("the seal is not a JSON object");
        }
        for (String name : (Iterable<String>) root::fieldNames) {
            if (!FIELDS.contains(name)) {
                throw new IllegalArgumentException("the seal holds the field '" + name + "', which no seal has");
            }
        }
        if (!root.path("version").isInt() || root.path("version").intValue() != 1) {
            throw new IllegalArgumentException("the seal has the version " + root.path("version")
                    + ", and this Traceworks reads version 1");
        }
        Instant at;
        try {
            at = Instant.parse(text(root, "sealed_at"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("the sealed_at of the seal is not a time", e);
        }
        Seal seal = new Seal(text(root, "workflow_id"), text(root, "digest"), text(root, "signer"), at,
                text(root, "signature"));
        if (!Arrays.equals(seal.toJson(), json)) {
            throw new IllegalArgumentException("the seal is not written as Traceworks writes it (other spacing, order"
                    + " or content), so it may hold what its signature does not cover");
        }
        return seal;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("the " + field + " of the seal is missing or no text");
        }
        return value.textValue();
    }
}
