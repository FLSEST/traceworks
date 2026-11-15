package traceworks.core.record;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.engine.StepOutputs;
import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.SourceStep;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A {@link RunSnapshot} as JSON, the form a checkpoint has in the run record. Every field is mapped by hand, so the
 * format does not change with the Java classes: {@code Instant}s are ISO-8601 strings, statuses their names, and the
 * values of variables and metadata stay what they are in JSON (strings, numbers, booleans, lists, maps, {@code null}).
 *
 * <p><b>Texts by reference.</b> The texts are not written into the snapshot: each step's input and output, the output
 * of each named block, the previous output and each variable that holds a text go to a {@link TextSink}, which stores
 * each one as a blob, and the snapshot holds the blob's reference {@code {"sha256": …, "size": …}} in its place. So a
 * snapshot grows with the number of steps and variables, not with their texts, and a text the record already holds is
 * not stored again. In this format (the root's {@code format} is {@value #FORMAT}) these fields always hold a
 * reference or {@code null}, and the variables that hold a text have a field of their own ({@code text_variables}),
 * in which each one is a reference, so no reference stands where a value of the workflow's own may stand: every other
 * value stays inline, the other variables in {@code variables}.
 *
 * <p><b>The earlier formats.</b> {@link #read} reads them too, so a run that stopped before this format still goes
 * on; {@link #write} writes this format only. The first, without {@code format}, embedded the texts. The second
 * ({@value #SECOND_FORMAT}) referred to them as this one does, except for the variables: only those named
 * {@code step_<n>_output} that held a text were references ({@code step_output_variables}), every other variable
 * stood inline, and each step carried a {@code context_delta} with the variables at its end. Nothing read the delta,
 * and it is not read now either: the state of the run is its {@code context}.
 *
 * <p>Equal snapshots give equal bytes: the snapshot's own maps and sets are written sorted by key, whatever their order
 * in memory, and a sink that names content by its hash gives equal texts equal references. A value keeps its order,
 * because a map value renders into a template in that order. Reading and writing again gives the same bytes in this
 * format.
 */
public final class RunSnapshotJson {

    /** The format {@link #write} writes; a snapshot without {@code format} is of the first, with embedded texts. */
    public static final int FORMAT = 3;

    /** The format before, whose variables {@code step_<n>_output} alone were references. */
    private static final int SECOND_FORMAT = 2;

    /**
     * A character outside the BMP is written as its UTF-8 bytes, not as two {@code \}{@code u} escapes: the record
     * redacts the snapshot as bytes, and finds a value that holds one only so.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8).build());

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Where the texts of a snapshot go when it is written. */
    @FunctionalInterface
    public interface TextSink {

        /** Stores {@code text}, never {@code null}, and returns the reference of the blob that holds it. */
        BlobRef store(String text);
    }

    /** Where they come from when it is read. */
    @FunctionalInterface
    public interface TextSource {

        /**
         * The text of the blob {@code reference} names. A text that cannot be had (its blob is gone or holds other
         * content) throws, and {@link #read} passes on what it throws as it is.
         */
        String text(Reference reference);
    }

    /**
     * Where a snapshot refers to a text and the blob that holds it.
     *
     * @param field the place in the snapshot, such as {@code steps[0].output} or {@code context.block_outputs.build}
     * @param blob  the blob
     */
    public record Reference(String field, BlobRef blob) {}

    private RunSnapshotJson() {
    }

    /**
     * @param texts stores each text of the snapshot
     * @throws IllegalArgumentException if a value cannot be written as JSON
     */
    public static byte[] write(RunSnapshot snapshot, TextSink texts) {
        Objects.requireNonNull(texts, "texts");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", FORMAT);
        root.set("context", context(snapshot.context(), texts));
        ArrayNode steps = root.putArray("steps");
        snapshot.steps().forEach(step -> steps.add(step(step, texts)));
        root.set("input_context", values(snapshot.inputContext()));
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write the run snapshot as JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads a snapshot of this format or of an earlier one. The texts it refers to are asked of {@code texts} before
     * anything else is read, each blob once.
     *
     * @param texts gives the text of each reference; a snapshot of the first format asks it nothing
     * @throws IllegalArgumentException if {@code json} is not a run snapshot; what {@code texts} throws, as it is
     */
    public static RunSnapshot read(byte[] json, TextSource texts) {
        Objects.requireNonNull(texts, "texts");
        JsonNode root = root(json);
        int format = format(root);
        boolean referenced = format != 1;
        Map<BlobRef, String> resolved = new HashMap<>();
        if (referenced) {
            for (Reference reference : references(root, format)) {
                if (!resolved.containsKey(reference.blob())) {
                    resolved.put(reference.blob(), Objects.requireNonNull(texts.text(reference),
                            "the text of " + reference.field()));
                }
            }
        }
        Function<JsonNode, String> text = referenced
                ? node -> node.isNull() ? null : resolved.get(blob(node))
                : RunSnapshotJson::text;
        try {
            List<StepResult> steps = new ArrayList<>();
            array(required(root, "steps")).forEach(step -> steps.add(step(step, text)));
            return new RunSnapshot(context(required(root, "context"), format, text), steps,
                    values(required(root, "input_context")));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {   // a time that is none, a null where a snapshot has none
            throw new IllegalArgumentException("Not a run snapshot: " + e, e);
        }
    }

    /**
     * The texts a snapshot refers to, in order: each step's input and output, then the block outputs, the previous
     * output and the text variables, by name (in the second format, the variables {@code step_<n>_output}); a blob may
     * be named more than once. None for the first format, which embeds them.
     *
     * @throws IllegalArgumentException if {@code json} is not a run snapshot, or a reference is malformed
     */
    public static List<Reference> references(byte[] json) {
        JsonNode root = root(json);
        int format = format(root);
        return format == 1 ? List.of() : references(root, format);
    }

    private static JsonNode root(byte[] json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a run snapshot: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Not a run snapshot: not a JSON object");
        }
        return root;
    }

    /** {@value #FORMAT} or {@value #SECOND_FORMAT}, or 1 for a snapshot without {@code format}. */
    private static int format(JsonNode root) {
        JsonNode format = root.get("format");
        if (format == null) {
            return 1;
        }
        if (format.isIntegralNumber() && format.canConvertToInt()
                && (format.intValue() == FORMAT || format.intValue() == SECOND_FORMAT)) {
            return format.intValue();
        }
        throw new IllegalArgumentException("Not a run snapshot this version reads: format " + format
                + " is not known");
    }

    /** The references of a snapshot of this format or of the second. */
    private static List<Reference> references(JsonNode root, int format) {
        List<Reference> references = new ArrayList<>();
        JsonNode steps = array(required(root, "steps"));
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = object(steps.get(i));
            reference(required(step, "input"), "steps[" + i + "].input", references);
            reference(required(step, "output"), "steps[" + i + "].output", references);
        }
        JsonNode context = object(required(root, "context"));
        references(required(context, "block_outputs"), "context.block_outputs.", references);
        reference(required(context, "previous_output"), "context.previous_output", references);
        if (format == SECOND_FORMAT) {
            references(required(context, "step_output_variables"), "context.step_output_variables.", references);
        } else {
            object(required(context, "text_variables")).properties().forEach(entry -> references.add(
                    new Reference("context.text_variables." + entry.getKey(), blob(entry.getValue()))));
        }
        return references;
    }

    /** The reference of each field of {@code node}, an object; a field may hold {@code null}. */
    private static void references(JsonNode node, String prefix, List<Reference> references) {
        object(node).properties().forEach(entry -> reference(entry.getValue(), prefix + entry.getKey(), references));
    }

    private static void reference(JsonNode node, String field, List<Reference> references) {
        if (!node.isNull()) {
            references.add(new Reference(field, blob(node)));
        }
    }

    /** {@code {"sha256": <64 lower-case hex digits>, "size": <bytes>}} and nothing else. */
    private static BlobRef blob(JsonNode node) {
        JsonNode sha = node.get("sha256");
        JsonNode size = node.get("size");
        if (!node.isObject() || node.size() != 2 || sha == null || !sha.isTextual()
                || !SHA256.matcher(sha.textValue()).matches() || size == null || !size.isIntegralNumber()
                || !size.canConvertToLong() || size.longValue() < 0) {
            throw wrongType("a blob reference", node);
        }
        return new BlobRef(sha.textValue(), size.longValue());
    }

    private static JsonNode reference(String text, TextSink texts) {
        if (text == null) {
            return MAPPER.nullNode();
        }
        BlobRef blob = texts.store(text);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sha256", blob.sha256());
        node.put("size", blob.size());
        return node;
    }

    /** A map of the snapshot's own, sorted by key, each text as its reference; {@code null} stays {@code null}. */
    private static JsonNode references(Map<String, String> texts, TextSink sink) {
        if (texts == null) {
            return MAPPER.nullNode();
        }
        ObjectNode node = MAPPER.createObjectNode();
        new TreeMap<>(texts).forEach((key, text) -> node.set(key, reference(text, sink)));
        return node;
    }

    private static ObjectNode context(ContextState context, TextSink texts) {
        ObjectNode node = MAPPER.createObjectNode();
        Map<String, Object> inline = new LinkedHashMap<>();
        Map<String, String> textVariables = new LinkedHashMap<>();
        context.variables().forEach((name, value) -> {
            if (value instanceof String text) {
                textVariables.put(name, text);
            } else {
                inline.put(name, value);
            }
        });
        node.set("variables", values(inline));
        node.set("text_variables", references(textVariables, texts));
        ContextState.Taint taint = context.taint();
        ObjectNode taintNode = node.putObject("taint");
        taintNode.set("untrusted_variables", names(taint.untrustedVariables()));
        taintNode.set("platform_variables", names(taint.platformVariables()));
        taintNode.set("tainted_outputs", names(taint.taintedOutputs()));
        taintNode.put("previous_output_tainted", taint.previousOutputTainted());
        taintNode.put("platform_used_in_shell", taint.platformUsedInShell());
        taintNode.put("platform_in_prompt", taint.platformInPrompt());
        node.set("block_outputs", references(context.blockOutputs(), texts));
        node.set("previous_output", reference(context.previousOutput(), texts));
        ObjectNode sourceSteps = node.putObject("source_steps");
        ObjectNode byName = sourceSteps.putObject("by_name");
        new TreeMap<>(context.sourceSteps().byName()).forEach((name, step) -> byName.set(name, sourceStep(step)));
        sourceSteps.set("ambiguous", names(context.sourceSteps().ambiguous()));
        sourceSteps.set("last", sourceStep(context.sourceSteps().last()));
        node.put("next_step_index", context.nextStepIndex());
        node.set("run_metadata", values(context.runMetadata()));
        node.set("remembered_approvals", values(context.rememberedApprovals()));
        return node;
    }

    /**
     * @param format the snapshot's format: from the second on, the variables that are references stand apart
     * @param text   a text field's text: its own in the first format, the text of its reference in the others
     */
    private static ContextState context(JsonNode node, int format, Function<JsonNode, String> text) {
        object(node);
        JsonNode taint = object(required(node, "taint"));
        JsonNode sourceSteps = object(required(node, "source_steps"));
        Map<String, SourceStep> byName = new LinkedHashMap<>();
        object(required(sourceSteps, "by_name")).properties()
                .forEach(entry -> byName.put(entry.getKey(), sourceStep(entry.getValue())));
        return new ContextState(
                variables(node, format, text),
                new ContextState.Taint(
                        names(required(taint, "untrusted_variables")),
                        names(required(taint, "platform_variables")),
                        names(required(taint, "tainted_outputs")),
                        bool(required(taint, "previous_output_tainted")),
                        bool(required(taint, "platform_used_in_shell")),
                        bool(required(taint, "platform_in_prompt"))),
                map(required(node, "block_outputs"), text),
                text.apply(required(node, "previous_output")),
                new ContextState.SourceSteps(byName, names(required(sourceSteps, "ambiguous")),
                        sourceStep(required(sourceSteps, "last"))),
                integer(required(node, "next_step_index")),
                values(required(node, "run_metadata")),
                strings(required(node, "remembered_approvals")));
    }

    /**
     * The variables; from the second format on, those inline and those by reference together, sorted: the text
     * variables of this format, the variables {@code step_<n>_output} of the second.
     */
    private static Map<String, Object> variables(JsonNode node, int format, Function<JsonNode, String> text) {
        Map<String, Object> inline = values(required(node, "variables"));
        if (format == 1) {
            return inline;
        }
        Map<String, Object> variables = new TreeMap<>();
        if (inline != null) {
            variables.putAll(inline);
        }
        boolean second = format == SECOND_FORMAT;
        map(required(node, second ? "step_output_variables" : "text_variables"), text).forEach((name, value) -> {
            if (second && !StepOutputs.isStepOutput(name)) {
                throw new IllegalArgumentException("Not a run snapshot: '" + name + "' is no step output variable");
            }
            if (variables.containsKey(name)) {
                throw new IllegalArgumentException("Not a run snapshot: variable '" + name + "' is there twice");
            }
            variables.put(name, value);
        });
        return new LinkedHashMap<>(variables);
    }

    private static ObjectNode step(StepResult step, TextSink texts) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", step.getId());
        node.put("step_index", step.getStepIndex());
        node.put("block_name", step.getBlockName());
        node.put("block_type", step.getBlockType());
        node.put("status", step.getStatus() != null ? step.getStatus().name() : null);
        node.set("input", reference(step.getInput(), texts));
        node.set("output", reference(step.getOutput(), texts));
        node.put("started_at", instant(step.getStartedAt()));
        node.put("completed_at", instant(step.getCompletedAt()));
        node.put("duration_ms", step.getDurationMs());
        node.set("metadata", values(step.getMetadata()));
        return node;
    }

    /** A step; the {@code context_delta} of the earlier formats is not read. */
    private static StepResult step(JsonNode node, Function<JsonNode, String> text) {
        object(node);
        return StepResult.builder()
                .id(text(required(node, "id")))
                .stepIndex(integer(required(node, "step_index")))
                .blockName(text(required(node, "block_name")))
                .blockType(text(required(node, "block_type")))
                .status(status(required(node, "status")))
                .input(text.apply(required(node, "input")))
                .output(text.apply(required(node, "output")))
                .startedAt(instant(required(node, "started_at")))
                .completedAt(instant(required(node, "completed_at")))
                .durationMs(optionalLong(required(node, "duration_ms")))
                .metadata(values(required(node, "metadata")))
                .build();
    }

    private static JsonNode sourceStep(SourceStep step) {
        if (step == null) {
            return MAPPER.nullNode();
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("block_name", step.blockName());
        node.put("step_id", step.stepId());
        node.put("step_index", step.stepIndex());
        node.put("block_type", step.blockType());
        node.put("status", step.status() != null ? step.status().name() : null);
        node.put("exit_code", step.exitCode());
        node.put("duration_ms", step.durationMs());
        node.set("root", sourceStep(step.root()));
        return node;
    }

    private static SourceStep sourceStep(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        object(node);
        JsonNode exitCode = required(node, "exit_code");
        return new SourceStep(
                text(required(node, "block_name")),
                text(required(node, "step_id")),
                integer(required(node, "step_index")),
                text(required(node, "block_type")),
                status(required(node, "status")),
                exitCode.isNull() ? null : integer(exitCode),
                optionalLong(required(node, "duration_ms")),
                sourceStep(required(node, "root")));
    }

    /** A map of the snapshot's own, sorted by key; each value as it is. {@code null} stays {@code null}. */
    private static JsonNode values(Map<String, ?> map) {
        if (map == null) {
            return MAPPER.nullNode();
        }
        ObjectNode node = MAPPER.createObjectNode();
        new TreeMap<>(map).forEach((key, value) -> node.set(key, MAPPER.valueToTree(value)));
        return node;
    }

    private static Map<String, Object> values(JsonNode node) {
        return map(node, RunSnapshotJson::value);
    }

    private static Map<String, String> strings(JsonNode node) {
        return map(node, RunSnapshotJson::text);
    }

    private static <V> Map<String, V> map(JsonNode node, Function<JsonNode, V> valueOf) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Not a run snapshot: expected an object, found " + node.getNodeType());
        }
        Map<String, V> map = new LinkedHashMap<>();
        node.properties().forEach(entry -> map.put(entry.getKey(), valueOf.apply(entry.getValue())));
        return map;
    }

    private static Object value(JsonNode node) {
        try {
            return MAPPER.treeToValue(node, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a run snapshot: " + e.getOriginalMessage(), e);
        }
    }

    private static ArrayNode names(Collection<String> names) {
        ArrayNode node = MAPPER.createArrayNode();
        new TreeSet<>(names).forEach(node::add);
        return node;
    }

    private static Set<String> names(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("Not a run snapshot: expected a list of names, found "
                    + node.getNodeType());
        }
        Set<String> names = new HashSet<>();
        node.forEach(name -> names.add(text(name)));
        if (names.contains(null)) {
            throw new IllegalArgumentException("Not a run snapshot: a list of names holds null");
        }
        return names;
    }

    private static String instant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant instant(JsonNode node) {
        String text = text(node);
        try {
            return text == null ? null : Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Not a run snapshot: '" + text + "' is not a time", e);
        }
    }

    private static StepStatus status(JsonNode node) {
        String text = text(node);
        try {
            return text == null ? null : StepStatus.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a run snapshot: '" + text + "' is not a step status", e);
        }
    }

    // ---- strict reading: a value of another type is refused, never read as a default ----

    private static String text(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw wrongType("text", node);
        }
        return node.textValue();
    }

    private static boolean bool(JsonNode node) {
        if (!node.isBoolean()) {
            throw wrongType("true or false", node);
        }
        return node.booleanValue();
    }

    private static int integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw wrongType("a whole number", node);
        }
        return node.intValue();
    }

    private static Long optionalLong(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw wrongType("a whole number", node);
        }
        return node.longValue();
    }

    private static JsonNode object(JsonNode node) {
        if (!node.isObject()) {
            throw wrongType("an object", node);
        }
        return node;
    }

    private static JsonNode array(JsonNode node) {
        if (!node.isArray()) {
            throw wrongType("a list", node);
        }
        return node;
    }

    private static IllegalArgumentException wrongType(String expected, JsonNode found) {
        return new IllegalArgumentException("Not a run snapshot: expected " + expected + ", found "
                + found.getNodeType());
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Not a run snapshot: '" + field + "' is missing");
        }
        return value;
    }
}
