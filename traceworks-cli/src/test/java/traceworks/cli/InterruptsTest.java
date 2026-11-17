package traceworks.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ctrl-C while a run executes: the first pauses, the second cancels. What the end of the process does (SIGTERM) is
 * tested with real signals in the integration tests, which start the jar as a process.
 */
class InterruptsTest {

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final List<Integer> exits = new ArrayList<>();

    /** The runs of a process, as the test scripts them; every call is noted. */
    private static final class FakeRuns implements Interrupts.Runs {
        final List<String> calls = new ArrayList<>();
        final CountDownLatch paused = new CountDownLatch(1);
        int running = 1;

        @Override
        public synchronized int pause() {
            calls.add("pause");
            paused.countDown();
            return running;
        }

        @Override
        public synchronized void cancel() {
            calls.add("cancel");
        }

        @Override
        public synchronized void interrupt() {
            calls.add("interrupt");
        }
    }

    private String printed() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * Waits until {@code text} is printed. The JVM hands a signal to a thread of its own, which prints after it has
     * asked the runs to pause: whoever only waits for the pause can be ahead of the message.
     */
    private void awaitPrinted(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!printed().contains(text) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void theFirstCtrlC_pauses_andSaysHowToCancel() {
        FakeRuns runs = new FakeRuns();
        Interrupts.CtrlC ctrlC = new Interrupts.CtrlC(runs, err, exits::add);

        ctrlC.run();

        assertThat(runs.calls).containsExactly("pause");
        assertThat(printed()).isEqualTo("traceworks: pausing after the step in flight; Ctrl-C again cancels\n");
        assertThat(exits).isEmpty();
    }

    @Test
    void theSecondCtrlC_cancels_andAThirdChangesNothing() {
        FakeRuns runs = new FakeRuns();
        Interrupts.CtrlC ctrlC = new Interrupts.CtrlC(runs, err, exits::add);

        ctrlC.run();
        ctrlC.run();

        assertThat(runs.calls).as("the second press cancels").containsExactly("pause", "cancel");
        assertThat(printed()).endsWith("traceworks: cancelling the run\n");

        ctrlC.run();

        assertThat(runs.calls).as("a third changes nothing").containsExactly("pause", "cancel");
        assertThat(printed()).containsOnlyOnce("cancelling the run");
        assertThat(exits).isEmpty();
    }

    /** Before the run started, or after it ended, there is nothing to pause: Ctrl-C ends the command, as ever. */
    @Test
    void ctrlCWhileNoRunExecutes_endsTheProcessWith130() {
        FakeRuns runs = new FakeRuns();
        runs.running = 0;
        Interrupts.CtrlC ctrlC = new Interrupts.CtrlC(runs, err, exits::add);

        ctrlC.run();

        assertThat(exits).containsExactly(130);
        assertThat(printed()).isEmpty();
    }

    /** A real SIGINT to this JVM reaches the guarded call's runs, and only while the call runs. */
    @Test
    void guard_takesARealSigint_whileTheCallRuns() throws Exception {
        Class<?> signalType = Class.forName("sun.misc.Signal");
        Object sigint = signalType.getConstructor(String.class).newInstance("INT");
        FakeRuns runs = new FakeRuns();

        boolean reached = Interrupts.guard(runs, err, true, () -> {
            try {
                signalType.getMethod("raise", signalType).invoke(null, sigint);
                return runs.paused.await(5, TimeUnit.SECONDS);
            } catch (ReflectiveOperationException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        // A JVM started with SIGINT ignored (a background job) never sees the signal: nothing to test there
        assumeTrue(reached, "this JVM ignores SIGINT");
        assertThat(runs.calls).containsExactly("pause");
        awaitPrinted("pausing after the step in flight");
        assertThat(printed()).contains("pausing after the step in flight");
    }

    /**
     * A command whose call returned takes no further Ctrl-C, while one still running does; once the last returns,
     * what handled SIGINT before is back. That is a probe the test installs first, so no signal of this test can
     * reach the JVM's own handler, which would end the test run.
     */
    @Test
    void guard_whenItsCallReturns_takesNoMoreCtrlC_andTheLastPutsBackTheHandlerItFound() throws Exception {
        Class<?> signalType = Class.forName("sun.misc.Signal");
        Class<?> handlerType = Class.forName("sun.misc.SignalHandler");
        Object sigint = signalType.getConstructor(String.class).newInstance("INT");
        Method handle = signalType.getMethod("handle", signalType, handlerType);
        Object probe = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {handlerType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "probe";
                    default -> null;
                });
        Object original;
        try {
            original = handle.invoke(null, sigint, probe);
        } catch (InvocationTargetException refused) {
            assumeTrue(false, "this JVM takes no SIGINT handler: " + refused.getCause());
            return;
        }
        FakeRuns outer = new FakeRuns();
        FakeRuns inner = new FakeRuns();
        try {
            // A JVM started with SIGINT ignored (a background job) never sees the signal: nothing to test there
            assumeTrue(original != handlerType.getField("SIG_IGN").get(null), "this JVM ignores SIGINT");
            boolean reached = Interrupts.guard(outer, err, true, () -> {
                Interrupts.guard(inner, err, true, () -> null);
                try {
                    signalType.getMethod("raise", signalType).invoke(null, sigint);
                    return outer.paused.await(5, TimeUnit.SECONDS);
                } catch (ReflectiveOperationException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(reached).as("the command still running takes the Ctrl-C").isTrue();
            assertThat(outer.calls).containsExactly("pause");
            assertThat(inner.calls).as("the command that returned takes none").isEmpty();
            assertThat(handle.invoke(null, sigint, probe)).as("the handler from before is back").isSameAs(probe);
        } finally {
            handle.invoke(null, sigint, original);
        }
    }

    /** Where a step's process would get Ctrl-C too, a pause could not finish the step: Ctrl-C stays a cancel. */
    @Test
    void guard_withoutAShield_installsNoHandler_andReturnsWhatTheCallReturns() {
        FakeRuns runs = new FakeRuns();

        String result = Interrupts.guard(runs, err, false, () -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(runs.calls).isEmpty();
    }
}
