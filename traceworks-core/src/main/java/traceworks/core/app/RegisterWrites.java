package traceworks.core.app;

import traceworks.core.port.Ledger;
import traceworks.core.port.RegisterStore;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterState;
import traceworks.core.register.StateJson;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What every write to a register does besides its own lines, under the register's lock: it looks at the anchors of
 * the log in the ledger before it writes, anchors the head of the log after it, and writes the files derived from
 * the log. {@link Registers} (a run's report) and {@link Findings} (a person's decision) share it.
 */
final class RegisterWrites {

    private RegisterWrites() {}

    /**
     * A {@code findings} command of a register as a person types it, with the project the register is of:
     * {@code traceworks findings hello verify --project shop}. Every hint names the project, so that it works
     * whatever number of projects the key has.
     */
    static String command(String key, String project, String command) {
        return "traceworks findings " + key + " " + command + (project == null ? "" : " --project " + project);
    }

    /**
     * The one message for a register from before registers had projects: nothing assigns it by itself, and until
     * someone does, no run reads or feeds it and no command shows or decides its findings.
     */
    static String fromBefore(String key) {
        return "the register " + key + " is from before registers had projects, and nothing assigns it to one by"
                + " itself: assign it to its project with '" + command(key, null, "assign") + " --project KEY'";
    }

