package traceworks.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow as {@code workflow.json} defines it: its name, blocks, settings and metadata, a stable {@code id}, the
 * {@code requires} list and the format marker ({@code format} and {@code format_version}).
 *
 * <p>{@link WorkflowJson} reads one from a file: a key of two words is written with an underscore
 * ({@code default_context}, {@code format_version}, {@code model_choices}), and the reader takes the earlier
 * camel-case name for it too.
 *
 * <p>Absent collections read as empty ones. The collections are read-only copies; the blocks themselves stay
 * mutable {@link BlockDefinition}s.
 *
 * @param id             stable UUID of the workflow; seals, registers and sub-workflow references use it
 * @param blocks         the ordered blocks
 * @param instructions   instructions for the AI steps of the workflow
 * @param model          default model of the AI steps; a block may override it
 * @param provider       default AI provider id of the AI steps; a block may override it
 * @param effort         default reasoning effort of the AI steps; a block may override it
 * @param defaultContext variables a run starts with unless its inputs set them
 * @param requires       the features beyond the common core the workflow needs, such as {@code container.network};
 *                       Traceworks knows {@link #KNOWN_REQUIREMENTS}
 * @param format         the format marker, {@value #FORMAT}; {@code null} for a definition without one
 * @param formatVersion  the version of the format, at most {@value #FORMAT_VERSION}; {@code null} when not given
 * @param modelChoices   the model choices the workflow opens, by name, as written: each a {@code default} and its
 *                       {@code options}, an option a {@code provider} and a {@code model}. A block refers to one
 *                       with {@code model_choice}; the run's input of the choice's name picks the option. Kept as
 *                       written, like {@code metadata}: the engine's {@code ModelChoices} reads and judges it
 */
public record WorkflowDefinition(
        String id,
        String name,
        String description,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        String provider,
        String effort,
        Map<String, Object> metadata,
        @JsonProperty("default_context") Map<String, Object> defaultContext,
        List<String> requires,
        String format,
        @JsonProperty("format_version") Integer formatVersion,
        @JsonProperty("model_choices") @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> modelChoices
) {

    /** A workflow that opens no model choice. */
    public WorkflowDefinition(String id, String name, String description, List<BlockDefinition> blocks,
                              String instructions, String model, String provider, String effort,
                              Map<String, Object> metadata, Map<String, Object> defaultContext,
                              List<String> requires, String format, Integer formatVersion) {
        this(id, name, description, blocks, instructions, model, provider, effort, metadata, defaultContext, requires,
                format, formatVersion, null);
    }

    /** The format marker of a Traceworks workflow file. */
    public static final String FORMAT = "traceworks-workflow";

    /** The newest format version this version of Traceworks reads. */
    public static final int FORMAT_VERSION = 1;

    /**
     * The features beyond the common core a workflow may declare in {@code requires}. A run refuses a workflow that
     * names any other, and the analyzer reports it.
     */
    public static final List<String> KNOWN_REQUIREMENTS = List.of("container.network", "package.images", "ai.tools",
            "ai.web", "ai.model_choice");

    /** The key of the workspace setting, in {@code metadata} or in the first block's config. */
    public static final String WORKSPACE_KEY = "workspace";

    public WorkflowDefinition {
        blocks = readOnlyCopy(blocks);
        metadata = readOnlyCopy(metadata);
        defaultContext = readOnlyCopy(defaultContext);
        requires = readOnlyCopy(requires);
        modelChoices = readOnlyCopy(modelChoices);
    }

    /** This definition with other blocks: the same workflow, its blocks as a run of it runs them. */
    public WorkflowDefinition withBlocks(List<BlockDefinition> otherBlocks) {
        return new WorkflowDefinition(id, name, description, otherBlocks, instructions, model, provider, effort,
                metadata, defaultContext, requires, format, formatVersion, modelChoices);
    }

    /**
     * Whether the definition asks for an isolated run workspace: a {@code workspace} setting with
     * {@code mode: isolated}, read from {@code metadata.workspace} or, when the metadata has
     * none, from the first block's config. Traceworks refuses it: the operator isolates a run with {@code --source}.
     */
    public boolean requestsIsolatedWorkspace() {
        Object workspace = metadata.get(WORKSPACE_KEY);
        if (!(workspace instanceof Map<?, ?>) && !blocks.isEmpty() && blocks.getFirst() != null
                && blocks.getFirst().getConfig() != null) {
            workspace = blocks.getFirst().getConfig().get(WORKSPACE_KEY);
        }
        return workspace instanceof Map<?, ?> setting && "isolated".equals(setting.get("mode"));
    }

    /** A copy that keeps {@code null} elements, so a malformed list is still read as written. */
    private static <T> List<T> readOnlyCopy(List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** A copy that keeps the order and {@code null} values of a JSON object. */
    private static Map<String, Object> readOnlyCopy(Map<String, Object> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
