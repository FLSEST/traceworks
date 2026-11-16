package traceworks.adapters.container;

import traceworks.adapters.host.BashScript;
import traceworks.core.compat.EarlierNames;
import traceworks.core.context.ExecutionContext;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.testing.RecordingTracker;
import traceworks.core.util.ProcessTracking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client process of one container. A shell script stands in for the runtime's client
 * ({@code /bin/sh fake-docker.sh}), so no runtime is needed; the remove command ({@code rm -f <name>}) is a command of
 * the test's choice. There are no live output lines to test (a later version brings step log events). Traceworks
 * keeps stdout and stderr apart, and reports a cancel as exit code 130.
 */
class ContainerProcessTest {

    private static final Map<String, String> ENVIRONMENT = Map.of("PATH", System.getenv("PATH"));

    private static CommandResult execute(Path fakeClient, String stdin, int timeoutSeconds, List<String> remove) {
        return ContainerProcess.execute(List.of("/bin/sh", fakeClient.toString()), ENVIRONMENT, stdin,
                timeoutSeconds, remove);
    }

    @Test
    void execute_keepsStdoutAndStderrApart_andPassesTheExitCodeThrough(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo started\necho 'no such image' >&2\n"
                + "echo done\nexit 125\n");

        CommandResult result = execute(fake, null, 10, List.of("true"));

