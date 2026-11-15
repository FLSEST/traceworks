package traceworks.cli;

import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.record.StepView;

/**
 * How a finding of the analyzer is printed, one line each: the level, the rule, the block and what was found. The one
 * format for {@code check} and for the check {@code run} makes before it starts.
 */
public final class FindingLines {

    private FindingLines() {
    }

    /** {@code ERROR   unknown-block-reference      build (block 1): references ...}. */
    public static String line(AnalyzerFinding finding) {
        return String.format("%-7s %-28s %s%s", finding.severity(), finding.rule(), where(finding),
                TerminalFormat.oneLine(finding.detail()));
    }

    /**
     * The block a finding is about, such as {@code build (block 2): }, or {@value StepView#UNNAMED} for a block without
     * a name; nothing for a finding on the workflow as a whole or on its package.
     */
    private static String where(AnalyzerFinding finding) {
        if (finding.blockIndex() < 0) {
            return "";
        }
        String name = finding.blockName() == null ? StepView.UNNAMED : TerminalFormat.oneLine(finding.blockName());
        return name + " (block " + finding.blockIndex() + "): ";
    }
}
