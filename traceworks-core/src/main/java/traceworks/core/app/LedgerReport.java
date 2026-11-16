package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.Clock;
import traceworks.core.port.Ledger;
import traceworks.core.port.LedgerRuns;
import traceworks.core.port.RunStore;
import traceworks.core.port.StoredRun;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.StartEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ledger against the runs folder of a home, both ways: what the ledger says of every run it names, and what
 * every run folder says that the ledger should hold. The ledger names a run when it starts and when it ends, outside
 * the run's folder, so a run that never ended, and a run whose folder was removed, is still here; and a run folder
 * the ledger does not know, although its record says it should, is found from the other side.
 *
 * @param started      how many runs the ledger saw start
 * @param ended        how many runs the ledger saw end
 * @param earlier      how many run folders hold a record of a version that noted no start, and have no start entry
 * @param open         the runs the ledger saw start and not end, in the order of their start
 * @param endedGone    the runs the ledger saw end whose folder is not in the runs folder, each with its status
 * @param earlierOpen  the run folders of an earlier version whose run has not ended, each with its status: the
 *                     ledger never knew them
 * @param problems     what does not hold, at most one per run; the acknowledged ones are not among them
 * @param acknowledged the runs a person acknowledged, for good, each with the note
 */
public record LedgerReport(int started, int ended, int earlier, List<Open> open, List<Named> endedGone,
                           List<Named> earlierOpen, List<Problem> problems, List<Acknowledged> acknowledged) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What {@link Open#folder} says when the run's folder is not in the runs folder of this home. */
    public static final String GONE = "GONE";
    /** What {@link Open#folder} says when the folder holds no record that can be read. */
    public static final String UNREADABLE = "UNREADABLE";
    /** What {@link Open#folder} says when the record in the folder is not the one the ledger saw start. */
    public static final String OTHER_RECORD = "OTHER RECORD";

    public LedgerReport {
        open = List.copyOf(open);
        endedGone = List.copyOf(endedGone);
        earlierOpen = List.copyOf(earlierOpen);
        problems = List.copyOf(problems);
        acknowledged = List.copyOf(acknowledged);
    }

    /**
     * A run the ledger saw start and not end.
     *
     * @param startedAt  when it started, as the start entry says; {@code null} when the entry cannot be read
     * @param workflowId its workflow, as the start entry says; {@code null} when the entry cannot be read
     * @param folder     what the run's folder says: its status ({@code RUNNING}, {@code PARKED},
     *                   {@code INTERRUPTED}, or a final status when only the end entry is missing), {@value #GONE},
     *                   {@value #UNREADABLE} or {@value #OTHER_RECORD}
     */
    public record Open(String runId, String startedAt, String workflowId, String folder) {
    }

    /** A run with one word about it: a status. */
    public record Named(String runId, String detail) {
    }

    /**
     * Something that does not hold for a run.
     *
     * @param code what kind: {@code lost} (started, never ended, no folder), {@code unreadable} (a folder without a
     *             record that can be read), {@code other-record} (the record is not the one that started),
     *             {@code no-start-entry} (the record says the ledger noted the start, and it holds no entry),
     *             {@code no-end-entry} (the record ended, and the ledger holds no end entry), {@code entry-removed}
     *             (a commit names an entry the ledger does not hold), {@code entry-invalid} (an entry that cannot be
     *             read, is not this run's, or an end entry that does not agree with its commit or its start entry)
     * @param text the sentence for a person
     */
    public record Problem(String runId, String code, String text) {
    }

    /**
     * A run someone acknowledged.
     *
     * @param problem the kind of problem that was acknowledged ({@link Problem#code})
     * @param said    what the check said then
     * @param covers  whether the run has that kind of problem now, which the acknowledgement then settles; when it
     *                has none, or another kind, the acknowledgement settles nothing and is listed all the same
     */
    public record Acknowledged(String runId, String actor, String at, String note, String problem, String said,
                               boolean covers) {
    }

    /** Whether nothing is wrong that nobody acknowledged. */
    public boolean ok() {
        return problems.isEmpty();
    }

    /**
     * Acknowledges the one problem the check reports of a run: who, when, what was reported and a note, as one more
     * entry of that run in the ledger.
     *
     * @param runRef the run id, or a prefix of at least four characters that one reported run has
     * @return the report after it
     * @throws RunRefusedException if the note is empty, or the check reports nothing of that run
     */
    static LedgerReport acknowledge(Ledger ledger, RunStore store, Clock clock, String actor, String runRef,
                                    String note) {
        if (note == null || note.isBlank()) {
            throw new RunRefusedException("An acknowledgement needs a note that says why: --note \"...\"");
        }
        LedgerReport before = of(ledger, store);
        List<Problem> hits = before.problems().stream().filter(problem -> problem.runId().equals(runRef)).toList();
        if (hits.isEmpty() && runRef.length() >= RunStore.MIN_PREFIX) {
            hits = before.problems().stream().filter(problem -> problem.runId().startsWith(runRef)).toList();
        }
        if (hits.size() != 1) {
            throw new RunRefusedException(hits.isEmpty()
                    ? "The check of the ledger reports nothing of a run " + runRef + ": there is nothing to"
                    + " acknowledge (traceworks verify lists what it reports)"
                    : runRef + " is the start of " + hits.size() + " runs the check reports; name one of them whole");
        }
        Problem problem = hits.getFirst();
        ObjectNode line = JSON.createObjectNode();
        line.put("run_id", problem.runId());
        line.put("at", clock.now().toString());
        line.put("actor", actor);
        line.put("problem", problem.code());
        line.put("said", problem.text());
        line.put("note", note.strip());
        try {
            ledger.acknowledge(problem.runId(), JSON.writeValueAsBytes(line));
        } catch (IOException | RuntimeException e) {
            throw new RunRefusedException("The ledger could not take the acknowledgement of run " + problem.runId()
                    + ": " + e.getMessage());
        }
        return of(ledger, store);
    }

    static LedgerReport of(Ledger ledger, RunStore store) {
        LedgerRuns named;
        try {
            named = ledger.runs();
        } catch (RuntimeException e) {
            throw new RecordDamagedException("The ledger cannot be relied on, so it cannot be said which runs it saw: "
                    + e.getMessage());
        }
        Set<String> folders = new LinkedHashSet<>(store.runIds());
        Map<String, Problem> problems = new LinkedHashMap<>();
        Map<String, LedgerRuns.Run> known = new LinkedHashMap<>();
        Map<String, Folder> looked = new LinkedHashMap<>();
        List<Open> open = new ArrayList<>();
        List<Named> endedGone = new ArrayList<>();
        int started = 0;
        int ended = 0;
        for (LedgerRuns.Run run : named.runs()) {
            String id = run.runId();
            known.put(id, run);
            StartEntry start = run.start() == null ? null : StartEntry.parse(run.start()).orElse(null);
            String entries = entries(run, start);
            if (entries != null) {
                problems.putIfAbsent(id, new Problem(id, entries.startsWith("a commit") ? "entry-removed"
                        : "entry-invalid", entries));
            }
            boolean hasStarted = run.startNamed() || run.start() != null;
            boolean hasEnded = run.endNamed() != null || run.end() != null;
            started += hasStarted ? 1 : 0;
            ended += hasEnded ? 1 : 0;
            Folder folder = folders.contains(id) ? look(store, id, start, run.end() != null) : null;
            if (folder != null) {
                looked.put(id, folder);
            }
            if (hasStarted && !hasEnded) {
                String state = folder == null ? GONE : folder.state();
                open.add(new Open(id, start == null ? null : start.startedAt(),
                        start == null ? null : start.workflowId(), state));
                if (folder == null) {
                    problems.putIfAbsent(id, new Problem(id, "lost", "started, never ended, and the folder is gone"));
                }
            } else if (hasEnded && folder == null) {
                endedGone.add(new Named(id, run.endNamed() == null ? "-" : run.endNamed()));
            }
        }
        open.sort(Comparator.comparing((Open run) -> run.startedAt() == null ? "" : run.startedAt())
                .thenComparing(Open::runId));

        // The other way: what every run folder says the ledger should hold
        int earlier = 0;
        List<Named> earlierOpen = new ArrayList<>();
        List<String> ids = new ArrayList<>(folders);
        java.util.Collections.sort(ids);
        for (String id : ids) {
            LedgerRuns.Run run = known.get(id);
            Folder folder = looked.containsKey(id) ? looked.get(id) : look(store, id, null, false);
            boolean startHeld = run != null && run.start() != null;
            boolean endHeld = run != null && run.end() != null;
            if (folder.state().equals(UNREADABLE)) {
                problems.putIfAbsent(id, new Problem(id, "unreadable", (run == null ? "a run folder the ledger does"
                        + " not know" : "the folder of a run the ledger names") + " holds no record that can be read"
                        + (folder.why() == null ? "" : " (" + folder.why() + ")")));
                continue;
            }
            if (folder.state().equals(OTHER_RECORD)) {
                problems.putIfAbsent(id, new Problem(id, "other-record", "the record in the folder is not the one the"
                        + " ledger saw start: its run.started line is " + folder.runStarted()));
                continue;
            }
            if (folder.ledgerStart() && !startHeld) {
                problems.putIfAbsent(id, new Problem(id, "no-start-entry", "the record says the ledger noted the start"
                        + " (" + folder.state() + "), and the ledger holds no start entry of the run"));
            } else if (!folder.ledgerStart() && !startHeld) {
                earlier++;
                if (!folder.isFinal()) {
                    earlierOpen.add(new Named(id, folder.state()));
                }
            }
            if (folder.isFinal() && !endHeld) {
                problems.putIfAbsent(id, new Problem(id, "no-end-entry", "the record ended " + folder.state()
                        + ", and the ledger holds no end entry of the run (traceworks resume " + id + " writes it)"));
            }
        }

        List<Acknowledged> acknowledged = new ArrayList<>();
        for (LedgerRuns.Run run : named.runs()) {
            JsonNode last = lastAck(run.acks());
            if (run.acks() == null) {
                continue;
            }
            String id = run.runId();
            Problem now = problems.get(id);
            String code = last == null ? null : last.path("problem").asText(null);
            boolean covers = now != null && now.code().equals(code);
            if (covers) {
                problems.remove(id);
            } else if (now != null) {
                problems.put(id, new Problem(id, now.code(), now.text() + " (acknowledged for another reason: "
                        + code + ")"));
            }
            acknowledged.add(new Acknowledged(id, last == null ? null : last.path("actor").asText(null),
                    last == null ? null : last.path("at").asText(null),
                    last == null ? "(the acknowledgement cannot be read)" : last.path("note").asText(""),
                    code, last == null ? null : last.path("said").asText(null), covers));
        }
        return new LedgerReport(started, ended, earlier, open, endedGone, earlierOpen,
                List.copyOf(problems.values()), acknowledged);
    }

    /**
     * What is wrong with the entries of a run as the ledger holds them, without its folder; {@code null} for nothing.
     * A message of a commit must have its entry; a start entry must be this run's; an end entry must be a manifest
     * of this run with the status its commit says, and name the {@code run.started} line its start entry names.
     */
    private static String entries(LedgerRuns.Run run, StartEntry start) {
        String id = run.runId();
        if (run.startNamed() && run.start() == null) {
            return "a commit says the run started, and the ledger holds no start entry of it: a later commit removed"
                    + " it, or the commit added none";
        }
        if (run.start() != null && (start == null || !id.equals(start.runId()))) {
            return "the start entry cannot be read, or is not this run's";
        }
        if (run.endNamed() != null && run.end() == null) {
            return "a commit says the run ended " + run.endNamed() + ", and the ledger holds no end entry of it: a"
                    + " later commit removed it, or the commit added none";
        }
        if (run.end() == null) {
            return null;
        }
        JsonNode manifest;
        try {
            manifest = JSON.readTree(run.end());
        } catch (IOException e) {
            manifest = null;
        }
        if (manifest == null || !manifest.isObject() || !id.equals(manifest.path("run_id").asText(null))) {
            return "the end entry is no manifest of this run";
        }
        String status = manifest.path("status").asText(null);
        if (run.endNamed() == null || !run.endNamed().equals(status)) {
            return "the end entry says " + status + ", and " + (run.endNamed() == null ? "no commit says that the run"
                    + " ended" : "its commit says " + run.endNamed());
        }
        if (start != null && !start.runStarted().equals(manifest.path("run_started").asText(null))) {
            return "the end entry does not agree with the start entry: it names the run.started line "
                    + manifest.path("run_started").asText("(none)") + ", the start entry " + start.runStarted();
        }
        return null;
    }

    /** The last acknowledgement of a run; {@code null} for none, or one that cannot be read. */
    private static JsonNode lastAck(byte[] acks) {
        if (acks == null) {
            return null;
        }
        String[] lines = new String(acks, StandardCharsets.UTF_8).strip().split("\n");
        try {
            JsonNode last = JSON.readTree(lines[lines.length - 1]);
            return last != null && last.isObject() ? last : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * What a run folder says.
     *
     * @param state       the run's status, {@value #UNREADABLE} or {@value #OTHER_RECORD}
     * @param why         why the record cannot be read; {@code null} otherwise
     * @param ledgerStart whether {@code run.started} says the ledger noted the start
     * @param runStarted  the SHA-256 of the first line; {@code null} when there is none
     */
    private record Folder(String state, String why, boolean ledgerStart, String runStarted, boolean isFinal) {
    }

    /**
     * Reads a run folder: the first line must be the {@code run.started} of the run the folder is named after, and,
     * when the ledger holds a start entry, the line that entry names. The whole record is read for its status only
     * when the ledger holds no end entry of the run: a run with one has ended, and {@code traceworks verify <run>}
     * is what compares its record with the entry.
     */
    private static Folder look(RunStore store, String id, StartEntry start, boolean ended) {
        try (StoredRun run = store.open(id)) {
            List<byte[]> lines = run.events().readAll();
            if (lines.isEmpty()) {
                return new Folder(UNREADABLE, "it has no event", false, null, false);
            }
            RecordedEvent first = RecordedEvent.parse(lines.getFirst());
            if (!RecordTypes.RUN_STARTED.equals(first.type()) || first.parent() != null || !id.equals(first.run())) {
                return new Folder(UNREADABLE, "its first line is no run.started of this run", false, null, false);
            }
            String hash = EventChain.sha256Hex(lines.getFirst());
            boolean ledgerStart = first.fields().path("ledger_start").asBoolean(false);
            if (start != null && id.equals(start.runId()) && !hash.equals(start.runStarted())) {
                return new Folder(OTHER_RECORD, null, ledgerStart, hash, false);
            }
            if (ended) {
                return new Folder("ENDED", null, ledgerStart, hash, true);
            }
            var status = RunRecords.view(id, RunRecords.parse(id, lines), run.writerAlive()).status();
            return new Folder(status.name(), null, ledgerStart, hash, RunRecords.isFinal(status));
        } catch (RuntimeException e) {
            return new Folder(UNREADABLE, e.getMessage(), false, null, false);
        }
    }
}
