package traceworks.core.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs a piece of a test beside the test, on a thread of its own, and says whether it runs.
 *
 * <p>A test that needs two things at once must not borrow a thread from a pool it shares with everything else.
 * {@code CompletableFuture.supplyAsync(work)} takes one from the common pool, whose size is the machine's number
 * of cores less one: on a machine with two cores that is one thread. A piece that blocks there holds it, and the
 * next piece waits in a queue and never starts; whether the pool then adds a thread depends on the JDK (21 does,
 * 25 does not). Such a test passes on a developer's machine and fails on a small one with "waited 30 s", which
 * says nothing about why.
 *
 * <p>{@link #start} gives each piece a thread with the name the test chose and returns only once that thread
 * runs, so two pieces started one after the other are two running threads, on every machine and every JDK. A
 * thread that does not start fails the test there, by name. {@link #threads} names every piece still running and
 * what its thread does, for the message of a test that waits in vain.
 */
public final class OwnThread {

    /** How long a new thread may take to run its first line; far more than any machine needs. */
    private static final long START_SECONDS = 30;

    private static final List<Thread> STARTED = new ArrayList<>();

    private OwnThread() {
    }

    /**
     * Starts {@code work} on a new thread named {@code name} and returns when that thread runs. The future ends
     * with what {@code work} returns, or with what it throws.
     */
    public static <T> CompletableFuture<T> start(String name, Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CountDownLatch runs = new CountDownLatch(1);
        Thread thread = Thread.ofPlatform().daemon().name("test: " + name).unstarted(() -> {
            runs.countDown();
            try {
                result.complete(work.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        synchronized (STARTED) {
            STARTED.removeIf(started -> !started.isAlive());
            STARTED.add(thread);
        }
        thread.start();
        try {
            if (!runs.await(START_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("The thread \"" + thread.getName() + "\" did not start within "
                        + START_SECONDS + " s; " + threads());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while the thread \"" + thread.getName() + "\" started", e);
        }
        return result;
    }

    /**
     * The pieces that still run, each with the state of its thread, as in
     * {@code own threads: "test: first run" TIMED_WAITING, "test: watch" RUNNABLE}; {@code own threads: none} when
     * every piece has ended.
     */
    public static String threads() {
        List<String> alive = new ArrayList<>();
        synchronized (STARTED) {
            for (Thread thread : STARTED) {
                if (thread.isAlive()) {
                    alive.add("\"" + thread.getName() + "\" " + thread.getState());
                }
            }
        }
        return "own threads: " + (alive.isEmpty() ? "none" : String.join(", ", alive));
    }
}
