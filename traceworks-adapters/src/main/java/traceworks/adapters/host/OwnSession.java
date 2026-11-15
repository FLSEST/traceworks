package traceworks.adapters.host;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts the process of a step in a session of its own, so that Ctrl-C in the terminal does not reach it. A terminal
 * sends the interrupt to every process of its foreground process group, which a child of the JVM belongs to: the
 * client of {@code docker run} would hand it to its container, and a host command would die of it. Traceworks takes
 * the first Ctrl-C as a pause that finishes the step in flight, so the step must not get the signal; Traceworks ends a
 * step itself, through {@link ProcessTree}, when a cancel or a timeout says so.
 *
 * <p>The way there is the program {@code setsid} (util-linux, BusyBox), found once on the {@code PATH}: it makes the
 * calling process the leader of a new session and then becomes the command, with the same process id, so its exit
 * code, its streams and its descendants are the command's. Where there is none (macOS), a command starts as before,
 * {@link #available} says so, and the command line then keeps Ctrl-C as a cancel.
 *
 * <p><b>The instant before.</b> A new process is born in the JVM's process group and leaves it only when
 * {@code setsid} has run, a few milliseconds later. A Ctrl-C in that instant kills it before the command has
 * started. The JVM then either cannot start the process at all (the helper it starts every process through was
 * killed: an {@link IOException}), or the process ends with 130 (128 + SIGINT) and has done nothing. Whoever takes
 * the Ctrl-C says when it came ({@link #terminalInterrupt}); whoever starts a process does it through
 * {@link #start}, which starts it again in the first case, and asks {@link #killedAtStart} for the second and
 * starts it again too. Measured on Linux with a JDK 21, under a stream of SIGINTs: of 3000 starts, 424 failed in
 * the helper and 72 ended with 130.
 */
public final class OwnSession {

    private static final Optional<String> SETSID = find(System.getenv("PATH"));

    /** The exit code of a process a SIGINT ended: 128 + 2. */
    private static final int KILLED_BY_SIGINT = 130;
    /** How long after its start a process can still be in the JVM's process group; far more than it ever takes. */
    private static final long START_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(2);
    /** A Ctrl-C this long before a start still counts: the start and the signal are told apart by two clocks. */
    private static final long SLACK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    /** How long {@link #killedAtStart} waits for the JVM to hand its own SIGINT to the command line. */
    private static final long HANDLER_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    /** When the terminal's last Ctrl-C came ({@link System#nanoTime}); {@code null} when none came. */
    private static final AtomicReference<Long> INTERRUPTED_AT = new AtomicReference<>();

    private OwnSession() {
    }

    /** Whether a step's process can be kept from the terminal's Ctrl-C on this machine. */
    public static boolean available() {
        return SETSID.isPresent();
    }

    /** Notes that the terminal's Ctrl-C came now; called by whoever handles SIGINT for the process. */
    public static void terminalInterrupt() {
        INTERRUPTED_AT.set(System.nanoTime());
    }

    /** Starts a process; it throws what {@link ProcessBuilder#start()} throws. */
    @FunctionalInterface
    public interface Starter {
        Process start() throws IOException;
    }

    /**
     * Starts the process, and once more when the start failed while the terminal's Ctrl-C came: the JVM starts every
     * process through a helper of its own, which is in the JVM's process group and dies of the Ctrl-C, and the JDK
     * reports that as a failed start. A start that fails for another reason in that moment fails again, the same way.
     *
     * @throws IOException what the start threw, the second one's when it was tried again
     */
    public static Process start(Starter starter) throws IOException {
        long begun = System.nanoTime();
        try {
            return starter.start();
        } catch (IOException e) {
            if (!interruptCameSince(begun)) {
                throw e;
            }
            return starter.start();
        }
    }

    /**
     * Whether a process started behind {@code setsid} at {@code startedNanos} ({@link System#nanoTime}), which has
     * just ended with {@code exitCode}, was killed by the terminal's Ctrl-C in the instant before it reached its own
     * session: it ended with 130 within two seconds of its start, and a Ctrl-C came in that time. Its command never
     * started, so the caller starts it again. A command that ends with 130 by itself in the very second of a Ctrl-C
     * is taken for the same; it has run for a moment only.
     */
    public static boolean killedAtStart(int exitCode, long startedNanos) {
        return exitCode == KILLED_BY_SIGINT && interruptCameSince(startedNanos);
    }

    /**
     * Whether the terminal's Ctrl-C came since {@code startedNanos}, which is at most two seconds ago.
     *
     * <p>The JVM hands its own SIGINT to a thread, which may come after the child's end is seen: the answer waits
     * up to a quarter of a second for it. Only a start that failed, or a process that ended with 130 right after its
     * start, pays that wait.
     */
    private static boolean interruptCameSince(long startedNanos) {
        if (SETSID.isEmpty()) {
            return false;   // no step is kept from Ctrl-C here, and nobody takes Ctrl-C as a pause
        }
        long now = System.nanoTime();
        if (now - startedNanos > START_WINDOW_NANOS) {
            return false;
        }
        long deadline = now + HANDLER_WAIT_NANOS;
        while (true) {
            Long at = INTERRUPTED_AT.get();
            if (at != null && at - startedNanos >= -SLACK_NANOS) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** {@code command} as it is started: behind {@code setsid} where there is one, else unchanged. */
    public static List<String> command(List<String> command) {
        // A program that cannot start must fail to start, as it does without setsid, which would start in its
        // place and report the failure as an exit code of its own
        if (SETSID.isEmpty() || command.isEmpty() || !canStart(command.getFirst(), System.getenv("PATH"))) {
            return command;
        }
        List<String> own = new ArrayList<>(command.size() + 1);
        own.add(SETSID.get());
        own.addAll(command);
        return own;
    }

    /** The {@code setsid} program in the directories of {@code path}; empty when none holds one that can run. */
    static Optional<String> find(String path) {
        return onPath("setsid", path);
    }

    /**
     * Whether {@code program} names a file that can run: as a path when it holds a {@code /}, else in a directory of
     * {@code path}, which is where the JVM looks a program up.
     */
    static boolean canStart(String program, String path) {
        if (program.contains(File.separator)) {
            File file = new File(program);
            return file.isFile() && file.canExecute();
        }
        return onPath(program, path).isPresent();
    }

    private static Optional<String> onPath(String program, String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isEmpty()) {
                continue;
            }
            File candidate = new File(directory, program);
            if (candidate.isFile() && candidate.canExecute()) {
                return Optional.of(candidate.getAbsolutePath());
            }
        }
        return Optional.empty();
    }
}
