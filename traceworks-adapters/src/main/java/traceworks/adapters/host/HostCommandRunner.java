package traceworks.adapters.host;

import traceworks.core.port.CommandRunner;
import traceworks.core.util.ProcessTracking;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs shell commands directly on the host: {@code /bin/bash -c} in the request's working directory. Host execution
 * is an operator exception ({@code allow_host_execution}) for environments without a container runtime; the CLI will
 * wire this runner only when the operator allows it. It never
 * runs a request for a container: a request with an image (a container step or a tool step), with mounts or with its
 * own stdin (an AI call, which always runs in a container) is refused with an {@link IllegalArgumentException}, before
 * anything runs. Without a container there is no network isolation
 * either: {@code network} is not enforced on the host.
 *
 * <ul>
 *   <li><b>Output:</b> stderr is merged into stdout in the order the process wrote them, one
 *       stream; {@link CommandResult#stderr()} stays empty. The text is returned as written; the shell block trims
 *       it. At most the output limit of it is kept ({@link BoundedOutput}): the operator's {@code output_limit_mib},
 *       {@link CommandRunner#OUTPUT_LIMIT_BYTES} by default. An output that goes beyond it stops the process and
 *       everything it started at once, as a timeout does, and the result has exit code
 *       {@value CommandRunner#OUTPUT_LIMIT_EXIT_CODE}, the first bytes up to the limit as stdout, {@code stdout} in
 *       {@link CommandResult#truncatedStreams()} and the limit in {@link CommandResult#outputLimitBytes()}.
 *       After the command ended, the output is read for a bound ({@value #AFTER_END_MILLIS} ms, or
 *       {@value #AFTER_STOP_MILLIS} ms after the runner stopped it or a cancel ended it) for what a process it left
 *       behind still writes; a reading that ends at that bound is marked in the result
 *       ({@link CommandResult#outputCut()}) and fails nothing. An interrupted wait ends the reading at once, and
 *       is marked the same way, after 0 ms.</li>
 *   <li><b>Environment:</b> only the operator's own variables of the inherited environment
 *       ({@link ChildProcessEnv#operatorEnvironment}), then the request's {@code environment}. Everything else the
 *       JVM was started with, such as API keys, tokens and cloud credentials, stays out; {@link ChildProcessEnv}
 *       scrubs the run variables of a workflow service as a second line.</li>
 *   <li><b>Command line:</b> the command goes to bash on stdin, never as an argument, so no other process on the
 *       machine reads it from the process list; its commands find stdin at its end, so a command that reads stdin
 *       ends instead of waiting for its timeout ({@link BashScript}).</li>
 *   <li><b>Timeout:</b> after {@code timeoutSeconds} the process and everything it started are ended
 *       ({@link ProcessTree}); the result is {@linkplain CommandResult#timedOut() timed out} with exit code
 *       {@value #TIMEOUT_EXIT_CODE} and the output so far.</li>
 *   <li><b>Cancel:</b> the process registers with {@link ProcessTracking#current()} through its
 *       {@linkplain ProcessTree#stopHandle stop handle}, so cancelling the run ends it and everything it started.</li>
 *   <li><b>Errors:</b> a process that cannot start, also one whose environment the operating system cannot take (a
 *       name with {@code =}, a NUL) or the JVM cannot pass on unchanged (a character beyond ASCII without a UTF-8
 *       locale, {@link ProcessEncoding}), ends with exit code 1 and {@code Error: <reason>}; an interrupted
 *       wait with exit code {@value #INTERRUPTED_EXIT_CODE} and {@code Command interrupted}. A process that could not
 *       start, and a script that did not arrive whole ({@link BashScript#notWhole}), are marked as not run
 *       ({@link CommandResult#notRunReason()}).</li>
 * </ul>
 */
public final class HostCommandRunner implements CommandRunner {

    /** The exit code of a command that ran out of time, as the shell block reports it. */
    static final int TIMEOUT_EXIT_CODE = 124;
    /** The exit code when the wait for the command was interrupted. */
    static final int INTERRUPTED_EXIT_CODE = 130;

    private static final String SHELL = "/bin/bash";
    /** How often the wait for the process looks at its output. */
    private static final long POLL_MILLIS = 200;
    /** How long the output is read after a command ended by itself, for what a process it left behind still writes. */
    static final long AFTER_END_MILLIS = 5000;
    /** The same after the command was stopped: on a timeout, an output beyond the limit or a cancel. */
    static final long AFTER_STOP_MILLIS = 2000;

    private final Map<String, String> inheritedEnvironment;
    private final String processEncoding;
    private final int outputLimit;

    /** A runner whose commands start from the operator variables of the JVM's environment, with the default limit. */
    public HostCommandRunner() {
        this(System.getenv());
    }

    /**
     * A runner whose commands start from the operator variables of the JVM's environment.
     *
     * @param outputLimit how many bytes of a command's output are kept: the operator's {@code output_limit_mib}
     */
    public HostCommandRunner(int outputLimit) {
        this(System.getenv(), ProcessEncoding.ofTheJvm(), outputLimit);
    }

    /** A runner whose commands start from the operator variables of {@code inheritedEnvironment}. */
    HostCommandRunner(Map<String, String> inheritedEnvironment) {
        this(inheritedEnvironment, ProcessEncoding.ofTheJvm());
    }

    /** @param processEncoding the encoding the JVM hands a process its arguments and environment in */
    HostCommandRunner(Map<String, String> inheritedEnvironment, String processEncoding) {
        this(inheritedEnvironment, processEncoding, OUTPUT_LIMIT_BYTES);
    }

    /** @param outputLimit how many bytes of the output are kept, at least 1 */
    HostCommandRunner(Map<String, String> inheritedEnvironment, String processEncoding, int outputLimit) {
        if (outputLimit < 1) {
            throw new IllegalArgumentException("An output limit is at least 1 byte: " + outputLimit);
        }
        this.inheritedEnvironment = ChildProcessEnv.operatorEnvironment(inheritedEnvironment);
        this.processEncoding = processEncoding;
        this.outputLimit = outputLimit;
    }

    /**
     * @throws IllegalArgumentException if the request asks for a container image or for mounts, or brings its own
     *                                  stdin: the host runs none of them
     */
    @Override
    public CommandResult run(CommandRequest request) {
        refuseContainerRequests(request);
        long start = System.nanoTime();
        CommandResult result = runOnce(request);
        // The terminal's Ctrl-C can kill the command in the instant before it reaches its own session: it had not
        // started, and a Ctrl-C is a pause, which must not fail the step in flight
        if (!ProcessTracking.current().cancelled() && !Thread.currentThread().isInterrupted()
                && OwnSession.killedAtStart(result.exitCode(), start)) {
            return request.delivered(runOnce(request));
        }
        return request.delivered(result);
    }

    private CommandResult runOnce(CommandRequest request) {
        long start = System.nanoTime();
        String command = request.script();
        ProcessTracking.Tracker tracker = ProcessTracking.current();
        Process process = null;
        ProcessTracking.StopHandle stop = null;
        BoundedOutput output = new BoundedOutput(outputLimit);
        try {
            // In a session of its own: Ctrl-C in the terminal must not reach the command (it pauses the run)
            ProcessBuilder pb = ChildProcessEnv.builder(
                    OwnSession.command(List.of(SHELL, "-c", BashScript.argument(command))),
                    request.workingDirectory(), inheritedEnvironment);
            // A name with '=' or a NUL anywhere is refused here, by the JDK: a start failure like any other
            pb.environment().putAll(request.environment());
            Optional<String> unpassable = ProcessEncoding.refusal(pb.command(), pb.environment(), processEncoding);
            if (unpassable.isPresent()) {
                return result(1, "Error: " + unpassable.get(), start, false, List.of())
                        .asNotRun(unpassable.get());
            }
            process = OwnSession.start(pb::start);
            stop = ProcessTree.stopHandle(process);
            tracker.started(stop);

            // Read the output on a thread of its own, so a full pipe never blocks the process. The reading starts
            // before bash gets its script: bash cannot end before the reader holds the stream, and so the output is
            // read to the end of the pipe, also what a child of the command writes after the command ended
            output.drain(process.getInputStream(), "host-command-output");
            BashScript.supply(process, BashScript.stdin(command));

            // An output beyond the limit stops the process the way a timeout does
            boolean timedOut = false;
            long deadline = start + TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS) && !output.exceeded()) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    break;
                }
            }
            long bound = AFTER_END_MILLIS;
            if (process.isAlive()) {
                ProcessTree.terminate(process);
                process.waitFor(ProcessTree.GRACE.toSeconds() + 2, TimeUnit.SECONDS);
                bound = AFTER_STOP_MILLIS;
            } else if (tracker.cancelled()) {
                // The cancel ended it, not the command itself
                bound = AFTER_STOP_MILLIS;
            }
            // A process the command left behind may still write: its output counts for this long, then the
            // reading ends, the process finds the output closed, and the result says that the output was cut
            output.end(bound);
            OutputCut cut = output.cut() ? new OutputCut(List.of(CommandResult.STDOUT), bound) : null;
            List<String> truncated = output.exceeded() ? List.of(CommandResult.STDOUT) : List.of();
            if (timedOut) {
                return result(TIMEOUT_EXIT_CODE, output.text(), start, true, truncated).withOutputCut(cut);
            }
            int exitCode = output.exceeded() ? OUTPUT_LIMIT_EXIT_CODE : process.exitValue();
            CommandResult ended = result(exitCode, output.text(), start, false, truncated).withOutputCut(cut);
            return BashScript.notWhole(exitCode, ended.stdout()) ? ended.asNotRun(BashScript.NOT_WHOLE_REASON) : ended;
        } catch (IOException | IllegalArgumentException e) {
            CommandResult failed = result(1, "Error: " + e.getMessage(), start, false, List.of());
            // Thrown before the process started (the JDK refused its environment, or it could not start), or after
            return process == null ? failed.asNotRun("the process could not start: " + e.getMessage()) : failed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CommandResult interrupted = result(INTERRUPTED_EXIT_CODE, "Command interrupted", start, false, List.of());
            // The reading ends here, before the end of the output: the result says so
            return output.endNow() ? interrupted.withOutputCut(new OutputCut(List.of(CommandResult.STDOUT), 0))
                    : interrupted;
        } finally {
            if (stop != null) {
                tracker.finished(stop);
            }
            ProcessTree.terminate(process);
        }
    }

    /**
     * The host runs plain shell commands only; a container step must never run on the host instead. A tool-mode
     * request always names an image ({@link CommandRequest}), so the image check refuses it too.
     */
    private static void refuseContainerRequests(CommandRequest request) {
        if (request.image() != null && !request.image().isBlank()) {
            throw new IllegalArgumentException("The host command runner does not run container images; this command "
                    + "asks for image '" + request.image() + "' and needs a container runtime");
        }
        if (!request.mounts().isEmpty()) {
            throw new IllegalArgumentException(
                    "The host command runner cannot mount directories; mounts need a container image");
        }
        if (request.stdin() != null) {
            throw new IllegalArgumentException("The host command runner does not take a request's own stdin; such a"
                    + " request (an AI call) needs a container runtime");
        }
    }

    /** A result with the merged output as stdout and this runner's limit. */
    private CommandResult result(int exitCode, String output, long startNanos, boolean timedOut,
                                 List<String> truncated) {
        return new CommandResult(exitCode, output, "", millisSince(startNanos), timedOut, truncated, outputLimit);
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
