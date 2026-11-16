package traceworks.core.engine;

import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The check a workflow passes before its first block runs. A workflow that Traceworks would run differently from what
 * it says is refused as a whole instead of running up to the point where it goes
 * wrong (docs/workflow-format.md). Refused are:
 * <ul>
 *   <li>a {@code format} other than {@value WorkflowDefinition#FORMAT} and a {@code format_version} above
 *       {@value WorkflowDefinition#FORMAT_VERSION}; a definition without them is admitted;</li>
 *   <li>a {@code requires} entry outside {@link WorkflowDefinition#KNOWN_REQUIREMENTS};</li>
 *   <li>a workflow that asks for an isolated run workspace ({@link WorkflowDefinition#requestsIsolatedWorkspace}):
 *       the operator isolates a run with {@code run --source}, and the workflow does not choose it;</li>
 *   <li>a block type no registered executor runs ({@link BlockTypeRegistry#isKnown}), a block without type and a
 *       {@code null} block, nested blocks included ({@link NestedBlocks});</li>
 *   <li>an unknown {@code execution_mode} on any block, nested ones included ({@link ExecutionMode#unknownValue}),
 *       and a missing one where it is required ({@link ExecutionMode#isMissing});</li>
 *   <li>a block that asks for skills ({@link BlockDefinition#requestsSkills}): Traceworks has none, so the step
 *       would run without them;</li>
 *   <li>a {@code switch} expression with the second placeholder syntax {@code ${name}}
 *       ({@link PlaceholderSyntax#dollarPlaceholderProblem}): Traceworks does not resolve it, so the switch would
 *       take another branch;</li>
 *   <li>a {@code mounts} entry without a mode ({@link #mountsWithoutMode}), nested blocks included: Docker makes it
 *       writable, Traceworks wants the mode written out;</li>
 *   <li>a {@code secrets} entry that is no literal secret name or a reserved one ({@link SecretNames#problems}),
 *       nested blocks included: the step would get the value of a variable its environment or the container
 *       client sets, or one run data picks;</li>
 *   <li>a feature the workflow uses without declaring it in {@code requires}, and an AI step the tool
 *       policy refuses: an {@code agentic} block without {@code tools}, an unknown {@code tools} value,
 *       a {@code provider} that names Codex, {@code mcp_servers}
 *       other than {@code []} and {@code user_mcp_servers: true} ({@link RunRequirements});</li>
 *   <li>in a child workflow of another package than the run's, a {@code container_image} {@code ./images/<name>}
 *       ({@link RunRequirements#packageImagesOfAnotherPackage}): the run resolves it against its own package, so the
 *       step would run the run's image of that name;</li>
 *   <li>a model choice that cannot be used, or a placeholder in {@code model} ({@link RunRequirements},
 *       {@link ModelChoices}).</li>
 * </ul>
 * The engine adds two kinds of reasons of a run, not of the definition alone ({@link #refusal(WorkflowDefinition,
 * boolean, List)}): a pick that is none of the options of a model choice, and an AI step whose model the operator
 * does not allow ({@link AllowedModels}).
 *
 * <p>Every reason is reported, not only the first. The block executors keep their own checks: a block that reaches one
 * without this check still fails instead of running wrongly.
 */
public final class RunAdmission {

    /** How the error of a refused run starts; the reasons follow, separated by {@code "; "}. */
    public static final String MESSAGE_PREFIX = "workflow refused: ";

    /** A mount written {@code host:container}, without the mode that would follow. */
    private static final Pattern WITHOUT_MODE = Pattern.compile("[^:]*[^:\\s][^:]*:[^:]*[^:\\s][^:]*");

    private final BlockTypeRegistry blockTypes;

    /** @param blockTypes the block types the engine runs */
    public RunAdmission(BlockTypeRegistry blockTypes) {
        this.blockTypes = Objects.requireNonNull(blockTypes, "blockTypes");
    }

    /**
     * The error of a refused run: {@value #MESSAGE_PREFIX} followed by every {@linkplain #reasons reason};
     * {@code null} when the workflow may run.
     */
    public String refusal(WorkflowDefinition definition) {
        return refusal(definition, false);
    }

    /**
     * Like {@link #refusal(WorkflowDefinition)}, for a workflow that a run may start as a child.
     *
     * @param otherPackage whether the workflow comes from another package than the run's
     *                     ({@link traceworks.core.port.WorkflowResolver#fromAnotherPackage})
     */
    public String refusal(WorkflowDefinition definition, boolean otherPackage) {
        return refusal(definition, otherPackage, List.of());
    }

    /**
     * Like {@link #refusal(WorkflowDefinition, boolean)}, with the reasons of the run itself in front of those of
     * the definition.
     *
     * @param ofTheRun reasons the definition alone does not tell: a refused pick of a model choice, a model the
     *                 operator does not allow
     */
    public String refusal(WorkflowDefinition definition, boolean otherPackage, List<String> ofTheRun) {
        List<String> reasons = new ArrayList<>(ofTheRun);
        reasons.addAll(reasons(definition, otherPackage));
        return reasons.isEmpty() ? null : MESSAGE_PREFIX + String.join("; ", reasons);
    }

    /** Every reason to refuse the workflow, the definition's own first, then block by block; empty when it may run. */
    public List<String> reasons(WorkflowDefinition definition) {
        return reasons(definition, false);
    }

    /**
     * Like {@link #reasons(WorkflowDefinition)}; for a workflow of another package than the run's, every
     * {@code ./images/<name>} it names is a reason too.
     */
    public List<String> reasons(WorkflowDefinition definition, boolean otherPackage) {
        List<String> reasons = new ArrayList<>();
        format(definition, reasons);
        for (String requirement : definition.requires()) {
            if (requirement == null || !WorkflowDefinition.KNOWN_REQUIREMENTS.contains(requirement)) {
                reasons.add("requires names " + (requirement == null ? "null" : "\"" + requirement + "\"")
                        + ", which Traceworks does not know (known: "
                        + String.join(", ", WorkflowDefinition.KNOWN_REQUIREMENTS) + ")");
            }
        }
        reasons.addAll(traceworks.core.model.AuditConfig.problems(definition.metadata()));
        traceworks.core.model.AuditConfig.of(definition.metadata()).ifPresent(audit -> {
            if (definition.blocks().stream().noneMatch(b -> b != null && audit.reportBlock().equals(b.getName()))) {
                reasons.add("metadata.audit.report_block '" + audit.reportBlock() + "' names no top-level block");
            }
        });
        if (definition.requestsIsolatedWorkspace()) {
            reasons.add("workspace.mode is isolated — in Traceworks the operator isolates a run, not the workflow:"
                    + " remove the setting and start the run with --source");
        }
        List<BlockDefinition> blocks = definition.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            block(blocks.get(i), i, reasons);
        }
        for (RunRequirements.Problem problem : RunRequirements.check(definition)) {
            if (problem.refuses()) {
                reasons.add(where(blocks, problem) + problem.message());
            }
        }
        if (otherPackage) {
            for (RunRequirements.Problem problem : RunRequirements.packageImagesOfAnotherPackage(definition)) {
                reasons.add(where(blocks, problem) + problem.message());
            }
        }
        return reasons;
    }

    /**
     * The reasons of {@link #reasons} for a {@code provider} that names Codex
     * ({@value RunRequirements#UNSUPPORTED_PROVIDER}), as they read there: the workflow's, then those of its blocks.
     * The start check of a run gives them for a workflow it reaches by a literal {@code workflow_id}, whose own
     * admission would refuse it only once its step runs.
     */
    public static List<String> codexReasons(WorkflowDefinition definition) {
        List<String> reasons = new ArrayList<>();
        for (RunRequirements.Problem problem : RunRequirements.check(definition)) {
            if (RunRequirements.UNSUPPORTED_PROVIDER.equals(problem.rule())) {
                reasons.add(where(definition.blocks(), problem) + problem.message());
            }
        }
        return reasons;
    }

    /** Where a problem of {@link RunRequirements} lies, as the other reasons name it: {@code Block 2 "x" › ...: }. */
    private static String where(List<BlockDefinition> blocks, RunRequirements.Problem problem) {
        if (problem.blockIndex() < 0) {
            return "";
        }
        BlockDefinition block = blocks.get(problem.blockIndex());
        String label = "Block " + (problem.blockIndex() + 1) + named(block.getName());
        NestedBlocks.Nested nested = problem.nested();
        return (nested == null ? label : label + " › " + nested.path() + named(nested.name())) + ": ";
    }

    private static void format(WorkflowDefinition definition, List<String> reasons) {
        if (definition.format() != null && !WorkflowDefinition.FORMAT.equals(definition.format())) {
            reasons.add("format \"" + definition.format() + "\" is not " + WorkflowDefinition.FORMAT);
        }
        if (definition.formatVersion() != null && definition.formatVersion() > WorkflowDefinition.FORMAT_VERSION) {
            reasons.add("format_version " + definition.formatVersion() + " is newer than this version of Traceworks"
                    + " reads (" + WorkflowDefinition.FORMAT_VERSION + ")");
        }
    }

    private void block(BlockDefinition block, int index, List<String> reasons) {
        String label = "Block " + (index + 1);
        if (block == null) {
            reasons.add(label + ": not a block (null)");
            return;
        }
        String where = label + named(block.getName());
        config(where, block.getType(), block.getConfig(), reasons);
        if (ExecutionMode.isMissing(block.getType(), block.getConfig())) {
            reasons.add(where + ": " + ExecutionMode.REQUIRED_MESSAGE);
        }
        if (block.requestsSkills()) {
            Object skillIds = block.unknownFields().get(BlockDefinition.SKILL_IDS);
            reasons.add(where + ": " + BlockDefinition.SKILL_IDS + " " + skillIds
                    + " — Traceworks has no skills, so the step would run without them");
        }
        NestedBlocks.visit(block.getConfig(), nested -> config(where + " › " + nested.path() + named(nested.name()),
                nested.type(), nested.config(), reasons));
    }

    /**
     * The checks of one block, top-level or nested: its type, its execution mode, a switch expression, its mounts and
     * its secrets.
     */
    private void config(String where, String type, Map<String, Object> config, List<String> reasons) {
        if (type == null || type.isBlank()) {
            reasons.add(where + ": no block type");
        } else if (!blockTypes.isKnown(type)) {
            reasons.add(where + ": unknown block type \"" + type + "\" (known: " + blockTypes.knownTypesAsText() + ")");
        }
        String mode = ExecutionMode.unknownValue(config);
        if (mode != null) {
            reasons.add(where + ": " + mode);
        }
        if ("switch".equals(type) && config != null && config.get("expression") instanceof String expression) {
            String unresolvable = PlaceholderSyntax.dollarPlaceholderProblem(expression);
            if (unresolvable != null) {
                reasons.add(where + ": the switch expression " + unresolvable);
            }
        }
        for (String mount : mountsWithoutMode(config)) {
            reasons.add(where + ": the mount " + mountWithoutModeProblem(mount));
        }
        for (String problem : SecretNames.problems(config)) {
            reasons.add(where + ": " + problem);
        }
    }

    /**
     * The entries of {@code mounts} in a block's config that are written {@code host:container}, without
     * {@code :ro} or {@code :rw}, as written; empty without such entries. An entry the run resolves from a single
     * placeholder is not judged here: the shell step refuses it when it runs.
     */
    public static List<String> mountsWithoutMode(Map<String, Object> config) {
        if (config == null || !(config.get("mounts") instanceof List<?> mounts)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (Object mount : mounts) {
            if (mount instanceof String entry && WITHOUT_MODE.matcher(entry.strip()).matches()) {
                found.add(entry);
            }
        }
        return found;
    }

    /** What is wrong with a mount {@link #mountsWithoutMode} found, and how to write it: {@code "<mount>" has ...}. */
    public static String mountWithoutModeProblem(String mount) {
        String entry = mount.strip();
        return "\"" + entry + "\" has no mode — write \"" + entry + ":ro\" (read-only) or \"" + entry
                + ":rw\" (writable)";
    }

    private static String named(String name) {
        return name == null || name.isBlank() ? "" : " \"" + name + "\"";
    }
}
