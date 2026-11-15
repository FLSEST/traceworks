package traceworks.core.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Checks that a stopped command took the processes it started in the background with it — the
 * state after a timeout or cancel, which return values and run status never show.
 *
 * <p>The command under test writes the pid of its background child into a file, e.g.
 * {@code sleep 300 & echo $! > child.pid; sleep 300}; {@link #script} builds that line.
 */
public final class BackgroundChild {

    private BackgroundChild() {
    }

    /** A shell line that starts {@code sleep 300} in the background, records its pid and then blocks. */
    public static String script(Path pidFile) {
        return "sleep 300 & echo $! > '" + pidFile + "'; sleep 300";
    }

    /** Waits until the command has written the pid of its background child. */
    public static long awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                String pid = Files.readString(pidFile).trim();
                if (!pid.isEmpty()) return Long.parseLong(pid);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no pid written to " + pidFile);
    }

    /** True once the process {@code pid} is gone, polling for up to {@code within}. Kills it if not, so no test leaks it. */
    public static boolean endsWithin(long pid, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true)) return true;
            Thread.sleep(20);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        return false;
    }
}
