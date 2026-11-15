package traceworks.core.testing;

import traceworks.core.util.ProcessTracking;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tracker that records the stop handles a runner reports, in order, and calls none of them. A tracker tells handles
 * apart by identity, so a test compares what {@link #started()} and {@link #finished()} got with {@code isSameAs}.
 */
public final class RecordingTracker implements ProcessTracking.Tracker {

    private final List<ProcessTracking.StopHandle> started = new CopyOnWriteArrayList<>();
    private final List<ProcessTracking.StopHandle> finished = new CopyOnWriteArrayList<>();

    @Override
    public void started(ProcessTracking.StopHandle stop) {
        started.add(stop);
    }

    @Override
    public void finished(ProcessTracking.StopHandle stop) {
        finished.add(stop);
    }

    /** The handles reported as started, in order. */
    public List<ProcessTracking.StopHandle> started() {
        return List.copyOf(started);
    }

    /** The handles reported as finished, in order. */
    public List<ProcessTracking.StopHandle> finished() {
        return List.copyOf(finished);
    }
}
