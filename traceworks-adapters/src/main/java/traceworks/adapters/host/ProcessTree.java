package traceworks.adapters.host;

import traceworks.core.util.ProcessTracking;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ends a process together with everything it started — the one way the host command runner and the
 * container client end a process on a timeout, an interrupt or a cancel. Killing only the process
 * itself leaves its children running: an AI CLI's shell tool, a test chain it launched, a
 * {@code docker run} client. They get reparented to init and keep working for a run that no longer
 * exists. Not every process goes through here: a git call of the package adapter that times out is
 * ended with {@link Process#destroyForcibly()} alone.
 *
 * <p>The tree is collected <em>before</em> anything is signalled, because once a parent is gone its
 * children hang under init and {@link Process#descendants()} no longer reaches them. Everyone first
 * gets SIGTERM, so cleanup traps run and a {@code docker run} client forwards the signal to its
 * container; whatever is still alive after {@link #GRACE} gets SIGKILL.
 *
 * <p>Not airtight: a child whose parent had already exited (e.g. after {@code nohup … &} or a daemon's
 * double fork) is out of reach of the parent chain. Closing that gap needs a process group or cgroup
 * and is not done here.
 */
public final class ProcessTree {

    /** How long the tree may take to end on SIGTERM before it is killed. */
    public static final Duration GRACE = Duration.ofSeconds(2);

    private static final long POLL_MILLIS = 50;

    private ProcessTree() {}

    /**
     * Signals {@code process} and all its descendants to end and kills the survivors after
     * {@link #GRACE}. Does not block: the escalation runs on a virtual thread, and the returned future
     * completes once the process is gone. Callers that must not return before that wait on the
     * process (or the future) themselves.
     */
    public static CompletableFuture<Void> terminate(Process process) {
        if (process == null || !process.isAlive()) return CompletableFuture.completedFuture(null);
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        // Through the handle: Process.destroy() also closes the pipes, so a process writing while it
        // cleans up would die of SIGPIPE before its trap finishes
        process.toHandle().destroy();

        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.ofVirtual().name("process-tree-kill").start(() -> {
            try {
                killSurvivors(process, descendants);
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /**
     * The handle a runner reports {@code process} with to {@link ProcessTracking}: a cancel of the run
     * {@linkplain #terminate terminates} the process and everything it started. The runner reports the process's end
     * with this same handle.
     */
    public static ProcessTracking.StopHandle stopHandle(Process process) {
        return () -> terminate(process);
    }

    private static void killSurvivors(Process process, List<ProcessHandle> descendants) {
        long deadline = System.nanoTime() + GRACE.toNanos();
        while (System.nanoTime() < deadline && (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Children started during the grace period count too, as long as their parent is still reachable
        Set<ProcessHandle> survivors = new LinkedHashSet<>();
        if (process.isAlive()) process.descendants().forEach(survivors::add);
        for (ProcessHandle parent : descendants) {
            if (parent.isAlive()) {
                survivors.add(parent);
                parent.descendants().forEach(survivors::add);
            }
        }
        survivors.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
