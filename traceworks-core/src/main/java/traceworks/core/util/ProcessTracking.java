package traceworks.core.util;

import java.util.function.Supplier;

/**
 * Binds a tracker for what is started on a step's thread, so cancelling a run can end a running command or AI
 * CLI instead of waiting for its timeout. The process runners (the adapters behind
 * the {@code CommandRunner} port and the AI providers) report start and end here, without
 * every request carrying the execution context. A thread outside a step gets {@link Tracker#NONE}.
 *
 * <p>A runner reports a {@link StopHandle}, not its process: the core never holds a process, it only calls what the
 * adapter handed it. How a process ends, and everything it started, is the adapter's business.
 *
 * <p>Deliberately a plain, non-inheritable {@link ThreadLocal}: only the step's own thread reports to the run. A child
 * run (sub-workflow) runs on the same thread and binds its own tracker for its steps; {@link #bind} restores the
 * parent's afterwards.
 */
public final class ProcessTracking {

    /**
     * What a cancel calls to end what a runner started: its process, with everything that process started. It returns
     * without waiting for the end, since a cancel calls the handles of a run one after the other. A tracker tells
     * handles apart by identity, so a runner reports {@link Tracker#finished} with the very handle it reported
     * {@link Tracker#started} with.
     */
    @FunctionalInterface
    public interface StopHandle {
        void stop();
    }

    public interface Tracker {
        Tracker NONE = new Tracker() {
            @Override
            public void started(StopHandle stop) {
                // not inside a workflow step
            }

            @Override
            public void finished(StopHandle stop) {
                // not inside a workflow step
            }
        };

        /** The runner started something: until {@link #finished}, a cancel of the run calls {@code stop}. */
        void started(StopHandle stop);

        /** What {@code stop} ends has ended, by itself or not: a cancel no longer calls it. */
        void finished(StopHandle stop);

        /**
         * Whether the run was cancelled. The cancel itself calls the registered stop handles; a runner asks this to
         * tell a process the cancel ended from one that ended by itself, and to start nothing more. Outside a step,
         * never.
         */
        default boolean cancelled() {
            return false;
        }
    }

    private static final ThreadLocal<Tracker> CURRENT = new ThreadLocal<>();

    private ProcessTracking() {}

    public static Tracker current() {
        Tracker tracker = CURRENT.get();
        return tracker != null ? tracker : Tracker.NONE;
    }

    /** Runs {@code body} with {@code tracker} bound and restores the previous binding afterwards. */
    public static <T> T bind(Tracker tracker, Supplier<T> body) {
        Tracker previous = CURRENT.get();
        CURRENT.set(tracker);
        try {
            return body.get();
        } finally {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }
}
