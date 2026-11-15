package traceworks.adapters.host;

import traceworks.core.port.HookRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an operator's hook on the host: the program and its arguments as given, no shell, the operator's whole
 * environment (it is the operator's own code; only the {@linkplain ChildProcessEnv#scrub scrubbed} variables are
 * removed), the payload on stdin, standard output discarded, the tail of standard error kept. A hook that runs past
 * its timeout, or that never reads its input, is ended with everything it started ({@link ProcessTree}) and reported
 * with exit code 124; one that cannot start with 127.
 */
public final class HostHookRunner implements HookRunner {

    public static final int TIMEOUT_EXIT_CODE = 124;
    public static final int NOT_STARTED_EXIT_CODE = 127;

    private final Map<String, String> environment;

    /** A runner whose hooks start from the JVM's environment. */
    public HostHookRunner() {
        this(System.getenv());
    }

    public HostHookRunner(Map<String, String> inheritedEnvironment) {
        this.environment = Map.copyOf(inheritedEnvironment);
    }

    @Override
    public Outcome run(List<String> argv, byte[] stdin, String workingDirectory, Duration timeout) {
        long start = System.nanoTime();
        Process process = null;
        try {
            ProcessBuilder pb = ChildProcessEnv.builder(argv, workingDirectory, environment);
            pb.redirectErrorStream(false);   // the host runner merges the streams; a hook's stderr is kept apart
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            StringBuilder stderr = new StringBuilder();
            Thread reader = drain(process.getErrorStream(), stderr);
            Thread writer = feed(process.getOutputStream(), stdin);   // a hook that never reads must not block us
            boolean ended = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!ended) {
                ProcessTree.terminate(process);
                process.waitFor(ProcessTree.GRACE.toSeconds() + 2, TimeUnit.SECONDS);
                reader.join(2000);
                return new Outcome(TIMEOUT_EXIT_CODE, millisSince(start), tail(stderr));
            }
            reader.join(5000);
            writer.join(1000);
            return new Outcome(process.exitValue(), millisSince(start), tail(stderr));
        } catch (IOException e) {
            return new Outcome(NOT_STARTED_EXIT_CODE, millisSince(start), tail(new StringBuilder(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                ProcessTree.terminate(process);
            }
            return new Outcome(TIMEOUT_EXIT_CODE, millisSince(start), "interrupted");
        }
    }

    private static Thread feed(OutputStream stream, byte[] payload) {
        Thread thread = new Thread(() -> {
            try (OutputStream out = stream) {
                out.write(payload);
            } catch (IOException ignored) {
                // the hook closed its input early, or was ended: its business
            }
        }, "hook-stdin");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Thread drain(InputStream stream, StringBuilder into) {
        Thread thread = new Thread(() -> {
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (into) {
                        into.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                        if (into.length() > STDERR_TAIL_CHARS * 2) {
                            into.delete(0, into.length() - STDERR_TAIL_CHARS);
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process ended: what was read is what there is
            }
        }, "hook-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String tail(StringBuilder stderr) {
        synchronized (stderr) {
            String text = stderr.toString();
            return text.length() <= STDERR_TAIL_CHARS ? text : text.substring(text.length() - STDERR_TAIL_CHARS);
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
