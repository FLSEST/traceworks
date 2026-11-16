package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.engine.ShellValues;
import traceworks.core.model.BlockDefinition;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code values} of a block ({@link ShellValues}), the shell variables its shell lines find set; nested blocks
 * are checked too.
 *
 * <ul>
 *   <li>{@code invalid-value-name} ({@code ERROR}): a name that is no variable name or that the run or the shell
 *       reserves, a value that is no text, {@code values} that are no object, and {@code values} on a tool step,
 *       which runs no shell. The step fails when it is reached, before anything runs.</li>
 *   <li>{@code value-name-configures-program} ({@code WARNING}): a name of a family that programs read as their
 *       configuration ({@link ShellValues#configuresAProgram}). The variable is not exported, so nothing reads it
 *       by itself; a command that exports it hands a protected value to that program.</li>
 *   <li>{@code unused-value} ({@code INFO}): a name that no shell line of the block uses as {@code $NAME} or
 *       {@code ${NAME…}}: the variable is set and nothing reads it.</li>
 * </ul>
 */
final class ValueRules {

    private ValueRules() {
    }

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        BlockConfigs.walk(blocks, block -> check(block, out));
    }

    private static void check(BlockConfigs.Config block, List<AnalyzerFinding> out) {
        List<String> problems = ShellValues.problems(block.config());
        for (String problem : problems) {
            out.add(AnalyzerFinding.of("invalid-value-name", Severity.ERROR, block.index(), block.name(),
                    block.where() + problem + "; the step fails when it is reached"));
        }
        if (!problems.isEmpty()) {
            return;
        }
        String shell = String.join("\n", BlockText.shellTexts(block.type(), block.config()));
        for (String name : ShellValues.names(block.config())) {
            if (ShellValues.configuresAProgram(name)) {
                out.add(AnalyzerFinding.of("value-name-configures-program", Severity.WARNING, block.index(),
                        block.name(), block.where() + "values sets " + name + ", a name programs read as their"
                                + " configuration (GIT_*, *_OPTIONS, LD_*, PYTHON*, NODE_*, JAVA_*) — the variable"
                                + " is not exported, but a command that exports it hands a protected value to such"
                                + " a program; choose another name"));
            }
            if (!Pattern.compile("\\$\\{?[#!]?" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(shell).find()) {
                out.add(AnalyzerFinding.of("unused-value", Severity.INFO, block.index(), block.name(),
                        block.where() + "values sets " + name + ", but no shell line of the block uses $" + name
                                + " — the variable is set and nothing reads it"));
            }
        }
    }
}
