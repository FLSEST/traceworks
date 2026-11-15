package traceworks.core.testing;

import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvents;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * An in-memory event sink for tests: keeps every event in the order it arrived. Safe to read from another thread
 * while the run emits, and {@link #await} blocks until an event arrives, so a test can answer a waiting run from
 * outside. A test that must react to an event while the run emits it overrides {@link #emit} and calls
 * {@code super.emit}.
 */
public class RecordingRunEvents implements RunEvents {

    private final List<RunEvent> events = new ArrayList<>();

    @Override
    public synchronized void emit(RunEvent event) {
        events.add(event);
        notifyAll();
    }

    /** Every event so far, in order; a child run's events stay wrapped in {@link ChildRunEvent}s. */
    public synchronized List<RunEvent> all() {
        return List.copyOf(events);
    }

    /** The top-level events of one type, in order. */
    public <T extends RunEvent> List<T> of(Class<T> type) {
        return all().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** The last event so far. */
    public synchronized RunEvent last() {
        return events.getLast();
    }

    /** Every event so far with the events of child runs unwrapped in place, grandchildren included. */
    public List<RunEvent> flattened() {
        List<RunEvent> flat = new ArrayList<>();
        for (RunEvent event : all()) {
            RunEvent inner = event;
            while (inner instanceof ChildRunEvent child) {
                inner = child.event();
            }
            flat.add(inner);
        }
        return flat;
    }

    /** The events of one child run, unwrapped one level, in order. */
    public List<RunEvent> ofChild(String runId) {
        return all().stream()
                .filter(e -> e instanceof ChildRunEvent child && child.runId().equals(runId))
                .map(e -> ((ChildRunEvent) e).event())
                .toList();
    }

    /**
     * Waits until an event of {@code type} has arrived, in this run or in one of its child runs, and returns the first
     * one.
     *
     * @throws AssertionError if none arrives within {@code timeout}
     */
    public <T extends RunEvent> T await(Class<T> type, Duration timeout) throws InterruptedException {
        return type.cast(await(type::isInstance, timeout));
    }

    /**
     * Waits until an event that matches {@code condition} has arrived, in this run or in one of its child runs, and
     * returns the first one.
     *
     * @throws AssertionError if none arrives within {@code timeout}
     */
    public synchronized RunEvent await(Predicate<RunEvent> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (RunEvent event : flattened()) {
                if (condition.test(event)) {
                    return event;
                }
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("no matching event within " + timeout + "; recorded: " + events);
            }
            wait(Math.max(1, left / 1_000_000));
        }
    }

    public synchronized void clear() {
        events.clear();
    }
}
