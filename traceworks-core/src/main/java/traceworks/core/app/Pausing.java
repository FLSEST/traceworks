package traceworks.core.app;

import traceworks.core.port.Clock;
import traceworks.core.port.Ids;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;
import traceworks.core.util.Message;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The pause use case, for a run another process runs: the request goes into the run's inbox, as a cancel does, and
 * the run's process records it and pauses. Only a running run can be paused: a parked or interrupted one has stopped
 * already and goes on with {@code resume}, a finished one is over.
 */
final class Pausing {

    /** The kind of a pause in the inbox. */
    static final String KIND = "pause";

    private final Clock clock;
    private final Ids ids;
    private final String actor;
    private final Duration wait;

    /** @param wait how long a pause waits for the run's process to record it */
    Pausing(Clock clock, Ids ids, String actor, Duration wait) {
        this.clock = clock;
        this.ids = ids;
        this.actor = actor;
        this.wait = wait;
    }

    /**
     * @throws RunRefusedException    if no process runs the run, or the run refused the request
     * @throws RequestQueuedException if the run's process has not recorded the request within the wait
     */
    RunResult pause(StoredRun run, String agent) {
        String runId = run.runId();
        if (run.tryLockWriter()) {
            // Nobody runs the run; this process holds it until the caller closes it, and writes nothing
            RunView view = RunRecords.view(runId, RunRecords.events(run), false);
            Message next = RunRecords.isFinal(view.status()) ? Message.of("a finished run cannot be paused")
                    : Message.of("it has stopped already; go on with traceworks resume ", Message.quoted(runId));
            throw new RunRefusedException(Message.of("Run ", Message.quoted(runId), " is not running (" + view.status()
                    + "): ", next));
        }
        String requestId = ids.newRequestId();
        run.inbox().post(new InboxRequest(requestId, KIND, null, null, null, null, Map.of(), null, actor,
                clock.now(), agent));
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(Answering.POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            List<RecordedEvent> events = RunRecords.events(run);
            for (RecordedEvent event : events) {
                if (!requestId.equals(event.fields().path("request").asText(null))) {
                    continue;
                }
                if (RecordTypes.REQUEST_REFUSED.equals(event.type())) {
                    throw new RunRefusedException(Message.of("The pause of run ", Message.quoted(runId), " was refused: ",
                            Message.quoted(event.fields().path("reason").asText())));
                }
                if (RecordTypes.RUN_PAUSE_REQUESTED.equals(event.type())) {
                    return recorded(run, events);
                }
            }
        }
        throw new RequestQueuedException("The pause is requested, not yet recorded: request " + requestId
                + " waits in the inbox of run " + runId + ", whose process has not recorded it within "
                + wait.toSeconds() + " s; see what became of it with traceworks show " + runId);
    }

    /** The run as it is now that its process has recorded the pause: mostly still at the step in flight. */
    private static RunResult recorded(StoredRun run, List<RecordedEvent> events) {
        boolean alive = run.writerAlive();
        RunView view = RunRecords.view(run.runId(), alive ? events : RunRecords.events(run), alive);
        boolean goesOn = !RunRecords.isFinal(view.status()) && !view.paused();
        return new RunResult(run.runId(), run.location(), view.status(), RunRecords.gateOf(view),
                goesOn ? "pause requested; the run parks after the step or the loop item in flight" : null);
    }
}
