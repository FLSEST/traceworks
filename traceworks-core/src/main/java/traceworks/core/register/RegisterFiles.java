package traceworks.core.register;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files a run of an audit workflow gets of its register, in a folder its steps read
 * ({@code {{ register_dir }}}, docs/registers.md, "The register folder of a run"): what the placeholders
 * {@code audit_findings}, {@code audit_items} and {@code previous_report} say, without their caps, since a file is
 * neither a shell line nor a prompt.
 *
 * <ul>
 *   <li>{@value #FINDINGS}: the findings {@code audit_findings} lists (open, awaiting verification, accepted,
 *       dismissed; with a catalog those of its rules), in its order, one JSON object a line, with the whole evidence
 *       and the description.</li>
 *   <li>{@value #RESOLVED}: the resolved findings, in the same form. They have a file of their own, so that a step
 *       that takes every line of {@value #FINDINGS} as something the register still holds stays right.</li>
 *   <li>{@value #ITEMS}: the items, one JSON object a line.</li>
 *   <li>{@value #PREVIOUS_REPORT}: the last valid report, defused as {@code previous_report} is, and whole.</li>
 * </ul>
 *
 * <p>Every file is always there. Before the first applied report each is empty, so a step can tell "nothing is
 * known" from a list that was cut: none is.
 */
public final class RegisterFiles {

    public static final String FINDINGS = "findings.jsonl";
    public static final String RESOLVED = "resolved.jsonl";
    public static final String ITEMS = "items.jsonl";
    public static final String PREVIOUS_REPORT = "previous-report.md";

    /** The names of the files, in the order the record names them. */
    public static final List<String> NAMES = List.of(FINDINGS, RESOLVED, ITEMS, PREVIOUS_REPORT);

    private RegisterFiles() {
    }

    /** The files of a register that does not exist yet: each empty. */
    public static Map<String, byte[]> none() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        NAMES.forEach(name -> files.put(name, new byte[0]));
        return Collections.unmodifiableMap(files);
    }

    /**
     * @param catalog        with a catalog, only findings of its rules are listed, as in {@code audit_findings};
     *                       {@code null} lists every finding
     * @param previousReport the text of the last applied report; {@code null} for none
     * @return the content of each file, by its name, in the order of {@link #NAMES}
     */
    public static Map<String, byte[]> of(RegisterState state, Catalog catalog, String previousReport) {
        StringBuilder findings = new StringBuilder();
        StringBuilder resolved = new StringBuilder();
        state.findings().stream()
                .filter(f -> catalog == null || catalog.rules().containsKey(f.ruleKey()))
                .sorted(FindingsText.ORDER)
                .forEach(f -> {
                    if (FindingsText.SHOWN.contains(f.status())) {
                        findings.append(StateJson.fileLine(f));
                    } else if (f.status() == FindingStatus.RESOLVED) {
                        resolved.append(StateJson.fileLine(f));
                    }
                });
        StringBuilder items = new StringBuilder();
        ItemsText.listed(state).forEach(item -> items.append(StateJson.fileLine(item, state.findings())));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(FINDINGS, bytes(findings.toString()));
        files.put(RESOLVED, bytes(resolved.toString()));
        files.put(ITEMS, bytes(items.toString()));
        files.put(PREVIOUS_REPORT, bytes(PreviousReport.whole(previousReport)));
        return Collections.unmodifiableMap(files);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
