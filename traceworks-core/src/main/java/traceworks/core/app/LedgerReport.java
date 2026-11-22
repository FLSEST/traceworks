package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.Clock;
import traceworks.core.port.Ledger;
import traceworks.core.port.LedgerRuns;
import traceworks.core.port.RegisterStore;
import traceworks.core.port.RunStore;
import traceworks.core.port.StoredRun;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.StartEntry;
import traceworks.core.util.Message;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
 * @param earlier      how many run folders are from before the ledger: their record is of a version that noted no
 *                     start ({@code run.started} without {@code ledger_start}), and the ledger holds nothing of
 *                     them. The ledger never knew them, so it says nothing of them, and that is no failure
 * @param open         the runs the ledger saw start and not end, in the order of their start
 * @param endedGone    the runs the ledger saw end whose folder is not in the runs folder, each with its status
 * @param earlierOpen  those of them whose run has not ended, each with its status
 * @param problems     what does not hold, every problem of every run and of every register; the acknowledged
 *                     ones are not among them
 * @param acknowledged the problems a person acknowledged, for good, each with the note
 * @param inFlight     the runs whose process is alive and has not written an entry yet that its record calls for:
 *                     between {@code run.started} and the start entry (it waits for the ledger's lock), or between
 *                     {@code run.finished} and the end entry. No failure: the entry is on its way
 * @param records      whether the record of every ended run was verified against the end entry (its chain, its
 *                     blobs and the manifest it gives), and every register of the home; without, only the first
 *                     line and the manifest of a run were read, and no register
 */
public record LedgerReport(int started, int ended, int earlier, List<Open> open, List<Named> endedGone,
                           List<Named> earlierOpen, List<Problem> problems, List<Acknowledged> acknowledged,
                           List<Named> inFlight, boolean records) {

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
        inFlight = List.copyOf(inFlight);
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
     *             {@code no-end-entry} (the record ended, and the ledger holds no end entry), {@code entry-invalid}
     *             (an entry that cannot be read, is not this run's, or an end entry that does not agree with its
     *             commit or its start entry), {@code entry-changed} (an entry is not what the commit that names it
     *             left: changed, removed or named twice; anchors or acknowledgements that were not only added to), {@code manifest-differs} (the manifest in
     *             the run folder is not the end entry), {@code record-broken} (the record of an ended run does not
     *             verify, or does not give the end entry), {@code unknown-run} (a record without
     *             {@code ledger_start} that is dated after the ledger began, of which the ledger holds nothing),
     *             {@code register-broken} (a register does not verify: its chain, its blobs, the project its log
     *             names, or its log against its anchors; the sentence ends with the state of its log, so that an
     *             acknowledgement covers that state only), {@code register-gone} (the ledger anchors a register
     *             this home does not hold). The two of a register are listed under the name of the register
     *             ({@link Ledger#registerName}) in place of a run id
     * @param text the sentence for a person. It holds what tells this problem from another one of its kind (a
     *             SHA-256, a commit), because an acknowledgement covers a problem by its kind and its sentence
     */
    public record Problem(String runId, String code, String text) {
    }

    /**
     * A run someone acknowledged.
     *
     * @param problem the kind of problem that was acknowledged ({@link Problem#code})
     * @param said    what the check said then ({@link Problem#text})
     * @param covers  whether the check says exactly that of the run now, which the acknowledgement then settles;
     *                when it does not, the acknowledgement settles nothing and is listed all the same
     */
    public record Acknowledged(String runId, String actor, String at, String note, String problem, String said,
                               boolean covers) {
    }

    /** Whether nothing is wrong that nobody acknowledged. */
    public boolean ok() {
        return problems.isEmpty();
    }

    /**
     * Acknowledges the problems the check reports of one run now: who, when, what was reported and a note, as one
     * more entry of that run in the ledger. It covers those problems as the check worded them, and no other.
     *
     * @param runRef the run id, or a prefix of at least four characters that one reported run has
     * @return the report after it
     * @throws RunRefusedException if the note is empty, or the check reports nothing of that run
     */
    static LedgerReport acknowledge(Ledger ledger, RunStore store, RegisterStore registers, Clock clock,
                                    String actor, String runRef, String note) {
        if (note == null || note.isBlank()) {
            throw new RunRefusedException(Message.of("An acknowledgement needs a note that says why: --note \"...\""));
        }
        LedgerReport before = of(ledger, store, registers, clock, true);
        Set<String> reported = new LinkedHashSet<>();
        before.problems().forEach(problem -> reported.add(problem.runId()));
        List<String> hits = reported.contains(runRef) ? List.of(runRef) : runRef.length() < RunStore.MIN_PREFIX
                ? List.of() : reported.stream().filter(id -> id.startsWith(runRef)).toList();
        if (hits.size() != 1) {
            throw new RunRefusedException(hits.isEmpty()
                    ? Message.of("The check of the ledger reports nothing under the name ", Message.typed(runRef),
                    ": there is nothing to acknowledge (traceworks verify lists what it reports, each problem"
                            + " under its name: a run id, or the name of a register)")
                    : Message.of(Message.typed(runRef), " is the start of " + hits.size()
                    + " names the check lists problems under; give one of them whole"));
        }
        String id = hits.getFirst();
        ObjectNode line = JSON.createObjectNode();
        line.put("run_id", id);
        line.put("at", clock.now().toString());
        line.put("actor", actor);
        line.put("note", note.strip());
        ArrayNode problems = line.putArray("problems");
        before.problems().stream().filter(problem -> problem.runId().equals(id)).forEach(problem ->
                problems.addObject().put("problem", problem.code()).put("said", problem.text()));
        try {
            ledger.acknowledge(id, JSON.writeValueAsBytes(line));
        } catch (IOException | RuntimeException e) {
            throw new RunRefusedException(Message.of("The ledger could not take the acknowledgement of " + id + ": ",
                    Message.of(e)));
        }
        return of(ledger, store, registers, clock, true);
    }

    /** Collects the problems, each once, in the order they are found. */
    private static final class Problems {
        private final Map<String, Problem> found = new LinkedHashMap<>();

        void add(String id, String code, String text) {
            found.putIfAbsent(id + "\n" + code + "\n" + text, new Problem(id, code, text));
        }
    }

    /**
     * How long a run may be between a line of its record and the entry of the ledger that line calls for, with its
     * process alive: the time to wait for the ledger's lock (a minute at most) and to commit. Past it the missing
     * entry is a problem although a process holds the run's lock: holding a lock is not writing an entry.
     */
    static final Duration IN_FLIGHT = Duration.ofMinutes(5);

    /**
     * @param records whether to verify the record of every ended run against its end entry: the chain, the blobs
     *                and the manifest the record gives; and every register of the home: its chain, its blobs, the
     *                project its log names and its log against its anchors. It reads every record, every register
     *                and every blob of the home; without it, a record that was forged while its manifest was left
     *                alone is found by {@code traceworks verify <run>} only, and a register that does not hold by
     *                {@code traceworks findings <key> verify} only
     */
    static LedgerReport of(Ledger ledger, RunStore store, RegisterStore registers, Clock clock, boolean records) {
        Instant now = clock.now();
        LedgerRuns named;
        try {
            named = ledger.runs();
        } catch (RuntimeException e) {
            throw new RecordDamagedException("The ledger cannot be relied on, so it cannot be said which runs it saw: "
                    + e.getMessage());
        }
        Instant began = named.began();
        Set<String> folders = new LinkedHashSet<>(store.runIds());
        Problems problems = new Problems();
        Map<String, LedgerRuns.Run> known = new LinkedHashMap<>();
        Map<String, Folder> looked = new LinkedHashMap<>();
        List<Open> open = new ArrayList<>();
        List<Named> endedGone = new ArrayList<>();
        List<Named> inFlight = new ArrayList<>();
        int started = 0;
        int ended = 0;
        for (LedgerRuns.Run run : named.runs()) {
            String id = run.runId();
            known.put(id, run);
            StartEntry start = run.start() == null ? null : StartEntry.parse(run.start()).orElse(null);
            run.changes().forEach(change -> problems.add(id, "entry-changed", change));
            String startProblem = startEntry(run, start);
            if (startProblem != null) {
                problems.add(id, "entry-invalid", startProblem);
            }
            String endProblem = endEntry(run, start);
            if (endProblem != null) {
                problems.add(id, "entry-invalid", endProblem);
            }
            boolean hasStarted = run.startNamed() || run.start() != null;
            // An end entry that is no manifest of the run, or does not agree with its commit or its start entry,
            // does not end the run: it stays started and not ended, with what its folder says
            boolean hasEnded = run.end() != null ? endProblem == null : run.endNamed() != null;
            started += hasStarted ? 1 : 0;
            ended += hasEnded ? 1 : 0;
            Folder folder = folders.contains(id)
                    ? look(store, id, start, hasEnded ? run.end() : null, hasEnded, records) : null;
            if (folder != null) {
                looked.put(id, folder);
            }
            if (hasStarted && !hasEnded) {
                open.add(new Open(id, start == null ? null : start.startedAt(),
                        start == null ? null : start.workflowId(), folder == null ? GONE : folder.state()));
                if (folder == null) {
                    problems.add(id, "lost", "started, never ended, and the folder is gone (the ledger noted "
                            + (start == null ? "no start entry that can be read" : "run.started "
                            + start.runStarted()) + ")");
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
            Folder folder = looked.containsKey(id) ? looked.get(id) : look(store, id, null, null, false, records);
            boolean startHeld = run != null && run.start() != null;
            boolean endHeld = run != null && run.end() != null;
            if (folder.state().equals(UNREADABLE)) {
                if (folder.alive() && folder.empty()) {
                    inFlight.add(new Named(id, "its record has no line yet"));
                } else {
                    problems.add(id, "unreadable", (run == null ? "a run folder the ledger does not know"
                            : "the folder of a run the ledger names") + " holds no record that can be read"
                            + (folder.why() == null ? "" : " (" + folder.why() + ")")
                            + (folder.runStarted() == null ? "" : "; its first line has the SHA-256 "
                            + folder.runStarted()));
                }
                continue;
            }
            if (folder.state().equals(OTHER_RECORD)) {
                problems.add(id, "other-record", "the record in the folder is not the one the ledger saw start: its"
                        + " run.started line is " + folder.runStarted());
                continue;
            }
            if (folder.record() != null) {
                problems.add(id, "record-broken", folder.record());
            }
            if (folder.manifest() != null) {
                problems.add(id, "manifest-differs", "the manifest in the run folder (" + folder.manifest()
                        + ") is not the end entry of the ledger (SHA-256 " + EventChain.sha256Hex(run.end())
                        + "), which is a copy of it (traceworks verify " + id + " says which one the record gives)");
            }
            // The ledger was read before the folder: an entry may have come since, so it is asked again
            if (folder.ledgerStart() && !startHeld && !holdsNow(() -> ledger.findStart(id))) {
                if (run != null && run.startNamed()) {
                    // a commit names the start: the missing entry is reported from the ledger's side already
                } else if (folder.alive() && young(folder.startedAt(), now)) {
                    inFlight.add(new Named(id, "its start entry is not written yet (run.started "
                            + age(folder.startedAt(), now) + " ago)"));
                } else {
                    problems.add(id, "no-start-entry", "the record says the ledger noted the start ("
                            + folder.state() + "), and the ledger holds no start entry of the run (run.started "
                            + folder.runStarted() + ")" + held(folder, folder.startedAt(), now));
                }
            } else if (!folder.ledgerStart() && run == null) {
                if (began != null && folder.startedAt() != null && !folder.startedAt().isBefore(began)) {
                    // Not from before the ledger: it began before this run did, and should know it
                    problems.add(id, "unknown-run", "the ledger does not know this run, and its record (run.started "
                            + folder.runStarted() + ") is dated " + folder.startedAt() + ", after the ledger began ("
                            + began + "): a run of that time has entries");
                    continue;
                }
                // From before the ledger: a run of a version that noted no start, of which the ledger holds nothing
                earlier++;
                if (!folder.isFinal()) {
                    earlierOpen.add(new Named(id, folder.state()));
                }
                continue;
            }
            if (folder.isFinal() && !endHeld && !holdsNow(() -> ledger.find(id))) {
                if (folder.alive() && young(folder.lastAt(), now)) {
                    inFlight.add(new Named(id, "its end entry is not written yet (run.finished "
                            + age(folder.lastAt(), now) + " ago)"));
                } else {
                    problems.add(id, "no-end-entry", "the record ended " + folder.state() + " with the line "
                            + folder.head() + ", and the ledger holds no end entry of the run (traceworks resume "
                            + id + " writes it)" + held(folder, folder.lastAt(), now));
                }
            }
        }

        if (records) {
            registers(ledger, registers, problems);
        }

        // An acknowledgement covers a problem by what the check said of it then: its kind and its sentence
        List<Acknowledged> acknowledged = new ArrayList<>();
        for (LedgerRuns.Run run : named.runs()) {
            if (run.acks() == null) {
                continue;
            }
            String id = run.runId();
            for (String text : new String(run.acks(), StandardCharsets.UTF_8).strip().split("\n")) {
                JsonNode line;
                try {
                    line = JSON.readTree(text);
                } catch (IOException e) {
                    line = null;
                }
                if (line == null || !line.isObject()) {
                    acknowledged.add(new Acknowledged(id, null, null, "(the acknowledgement cannot be read)", null,
                            null, false));
                    continue;
                }
                for (JsonNode problem : line.path("problems")) {
                    String code = problem.path("problem").asText(null);
                    String said = problem.path("said").asText(null);
                    boolean covers = problems.found.remove(id + "\n" + code + "\n" + said) != null;
                    acknowledged.add(new Acknowledged(id, line.path("actor").asText(null),
                            line.path("at").asText(null), line.path("note").asText(""), code, said, covers));
                }
            }
        }
        return new LedgerReport(started, ended, earlier, open, endedGone, earlierOpen,
                List.copyOf(problems.found.values()), acknowledged, inFlight, records);
    }

    /**
     * Every register of the home, as {@code traceworks findings <key> verify} judges one: its chain, its blobs,
     * the project its log names (a folder that was copied names another) and its log against its anchors. A
     * register from before registers had projects is judged as one, against the anchors an earlier version wrote
     * under its key alone. And every register the ledger anchors must be there: a folder that was removed with its
     * log takes the findings and the decisions with it, and only the ledger still names it. Each problem is listed
     * under the name of its register ({@link Ledger#registerName}).
     */
    private static void registers(Ledger ledger, RegisterStore registers, Problems problems) {
        Set<Ledger.Anchored> held = new LinkedHashSet<>();
        Set<String> assigned = new LinkedHashSet<>();
        Set<String> unlisted = new LinkedHashSet<>();
        List<String> keys;
        try {
            keys = registers.keys();
        } catch (RuntimeException e) {
            throw new RecordDamagedException("The registers of this home cannot be listed, so they cannot be"
                    + " checked: " + e.getMessage());
        }
        for (String key : keys) {
            boolean fromBefore;
            List<String> projects;
            try {
                fromBefore = registers.unassigned(key);
                projects = registers.projects(key);
            } catch (RuntimeException e) {
                // What lies at the key cannot be said, so nothing of it is reported as gone as well
                problems.add(Ledger.registerName(key, null), "register-broken", "the registers of the key " + key
                        + " cannot be listed: " + e.getMessage());
                unlisted.add(key);
                continue;
            }
            if (fromBefore) {
                held.add(new Ledger.Anchored(key, null));
                try (RegisterStore.Opened opened = registers.openUnassigned(key)) {
                    register(ledger, registers, opened, problems);
                } catch (RuntimeException e) {
                    problems.add(Ledger.registerName(key, null), "register-broken", "the register " + key
                            + " from before registers had projects cannot be read: " + e.getMessage());
                }
            }
            for (String project : projects) {
                held.add(new Ledger.Anchored(key, project));
                try (RegisterStore.Opened opened = registers.open(key, project)) {
                    register(ledger, registers, opened, problems);
                    if (assigned(opened)) {
                        assigned.add(key);   // the anchors an earlier version wrote under the key are its own
                    }
                } catch (RuntimeException e) {
                    problems.add(Ledger.registerName(key, project), "register-broken", "the register "
                            + RegisterOutcome.named(key, project) + " cannot be read: " + e.getMessage());
                }
            }
        }
        List<Ledger.Anchored> anchored;
        try {
            anchored = ledger.anchoredRegisters();
        } catch (RuntimeException e) {
            throw new RecordDamagedException("The ledger cannot be relied on, so it cannot be said which registers"
                    + " it anchors: " + e.getMessage());
        }
        for (Ledger.Anchored register : anchored) {
            if (held.contains(register) || (register.project() == null && assigned.contains(register.key()))
                    || unlisted.contains(register.key())) {
                continue;
            }
            problems.add(Ledger.registerName(register.key(), register.project()), "register-gone",
                    register.project() == null
                            ? "the ledger holds anchors of the register " + register.key() + " from before registers"
                            + " had projects, and this home holds neither that register nor one that was assigned"
                            + " from it: its folder was removed or renamed"
                            : "the ledger anchors the register " + RegisterOutcome.named(register.key(),
                            register.project()) + ", and this home holds no such register: its folder was removed"
                            + " or renamed");
        }
    }

    /**
     * Whether the log of a register says that it was assigned to its project. A log that cannot be read says
     * nothing, and accounts for nothing: the check of the register reported it already, once.
     */
    private static boolean assigned(RegisterStore.Opened opened) {
        try {
            return Findings.assigned(opened.events().readAll());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The problems of one register that is open, each as {@code findings <key> verify} words it, followed by the
     * state of the register's log ({@link #logState}): an acknowledgement covers a problem by its sentence, and
     * must cover that state of the register only. What a problem is about beside the log is in its sentence
     * already: the digest of an edited {@code state.json}, what an edited blob hashes to, why a log cannot be
     * read. A register whose name does not say its key and project ({@link Ledger#registerName} of a long key and
     * project) has them at the beginning of each sentence.
     */
    private static void register(Ledger ledger, RegisterStore registers, RegisterStore.Opened opened,
                                 Problems problems) {
        String name = Ledger.registerName(opened.key(), opened.project());
        List<String> found = Findings.verified(opened, ledger, registers).report().problems();
        if (found.isEmpty()) {
            return;
        }
        String state = logState(opened);
        String whose = name.startsWith(Ledger.REGISTER_NAME_HASHED) ? "the register "
                + RegisterOutcome.named(opened.key(), opened.project()) + ": " : "";
        found.forEach(problem -> problems.add(name, "register-broken", whose + problem + state));
    }

    /**
     * What tells a problem of a register from a later one of the same words: the head of its log as it is, or
     * that it has none. The head alone does not change when a line before the last is changed again, so the
     * sentence holds a digest of every line too, as that of a record does ({@link #record}). Nothing for a log
     * that cannot be read: the problem says that.
     */
    private static String logState(RegisterStore.Opened opened) {
        List<byte[]> lines;
        try {
            lines = opened.events().readAll();
        } catch (RuntimeException e) {
            return "";
        }
        if (lines.isEmpty()) {
            return " (the register's log has no line)";
        }
        try {
            java.security.MessageDigest all = java.security.MessageDigest.getInstance("SHA-256");
            for (byte[] line : lines) {
                all.update(line);
                all.update((byte) '\n');
            }
            return " (the register's log ends with the line " + EventChain.sha256Hex(lines.getLast())
                    + "; its lines have the SHA-256 " + java.util.HexFormat.of().formatHex(all.digest()) + ")";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * How far ahead of this machine's clock a line may be dated and still count as written now: the clock of the
     * process that wrote it is this machine's, so a few seconds cover a clock that was set back in between.
     */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    /**
     * Whether a line written at {@code at} is young enough for its entry to be on its way: not older than
     * {@link #IN_FLIGHT}, and not dated ahead of now, which no line written now is.
     */
    private static boolean young(Instant at, Instant now) {
        return at != null && !ahead(at, now) && Duration.between(at, now).compareTo(IN_FLIGHT) <= 0;
    }

    private static boolean ahead(Instant at, Instant now) {
        return at != null && at.isAfter(now.plus(CLOCK_SKEW));
    }

    private static String age(Instant at, Instant now) {
        long seconds = Math.max(0, Duration.between(at, now).toSeconds());
        return seconds < 120 ? seconds + " s" : seconds / 60 + " min";
    }

    /** What to add for a run whose lock a process holds past the bound: that is no writer of an entry. */
    private static String held(Folder folder, Instant at, Instant now) {
        return !folder.alive() || at == null ? ""
                : ahead(at, now) ? "; a process holds the lock of the run, and that line is dated " + at
                + ", which lies ahead of now: no line written now is"
                : "; a process holds the lock of the run, and that line is " + age(at, now) + " old, past the "
                + IN_FLIGHT.toMinutes() + " min an entry may take";
    }

    /** Whether the ledger holds the entry now; a ledger that cannot say counts as not holding it. */
    private static boolean holdsNow(java.util.function.Supplier<java.util.Optional<byte[]>> entry) {
        try {
            return entry.get().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * What is wrong with the start entry of a run as the ledger holds it; {@code null} for nothing: the entry must
     * be this run's. An entry that is named and not held is a change of the run ({@link LedgerRuns.Run#changes}).
     * Every sentence holds what tells this case from another one of its kind (the SHA-256 of the entry), since an
     * acknowledgement covers a problem by its sentence.
     */
    private static String startEntry(LedgerRuns.Run run, StartEntry start) {
        if (run.start() != null && (start == null || !run.runId().equals(start.runId()))) {
            return "the start entry (SHA-256 " + EventChain.sha256Hex(run.start()) + ") cannot be read, or is not"
                    + " this run's";
        }
        return null;
    }

    /**
     * What is wrong with the end entry of a run as the ledger holds it, without its folder; {@code null} for
     * nothing. A message of a commit must have its entry; the entry must be a manifest of this run with the status
     * its commit says, and name the {@code run.started} line its start entry names.
     */
    private static String endEntry(LedgerRuns.Run run, StartEntry start) {
        String id = run.runId();
        if (run.end() == null) {
            return null;
        }
        String entry = "the end entry (SHA-256 " + EventChain.sha256Hex(run.end()) + ")";
        JsonNode manifest;
        try {
            manifest = JSON.readTree(run.end());
        } catch (IOException e) {
            manifest = null;
        }
        if (manifest == null || !manifest.isObject() || !id.equals(manifest.path("run_id").asText(null))) {
            return entry + " is no manifest of this run";
        }
        String status = manifest.path("status").asText(null);
        if (run.endNamed() == null || !run.endNamed().equals(status)) {
            return entry + " says " + status + ", and " + (run.endNamed() == null ? "no commit says that the run"
                    + " ended" : "its commit says " + run.endNamed());
        }
        if (start != null && !start.runStarted().equals(manifest.path("run_started").asText(null))) {
            return entry + " does not agree with the start entry: it names the run.started line "
                    + manifest.path("run_started").asText("(none)") + ", the start entry " + start.runStarted();
        }
        return null;
    }

    /**
     * What a run folder says.
     *
     * @param state           the run's status, {@code ENDED} for a run the ledger saw end, {@value #UNREADABLE} or
     *                        {@value #OTHER_RECORD}
     * @param why             why the record cannot be read; {@code null} otherwise
     * @param ledgerStart     whether {@code run.started} says the ledger noted the start
     * @param runStarted      the SHA-256 of the first line; {@code null} when there is none
     * @param alive           whether a process holds the run's writer lock now: it may be about to write an entry
     * @param empty           whether the record has no line at all
     * @param manifest        for a run whose end entry the folder's manifest is not: what the folder holds
     *                        ({@code SHA-256 <hex>}, or {@code none}); {@code null} when they are the same or the
     *                        ledger holds no end entry
     * @param startedAt       the time of {@code run.started}; {@code null} when there is none
     * @param lastAt          the time of the last line; {@code null} when the record was not read whole
     * @param head            the SHA-256 of the last line; {@code null} when the record was not read whole
     * @param record          for an ended run whose record was verified and does not hold: what is wrong with it;
     *                        {@code null} otherwise
     */
    private record Folder(String state, String why, boolean ledgerStart, String runStarted, boolean isFinal,
                          boolean alive, boolean empty, String manifest, Instant startedAt, Instant lastAt,
                          String head, String record) {

        /** A folder of which only {@code state} and {@code why} can be said, and the first line's hash if any. */
        static Folder unreadable(String state, String why, String runStarted, boolean ledgerStart, boolean alive,
                                 boolean empty) {
            return new Folder(state, why, ledgerStart, runStarted, false, alive, empty, null, null, null, null,
                    null);
        }
    }

    /**
     * Reads a run folder: the first line must be the {@code run.started} of the run the folder is named after, and,
     * when the ledger holds a start entry, the line that entry names. Of a run the ledger saw end only the first
     * line is read, and its manifest, which must be the end entry byte for byte; the whole record is read for its
     * status only of a run without an end entry. {@code traceworks verify <run>} is what checks a record in full.
     *
     * @param end the end entry the ledger holds; {@code null} for none
     */
    private static Folder look(RunStore store, String id, StartEntry start, byte[] end, boolean ended,
                               boolean records) {
        try (StoredRun run = store.open(id)) {
            boolean alive = run.writerAlive();
            byte[] line = ended ? run.events().first().orElse(null) : null;
            List<byte[]> lines = ended ? null : run.events().readAll();
            if (!ended && !lines.isEmpty()) {
                line = lines.getFirst();
            }
            if (line == null) {
                return Folder.unreadable(UNREADABLE, "it has no event", null, false, alive, true);
            }
            String hash = EventChain.sha256Hex(line);
            RecordedEvent first;
            try {
                first = RecordedEvent.parse(line);
            } catch (RuntimeException e) {
                return Folder.unreadable(UNREADABLE, "its first line is no event", hash, false, alive, false);
            }
            if (!RecordTypes.RUN_STARTED.equals(first.type()) || first.parent() != null || !id.equals(first.run())) {
                return Folder.unreadable(UNREADABLE, "its first line is no run.started of this run", hash, false,
                        alive, false);
            }
            boolean ledgerStart = first.fields().path("ledger_start").asBoolean(false);
            if (start != null && id.equals(start.runId()) && !hash.equals(start.runStarted())) {
                return Folder.unreadable(OTHER_RECORD, null, hash, ledgerStart, alive, false);
            }
            if (ended) {
                java.util.Optional<byte[]> manifest = run.readManifest();
                String differs = end == null || manifest.filter(bytes -> java.util.Arrays.equals(bytes, end))
                        .isPresent() ? null : manifest.map(bytes -> "SHA-256 " + EventChain.sha256Hex(bytes))
                        .orElse("none");
                return new Folder("ENDED", null, ledgerStart, hash, true, alive, false, differs, first.at(), null,
                        null, records && end != null ? record(run, id, end) : null);
            }
            List<RecordedEvent> events = RunRecords.parse(id, lines);
            var status = RunRecords.view(id, events, alive).status();
            return new Folder(status.name(), null, ledgerStart, hash, RunRecords.isFinal(status), alive, false, null,
                    first.at(), events.getLast().at(), EventChain.sha256Hex(lines.getLast()), null);
        } catch (RuntimeException e) {
            return Folder.unreadable(UNREADABLE, e.getMessage(), null, false, false, false);
        }
    }

    /**
     * The record of an ended run against its end entry: the chain and the blobs must verify, nothing may follow the
     * last line feed, and the manifest the record gives must be the end entry byte for byte. So a record that was rewritten while its manifest was left
     * alone does not pass.
     *
     * @return what is wrong, with the head of the record as it is; {@code null} for nothing
     */
    private static String record(StoredRun run, String id, byte[] end) {
        try {
            List<byte[]> lines = run.events().readAll();
            // The record has an end entry, so it is ended: bytes after its last line feed are a problem here as
            // they are for verify <run>, which must not fail what this check passes
            byte[] tail = run.events().tail();
            traceworks.core.record.ChainReport chain = Verification.afterTheLastLine(
                    traceworks.core.record.ChainVerifier.verify(id, lines, run.blobs()), true, tail);
            if (!chain.intact()) {
                // The sentence is what an acknowledgement covers, so it names the whole record as it is: a digest
                // of every line and of every problem (a changed blob's problem names what the blob holds). Whatever
                // changes in the record afterwards is another sentence
                java.security.MessageDigest all = java.security.MessageDigest.getInstance("SHA-256");
                for (byte[] line : lines) {
                    all.update(line);
                    all.update((byte) '\n');
                }
                for (String problem : chain.problems()) {
                    all.update(problem.getBytes(StandardCharsets.UTF_8));
                    all.update((byte) '\n');
                }
                all.update(tail);   // what follows the last line feed, so that other bytes are another sentence
                return "the record does not verify (" + chain.problems().getFirst() + "; "
                        + chain.problems().size() + (chain.problems().size() == 1 ? " problem" : " problems")
                        + " in all; its lines and problems have the SHA-256 "
                        + java.util.HexFormat.of().formatHex(all.digest()) + "; it ends with the line "
                        + chain.head() + "): traceworks verify " + id + " says more";
            }
            return Verification.matches(end, RunRecords.view(id, RunRecords.parse(id, lines), false), run.blobs(),
                    Verification.namesStarter(lines))
                    ? null : "the end entry of the ledger is not the manifest the record gives (the record ends with"
                    + " the line " + chain.head() + "): traceworks verify " + id + " says more";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            return "the record cannot be read (" + e.getMessage() + ")";
        }
    }
}
