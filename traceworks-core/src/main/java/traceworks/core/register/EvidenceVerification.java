package traceworks.core.register;

import traceworks.core.compat.GermanWords;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a finding's evidence names a fact a step of this run measured. A fact sheet is an output with the header
 * {@value #FACT_SHEET_MARKER} (or the German one, {@link GermanWords#FACT_SHEET_MARKER}); its
 * {@code FACT <name>:} and {@code HIT <name>:} lines (or {@code FAKT}, {@code TREFFER}) give the names. An evidence
 * that names none of them is invented or recycled. The check is mechanical: it cannot tell whether a fact that exists
 * supports the conclusion drawn from it.
 */
public final class EvidenceVerification {

    /** The header of a fact sheet. */
    public static final String FACT_SHEET_MARKER = "## FACT SHEET";

    private static final Pattern FACT_NAME = Pattern.compile("^\\s*(?:FACT|HIT|" + GermanWords.FACT_KEYWORDS
            + ")\\s+([A-Za-z0-9_.:-]+)\\s*:", Pattern.MULTILINE);
    private static final int MIN_NAME_LENGTH = 4;

    private EvidenceVerification() {
    }

    /** The names of every fact the outputs state; empty when no output is a fact sheet. */
    public static Set<String> factNames(List<String> stepOutputs) {
        Set<String> names = new LinkedHashSet<>();
        if (stepOutputs == null) {
            return names;
        }
        for (String output : stepOutputs) {
            if (output == null || !(output.contains(FACT_SHEET_MARKER)
                    || output.contains(GermanWords.FACT_SHEET_MARKER))) {
                continue;
            }
            Matcher matcher = FACT_NAME.matcher(output);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (name.length() >= MIN_NAME_LENGTH) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Whether {@code evidence} names one of the facts; always when the run measured none. */
    public static boolean isSupported(String evidence, Set<String> factNames) {
        if (factNames.isEmpty()) {
            return true;
        }
        if (evidence == null || evidence.isBlank()) {
            return false;
        }
        return factNames.stream().anyMatch(evidence::contains);
    }
}
