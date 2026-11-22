package traceworks.core.app;

import traceworks.core.port.Ledger;
import traceworks.core.port.StoredRun;
import traceworks.core.record.ChainReport;
import traceworks.core.record.ChainVerifier;
import traceworks.core.record.Manifest;
import traceworks.core.record.RunView;
import traceworks.core.record.StartEntry;
import traceworks.core.util.Message;
import traceworks.core.view.RunHtml;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a run's record leaves besides the log: {@code run.html}, and for a finished run its manifest and ledger
 * entry.
 */
final class RunOutputs {

    private final Ledger ledger;
    private final Registers registers;

    RunOutputs(Ledger ledger, Registers registers) {
        this.ledger = ledger;
        this.registers = registers;
    }

    /**
     * After the writer appended the run's end: a parked run gets {@code run.html} only; a run with a final status its
     * manifest, its ledger entry and {@code run.html}, in that order. {@code run.html} is written also when the
     * manifest or the ledger entry fails, and that failure is thrown afterwards. A record whose chain does not verify
     * gets no manifest and no ledger entry: they would vouch for a record that does not hold.
     *
     * @param catalogJson the package's {@code catalog.json}, for the register; {@code null} for none
     * @return what the run's end did to its register; {@code null} for a workflow without an audit block
     * @throws RecordDamagedException if the run has a final status and its chain does not verify
     */
    RegisterOutcome finish(StoredRun run, byte[] catalogJson) {
        List<byte[]> lines = run.events().readAll();
        RunView view = RunRecords.view(run.runId(), RunRecords.parse(run.runId(), lines), false);
        ChainReport chain = ChainVerifier.verify(run.runId(), lines, run.blobs());
        RegisterOutcome register = null;
        if (RunRecords.isFinal(view.status())) {
            try {
                if (!chain.intact()) {
                    throw new RecordDamagedException("The record of run " + run.runId() + " does not verify ("
                            + chain.problems().getFirst() + "), so it gets no manifest and no ledger entry"
                            + RecordDamagedException.seeVerify(run.runId()));
                }
                byte[] manifest = Manifest.of(view, run.blobs(), TraceworksVersion.current()).toJson();
                run.writeManifest(manifest);
                ledger.record(run.runId(), view.status().name(), manifest);
                register = registers.feed(run, view, chain.head(), catalogJson).orElse(null);
            } catch (RuntimeException e) {
                try {
                    writeView(run, view, chain);
                } catch (RuntimeException viewFailure) {
                    e.addSuppressed(viewFailure);
                }
                throw e;
            }
        }
        writeView(run, view, chain);
        return register;
    }