    /**
     * Why a register the ledger knows and whose log is not there is gone, for every run and every command: the
     * ledger holds anchors of the key and the project, and the register's log has not begun (it is missing, empty,
     * a fragment of a line, or holds notes of the tool alone). A run that started on it would be handed nothing,
     * and a write would begin a log at seq 0 under the anchors of the one that is gone; so nothing is begun in its
     * place, and nothing is written. A register the ledger never anchored is begun as ever. Anchors that cannot be
     * read refuse a register without a log the same way: what cannot be asked is not taken as "no anchors". The
     * same holds for the key as a whole when the ledger anchors a register from before registers had projects
     * that is not there any more ({@link #earlierGone}).
     *
     * <p>An acknowledgement of the problem in {@code traceworks verify} is not asked here: it silences that check
     * and begins nothing anew.
     *
     * @param ledger the ledger; {@code null} for none
     * @return the reason, for a refusal; {@code null} when the log has begun, or the ledger holds no anchor of it
     * @throws RuntimeException if the store cannot be listed or the log cannot be read
     */
    static String logGone(Ledger ledger, RegisterStore store, String key, String project) {
        if (ledger == null) {
            return null;
        }
        String earlier = earlierGone(ledger, store, key);
        if (earlier != null) {
            return earlier;
        }
        if (store.projects(key).contains(project)) {
            try (RegisterStore.Opened opened = store.open(key, project)) {
                if (begun(opened.events().readAll())) {
                    return null;
                }
            }
        }
        String name = RegisterOutcome.named(key, project);
        try {
            if (ledger.registerAnchors(key, project).isEmpty()) {
                return null;
            }
        } catch (RuntimeException e) {
            return "the log of the register " + name + " is not there, and the ledger cannot say whether it holds"
                    + " anchors of it (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    + "): a register is not begun while that cannot be asked";
        }
        return "the ledger holds anchors of the register " + name + ", and its log is gone: the register is not"
                + " begun anew under the anchors of the one that was there. Put the register's folder back, or feed"
                + " another project key (--project KEY). " + VERIFY_SAYS;
    }

    private static final String VERIFY_SAYS = "'traceworks verify' says what the ledger holds of it; an"
            + " acknowledgement there silences that check and begins nothing anew";

    /**
     * Why no run and no command works on any register of {@code key}: the ledger holds the anchors an earlier
     * version wrote of the register of that key, from before registers had projects, and nothing in this home
     * accounts for them: neither a register at the key itself, nor a project whose log says that it was assigned
     * ({@code register.assigned}). That register may have been any project's, so none is begun beside its anchors.
     * A project whose log cannot be read accounts for nothing: while no other register accounts for the anchors,
     * the key is refused, and the reason names that project. A ledger that cannot be read says nothing here;
     * {@link #logGone} refuses a register without a log for it.
     *
     * @param ledger the ledger; {@code null} for none
     * @return the reason, for a refusal; {@code null} when the ledger holds no such anchors, or they are accounted
     *         for
     * @throws RuntimeException if the store cannot be listed
     */
    static String earlierGone(Ledger ledger, RegisterStore store, String key) {
        if (ledger == null) {
            return null;
        }
        try {
            if (ledger.earlierRegisterAnchors(key).isEmpty()) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        // A project whose log cannot be read accounts for nothing: it may be anything, and while nothing else
        // accounts for the anchors the key stays refused. The message names it, since it is what stands in the way
        List<String> unread = new java.util.ArrayList<>();
        if (store.keys().contains(key)) {
            if (store.unassigned(key)) {
                return null;
            }
            for (String project : store.projects(key)) {
                try (RegisterStore.Opened opened = store.open(key, project)) {
                    if (Findings.assigned(opened.events().readAll())) {
                        return null;
                    }
                } catch (RuntimeException e) {
                    unread.add("the log of the register " + RegisterOutcome.named(key, project) + " cannot be read ("
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                            + "), so it accounts for nothing");
                }
            }
        }
        return "the ledger holds anchors of the register " + key + " from before registers had projects, and this"
                + " home holds neither that register nor one that was assigned from it"
                + (unread.isEmpty() ? "" : " (" + String.join("; ", unread) + ")") + ": no register of " + key
                + " is begun beside the anchors of the one that was there, for any project. Put the register's"
                + " folder back, or feed a register of another key. " + VERIFY_SAYS;
    }

    /**
     * Whether a log has begun, by its complete lines: it holds a line that is no note of the tool
     * ({@link traceworks.core.register.RegisterReplay#begun}). A line that is no line of a register counts: such a
     * log is damaged, not gone, and is refused for what it is.
     */
    static boolean begun(List<byte[]> lines) {
        return traceworks.core.register.RegisterReplay.begunLines(lines);
    }

    /**
     * The registers the ledger holds anchors of, for the listings of {@code findings}: a register whose folder was
     * removed is still one of its key. A ledger that cannot be read names none here; what needs its answer asks
     * again and refuses ({@link #logGone}).
     *
     * @param ledger the ledger; {@code null} for none
     */
    static List<Ledger.Anchored> anchored(Ledger ledger) {
        if (ledger == null) {
            return List.of();
        }
        try {
            return ledger.anchoredRegisters();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The anchors of a register in the ledger.
     *
     * @param earlier what a version before registers had projects wrote under the key alone, oldest first: the
     *                sequence number and the head of a line, and nothing of what was written
     * @param own     the anchors of the key and the project, oldest first, which follow the earlier ones
     */
    record Anchors(List<traceworks.core.port.RegisterAnchor> earlier, List<traceworks.core.port.RegisterAnchor> own) {

        /** All of them, oldest first. */
        List<traceworks.core.port.RegisterAnchor> all() {
            List<traceworks.core.port.RegisterAnchor> all = new java.util.ArrayList<>(earlier);
            all.addAll(own);
            return all;
        }
    }

    /**
     * The anchors of a register in the ledger, oldest first. A register that was assigned to its project
     * ({@code register.assigned}) was anchored under its key alone before: those anchors come first, so what the
     * ledger said of the log before the assignment still holds it. A register from before projects that is not
     * assigned yet has only those.
     *
     * @param assigned whether the log of the register holds {@code register.assigned}
     * @throws IllegalStateException with the reason when the ledger cannot be read
     */
    static Anchors anchors(Ledger ledger, RegisterStore.Opened opened, boolean assigned) {
        return new Anchors(assigned || opened.project() == null ? ledger.earlierRegisterAnchors(opened.key())
                : List.of(), opened.project() == null ? List.of()
                : ledger.registerAnchors(opened.key(), opened.project()));
    }

    /**
     * What a writer finds of the anchors before it writes, under the register's lock. Lines after the last anchor
     * are taken as they are, since a writer that was cut off before its anchor leaves the same as someone who
     * appended to the log; but the writer records them ({@code register.unanchored}, with the first and the last
     * sequence number) and says so. A log that does not match its anchors is said too; {@code findings <key> verify}
     * names the place. A ledger that cannot be read says nothing here: the anchoring after the write reports it.
     *
     * @return what to tell the person; {@code null} when the last line is anchored or no anchor exists yet
     */
    static String anchorsBefore(Ledger ledger, RegisterStore.Opened opened, RegisterLog log) {
        return anchorsBefore(ledger, opened, log, opened.project());
    }

    /**
     * As {@link #anchorsBefore(Ledger, RegisterStore.Opened, RegisterLog)}, for the write that assigns a register
     * from before registers had projects: the person reads the note when the register is that of {@code project},
     * so the command it names must name that project. The register is compared with the anchors of that project
     * too, not only with the earlier ones: a backup assigned again to the project whose folder was lost lacks what
     * the ledger anchors for the project, and the note says so.
     */
    static String anchorsBefore(Ledger ledger, RegisterStore.Opened opened, RegisterLog log, String project) {
        if (ledger == null || log.lastSeq() < 0) {
            return null;
        }
        boolean assigned = traceworks.core.register.RegisterReplay.assignedTo(log.read()) != null;
        Anchors held;
        try {
            held = anchors(ledger, opened, assigned);
            if (opened.project() == null && project != null) {
                held = new Anchors(held.earlier(), ledger.registerAnchors(opened.key(), project));
            }
        } catch (RuntimeException e) {
            return null;
        }
        // The lines no anchor covers are those after the anchors of the register as it lies: for an assignment the
        // earlier ones, since the anchors of the project are of a log that is not this one
        List<traceworks.core.port.RegisterAnchor> anchors = opened.project() == null ? held.earlier() : held.all();
        String note = null;
        if (!traceworks.core.register.RegisterAnchors.check(opened.events().readAll(), held.earlier(), held.own())
                .problems().isEmpty()) {
            // An assignment (the register is still that of no project) goes on all the same: a refusal would
            // leave such a register without any way to a project. The mismatch stays in the record, and the note
            // says who goes on reporting it
            note = opened.project() == null
                    ? "the register does not match its anchors in the ledger, and it is assigned as it is: '"
                    + command(opened.key(), project, "verify") + "' says where, and goes on reporting it"
                    : "the register does not match its anchors in the ledger ('"
                    + command(opened.key(), project, "verify") + "' says where)";
        }
        int[] tail = traceworks.core.register.RegisterAnchors.tail(log.lastSeq(), anchors);
        if (tail != null) {
            // One line per gap: a gap that an earlier write found and recorded is not recorded again. The lines
            // since are the ones of the writes whose anchor failed, each reported when it failed
            RegisterEvent recorded = null;
            for (RegisterEvent event : log.read()) {
                if (event.seq() >= tail[0] && RegisterEvents.UNANCHORED.equals(event.type())) {
                    recorded = event;
                    break;
                }
            }
            String range = "seq " + tail[0] + (tail[0] == tail[1] ? "" : " to " + tail[1]);
            String found;
            if (recorded != null) {
                found = range + (tail[0] == tail[1] ? " is" : " are") + " still not anchored: the gap is recorded at"
                        + " seq " + recorded.seq() + ", and the anchor of every write since failed, as reported then";
            } else {
                Map<String, Object> fields = new java.util.LinkedHashMap<>();
                fields.put("first_seq", tail[0]);
                fields.put("last_seq", tail[1]);
                log.append(RegisterEvents.UNANCHORED, fields);
                found = range + (tail[0] == tail[1] ? " was" : " were") + " not anchored before this write and "
                        + (tail[0] == tail[1] ? "is" : "are") + " taken unproven: lines that were never anchored,"
                        + " left by a write whose anchor failed or appended without Traceworks (recorded as "
                        + RegisterEvents.UNANCHORED + ")";
            }
            note = note == null ? found : note + "; " + found;
        }
        return note;
    }

    /**
     * Anchors the head of the register's log in the ledger, under the register's lock, so the anchors of two writers
     * are in the order of their lines. The write is done by then and stays: a ledger that cannot take the anchor is
     * reported, never thrown, and {@code findings <key> verify} names the lines that are not anchored.
     *
     * <p>The anchor says what was written ({@link traceworks.core.port.RegisterAnchor.Written}). No writer says
     * that of its own lines: it is derived here, from the lines the log gained since the last anchor the ledger
     * holds that the log still agrees with ({@link traceworks.core.register.RegisterAnchors#agreed}). So an anchor
     * that follows one the ledger could not take covers the lines of both writes, the anchor of a write after a
     * cut or to a log that was replaced says what was written, and a head the ledger anchors already gets no
     * second anchor: no anchor says that nothing was written.
     *
     * @return why the ledger did not take it; {@code null} when it did, or when there is no ledger
     */
    static String anchor(Ledger ledger, RegisterStore.Opened opened, RegisterLog log) {
        if (ledger == null || log.lastSeq() < 0) {
            return null;
        }
        try {
            List<RegisterEvent> events = log.read();
            List<traceworks.core.port.RegisterAnchor> held = anchors(ledger, opened,
                    traceworks.core.register.RegisterReplay.assignedTo(events) != null).all();
            // Counted from the last anchor the log still agrees with: one whose line was cut, or that is of a
            // log this one replaced, covers nothing that is here
            traceworks.core.port.RegisterAnchor before = traceworks.core.register.RegisterAnchors.agreed(
                    opened.events().readAll(), held);
            if (before != null && before.seq() == log.lastSeq()) {
                return null;   // a write that added no line: the head is a line the ledger anchors
            }
            ledger.anchorRegister(opened.key(), opened.project(), new traceworks.core.port.RegisterAnchor(
                    log.lastSeq(), log.head(), traceworks.core.register.RegisterAnchors.written(
                    traceworks.core.register.RegisterAnchors.covered(events, before, log.lastSeq()))));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** Writes what is derived from the log: {@code state.json} and {@code report.html}. */
    static void writeDerived(RegisterStore.Opened opened, RegisterLog log, RegisterState state) {
        opened.writeState(StateJson.write(state));
        List<RegisterEvent> events = log.read();
        traceworks.core.record.ChainReport chain = traceworks.core.register
                .RegisterVerifier.verify(opened.key(), state.project(), opened.events().readAll(), opened.blobs(),
                        Optional.empty());
        opened.writeView(traceworks.core.view.RegisterHtml.render(state, events, opened.blobs(), chain,
                TraceworksVersion.current()).getBytes(StandardCharsets.UTF_8));
    }
}
