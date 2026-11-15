package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules about questions and parking (docs/cli.md, "Interactive and parked runs"). Without a terminal, only a
 * top-level {@code approval} or {@code human-input} that a person answers alone parks the run; the process waits for
 * every other question until it times out. A resume sets the run variables again, so a parked question that embeds
 * one of them asks something else after the park.
 * <ul>
 *   <li>{@code nested-wait-point} ({@code WARNING}): a question that never parks the run: the plan approval of an
 *       {@code agentic} block ({@code require_approval: true}), and an {@code approval}, {@code human-input} or
 *       agentic plan approval in the workflow a {@code sub-workflow} block or a {@code foreach} with
 *       {@code workflow_id} runs, at any depth. The children are looked into only when the analyzer has a resolver
 *       for them; a child that cannot be resolved, or a reference with a placeholder, is skipped.</li>
 *   <li>{@code question-changes-on-resume} ({@code WARNING}): the {@code question} of an {@code approval} or the
 *       {@code prompt} of a {@code human-input} that a person answers embeds {@code run_timestamp},
 *       {@code started_at} or {@code trigger_source}; after a park the resumed step fails with "the question changed
 *       while the run was parked".</li>
 * </ul>
 */
final class ParkingRules {

    /** The run variables a resume sets again. */
    static final Set<String> RESET_ON_RESUME = Set.of("run_timestamp", "started_at", "trigger_source");

    /** How deep the rule follows children of children; the engine itself has no lower limit. */
    private static final int MAX_DEPTH = 16;

    private static final String NEVER_PARKS = "never parks the run, so without a terminal (no console, or"
            + " --no-input) the process waits for it until it times out";

    private ParkingRules() {
    }

    /**
     * @param children finds the workflows of {@code sub-workflow} and {@code foreach} blocks; {@code null} for none
     */
    static void apply(List<BlockDefinition> blocks, WorkflowResolver children, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) {
                continue;
            }
            Map<String, Object> config = block.getConfig() == null ? Map.of() : block.getConfig();
            if (asksPlanApproval(block.getType(), config)) {
                out.add(AnalyzerFinding.of("nested-wait-point", Severity.WARNING, i, block.getName(),
                        "the plan approval of this agentic step (require_approval: true) is asked inside the step and"
                                + " " + NEVER_PARKS));
            }
            String ref = childRef(block.getType(), config);
            if (ref != null && children != null) {
                Set<String> seen = new HashSet<>();
                String asked = firstQuestion(ref, children, seen, 1);
                if (asked != null) {
                    out.add(AnalyzerFinding.of("nested-wait-point", Severity.WARNING, i, block.getName(),
                            "the child workflow " + ref + " asks a person (" + asked + "): a question in a child "
                                    + NEVER_PARKS));
                }
            }
            questionChangesOnResume(block, config, i, out);
        }
    }

    private static boolean asksPlanApproval(String type, Map<String, Object> config) {
        return "agentic".equals(type) && Boolean.TRUE.equals(config.get("require_approval"));
    }

    /** The {@code workflow_id} a block runs as a child, when it is static; {@code null} otherwise. */
    private static String childRef(String type, Map<String, Object> config) {
        if (!"sub-workflow".equals(type) && !"foreach".equals(type)) {
            return null;
        }
        return config.get("workflow_id") instanceof String ref && !ref.isBlank() && !ref.contains("{{") ? ref : null;
    }

    /** The first question the child workflow, or a child of it, asks a person, as a label; {@code null} for none. */
    private static String firstQuestion(String ref, WorkflowResolver resolver, Set<String> seen, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        WorkflowDefinition child;
        try {
            child = resolver.resolve(ref);
        } catch (RuntimeException e) {
            return null;   // a reference that names no workflow fails at run time, with its own message
        }
        if (child == null || !seen.add(child.id() == null ? ref : child.id())) {
            return null;
        }
        WorkflowResolver inner = resolver.forChild(child);
        for (BlockDefinition block : child.blocks()) {
            if (block == null || block.getType() == null) {
                continue;
            }
            Map<String, Object> config = block.getConfig() == null ? Map.of() : block.getConfig();
            String label = "\"" + (block.getName() == null ? "(unnamed)" : block.getName()) + "\"";
            if (("approval".equals(block.getType()) || "human-input".equals(block.getType()))
                    && !answeredByAi(config)) {
                return block.getType() + " " + label;
            }
            if (asksPlanApproval(block.getType(), config)) {
                return "the plan approval of agentic " + label;
            }
            String grandchild = childRef(block.getType(), config);
            if (grandchild != null) {
                String asked = firstQuestion(grandchild, inner, seen, depth + 1);
                if (asked != null) {
                    return asked + " in " + grandchild;
                }
            }
        }
        return null;
    }

    /**
     * Whether an AI actor alone answers the question. {@code shadow} still asks a person, and a placeholder or any
     * other value is a person unless a run says otherwise, so only a literal {@code ai} counts. (A run with untrusted
     * input turns {@code ai} into {@code shadow} at run time; the analyzer cannot know that.)
     */
    private static boolean answeredByAi(Map<String, Object> config) {
        return config.get("actor") instanceof String actor && "ai".equalsIgnoreCase(actor.strip());
    }

    private static void questionChangesOnResume(BlockDefinition block, Map<String, Object> config, int i,
                                                List<AnalyzerFinding> out) {
        String key = switch (block.getType() == null ? "" : block.getType()) {
            case "approval" -> "question";
            case "human-input" -> "prompt";
            default -> null;
        };
        if (key == null || !(config.get(key) instanceof String text)) {
            return;
        }
        if (config.get("actor") instanceof String actor
                && ("ai".equalsIgnoreCase(actor) || "shadow".equalsIgnoreCase(actor))) {
            return;   // a question cast with an AI actor never parks
        }
        Set<String> reset = new LinkedHashSet<>();
        for (String name : PlaceholderSyntax.keysIn(text)) {
            if (RESET_ON_RESUME.contains(name.strip())) {
                reset.add(name.strip());
            }
        }
        if (reset.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>(reset);
        out.add(AnalyzerFinding.of("question-changes-on-resume", Severity.WARNING, i, block.getName(),
                "the " + key + " embeds " + String.join(", ", names) + ", which a resume sets again: if the run"
                        + " parks here, the resumed step asks another question and fails with \"the question"
                        + " changed while the run was parked\"; leave " + (names.size() == 1 ? "it" : "them")
                        + " out of the " + key));
    }
}