    /**
     * Completes the finalisation of a run with a final status whose manifest or ledger entry is missing, because the
     * process that finished it could not write them (no git, a full disk). Nothing is written to the log. A manifest
     * or ledger copy that is there must be the one the record gives; the missing one is written from it, or both from
     * the record. Doing it again completes nothing. Only a run folder in this home's {@code runs/} is completed: a
     * copied folder belongs to the ledger of the home that ran it, and {@code verify} is what checks a copy.
     *
     * @return what was written, in order: {@code start entry} (of a run whose start the ledger could not note, and
     *         that ended without a step for it), {@code manifest}, {@code ledger entry}; empty when nothing was
     *         missing
     * @throws RunRefusedException    if the run folder is not in the runs folder of this home
     * @throws RecordDamagedException if the chain does not verify, or a manifest or ledger copy that is there does
     *                                not match the record
     */
    List<String> complete(StoredRun run, byte[] catalogJson) {
        String id = run.runId();
        if (!run.inStore()) {
            throw new RunRefusedException(Message.of("Run ", Message.quoted(id), " has finished, and its folder is not in the runs folder of"
                    + " this home, so this home's ledger does not record it; check the copy with traceworks verify ",
                    Message.typed(run.location())));
        }
        Optional<byte[]> manifest = run.readManifest();
        Optional<byte[]> copy = ledger.find(id);
        List<byte[]> lines = run.events().readAll();
        RunView view = RunRecords.view(id, RunRecords.parse(id, lines), false);
        // A run that ended FAILED because the ledger could not note its start: the entry is written now
        boolean startMissing = view.ledgerStart() && ledger.findStart(id).isEmpty();
        if (manifest.isPresent() && copy.isPresent() && !startMissing) {
            feedQuietly(run, catalogJson);
            return List.of();
        }
        ChainReport chain = ChainVerifier.verify(id, lines, run.blobs());
        if (!chain.intact()) {
            throw new RecordDamagedException("The record of run " + id + " does not verify ("
                    + chain.problems().getFirst() + "), so its finalisation is not completed"
                    + RecordDamagedException.seeVerify(id));
        }
        for (Optional<byte[]> written : List.of(manifest, copy)) {
            if (written.isPresent() && !Verification.matches(written.get(), view, run.blobs(),
                    Verification.namesStarter(lines))) {
                throw new RecordDamagedException("The " + (written == manifest ? "manifest" : "ledger entry")
                        + " of run " + id + " does not match its record" + RecordDamagedException.seeVerify(id));
            }
        }
        byte[] json = manifest.or(() -> copy)
                .orElseGet(() -> Manifest.of(view, run.blobs(), TraceworksVersion.current()).toJson());
        List<String> completed = new ArrayList<>();
        if (startMissing) {
            ledger.recordStart(id, StartEntry.of(lines.getFirst()).toJson());
            completed.add("start entry");
        }
        if (manifest.isEmpty()) {
            run.writeManifest(json);
            completed.add("manifest");
        }
        if (copy.isEmpty()) {
            ledger.record(id, view.status().name(), json);
            completed.add("ledger entry");
        }
        registers.feed(run, view, chain.head(), catalogJson);
        writeView(run, view, chain);
        return completed;
    }

    /**
     * Before a resume goes on: the ledger must hold the start entry of the run, as before its first step. A run that
     * lost its entry, never got it (its process was killed between {@code run.started} and the commit) or is of a
     * version that noted no start gets it now, from the first line of its record; a resume adds none to a run that
     * has one. So no step of this version runs in a run the ledger does not name.
     *
     * @throws RunRefusedException if the ledger cannot be read or cannot take the entry: the resume does not go on
     */
    void requireStartNoted(StoredRun run) {
        String id = run.runId();
        try {
            if (ledger.findStart(id).isEmpty()) {
                ledger.recordStart(id, StartEntry.of(run.events().readAll().getFirst()).toJson());
            }
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("Run ", Message.quoted(id), " cannot resume: the ledger holds no start entry of it and"
                    + " could not take one now, and no step runs in a run the ledger does not name: ",
                    Message.of(e)));
        }
    }

    /** Renders the record as it is now into {@code run.html}. */
    static void writeView(StoredRun run, RunView view, List<byte[]> lines) {
        writeView(run, view, ChainVerifier.verify(run.runId(), lines, run.blobs()));
    }

    private static void writeView(StoredRun run, RunView view, ChainReport chain) {
        run.writeView(RunHtml.render(view, run.blobs(), chain, TraceworksVersion.current()));
    }

    /** Feeds the register of a run that was finalised earlier, which is idempotent; a failure is not this caller's. */
    private void feedQuietly(StoredRun run, byte[] catalogJson) {
        try {
            List<byte[]> lines = run.events().readAll();
            RunView view = RunRecords.view(run.runId(), RunRecords.parse(run.runId(), lines), false);
            ChainReport chain = ChainVerifier.verify(run.runId(), lines, run.blobs());
            if (chain.intact()) {
                registers.feed(run, view, chain.head(), catalogJson);
            }
        } catch (RuntimeException ignored) {
            // the register says what it holds; a completion changes nothing of the run
        }
    }
}
