package traceworks.cli;

import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.record.StepView;

import java.io.PrintStream;

/**
 * How a finding of the analyzer is printed, one line each: the level, the rule, the block and what was found. The one
 * format for {@code check} and for the check {@code run} makes before it starts.
 */
public final class FindingLines {

    private FindingLines() {
    }

    /**
     * {@code ERROR   unknown-block-reference      build (block 1): references ...}. The name of the block and the
     * detail, which quotes the workflow, are texts from outside: a line too long goes on on marked lines.
     */
    public static void print(PrintStream out, AnalyzerFinding finding) {
        TerminalText.println(out, String.format("%-7s %-28s ", finding.severity(), finding.rule()), where(finding),
                TerminalText.oneLine(finding.detail()));
    }

    /**
     * The block a finding is about, such as {@code build (block 2): }, or {@value StepView#UNNAMED} for a block without
     * a name; nothing for a finding on the workflow as a whole or on its package.
     */
    private static Object where(AnalyzerFinding finding) {
        if (finding.blockIndex() < 0) {
            return "";
        }
        return TerminalText.seq(finding.blockName() == null ? StepView.UNNAMED
                : TerminalText.oneLine(finding.blockName()), " (block " + finding.blockIndex() + "): ");
    }
}
