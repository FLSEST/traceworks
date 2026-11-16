package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.BlockAiSettings;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.PlatformVariables;
import traceworks.core.engine.RunVariables;
import traceworks.core.engine.RunRequirements;
import traceworks.core.model.AuditConfig;
import traceworks.core.register.Catalog;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules on what Traceworks refuses or does not provide. Where a definition relies on a default Traceworks does not
 * have or on a feature it does not run, the analyzer says so before a run, so a workflow never silently means something
 * other than it says (docs/workflow-format.md). The errors marked "refused" are the reasons of the engine's run
 * admission: such a
 * workflow starts no block. The rules that read the Dockerfiles of a package's {@code images/} are in
 * {@link PackageImageRules}.
 * <ul>
 *   <li>{@code unknown-format} ({@code ERROR}, refused): a {@code format} other than {@value WorkflowDefinition#FORMAT}
 *       or a {@code format_version} above {@value WorkflowDefinition#FORMAT_VERSION}. A definition without them
 *       is fine.</li>
 *   <li>{@code unknown-requirement} ({@code ERROR}, refused): an entry of {@code requires} outside
 *       {@link WorkflowDefinition#KNOWN_REQUIREMENTS}.</li>
 *   <li>{@code unsupported-workspace-isolation} ({@code ERROR}, refused): {@code workspace.mode: isolated} in
 *       {@code metadata} or on the first block. A workflow cannot isolate its own run: the
 *       operator isolates a run with {@code run --source}.</li>
 *   <li>{@code explicit-execution-mode} ({@code ERROR}, refused): {@code conditional}, {@code retry}, {@code while} or
 *       {@code foreach} without {@code execution_mode} ({@link ExecutionMode#isMissing}). A workflow states
 *       whether a step asks a model. A foreach with {@code workflow_id} runs no command per item and needs none.</li>
 *   <li>{@code unsupported-block-type} ({@code ERROR}, refused): a block type the engine's registry does not run, a
 *       block without type, or a null entry.</li>
 *   <li>{@code unsupported-skills} ({@code ERROR}, refused): {@code skillIds} on a block
 *       ({@link BlockDefinition#requestsSkills}). Traceworks has no skills, so the step would run
 *       without them.</li>
 *   <li>{@code dollar-placeholder-in-switch} ({@code ERROR}, refused): a {@code switch} expression with
 *       {@code ${name}}, which Traceworks does not resolve.</li>
 *   <li>{@code docker-socket-refused} ({@code ERROR}): {@code mount_docker_socket: true} where a shell step reads it:
 *       a {@code shell} block, a wrapper in shell mode, a shell block in a switch case. Traceworks refuses the
 *       step.</li>
 *   <li>{@code unbalanced-placeholder} ({@code ERROR}): {@code {{x }}} or {@code {{ x}}}. The resolver reads only
 *       {@code {{x}}} and {@code {{ x }}}; anything else stays literal.</li>
 *   <li>{@code report-block-required} ({@code ERROR}): {@code previous_report} without
 *       {@code metadata.audit.report_block}. The previous report is the output of that block.</li>
 *   <li>{@code unprovided-variable} ({@code WARNING}): a placeholder of a variable Traceworks does not
 *       provide: the engine's {@link PlatformVariables} except {@code previous_report}
 *       and {@code audit_findings}, which Traceworks keeps ({@code open_todos}, {@code request_metrics},
 *       {@code goal_*}, {@code execution_path_*}, ...; a run input never sets them, so they stay literal), and
 *       {@code todo_*}, {@code project_id}, {@code home_dir}, {@code platform_scripts} (a run input of the same name
 *       is used as is, otherwise they stay literal).</li>
 *   <li>{@code blank-ai-prompt} ({@code WARNING}): an AI prompt that is blank: the command of {@code simple}, the
 *       condition of {@code conditional} and the command of {@code retry}, {@code while} and {@code foreach} in
 *       {@code ai} mode. It is sent to the model anyway (a blank {@code while} condition is refused at run
 *       time instead).</li>
 *   <li>{@code unknown-provider} ({@code ERROR}): a {@code provider} of the workflow, or of a block that reads it
 *       ({@link #readsProvider}), outside the values of the schema's AI field {@code provider}, compared ignoring
 *       case. Codex is {@code unsupported-provider} instead ({@link RunRequirements}). Traceworks refuses the step: an
 *       unknown id never falls back to a default provider.</li>
 *   <li>{@code invalid-audit-config}, {@code invalid-audit-key}, {@code report-block-missing},
 *       {@code catalog-invalid} ({@code ERROR}): a {@code metadata.audit} or {@code catalog.json} the register cannot
 *       use ({@link #auditRules}, {@link #catalog}).</li>
 *   <li>{@code register-dir-outside-audit} ({@code ERROR}): {@code register_dir} in a workflow that feeds no
 *       register, so its run has no register folder and the placeholder would stay as text.</li>
 *   <li>{@code register-dir-in-ai-prompt} ({@code ERROR}): {@code register_dir} in the prompt of an AI call, whose
 *       container does not get the folder.</li>
 *   <li>{@code previous-report-outside-audit} ({@code ERROR}): {@code previous_report} in a workflow without an audit
 *       block (decided 2026-11-11).</li>
 *   <li>{@code host-dependent-step} ({@code WARNING}): a shell step without {@code container_image} that calls a
 *       network or host tool or uses an absolute host path. Traceworks runs steps in containers.</li>
 * </ul>
 */
final class TraceworksRules {

    private TraceworksRules() {}

    /** The config key of a block's AI provider. */
    static final String PROVIDER_KEY = BlockAiSettings.PROVIDER_KEY;

    /** The {@link PlatformVariables} Traceworks keeps: the registers of a later version bring them back. */
    private static final Set<String> KEPT_PLATFORM_VARIABLES = Set.of("previous_report", "audit_findings",
            "audit_items");

    /**
     * Variables beyond the {@link PlatformVariables} that Traceworks does not provide, and the prefixes of
     * the families.
     */
    private static final Set<String> UNPROVIDED = Set.of("project_id", "home_dir", "platform_scripts");
    private static final List<String> UNPROVIDED_PREFIXES = List.of("todo_", "goal_");

    /** Block types Traceworks knows by name and does not run, for a clearer message than "unknown". */
    private static final Set<String> UNSUPPORTED_BLOCK_TYPES = Set.of("skill-capability", "integration", "document",
            "todo-create", "todo-update", "todo-close", "dashboard-report");

    /** A network or host tool called as a word: not part of a longer name, a file name or a variable. */
    private static final Pattern HOST_TOOL = Pattern.compile("(?<![\\w.$-])(curl|wget|mvn|npm|pip|docker)(?![\\w.-])");

    /** An absolute path under a host's user directories, with the rest of the path. */
    private static final Pattern HOST_PATH = Pattern.compile(
            "(?<![\\w.~/:$-])/(?:home|Users|root)(?![\\w.-])(?:/[^\\s'\"`;|&()<>]*)?");

    /**
     * The audit rules of a definition: {@code invalid-audit-config} and {@code invalid-audit-key} for a broken
     * {@code metadata.audit}, {@code report-block-missing} when {@code report_block} names no top-level block, and
     * {@code previous-report-outside-audit} for {@code previous_report} in a workflow without an audit block
     * (decided 2026-11-11: an error, not a literal), and {@code register-dir-outside-audit} for {@code register_dir}
     * in a workflow that feeds no register.
     */
    static void auditRules(WorkflowDefinition workflow, List<AnalyzerFinding> out) {
        Map<String, Object> metadata = workflow.metadata();
        for (String problem : AuditConfig.problems(metadata)) {
            boolean key = problem.startsWith("metadata.audit.key '");
            out.add(AnalyzerFinding.of(key ? "invalid-audit-key" : "invalid-audit-config", Severity.ERROR, -1, null,
                    problem + (key ? "" : " — the audit is understood completely or not at all, so the"
                            + " workflow is refused")));
        }
        AuditConfig config = AuditConfig.of(metadata).orElse(null);
        if (config != null && workflow.blocks().stream().noneMatch(block -> block != null
                && config.reportBlock().equals(block.getName()))) {
            out.add(AnalyzerFinding.of("report-block-missing", Severity.ERROR, -1, null, "metadata.audit.report_block '"
                    + config.reportBlock() + "' names no top-level block, so no run has a report to feed the register"));
        }
        registerDirInPrompts(workflow, out);
        if (config == null && AuditConfig.problems(metadata).isEmpty()) {
            // No audit, or one that is switched off: no register, so no report. A broken audit block has its own error
            List<BlockDefinition> blocks = workflow.blocks();
            for (int i = 0; i < blocks.size(); i++) {
                BlockDefinition block = blocks.get(i);
                if (block == null || !PlaceholderSyntax.keysIn(BlockText.joined(block)).contains("previous_report")) {
                    continue;
                }
                out.add(AnalyzerFinding.of("previous-report-outside-audit", Severity.ERROR, i, block.getName(),
                        "uses {{ previous_report }}, which only an audit workflow has (metadata.audit with enabled,"
                                + " key, category and report_block); nothing else fills it, and Traceworks refuses the"
                                + " run rather than run it with the placeholder left in the text"));
            }
        }
        if (config == null && AuditConfig.problems(metadata).isEmpty()) {
            // No audit, or one that is switched off: no register, so no folder. A broken audit block has its own error
            List<BlockDefinition> blocks = workflow.blocks();
            for (int i = 0; i < blocks.size(); i++) {
                BlockDefinition block = blocks.get(i);
                if (block == null
                        || !PlaceholderSyntax.keysIn(BlockText.joined(block)).contains(RunVariables.REGISTER_DIR)) {
                    continue;
                }
                out.add(AnalyzerFinding.of("register-dir-outside-audit", Severity.ERROR, i, block.getName(),
                        "uses {{ register_dir }}, the folder with the files of the register, which only the run of an"
                                + " audit workflow has (metadata.audit with enabled, key, category and report_block)."
                                + " A workflow that an audit run starts sees that run's folder when it runs, but check"
                                + " judges it as what it is by itself: read the folder in the audit workflow, or hand"
                                + " its path on under another name (a foreach context entry such as \"known_dir\":"
                                + " \"{{ register_dir }}\"; the name register_dir itself is reserved). In a run that"
                                + " has no folder, a step that names it fails before it runs anything"));
            }
        }
    }

    /**
     * {@code register-dir-in-ai-prompt}: {@code register_dir} in a text a block sends to a model. The container of
     * an AI call does not get the folder, and in a host run the prompt would carry a path of the run folder; the run
     * fails such a step before the call.
     */
    private static void registerDirInPrompts(WorkflowDefinition workflow, List<AnalyzerFinding> out) {
        List<BlockDefinition> blocks = workflow.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) {
                continue;
            }
            List<String> prompts = new java.util.ArrayList<>(BlockText.instructions(block));
            BlockText.nestedInstructions(block).values().forEach(prompts::addAll);
            if ("agentic".equals(block.getType()) && block.getConfig() != null
                    && block.getConfig().get("success_criteria") instanceof String criteria) {
                prompts.add(criteria);
            }
            if (prompts.stream().anyMatch(text -> PlaceholderSyntax.keysIn(text).contains(RunVariables.REGISTER_DIR))) {
                out.add(AnalyzerFinding.of("register-dir-in-ai-prompt", Severity.ERROR, i, block.getName(),
                        "puts {{ register_dir }} into the prompt of an AI step: the container of an AI call does not"
                                + " get the register folder, so the path names nothing there, and no model is handed"
                                + " the path of a run folder. The run fails the step before the call. Let a shell"
                                + " step read the files and put what the model needs into its output"));
            }
        }
    }

    /** {@code catalog-invalid}: a {@code catalog.json} the register cannot read. */
    static void catalog(byte[] catalogJson, List<AnalyzerFinding> out) {
        if (catalogJson == null) return;
        try {
            Catalog.parse(catalogJson);
        } catch (IllegalArgumentException e) {
            out.add(AnalyzerFinding.of("catalog-invalid", Severity.ERROR, -1, null, e.getMessage()));
        }
    }

    /** The rules on the definition as a whole: its format, requirements, workspace setting and provider. */
    static void applyDefinition(WorkflowDefinition workflow, List<AnalyzerFinding> out) {
        unknownFormat(workflow, out);
        for (String requirement : workflow.requires()) {
            if (requirement != null && WorkflowDefinition.KNOWN_REQUIREMENTS.contains(requirement)) continue;
            String shown = requirement == null ? "null" : "\"" + requirement + "\"";
            String hint = "container.network-none".equals(requirement)
                    ? "; drop it: no network is the default, a step gets network only with network: true" : "";
            out.add(AnalyzerFinding.of("unknown-requirement", Severity.ERROR, -1, null,
                    "requires names " + shown + ", which Traceworks does not know — it refuses the workflow; known are "
                            + String.join(", ", WorkflowDefinition.KNOWN_REQUIREMENTS) + hint));
        }
        unsupportedWorkspaceIsolation(workflow, out);
        String provider = workflow.provider();
        BlockConfigSchema.Field providers = providerField(BlockConfigSchema.get());
        if (provider != null && !provider.isBlank() && !providers.allows(provider)
                && !RunRequirements.isCodex(provider)) {
            out.add(AnalyzerFinding.of("unknown-provider", Severity.ERROR, -1, null,
                    "provider \"" + provider + "\" is not one of " + String.join(", ", providers.values())
                            + " — Traceworks refuses every AI step that uses it: a step that asks for one model must"
                            + " never silently run on another" + placeholderNote(provider)));
        }
    }

    private static void unknownFormat(WorkflowDefinition workflow, List<AnalyzerFinding> out) {
        if (workflow.format() != null && !WorkflowDefinition.FORMAT.equals(workflow.format())) {
            out.add(AnalyzerFinding.of("unknown-format", Severity.ERROR, -1, null, "format \"" + workflow.format()
                    + "\" is not " + WorkflowDefinition.FORMAT + " — Traceworks refuses the workflow"));
        }
        if (workflow.formatVersion() != null && workflow.formatVersion() > WorkflowDefinition.FORMAT_VERSION) {
            out.add(AnalyzerFinding.of("unknown-format", Severity.ERROR, -1, null, "format_version "
                    + workflow.formatVersion() + " is newer than this version of Traceworks reads ("
                    + WorkflowDefinition.FORMAT_VERSION + ") — it refuses the workflow"));
        }
    }

    /** On the definition when the metadata holds the setting, else on the first block, the other place it is read. */
    private static void unsupportedWorkspaceIsolation(WorkflowDefinition workflow, List<AnalyzerFinding> out) {
        if (!workflow.requestsIsolatedWorkspace()) return;
        boolean inMetadata = workflow.metadata().get(WorkflowDefinition.WORKSPACE_KEY) instanceof Map<?, ?>;
        out.add(AnalyzerFinding.of("unsupported-workspace-isolation", Severity.ERROR, inMetadata ? -1 : 0,
                inMetadata ? null : workflow.blocks().getFirst().getName(),
                (inMetadata ? "metadata." : "") + "workspace.mode is isolated — a workflow cannot isolate its"
                        + " own run; the operator isolates a run with run --source, so the workflow is"
                        + " refused: remove the setting and start the run with --source"));
    }

    /** The rules on the blocks; {@code blockTypes} are the types the engine runs. */
    static void apply(List<BlockDefinition> blocks, Map<String, Object> metadata, Set<String> blockTypes,
                      List<AnalyzerFinding> out) {
        BlockConfigSchema schema = BlockConfigSchema.get();
        boolean reportBlock = metadata.get("audit") instanceof Map<?, ?> audit
                && audit.get("report_block") instanceof String name && !name.isBlank();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            unsupportedBlockType(block, i, blockTypes, out);
            if (block == null) continue;
            explicitExecutionMode(block, i, out);
            unsupportedSkills(block, i, out);
            dollarPlaceholderInSwitch(block, i, out);
            dockerSocketRefused(block, i, out);
            unbalancedPlaceholder(block, i, out);
            if (!reportBlock) reportBlockRequired(block, i, out);
            unprovidedVariable(block, i, out);
            blankAiPrompt(block, i, out);
            unknownProvider(schema, block, i, out);
            hostDependentStep(block, i, out);
        }
    }

    private static void explicitExecutionMode(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        if (!ExecutionMode.isMissing(block.getType(), block.getConfig())) return;
        out.add(AnalyzerFinding.of("explicit-execution-mode", Severity.ERROR, i, block.getName(),
                "the " + block.getType() + " block has no execution_mode — Traceworks refuses the workflow:"
                        + " a workflow states whether a step asks a model; write it out: shell or ai"));
    }

    private static void unsupportedSkills(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        if (!block.requestsSkills()) return;
        out.add(AnalyzerFinding.of("unsupported-skills", Severity.ERROR, i, block.getName(),
                BlockDefinition.SKILL_IDS + " " + block.unknownFields().get(BlockDefinition.SKILL_IDS)
                        + " — Traceworks has no skills, so the step would run without them, and refuses the"
                        + " workflow; put what the step needs into its prompt"));
    }

    private static void dollarPlaceholderInSwitch(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        BlockConfigs.walk(block, i, config -> dollarPlaceholderInSwitch(config.type(), config.config(), i,
                config.name(), config.prefix(), out));
    }

    private static void dollarPlaceholderInSwitch(String type, Map<String, Object> config, int i, String name,
                                                  String prefix, List<AnalyzerFinding> out) {
        if (!"switch".equals(type) || config == null || !(config.get("expression") instanceof String expression)) {
            return;
        }
        String problem = PlaceholderSyntax.dollarPlaceholderProblem(expression);
        if (problem == null) return;
        out.add(AnalyzerFinding.of("dollar-placeholder-in-switch", Severity.ERROR, i, name, prefix
                + "the switch expression " + problem + " — Traceworks refuses the workflow"));
    }

    private static void dockerSocketRefused(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        BlockConfigs.walk(block, i, config -> dockerSocketRefused(config.type(), config.config(), config.nested(), i,
                config.name(), config.prefix(), out));
    }

    /**
     * Where a shell step reads {@code mount_docker_socket}: a {@code shell} block, a wrapper in shell mode, and in a
     * switch case a {@code simple} block in shell mode. Elsewhere the setting has no effect ({@code ConfigKeyRules}
     * reports it).
     */
    private static void dockerSocketRefused(String type, Map<String, Object> config, boolean nested, int i,
                                            String name, String prefix, List<AnalyzerFinding> out) {
        if (type == null || config == null || !Boolean.TRUE.equals(config.get("mount_docker_socket"))) return;
        boolean shellStep = switch (type) {
            case "shell" -> true;
            case "conditional", "retry", "while", "foreach" -> !nested && BlockText.runsAsShell(config);
            case "simple" -> nested && BlockText.runsAsShell(config);
            default -> false;
        };
        if (!shellStep) return;
        out.add(AnalyzerFinding.of("docker-socket-refused", Severity.ERROR, i, name, prefix + "mount_docker_socket:"
                + " true — Traceworks never mounts the Docker socket and refuses the step; remove the setting"));
    }

    private static void unbalancedPlaceholder(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : BlockText.allStrings(block)) {
            Matcher m = PlaceholderSyntax.PLACEHOLDER.matcher(text);
            while (m.find()) {
                if (!m.group(2).equals(m.group(2).strip())) found.add(m.group());
            }
        }
        if (found.isEmpty()) return;
        out.add(AnalyzerFinding.of("unbalanced-placeholder", Severity.ERROR, i, block.getName(),
                String.join(", ", found) + " — unbalanced spaces inside the braces: the resolver reads only {{x}} and"
                        + " {{ x }}, so the placeholder stays literal in the run; write {{ x }}"));
    }

    private static void reportBlockRequired(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        if (!PlaceholderSyntax.keysIn(BlockText.joined(block)).contains("previous_report")) return;
        out.add(AnalyzerFinding.of("report-block-required", Severity.ERROR, i, block.getName(),
                "uses {{ previous_report }}, but metadata.audit.report_block names no block — the previous report is"
                        + " the output of that block; name the"
                        + " block that writes the report"));
    }

    private static void unsupportedBlockType(BlockDefinition block, int i, Set<String> blockTypes,
                                             List<AnalyzerFinding> out) {
        if (block == null) {
            out.add(AnalyzerFinding.of("unsupported-block-type", Severity.ERROR, i, null,
                    "block " + (i + 1) + " is null, not a block — Traceworks refuses the workflow"));
            return;
        }
        BlockConfigs.walk(block, i, config -> {
            String problem = typeProblem(config.type(), blockTypes);
            if (problem != null) {
                out.add(AnalyzerFinding.of("unsupported-block-type", Severity.ERROR, i, config.name(),
                        config.prefix() + problem));
            }
        });
    }

    /** What is wrong with a block type; null for a type the engine runs. */
    private static String typeProblem(String type, Set<String> blockTypes) {
        if (type == null || type.isBlank()) {
            return "the block has no type — Traceworks refuses the workflow";
        }
        if (blockTypes.contains(type)) return null;
        if (UNSUPPORTED_BLOCK_TYPES.contains(type)) {
            return type + " is a block type Traceworks does not run; it refuses the workflow";
        }
        String suggestion = FindingText.suggestion(type, blockTypes);
        return "block type \"" + type + "\" is not supported, Traceworks refuses the workflow — it runs "
                + String.join(", ", blockTypes) + (suggestion == null ? "" : "; did you mean " + suggestion + "?");
    }

    private static void unprovidedVariable(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        Set<String> found = new LinkedHashSet<>();
        for (String key : PlaceholderSyntax.keysIn(BlockText.joined(block))) {
            String name = key.strip();
            if (unprovided(name)) found.add("{{ " + name + " }}");
        }
        if (found.isEmpty()) return;
        out.add(AnalyzerFinding.of("unprovided-variable", Severity.WARNING, i, block.getName(),
                String.join(", ", found) + " — Traceworks does not provide them:"
                        + " a run input of the same name is used as is (never for a platform data name such as"
                        + " open_todos), otherwise the placeholder stays literal; package files ({{ package_dir }}) and"
                        + " the workspace variables replace what is needed"));
    }

    /** Whether the variable is one a workflow may name and Traceworks does not provide. */
    static boolean unprovided(String name) {
        return PlatformVariables.contains(name) && !KEPT_PLATFORM_VARIABLES.contains(name)
                || UNPROVIDED.contains(name) || UNPROVIDED_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static void blankAiPrompt(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        BlockConfigs.walk(block, i, config -> blankAiPrompt(config.type(), config.config(), config.nested(), i,
                config.name(), config.prefix(), out));
    }

    private static void blankAiPrompt(String type, Map<String, Object> config, boolean nested, int i, String name,
                                      String prefix, List<AnalyzerFinding> out) {
        if (type == null || config == null) return;
        String key = switch (type) {
            // a nested simple block with execution_mode: shell runs its command as a shell (switch cases)
            case "simple" -> nested && BlockText.runsAsShell(config) ? null : "command";
            case "conditional" -> aiMode(config) ? "condition" : null;
            case "retry", "while" -> aiMode(config) ? "command" : null;
            case "foreach" -> aiMode(config) && !(config.get("workflow_id") instanceof String id && !id.isBlank())
                    ? "command" : null;
            default -> null;
        };
        if (key == null || !(config.get(key) instanceof String prompt) || !prompt.isBlank()) return;
        out.add(AnalyzerFinding.of("blank-ai-prompt", Severity.WARNING, i, name,
                prefix + key + " is blank — the " + type + " block sends it to the model anyway, which costs a call"
                        + " and decides nothing; write the prompt"));
    }

    private static boolean aiMode(Map<String, Object> config) {
        return ExecutionMode.AI.equals(config.get(ExecutionMode.KEY));
    }

    private static void unknownProvider(BlockConfigSchema schema, BlockDefinition block, int i,
                                        List<AnalyzerFinding> out) {
        BlockConfigSchema.Field field = providerField(schema);
        BlockConfigs.walk(block, i, config -> unknownProvider(field, config.type(), config.config(), config.nested(),
                i, config.name(), config.prefix(), out));
    }

    /**
     * Only where the block reads {@code provider} ({@link #readsProvider}). Elsewhere the value has no effect:
     * {@code unknown-config-key} or {@code invalid-config-value} reports it there.
     */
    private static void unknownProvider(BlockConfigSchema.Field field, String type, Map<String, Object> config,
                                        boolean nested, int i, String name, String prefix, List<AnalyzerFinding> out) {
        if (!readsProvider(type, config, nested)) return;
        Object value = config.get(PROVIDER_KEY);
        if (value == null || value instanceof String text && (text.isBlank() || field.allows(text)
                || RunRequirements.isCodex(text))) return;
        String providers = String.join(", ", field.values());
        out.add(AnalyzerFinding.of("unknown-provider", Severity.ERROR, i, name, value instanceof String text
                ? prefix + "provider \"" + text + "\" is not one of " + providers + " — Traceworks refuses the step:"
                        + " a step that asks for one model must never silently run on another" + placeholderNote(text)
                : prefix + "provider: " + value + " is not a provider id (" + providers + ") — the step fails when"
                        + " it is reached"));
    }

    /**
     * Whether a block of this type and config reads {@code provider} for an AI call: the run admission's rule
     * ({@link RunRequirements#callsAi}).
     */
    static boolean readsProvider(String type, Map<String, Object> config, boolean nested) {
        return RunRequirements.callsAi(type, config, nested);
    }

    /** The schema's AI field {@code provider}: its values are the providers Traceworks knows. */
    static BlockConfigSchema.Field providerField(BlockConfigSchema schema) {
        BlockConfigSchema.Field field = schema.aiField(PROVIDER_KEY);
        if (field == null) {
            throw new IllegalStateException(BlockConfigSchema.RESOURCE + " has no AI field " + PROVIDER_KEY);
        }
        return field;
    }

    private static String placeholderNote(String provider) {
        return provider.contains("{{") ? " (a placeholder in provider is never resolved)" : "";
    }

    private static void hostDependentStep(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        Set<String> tools = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        BlockConfigs.walk(block, i, config -> hostUse(config.type(), config.config(), tools, paths));
        if (tools.isEmpty() && paths.isEmpty()) return;
        String what = (tools.isEmpty() ? "" : "calls " + String.join(", ", tools))
                + (tools.isEmpty() || paths.isEmpty() ? "" : " and ")
                + (paths.isEmpty() ? "" : "uses the host path " + String.join(", ", paths));
        out.add(AnalyzerFinding.of("host-dependent-step", Severity.WARNING, i, block.getName(),
                what + " without container_image — Traceworks runs steps in containers, where the host's tools and"
                        + " paths are missing and there is no network unless the step asks for it; set container_image"
                        + " to an image that has what the step needs (network: true and requires container.network if"
                        + " it needs the network)"));
    }

    /** The host tools and paths in the shell texts of one config without an image; comment lines do not count. */
    private static void hostUse(String type, Map<String, Object> config, Set<String> tools, Set<String> paths) {
        if (config == null || config.get("container_image") instanceof String image && !image.isBlank()) return;
        for (String text : BlockText.shellTexts(type, config)) {
            for (String line : text.split("\n")) {
                if (line.strip().startsWith("#")) continue;
                Matcher tool = HOST_TOOL.matcher(line);
                while (tool.find()) tools.add(tool.group(1));
                Matcher path = HOST_PATH.matcher(line);
                while (path.find()) paths.add(path.group());
            }
        }
    }
}
