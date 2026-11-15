package traceworks.cli;

import traceworks.adapters.host.OwnSession;
import traceworks.core.app.RunServices;

import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * What stops a run from its own terminal, while {@code run} or {@code resume} executes it.
 *
 * <ul>
 *   <li><b>Ctrl-C once</b> pauses: the run finishes the step or the loop item in flight and parks, and
 *       {@code resume} goes on later.</li>
 *   <li><b>Ctrl-C again</b> cancels, as {@code traceworks cancel} does: final.</li>
 *   <li><b>The end of the process</b> (SIGTERM, a closed terminal, a shutdown) cannot wait for a step: the step in
 *       flight is ended and the run is left {@code INTERRUPTED}, which {@code resume} continues.</li>
 * </ul>
 *
 * <p>Ctrl-C is taken by a signal handler ({@code sun.misc.Signal}, reached by reflection: the class is not part of
 * the Java platform, and a runtime may lack it), because a second Ctrl-C cannot be seen once the JVM shuts down. The
 * pause is of use only where a step's process does not get the terminal's Ctrl-C itself ({@link OwnSession}). Where
 * either is missing, Ctrl-C cancels as it always did, the end of the process cancels too, and the command says so
 * once.
 */
public final class Interrupts {

    /** How long a cancel from the shutdown hook gives the run to write its end. */
    private static final long WAIT_SECONDS = 10;

    static final String PAUSING = "traceworks: pausing after the step in flight; Ctrl-C again cancels";
    static final String CANCELLING = "traceworks: cancelling the run";

    /** The exit code of a process that Ctrl-C ended: 128 + SIGINT. */
    private static final int EXIT_SIGINT = 130;

    /** The commands that take Ctrl-C now; one in a command line, several where tests run commands side by side. */
    private static final Set<CtrlC> LISTENING = ConcurrentHashMap.newKeySet();
    /** Puts back what handled SIGINT before the first listener; {@code null} while nobody listens. */
    private static Runnable restore;
    private static final AtomicBoolean NOTED = new AtomicBoolean();

    private Interrupts() {
    }

    /** What Ctrl-C and the end of the process do to the runs this process executes. */
    interface Runs {

        /** Asks every run to pause; returns how many runs there are. */
        int pause();

        void cancel();

        /** Leaves every run interrupted: nothing more is recorded, the step in flight is ended. */
        void interrupt();
    }

    /** How this process takes SIGINT. */
    enum Mode {
        /** The handler is installed: Ctrl-C pauses, then cancels. */
        HANDLED,
        /** The process was started with SIGINT ignored (in the background, under nohup): Ctrl-C does nothing. */
        IGNORED,
        /** No handler can be installed, or a step's process would get Ctrl-C too: Ctrl-C cancels, as before. */
        CANCELS
    }

    /** One command's Ctrl-C: the first pauses its runs, the second cancels them, any further changes nothing. */
    static final class CtrlC implements Runnable {

        private final Runs runs;
        private final PrintStream err;
        private final IntConsumer exit;
        private final AtomicInteger presses = new AtomicInteger();

        /** @param exit ends the process with this code, when Ctrl-C comes while no run executes */
        CtrlC(Runs runs, PrintStream err, IntConsumer exit) {
            this.runs = runs;
            this.err = err;
            this.exit = exit;
        }

        @Override
        public void run() {
            int press = presses.incrementAndGet();
            if (press == 1) {
                if (runs.pause() == 0) {
                    // Nothing to pause (the run has not started, or has ended): Ctrl-C ends the command, as ever
                    exit.accept(EXIT_SIGINT);
                    return;
                }
                err.println(PAUSING);
            } else if (press == 2) {
                err.println(CANCELLING);
                runs.cancel();
            }
        }
    }

    /** Runs {@code call} with Ctrl-C and the end of the process handled for the runs {@code services} executes. */
    public static <T> T guard(RunServices services, PrintStream err, Supplier<T> call) {
        Runs runs = new Runs() {
            @Override
            public int pause() {
                return services.pauseActiveRuns();
            }

            @Override
            public void cancel() {
                services.cancelActiveRuns();
            }

            @Override
            public void interrupt() {
                services.interruptActiveRuns();
            }
        };
        return guard(runs, err, OwnSession.available(), call);
    }

