package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.compare.RunComparer;
import traceworks.core.compare.RunComparison;
import traceworks.core.compare.RunComparison.FindingsDelta;
import traceworks.core.compare.RunFacts;
import traceworks.core.port.Clock;
import traceworks.core.port.Ledger;
import traceworks.core.port.RegisterStore;
import traceworks.core.port.StoredRun;
import traceworks.core.record.Manifest;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;
import traceworks.core.model.AuditConfig;
import traceworks.core.register.Finding;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterState;
import traceworks.core.register.StateJson;
import traceworks.core.util.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The use case behind {@code traceworks diff}: compares two runs from their records and writes nothing. Each run must
 * have finished and must verify (chain, blobs, manifest and ledger entry, as {@code verify} checks them), so a
 * difference is never one that damage or tampering made; a run that has not finished, cannot be read or does not
 * verify is refused, each with a message of its own. For two audit runs that feed the same register, the findings are
 * compared between the state the register recorded after each run's report ({@code state_blob} of its
 * {@code report.applied}).
 */
final class Comparing {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Comparing() {
    }

    /**
     * @throws RunRefusedException    if a run has not finished
     * @throws RecordDamagedException if a run's record cannot be read or does not verify
     */
    static RunComparison compare(StoredRun a, StoredRun b, Ledger ledger, RegisterStore registers, Clock clock) {
        RunFacts factsA = facts(a, ledger);
        RunFacts factsB = facts(b, ledger);
        RunComparison comparison = RunComparer.compare(factsA, factsB);
        return comparison.withFindings(findings(a, b, factsA.view(), factsB.view(), registers, clock));
    }

    private static RunFacts facts(StoredRun run, Ledger ledger) {
        String id = run.runId();
        List<RecordedEvent> events = RunRecords.events(run);
        RunView view = RunRecords.view(id, events, run.writerAlive());
        if (!RunRecords.isFinal(view.status())) {
            throw new RunRefusedException(Message.of("Run ", Message.quoted(id), " has not finished (" + view.status() + "), so it cannot be"
                    + " compared: only a finished run has a manifest and a ledger entry to verify"));
        }
        VerifyReport report = Verification.verify(run, ledger);
        if (!report.ok()) {
            String problem = !report.chain().problems().isEmpty() ? report.chain().problems().getFirst()
                    : !acceptable(report.manifest()) ? "manifest " + report.manifest() : "ledger " + report.ledger();
            throw new RecordDamagedException("The record of run " + id + " does not verify (" + problem + "), so it is"
                    + " not compared" + RecordDamagedException.seeVerify(id));
        }
        byte[] manifestJson = run.readManifest().orElseThrow(() -> new RecordDamagedException("Run " + id
                + " has no manifest" + RecordDamagedException.seeVerify(id)));
        JsonNode written;
        try {
            written = JSON.readTree(manifestJson);
        } catch (IOException e) {
            throw new RecordDamagedException("The manifest of run " + id + " cannot be read: " + e.getMessage()
                    + RecordDamagedException.seeVerify(id));
        }
        // The manifest verified, so it is the one the record gives; its models stand also where a metadata blob is gone
        List<String> models = new ArrayList<>();
        written.path("models").forEach(model -> models.add(model.asText()));
        Manifest manifest = Manifest.of(view, run.blobs(), written.path("traceworks_version").asText(null))
                .withModels(models);
        Map<String, Object> inputs = view.inputs() == null ? null
                : RunRecords.checkedContent(run, view.inputs(), "inputs")
                        .map(bytes -> RunRecords.inputs(id, bytes)).orElse(null);
        Map<String, String> aiImages = new LinkedHashMap<>();
        events.getFirst().fields().path("ai_images").fields()
                .forEachRemaining(entry -> aiImages.put(entry.getKey(), entry.getValue().asText()));
        return new RunFacts(view, manifest, inputs, aiImages);
    }

    private static boolean acceptable(String state) {
        return VerifyReport.OK.equals(state) || VerifyReport.NOT_CHECKED.equals(state);
    }

    /** The register key of the audit workflow the run ran; empty for any other workflow, or a deleted definition. */
    private static Optional<String> registerKey(StoredRun run, RunView view) {
        if (view.definition() == null) {
            return Optional.empty();
        }
        return RunRecords.checkedContent(run, view.definition(), "workflow definition")
                .flatMap(bytes -> AuditConfig.of(RunRecords.definition(run.runId(), bytes).metadata()))
                .map(AuditConfig::key);
    }

    /** {@code null} when neither run is an audit run. */
    private static FindingsDelta findings(StoredRun a, StoredRun b, RunView viewA, RunView viewB,
                                          RegisterStore registers, Clock clock) {
        String keyA = registerKey(a, viewA).orElse(null);
        String keyB = registerKey(b, viewB).orElse(null);
        if (keyA == null && keyB == null) {
            return null;
        }
        if (keyA == null || !keyA.equals(keyB)) {
            return FindingsDelta.notCompared(keyA == null ? keyB : keyA, "the two runs do not feed the same register");
        }
        if (!registers.keys().contains(keyA)) {
            return FindingsDelta.notCompared(keyA, "this home has no register " + keyA);
        }
        try (RegisterStore.Opened opened = registers.open(keyA)) {
            List<RegisterEvent> events = new RegisterLog(opened, clock).read();
            RegisterState[] states = new RegisterState[2];
            String[] ids = {a.runId(), b.runId()};
            for (int i = 0; i < 2; i++) {
                RegisterEvent applied = applied(events, ids[i]);
                if (applied == null) {
                    return FindingsDelta.notCompared(keyA, "the register " + keyA + " did not apply the report of run "
                            + ids[i] + ", so it holds no state after it");
                }
                byte[] state = opened.blobs().get(applied.text("state_blob")).orElse(null);
                if (state == null) {
                    return FindingsDelta.notCompared(keyA, "the state the register " + keyA + " recorded after run "
                            + ids[i] + " is deleted");
                }
                states[i] = StateJson.read(state);
            }
            return delta(keyA, states[0], states[1]);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return FindingsDelta.notCompared(keyA, "the register " + keyA + " cannot be read: " + e.getMessage());
        }
    }

    private static RegisterEvent applied(List<RegisterEvent> events, String runId) {
        for (RegisterEvent event : events) {
            if (RegisterEvents.REPORT_APPLIED.equals(event.type()) && runId.equals(event.text("run_id"))) {
                return event;
            }
        }
        return null;
    }

    private static FindingsDelta delta(String key, RegisterState before, RegisterState after) {
        List<RunComparison.Finding> added = new ArrayList<>();
        List<RunComparison.Finding> resolved = new ArrayList<>();
        List<RunComparison.Finding> reopened = new ArrayList<>();
        for (Finding finding : after.findings()) {
            Finding earlier = before.finding(finding.id());
            RunComparison.Finding named = new RunComparison.Finding(finding.id(),
                    finding.severity() == null ? null : finding.severity().name(), finding.subject(), finding.title());
            if (earlier == null) {
                added.add(named);
            } else if (finding.status() == FindingStatus.RESOLVED && earlier.status() != FindingStatus.RESOLVED) {
                resolved.add(named);
            } else if (finding.status() == FindingStatus.OPEN && earlier.status() != FindingStatus.OPEN) {
                reopened.add(named);
            }
        }
        return new FindingsDelta(key, null, added, resolved, reopened);
    }
}
