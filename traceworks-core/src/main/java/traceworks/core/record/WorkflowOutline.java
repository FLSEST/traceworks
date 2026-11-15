package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shape of the workflow a run ran, read from the {@code workflow.json} the record keeps: its description and
 * one line per block. It is what a reader needs to see which workflow this was and how it is built; the definition
 * itself stays in the blob. Read leniently: a definition the engine would refuse still gets an outline.
 *
 * @param description the workflow's description; {@code null} when it has none
 * @param blocks      the blocks, in definition order
 */
public record WorkflowOutline(String description, List<Block> blocks) {

    /** The most characters of a block's {@link Block#what}. */
    static final int WHAT_LIMIT = 120;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The keys whose first line says what a block does, in the order they are tried. */
    private static final List<String> WHAT_KEYS = List.of("command", "prompt", "goal", "question", "condition", "message",
            "workflow_id", "items_command", "items_source");

    public WorkflowOutline {
        blocks = List.copyOf(blocks);
    }

    /**
     * One block of the definition.
     *
     * @param position the block's position in the definition, 0-based: the step index of a top-level block
     * @param name     the block's name; {@code null} for a block without one
     * @param type     the block type
     * @param provider the AI provider the block names, else {@code null}
     * @param model    the model the block names; {@code model choice <name>} for a block that refers to a model
     *                 choice; else {@code null}
     * @param what     the first line of the block's command, prompt, goal or the like, cut at {@value #WHAT_LIMIT}
     *                 characters; {@code null} when the config has none of them
     */
    public record Block(int position, String name, String type, String provider, String model, String what,
                        String web) {

        /**
         * The type as a reader is shown it: the type, the provider and model the block names in brackets, and its
         * {@code web} when it has one, such as {@code simple (claude opus) web search}; {@code ?} for a block without
         * a type.
         */
        public String shownType() {
            String shown = type == null ? "?" : type;
            if (provider != null || model != null) {
                shown += " (" + (provider == null ? model : model == null ? provider : provider + " " + model) + ")";
            }
            return web == null ? shown : shown + " web " + web;
        }
    }

    /**
     * The line of one block held as its parts, such as a block nested in another or one of a child workflow: the same
     * line a top-level block of a recorded definition gets.
     *
     * @param position the block's position in its list; a caller's own number for a block that has none
     * @param config   the block's config; {@code null} for none
     */
    public static Block block(int position, String name, String type, Map<String, Object> config) {
        JsonNode node = config == null ? JSON.createObjectNode() : JSON.valueToTree(config);
        return new Block(position, blankToNull(name), blankToNull(type), text(node, "provider"), model(node),
                what(node), web(node));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The outline of a recorded definition.
     *
     * @return the outline, or empty when the bytes are no JSON object
     */
    public static java.util.Optional<WorkflowOutline> of(byte[] workflowJson) {
        JsonNode root;
        try {
            root = JSON.readTree(workflowJson);
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return java.util.Optional.empty();
        }
        List<Block> blocks = new ArrayList<>();
        JsonNode list = root.path("blocks");
        if (list.isArray()) {
            for (int i = 0; i < list.size(); i++) {
                JsonNode block = list.get(i);
                JsonNode config = block.path("config");
                blocks.add(new Block(i, text(block, "name"), text(block, "type"), text(config, "provider"),
                        model(config), what(config), web(config)));
            }
        }
        return java.util.Optional.of(new WorkflowOutline(text(root, "description"), blocks));
    }

    /**
     * The model the block names; for a block that refers to a model choice instead ({@code model_choice}), the words
     * {@code model choice <name>}: the definition does not say which option a run picks.
     */
    private static String model(JsonNode config) {
        String model = text(config, "model");
        String choice = text(config, "model_choice");
        return model != null || choice == null ? model : "model choice " + choice;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /** The block's {@code web} as the definition writes it, when it is not {@code none}; {@code null} otherwise. */
    private static String web(JsonNode config) {
        String web = text(config, "web");
        return web == null || web.strip().equalsIgnoreCase("none") ? null : web.strip().toLowerCase(Locale.ROOT);
    }

    private static String what(JsonNode config) {
        for (String key : WHAT_KEYS) {
            String value = text(config, key);
            if (value != null) {
                return firstLine(value);
            }
        }
        return null;
    }

    /** The first line of a text, cut at {@value #WHAT_LIMIT} characters: how much of a command or prompt is shown. */
    public static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= WHAT_LIMIT ? line : line.substring(0, WHAT_LIMIT - 1) + "…";
    }
}
