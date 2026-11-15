package traceworks.core.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code workflow.json}. This is the one place that knows a key of the definition has two spellings: the name
 * with an underscore, which the docs show and the rest of Traceworks reads and names, and the camel-case name of
 * earlier files, which stays valid. The reader turns the earlier name into the name before anything else sees the
 * definition, so the run, the analyzer, {@code describe}, {@code diff} and the register meet one spelling only.
 *
 * <p>The keys with two spellings are {@link #TOP_LEVEL} at the top of the file and {@link #AUDIT} in
 * {@code metadata.audit}. A key of one word has one spelling, and so has every key of a block's {@code config}. A
 * definition that has both spellings of one key is refused ({@value #DUPLICATE_RULE}): it would not say which value
 * counts.
 */
public final class WorkflowJson {

    /** The rule a definition with both spellings of one key breaks. */
    public static final String DUPLICATE_RULE = "duplicate-key-spelling";

    /** The top-level keys with two spellings: the earlier name and the name, in the order a refusal names them. */
    public static final Map<String, String> TOP_LEVEL = ordered(
            "formatVersion", "format_version",
            "defaultContext", "default_context",
            "modelChoices", "model_choices");

    /** The keys of {@code metadata.audit} with two spellings: the earlier name and the name, in that order too. */
    public static final Map<String, String> AUDIT = ordered(
            "reportBlock", "report_block",
            "schemaVersion", "schema_version");

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowJson() {
    }

    /**
     * The definition the bytes of a {@code workflow.json} hold, its keys under their names.
     *
     * @throws JsonProcessingException if the bytes are no JSON, no definition (an unknown top-level key, a value of
     *                                 the wrong shape) or hold both spellings of one key
     */
    public static WorkflowDefinition read(byte[] json) throws JsonProcessingException {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (root == null || root.isMissingNode()) {
            throw MismatchedInputException.from(null, WorkflowDefinition.class,
                    "No content to map due to end-of-input");
        }
        if (root instanceof ObjectNode definition) {
            List<String> twice = new ArrayList<>();
            rename(definition, TOP_LEVEL, "", twice);
            if (definition.path("metadata").path("audit") instanceof ObjectNode audit) {
                rename(audit, AUDIT, "metadata.audit.", twice);
            }
            if (!twice.isEmpty()) {
                throw JsonMappingException.from((com.fasterxml.jackson.core.JsonParser) null, "the definition has "
                        + String.join(", ", twice) + ", two spellings of one key — keep the name with the underscore"
                        + " and remove the other (" + DUPLICATE_RULE + ")");
            }
        }
        return JSON.treeToValue(root, WorkflowDefinition.class);
    }

    /**
     * Renames the earlier names of {@code node} and adds every key that stands there in both spellings to
     * {@code twice}, as {@code both <name> and <earlier name>}, in the order of {@code names}.
     */
    private static void rename(ObjectNode node, Map<String, String> names, String where, List<String> twice) {
        for (Map.Entry<String, String> twin : names.entrySet()) {
            String earlier = twin.getKey();
            String name = twin.getValue();
            if (!node.has(earlier)) {
                continue;
            }
            if (node.has(name)) {
                twice.add("both " + where + name + " and " + where + earlier);
            } else {
                node.set(name, node.remove(earlier));
            }
        }
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
