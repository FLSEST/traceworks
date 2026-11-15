package traceworks.core.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The JSON of the record's lines and blobs, for the register log, which writes lines the same way. */
public final class RecordJson {

    private RecordJson() {
    }

    /** The mapper the record writes with: sorted map keys, surrogates combined. */
    public static ObjectMapper mapper() {
        return Blobs.MAPPER;
    }

    public static ObjectNode object() {
        return Blobs.MAPPER.createObjectNode();
    }

    /** {@code value} as JSON bytes, as a line or a blob of the record is written. */
    public static byte[] json(Object value) {
        return Blobs.json(value);
    }
}
