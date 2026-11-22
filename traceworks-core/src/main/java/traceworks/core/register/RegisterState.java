package traceworks.core.register;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The state of a register: a pure function of its log, kept as {@code state.json} for a quick read and regenerated
 * from the log to verify it.
 *
 * @param format       the format of {@code state.json}, {@value #FORMAT}
 * @param key          the register key
 * @param workflowId   the id of the workflow that feeds it, pinned on first use
 * @param category     the audit's category
 * @param lastReport   the last report applied or rejected; {@code null} before the first
 * @param findings     every finding ever stated, sorted by id
 * @param validReports how many reports were applied; the retirement of absent triaged findings counts them
 * @param items        the items the audit judges, as its reports listed them, sorted by name; empty for an audit
 *                     that lists none
 * @param namesDecidedSeverity whether {@code state.json} names the severity a decision was made on where the
 *                     finding has another one now ({@link Finding#decidedOnOtherSeverity}). On from the line
 *                     {@code register.requires} of the log, which a version that reconciles by that severity
 *                     writes before its first line that needs it, and never off again: the
 *                     states an earlier version recorded did not name it, and the fold of its lines has to give
 *                     their bytes. Not a key of {@code state.json} itself
 * @param project      the project the register is of, as its log names it; {@code null} for a register from before
 *                     registers had projects, whose {@code state.json} then has no such key
 */
public record RegisterState(int format, String key, String workflowId, String category, LastReport lastReport,
                            List<Finding> findings, int validReports, List<Item> items,
                            boolean namesDecidedSeverity, String project) {

    /** The format of {@code state.json}. */
    public static final int FORMAT = 1;

    public RegisterState {
        Objects.requireNonNull(key, "key");
        List<Finding> sorted = new ArrayList<>(findings == null ? List.of() : findings);
        sorted.sort(Comparator.comparing(Finding::id));
        findings = List.copyOf(sorted);
        List<Item> byName = new ArrayList<>(items == null ? List.of() : items);
        byName.sort(Comparator.comparing(Item::name));
        items = List.copyOf(byName);
    }

    /** A register whose log names no project: as a version before registers had projects wrote it. */
    public RegisterState(int format, String key, String workflowId, String category, LastReport lastReport,
                         List<Finding> findings, int validReports, List<Item> items,
                         boolean namesDecidedSeverity) {
        this(format, key, workflowId, category, lastReport, findings, validReports, items, namesDecidedSeverity,
                null);
    }

    /** A register whose state does not name the severity of a decision: as an earlier version wrote it. */
    public RegisterState(int format, String key, String workflowId, String category, LastReport lastReport,
                         List<Finding> findings, int validReports, List<Item> items) {
        this(format, key, workflowId, category, lastReport, findings, validReports, items, false);
    }

    /** A register without items. */
    public RegisterState(int format, String key, String workflowId, String category, LastReport lastReport,
                         List<Finding> findings, int validReports) {
        this(format, key, workflowId, category, lastReport, findings, validReports, List.of());
    }

    /**
     * The last report a register took in.
     *
     * @param reportBlob the sha256 of the report text in the register's blobs
     * @param valid      whether it was applied; a rejected one changed no finding
     */
    public record LastReport(String runId, Instant finishedAt, String reportBlob, boolean valid) {
    }

    /** A register that no report has fed yet. */
    public static RegisterState empty(String key, String workflowId, String category) {
        return new RegisterState(FORMAT, key, workflowId, category, null, List.of(), 0);
    }

    /** The same register with these findings. */
    public RegisterState with(List<Finding> newFindings) {
        return new RegisterState(format, key, workflowId, category, lastReport, newFindings, validReports, items,
                namesDecidedSeverity, project);
    }

    /** The same register, fed by this workflow in this category. */
    public RegisterState ownedBy(String newWorkflowId, String newCategory) {
        return new RegisterState(format, key, newWorkflowId, newCategory, lastReport, findings, validReports, items,
                namesDecidedSeverity, project);
    }

    /** The same register, of this project. */
    public RegisterState ofProject(String newProject) {
        return new RegisterState(format, key, workflowId, category, lastReport, findings, validReports, items,
                namesDecidedSeverity, newProject);
    }

    /** The same register after a report that changed nothing but the last report. */
    public RegisterState withLastReport(LastReport report) {
        return new RegisterState(format, key, workflowId, category, report, findings, validReports, items,
                namesDecidedSeverity, project);
    }

    /** The same register, whose state names the severity a decision was made on from now on. */
    public RegisterState namingDecidedSeverity() {
        return namesDecidedSeverity ? this : new RegisterState(format, key, workflowId, category, lastReport,
                findings, validReports, items, true, project);
    }

    /** How many items are in each state; every state has an entry. */
    public java.util.Map<ItemState, Integer> itemCounts() {
        java.util.Map<ItemState, Integer> counts = new java.util.EnumMap<>(ItemState.class);
        for (ItemState state : ItemState.values()) {
            counts.put(state, 0);
        }
        items.forEach(item -> counts.merge(ItemState.of(item, findings), 1, Integer::sum));
        return counts;
    }

    /** The finding with {@code id}, or {@code null}. */
    public Finding finding(String id) {
        for (Finding finding : findings) {
            if (finding.id().equals(id)) {
                return finding;
            }
        }
        return null;
    }
}
