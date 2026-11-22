package traceworks.core.app;

import traceworks.core.model.ProjectKey;
import traceworks.core.port.Clock;
import traceworks.core.port.Ledger;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.ChainReport;
import traceworks.core.register.DecisionAgent;
import traceworks.core.register.Disposition;
import traceworks.core.register.Finding;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterAnchors;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterReplay;
import traceworks.core.register.RegisterState;
import traceworks.core.register.RegisterVerifier;
import traceworks.core.register.RegisterWriter;
import traceworks.core.register.StateJson;
import traceworks.core.util.Message;

import java.time.Instant;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;

/**
 * The use case behind {@code traceworks findings}: what a register holds, read from its log, and whether it verifies.
 * A register is that of a key and a project. A call names the key; the project is the one this object is for
 * ({@link #ofProject}), or, without one, the only project the key has: with more than one the call is refused and
 * names them.
 */
public final class Findings {

    private final RegisterStore store;
    private final Clock clock;
    private final String actor;
    private final Ledger ledger;
    /** The project every call of this object addresses; {@code null} for the only one a key has. */
    private final String project;
    private String unanchored;
    private String anchorNote;
    private String notWritten;

    /** Without a ledger: no decision is anchored, and {@code verify} checks no anchor. */
    public Findings(RegisterStore store, Clock clock, String actor) {
        this(store, clock, actor, null);
    }

    /**
     * @param actor  who runs the command that decides, recorded with every disposition; an agent that decides
     *               under that actor is named with the decision itself ({@link #dismiss(String, String, String,
     *               String)})
     * @param ledger anchors the register's head after every write, and is asked by {@code verify}; {@code null} for
     *               none
     */
    public Findings(RegisterStore store, Clock clock, String actor, Ledger ledger) {
        this(store, clock, actor, ledger, null);
    }

    private Findings(RegisterStore store, Clock clock, String actor, Ledger ledger, String project) {
        this.store = store;
        this.clock = clock;
        this.actor = actor;
        this.ledger = ledger;
        this.project = project;
    }

