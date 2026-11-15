package traceworks.core.analyzer;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.engine.BlockTypeRegistry;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;
import traceworks.core.port.PackageImage;
import traceworks.core.port.WorkflowResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks a workflow definition deterministically for known construction errors, without an AI call. This is the
 * analyzer behind {@code traceworks check}: it reports, it does not block.
 *
 * <p>The rules are decidable from the text, some of them from the text plus the definition's metadata, the key rules
 * against the {@link BlockConfigSchema}, and the type rule against the engine's block registry. Each rule has a stable
 * key ({@link AnalyzerFinding#rule()}):
 * <ul>
 *   <li>{@code empty-workflow}: a definition without blocks;</li>
 *   <li>wiring ({@link WiringRules}): {@code unknown-block-reference}, {@code unknown-block-property},
 *       {@code step-output-out-of-range}, {@code chain-only-placeholder}, {@code audit-only-placeholder},
 *       {@code unwired-output};</li>
 *   <li>{@code duplicate-block-name} ({@link BlockNameRules}) and {@code unknown-variable}
 *       ({@link VariableRules});</li>
 *   <li>hygiene ({@link HygieneRules}): {@code absolute-home-path}, {@code missing-strict-mode},
 *       {@code duplicated-instructions}, {@code dated-model-id};</li>
 *   <li>{@code on-no-without-reject-option} ({@link ApprovalRules}) and {@code items-command-without-source}
 *       ({@link ForEachRules});</li>
 *   <li>config keys ({@link ConfigKeyRules}): {@code unknown-config-key}, {@code missing-required-config-key},
 *       {@code invalid-config-value}, {@code legacy-config-key};</li>
 *   <li>the stages of a compress block ({@link CompressRules}): {@code invalid-config-value} and
 *       {@code unknown-config-key}, no new ids;</li>
 *   <li>block shape ({@link BlockShapeRules}): {@code dead-nested-blocks}, {@code unknown-block-field};</li>
 *   <li>{@code secret-mount}, {@code mount-mode-required} ({@link MountRules});</li>
 *   <li>{@code invalid-secret-name} ({@link SecretRules});</li>
 *   <li>requirements and the tool policy ({@link RequirementRules}): {@code missing-requirement},
 *       {@code unused-requirement}, {@code tools-required}, {@code unsupported-provider},
 *       {@code unsupported-mcp-servers}, and {@code invalid-config-value} for {@code tools};</li>
 *   <li>shell quoting ({@link QuotingRules}): {@code quoted-placeholder}, {@code placeholder-self-compare},
 *       {@code heredoc-placeholder}, {@code unquoted-ai-output};</li>
 *   <li>the isolation setting {@code workspace} ({@link WorkspaceRules}): {@code legacy-install-npm},
 *       {@code unknown-workspace-mode}, {@code workspace-not-on-first-block}, {@code build-in-project-path};</li>
 *   <li>Traceworks ({@link TraceworksRules}): {@code unknown-format}, {@code unknown-requirement},
 *       {@code unsupported-workspace-isolation}, {@code explicit-execution-mode}, {@code unsupported-block-type},
 *       {@code unsupported-skills}, {@code dollar-placeholder-in-switch}, {@code docker-socket-refused},
 *       {@code unbalanced-placeholder}, {@code report-block-required}, {@code unprovided-variable},
 *       {@code blank-ai-prompt}, {@code unknown-provider}, {@code host-dependent-step},
 *       {@code invalid-audit-config}, {@code invalid-audit-key},
 *       {@code report-block-missing}, {@code catalog-invalid}, {@code previous-report-outside-audit};</li>
 *   <li>parking ({@link ParkingRules}): {@code nested-wait-point}, {@code question-changes-on-resume};</li>
 *   <li>package images ({@link PackageImageRules}): {@code child-package-image}, {@code invalid-image-name},
 *       {@code unpinned-base-image}, {@code image-without-description};</li>
 *   <li>child workflows ({@link ChildProviderRules}): {@code unsupported-provider} for a child that names Codex;</li>
 *   <li>{@code unknown-model-backend} ({@link ModelBackendRules}), for an analyzer that knows the AI CLIs
 *       ({@link #withAiClis}).</li>
 * </ul>
 *
 * <p>The analyzer reports, the engine refuses: the reasons of the engine's run admission
 * ({@link traceworks.core.engine.RunAdmission}) are {@code ERROR} findings here too, so
 * {@code traceworks check} shows before a run what would refuse it. {@code traceworks run} goes further and starts no
 * run of a definition with an {@code ERROR} finding, whichever rule it comes from; the engine itself still refuses
 * only what its admission names.
 *
 * <p><b>What it deliberately does not check.</b> Rules that need more than the text: redundant AI calls (a
 * similarity judgement over two tasks), "a shell block would have been enough here" (an assessment, not a
 * measurement) and timeouts that are too tight (needs run-time data). A rule that has to guess produces noise, and a
 * checking tool that nobody believes gets switched off. For the same reason a rule that was to find prompts ordering a
 * {@code gate_status} marker was dropped: on review, none of its hits on real definitions was real, because telling
 * "orders a marker" from "talks about markers" needs text understanding, not pattern search; the gate expectation is
 * explicit in Traceworks ({@code metadata.gate.expected}).
 *
 * <p>No rule needs a running instance: none checks whether Docker is reachable or whether the source is a Git
 * repository. The analyzer reads the definition only.
 *
 * <p>The entry point is {@code analyze(workflow)}, on a {@link WorkflowDefinition}.
 */
public final class WorkflowAnalyzer {

    private final BlockTypeRegistry registry;
    /** The names of the inputs of the run the definition is checked for: variables that have a value. */
    private final Set<String> inputs;
    /** The AI CLIs a run would use, for {@code unknown-model-backend}; empty when the analyzer was given none. */
    private final List<AiCli> clis;

    /** An analyzer for the block types of the core ({@link CoreBlocks#registry}). */
    public WorkflowAnalyzer() {
        this(CoreBlocks.registry(null));
    }

    /** An analyzer for the block types {@code registry} runs ({@code unsupported-block-type} reports the others). */
    public WorkflowAnalyzer(BlockTypeRegistry registry) {
        this(registry, Set.of());
    }

    private WorkflowAnalyzer(BlockTypeRegistry registry, Set<String> inputs) {
        this(registry, inputs, List.of());
    }

    private WorkflowAnalyzer(BlockTypeRegistry registry, Set<String> inputs, List<AiCli> clis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.inputs = inputs;
        this.clis = clis;
    }

    /**
     * An analyzer that checks a definition for a run with these inputs: {@code unknown-variable} does not report a
     * variable one of them sets. Without it, a definition is checked on its own, and a variable that only an input
     * sets is reported.
     *
     * @param inputNames the names of the run's inputs
     */
    public WorkflowAnalyzer withInputs(Collection<String> inputNames) {
        return new WorkflowAnalyzer(registry, Set.copyOf(inputNames), clis);
    }

    /**
     * An analyzer that knows the AI CLIs a run would use: {@code unknown-model-backend} asks them about the models
     * the definition names as literals. Without it, that rule reports nothing.
     */
    public WorkflowAnalyzer withAiClis(Collection<AiCli> aiClis) {
        return new WorkflowAnalyzer(registry, inputs, List.copyOf(aiClis));
    }

    /**
     * What is wrong with the inputs of a run of {@code workflow}, one English text per input: an input that no block
     * reads and that is not in {@code defaultContext}, so that it changes nothing in the run ({@link VariableRules}).
     * No finding of a definition: it judges one run's inputs.
     *
     * @param inputNames the names of the run's inputs
     */
    public static List<String> unreadInputs(WorkflowDefinition workflow, Collection<String> inputNames) {
        return VariableRules.unreadInputs(workflow, inputNames);
    }

    /**
     * The findings of a definition, most severe first and within equal severity in block order (findings on the
     * definition as a whole first). The rules on the definition itself ({@code requires}, {@code provider}) also run
     * when it has no blocks, which is itself the finding {@code empty-workflow}: a run of it does nothing and ends
     * COMPLETED.
     */
    public List<AnalyzerFinding> analyze(WorkflowDefinition workflow) {
        return analyze(workflow, null);
    }

    /**
     * Like {@link #analyze(WorkflowDefinition)}, and looks into the workflows the definition's {@code sub-workflow}
     * and {@code foreach} blocks run, for the questions there ({@code nested-wait-point}).
     *
     * @param children finds the child workflows; {@code null} to look into none
     */
    public List<AnalyzerFinding> analyze(WorkflowDefinition workflow, WorkflowResolver children) {
        return analyze(workflow, children, List.of());
    }

    /**
     * Like {@link #analyze(WorkflowDefinition, WorkflowResolver)}, and checks the Dockerfiles of the package's images
     * ({@code unpinned-base-image}).
     *
     * @param images the package's images; empty for none
     */
    public List<AnalyzerFinding> analyze(WorkflowDefinition workflow, WorkflowResolver children,
                                         java.util.Collection<PackageImage> images) {
        return analyze(workflow, children, images, List.of());
    }

    /**
     * Like {@link #analyze(WorkflowDefinition, WorkflowResolver, java.util.Collection)}, and names the directories
     * under the package's {@code images/} that have a Dockerfile but no valid name ({@code invalid-image-name}).
     *
     * @param invalidImageDirectories the names of those directories; empty for none
     */
    public List<AnalyzerFinding> analyze(WorkflowDefinition workflow, WorkflowResolver children,
                                         java.util.Collection<PackageImage> images,
                                         List<String> invalidImageDirectories) {
        return analyze(workflow, children, images, invalidImageDirectories, null);
    }

    /**
     * Like {@link #analyze(WorkflowDefinition, WorkflowResolver, java.util.Collection, List)}, and checks the
     * package's {@code catalog.json} ({@code catalog-invalid}).
     *
     * @param catalogJson the bytes of {@code catalog.json}; {@code null} for none
     */
    public List<AnalyzerFinding> analyze(WorkflowDefinition workflow, WorkflowResolver children,
                                         java.util.Collection<PackageImage> images,
                                         List<String> invalidImageDirectories, byte[] catalogJson) {
        if (workflow == null) {
            return List.of();
        }
        List<AnalyzerFinding> findings = new ArrayList<>();
        TraceworksRules.applyDefinition(workflow, findings);
        TraceworksRules.auditRules(workflow, findings);
        TraceworksRules.catalog(catalogJson, findings);
        PackageImageRules.unpinnedBaseImages(images, findings);
        PackageImageRules.imagesWithoutDescription(images, findings);
        PackageImageRules.invalidNames(invalidImageDirectories, findings);
        RequirementRules.apply(workflow, findings);
        ModelChoiceRules.apply(workflow, clis, findings);
        List<BlockDefinition> blocks = workflow.blocks();
        if (blocks.isEmpty()) {
            findings.add(AnalyzerFinding.of("empty-workflow", AnalyzerFinding.Severity.ERROR, -1, null,
                    "the workflow has no blocks — a run of it does nothing and still ends COMPLETED; add a block"));
        } else {
            Map<String, Object> metadata = workflow.metadata();
            WiringRules.apply(blocks, metadata, workflow.defaultContext(), findings);
            BlockNameRules.apply(blocks, findings);
            VariableRules.apply(workflow, inputs, findings);
            HygieneRules.apply(blocks, workflow.instructions(), findings);
            ApprovalRules.apply(blocks, findings);
            ForEachRules.apply(blocks, findings);
            ConfigKeyRules.apply(blocks, findings);
            CompressRules.apply(blocks, findings);
            BlockShapeRules.apply(blocks, findings);
            MountRules.apply(blocks, findings);
            SecretRules.apply(blocks, findings);
            QuotingRules.apply(blocks, findings);
            WorkspaceRules.apply(workflow, findings);
            TraceworksRules.apply(blocks, metadata, registry.knownTypes(), findings);
            ParkingRules.apply(blocks, children, findings);
            PackageImageRules.apply(blocks, children, findings);
            ChildProviderRules.apply(blocks, children, findings);
            ModelBackendRules.apply(workflow, clis, findings);
        }
        return sorted(findings);
    }

    /**
     * Whether a block of this type and config makes an AI call that reads {@code provider}: a block without a
     * provider of its own then uses the workflow's.
     *
     * @param nested whether the block sits inside another block's config (a switch case)
     */
    public static boolean readsProvider(String type, Map<String, Object> config, boolean nested) {
        return TraceworksRules.readsProvider(type, config, nested);
    }

    /** Like {@link #analyze(WorkflowDefinition)} on a loose block list without metadata, for the tests. */
    List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions) {
        return analyze(blocks, instructions, Map.of());
    }

    /** Like {@link #analyze(WorkflowDefinition)} on a loose block list with the definition's metadata. */
    List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions, Map<String, Object> metadata) {
        return analyze(new WorkflowDefinition(null, null, null, blocks, instructions, null, null, null, metadata, null,
                null, null, null));
    }

    /** Most severe first, within equal severity in block order. */
    private static List<AnalyzerFinding> sorted(List<AnalyzerFinding> findings) {
        List<AnalyzerFinding> ordered = new ArrayList<>(findings);
        ordered.sort((a, b) -> a.severity() != b.severity()
                ? a.severity().compareTo(b.severity())
                : Integer.compare(a.blockIndex(), b.blockIndex()));
        return List.copyOf(ordered);
    }
}
