package traceworks.core.app;

import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.analyzer.AnalyzerFinding.Severity;

import java.util.List;

/**
 * What the check before a run found ({@link RunServices#checkBeforeRun}): the analyzer's findings on the package, for
 * the inputs of this run, and what is wrong with the inputs themselves.
 *
 * @param findings      the analyzer's findings, most severe first; empty when the caller asked for no analysis
 * @param inputWarnings one English text for each input that changes nothing in the run, because no block reads it
 */
public record RunCheck(List<AnalyzerFinding> findings, List<String> inputWarnings) {

    public RunCheck {
        findings = List.copyOf(findings);
        inputWarnings = List.copyOf(inputWarnings);
    }

    /** The findings that refuse the run: every {@code ERROR}. */
    public List<AnalyzerFinding> errors() {
        return findings.stream().filter(finding -> finding.severity() == Severity.ERROR).toList();
    }

    /** The findings a person should see before the run starts or is refused: every {@code ERROR} and {@code WARNING}. */
    public List<AnalyzerFinding> errorsAndWarnings() {
        return findings.stream().filter(finding -> finding.severity() != Severity.INFO).toList();
    }
}
