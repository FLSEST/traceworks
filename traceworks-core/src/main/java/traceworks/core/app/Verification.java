package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.port.BlobStore;
import traceworks.core.port.Ledger;
import traceworks.core.port.StoredRun;
import traceworks.core.record.ChainReport;
import traceworks.core.record.ChainVerifier;
import traceworks.core.record.EventChain;
import traceworks.core.record.Manifest;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RegisterHandover;
import traceworks.core.record.RunView;
import traceworks.core.record.SealView;
import traceworks.core.record.StartEntry;
import traceworks.core.seal.Seal;
import traceworks.core.seal.SshSignature;
import traceworks.core.record.StepView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Verifies a run: the hash chain and the blobs, then whether {@code manifest.json} names the head and the number of
 * events the log has (a cut-off tail leaves an intact chain and shows only there) and is byte for byte the manifest
 * the record gives (a rewritten status or gate shows there), and whether the ledger holds the
 * same manifest and noted the start of the run with the first line the record has, and for the run of an audit workflow whether the files of its register folder are the ones
 * {@code run.started} recorded. A manifest or ledger entry is expected once the log says the run finished; when there is one, it is
 * checked whatever the log says, so a log cut before its {@code run.finished} does not escape. Bytes after the last
 * line feed of a finished run's log are a problem of the chain.
 */
final class Verification {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Verification() {
    }

    static VerifyReport verify(StoredRun run, Ledger ledger) {
        List<byte[]> lines = run.events().readAll();
        String holds = runOfFirstLine(lines);
        if (holds != null && !holds.equals(run.runId())) {
            // A renamed folder: every line would name the other run; one problem says what is wrong
            ChainReport renamed = new ChainReport(false, lines.size(), EventChain.sha256Hex(lines.getLast()),
                    List.of("run folder " + run.runId() + " holds run " + holds), List.of());
            return new VerifyReport(renamed, VerifyReport.NOT_CHECKED, VerifyReport.NOT_CHECKED,
                    "the folder does not hold the run its name says", false);
        }
        boolean finished = finished(lines, run.runId());
        ChainReport chain = afterTheLastLine(ChainVerifier.verify(run.runId(), lines, run.blobs()), finished,
                run.events().tail());
        Optional<byte[]> manifest = run.readManifest();
        // A run folder copied elsewhere is checked offline: this home's ledger does not speak for it
        String ledgerReason = run.inStore() ? null : "the run folder is not in the runs folder of this home; its"
                + " ledger entry is in the ledger of the home it ran in";
        Optional<byte[]> copy = run.inStore() ? ledger.find(run.runId()) : Optional.empty();

        RunView view = viewOf(lines);
        String manifestState = manifest.map(json -> agrees(json, run.runId(), chain, view, run.blobs()))
                .orElse(finished ? VerifyReport.MISSING : VerifyReport.NOT_CHECKED);
        String ledgerState;
        if (ledgerReason != null) {
            ledgerState = VerifyReport.NOT_CHECKED;
        } else if (copy.isEmpty()) {
            ledgerState = finished ? VerifyReport.MISSING : VerifyReport.NOT_CHECKED;
        } else if (manifest.isPresent()) {
            ledgerState = Arrays.equals(copy.get(), manifest.get()) ? VerifyReport.OK : VerifyReport.MISMATCH;
        } else {
            ledgerState = agrees(copy.get(), run.runId(), chain, view, run.blobs());
        }
        String sealState = view == null ? VerifyReport.NOT_CHECKED : seal(view.seal());
        String registerState = view == null ? VerifyReport.NOT_CHECKED : registerFolder(run, view.register());
        String startState = start(run, ledger, lines);
        boolean ok = chain.intact() && acceptable(manifestState) && acceptable(ledgerState)
                && (startState.startsWith(VerifyReport.OK) || startState.startsWith(VerifyReport.NONE)
                || startState.equals(VerifyReport.NOT_CHECKED))
                && (sealState.startsWith(VerifyReport.OK) || sealState.equals(VerifyReport.UNSEALED)
                || sealState.equals(VerifyReport.NOT_CHECKED))
                && (registerState.startsWith(VerifyReport.OK) || registerState.equals(VerifyReport.NOT_CHECKED));
        return new VerifyReport(chain, manifestState, ledgerState, ledgerReason, ok, sealState, registerState,
                startState);
    }

