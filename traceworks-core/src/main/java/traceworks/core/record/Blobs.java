package traceworks.core.record;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.BlobRef;
import traceworks.core.port.BlobStore;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns content into blobs and the blob references into the JSON the record holds. Every blob goes through the
 * {@link Redactor} before it is hashed and stored, so its reference names the bytes the store holds.
 *
 * <p>The texts of a checkpoint are stored once ({@link #checkpointTexts}): a run holds the same string for a text from
 * one checkpoint to the next until it changes the text, so a checkpoint knows each string the one before stored and
 * reuses its reference instead of encoding, redacting and hashing the text again.
 */
final class Blobs {

    /**
     * Writes the lines and the JSON blobs, with sorted map keys. A character outside the BMP is written as its UTF-8
     * bytes, not as two {@code \}{@code u} escapes of its surrogates (Jackson's default), so the redactor finds a value
     * that holds one in a JSON blob as it does in a text: it escapes nothing a value may hold
     * ({@link Redactor#problem}).
     */
    static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8).build())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final BlobStore store;
    /** Written before the first blob, read by every thread that stores one. */
    private volatile Redactor redactor = Redactor.none();
    /**
     * The texts the last checkpoint held, each by its string (identity, never equality: comparing two large texts
     * costs what hashing one does), with the reference it was stored as. Guarded by this object: the run's thread
     * writes the checkpoints, under the recorder's lock, and the thread that sets the redactor may be another.
     */
    private Map<String, BlobRef> heldTexts = new IdentityHashMap<>();

    Blobs(BlobStore store) {
        this.store = store;
    }

    /**
     * Redacts every blob stored from now on with {@code redactor}. A reference names the bytes the redactor in force
     * made of a text, so the texts the checkpoints before stored are stored again by the next one.
     */
    synchronized void redactWith(Redactor redactor) {
        this.redactor = redactor;
        heldTexts = new IdentityHashMap<>();
    }

    /**
     * Writes the state of one checkpoint with {@code write}, which stores each text of the state through the sink it is
     * given and returns the state. A text whose string the checkpoint before stored, or this one already did, gets the
     * reference it was stored as, and nothing is encoded, redacted, hashed or stored for it; any other text is stored as
     * {@link #ref} stores it. Afterwards this object knows just the texts of this checkpoint, so it holds no text beyond
     * the next checkpoint that the run no longer holds.
     */
    synchronized <T> T checkpointTexts(Function<Function<String, BlobRef>, T> write) {
        Map<String, BlobRef> before = heldTexts;
        Map<String, BlobRef> held = new IdentityHashMap<>();
        T state = write.apply(text -> held.computeIfAbsent(text, t -> {
            BlobRef stored = before.get(t);
            return stored != null ? stored : ref(t);
        }));
        heldTexts = held;
        return state;
    }

    /** Forgets the texts of the last checkpoint: the record has ended, and the run holds no text any more. */
    synchronized void forgetCheckpointTexts() {
        heldTexts = new IdentityHashMap<>();
    }

    /**
     * Stores the content, redacted, and returns {@code {"sha256":…,"size":…}} of what was stored; JSON {@code null}
     * for {@code null} content. A string is stored as its UTF-8 text, bytes as they are, anything else as JSON with
     * sorted map keys.
     */
    JsonNode of(Object content) {
        return content == null ? JsonNodeFactory.instance.nullNode() : node(ref(content));
    }

    /**
     * Stores the content, never {@code null}, as {@link #of} does and returns the reference of what was stored: the
     * same content gives the same blob, whichever of the two stored it.
     */
    BlobRef ref(Object content) {
        Objects.requireNonNull(content, "content");
        byte[] bytes;
        if (content instanceof byte[] raw) {
            bytes = raw;
        } else if (content instanceof CharSequence text) {
            bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = json(content);
        }
        return store.put(redactor.redact(bytes));
    }

    /** {@code text} as the record holds it: redacted by the redactor in force. */
    String redact(String text) {
        return redactor.redact(text);
    }

    /**
     * Stores bytes as they are, unless the redactor finds a value of a secret in them: the one scan decides, and
     * content without a value needs no redaction.
     *
     * @return the reference of the stored blob; {@code null}, and nothing is stored, when a value occurs
     */
    JsonNode ofUnlessSecret(byte[] content) {
        if (redactor.contains(content)) {
            return null;
        }
        return node(store.put(content));
    }

    private static JsonNode node(BlobRef ref) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sha256", ref.sha256());
        node.put("size", ref.size());
        return node;
    }

    /**
     * Whether {@code node} is a blob reference: {@code {"sha256": <64 lower-case hex digits>, "size": <integer>}} and
     * nothing else, as {@link #of} writes it: the shape the redactor keeps as it is, since it must keep naming its
     * blob.
     */
    static boolean isReference(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 2) {
            return false;
        }
        JsonNode sha = node.get("sha256");
        JsonNode size = node.get("size");
        return sha != null && sha.isTextual() && SHA256.matcher(sha.textValue()).matches()
                && size != null && size.isIntegralNumber();
    }

    static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write a value of the run as JSON: " + e.getOriginalMessage(), e);
        }
    }
}
