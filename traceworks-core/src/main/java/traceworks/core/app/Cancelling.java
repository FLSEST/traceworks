package traceworks.core.app;

import traceworks.core.engine.RunGate;
import traceworks.core.model.RunStatus;
import traceworks.core.port.Clock;
import traceworks.core.port.Ids;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunView;
import traceworks.core.util.Message;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The cancel use case. A parked or interrupted run has no writer: it is finished here, as the holder of its writer
 * lock. First what is left of its containers is removed (an interrupted run's process may have died while one ran),
 * and the cancel is refused unless that is confirmed; then come {@code run.cancel_requested}, {@code run.finished}
 * with the status {@code CANCELLED} and the gate of its last checkpoint's steps, the removal of its checkout of a
 * source that no command could write in ({@link Checkouts#afterRun}), and its manifest, ledger entry and
 * {@code run.html}. A running run belongs to the process that writes it: the cancel goes into its inbox, and the
 * cancel waits for the run to end.
 */
final class Cancelling {

    static final Duration WAIT = Duration.ofSeconds(30);

    private final Clock clock;
    private final Ids ids;
    private final String actor;
    private final RunOutputs outputs;
    private final Duration wait;
    private final BiConsumer<String, List<RecordedEvent>> removeLeftovers;
    private final Checkouts checkouts;

    /**
     * @param wait            how long a cancel of a running run waits for the run to end
     * @param removeLeftovers removes what is left of the containers of the run with this id and these events, and
     *                        throws {@link RunRefusedException} unless that is confirmed
     * @param checkouts       removes the checkout of a cancelled run's source that no command could write in
     */
    Cancelling(Clock clock, Ids ids, String actor, RunOutputs outputs, Duration wait,
               BiConsumer<String, List<RecordedEvent>> removeLeftovers, Checkouts checkouts) {
        this.clock = clock;
        this.ids = ids;
        this.actor = actor;
        this.outputs = outputs;
        this.wait = wait;
        this.removeLeftovers = removeLeftovers;
        this.checkouts = checkouts;
    }

    /**
     * @throws RunRefusedException if the run has finished, or what is left of its containers cannot be confirmed
     *                             removed
     */
    RunResult cancel(StoredRun run) {
        return cancel(run, null);
    }

    /** As {@link #cancel(StoredRun)}, for a cancel {@code agent} asked for under the actor; {@code null} for none. */
    RunResult cancel(StoredRun run, String agent) {
        if (run.tryLockWriter()) {
            return cancelStopped(run, ids.newRequestId(), agent);
        }
        return cancelRunning(run, agent);
    }

    /** No process runs the run, and this one holds its writer lock: the run is finished here. */
    private RunResult cancelStopped(StoredRun run, String requestId, String agent) {
        return finish(run, requestId, List.of(), agent);
    }

    /**
     * Finishes as {@code CANCELLED} a run that no process runs and whose writer lock this process holds, without a
     * step: for this process's own cancel, for every cancel waiting in its inbox, and for a cancel its record holds
     * already. A cancel is never stale: it is meant for whichever process runs the run next, so each one in the inbox
     * is recorded as {@code run.cancel_requested} with its sender. Every other request there was bound to a question
     * of a run that went on without it, and is refused ({@link RunRecords#refuseStale}).
     *
     * @param requestId the id this process's own cancel is recorded with; {@code null} when it has none (a resume
     *                  that found a cancel)
     * @param taken     requests the caller already took from the inbox, oldest first
     * @throws RunRefusedException if the run has finished, or what is left of its containers cannot be confirmed
     *                             removed
     */
    RunResult finish(StoredRun run, String requestId, List<InboxRequest> taken) {
        return finish(run, requestId, taken, null);
    }

    /**
     * As {@link #finish(StoredRun, String, List)}.
     *
     * @param agent the agent that asked for this process's own cancel under the actor; {@code null} for none
     */
    private RunResult finish(StoredRun run, String requestId, List<InboxRequest> taken, String agent) {
        // Before anything is recorded: nobody runs the run now, and a container its dead process left can still write
        List<RecordedEvent> events = RunRecords.events(run);
        removeLeftovers.accept(run.runId(), events);
        RunView view = RunRecords.view(run.runId(), events, false);
        if (RunRecords.isFinal(view.status())) {
            throw new RunRefusedException(Message.of("Run ", Message.quoted(run.runId()), " has finished (" + view.status()
                    + "); there is nothing to cancel"));
        }
        Map<String, Object> gate = checkpointGate(run, view);
        RunRecorder recorder = RunRecords.continueRecord(run, events, RunRecords.RECORDING_ONLY, clock);
        List<InboxRequest> requests = new ArrayList<>(taken);
        requests.addAll(run.inbox().take());
        List<InboxRequest> cancels = RunRecords.refuseStale(recorder, requests);
        if (requestId != null && notRecorded(events, requestId)) {
            recorder.record(RecordTypes.RUN_CANCEL_REQUESTED, RunRecords.cancelRequested(actor, requestId, agent));
        }
        for (InboxRequest cancel : cancels) {
            if (!cancel.id().equals(requestId) && notRecorded(events, cancel.id())) {
                recorder.record(RecordTypes.RUN_CANCEL_REQUESTED,
                        RunRecords.cancelRequested(cancel.actor(), cancel.id(), inboxAgent(cancel)));
            }
        }
        recorder.emit(new RunFinished(RunStatus.CANCELLED, gate, null));
        RunResult.KeptCheckout kept = checkouts.afterRun(run.runId(), events, RunStatus.CANCELLED);
        RegisterOutcome register = outputs.finish(run, null);
        return new RunResult(run.runId(), run.location(), RunStatus.CANCELLED, gate, null, List.of(), kept, register);
    }

    /** The agent a cancel from the inbox names, when it is a name; the inbox is a folder, so anything else is none. */
    private static String inboxAgent(InboxRequest cancel) {
        return cancel.agent() == null || traceworks.core.register.DecisionAgent.problem(cancel.agent()) != null
                ? null : cancel.agent().strip();
    }

    /** Whether the record holds no {@code run.cancel_requested} of this request yet. */
    private static boolean notRecorded(List<RecordedEvent> events, String requestId) {
        return events.stream().noneMatch(e -> RecordTypes.RUN_CANCEL_REQUESTED.equals(e.type()) && mine(e, requestId));
    }

    /**
     * The gate of the last checkpoint's steps; none when there is no checkpoint, or its state or a text the state
     * refers to (a step's output, which holds its gate) was deleted.
     *
     * @throws RecordDamagedException if the state or a text it refers to has other content than the record names
     */
    private static Map<String, Object> checkpointGate(StoredRun run, RunView view) {
        RunView.Checkpoint checkpoint = view.lastCheckpoint();
        if (checkpoint == null || checkpoint.state() == null) {
            return Map.of();
        }
        return RunRecords.snapshotUnlessDeleted(run, checkpoint.state())
                .map(state -> RunGate.ofSteps(state.steps()))
                .orElse(Map.of());
    }

    /**
     * Another process writes the run: the cancel goes into its inbox. If the run parks or its process dies before it
     * ended (whether the cancel reached it or not), it is finished here instead.
     *
     * @throws RunRefusedException    if the record refuses this cancel
     * @throws RequestQueuedException if the run's process has not recorded the cancel within the wait
     */
    private RunResult cancelRunning(StoredRun run, String agent) {
        String requestId = ids.newRequestId();
        run.inbox().post(new InboxRequest(requestId, "cancel", null, null, null, null, Map.of(), null, actor,
                clock.now(), agent));
        long deadline = System.nanoTime() + wait.toNanos();
        boolean seen = false;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(Answering.POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            List<RecordedEvent> events = RunRecords.events(run);
            if (finished(events)) {
                return finishedResult(run, events);
            }
            refuseIfRefused(run, events, requestId);
            seen = seen || events.stream().anyMatch(e -> mine(e, requestId));
            if (run.tryLockWriter()) {
                // The run parked, or its process died, before it ended: it is finished here
                List<RecordedEvent> now = RunRecords.events(run);
                if (finished(now)) {
                    return finishedResult(run, now);
                }
                refuseIfRefused(run, now, requestId);
                return cancelStopped(run, requestId, agent);
            }
        }
        if (!seen) {
            throw new RequestQueuedException("The cancel is requested, not yet recorded: request " + requestId
                    + " waits in the inbox of run " + run.runId() + ", whose process has not recorded it within "
                    + wait.toSeconds() + " s; see what became of it with traceworks show " + run.runId());
        }
        RunView view = RunRecords.view(run.runId(), RunRecords.events(run), run.writerAlive());
        return new RunResult(run.runId(), run.location(), view.status(), RunRecords.gateOf(view),
                "cancel requested; the run has not ended yet");
    }

    private static boolean mine(RecordedEvent event, String requestId) {
        return requestId.equals(event.fields().path("request").asText(null));
    }

    private static void refuseIfRefused(StoredRun run, List<RecordedEvent> events, String requestId) {
        for (RecordedEvent event : events) {
            if (RecordTypes.REQUEST_REFUSED.equals(event.type()) && mine(event, requestId)) {
                throw new RunRefusedException(Message.of("The cancel of run ", Message.quoted(run.runId()), " was refused: ",
                        Message.quoted(event.fields().path("reason").asText())));
            }
        }
    }

    private static boolean finished(List<RecordedEvent> events) {
        return events.stream().anyMatch(e -> e.parent() == null && RecordTypes.RUN_FINISHED.equals(e.type()));
    }

    private static RunResult finishedResult(StoredRun run, List<RecordedEvent> events) {
        RunView view = RunRecords.view(run.runId(), events, false);
        return new RunResult(run.runId(), run.location(), view.status(), RunRecords.gateOf(view), null);
    }

}