    /**
     * The start entry of the ledger against the first line of the record. The entry holds the SHA-256 of the
     * {@code run.started} line it was written for, so a record whose first line was changed, or that is another
     * record under the same run id, does not match. A {@code run.started} with {@code ledger_start} was written by a
     * version that lets no step run without the entry: such a run without one lost it. A {@code run.started} without
     * the field is of an earlier version and has none; an entry that is there is compared all the same.
     */
    private static String start(StoredRun run, Ledger ledger, List<byte[]> lines) {
        if (!run.inStore()) {
            return VerifyReport.NOT_CHECKED;
        }
        Optional<byte[]> entry;
        try {
            entry = ledger.findStart(run.runId());
        } catch (RuntimeException e) {
            return "NOT checked: " + e.getMessage();
        }
        RecordedEvent first = null;
        try {
            first = lines.isEmpty() ? null : RecordedEvent.parse(lines.getFirst());
        } catch (IllegalArgumentException e) {
            // the chain report names the line
        }
        if (entry.isEmpty()) {
            if (first == null) {
                return VerifyReport.NOT_CHECKED;
            }
            return first.fields().path("ledger_start").asBoolean(false)
                    ? VerifyReport.MISSING + ": run.started says the ledger noted the start of this run, and the"
                    + " ledger holds no start entry of it"
                    : VerifyReport.NONE + ": the run was recorded by a version that noted no start in the ledger"
                    + " (Traceworks " + first.fields().path("version").asText("of an unknown version") + ")";
        }
        String line = first == null ? null : EventChain.sha256Hex(lines.getFirst());
        Optional<StartEntry> noted = StartEntry.parse(entry.get());
        if (noted.isEmpty()) {
            return VerifyReport.MISMATCH + ": the start entry of the ledger cannot be read";
        }
        if (!run.runId().equals(noted.get().runId()) || !noted.get().runStarted().equals(line)) {
            return VerifyReport.MISMATCH + ": the ledger noted the start of run " + noted.get().runId()
                    + " with the run.started line " + noted.get().runStarted() + ", and the first line of this record"
                    + " is " + (line == null ? "not readable" : line);
        }
        return VerifyReport.OK + " (the ledger noted the start, run.started " + line.substring(0, 12) + ")";
    }

    /**
     * The register folder of the run against the digests {@code run.started} recorded: the folder is part of the
     * run's record, so a file that is missing, changed or added is a problem. This says that the files are the ones
     * the run's steps got, not that the register held the truth.
     */
    private static String registerFolder(StoredRun run, RegisterHandover recorded) {
        if (recorded == null) {
            return VerifyReport.NOT_CHECKED;
        }
        String problem = new RegisterFolder(run, recorded).problem();
        return problem != null ? problem : VerifyReport.OK + " (" + recorded.files().size()
                + (recorded.files().size() == 1 ? " file" : " files") + " of the register " + recorded.key()
                + (recorded.seq() == null ? ", " : " ") + recorded.position() + ")";
    }

    /**
     * The seal the record holds, checked against itself: the recorded signature must be a valid one over the recorded
     * fields of the seal, by the key whose fingerprint the record names. This needs no home and no package, so it
     * works on a copied run folder. Whether the signer is allowed is the question of the home that ran the run, and
     * was answered at its start.
     */
    private static String seal(SealView recorded) {
        if (recorded == null) {
            return VerifyReport.UNSEALED;
        }
        try {
            Seal seal = new Seal(recorded.workflowId(), recorded.digest(), recorded.signer(),
                    java.time.Instant.parse(recorded.sealedAt()), recorded.signature());
            SshSignature signature = SshSignature.parse(seal.signature());
            String problem = signature.problem(seal.payload(), SshSignature.NAMESPACE);
            if (problem != null) {
                return "the recorded seal is not validly signed: " + problem;
            }
            if (!signature.fingerprint().equals(recorded.key())) {
                return "the recorded seal names the key " + recorded.key() + ", but its signature was made with "
                        + signature.fingerprint();
            }
            return VerifyReport.OK + " (sealed by " + recorded.signer() + ", key " + recorded.key() + ", digest "
                    + recorded.digest() + ")";
        } catch (RuntimeException e) {
            return "the recorded seal cannot be read: " + e.getMessage();
        }
    }

