package traceworks.adapters.container;

import traceworks.adapters.host.BashScript;
import traceworks.adapters.host.BoundedOutput;
import traceworks.adapters.host.ChildProcessEnv;
import traceworks.adapters.host.OwnSession;
import traceworks.adapters.host.ProcessTree;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.util.ProcessTracking;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The client process of one container: it owns the named
 * container, so a timeout or a cancel removes the actual work, not only the client. The client registers with the run
 * whose step runs on this thread ({@link ProcessTracking}, through its {@linkplain ProcessTree#stopHandle stop
 * handle}): a cancel ends it even where the caller has no stop flag to pass (the inner shell step of a wrapper block),
 * and the container goes with it.
 *
 * <ul>
 *   <li><b>Streams:</b> stdout and stderr are kept apart, each read on a thread of its own, so a full pipe never
 *       blocks the client. Stdin gets the given text, or is closed at once ({@link BashScript#supply}). Of each,
 *       at most the output limit is kept ({@link BoundedOutput}): the operator's {@code output_limit_mib},
 *       {@link CommandRunner#OUTPUT_LIMIT_BYTES} by default.</li>
 *   <li><b>Environment:</b> exactly the given one; nothing of the JVM's own, and {@link ChildProcessEnv} scrubs
 *       the run variables of a workflow service as a second line.</li>
 *   <li><b>Timeout:</b> exit code {@value #TIMEOUT_EXIT_CODE}, {@linkplain CommandResult#timedOut() timed out}, with
 *       the output so far; the shell block adds the "timed out" line.</li>
 *   <li><b>Output beyond the limit:</b> a stream that goes beyond it ends the client the way a timeout does, and the
 *       container is removed. The result has exit code {@value CommandRunner#OUTPUT_LIMIT_EXIT_CODE}, the first
 *       bytes of that stream up to the limit, its name in {@link CommandResult#truncatedStreams()} and the limit in
 *       {@link CommandResult#outputLimitBytes()}; what runs the command says why
 *       ({@link CommandResult#truncationNote()}). A timeout or a cancel that came first keeps its exit code, and
 *       a call that starts no container is stopped the same way.</li>
 *   <li><b>Cancel:</b> exit code {@value #CANCELLED_EXIT_CODE}; a cancel before the start starts nothing
 *       ({@code Cancelled before container start}). An interrupted wait: exit code {@value #CANCELLED_EXIT_CODE},
 *       {@code Command interrupted}, and the interrupt flag stays set.</li>
 *   <li><b>A client that cannot start:</b> exit code 1, {@code Error: <reason>}, marked as not run
 *       ({@link CommandResult#notRunReason()}), as a cancel before the start is. A container that cannot start: the
 *       client's own exit code (125 for docker), not marked, since a container's command may exit 125 by itself.</li>
 *   <li><b>Ending it:</b> SIGTERM first, which the attached client hands to the container, then SIGKILL after
 *       {@link ProcessTree#GRACE} ({@link ProcessTree#terminate}). Afterwards, however the client ended, the remove
 *       command ({@code rm -f <name>}) runs, bounded at {@value #REMOVE_WAIT_SECONDS} seconds. A removal that fails
 *       or does not end in time is reported: the container may still run, and still write into its mounts.</li>
 * </ul>
 */
final class ContainerProcess {

    /** The exit code of a command that ran out of time. */
    static final int TIMEOUT_EXIT_CODE = 124;
    /** The exit code of a cancelled or interrupted command. */
    static final int CANCELLED_EXIT_CODE = 130;
    /** How long a run waits for the removal of its container after the client ended. */
    static final int REMOVE_WAIT_SECONDS = 5;

    private static final long POLL_MILLIS = 200;

    private ContainerProcess() {
    }

    /**
     * Runs the client and waits for it, then removes the container, keeping the default
     * {@link CommandRunner#OUTPUT_LIMIT_BYTES} of each stream.
     *
     * @param command        the client's command line
     * @param environment    the client's whole environment
     * @param stdin          what the client gets on stdin; {@code null} closes it at once
     * @param timeoutSeconds after this many seconds the client is ended
     * @param removeCommand  the command that removes the container afterwards
     */
    static CommandResult execute(List<String> command, Map<String, String> environment, String stdin,
                                 int timeoutSeconds, List<String> removeCommand) {
        return execute(command, environment, stdin, timeoutSeconds, removeCommand, () -> { },
                CommandRunner.OUTPUT_LIMIT_BYTES);
    }

    /**
     * Like {@link #execute(List, Map, String, int, List)}, keeping at most {@code outputLimit} bytes of each stream,
     * and tells {@code unconfirmed} when the remove command did not end with exit code 0 within
     * {@value #REMOVE_WAIT_SECONDS} seconds, so the container may still run.
     */
    static CommandResult execute(List<String> command, Map<String, String> environment, String stdin,
                                 int timeoutSeconds, List<String> removeCommand, Runnable unconfirmed,
                                 int outputLimit) {
        if (ProcessTracking.current().cancelled()) {
            return CommandResult.notRun(CANCELLED_EXIT_CODE, "Cancelled before container start", outputLimit);
        }
        return run(command, environment, stdin, timeoutSeconds, removeCommand, unconfirmed, outputLimit);
    }

    /**
     * A client call that starts no container (probe, inspect, list, remove, build, pull): the same process handling,
     * without the removal, keeping at most {@code outputLimit} bytes of each stream. A cancel ends it like any process
     * of the step; its exit code is then the client's.
     */
    static CommandResult call(List<String> command, Map<String, String> environment, int timeoutSeconds,
                              int outputLimit) {
        return run(command, environment, null, timeoutSeconds, null, () -> { }, outputLimit);
    }

    /**
     * Runs the client, and once more when the terminal's Ctrl-C killed it in the instant before it reached its own
     * session ({@link OwnSession#killedAtStart}): it had not started, and a Ctrl-C is a pause, which must not fail
     * the step in flight. A cancel or an interrupt of this thread is no such case.
     */
    private static CommandResult run(List<String> command, Map<String, String> environment, String stdin,
                                     int timeoutSeconds, List<String> removeCommand, Runnable unconfirmed,
                                     int outputLimit) {
        long started = System.nanoTime();
        CommandResult result = runOnce(command, environment, stdin, timeoutSeconds, removeCommand, unconfirmed,
                outputLimit);
        if (!ProcessTracking.current().cancelled() && !Thread.currentThread().isInterrupted()
                && OwnSession.killedAtStart(result.exitCode(), started)) {
            return runOnce(command, environment, stdin, timeoutSeconds, removeCommand, unconfirmed, outputLimit);
        }
        return result;
    }

    private static CommandResult runOnce(List<String> command, Map<String, String> environment, String stdin,
                                         int timeoutSeconds, List<String> removeCommand, Runnable unconfirmed,
                                         int outputLimit) {
        long started = System.nanoTime();
        ProcessTracking.Tracker tracker = ProcessTracking.current();
        boolean container = removeCommand != null;
        BoundedOutput stdout = new BoundedOutput(outputLimit);
        BoundedOutput stderr = new BoundedOutput(outputLimit);
        Process process = null;
        ProcessTracking.StopHandle stop = null;
        try {
            // In a session of its own: Ctrl-C in the terminal must not reach the client, which would hand it on
            ProcessBuilder builder = new ProcessBuilder(OwnSession.command(command));
            builder.environment().clear();
            // A name with '=' or a NUL anywhere is refused here, by the JDK: a start failure like any other
            builder.environment().putAll(environment);
            ChildProcessEnv.scrub(builder);
            process = OwnSession.start(builder::start);
            stop = ProcessTree.stopHandle(process);
            tracker.started(stop);
            BashScript.supply(process, stdin);
            Thread out = stdout.drain(process.getInputStream(), "container-client-output");
            Thread err = stderr.drain(process.getErrorStream(), "container-client-output");

            boolean timedOut = false;
            long deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (container && tracker.cancelled()) break;
                // An output beyond the limit ends the client the way a timeout does
                if (stdout.exceeded() || stderr.exceeded()) break;
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    break;
                }
            }
            if (process.isAlive()) {
                ProcessTree.terminate(process);
                process.waitFor(ProcessTree.GRACE.toSeconds() + 2, TimeUnit.SECONDS);
            }
            out.join(2000);
            err.join(2000);
            if (timedOut) {
                return result(TIMEOUT_EXIT_CODE, stdout, stderr, "", started, true);
            }
            if (container && tracker.cancelled()) {
                // the cancel ended the client, or this loop did; either way its own exit code says nothing
                return result(CANCELLED_EXIT_CODE, stdout, stderr, "", started, false);
            }
            if (stdout.exceeded() || stderr.exceeded()) {
                // also when the client ended by itself before the loop saw it: the output is not whole
                return result(CommandRunner.OUTPUT_LIMIT_EXIT_CODE, stdout, stderr, "", started, false);
            }
            return result(process.exitValue(), stdout, stderr, "", started, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(CANCELLED_EXIT_CODE, stdout, stderr, "Command interrupted", started, false);
        } catch (IOException | IllegalArgumentException e) {
            CommandResult failed = result(1, stdout, stderr, "Error: " + e.getMessage(), started, false);
            // Thrown before the client started (the JDK refused its environment, or it could not start), or after
            return process == null ? failed.asNotRun("the container client could not start: " + e.getMessage())
                    : failed;
        } finally {
            if (process != null) {
                tracker.finished(stop);
                ProcessTree.terminate(process);
                if (container && !remove(removeCommand, environment)) {
                    unconfirmed.run();
                }
            }
        }
    }

    /**
     * Removes the container and waits a bounded time for it.
     *
     * <p>A cancelled run may arrive here with the interrupt flag set, and a wait on an interrupted thread throws at
     * once. The container would then still run, and keep writing into its mounts, after the run already counts as
     * cancelled. So the flag is cleared for this one wait of at most {@value #REMOVE_WAIT_SECONDS} seconds and set
     * again afterwards: the caller still sees the interruption, only a little later.
     *
     * <p>The remove command starts in a session of its own like the client, and once more when the terminal's Ctrl-C
     * killed it before it got there ({@link OwnSession#killedAtStart}).
     *
     * @return whether the removal is confirmed: the remove command ended with exit code 0 within the wait
     */
    private static boolean remove(List<String> removeCommand, Map<String, String> environment) {
        long started = System.nanoTime();
        Integer exitCode = removeOnce(removeCommand, environment);
        if (exitCode != null && OwnSession.killedAtStart(exitCode, started)) {
            exitCode = removeOnce(removeCommand, environment);
        }
        return exitCode != null && exitCode == 0;
    }

    /** The exit code of the remove command; {@code null} when it did not start or did not end within the wait. */
    private static Integer removeOnce(List<String> removeCommand, Map<String, String> environment) {
        boolean interrupted = Thread.interrupted();
        try {
            ProcessBuilder builder = new ProcessBuilder(OwnSession.command(removeCommand))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(environment);
            ChildProcessEnv.scrub(builder);
            Process cleanup = OwnSession.start(builder::start);
            cleanup.getOutputStream().close();
            if (!cleanup.waitFor(REMOVE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                ProcessTree.terminate(cleanup);
                return null;
            }
            return cleanup.exitValue();
        } catch (InterruptedException e) {
            // Interrupted again while waiting: stop waiting, the removal goes on by itself.
            interrupted = true;
            return null;
        } catch (IOException | IllegalArgumentException e) {
            // The client cannot be started, so there is nothing to remove the container with.
            return null;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * The result, with what the readers collected so far (a reader still running may add to it afterwards) and the
     * streams that went beyond the limit.
     */
    private static CommandResult result(int exitCode, BoundedOutput stdout, BoundedOutput stderr, String note,
                                        long started, boolean timedOut) {
        String err = stderr.text();
        if (!note.isEmpty()) {
            err = err.isEmpty() || err.endsWith("\n") ? err + note : err + "\n" + note;
        }
        List<String> truncated = new ArrayList<>();
        if (stdout.exceeded()) truncated.add(CommandResult.STDOUT);
        if (stderr.exceeded()) truncated.add(CommandResult.STDERR);
        return new CommandResult(exitCode, stdout.text(), err,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), timedOut, truncated, stdout.limit());
    }
}
