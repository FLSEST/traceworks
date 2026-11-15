package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.RunAdmission;
import traceworks.core.engine.RunRequirements;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.List;

/**
 * {@code unsupported-provider} ({@code ERROR}) for a child workflow: a workflow that a {@code sub-workflow} or
 * {@code foreach} block runs by a literal {@code workflow_id}, at any depth, names Codex as a provider, its own or a
 * block's. The start check of a run refuses such a run before it exists ({@link RunAdmission#codexReasons}); the
 * checked workflow's own Codex provider is {@link RequirementRules}'. Reported at the top-level block that leads to
 * the child; the children are looked into only when the analyzer has a resolver for them.
 */
final class ChildProviderRules {

    private ChildProviderRules() {
    }

    /**
     * @param children finds the workflows of {@code sub-workflow} and {@code foreach} blocks; {@code null} for none
     */
    static void apply(List<BlockDefinition> blocks, WorkflowResolver children, List<AnalyzerFinding> out) {
        if (children == null) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            String ref = block == null ? null : ChildWorkflows.literalRef(block.getType(), block.getConfig());
            if (ref == null) {
                continue;
            }
            WorkflowDefinition child;
            try {
                child = children.resolve(ref);
            } catch (RuntimeException e) {
                continue;   // a reference that names no workflow fails at run time, with its own message
            }
            for (ChildWorkflows.Reached reached : ChildWorkflows.reached(child, children.forChild(child))) {
                WorkflowDefinition workflow = reached.definition();
                String named = workflow == child && !ref.equals(child.id()) ? " (" + ref + ")" : "";
                for (String reason : RunAdmission.codexReasons(workflow)) {
                    out.add(AnalyzerFinding.of(RunRequirements.UNSUPPORTED_PROVIDER, Severity.ERROR, i,
                            block.getName(), "the child workflow " + workflow.id() + named + ": " + reason
                                    + " (Traceworks refuses the run at its start)"));
                }
            }
        }
    }
}