    /**
     * The chain report with what follows the last line feed. The lines that are read end there, so bytes after it
     * leave an intact chain. After {@code run.finished} no writer appends, so such bytes are tampering or damage, and
     * a problem. A parked or interrupted run's are a line its killed writer did not finish, which its next writer
     * cuts off and records as {@code run.recovered}: no problem.
     */
    private static ChainReport afterTheLastLine(ChainReport chain, boolean finished, byte[] tail) {
        if (tail.length == 0 || !finished) {
            return chain;
        }
        List<String> problems = new ArrayList<>(chain.problems());
        problems.add(tail.length + " bytes after the last line feed of the log, after run.finished, where no writer"
                + " appends");
        return new ChainReport(false, chain.events(), chain.head(), problems, chain.deletedBlobs());
    }

    /** The run the first line belongs to, when it is a root event; {@code null} otherwise. */
    private static String runOfFirstLine(List<byte[]> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        try {
            RecordedEvent first = RecordedEvent.parse(lines.getFirst());
            return first.parent() == null ? first.run() : null;
        } catch (IllegalArgumentException e) {
            return null;   // the chain report names the line
        }
    }

    private static boolean acceptable(String state) {
        return VerifyReport.OK.equals(state) || VerifyReport.NOT_CHECKED.equals(state);
    }

    /**
     * Whether a readable line of the log is the run's own {@code run.finished}. The run's id decides, as it does for
     * the view: a line of the run that names a parent is a problem of the chain, and no way around the manifest
     * and the ledger entry a finished run has.
     */
    private static boolean finished(List<byte[]> lines, String runId) {
        for (byte[] line : lines) {
            try {
                RecordedEvent event = RecordedEvent.parse(line);
                if (runId.equals(event.run()) && RecordTypes.RUN_FINISHED.equals(event.type())) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // the chain report names the line
            }
        }
        return false;
    }

    /**
     * Whether manifest bytes are the manifest the record gives, byte for byte: {@link Manifest#of} of the record,
     * with the Traceworks version the bytes name (the version that finished the run). When a step's metadata blob
     * was deleted, the models cannot all be told from the record, and the ones the bytes name are taken as far as
     * they are possible ({@link Manifest#modelsPossible}): every model the remaining blobs name, and no more further
     * models than blobs are deleted.
     */
    static boolean matches(byte[] json, RunView view, BlobStore blobs) {
        JsonNode written;
        try {
            written = MAPPER.readTree(json);
        } catch (IOException e) {
            return false;
        }
        if (written == null || !written.path("traceworks_version").isTextual()
                || !RunRecords.isFinal(view.status())) {
            return false;
        }
        Manifest expected = Manifest.of(view, blobs, written.path("traceworks_version").textValue());
        if (!written.has("run_started") && !view.ledgerStart()) {
            // A manifest of a version that did not name the run.started line. A run whose start the ledger noted
            // was finished by a version that names it, so its manifest must
            expected = expected.withoutRunStarted();
        }
        if (Manifest.metadataDeleted(view, blobs)) {
            List<String> models = new ArrayList<>();
            for (JsonNode model : written.path("models")) {
                if (!model.isTextual()) {
                    return false;
                }
                models.add(model.textValue());
            }
            if (!Manifest.modelsPossible(models, view, blobs)) {
                return false;
            }
            expected = expected.withModels(models);
        }
        return Arrays.equals(expected.toJson(), json);
    }

    /** The view of a log whose lines all parse; {@code null} otherwise (the chain report names the line). */
    private static RunView viewOf(List<byte[]> lines) {
        try {
            return lines.isEmpty() ? null : RunView.of(lines.stream().map(RecordedEvent::parse).toList(), false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code ok} when the manifest is this run's, names the chain's head and event count, and is byte for byte the
     * manifest the record gives ({@link #matches}, so a rewritten status or gate shows), else {@code mismatch}.
     */
    private static String agrees(byte[] json, String runId, ChainReport chain, RunView view, BlobStore blobs) {
        return VerifyReport.OK.equals(agrees(json, runId, chain)) && view != null && matches(json, view, blobs)
                ? VerifyReport.OK : VerifyReport.MISMATCH;
    }

    /** {@code ok} when the manifest is this run's and names the chain's head and event count, else {@code mismatch}. */
    private static String agrees(byte[] json, String runId, ChainReport chain) {
        try {
            JsonNode manifest = MAPPER.readTree(json);
            boolean same = manifest != null && runId.equals(manifest.path("run_id").asText(null))
                    && manifest.path("events").isInt() && manifest.path("events").intValue() == chain.events()
                    && chain.head().equals(manifest.path("head").asText(null));
            return same ? VerifyReport.OK : VerifyReport.MISMATCH;
        } catch (IOException e) {
            return VerifyReport.MISMATCH;
        }
    }
}