    /**
     * The same use case for the registers of one project ({@code --project KEY}): every call of the returned object
     * addresses the register of its key and this project.
     *
     * @throws RunRefusedException if the key is no project key
     */
    public Findings ofProject(String key) {
        try {
            return new Findings(store, clock, actor, ledger, ProjectKey.checked(key));
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of(e));
        }
    }

    /**
     * The projects that have a register of {@code key}, sorted; empty for a key without one. They are the projects
     * the store holds (for files, their folders) and the projects the ledger anchors for the key: a project whose
     * whole folder was removed still counts, so "the only project the key has" never becomes another one without
     * a word. A ledger that cannot be listed names none here; {@link #ledgerUnlisted} says so, and whoever takes
     * one project from this list asks it first.
     */
    public List<String> projects(String key) {
        java.util.SortedSet<String> projects = new java.util.TreeSet<>(listed(() -> store.keys().contains(key)
                ? store.projects(key) : List.of()));
        for (Ledger.Anchored anchored : RegisterWrites.anchored(ledger)) {
            if (anchored.key().equals(key) && anchored.project() != null) {
                projects.add(anchored.project());
            }
        }
        return List.copyOf(projects);
    }

    /**
     * Why the ledger cannot say which registers it anchors: it is there and cannot be listed. Empty when it can,
     * and without a ledger. While it cannot, the projects of a key are not known: {@link #projects} then gives
     * the folders alone, and a project whose folder was removed is missing from them. So nothing takes "the only
     * project the key has" from that list ({@link #projectOf}), and a listing says that it may lack some.
     */
    public java.util.Optional<String> ledgerUnlisted() {
        if (ledger == null) {
            return java.util.Optional.empty();
        }
        try {
            ledger.anchoredRegisters();
            return java.util.Optional.empty();
        } catch (RuntimeException e) {
            return java.util.Optional.of(reason(e));
        }
    }

    /**
     * What {@code listing} gives of the store. A store that cannot be listed (a folder that cannot be read) is a
     * refusal with the reason, never a failure of the program.
     *
     * @throws RunRefusedException with the reason
     */
    private static <T> T listed(java.util.function.Supplier<T> listing) {
        try {
            return listing.get();
        } catch (RunRefusedException | traceworks.core.port.BlobStore.ChangedBlobException e) {
            throw e;   // a refusal with its reason; a blob that was changed ends the command as a damaged record does
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The registers cannot be listed: ", Message.quoted(reason(e))));
        }
    }

    /** What an exception says, or its name when it says nothing. */
    private static String reason(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Why no command works on any register of {@code key}: the ledger anchors a register of the key from before
     * registers had projects, and this home holds neither it nor one that was assigned from it
     * ({@link RegisterWrites#earlierGone}); empty otherwise.
     */
    public java.util.Optional<String> earlierGone(String key) {
        return java.util.Optional.ofNullable(listed(() -> RegisterWrites.earlierGone(ledger, store, key)));
    }

    /**
     * Refuses a register the ledger knows and whose log is not there ({@link RegisterWrites#logGone}): it is gone
     * for every command that shows or decides its findings, and nothing is opened for writing, which would make
     * its folder anew.
     *
     * @throws RunRefusedException with the reason
     */
    private void present(String key, String project) {
        String gone = usable(key, project, () -> RegisterWrites.logGone(ledger, store, key, project));
        if (gone != null) {
            throw refused(gone);
        }
    }

    /** A refusal whose reason is a sentence of {@link RegisterWrites}, which begins in lower case. */
    private static RunRefusedException refused(String reason) {
        return new RunRefusedException(Message.of(reason.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + reason.substring(1)));
    }

    /**
     * Why the ledger could not anchor the register's head after the last decision or render of this object; empty
     * when it did. The write itself is kept either way.
     */
    public java.util.Optional<String> unanchored() {
        return java.util.Optional.ofNullable(unanchored);
    }

    /**
     * What the last decision of this object could not write after its line was appended: the files derived from
     * the log ({@code state.json}, {@code report.html}), with the reason; empty when they were written. The
     * decision is recorded either way, and a render writes the files once they can be written.
     */
    public java.util.Optional<String> notWritten() {
        return java.util.Optional.ofNullable(notWritten);
    }

    /**
     * What the last decision or render of this object found of the anchors before it wrote: lines after the last
     * anchor, which it took as they were and recorded ({@code register.unanchored}), or a log that does not match
     * its anchors; empty when the last line was anchored.
     */
    public java.util.Optional<String> anchorNote() {
        return java.util.Optional.ofNullable(anchorNote);
    }

    /**
     * What {@code findings <key> verify} found.
     *
     * @param report the chain, the blobs, the states and the anchors: the problems of all of them
     * @param anchor one line about the anchor in the ledger ({@link RegisterAnchors.Check#summary()}); {@code null}
     *               without a ledger
     * @param chainHolds whether the register holds by itself: its chain, its blobs, its states and its state file,
     *               whatever the anchors say
     */
    public record Verified(ChainReport report, String anchor, boolean chainHolds) {
    }

    /**
     * Accepts a finding as a risk: a note says why, the review date defaults to 180 days ahead.
     *
     * @param reference the finding's id or a prefix of at least 8 characters
     * @throws RunRefusedException if the finding is not found, not open, the note is missing or the date is out
     *                             of range; nothing is recorded then
     */
    public Finding accept(String key, String reference, String note, Instant until) {
        return accept(key, reference, note, until, null);
    }

    /**
     * As {@link #accept(String, String, String, Instant)}, decided by an agent under the actor.
     *
     * @param agent the name the agent gave itself ({@link DecisionAgent}), recorded beside the actor; {@code null}
     *              for a decision that names none
     * @throws RunRefusedException also if the name is blank or beyond its bounds; nothing is recorded then
     */
    public Finding accept(String key, String reference, String note, Instant until, String agent) {
        return decide(key, reference, FindingStatus.ACCEPTED_RISK, note, until, agent);
    }

    /** Dismisses a finding with a note; it never comes up for review. */
    public Finding dismiss(String key, String reference, String note) {
        return dismiss(key, reference, note, null);
    }

    /** As {@link #dismiss(String, String, String)}, decided by an agent under the actor; {@code null} for none. */
    public Finding dismiss(String key, String reference, String note, String agent) {
        return decide(key, reference, FindingStatus.DISMISSED, note, null, agent);
    }

    /** Opens an accepted, dismissed or resolved finding again; the note is optional. */
    public Finding reopen(String key, String reference, String note) {
        return reopen(key, reference, note, null);
    }

    /** As {@link #reopen(String, String, String)}, decided by an agent under the actor; {@code null} for none. */
    public Finding reopen(String key, String reference, String note, String agent) {
        return decide(key, reference, FindingStatus.OPEN, note, null, agent);
    }

    private Finding decide(String key, String reference, FindingStatus kind, String note, Instant until,
                           String named) {
        // Before the register is opened: a name that is none must not reach a line, whoever calls
        String agent;
        try {
            agent = named == null ? null : DecisionAgent.checked(named, "The agent of a decision");
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of(e));
        }
        String project = projectOf(key);
        present(key, project);
        notWritten = null;
        boolean[] recorded = {false};
        try (RegisterStore.Opened opened = store.openForWrite(key, project)) {
            // The register is judged by the lines it holds, before its log is opened for writing: that discards
            // a torn tail and records it (register.repaired), and a refused command writes nothing
            RegisterState state = judged(opened);
            Finding finding = find(state, reference);
            Instant at = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);   // as the log writes it
            Finding after;
            try {
                after = switch (kind) {
                    case ACCEPTED_RISK -> Disposition.accept(finding, note, actor, at, until, agent);
                    case DISMISSED -> Disposition.dismiss(finding, note, actor, at, agent);
                    default -> Disposition.reopen(finding, note, actor, at);
                };
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new RunRefusedException(Message.of("Finding " + finding.id().substring(0, 8) + " of ", Message.typed(key), ": ",
                        Message.of(e)));
            }
            List<Finding> findings = new ArrayList<>();
            for (Finding f : state.findings()) {
                findings.add(f.id().equals(after.id()) ? after : f);
            }
            // Only now, when the decision is taken: a refused one writes nothing, neither the note of a torn tail
            // nor the one of lines without an anchor. Neither note holds state, so the fold above stays true
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            anchorNote = RegisterWrites.anchorsBefore(ledger, opened, log);
            RegisterState next = new RegisterWriter(log, opened.blobs()).disposition(state.with(findings), after,
                    kind, note, actor, agent, at,
                    after.disposition() == null ? null : after.disposition().until());
            recorded[0] = true;
            // The decision is in the log from here on: what fails after it is said, and takes nothing back
            derived(opened, log, next);
            unanchored = RegisterWrites.anchor(ledger, opened, log);
            return after;
        } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
            // A blob that was changed ends the command as a damaged record does, with the command that checks
            // this register
            throw e.ofRegister(key, project);
        } catch (RunRefusedException e) {
            throw e;   // a refusal with its reason
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The register '", Message.typed(key), "' of the project '",
                    Message.typed(project), "' cannot be written", recorded[0] ? ": the decision is recorded in its"
                    + " log, and the register could not be closed: " : ", and the decision is not recorded: ",
                    Message.quoted(reason(e))));
        }
    }

    /**
     * Writes the files derived from the log after a line was appended. The line stays whatever happens to them, so
     * a failure is kept for the command to say ({@link #notWritten}) and is not thrown.
     */
    private void derived(RegisterStore.Opened opened, RegisterLog log, RegisterState state) {
        try {
            RegisterWrites.writeDerived(opened, log, state);
        } catch (RuntimeException e) {
            notWritten = "state.json or report.html of the register could not be written (" + reason(e) + ")";
        }
    }

    private static Finding find(RegisterState state, String reference) {
        if (reference == null || reference.length() < 8) {
            throw new RunRefusedException(Message.of("A finding is named by its id or a prefix of at least 8 characters"));
        }
        List<Finding> matches = state.findings().stream().filter(f -> f.id().startsWith(reference)).toList();
        if (matches.size() != 1) {
            throw new RunRefusedException(Message.of(matches.isEmpty() ? "No finding of " : matches.size()
                    + " findings of ", Message.typed(state.key()), matches.isEmpty() ? " starts with "
                    : " start with ", Message.typed(reference)));
        }
        return matches.getFirst();
    }

    /**
     * The keys of the registers, sorted: those the store holds and those the ledger anchors, so a register whose
     * folder was removed is still named.
     */
    public List<String> keys() {
        java.util.SortedSet<String> keys = new java.util.TreeSet<>(listed(store::keys));
        RegisterWrites.anchored(ledger).forEach(anchored -> keys.add(anchored.key()));
        return List.copyOf(keys);
    }

    /**
     * The state of the register {@code key}, folded from its log; {@code state.json} is not read.
     *
     * @throws RunRefusedException if there is no such register, the key has several projects and this object is
     *                             for none of them, or the register cannot be used: its log cannot be read or
     *                             replayed, names another project than the register's, or is gone while the
     *                             ledger holds anchors of it
     */
    public RegisterState state(String key) {
        String project = projectOf(key);
        present(key, project);
        try (RegisterStore.Opened opened = store.open(key, project)) {
            return usable(opened, () -> RegisterReplay.replay(key, opened.project(),
                    new RegisterLog(opened, clock).read(), opened.blobs()));
        }
    }

    /**
     * The lines of the register's log, in order. They are the lines of this register only when the log names its
     * project, so that is checked here as {@link #state} checks it, whoever calls: the lines of a folder that was
     * copied are those of another register.
     *
     * @throws RunRefusedException as {@link #state}
     */
    public List<RegisterEvent> events(String key) {
        String project = projectOf(key);
        present(key, project);
        try (RegisterStore.Opened opened = store.open(key, project)) {
            return usable(opened, () -> {
                List<RegisterEvent> events = new RegisterLog(opened, clock).read();
                RegisterReplay.replay(key, opened.project(), events, opened.blobs());
                return events;
            });
        }
    }

    /**
     * The state of a register that is open, folded from the complete lines of its log, and nothing is written: a
     * torn tail stays where it is.
     *
     * @throws RunRefusedException if the register cannot be used ({@link #usable})
     */
    private RegisterState judged(RegisterStore.Opened opened) {
        return usable(opened, () -> RegisterReplay.replay(opened.key(), opened.project(),
                new RegisterLog(opened, clock).read(), opened.blobs()));
    }

    /**
     * What {@code read} gives of a register. A register that cannot be used is a refusal, with the reason and the
     * command that says more, never a failure of the program: its log cannot be read, holds a line that is none
     * or names another project (a folder that was copied), or a blob the fold needs is gone.
     */
    private static <T> T usable(RegisterStore.Opened opened, java.util.function.Supplier<T> read) {
        return usable(opened.key(), opened.project(), read);
    }

    /** As {@link #usable(RegisterStore.Opened, java.util.function.Supplier)}, of a register that is not open. */
    private static <T> T usable(String key, String project, java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
            // A blob that was changed ends the command as a damaged record does, with the command that checks
            // this register
            throw e.ofRegister(key, project);
        } catch (RunRefusedException e) {
            throw e;   // a refusal with its reason
        } catch (RuntimeException e) {
            // Whatever the fold or the store throws: a line that is none, a blob that is gone, a time that cannot
            // be parsed, a file that cannot be read
            throw new RunRefusedException(Message.of("The register '", Message.typed(key),
                    "' of the project '", Message.typed(project), "' cannot be used: ",
                    Message.quoted(reason(e)),
                    "; 'traceworks findings ", Message.typed(key), " verify --project ",
                    Message.typed(project), "' says what is wrong"));
        }
    }

    /**
     * Writes {@code state.json} and {@code report.html} of the register again, and returns the register's location.
     *
     * @throws RunRefusedException if the register cannot be used or written, with the reason: also when the files
     *                             could not be written, which says so and names the notes the log gained before
     */
    public String render(String key) {
        String project = projectOf(key);
        present(key, project);
        notWritten = null;
        try (RegisterStore.Opened opened = store.openForWrite(key, project)) {
            // Judged before its log is opened for writing, as a decision is: a refused render writes nothing
            RegisterState state = judged(opened);
            int before = opened.events().readAll().size();
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            anchorNote = RegisterWrites.anchorsBefore(ledger, opened, log);
            derived(opened, log, state);
            // Anchors a head the ledger could not take when it was written; an anchor the ledger holds stays
            unanchored = RegisterWrites.anchor(ledger, opened, log);
            if (notWritten != null) {
                int notes = log.lastSeq() + 1 - before;
                throw new RunRefusedException(Message.of("The register '", Message.typed(key), "' of the project '",
                        Message.typed(project), "' is not rendered: ", Message.quoted(notWritten), notes == 0 ? ""
                        : "; its log gained " + notes + (notes == 1 ? " note" : " notes") + " of the tool before"
                        + " that, which " + (notes == 1 ? "stays" : "stay")));
            }
            return opened.location();
        } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
            // A blob that was changed ends the command as a damaged record does, with the command that checks
            // this register
            throw e.ofRegister(key, project);
        } catch (RunRefusedException e) {
            throw e;   // a refusal with its reason
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The register '", Message.typed(key), "' of the project '",
                    Message.typed(project), "' cannot be written: ", Message.quoted(reason(e))));
        }
    }

    /** Verifies the register's chain, blobs and state file, and its log against the anchors in the ledger. */
    public ChainReport verify(String key) {
        return verified(key).report();
    }

    /**
     * As {@link #verify}, with the line about the anchor. An anchor that does not match its line, one that says
     * something else was written than the lines it covers give ({@link RegisterAnchors#check}), or a ledger that
     * cannot be read, is a problem; a register without an anchor, and lines after the last anchor, are said in the
     * line and are none.
     *
     * <p>Without a project it works on a register from before registers had projects too, as the register of no
     * project and against the anchors an earlier version wrote under the key alone: it is the command that says
     * what is wrong with a register {@link #assign} refuses.
     */
    public Verified verified(String key) {
        boolean fromBefore = project == null && unassigned(key);
        String of = fromBefore ? null : projectOf(key);
        try (RegisterStore.Opened opened = fromBefore ? store.openUnassigned(key) : store.open(key, of)) {
            return verified(opened, ledger, store);
        } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
            // A blob that was changed ends the command as a damaged record does, with the command that checks
            // this register
            throw e.ofRegister(key, of);
        } catch (RunRefusedException e) {
            throw e;   // a refusal with its reason
        } catch (RuntimeException e) {
            // A register that cannot even be opened does not verify, and that is what is said of it
            return new Verified(new ChainReport(false, 0, null, List.of("the register cannot be read: "
                    + reason(e)), List.of()), ledger == null ? null : "NOT checked", false);
        }
    }

    /**
     * What {@link #verified(String)} finds of one register that is open, whatever lies beside it at its key: the
     * check of the whole home judges every register this way.
     *
     * <p>A register that was assigned to its project ({@code register.assigned}) is the one register from before
     * of its key: another project of the key whose log says the same is reported, at both, each naming the other.
     *
     * @param ledger the ledger whose anchors the log is compared with; {@code null} for none
     * @param store  where the other projects of the key are looked at
     */
    static Verified verified(RegisterStore.Opened opened, Ledger ledger, RegisterStore store) {
        Verified found = verified(opened, ledger);
        List<String> beside;
        RegisterEvent cutOff;
        try {
            List<byte[]> lines = opened.events().readAll();
            beside = opened.project() == null || !assigned(lines) ? List.of()
                    : assignedBeside(store, opened.key(), opened.project());
            // A register that still lies at its key and whose log says that it was assigned: the assignment was
            // cut off after its line. Said as that, with the command that finishes it
            cutOff = opened.project() == null ? assignedLine(lines) : null;
        } catch (RuntimeException e) {
            return found;   // a log that cannot be read is reported as that
        }
        if (beside.isEmpty() && cutOff == null) {
            return found;
        }
        List<String> problems = new ArrayList<>(found.report().problems());
        if (cutOff != null) {
            String to = cutOff.text("project");
            problems.add("the assignment of the register " + opened.key() + " to the project " + to + " was begun ("
                    + traceworks.core.register.RegisterEvents.ASSIGNED + " at seq " + cutOff.seq() + ") and not"
                    + " finished: '" + RegisterWrites.command(opened.key(), null, "assign") + " --project " + to
                    + "' finishes it");
        }
        for (String other : beside) {
            problems.add("the log of the register " + RegisterOutcome.named(opened.key(), opened.project())
                    + " holds " + traceworks.core.register.RegisterEvents.ASSIGNED + ", and so does that of the"
                    + " project " + other + ": a register from before registers had projects is assigned once, so"
                    + " one of the two is a copy of the other. Remove the folder of the copy ('"
                    + RegisterWrites.command(opened.key(), other, "verify") + "' checks the other one)");
        }
        return new Verified(new ChainReport(false, found.report().events(), found.report().head(), problems,
                found.report().deletedBlobs()), found.anchor(), found.chainHolds());
    }

    /** As {@link #verified(RegisterStore.Opened, Ledger, RegisterStore)}, of the register alone. */
    private static Verified verified(RegisterStore.Opened opened, Ledger ledger) {
        String key = opened.key();
        // The anchors first, the lines after: a reader takes no lock, and a register is written before its
        // anchor. A write in between then leaves lines without an anchor, never an anchor beyond the lines
        // Whether the anchors an earlier version wrote under the key alone are this register's is told by
        // its log (it was assigned to its project), which is read after: so both are read here
        RegisterWrites.Anchors anchors = null;
        RegisterWrites.Anchors withEarlier = null;
        RuntimeException unread = null;
        if (ledger != null) {
            try {
                withEarlier = RegisterWrites.anchors(ledger, opened, true);
                anchors = RegisterWrites.anchors(ledger, opened, false);
            } catch (RuntimeException e) {
                unread = e;
            }
        }
        List<byte[]> lines;
        ChainReport chain;
        try {
            lines = opened.events().readAll();
            chain = RegisterVerifier.verify(key, opened.project(), lines, opened.blobs(), opened.state());
        } catch (RuntimeException e) {
            // A register that cannot even be read does not verify, and that is what is said of it
            return new Verified(new ChainReport(false, 0, null, List.of("the register cannot be read: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), List.of()),
                    ledger == null ? null : "NOT checked", false);
        }
        if (ledger == null) {
            return new Verified(chain, null, chain.intact());
        }
        RegisterAnchors.Check check;
        try {
            if (unread != null) {
                throw unread;
            }
            RegisterWrites.Anchors held = assigned(lines) ? withEarlier : anchors;
            check = RegisterAnchors.check(lines, held.earlier(), held.own());
        } catch (RuntimeException e) {
            check = new RegisterAnchors.Check(List.of("the anchor of the register was not compared: the ledger"
                    + " could not be read (" + e.getMessage() + ")"), "NOT checked");
        }
        List<String> problems = new ArrayList<>(chain.problems());
        problems.addAll(check.problems());
        return new Verified(new ChainReport(problems.isEmpty(), chain.events(), chain.head(), problems,
                chain.deletedBlobs()), check.summary(), chain.intact());
    }

    /** Whether a line of the log says that the register was assigned to its project; a line that is none says nothing. */
    static boolean assigned(List<byte[]> lines) {
        for (byte[] line : lines) {
            try {
                if (traceworks.core.register.RegisterEvents.ASSIGNED.equals(RegisterEvent.parse(line).type())) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // the chain report names the line
            }
        }
        return false;
    }

    /**
     * Whether a register from before registers had projects lies at {@code key}: it is the register of no project
     * until {@link #assign} gives it one, and every other call on the key is refused.
     */
    public boolean unassigned(String key) {
        return listed(() -> store.keys().contains(key) && store.unassigned(key));
    }

    /**
     * The project the register from before registers had projects at {@code key} was being assigned to when its
     * assignment was cut off: its log holds {@code register.assigned}, and it still lies at the key. Empty when no
     * register from before lies there, when none was begun, and when the log cannot be read, which
     * {@link #verified} says.
     */
    public java.util.Optional<String> assignmentBegun(String key) {
        if (!unassigned(key)) {
            return java.util.Optional.empty();
        }
        try (RegisterStore.Opened opened = store.openUnassigned(key)) {
            return java.util.Optional.ofNullable(begunFor(opened.events().readAll()));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** The project the first {@code register.assigned} of {@code lines} names; {@code null} without such a line. */
    private static String begunFor(List<byte[]> lines) {
        RegisterEvent line = assignedLine(lines);
        return line == null ? null : line.text("project");
    }

    private static RegisterEvent assignedLine(List<byte[]> lines) {
        for (byte[] line : lines) {
            try {
                RegisterEvent event = RegisterEvent.parse(line);
                if (traceworks.core.register.RegisterEvents.ASSIGNED.equals(event.type())) {
                    return event;
                }
            } catch (IllegalArgumentException e) {
                // the chain report names the line
            }
        }
        return null;
    }

    /**
     * Assigns the register from before registers had projects that lies at {@code key} to {@code project}: under
     * the register's lock it appends {@code register.assigned} with the project and the actor, writes the derived
     * files, moves the register to its place under the project and anchors its head there. The log is the same
     * chain before and after, and the anchors the ledger held under the key alone stay what they were.
     *
     * <p>An assignment that was cut off is finished by the same call and changes nothing twice: a register whose
     * log holds the line already gets no second one, and one that is in its place and lacks only the anchor gets
     * the anchor.
     *
     * <p>The register is judged before it is stamped: its chain and its blobs must verify, as {@link #verified}
     * says of a register from before. One that does not is refused with what is wrong.
     *
     * @return the location of the register after it
     * <p>One register from before is assigned once ({@link #assignedOnce}): when it was assigned to a project
     * already, what lies at the key is a copy, and the assignment is refused.
     *
     * @throws RunRefusedException    if the key is no project key, no register from before lies at the key, the
     *                                project has a register of the key already, the register was assigned to a
     *                                project already, or an assignment to another project was begun; nothing is
     *                                written then, also no note of a torn tail
     * @throws RecordDamagedException if the register does not verify; nothing is written then either
     */
    public String assign(String key, String project) {
        try {
            ProjectKey.checked(project);
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of(e));
        }
        requireKey(key);
        earlierGone(key).ifPresent(earlier -> {
            throw refused(earlier);
        });
        if (!unassigned(key)) {
            // What an assignment that moved no log left at the key is nothing, and goes with the next one
            listed(() -> {
                store.clearStaging(key);
                return null;
            });
            return anchorAssigned(key, project);
        }
        notWritten = null;
        RegisterStore.Opened unassigned;
        try {
            unassigned = store.openUnassignedForWrite(key);
        } catch (IllegalStateException e) {
            throw notFromBefore(key);   // another process assigned it in the meantime
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The register '", Message.typed(key), "' cannot be assigned: ",
                    Message.quoted(reason(e))));
        }
        boolean[] begun = {false};
        try (RegisterStore.Opened opened = unassigned) {
            // One register from before is assigned once: asked under the lock of the assignment, which every
            // assignment of the key takes, and before anything is read of the register or written into it
            assignedOnce(key, project);
            // The register is judged before it is stamped: its chain and its blobs, as verify judges them. Not its
            // state file, which an assignment that was cut off after its line left as it was before the line
            List<byte[]> lines;
            ChainReport chain;
            try {
                lines = opened.events().readAll();
                chain = RegisterVerifier.verify(key, null, lines, opened.blobs(), java.util.Optional.empty());
            } catch (RuntimeException e) {
                throw doesNotVerify(key, "it cannot be read (" + e.getMessage() + ")", 1);
            }
            if (!chain.intact()) {
                throw doesNotVerify(key, chain.problems().getFirst(), chain.problems().size());
            }
            List<RegisterEvent> events = lines.stream().map(RegisterEvent::parse).toList();
            String begunFor = RegisterReplay.assignedTo(events);
            if (begunFor != null && !begunFor.equals(project)) {
                throw new RunRefusedException(Message.of("The assignment of the register '", Message.typed(key),
                        "' was begun for the project ", Message.quoted(begunFor), " and not finished: finish it with '"
                        + RegisterWrites.command(key, null, "assign") + " --project ", Message.quoted(begunFor),
                        "'"));
            }
            if (begunFor == null) {
                if (opened.placeTaken(project)) {
                    throw taken(key, project);
                }
                if (!RegisterReplay.begun(events)) {
                    throw new RunRefusedException(Message.of("What lies at the register '", Message.typed(key), "' is"
                            + " no register from before registers had projects: its log has not begun"));
                }
            }
            // Only now is anything written: an assignment that is refused leaves the log as it was, also without
            // the note that a torn tail was cut
            begun[0] = begunFor != null;
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            if (begunFor == null) {
                anchorNote = RegisterWrites.anchorsBefore(ledger, opened, log, project);
                Map<String, Object> fields = new java.util.LinkedHashMap<>();
                fields.put("project", project);
                fields.put("actor", actor);
                log.append(traceworks.core.register.RegisterEvents.ASSIGNED, fields);
                begun[0] = true;
            }
            RegisterWrites.writeDerived(opened, log, RegisterReplay.replay(key, project, log.read(), opened.blobs()));
            try {
                opened.assignTo(project);
            } catch (IllegalStateException e) {
                if (opened.project() == null && opened.placeTaken(project)) {
                    throw taken(key, project);
                }
                throw e;
            }
            unanchored = RegisterWrites.anchor(ledger, opened, log);
            return opened.location();
        } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
            // A blob that was changed ends the command as a damaged record does, with the command that checks
            // this register
            throw e.ofRegister(key, null);
        } catch (RunRefusedException e) {
            throw e;   // a refusal with its reason
        } catch (RuntimeException e) {
            // Whatever the store throws: a log that cannot be written, a file or a folder that cannot be moved
            throw new RunRefusedException(begun[0]
                    ? Message.of("The assignment of the register '", Message.typed(key), "' to ",
                    Message.quoted(project), " is recorded in its log (register.assigned) and not finished: ",
                    Message.quoted(reason(e)), "; '" + RegisterWrites.command(key, null, "assign") + " --project ",
                    Message.quoted(project), "' finishes it")
                    : Message.of("The register '", Message.typed(key), "' cannot be written, and it is not"
                    + " assigned: ", Message.quoted(reason(e))));
        }
    }

    /**
     * Refuses the assignment of the register from before registers had projects of {@code key} when that register
     * was assigned already: a project of the key whose log holds {@code register.assigned}, or anchors of a project
     * of the key in the ledger that name such a line. What lies at the key is then a copy of what was assigned (a
     * backup put back), and a second assignment would give its findings and decisions to a second project.
     *
     * <p>The one exception is the project it was assigned to, while that project has no log that has begun: a
     * backup put back after the project's folder was lost. The log then lacks what the ledger anchors for the
     * project, which the assignment says ({@link RegisterWrites#anchorsBefore}).
     *
     * <p>What cannot be asked is not taken as "not assigned": a project of the key whose log cannot be read, and a
     * ledger that cannot say what it anchors, refuse the assignment too.
     *
     * @throws RunRefusedException with the project it was assigned to and the way out; nothing is written then
     */
    private void assignedOnce(String key, String project) {
        for (String other : listed(() -> store.projects(key))) {
            List<byte[]> lines;
            try (RegisterStore.Opened opened = store.open(key, other)) {
                lines = opened.events().readAll();
            } catch (RuntimeException e) {
                throw cannotSayAssigned(key, project, "the log of the register " + RegisterOutcome.named(key, other)
                        + " cannot be read (" + reason(e) + ")");
            }
            if (assigned(lines)) {
                throw assignedAlready(key, project, other, "the log of the register "
                        + RegisterOutcome.named(key, other) + " holds " + traceworks.core.register.RegisterEvents.ASSIGNED);
            }
        }
        if (ledger == null) {
            return;
        }
        try {
            for (Ledger.Anchored anchored : ledger.anchoredRegisters()) {
                // The project itself is the exception: it has no log that has begun, or the loop above refused
                if (!anchored.key().equals(key) || anchored.project() == null || anchored.project().equals(project)) {
                    continue;
                }
                for (traceworks.core.port.RegisterAnchor anchor : ledger.registerAnchors(key, anchored.project())) {
                    if (anchor.written() != null && anchor.written().lines().containsKey(
                            traceworks.core.register.RegisterEvents.ASSIGNED)) {
                        throw assignedAlready(key, project, anchored.project(), "the ledger anchors that assignment");
                    }
                }
            }
        } catch (RunRefusedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw cannotSayAssigned(key, project, "the ledger cannot say (" + reason(e) + ")");
        }
    }

    private static RunRefusedException assignedAlready(String key, String project, String other, String how) {
        return new RunRefusedException(Message.of("The register '", Message.typed(key), "' cannot be assigned to ",
                Message.quoted(project), ": the register from before registers had projects of this key was assigned"
                        + " to the project ", Message.quoted(other), " already (", Message.quoted(how), "). A register"
                        + " from before is assigned once, so what lies at the key is a copy of what was assigned:"
                        + " remove it"));
    }

    private static RunRefusedException cannotSayAssigned(String key, String project, String why) {
        return new RunRefusedException(Message.of("The register '", Message.typed(key), "' cannot be assigned to ",
                Message.quoted(project), ": ", Message.quoted(why), ", so it cannot be said whether the register from"
                        + " before registers had projects was assigned to a project already"));
    }

    /**
     * The other projects of {@code key} whose log holds {@code register.assigned} too, sorted: one register from
     * before is assigned once, so two such logs are one register and a copy of it. A project whose log cannot be
     * read says nothing here; the check reports it for itself.
     */
    private static List<String> assignedBeside(RegisterStore store, String key, String project) {
        List<String> beside = new ArrayList<>();
        List<String> projects;
        try {
            projects = store.projects(key);
        } catch (RuntimeException e) {
            return beside;
        }
        for (String other : projects) {
            if (other.equals(project)) {
                continue;
            }
            try (RegisterStore.Opened opened = store.open(key, other)) {
                if (assigned(opened.events().readAll())) {
                    beside.add(other);
                }
            } catch (RuntimeException e) {
                // reported as a register that cannot be read
            }
        }
        return beside;
    }

    /**
     * The refusal of a register that does not verify: nothing is written into it, and the command that says what
     * is wrong works on a register from before ({@link #verified}).
     */
    private static RecordDamagedException doesNotVerify(String key, String problem, int problems) {
        return new RecordDamagedException(Message.of("The register '", Message.typed(key), "' does not verify, so"
                + " it is not assigned: ", Message.quoted(problem), problems == 1 ? "" : " (" + problems
                + " problems in all)", "; '" + RegisterWrites.command(key, null, "verify")
                + "' says what is wrong"));
    }

    private static RunRefusedException taken(String key, String project) {
        return new RunRefusedException(Message.of("The register '", Message.typed(key), "' cannot be assigned to ",
                Message.quoted(project), ": the project ", Message.quoted(project), " has a register of ",
                Message.quoted(key), " already, or a folder in its place"));
    }

    private RunRefusedException notFromBefore(String key) {
        List<String> projects = projects(key);
        return new RunRefusedException(Message.of("The register '", Message.typed(key), "' is not from before"
                + " registers had projects, so there is nothing to assign", projects.isEmpty() ? ""
                : Message.of(": it is kept for ", Message.quoted(String.join(", ", projects)))));
    }

    /**
     * The end of an assignment that was cut off between the move and the anchor: the register lies at its place
     * under {@code project}, its log says that it was assigned to it, and the ledger holds no anchor of it there.
     * Then its head is anchored, and no line is added.
     *
     * @throws RunRefusedException if that is not the case: no register from before lies at the key
     */
    private String anchorAssigned(String key, String project) {
        if (ledger == null || !listed(() -> store.keys().contains(key) && store.projects(key).contains(project))) {
            throw notFromBefore(key);
        }
        // Decided from a read, before any writer opens the log: a refused assign writes nothing into the
        // register of a project, also no note that a torn tail was cut
        boolean lacksItsAnchor;
        try (RegisterStore.Opened opened = store.open(key, project)) {
            lacksItsAnchor = lacksTheAnchorOfItsAssignment(key, project, opened.events().readAll());
        } catch (RuntimeException e) {
            lacksItsAnchor = false;   // a log that cannot be read is no assignment to finish
        }
        if (!lacksItsAnchor) {
            throw notFromBefore(key);
        }
        try {
            return anchorOfAssigned(key, project);
        } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
            // A blob that was changed ends the command as a damaged record does, with the command that checks
            // this register
            throw e.ofRegister(key, project);
        } catch (RunRefusedException e) {
            throw e;   // a refusal with its reason
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The register '", Message.typed(key), "' of the project '",
                    Message.typed(project), "' cannot be written: ", Message.quoted(reason(e))));
        }
    }

    private String anchorOfAssigned(String key, String project) {
        try (RegisterStore.Opened opened = store.openForWrite(key, project)) {
            // Asked again under the register's lock, and still from a read: another writer may have anchored it
            if (!lacksTheAnchorOfItsAssignment(key, project, opened.events().readAll())) {
                throw notFromBefore(key);
            }
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            unanchored = RegisterWrites.anchor(ledger, opened, log);
            return opened.location();
        }
    }

    /**
     * Whether the register of {@code key} and {@code project} is one whose assignment was cut off between its move
     * and its anchor: its log says that it was assigned to this project, and the ledger holds no anchor of it. A
     * ledger that cannot say counts as holding one: it takes no anchor either.
     *
     * @param lines the complete lines of the register's log, as read
     */
    private boolean lacksTheAnchorOfItsAssignment(String key, String project, List<byte[]> lines) {
        if (!project.equals(begunFor(lines))) {
            return false;
        }
        try {
            return ledger.registerAnchors(key, project).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @throws RunRefusedException if no register has this key, or the ledger cannot say whether one has */
    private void requireKey(String key) {
        List<String> keys = keys();
        if (!keys.contains(key)) {
            ledgerUnlisted().ifPresent(why -> {
                throw new RunRefusedException(Message.of("This home holds no register '", Message.typed(key),
                        "', and the ledger cannot say whether it anchors one (", Message.quoted(why), ")"));
            });
            throw new RunRefusedException(Message.of("No register '", Message.typed(key), "'", keys.isEmpty()
                    ? "; no run has fed one" : Message.of("; the registers are ",
                    Message.quoted(String.join(", ", keys)))));
        }
    }

    /**
     * The project of the register a call on {@code key} addresses: the one this object is for, or the only one the
     * key has.
     *
     * @throws RunRefusedException if there is no such register, the key has several projects and this object is
     *                             for none of them, a register from before registers had projects lies at the
     *                             key, which is assigned first ({@link #assign}), or the ledger cannot be listed
     *                             ({@link #ledgerUnlisted}) and this object is for no project, or for one this
     *                             home holds no folder of
     */
    public String projectOf(String key) {
        requireKey(key);
        if (unassigned(key)) {
            throw refused(RegisterWrites.fromBefore(key));
        }
        List<String> projects = projects(key);
        java.util.Optional<String> unlisted = ledgerUnlisted();
        if (project == null && unlisted.isPresent()) {
            // The folders alone are not the projects of the key: one whose folder was removed is known to the
            // ledger only, and "the only project" of what is left would be another one
            throw new RunRefusedException(Message.of("The ledger cannot say which projects the register '",
                    Message.typed(key), "' has (", Message.quoted(unlisted.get()), "): name the project with"
                    + " --project KEY"));
        }
        if (project == null ? projects.isEmpty() : !projects.contains(project)) {
            // No such register, and maybe for a reason: the one the ledger anchors under the key alone is gone
            String earlier = RegisterWrites.earlierGone(ledger, store, key);
            if (earlier != null) {
                throw refused(earlier);
            }
        }
        if (project != null) {
            if (!projects.contains(project) && unlisted.isPresent()) {
                throw new RunRefusedException(Message.of("This home holds no register '", Message.typed(key),
                        "' of the project '", Message.typed(project), "', and the ledger cannot say whether it"
                        + " anchors one (", Message.quoted(unlisted.get()), ")"));
            }
            if (!projects.contains(project)) {
                throw new RunRefusedException(Message.of("No register '", Message.typed(key), "' of the project '",
                        Message.typed(project), "'", projects.isEmpty() ? "; no run has fed one"
                        : Message.of("; its projects are ", Message.quoted(String.join(", ", projects)))));
            }
            return project;
        }
        if (projects.size() == 1) {
            return projects.getFirst();
        }
        if (projects.isEmpty()) {
            throw new RunRefusedException(Message.of("No register '", Message.typed(key), "'; no run has fed one"));
        }
        throw new RunRefusedException(Message.of("The register '", Message.typed(key), "' has " + projects.size()
                + " projects (", Message.quoted(String.join(", ", projects)), "): name one with --project KEY"));
    }
}
