package traceworks.core.describe;

import traceworks.core.record.WorkflowOutline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a workflow package will do, as far as the package says it: read from the package alone, before any run. Plain
 * data; {@link WorkflowDescriber} builds it.
 *
 * @param id                 the workflow's id; {@code null} when it has none
 * @param name               the workflow's name; {@code null} when it has none
 * @param description        the workflow's description; {@code null} when it has none
 * @param packageDir         the package directory
 * @param digest             the package digest, as a run of the unchanged package records it
 * @param commit             the HEAD commit of the git checkout holding the package; {@code null} outside one
 * @param uncommittedChanges whether the package has uncommitted changes; {@code false} outside a checkout
 * @param inputs             the variables a run starts with unless its inputs set them ({@code defaultContext}), each
 *                           with its default as text, in definition order
 * @param requires           the features the workflow declares in {@code requires}
 * @param aiDefaults         the workflow's own AI settings, which a block may override, such as {@code provider pi};
 *                           empty when it names none
 * @param instructions       the first line of the workflow's instructions for its AI steps; {@code null} without any
 * @param audit              the register the workflow feeds; {@code null} for a workflow that is no audit
 * @param blocks             the blocks, in definition order
 * @param counts             what the blocks add up to
 * @param modelChoices       the model choices the workflow opens ({@code modelChoices}), in declaration order; empty
 *                           for a workflow whose steps name their models
 */
public record WorkflowDescription(String id, String name, String description, String packageDir, String digest,
                                  String commit, boolean uncommittedChanges, Map<String, String> inputs,
                                  List<String> requires, List<String> aiDefaults, String instructions, Audit audit,
                                  List<Block> blocks, Counts counts, List<ModelChoice> modelChoices) {

    /** A description of a workflow that opens no model choice. */
    public WorkflowDescription(String id, String name, String description, String packageDir, String digest,
                               String commit, boolean uncommittedChanges, Map<String, String> inputs,
                               List<String> requires, List<String> aiDefaults, String instructions, Audit audit,
                               List<Block> blocks, Counts counts) {
        this(id, name, description, packageDir, digest, commit, uncommittedChanges, inputs, requires, aiDefaults,
                instructions, audit, blocks, counts, List.of());
    }

    /**
     * A model choice the workflow opens: whoever starts a run picks one option with the input of the choice's name.
     *
     * @param defaultOption the option of a run without a pick
     * @param options       the options, each {@code name}, {@code provider} and {@code model}, in declaration order
     * @param usedBy        the blocks that refer to the choice, by name, in definition order
     */
    public record ModelChoice(String name, String defaultOption, List<Option> options, List<String> usedBy) {

        public ModelChoice {
            options = List.copyOf(options);
            usedBy = List.copyOf(usedBy);
        }

        /** One option: a provider and a model that belong together. */
        public record Option(String name, String provider, String model) {}

        /** How a run picks: the flag with the choice's name. */
        public String pickedWith() {
            return "--input " + name + "=<option>";
        }
    }

    /** Said once below the blocks: what the package does not decide. */
    public static final String OPERATOR_NOTE = "The operator's settings decide the rest: traceworks.json (ai_network,"
            + " ai_hosts, ai_images, allowed_models, the output limits, allow_host_execution) is not shown here; it"
            + " decides where the container of an AI step can connect, which image an AI CLI runs in, which models"
            + " may run at all, and whether a step may run on the host where no container runtime works.";

    /** Said last: a description is no check. */
    public static final String CHECK_NOTE = "Nothing was checked: 'traceworks check <package>' says what a run would"
            + " refuse.";

    public WorkflowDescription {
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        requires = List.copyOf(requires);
        aiDefaults = List.copyOf(aiDefaults);
        blocks = List.copyOf(blocks);
        modelChoices = modelChoices == null ? List.of() : List.copyOf(modelChoices);
    }

    /**
     * The register an audit workflow feeds ({@code metadata.audit}). A field is as written, {@code null} when it is
     * missing or no text.
     *
     * @param problems what is wrong with the audit block, for which a run refuses the workflow; empty when it is valid
     */
    public record Audit(String key, String category, String reportBlock, List<String> problems) {

        public Audit {
            problems = List.copyOf(problems);
        }
    }

    /**
     * One block.
     *
     * @param outline  the line {@code show} prints for a block of a run: position, name, type, provider and model, and
     *                 the first line of its command or prompt; the position is {@code -1} for a block of a list whose
     *                 blocks have no step index of their own (a switch case)
     * @param lines    what the block may touch, one line per kind; empty for a block that touches nothing of note
     * @param sections the blocks it holds or starts: the cases of a switch, a child workflow
     */
    public record Block(WorkflowOutline.Block outline, List<Line> lines, List<Section> sections) {

        public Block {
            lines = List.copyOf(lines);
            sections = List.copyOf(sections);
        }
    }

    /**
     * One kind of thing a block may touch.
     *
     * @param label {@value #SHELL} for the command of a shell step, {@value #AI} for an AI call, {@value #ALSO} for
     *              the commands a block runs besides the one its first line shows, {@value #PERSON} for a question to
     *              a person
     * @param facts what holds, each a short phrase such as {@code network none}
     */
    public record Line(String label, List<String> facts) {

        public static final String SHELL = "shell";
        public static final String AI = "ai";
        public static final String ALSO = "also";
        public static final String PERSON = "person";

        public Line {
            facts = List.copyOf(facts);
        }
    }

    /**
     * Blocks below a block.
     *
     * @param label  what they are, such as {@code case "prod"}, {@code default} or {@code workflow Child (id)}
     * @param blocks the blocks; empty when they are not shown
     * @param note   what a reader must know about them, such as why they are not shown; {@code null} for none
     */
    public record Section(String label, List<Block> blocks, String note) {

        public Section {
            blocks = List.copyOf(blocks);
        }
    }

    /**
     * What the shown blocks add up to: the workflow's own, those of its switch cases and those of the child workflows
     * shown. A list no executor runs counts for nothing.
     *
     * @param blocks       the blocks
     * @param aiSteps      the blocks that call an AI
     * @param networkSteps the shell steps with {@code network: true}
     * @param personSteps  the blocks that ask a person
     * @param webSteps     the AI steps with web tools
     * @param notShown     the child workflows that are not shown, whose blocks are not counted
     */
    public record Counts(int blocks, int aiSteps, int networkSteps, int personSteps, int webSteps, int notShown) {

        /** The counts as one line, such as {@code 4 blocks: 1 AI step, 0 shell steps with network, ...}. */
        public String sentence() {
            StringBuilder text = new StringBuilder(many(blocks, "block", "blocks")).append(": ")
                    .append(many(aiSteps, "AI step", "AI steps"));
            if (webSteps > 0) {
                text.append(" (").append(webSteps).append(" with web)");
            }
            text.append(", ").append(many(networkSteps, "shell step", "shell steps")).append(" with network, ")
                    .append(many(personSteps, "step that asks", "steps that ask")).append(" a person");
            if (notShown > 0) {
                text.append("; ").append(many(notShown, "sub-workflow", "sub-workflows")).append(" not shown, ")
                        .append(notShown == 1 ? "its" : "their").append(" blocks not counted");
            }
            return text.toString();
        }

        private static String many(int count, String one, String more) {
            return count + " " + (count == 1 ? one : more);
        }
    }
}
