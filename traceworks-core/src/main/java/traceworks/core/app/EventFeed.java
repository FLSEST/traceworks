package traceworks.core.app;

import traceworks.core.record.RecordedEvent;

import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Hands the events of a run to the caller's listener, in order, on a daemon thread of its own: a listener that blocks
 * (a terminal that does not take output) never holds up the writer, and one that throws misses that event only.
 * {@link #close} delivers what is queued and then stops the thread.
 */
final class EventFeed implements AutoCloseable {

    private final Consumer<RecordedEvent> listener;
    /** The events to deliver; empty marks the end. */
    private final LinkedBlockingQueue<Optional<RecordedEvent>> queue = new LinkedBlockingQueue<>();
    private final Thread thread;

    EventFeed(Consumer<RecordedEvent> listener, String name) {
        this.listener = listener;
        this.thread = Thread.ofPlatform().daemon().name(name).start(this::feed);
    }

    void offer(RecordedEvent event) {
        queue.add(Optional.of(event));
    }

    private void feed() {
        while (true) {
            Optional<RecordedEvent> event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (event.isEmpty()) {
                return;
            }
            try {
                listener.accept(event.get());
            } catch (RuntimeException e) {
                // The listener only watches the run: its failure costs it this event, nothing else
            }
        }
    }

    /** Delivers every event queued so far, then stops. */
    @Override
    public void close() {
        queue.add(Optional.empty());
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
