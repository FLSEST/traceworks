package traceworks.core.app;

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

import java.time.Instant;
import java.util.ArrayList;

import java.util.List;

/** The use case behind {@code traceworks findings}: what a register holds, read from its log, and whether it verifies. */
public final class Findings {

    private final RegisterStore store;
    private final Clock clock;
    private final String actor;
    private final Ledger ledger;
    private String unanchored;
    private String anchorNote;

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
        this.store = store;
        this.clock = clock;
        this.actor = actor;
        this.ledger = ledger;
    }

    /**
     * Why the ledger could not anchor the register's head after the last decision or render of this object; empty
     * when it did. The write itself is kept either way.
     */
    public java.util.Optional<String> unanchored() {
        return java.util.Optional.ofNullable(unanchored);
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
            throw new RunRefusedException(e.getMessage());
        }
        try (RegisterStore.Opened opened = store.openForWrite(requireKnown(key))) {
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            RegisterState state = RegisterReplay.replay(key, log.read(), opened.blobs());
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
                throw new RunRefusedException("Finding " + finding.id().substring(0, 8) + " of " + key + ": "
                        + e.getMessage());
            }
            List<Finding> findings = new ArrayList<>();
            for (Finding f : state.findings()) {
                findings.add(f.id().equals(after.id()) ? after : f);
            }
            // Only now, when the decision is taken: a refused one writes nothing, this line neither
            anchorNote = RegisterWrites.anchorsBefore(ledger, opened, log);
            RegisterState next = new RegisterWriter(log, opened.blobs()).disposition(state.with(findings), after,
                    kind, note, actor, agent, at,
                    after.disposition() == null ? null : after.disposition().until());
            RegisterWrites.writeDerived(opened, log, next);
            unanchored = RegisterWrites.anchor(ledger, opened, log);
            return after;
        }
    }

    private static Finding find(RegisterState state, String reference) {
        if (reference == null || reference.length() < 8) {
            throw new RunRefusedException("A finding is named by its id or a prefix of at least 8 characters");
        }
        List<Finding> matches = state.findings().stream().filter(f -> f.id().startsWith(reference)).toList();
        if (matches.size() != 1) {
            throw new RunRefusedException(matches.isEmpty() ? "No finding of " + state.key() + " starts with " + reference
                    : matches.size() + " findings of " + state.key() + " start with " + reference);
        }
        return matches.getFirst();
    }

    /** The keys of the registers that exist, sorted. */
    public List<String> keys() {
        return store.keys();
    }

    /**
     * The state of the register {@code key}, folded from its log; {@code state.json} is not read.
     *
     * @throws RunRefusedException if there is no such register
     */
    public RegisterState state(String key) {
        try (RegisterStore.Opened opened = store.open(requireKnown(key))) {
            return RegisterReplay.replay(key, new RegisterLog(opened, clock).read(), opened.blobs());
        }
    }

    /** The lines of the register's log, in order. */
    public List<RegisterEvent> events(String key) {
        try (RegisterStore.Opened opened = store.open(requireKnown(key))) {
            return new RegisterLog(opened, clock).read();
        }
    }

    /** Writes {@code state.json} and {@code report.html} of the register again, and returns the register's location. */
    public String render(String key) {
        try (RegisterStore.Opened opened = store.openForWrite(requireKnown(key))) {
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            RegisterState state = RegisterReplay.replay(key, log.read(), opened.blobs());
            anchorNote = RegisterWrites.anchorsBefore(ledger, opened, log);
            RegisterWrites.writeDerived(opened, log, state);
            // Anchors a head the ledger could not take when it was written; an anchor the ledger holds stays
            unanchored = RegisterWrites.anchor(ledger, opened, log);
            return opened.location();
        }
    }

    /** Verifies the register's chain, blobs and state file, and its log against the anchors in the ledger. */
    public ChainReport verify(String key) {
        return verified(key).report();
    }

    /**
     * As {@link #verify}, with the line about the anchor. An anchor that does not match its line, or a ledger that
     * cannot be read, is a problem; a register without an anchor, and lines after the last anchor, are said in the
     * line and are none.
     */
    public Verified verified(String key) {
        try (RegisterStore.Opened opened = store.open(requireKnown(key))) {
            // The anchors first, the lines after: a reader takes no lock, and a register is written before its
            // anchor. A write in between then leaves lines without an anchor, never an anchor beyond the lines
            List<traceworks.core.port.RegisterAnchor> anchors = null;
            RuntimeException unread = null;
            if (ledger != null) {
                try {
                    anchors = ledger.registerAnchors(key);
                } catch (RuntimeException e) {
                    unread = e;
                }
            }
            List<byte[]> lines = opened.events().readAll();
            ChainReport chain = RegisterVerifier.verify(key, lines, opened.blobs(), opened.state());
            if (ledger == null) {
                return new Verified(chain, null, chain.intact());
            }
            RegisterAnchors.Check check;
            try {
                if (unread != null) {
                    throw unread;
                }
                check = RegisterAnchors.check(lines, anchors);
            } catch (RuntimeException e) {
                check = new RegisterAnchors.Check(List.of("the anchor of the register was not compared: the ledger"
                        + " could not be read (" + e.getMessage() + ")"), "NOT checked");
            }
            List<String> problems = new ArrayList<>(chain.problems());
            problems.addAll(check.problems());
            return new Verified(new ChainReport(problems.isEmpty(), chain.events(), chain.head(), problems,
                    chain.deletedBlobs()), check.summary(), chain.intact());
        }
    }

    private String requireKnown(String key) {
        if (!store.keys().contains(key)) {
            throw new RunRefusedException("No register '" + key + "'" + (store.keys().isEmpty() ? "; no run has fed one"
                    : "; the registers are " + String.join(", ", store.keys())));
        }
        return key;
    }
}
