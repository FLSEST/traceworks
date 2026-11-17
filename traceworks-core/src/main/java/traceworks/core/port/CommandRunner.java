package traceworks.core.port;

import traceworks.core.util.ProcessTracking;
import traceworks.core.util.ShellVariables;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Port to process execution: runs one shell command, normally in a container, and waits for it. The shell
 * blocks of the core run commands only through this port; the run's runner decides where: in a container
 * ({@code core.container.ContainerCommandRunner}), or on the host when the operator allows host execution.
 *
 * <p>A request runs either a shell command ({@code bash -c}) or, in tool mode, the image's own entrypoint with
 * {@linkplain CommandRequest#entrypointArgs() arguments} and no shell in between.
 *
 * <p>Running a command has two steps: {@link #plan} resolves what the command will be (image ID, effective mounts,
 * working directory) without starting anything, and the plan's execution runs it. The record writes the planned
 * facts before the command starts; {@link #run} is both steps at once.
 *
 * <p>An implementation that starts a process registers a {@linkplain ProcessTracking.StopHandle stop handle} for it
 * with {@link ProcessTracking#current()}: the engine binds the tracker of the running step there, so cancelling the
 * run calls the handle, which ends the process and everything it started.
 *
 * <p>An implementation that reads a process keeps at most its output limit of its stdout and of its stderr, each:
 * the operator's {@code output_limit_mib}, {@link #OUTPUT_LIMIT_BYTES} (16 MiB) by default, the same for every runner
 * of a run. A stream that goes beyond it stops the command at once, the way a timeout does; the result holds the first
 * bytes of that stream up to the limit, cut where no UTF-8 character is split, names it in
 * {@link CommandResult#truncatedStreams()}, names the limit in {@link CommandResult#outputLimitBytes()} and has the
 * exit code {@value #OUTPUT_LIMIT_EXIT_CODE}, unless a timeout or a cancel ended the command first. A command over the
 * limit has failed: what runs it never takes the cut text for the whole one, and says why with
 * {@link CommandResult#truncationNote()}.
 */
public interface CommandRunner {

    /**
     * The most a runner keeps of a command's stdout, and of its stderr, in MiB, unless the operator sets another limit
     * ({@code output_limit_mib}): 16 MiB each. Every runner has a limit, so a command that prints without end cannot
     * exhaust Traceworks' memory, whatever the container's memory limit is.
     */
    int OUTPUT_LIMIT_MIB = 16;

    /** {@link #OUTPUT_LIMIT_MIB} in bytes, the default limit of a runner. */
    int OUTPUT_LIMIT_BYTES = OUTPUT_LIMIT_MIB * 1024 * 1024;

    /**
     * The exit code of a command that a runner stopped because a stream went beyond its output limit:
     * 128 + SIGXFSZ, the signal with which the system stops a process that writes past its file size limit.
     */
    int OUTPUT_LIMIT_EXIT_CODE = 153;

    /** Runs the command and returns once it has ended or timed out. */
    CommandResult run(CommandRequest request);

    /**
     * The image reference that {@code image}, as a request names it, resolves to when this runner runs it: for a
     * package image ({@code ./images/<name>}) the tag Traceworks built, {@code null} when it has none. By default the
     * name itself, right for a runner that has no catalog of images. A runner that wraps another one answers as the
     * wrapped one does.
     */
    default String imageReference(String image) {
        return image;
    }

    /**
     * {@code bytes} as an output limit reads: {@code 16 MiB} for whole MiB, as the operator sets it, {@code 4 KiB} for
     * whole KiB and {@code 100 bytes} otherwise, as only a test sets it.
     */
    static String sizeText(long bytes) {
        long mib = 1024 * 1024;
        if (bytes >= mib && bytes % mib == 0) return bytes / mib + " MiB";
        if (bytes >= 1024 && bytes % 1024 == 0) return bytes / 1024 + " KiB";
        return bytes + (bytes == 1 ? " byte" : " bytes");
    }

    /**
     * What running {@code request} means, resolved before anything starts, and the execution that runs it. By default
     * the request's own facts, without an image ID and on the host when it names no image, and the execution is
     * {@link #run}: right for a runner that runs every request as it is.
     */
    default CommandPlan plan(CommandRequest request) {
        return new CommandPlan(new CommandFacts(request.workingDirectory(), request.image(), null, request.mounts(),
                request.network(), List.copyOf(request.environment().keySet()), request.image() == null),
                () -> run(request));
    }

    /**
     * One command to run. Absent environment and mounts are empty; environment, mounts and entrypoint arguments are
     * read-only copies.
     *
     * @param command           the command line, run by a shell ({@code bash -c}); {@code null} in tool mode
     * @param workingDirectory  the directory the command starts in, as a host path (the run's project); a container
     *                          runner maps it to where the container sees it
     * @param timeoutSeconds    after this many seconds the command is stopped and the result is
     *                          {@linkplain CommandResult#timedOut() timed out}
     * @param environment       the environment variables set for the command
     * @param image             the container image to run in; {@code null} for none, and the run's runner decides:
     *                          the {@code shell} image in a container run, the host in a host run
     * @param mounts            the block's own host directories mounted into the container; the runner adds the
     *                          run's standard ones (workspace, package, scratch)
     * @param network           where the container can connect; absent is {@link Egress#NONE}. A shell step has
     *                          {@link Egress#OPEN} with {@code network: true} and none without; an AI call has the
     *                          hosts of its provider ({@link Egress#hosts})
     * @param entrypointArgs    tool mode: the arguments for the image's own entrypoint, which runs without a shell;
     *                          {@code null} for a shell command. Added for the tool mode of the {@code shell} block.
     * @param stdin             what the command reads on its standard input; {@code null} for nothing (closed)
     * @param writableWorkspace whether a container may write the workspace ({@code workspace_writable: true}); by
     *                          default it sees it read-only
     * @param noRegisterFolder  whether the container goes without the register folder of an audit run, which every
     *                          other container of such a run mounts read-only: an AI call, whose model reads of the
     *                          register only what a step put into its prompt
     * @param values            shell variables the command finds set, by name, in the order given: the block's
     *                          {@code values}. They are no part of {@code command}: a runner runs {@link #script},
     *                          which sets them first, and the record keeps them next to the command. Not exported,
     *                          and only for a shell command
     * @throws IllegalArgumentException if the request has both a command and entrypoint arguments, neither,
     *                                  entrypoint arguments without an image, or values without a shell command
     */
    record CommandRequest(String command, String workingDirectory, int timeoutSeconds,
                          Map<String, String> environment, String image, List<Mount> mounts, Egress network,
                          List<String> entrypointArgs, String stdin, boolean writableWorkspace,
                          boolean noRegisterFolder, Map<String, String> values) {
        public CommandRequest {
            network = network == null ? Egress.NONE : network;
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            mounts = mounts == null ? List.of() : List.copyOf(mounts);
            entrypointArgs = entrypointArgs == null ? null : List.copyOf(entrypointArgs);
            values = values == null || values.isEmpty() ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
            if (entrypointArgs != null && !values.isEmpty()) {
                throw new IllegalArgumentException("An image's entrypoint runs without a shell: it takes no values");
            }
            if (entrypointArgs != null) {
                if (command != null) {
                    throw new IllegalArgumentException(
                            "A request runs either a shell command or the image's entrypoint, not both");
                }
                if (image == null || image.isBlank()) {
                    throw new IllegalArgumentException("Running an image's entrypoint needs an image");
                }
            } else if (command == null) {
                throw new IllegalArgumentException("A request needs a command or entrypoint arguments");
            }
        }

        /**
         * A request that gets the register folder of an audit run, as every request but an AI call does, and has no
         * values.
         */
        public CommandRequest(String command, String workingDirectory, int timeoutSeconds,
                              Map<String, String> environment, String image, List<Mount> mounts, Egress network,
                              List<String> entrypointArgs, String stdin, boolean writableWorkspace) {
            this(command, workingDirectory, timeoutSeconds, environment, image, mounts, network, entrypointArgs,
                    stdin, writableWorkspace, false, Map.of());
        }

        /** A request with the network on ({@link Egress#OPEN}) or off ({@link Egress#NONE}). */
        public CommandRequest(String command, String workingDirectory, int timeoutSeconds,
                              Map<String, String> environment, String image, List<Mount> mounts, boolean network,
                              List<String> entrypointArgs, String stdin, boolean writableWorkspace) {
            this(command, workingDirectory, timeoutSeconds, environment, image, mounts, Egress.of(network),
                    entrypointArgs, stdin, writableWorkspace);
        }

        /** A request without stdin, with a read-only workspace. */
        public CommandRequest(String command, String workingDirectory, int timeoutSeconds,
                              Map<String, String> environment, String image, List<Mount> mounts, boolean network,
                              List<String> entrypointArgs) {
            this(command, workingDirectory, timeoutSeconds, environment, image, mounts, network, entrypointArgs, null,
                    false);
        }

        /**
         * A shell command ({@code bash -c}) without stdin, with a read-only workspace; without {@code image} it runs
         * where the run's runner runs image-less commands.
         */
        public CommandRequest(String command, String workingDirectory, int timeoutSeconds,
                              Map<String, String> environment, String image, List<Mount> mounts, boolean network) {
            this(command, workingDirectory, timeoutSeconds, environment, image, mounts, network, null);
        }

        /** Tool mode: the entrypoint of {@code image} runs with {@code args}, no shell in between. */
        public static CommandRequest entrypoint(String image, List<String> args, String workingDirectory,
                                                int timeoutSeconds, Map<String, String> environment,
                                                List<Mount> mounts, boolean network) {
            return new CommandRequest(null, workingDirectory, timeoutSeconds, environment, image, mounts, network,
                    args == null ? List.of() : args);
        }

        /** This request, with {@code network} as where its container can connect. */
        public CommandRequest withNetwork(Egress network) {
            return new CommandRequest(command, workingDirectory, timeoutSeconds, environment, image, mounts, network,
                    entrypointArgs, stdin, writableWorkspace, noRegisterFolder, values);
        }

        /** This request, without the register folder of an audit run among its mounts. */
        public CommandRequest withoutRegisterFolder() {
            return new CommandRequest(command, workingDirectory, timeoutSeconds, environment, image, mounts, network,
                    entrypointArgs, stdin, writableWorkspace, true, values);
        }

        /** This request, with {@code values} as the shell variables its command finds set. */
        public CommandRequest withValues(Map<String, String> values) {
            return new CommandRequest(command, workingDirectory, timeoutSeconds, environment, image, mounts, network,
                    entrypointArgs, stdin, writableWorkspace, noRegisterFolder, values);
        }

        /**
         * The text a runner hands to bash for a shell command: the lines that set the {@link #values}, written by
         * Traceworks alone and without a byte of a value as code ({@link ShellVariables#preamble}), then
         * {@link #command}; the command itself for a request without values. {@code null} for an entrypoint request.
         */
        public String script() {
            return command == null ? null : ShellVariables.preamble(values) + command;
        }

        /**
         * The result of a runner that ran {@link #script}, as the engine reads it: when a value was not delivered
         * ({@link ShellVariables#notDelivered}) the command's own text did not run, and the result says so
         * ({@link CommandResult#notRunReason}), so that no condition takes the exit code for an answer. Every
         * runner passes its result through this.
         */
        public CommandResult delivered(CommandResult result) {
            if (result == null || result.didNotRun() || values.isEmpty()) {
                return result;
            }
            // A runner may hand both streams over as one
            boolean failed = ShellVariables.notDelivered(values, result.exitCode(), result.stderr())
                    || ShellVariables.notDelivered(values, result.exitCode(), result.stdout());
            return failed ? result.asNotRun("a value of the block (values) was not delivered to the shell, so the"
                    + " command did not run") : result;
        }

        /** Whether this request runs the image's entrypoint (tool mode) instead of a shell command. */
        public boolean runsEntrypoint() {
            return entrypointArgs != null;
        }

        /** This request, with {@code stdin} on its standard input; {@code null} closes it. */
        public CommandRequest withStdin(String stdin) {
            return new CommandRequest(command, workingDirectory, timeoutSeconds, environment, image, mounts, network,
                    entrypointArgs, stdin, writableWorkspace, noRegisterFolder, values);
        }

        /** This request, with the workspace writable or read-only. */
        public CommandRequest withWritableWorkspace(boolean writable) {
            return new CommandRequest(command, workingDirectory, timeoutSeconds, environment, image, mounts, network,
                    entrypointArgs, stdin, writable, noRegisterFolder, values);
        }

        /** The request with the names of its environment variables, never their values (a secret's among them). */
        @Override
        public String toString() {
            return "CommandRequest[command=" + command + ", workingDirectory=" + workingDirectory
                    + ", timeoutSeconds=" + timeoutSeconds + ", environment=" + new TreeSet<>(environment.keySet())
                    + ", image=" + image + ", mounts=" + mounts + ", network=" + network + ", entrypointArgs="
                    + entrypointArgs + ", stdin=" + (stdin == null ? null : stdin.length() + " characters")
                    + ", writableWorkspace=" + writableWorkspace + ", noRegisterFolder=" + noRegisterFolder
                    + (values.isEmpty() ? "" : ", values=" + values.keySet()) + "]";
        }
    }

    /**
     * A command as it will run, and how to run it. The execution runs it once; it may return a refusal (a missing
     * image) instead of starting anything.
     */
    record CommandPlan(CommandFacts facts, Supplier<CommandResult> execution) {
        public CommandPlan {
            Objects.requireNonNull(facts, "facts");
            Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * What a command will run as, resolved before it starts: what the record keeps of it ({@code step.command}).
     *
     * @param workingDirectory the directory the command starts in, inside the container for a container command
     * @param image            the image as the step names it, {@code shell} for the default image; {@code null} on
     *                         the host
     * @param imageId          the local ID of the image the container starts from; {@code null} on the host and for an
     *                         image that is not present
     * @param mounts           every mount the container gets, the standard ones (workspace, package, scratch, and
     *                         the register folder of an audit run) first
     * @param network          where the container can connect; absent is {@link Egress#NONE}
     * @param environmentNames the names of the environment variables the command gets, sorted; never their values
     * @param host             whether the command runs on the host
     */
    record CommandFacts(String workingDirectory, String image, String imageId, List<Mount> mounts, Egress network,
                        List<String> environmentNames, boolean host) {
        public CommandFacts {
            network = network == null ? Egress.NONE : network;
            mounts = mounts == null ? List.of() : List.copyOf(mounts);
            environmentNames = environmentNames == null ? List.of() : List.copyOf(new TreeSet<>(environmentNames));
        }

        /** Facts with the network on ({@link Egress#OPEN}) or off ({@link Egress#NONE}). */
        public CommandFacts(String workingDirectory, String image, String imageId, List<Mount> mounts,
                            boolean network, List<String> environmentNames, boolean host) {
            this(workingDirectory, image, imageId, mounts, Egress.of(network), environmentNames, host);
        }
    }

    /**
     * A bind mount of a host directory into the container.
     *
     * @param readOnly whether the container may only read it
     */
    record Mount(String hostPath, String containerPath, boolean readOnly) {}

    /**
     * What a container asked its egress proxy for: the connections to one target that got one verdict.
     *
     * @param target  {@code host:port} as the container wrote it, in lower case; {@code ?} for a request that named
     *                none, {@value #OTHER_TARGETS} for the targets beyond the first {@value #MAX_TARGETS}, and
     *                {@code *} for {@value #LIMIT}
     * @param verdict {@value #ALLOW} (connected), {@value #DENY} (not listed, or no {@code CONNECT}), {@value #FAIL}
     *                (listed, but the connection failed) or {@value #LIMIT} (the proxy reached the end of its log and
     *                refused everything from then on)
     * @param count   how many requests
     */
    record Connection(String target, String verdict, int count) {

        public static final String ALLOW = "ALLOW";
        public static final String DENY = "DENY";
        public static final String FAIL = "FAIL";
        public static final String LIMIT = "LIMIT";

        /** How many distinct targets a result names; the requests to further ones are counted together. */
        public static final int MAX_TARGETS = 100;
        /** The target of the requests to targets beyond {@link #MAX_TARGETS}. */
        public static final String OTHER_TARGETS = "(other targets)";

        /** Whether the container asked for something it did not get because it is not allowed. */
        public boolean denied() {
            return DENY.equals(verdict) || LIMIT.equals(verdict);
        }
    }

    /**
     * The reading of a command's output that ended before the end of the stream: at its bound, because after the
     * command ended something it left behind still held the output, or at once, because the wait for the command was
     * interrupted. What was written afterwards was not kept. The output may be incomplete. A mark: it fails nothing.
     *
     * @param streams the streams whose reading ended so, {@value CommandResult#STDOUT} before
     *                {@value CommandResult#STDERR}; never empty, a read-only copy
     * @param afterMs how long the runner waited for the end of the output after the command ended: the bound; 0 for
     *                an interrupted wait
     */
    record OutputCut(List<String> streams, long afterMs) {

        public OutputCut {
            streams = List.copyOf(streams);
            if (streams.isEmpty()) {
                throw new IllegalArgumentException("A cut names the streams it happened to");
            }
        }

        /** Such as {@code stdout after 5000 ms}. */
        public String summary() {
            return String.join(", ", streams) + " after " + afterMs + " ms";
        }
    }

    /**
     * How a command ended.
     *
     * @param exitCode         the exit code of the command
     * @param durationMs       how long the command ran
     * @param timedOut         whether the command was stopped because it ran out of time
     * @param truncatedStreams the streams that went beyond the output limit, {@value #STDOUT} before
     *                         {@value #STDERR}; such a stream holds only its first {@code outputLimitBytes}. Empty
     *                         when the output is whole; absent is empty, and the list is a read-only copy
     * @param outputLimitBytes the most the runner kept of each stream, which {@link #truncationNote()} names:
     *                         {@link CommandRunner#OUTPUT_LIMIT_BYTES} unless the operator set another limit
     * @param notRunReason     why the command did not run, as the runner knows it: a refusal it planned (a missing
     *                         image, no container runtime, a mount it refuses), a process or container client that
     *                         could not start, a script that did not arrive whole. The exit code and the output say
     *                         the same, but a command may end with that exit code by itself, so what reads the result
     *                         as an answer (a test, a condition, a list) asks this. Empty when the command ran, or the
     *                         runner cannot tell; absent is empty
     * @param connections      what the container asked its egress proxy for, by target and verdict in the order of
     *                         their first appearance; empty for a command without listed hosts
     *                         ({@link Egress#hosts}); absent is empty
     * @param outputCut        set when the reading of the output ended at its bound because something the command
     *                         left behind still held it; {@code null} when the output was read to its end
     */
    record CommandResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut,
                         List<String> truncatedStreams, int outputLimitBytes, String notRunReason,
                         List<Connection> connections, OutputCut outputCut) {

        /** The name of the standard output in {@link #truncatedStreams()} and in the record. */
        public static final String STDOUT = "stdout";
        /** The name of the standard error in {@link #truncatedStreams()} and in the record. */
        public static final String STDERR = "stderr";

        public CommandResult {
            truncatedStreams = truncatedStreams == null ? List.of() : List.copyOf(truncatedStreams);
            if (outputLimitBytes < 1) {
                throw new IllegalArgumentException("An output limit is at least 1 byte: " + outputLimitBytes);
            }
            notRunReason = notRunReason == null ? "" : notRunReason;
            connections = connections == null ? List.of() : List.copyOf(connections);
        }

        /** A result of a command whose output was read to its end. */
        public CommandResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut,
                             List<String> truncatedStreams, int outputLimitBytes, String notRunReason,
                             List<Connection> connections) {
            this(exitCode, stdout, stderr, durationMs, timedOut, truncatedStreams, outputLimitBytes, notRunReason,
                    connections, null);
        }

        /** This result, with the mark of an output whose reading ended at its bound; {@code null} for none. */
        public CommandResult withOutputCut(OutputCut outputCut) {
            return new CommandResult(exitCode, stdout, stderr, durationMs, timedOut, truncatedStreams,
                    outputLimitBytes, notRunReason, connections, outputCut);
        }

        /** A result of a command that asked no egress proxy for anything. */
        public CommandResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut,
                             List<String> truncatedStreams, int outputLimitBytes, String notRunReason) {
            this(exitCode, stdout, stderr, durationMs, timedOut, truncatedStreams, outputLimitBytes, notRunReason,
                    List.of());
        }

        /** This result, with what its container asked the egress proxy for. */
        public CommandResult withConnections(List<Connection> connections) {
            return new CommandResult(exitCode, stdout, stderr, durationMs, timedOut, truncatedStreams,
                    outputLimitBytes, notRunReason, connections, outputCut);
        }

        /** A result of a command that ran, or of which the runner cannot tell that it did not. */
        public CommandResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut,
                             List<String> truncatedStreams, int outputLimitBytes) {
            this(exitCode, stdout, stderr, durationMs, timedOut, truncatedStreams, outputLimitBytes, "");
        }

        /**
         * A command that did not run: nothing started, so its output is whole and it did not time out.
         *
         * @param reason why it did not run, which the result keeps as {@link #notRunReason()} and as its stderr
         */
        public static CommandResult notRun(int exitCode, String reason, int outputLimitBytes) {
            return new CommandResult(exitCode, "", reason, 0, false, List.of(), outputLimitBytes, reason);
        }

        /** Whether the runner knows that the command did not run ({@link #notRunReason()}). */
        public boolean didNotRun() {
            return !notRunReason.isEmpty();
        }

        /** This result, marked as one of a command that did not run, for {@code reason}. */
        public CommandResult asNotRun(String reason) {
            return new CommandResult(exitCode, stdout, stderr, durationMs, timedOut, truncatedStreams,
                    outputLimitBytes, reason, connections, outputCut);
        }

        /** A result of a runner with the default limit, {@link CommandRunner#OUTPUT_LIMIT_BYTES}. */
        public CommandResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut,
                             List<String> truncatedStreams) {
            this(exitCode, stdout, stderr, durationMs, timedOut, truncatedStreams, OUTPUT_LIMIT_BYTES);
        }

        /** A result whose output is whole. */
        public CommandResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut) {
            this(exitCode, stdout, stderr, durationMs, timedOut, List.of());
        }

        /** Whether a stream went beyond the output limit, so the output is not whole. */
        public boolean truncated() {
            return !truncatedStreams.isEmpty();
        }

        /** The output limit as a text, such as {@code 16 MiB}: what {@link #truncationNote()} names. */
        public String outputLimit() {
            return CommandRunner.sizeText(outputLimitBytes);
        }

        /**
         * The sentence that says which stream went beyond the limit, such as {@code The command's standard output
         * exceeded 16 MiB; Traceworks stopped it and kept the first 16 MiB}; empty when the output is whole.
         */
        public String truncationNote() {
            if (truncatedStreams.isEmpty()) {
                return "";
            }
            String limit = outputLimit();
            List<String> names = truncatedStreams.stream()
                    .map(stream -> STDERR.equals(stream) ? "standard error" : "standard output").toList();
            return names.size() == 1
                    ? "The command's " + names.getFirst() + " exceeded " + limit
                            + "; Traceworks stopped it and kept the first " + limit
                    : "The command's " + String.join(" and ", names) + " exceeded " + limit
                            + " each; Traceworks stopped it and kept the first " + limit + " of each";
        }
    }
}
