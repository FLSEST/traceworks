package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.RunVariables;
import traceworks.core.model.AuditConfig;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.register.Catalog;

import java.util.List;
import java.util.Map;

/**
 * The rules on an audit workflow and what it gets from its register: its {@code metadata.audit}, its
 * {@code catalog.json}, and the places of {@code previous_report} and {@code register_dir}
 * (docs/registers.md).
 * <ul>
 *   <li>{@code invalid-audit-config}, {@code invalid-audit-key}, {@code report-block-missing},
 *       {@code catalog-invalid} ({@code ERROR}): a {@code metadata.audit} or {@code catalog.json} the register cannot
 *       use ({@link #apply}, {@link #catalog}).</li>
 *   <li>{@code report-from-child-sets-no-gate} ({@code INFO}): the report block of an audit runs a child
 *       workflow ({@code sub-workflow}, {@code foreach} with {@code workflow_id}), whose gate markers set no gate
 *       of the run.</li>
 *   <li>{@code register-dir-outside-audit} ({@code ERROR}): {@code register_dir} in a workflow that feeds no
 *       register, so its run has no register folder and the placeholder would stay as text.</li>
 *   <li>{@code register-dir-in-ai-prompt} ({@code ERROR}): {@code register_dir} in the prompt of an AI call, whose
 *       container does not get the folder.</li>
 *   <li>{@code previous-report-outside-audit} ({@code ERROR}): {@code previous_report} in a workflow without an audit
 *       block (decided 2026-11-11).</li>
 *   <li>{@code report-block-required} ({@code ERROR}): {@code previous_report} without
 *       {@code metadata.audit.report_block}. The previous report is the output of that block.</li>
 * </ul>
 */
final class AuditRules {

    private AuditRules() {}

    /**
     * The audit rules of a definition: {@code invalid-audit-config} and {@code invalid-audit-key} for a broken
     * {@code metadata.audit}, {@code report-block-missing} when {@code report_block} names no top-level block, and
     * {@code previous-report-outside-audit} for {@code previous_report} in a workflow without an audit block
     * (decided 2026-11-11: an error, not a literal), and {@code register-dir-outside-audit} for {@code register_dir}
     * in a workflow that feeds no register.
     */
    static void apply(WorkflowDefinition workflow, List<AnalyzerFinding> out) {
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
        if (config != null) {
            List<BlockDefinition> all = workflow.blocks();
            for (int i = 0; i < all.size(); i++) {
                BlockDefinition block = all.get(i);
                if (block != null && config.reportBlock().equals(block.getName())
                        && ChildWorkflows.runsAChild(block.getType(), block.getConfig())) {
                    out.add(AnalyzerFinding.of("report-from-child-sets-no-gate", Severity.INFO, i, block.getName(),
                            "the report block runs a child workflow: its output is a copy of the child's, and a"
                                    + " gate_status marker in a copy sets no gate of this run — the child's gate"
                                    + " stays the child's. Let a step of this workflow print the report and its"
                                    + " gate, or add one after this block that states the gate"));
                    break;
                }
            }
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

    /**
     * {@code report-block-required}: {@code previous_report} in a block of a workflow whose
     * {@code metadata.audit.report_block} names no block. {@link TraceworksRules#apply} calls it per block.
     */
    static void reportBlockRequired(BlockDefinition block, int i, List<AnalyzerFinding> out) {
        if (!PlaceholderSyntax.keysIn(BlockText.joined(block)).contains("previous_report")) return;
        out.add(AnalyzerFinding.of("report-block-required", Severity.ERROR, i, block.getName(),
                "uses {{ previous_report }}, but metadata.audit.report_block names no block — the previous report is"
                        + " the output of that block; name the"
                        + " block that writes the report"));
    }
}
