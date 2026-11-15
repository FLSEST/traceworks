package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.port.Clock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Enforces the safety limits of an agentic block: total and iteration timeouts, repeated-action detection, and
 * pause/cancel handling. The timeouts read the run's clock.
 */
class AgenticSafetyGuard {

    private final Clock clock;
    private final Instant blockStart;
    private final int totalTimeoutSeconds;
    private final int iterationTimeoutSeconds;
    private final int maxRepeatedActions;
    private final List<String> recentActions = new ArrayList<>();

    /** A guard on the system clock. */
    AgenticSafetyGuard(int totalTimeoutSeconds, int iterationTimeoutSeconds, int maxRepeatedActions) {
        this(Instant::now, totalTimeoutSeconds, iterationTimeoutSeconds, maxRepeatedActions);
    }

    AgenticSafetyGuard(Clock clock, int totalTimeoutSeconds, int iterationTimeoutSeconds, int maxRepeatedActions) {
        this.clock = clock;
        this.blockStart = clock.now();
        this.totalTimeoutSeconds = totalTimeoutSeconds;
        this.iterationTimeoutSeconds = iterationTimeoutSeconds;
        this.maxRepeatedActions = maxRepeatedActions;
    }

    /**
     * Checks if the total block timeout has been exceeded.
     *
     * @return the stop reason if timed out, null otherwise
     */
    String checkTotalTimeout() {
        long elapsedSeconds = elapsedSeconds();
        if (elapsedSeconds > totalTimeoutSeconds) {
            return "TOTAL_TIMEOUT";
        }
        return null;
    }

    /** Returns elapsed seconds since block start. */
    long elapsedSeconds() {
        return (clock.now().toEpochMilli() - blockStart.toEpochMilli()) / 1000;
    }

    /**
     * Checks if a single iteration exceeded its timeout.
     *
     * @return the stop reason if timed out, null otherwise
     */
    String checkIterationTimeout(Instant iterStart) {
        long iterMs = clock.now().toEpochMilli() - iterStart.toEpochMilli();
        if (iterMs > (long) iterationTimeoutSeconds * 1000) {
            return "ITERATION_TIMEOUT";
        }
        return null;
    }

    /**
     * Tracks an action and checks if the same action has been repeated too many times.
     *
     * @return the stop reason if repeated too many times, null otherwise
     */
    String trackAndCheckRepeatedAction(String action) {
        String actionNormalized = action.strip();
        recentActions.add(actionNormalized);

        if (recentActions.size() >= maxRepeatedActions) {
            List<String> lastN = recentActions.subList(
                    recentActions.size() - maxRepeatedActions, recentActions.size());
            if (lastN.stream().allMatch(a -> a.equals(lastN.getFirst()))) {
                return "REPEATED_ACTIONS";
            }
        }
        return null;
    }

    /**
     * Waits while the run is paused.
     *
     * @return true if execution should continue, false if cancelled during pause
     */
    boolean waitIfPaused(ExecutionContext context) {
        while (context.isPaused() && !context.shouldStop()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !context.shouldStop();
    }
}