        assertThat(result.stdout()).isEqualTo("started\ndone\n");
        assertThat(result.stderr()).isEqualTo("no such image\n");
        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.didNotRun()).as("a client that exits 125 ran; its exit code alone says nothing").isFalse();
    }

    /** The fake client runs what a container runs for a large script: bash with the runner. */
    @Test
    void execute_handsItsStdinOver_alsoBeyondTheArgumentLimit(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"),
                "exec /bin/bash -c '" + BashScript.RUNNER.replace("'", "'\\''") + "'\n");
        String script = "value='" + "a".repeat(200_000) + "'\necho \"${#value}\"";

        CommandResult result = execute(fake, BashScript.stdin(script), 10, List.of("true"));

        assertThat(result.stdout()).isEqualTo("200000\n");
        assertThat(result.exitCode()).isZero();
    }

    /** Without stdin the client's own is closed right after the start: nothing waits for input that never comes. */
    @Test
    void execute_withoutStdin_closesStdinAtOnce(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "cat\necho done\n");

        CommandResult result = execute(fake, null, 5, List.of("true"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("done\n");
        assertThat(result.durationMs()).isLessThan(4_000);
    }

    @Test
    void execute_givesTheClientOnlyTheGivenEnvironment(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo \"[$TOKEN] [${HOME-unset}]\"\n");

        CommandResult result = ContainerProcess.execute(List.of("/bin/sh", fake.toString()),
                Map.of("PATH", System.getenv("PATH"), "TOKEN", "the value"), null, 10, List.of("true"));

        assertThat(result.stdout()).isEqualTo("[the value] [unset]\n");
    }

    /** The remove command gets only the given environment too, as the client does. */
    @Test
    void execute_givesTheRemoveCommandOnlyTheGivenEnvironment(@TempDir Path dir) throws IOException {
        Path seen = dir.resolve("seen");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo ok\n");

        ContainerProcess.execute(List.of("/bin/sh", fake.toString()), ENVIRONMENT, null, 10,
                List.of("/bin/sh", "-c", "echo \"[${HOME-unset}]\" > \"$1\"", "fake-docker-rm", seen.toString()));

        assertThat(seen).hasContent("[unset]");
    }

    /** The container is removed after every end, a normal one included: {@code --rm} alone leaves a race. */
    @Test
    void execute_normalEnd_removesTheContainer(@TempDir Path dir) throws IOException {
        Path removed = dir.resolve("removed");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo ok\n");

        CommandResult result = execute(fake, null, 10, List.of("touch", removed.toString()));

        assertThat(result.exitCode()).isZero();
        assertThat(removed).as("container removed").exists();
    }

    /** A removal that fails is reported, so the run knows the container may still run; a confirmed one is not. */
    @Test
    void execute_reportsARemovalThatIsNotConfirmed(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo ok\n");
        AtomicInteger unconfirmed = new AtomicInteger();

        ContainerProcess.execute(List.of("sh", fake.toString()), Map.of("PATH", System.getenv("PATH")), null, 10,
                List.of("false"), unconfirmed::incrementAndGet, CommandRunner.OUTPUT_LIMIT_BYTES);
        assertThat(unconfirmed).hasValue(1);
        ContainerProcess.execute(List.of("sh", fake.toString()), Map.of("PATH", System.getenv("PATH")), null, 10,
                List.of("true"), unconfirmed::incrementAndGet, CommandRunner.OUTPUT_LIMIT_BYTES);
        assertThat(unconfirmed).as("a removal that succeeded").hasValue(1);
    }

    /**
     * A remove command that does not end within its wait confirms nothing: the container may still run, and still
     * write into its mounts.
     */
    @Test
    void execute_aRemovalThatDoesNotEndWithinItsWait_isNotConfirmed(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo ok\n");
        AtomicInteger unconfirmed = new AtomicInteger();

        CommandResult result = ContainerProcess.execute(List.of("/bin/sh", fake.toString()), ENVIRONMENT, null, 10,
                List.of("sleep", "30"), unconfirmed::incrementAndGet, CommandRunner.OUTPUT_LIMIT_BYTES);

        assertThat(result.exitCode()).isZero();
        assertThat(unconfirmed).hasValue(1);
    }

    /** A client that timed out or printed beyond the limit is ended: a removal that fails then is reported as well. */
    @Test
    void execute_reportsARemovalThatIsNotConfirmed_alsoAfterATimeoutOrAnOutputBeyondTheLimit(@TempDir Path dir)
            throws IOException {
        Path hangs = Files.writeString(dir.resolve("hangs.sh"), "exec sleep 30\n");
        Path floods = Files.writeString(dir.resolve("floods.sh"), "exec yes\n");
        AtomicInteger unconfirmed = new AtomicInteger();

        CommandResult timedOut = ContainerProcess.execute(List.of("/bin/sh", hangs.toString()), ENVIRONMENT, null, 1,
                List.of("false"), unconfirmed::incrementAndGet, CommandRunner.OUTPUT_LIMIT_BYTES);
        assertThat(timedOut.exitCode()).isEqualTo(124);
        assertThat(unconfirmed).as("after the timeout").hasValue(1);
        CommandResult beyond = ContainerProcess.execute(List.of("/bin/sh", floods.toString()), ENVIRONMENT, null, 60,
                List.of("false"), unconfirmed::incrementAndGet, 1024);
        assertThat(beyond.exitCode()).isEqualTo(153);
        assertThat(unconfirmed).as("after the output beyond the limit").hasValue(2);
    }

    /**
     * The run variables of a workflow service ({@link EarlierNames#SERVICE_ENVIRONMENT}) go also when the given
     * environment holds them, for the client and for its removal: the second line behind the caller's allowlist.
     */
    @Test
    void execute_givesNeitherTheClientNorTheRemovalTheRunVariablesOfAService(@TempDir Path dir) throws IOException {
        String runToken = EarlierNames.SERVICE_ENVIRONMENT.stream().filter(name -> name.endsWith("_RUN_TOKEN"))
                .findFirst().orElseThrow();
        Path seen = dir.resolve("seen");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"),
                "echo \"[${" + runToken + "-unset}] [$TOKEN]\"\n");
        Map<String, String> environment = Map.of("PATH", System.getenv("PATH"), "TOKEN", "the value",
                runToken, "token-of-the-parent-run");

        CommandResult result = ContainerProcess.execute(List.of("/bin/sh", fake.toString()), environment, null, 10,
                List.of("/bin/sh", "-c", "echo \"[${" + runToken + "-unset}]\" > \"$1\"", "fake-docker-rm",
                        seen.toString()));

        assertThat(result.stdout()).isEqualTo("[unset] [the value]\n");
        assertThat(seen).hasContent("[unset]");
    }

    @Test
    void execute_timeout_gives124_endsTheClient_andRemovesTheContainer(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path removed = dir.resolve("removed");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"),
                "touch '" + started + "'\necho half\nexec sleep 30\n");
        long start = System.nanoTime();

        CommandResult result = execute(fake, null, 1, List.of("touch", removed.toString()));

        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.stdout()).as("the output so far").isEqualTo("half\n");
        assertThat(removed).as("container removed").exists();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(15);
    }

    /** Like {@link #execute}, keeping at most {@code limit} bytes of each stream, as the operator's limit does. */
    private static CommandResult limited(Path fakeClient, int limit, List<String> remove) {
        return ContainerProcess.execute(List.of("/bin/sh", fakeClient.toString()), ENVIRONMENT, null, 120, remove,
                () -> { }, limit);
    }

    /**
     * A stream beyond the limit stops the client as a timeout does, long before the timeout: it is ended, the
     * container is removed, and the result keeps exactly the first bytes of the stream, names it and has the limit's
     * exit code. The other stream is kept whole.
     */
    @Test
    void execute_streamBeyondTheLimit_endsTheClientAtOnce_andRemovesTheContainer(@TempDir Path dir)
            throws Exception {
        Path removed = dir.resolve("removed");
        Path pidFile = dir.resolve("yes.pid");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"),
                "echo 'pulling' >&2\necho $$ > '" + pidFile + "'\nexec yes\n");
        long start = System.nanoTime();

        CommandResult result = limited(fake, 64 * 1024, List.of("touch", removed.toString()));

        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(20);
        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.truncatedStreams()).containsExactly("stdout");
        assertThat(result.stdout().length()).isEqualTo(64 * 1024);
        assertThat(result.stdout().replace("y\n", "")).isEmpty();
        assertThat(result.stderr()).isEqualTo("pulling\n");
        assertThat(result.outputLimitBytes()).as("the limit it had").isEqualTo(64 * 1024);
        assertThat(result.truncationNote()).contains("exceeded 64 KiB");
        assertThat(removed).as("container removed").exists();
        long yes = Long.parseLong(Files.readString(pidFile).strip());
        assertThat(ProcessHandle.of(yes).map(ProcessHandle::isAlive).orElse(false)).as("client ended").isFalse();
    }

    @Test
    void execute_stderrBeyondTheLimit_isNamed_andStdoutStaysWhole(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo started\nexec yes no >&2\n");

        CommandResult result = limited(fake, 1024, List.of("true"));

        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.truncatedStreams()).containsExactly("stderr");
        assertThat(result.stdout()).isEqualTo("started\n");
        assertThat(result.stderr()).isEqualTo("no\n".repeat(341) + "n");
    }

    /** By default the client keeps 16 MiB of a stream. */
    @Test
    void execute_endlessOutput_keepsSixteenMebibytes(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "exec yes\n");

        CommandResult result = execute(fake, null, 120, List.of("true"));

        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.truncatedStreams()).containsExactly("stdout");
        assertThat(result.stdout().length()).isEqualTo(16 * 1024 * 1024);
    }

    /** "€" is three bytes in UTF-8; the one the limit splits is left out whole. Up to the limit nothing changes. */
    @Test
    void execute_aCharacterThatStraddlesTheLimit_isNotSplit_andOutputUpToTheLimitIsWhole(@TempDir Path dir)
            throws IOException {
        Path straddles = Files.writeString(dir.resolve("straddles.sh"),
                "head -c 1022 /dev/zero | tr '\\0' a\nprintf '\\342\\202\\254 and more'\n");
        Path fits = Files.writeString(dir.resolve("fits.sh"),
                "head -c 1021 /dev/zero | tr '\\0' a\nprintf '\\342\\202\\254'\nprintf 'x' >&2\n");

        CommandResult cut = limited(straddles, 1024, List.of("true"));
        CommandResult whole = limited(fits, 1024, List.of("true"));

        assertThat(cut.exitCode()).isEqualTo(153);
        assertThat(cut.stdout()).isEqualTo("a".repeat(1022));
        assertThat(whole.exitCode()).isZero();
        assertThat(whole.truncatedStreams()).isEmpty();
        assertThat(whole.stdout()).isEqualTo("a".repeat(1021) + "€");
        assertThat(whole.stderr()).isEqualTo("x");
    }

    /**
     * The client registers with the run whose step runs on this thread, so a cancel ends it, the container is removed
     * and the result is exit code 130, whether or not the cancel reached the client first.
     */
    @Test
    void execute_onARunThread_endsWhenTheRunIsCancelled_andRemovesTheContainer(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path removed = dir.resolve("removed");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "touch '" + started + "'\nexec sleep 30\n");
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        AtomicReference<CommandResult> result = new AtomicReference<>();

        Thread worker = Thread.ofVirtual().start(() -> result.set(ProcessTracking.bind(context.processTracker(),
                () -> execute(fake, null, 60, List.of("touch", removed.toString())))));
        awaitFile(started);
        context.cancel();
        worker.join(TimeUnit.SECONDS.toMillis(15));

        assertThat(worker.isAlive()).as("container client still running after the cancel").isFalse();
        assertThat(result.get().exitCode()).isEqualTo(130);
        assertThat(result.get().timedOut()).isFalse();
        assertThat(removed).as("container removed").exists();
    }

    /**
     * A tracker tells stop handles apart by identity: the client reports its end with the very handle it reported its
     * start with, once each, for a container and for a client call alike, so a client that ended leaves nothing behind
     * for a later cancel to call.
     */
    @Test
    void execute_andCall_onARunThread_unregisterTheVeryHandleTheyRegistered(@TempDir Path dir) throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo ok\n");
        RecordingTracker tracker = new RecordingTracker();

        CommandResult container = ProcessTracking.bind(tracker, () -> execute(fake, null, 10, List.of("true")));
        assertThat(container.exitCode()).isZero();
        assertThat(tracker.started()).hasSize(1);
        assertThat(tracker.finished()).hasSize(1);
        assertThat(tracker.finished().getFirst()).isSameAs(tracker.started().getFirst());

        CommandResult call = ProcessTracking.bind(tracker,
                () -> ContainerProcess.call(List.of("/bin/sh", fake.toString()), ENVIRONMENT, 10,
                        CommandRunner.OUTPUT_LIMIT_BYTES));
        assertThat(call.exitCode()).isZero();
        assertThat(tracker.started()).hasSize(2);
        assertThat(tracker.finished()).hasSize(2);
        assertThat(tracker.finished().get(1)).isSameAs(tracker.started().get(1));
    }

    /**
     * The terminal's Ctrl-C can kill a client in the instant before it reaches its own session: it ends with 130 and
     * has done nothing. Such a client call is made again, once; a lookup that ends so must not read as "not there".
     */
    @Test
    void call_ofAClientTheCtrlCKilledAtItsStart_isMadeAgain(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                traceworks.adapters.host.OwnSession.available(), "needs setsid");
        Path calls = dir.resolve("calls");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo call >> '" + calls + "'\n"
                + "[ \"$(wc -l < '" + calls + "')\" -ge 2 ] || exit 130\necho found\n");

        traceworks.adapters.host.OwnSession.terminalInterrupt();
        CommandResult call = ContainerProcess.call(List.of("/bin/sh", fake.toString()), ENVIRONMENT, 10,
                CommandRunner.OUTPUT_LIMIT_BYTES);

        assertThat(call.exitCode()).as(call.stderr()).isZero();
        assertThat(call.stdout()).contains("found");
        assertThat(Files.readAllLines(calls)).hasSize(2);
    }

    @Test
    void execute_cancelledBeforeTheStart_startsNothing(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path removed = dir.resolve("removed");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "touch '" + started + "'\n");
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.cancel();

        CommandResult result = ProcessTracking.bind(context.processTracker(),
                () -> execute(fake, null, 60, List.of("touch", removed.toString())));

        assertThat(result.exitCode()).isEqualTo(130);
        assertThat(result.stderr()).isEqualTo("Cancelled before container start");
        assertThat(result.notRunReason()).isEqualTo("Cancelled before container start");
        assertThat(started).as("client started").doesNotExist();
        assertThat(removed).as("nothing to remove").doesNotExist();
    }

    /**
     * Cancelling a run may interrupt its thread. The interruption must survive the container removal (else the caller
     * never learns about it), and the removal must still be awaited instead of given up at once on the interrupted
     * thread.
     */
    @Test
    void execute_interrupted_keepsTheInterruptFlagAndWaitsForTheContainerRemoval(@TempDir Path dir)
            throws Exception {
        Path started = dir.resolve("started");
        Path removed = dir.resolve("removed");
        Path pid = dir.resolve("pid");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo $$ > '" + pid + "'\ntouch '" + started
                + "'\nexec sleep 30\n");
        List<String> remove = List.of("/bin/sh", "-c", "sleep 0.5 && touch \"$1\"", "fake-docker-rm",
                removed.toString());
        AtomicReference<CommandResult> result = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        AtomicBoolean removedBeforeReturn = new AtomicBoolean();

        Thread worker = Thread.ofPlatform().start(() -> {
            result.set(execute(fake, null, 60, remove));
            removedBeforeReturn.set(Files.exists(removed));
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        });
        awaitFile(started);
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(20));

        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get().exitCode()).isEqualTo(130);
        assertThat(result.get().stderr()).contains("Command interrupted");
        assertThat(interruptedAfterReturn).as("interrupt flag still set for the caller").isTrue();
        assertThat(removedBeforeReturn).as("container removal awaited before returning").isTrue();
        assertThat(traceworks.core.testing.BackgroundChild.endsWithin(
                Long.parseLong(Files.readString(pid).trim()), java.time.Duration.ofSeconds(5)))
                .as("the client was ended").isTrue();
    }

    /** Ends a client that hangs by a cancel of its run, or by an interrupt of its thread, and waits for the result. */
    private static CommandResult endedEarly(Path dir, boolean byCancel, List<String> remove, Runnable unconfirmed)
            throws Exception {
        Path started = dir.resolve("started");
        Files.deleteIfExists(started);
        Path fake = Files.writeString(dir.resolve("hangs.sh"), "touch '" + started + "'\nexec sleep 30\n");
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        AtomicReference<CommandResult> result = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> result.set(ProcessTracking.bind(context.processTracker(),
                () -> ContainerProcess.execute(List.of("/bin/sh", fake.toString()), ENVIRONMENT, null, 60, remove,
                        unconfirmed, CommandRunner.OUTPUT_LIMIT_BYTES))));
        awaitFile(started);
        if (byCancel) {
            context.cancel();
        } else {
            worker.interrupt();
        }
        worker.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(worker.isAlive()).as("container client still running").isFalse();
        return result.get();
    }

    /**
     * A cancel or an interrupt ends the client as a timeout does: a removal that fails then is reported, so the run
     * knows the container may still run, and a confirmed one is not.
     */
    @Test
    void execute_reportsARemovalThatIsNotConfirmed_alsoAfterACancelOrAnInterrupt(@TempDir Path dir) throws Exception {
        AtomicInteger unconfirmed = new AtomicInteger();

        assertThat(endedEarly(dir, true, List.of("true"), unconfirmed::incrementAndGet).exitCode()).isEqualTo(130);
        assertThat(unconfirmed).as("cancelled, the removal succeeded").hasValue(0);
        assertThat(endedEarly(dir, true, List.of("false"), unconfirmed::incrementAndGet).exitCode()).isEqualTo(130);
        assertThat(unconfirmed).as("cancelled, the removal failed").hasValue(1);

        assertThat(endedEarly(dir, false, List.of("true"), unconfirmed::incrementAndGet).exitCode()).isEqualTo(130);
        assertThat(unconfirmed).as("interrupted, the removal succeeded").hasValue(1);
        assertThat(endedEarly(dir, false, List.of("false"), unconfirmed::incrementAndGet).exitCode()).isEqualTo(130);
        assertThat(unconfirmed).as("interrupted, the removal failed").hasValue(2);
    }

    /**
     * The client of a container does not rely on the cancel having called its stop handle: a run that counts as
     * cancelled ends the client at the next look, long before the timeout, and the result is a cancel's.
     */
    @Test
    void execute_endsTheClientOfARunThatCountsAsCancelled_alsoWhenNothingCalledItsStopHandle(@TempDir Path dir)
            throws Exception {
        Path removed = dir.resolve("removed");
        Path pid = dir.resolve("pid");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "echo $$ > '" + pid + "'\nexec sleep 30\n");
        AtomicBoolean cancelled = new AtomicBoolean();
        ProcessTracking.Tracker tracker = new ProcessTracking.Tracker() {
            @Override
            public void started(ProcessTracking.StopHandle stop) {
                cancelled.set(true);
            }

            @Override
            public void finished(ProcessTracking.StopHandle stop) {
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };

        CommandResult result = ProcessTracking.bind(tracker,
                () -> execute(fake, null, 8, List.of("touch", removed.toString())));

        assertThat(result.exitCode()).isEqualTo(130);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.durationMs()).as("ended at the next look, not at the timeout").isLessThan(6_000);
        assertThat(removed).as("container removed").exists();
        assertThat(traceworks.core.testing.BackgroundChild.endsWithin(
                Long.parseLong(Files.readString(pid).trim()), java.time.Duration.ofSeconds(5)))
                .as("the client was ended").isTrue();
    }

    /**
     * An attached client hands SIGTERM to its container and ends when the container has stopped. The removal waits
     * for that end, within the grace: here a client that takes a second, and a remove command that looks whether it
     * was through.
     */
    @Test
    void execute_timeout_waitsForAClientThatTakesItsTimeToEnd_beforeItRemovesTheContainer(@TempDir Path dir)
            throws Exception {
        Path started = dir.resolve("started");
        Path stopped = dir.resolve("stopped");
        Path removedAfterTheStop = dir.resolve("removed-after-the-stop");
        Path fake = Files.writeString(dir.resolve("fake-docker.sh"), "exec >/dev/null 2>&1\n"
                + "trap 'trap \"\" TERM; sleep 1; touch \"" + stopped + "\"; exit 0' TERM\n"
                + "sleep 30 &\ntouch '" + started + "'\nwait\n");
        List<String> remove = List.of("/bin/sh", "-c", "[ -e \"$1\" ] && touch \"$2\"", "fake-docker-rm",
                stopped.toString(), removedAfterTheStop.toString());

        CommandResult result = execute(fake, null, 1, remove);

        assertThat(started).as("the client ran").exists();
        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(removedAfterTheStop).as("the client had ended when the container was removed").exists();
    }

    /**
     * The result holds each stream to its end, not what the reader happened to have when the client ended: here the
     * last line of a stream is written a moment after the client's end, by a child that still holds only that stream.
     * The client ends a moment after its own last line, so that the reader waits in a read when it does: the JDK
     * closes a stream nobody reads at that instant, with what is still to come.
     */
    @Test
    void execute_readsEachStreamToItsEnd_beforeItReturnsTheResult(@TempDir Path dir) throws IOException {
        Path lateStdout = Files.writeString(dir.resolve("late-stdout.sh"),
                "(exec 2>&-; sleep 0.8; echo late) &\necho early\nsleep 0.3\n");
        Path lateStderr = Files.writeString(dir.resolve("late-stderr.sh"),
                "(exec >&-; sleep 0.8; echo late >&2) &\necho early >&2\nsleep 0.3\n");

        CommandResult stdout = execute(lateStdout, null, 10, List.of("true"));
        CommandResult stderr = execute(lateStderr, null, 10, List.of("true"));

        assertThat(stdout.exitCode()).isZero();
        assertThat(stdout.stdout()).isEqualTo("early\nlate\n");
        assertThat(stderr.exitCode()).isZero();
        assertThat(stderr.stderr()).isEqualTo("early\nlate\n");
    }

    @Test
    void execute_clientThatCannotStart_endsWithExit1AndTheReason(@TempDir Path dir) {
        CommandResult result = ContainerProcess.execute(List.of(dir.resolve("no-such-docker").toString()),
                ENVIRONMENT, null, 10, List.of("true"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("Error: ");
        assertThat(result.notRunReason()).startsWith("the container client could not start: ");
    }

    private static void awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(file).as("fake container started").exists();
    }
}
