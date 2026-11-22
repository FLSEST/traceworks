package traceworks.cli.mcp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * How often a client may call the tools of one server: a bound on all calls, and a lower one on the calls that
 * change something. A model in a loop is the case: it shall not start runs, answer questions or decide findings
 * faster than a person can follow. A refused call counts for nothing and changes nothing.
 */
final class CallLimits {

    /** The most calls of any tool in a minute. */
    static final int CALLS_PER_MINUTE = 600;
    /** The most calls in a minute of the tools that change something. */
    static final int CHANGES_PER_MINUTE = 30;

    private static final long MINUTE_NANOS = 60_000_000_000L;

    private final LongSupplier nanos;
    private final Deque<Long> calls = new ArrayDeque<>();
    private final Deque<Long> changes = new ArrayDeque<>();

    /** @param nanos a clock that never goes back, such as {@link System#nanoTime} */
    CallLimits(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /**
     * Takes a call, or refuses it.
     *
     * @param change whether the tool changes something
     * @return {@code null} when the call is taken; else why it is not, and when to try again
     */
    synchronized String refusal(boolean change) {
        long now = nanos.getAsLong();
        forget(calls, now);
        forget(changes, now);
        if (calls.size() >= CALLS_PER_MINUTE) {
            return "this server takes at most " + CALLS_PER_MINUTE + " calls a minute; try again in "
                    + wait(calls, now) + " s";
        }
        if (change && changes.size() >= CHANGES_PER_MINUTE) {
            return "this server takes at most " + CHANGES_PER_MINUTE + " calls that change something a minute; try"
                    + " again in " + wait(changes, now) + " s";
        }
        calls.add(now);
        if (change) {
            changes.add(now);
        }
        return null;
    }

    private static void forget(Deque<Long> taken, long now) {
        while (!taken.isEmpty() && now - taken.peekFirst() >= MINUTE_NANOS) {
            taken.removeFirst();
        }
    }

    private static long wait(Deque<Long> taken, long now) {
        return Math.max(1, (taken.peekFirst() + MINUTE_NANOS - now + 999_999_999L) / 1_000_000_000L);
    }
}
