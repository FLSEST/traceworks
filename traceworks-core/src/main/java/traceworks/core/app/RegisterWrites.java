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
     * What a writer finds of the anchors before it writes, under the register's lock. Lines after the last anchor
     * are taken as they are, since a writer that was cut off before its anchor leaves the same as someone who
     * appended to the log; but the writer records them ({@code register.unanchored}, with the first and the last
     * sequence number) and says so. A log that does not match its anchors is said too; {@code findings <key> verify}
     * names the place. A ledger that cannot be read says nothing here: the anchoring after the write reports it.
     *
     * @return what to tell the person; {@code null} when the last line is anchored or no anchor exists yet
     */
    static String anchorsBefore(Ledger ledger, RegisterStore.Opened opened, RegisterLog log) {
        if (ledger == null || log.lastSeq() < 0) {
            return null;
        }
        List<traceworks.core.port.RegisterAnchor> anchors;
        try {
            anchors = ledger.registerAnchors(opened.key());
        } catch (RuntimeException e) {
            return null;
        }
        String note = null;
        if (!traceworks.core.register.RegisterAnchors.check(opened.events().readAll(), anchors)
                .problems().isEmpty()) {
            note = "the register does not match its anchors in the ledger ('traceworks findings " + opened.key()
                    + " verify' says where)";
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
     * @return why the ledger did not take it; {@code null} when it did, or when there is no ledger
     */
    static String anchor(Ledger ledger, RegisterStore.Opened opened, RegisterLog log) {
        if (ledger == null || log.lastSeq() < 0) {
            return null;
        }
        try {
            ledger.anchorRegister(opened.key(), log.lastSeq(), log.head());
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
                .RegisterVerifier.verify(opened.key(), opened.events().readAll(), opened.blobs(), Optional.empty());
        opened.writeView(traceworks.core.view.RegisterHtml.render(state, events, opened.blobs(), chain,
                TraceworksVersion.current()).getBytes(StandardCharsets.UTF_8));
    }
}
