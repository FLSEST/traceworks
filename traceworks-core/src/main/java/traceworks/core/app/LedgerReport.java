package traceworks.core.app;

import traceworks.core.port.Ledger;
import traceworks.core.port.LedgerRuns;
import traceworks.core.port.RunStore;
import traceworks.core.port.StoredRun;
import traceworks.core.record.StartEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the ledger says about the runs of a home, against its runs folder. The ledger names a run when it starts and
 * when it ends, outside the run's folder, so a run that never ended, and a run whose folder was removed, is still
 * here.
 *
 * @param started       how many runs the ledger saw start
 * @param ended         how many runs the ledger saw end
 * @param open          the runs the ledger saw start and not end, in the order of their start
 * @param endedGone     the runs the ledger saw end whose folder is not in the runs folder, by id
 * @param removed       the entries a later commit took out of the ledger, as their paths; Traceworks removes none
 */
public record LedgerReport(int started, int ended, List<Open> open, List<String> endedGone, List<String> removed) {

    /** What {@link Open#folder} says when the run's folder is not in the runs folder of this home. */
    public static final String GONE = "GONE";

    public LedgerReport {
        open = List.copyOf(open);
        endedGone = List.copyOf(endedGone);
        removed = List.copyOf(removed);
    }

    /**
     * A run the ledger saw start and not end.
     *
     * @param startedAt  when it started, as the start entry says; {@code null} when the entry cannot be read
     * @param workflowId its workflow, as the start entry says; {@code null} when the entry cannot be read
     * @param folder     what the run's folder says: its status ({@code RUNNING}, {@code PARKED},
     *                   {@code INTERRUPTED}, or a final status when only the end entry is missing), {@value #GONE}
     *                   when there is no folder, or {@code UNREADABLE} when its record cannot be read
     */
    public record Open(String runId, String startedAt, String workflowId, String folder) {

        /** Whether the folder is gone: the run began, and nothing says what became of it. */
        public boolean gone() {
            return GONE.equals(folder);
        }
    }

    /** The runs that started, never ended, and whose folder is gone. */
    public List<Open> lost() {
        return open.stream().filter(Open::gone).toList();
    }

    /**
     * Whether the ledger and the runs folder agree as far as a deleted run can be told: no run started without an end
     * whose folder is gone, and no entry was taken out of the ledger. A finished run whose folder is gone is listed
     * and is no failure: the ledger keeps its manifest.
     */
    public boolean ok() {
        return lost().isEmpty() && removed.isEmpty();
    }

    static LedgerReport of(Ledger ledger, RunStore store) {
        LedgerRuns runs;
        try {
            runs = ledger.runs();
        } catch (RuntimeException e) {
            throw new RecordDamagedException("The ledger cannot be read, so it cannot be said which runs it saw: "
                    + e.getMessage());
        }
        Set<String> folders = new HashSet<>(store.runIds());
        List<Open> open = new ArrayList<>();
        for (String id : runs.started()) {
            if (!runs.ended().contains(id)) {
                StartEntry entry = entry(ledger, id);
                open.add(new Open(id, entry == null ? null : entry.startedAt(),
                        entry == null ? null : entry.workflowId(), folders.contains(id) ? status(store, id) : GONE));
            }
        }
        open.sort(java.util.Comparator.comparing((Open run) -> run.startedAt() == null ? "" : run.startedAt())
                .thenComparing(Open::runId));
        List<String> endedGone = runs.ended().stream().filter(id -> !folders.contains(id)).toList();
        return new LedgerReport(runs.started().size(), runs.ended().size(), open, endedGone, runs.removed());
    }

    /** The start entry of a run; {@code null} when the ledger holds none now or it cannot be read. */
    private static StartEntry entry(Ledger ledger, String id) {
        try {
            return ledger.findStart(id).flatMap(StartEntry::parse).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String status(RunStore store, String id) {
        try (StoredRun run = store.open(id)) {
            return RunRecords.view(run.runId(), RunRecords.events(run), run.writerAlive()).status().name();
        } catch (RuntimeException e) {
            return "UNREADABLE";
        }
    }
}
