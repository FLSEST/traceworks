package traceworks.core.app;

import traceworks.core.engine.RunOutcome;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.Ids;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The runs this process executes now, started or resumed, so that Ctrl-C reaches each of them ({@link #pauseAll},
 * {@link #cancelAll}), and the end of the process too ({@link #interruptAll}).
 */
final class ActiveRuns {

    private final String actor;
    private final Ids ids;
    private final Set<ActiveRun> active = ConcurrentHashMap.newKeySet();

    /** @param actor who cancels the runs on Ctrl-C: the one who uses Traceworks here */
    ActiveRuns(String actor, Ids ids) {
        this.actor = actor;
        this.ids = ids;
    }

    /**
     * Runs the engine through the run's session, and counts the run among the active ones until the engine returns.
     *
     * @param runtime the runtime the run's containers run on; {@code null} for none
     */
    RunOutcome execute(RunSession session, String runId, ContainerRuntime runtime, Supplier<RunOutcome> engineRun) {
        ActiveRun run = new ActiveRun(session, runId, runtime);
        active.add(run);
        try {
            return session.execute(engineRun);
        } finally {
            active.remove(run);
            if (runtime != null) {
                runtime.runEnded(runId);
            }
        }
    }

    /**
     * Asks every active run to pause, as a request from this process's actor: each finishes the step or the loop item
     * in flight and parks.
     *
     * @return how many runs this process executes now
     */
    int pauseAll() {
        int runs = 0;
        for (ActiveRun run : active) {
            run.session().requestPause(actor, ids.newRequestId());
            runs++;
        }
        return runs;
    }

    /**
     * Asks every active run to stop, as a cancel from this process's actor, and then removes what is left of its
     * containers. It cannot refuse: what the runtime cannot confirm gone counts as a container that may still run, so
     * the run does not list its scratch directory while it may.
     */
    void cancelAll() {
        for (ActiveRun run : active) {
            run.session().requestCancel(actor, ids.newRequestId());
            if (run.runtime() != null) {
                run.runtime().removeLeftovers(run.runId());
            }
        }
    }

    /**
     * Leaves every active run interrupted, for a process that is told to end: each run's record stops, its step in
     * flight is ended, and what is left of its containers is removed. No cancel is recorded, so {@code resume} goes on
     * with the run. Like {@link #cancelAll}, it cannot refuse.
     */
    void interruptAll() {
        for (ActiveRun run : active) {
            run.session().interrupt();
            if (run.runtime() != null) {
                run.runtime().removeLeftovers(run.runId());
            }
        }
    }

    /**
     * Leaves interrupted, as {@link #interruptAll} does, every active run that waits for an answer in this process;
     * the others go on.
     *
     * @return the runs it interrupted
     */
    List<String> interruptWaiting() {
        List<String> interrupted = new ArrayList<>();
        for (ActiveRun run : active) {
            if (run.session().waitsAtAQuestion()) {
                run.session().interrupt();
                if (run.runtime() != null) {
                    run.runtime().removeLeftovers(run.runId());
                }
                interrupted.add(run.runId());
            }
        }
        return interrupted;
    }

    /** A run this process executes, and the runtime its containers run on ({@code null} for none). */
    private record ActiveRun(RunSession session, String runId, ContainerRuntime runtime) {}
}
