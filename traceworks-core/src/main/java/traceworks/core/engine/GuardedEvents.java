package traceworks.core.engine;

import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvents;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The run's event sink as the engine and the block executors see it: a failing write is turned into an
 * {@link EventLogException} and remembered, so the engine stops the run even when a block executor caught the
 * exception and went on.
 */
final class GuardedEvents implements RunEvents {

    private final RunEvents sink;
    private final AtomicReference<EventLogException> failure = new AtomicReference<>();

    GuardedEvents(RunEvents sink) {
        this.sink = sink;
    }

    @Override
    public void emit(RunEvent event) {
        try {
            sink.emit(event);
        } catch (EventLogException e) {
            // A child run's events go through the parent's guarded sink: the parent recorded the failure already
            failure.compareAndSet(null, e);
            throw e;
        } catch (RuntimeException e) {
            EventLogException failed = new EventLogException(e);
            failure.compareAndSet(null, failed);
            throw failed;
        }
    }

    /** The first write that failed during the run; {@code null} while every write succeeded. */
    EventLogException failure() {
        return failure.get();
    }

    /**
     * Registers a record write that failed outside this sink (a child run's) and throws it, so the block loop
     * stops even where a block catches it. The first failure of the run stays the one it ends with.
     */
    void fail(EventLogException failed) {
        failure.compareAndSet(null, failed);
        throw failed;
    }

    /** Throws the first failed write again, so the block loop stops; does nothing while every write succeeded. */
    void throwIfFailed() {
        EventLogException failed = failure.get();
        if (failed != null) {
            throw failed;
        }
    }
}
