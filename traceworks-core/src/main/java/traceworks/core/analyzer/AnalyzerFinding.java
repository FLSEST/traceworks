package traceworks.core.analyzer;

/**
 * A construction-error finding on a workflow definition.
 *
 * <p>One English text per finding: Traceworks produces English only.
 *
 * @param rule       stable key of the rule, for filters and registers
 * @param severity   how bad it is, see {@link Severity}
 * @param blockIndex 0-based index of the affected top-level block, {@code -1} for the definition as a whole
 * @param blockName  name of that block if set, otherwise {@code null}
 * @param detail     what exactly was found, in one sentence and with the evidence
 */
public record AnalyzerFinding(String rule, Severity severity, int blockIndex, String blockName, String detail) {

    /**
     * Most severe first; the analyzer sorts by this order.
     */
    public enum Severity {
        /** The workflow does not run as written, is refused, or exposes a secret. */
        ERROR,
        /** Costs runtime, money or context, or needs a look by a person. */
        WARNING,
        /** Hygiene. */
        INFO
    }

    /** The path of every rule: a finding without a text helps nobody. */
    static AnalyzerFinding of(String rule, Severity severity, int index, String name, String detail) {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("finding " + rule + " needs a detail");
        }
        return new AnalyzerFinding(rule, severity, index, name, detail);
    }
}
