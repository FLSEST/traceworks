package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.engine.SecretNames;
import traceworks.core.model.BlockDefinition;

import java.util.List;

/**
 * {@code invalid-secret-name} ({@code ERROR}): an entry of {@code secrets} that is no literal secret name, or a
 * reserved one ({@link SecretNames#problems}): a placeholder, a name that is not {@code [A-Z_][A-Z0-9_]*}, a name the
 * step's environment or the container client sets ({@code PATH}, {@code HOME}, {@code LC_*}, {@code DOCKER_*}, ...),
 * one that starts with {@code TRACEWORKS_}, an AI provider's credential name ({@code ANTHROPIC_API_KEY}, ...), or
 * {@code secrets} that is not a list. The run admission refuses such a
 * workflow; nested blocks are checked too.
 */
final class SecretRules {

    private SecretRules() {
    }

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        BlockConfigs.walk(blocks, block -> check(block, out));
    }

    private static void check(BlockConfigs.Config block, List<AnalyzerFinding> out) {
        for (String problem : SecretNames.problems(block.config())) {
            out.add(AnalyzerFinding.of("invalid-secret-name", Severity.ERROR, block.index(), block.name(),
                    block.where() + problem + "; Traceworks refuses the workflow"));
        }
    }
}