    /**
     * @param shielded whether a step's process is kept from the terminal's Ctrl-C; without that a pause would not
     *                 finish the step in flight, so Ctrl-C stays a cancel
     */
    static <T> T guard(Runs runs, PrintStream err, boolean shielded, Supplier<T> call) {
        CtrlC ctrlC = new CtrlC(runs, err, code -> Runtime.getRuntime().exit(code));
        Mode mode = shielded ? listen(ctrlC) : Mode.CANCELS;
        if (mode == Mode.CANCELS && NOTED.compareAndSet(false, true)) {
            err.println("traceworks: note: Ctrl-C cancels the run here and cannot pause it ("
                    + (shielded ? "this Java runtime gives no signal handling"
                    : "there is no setsid program to keep a step's process from the terminal's Ctrl-C")
                    + "); to pause, use 'traceworks pause' from another terminal");
        }
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean ending = new AtomicBoolean();
        Thread hook = new Thread(() -> {
            ending.set(true);
            if (mode == Mode.CANCELS) {
                runs.cancel();
                try {
                    returned.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                // The process is told to end and cannot wait for a step: the run stays resumable
                runs.interrupt();
            }
        }, "traceworks-interrupt");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return call.get();
        } catch (RuntimeException | Error e) {
            if (ending.get() && mode != Mode.CANCELS) {
                // The run was interrupted on purpose, and the record says nothing more: neither does this command.
                // The JVM ends once the hook has; until then this thread must not report a failure.
                waitForTheEnd();
            }
            throw e;
        } finally {
            returned.countDown();
            if (mode == Mode.HANDLED) {
                forget(ctrlC);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The JVM is going down already: the hook finishes now that the call has returned
            }
        }
    }

    private static void waitForTheEnd() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- SIGINT ----

    /** Lets {@code ctrlC} take SIGINT from now on; the first listener installs the handler. */
    private static synchronized Mode listen(CtrlC ctrlC) {
        if (LISTENING.isEmpty()) {
            Mode mode = install();
            if (mode != Mode.HANDLED) {
                return mode;
            }
        }
        LISTENING.add(ctrlC);
        return Mode.HANDLED;
    }

    /** {@code ctrlC} no longer takes SIGINT; the last listener puts back what handled it before. */
    private static synchronized void forget(CtrlC ctrlC) {
        if (LISTENING.remove(ctrlC) && LISTENING.isEmpty() && restore != null) {
            restore.run();
            restore = null;
        }
    }

    /**
     * Installs the handler of SIGINT, which hands each signal to every listener, on a thread of the JVM.
     *
     * @return {@link Mode#CANCELS} when the runtime has no {@code sun.misc.Signal} or refuses the handler (it runs
     *         with {@code -Xrs}); {@link Mode#IGNORED} when the process ignores SIGINT, which the JVM leaves so
     */
    private static Mode install() {
        try {
            Class<?> signalType = Class.forName("sun.misc.Signal");
            Class<?> handlerType = Class.forName("sun.misc.SignalHandler");
            Object signal = signalType.getConstructor(String.class).newInstance("INT");
            InvocationHandler dispatch = (proxy, method, args) -> switch (method.getName()) {
                case "handle" -> {
                    // First: a process this Ctrl-C killed while it was starting is started again
                    OwnSession.terminalInterrupt();
                    LISTENING.forEach(CtrlC::run);
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "traceworks SIGINT handler";
            };
            Object handler = Proxy.newProxyInstance(Interrupts.class.getClassLoader(), new Class<?>[] {handlerType},
                    dispatch);
            Method handle = signalType.getMethod("handle", signalType, handlerType);
            Object before = handle.invoke(null, signal, handler);
            if (before == handlerType.getField("SIG_IGN").get(null)) {
                return Mode.IGNORED;
            }
            restore = () -> {
                try {
                    handle.invoke(null, signal, before);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // The handler stays; with no listener left it does nothing
                }
            };
            return Mode.HANDLED;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Mode.CANCELS;
        }
    }
}
