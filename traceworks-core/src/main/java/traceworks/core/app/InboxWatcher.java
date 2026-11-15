package traceworks.core.app;

import traceworks.core.port.Inbox;
import traceworks.core.port.InboxRequest;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Polls the inbox of a running run every {@link #INTERVAL} on a daemon thread of its own and hands each request to
 * the session, oldest first. A request whose handling fails goes to the failure handler (the session records it as
 * refused), and the requests taken with it are still handled. Requests that arrive after {@link #close} stay in the
 * inbox.
 */
final class InboxWatcher implements AutoCloseable {

    static final Duration INTERVAL = Duration.ofMillis(200);

    private final Inbox inbox;
    private final Consumer<InboxRequest> handler;
    private final BiConsumer<InboxRequest, RuntimeException> onFailure;
    private final Thread thread;
    private volatile boolean stopped;

    InboxWatcher(Inbox inbox, Consumer<InboxRequest> handler, BiConsumer<InboxRequest, RuntimeException> onFailure,
                 String name) {
        this.inbox = inbox;
        this.handler = handler;
        this.onFailure = onFailure;
        this.thread = Thread.ofPlatform().daemon().name(name).unstarted(this::watch);
    }

    void start() {
        thread.start();
    }

    private void watch() {
        while (!stopped) {
            List<InboxRequest> taken;
            try {
                taken = inbox.take();
            } catch (RuntimeException e) {
                taken = List.of();   // an inbox that cannot be read now may be readable at the next poll
            }
            // A request taken is handled even when the watcher stops meanwhile: it left the inbox already
            for (InboxRequest request : taken) {
                handleOne(request);
            }
            try {
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handleOne(InboxRequest request) {
        try {
            handler.accept(request);
        } catch (RuntimeException e) {
            try {
                onFailure.accept(request, e);
            } catch (RuntimeException unrecorded) {
                // The log takes no writes any more; the run's own writes fail on it too and end the run
            }
        }
    }

    /** Stops polling and waits until the request being handled, if any, is done. */
    @Override
    public void close() {
        stopped = true;
        if (thread.isAlive()) {
            thread.interrupt();
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
}
