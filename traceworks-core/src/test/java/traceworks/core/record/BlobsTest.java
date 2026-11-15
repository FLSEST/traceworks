package traceworks.core.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.port.BlobRef;
import traceworks.core.testing.InMemoryBlobStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What {@link Blobs} stores: the content as the redactor saw it, a JSON blob written as the redactor reads it. */
class BlobsTest {

    private static final String ASTRAL = "key-🔑-0123456789";

    private final InMemoryBlobStore store = new InMemoryBlobStore();
    private final Blobs blobs = new Blobs(store);

    private String stored(BlobRef ref) {
        return new String(store.get(ref.sha256()).orElseThrow(), StandardCharsets.UTF_8);
    }

    /**
     * A character outside the BMP is written in a JSON blob as its UTF-8 bytes, as in a text blob, not as two
     * {@code \}{@code u} escapes the redactor would not find: the value is redacted in both.
     */
    @Test
    void aValueWithACharacterOutsideTheBmp_isRedactedInAJsonBlob_asInAText() throws Exception {
        blobs.redactWith(new Redactor(Map.of("TOKEN", ASTRAL)));

        String json = stored(blobs.ref(Map.of("tokens", List.of("cannot deploy with " + ASTRAL))));
        String text = stored(blobs.ref("cannot deploy with " + ASTRAL));

        assertThat(text).isEqualTo("cannot deploy with [redacted:TOKEN]");
        assertThat(json).isEqualTo("{\"tokens\":[\"cannot deploy with [redacted:TOKEN]\"]}");
        assertThat(new ObjectMapper().readTree(json).toString()).doesNotContain("0123456789");
    }

    /** Such a character that is no secret is kept as it is, and reads back as it was. */
    @Test
    void aCharacterOutsideTheBmp_isWrittenAsUtf8_andReadsBackTheSame() throws Exception {
        String json = new String(Blobs.json(Map.of("k", "a 🔑 b")), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("{\"k\":\"a 🔑 b\"}").doesNotContain("\\u");
        assertThat(new ObjectMapper().readTree(json).path("k").asText()).isEqualTo("a 🔑 b");
    }
}
