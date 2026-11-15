package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.engine.RunRequirements;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;

import java.util.List;

/**
 * The requirements and the tool policy of AI steps, as the run admission judges them
 * ({@link RunRequirements}): what it refuses is an {@code ERROR}, the rest a {@code WARNING}.
 * <ul>
 *   <li>{@code missing-requirement} ({@code ERROR}, refused): {@code network: true}, a {@code ./images/} image or a
 *       {@code tools} value other than {@code none} without {@code container.network}, {@code package.images} or
 *       {@code ai.tools} in {@code requires}.</li>
 *   <li>{@code unused-requirement} ({@code WARNING}): a declared requirement that no block uses.</li>
 *   <li>{@code tools-required} ({@code ERROR}, refused): an {@code agentic} block without {@code tools}.</li>
 *   <li>{@code invalid-config-value} ({@code ERROR}, refused): a {@code tools} value outside {@code none}, {@code read},
 *       {@code write} and {@code shell}, a placeholder included.</li>
 *   <li>{@code unsupported-provider} ({@code ERROR}, refused): the workflow's {@code provider}, or a block's own on
 *       an AI call, names Codex. Traceworks does not run Codex; any other unknown id is
 *       {@code unknown-provider}.</li>
 *   <li>{@code unsupported-mcp-servers} ({@code ERROR}, refused): {@code mcp_servers} other than {@code []},
 *       {@code user_mcp_servers: true}.</li>
 *   <li>{@code invalid-model-choice} ({@code ERROR}, refused): a {@code model_choices} declaration that is not
 *       understood completely, a default that is none of the options, a block whose {@code model_choice} names no
 *       declared choice or stands next to {@code provider} or {@code model}.</li>
 *   <li>{@code placeholder-in-model} ({@code ERROR}, refused): a {@code model} with a placeholder, which no run
 *       resolves.</li>
 * </ul>
 */
final class RequirementRules {

    private RequirementRules() {
    }

    static void apply(WorkflowDefinition workflow, List<AnalyzerFinding> out) {
        List<BlockDefinition> blocks = workflow.blocks();
        for (RunRequirements.Problem problem : RunRequirements.check(workflow)) {
            int index = problem.blockIndex();
            String name = index < 0 ? null : blocks.get(index).getName();
            String where = problem.nested() == null ? "" : FindingText.nestedPath(index, problem.nested()) + ": ";
            out.add(AnalyzerFinding.of(problem.rule(), problem.refuses() ? Severity.ERROR : Severity.WARNING, index,
                    name, where + problem.message() + (problem.refuses() ? "; Traceworks refuses the workflow" : "")));
        }
    }
}
