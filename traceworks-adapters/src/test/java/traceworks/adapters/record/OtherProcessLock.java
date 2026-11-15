package traceworks.adapters.record;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A lock on a file taken by another process, as a writer in another process takes it: a second JVM started from the
 * test classpath, which locks the way this JVM does (fcntl on Linux). Every wait has a time limit, so a lock that is
 * never released fails the test with a message instead of hanging the suite.
 */
public final class OtherProcessLock implements AutoCloseable {

    /** How long any wait for the other process may take. */
    static final Duration LIMIT = Duration.ofSeconds(30);

    private static final String HOLD = "hold";
    private static final String TRY = "try";
    private static final String LOCKED = "locked";

    private final Process process;

    private OtherProcessLock(Process process) {
        this.process = process;
    }

    /** Starts a process that takes the lock on {@code file}, and returns once it holds it. */
    static OtherProcessLock hold(Path file) throws IOException, InterruptedException {
        Process process = start(HOLD, file);
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8));
        String line;
        try {
            line = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (IOException e) {
                    return null;
                }
            }).get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            process.destroyForcibly();
            throw new AssertionError("the other process did not take the lock on " + file + " within " + LIMIT);
        } catch (ExecutionException e) {
            process.destroyForcibly();
            throw new AssertionError("the other process could not be read", e);
        }
        if (!LOCKED.equals(line)) {
            process.destroyForcibly();
            throw new AssertionError("the other process did not say it holds the lock on " + file + "; it said: "
                    + line);
        }
        return new OtherProcessLock(process);
    }

    /** Whether another process gets the lock on {@code file} at once; it lets go of it again right away. */
    static boolean free(Path file) throws IOException, InterruptedException {
        Process process = start(TRY, file);
        process.getOutputStream().close();
        awaitEnd(process, "try the lock on " + file);
        return process.exitValue() == 0;
    }

    /** Lets go of the lock: closes the other process's stdin and waits for it to end. */
    @Override
    public void close() throws InterruptedException {
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // it ended already: the wait below says how
        }
        awaitEnd(process, "let go of the lock");
        if (process.exitValue() != 0) {
            throw new AssertionError("the other process ended with exit " + process.exitValue());
        }
    }

    private static Process start(String mode, Path file) throws IOException {
        String java = ProcessHandle.current().info().command().orElse(null);
        assumeTrue(java != null, "the java binary of this JVM is not known");
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), OtherProcessLock.class.getName(),
                mode, file.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private static void awaitEnd(Process process, String what) throws InterruptedException {
        if (!process.waitFor(LIMIT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the other process did not " + what + " within " + LIMIT);
        }
    }

    /**
     * The other process: {@code hold <file>} takes the lock, prints {@value #LOCKED} and keeps the lock until its
     * stdin closes; {@code try <file>} ends with exit 0 when it got the lock at once, with 1 when not.
     */
    public static void main(String[] args) throws IOException {
        try (FileChannel channel = FileChannel.open(Path.of(args[1]), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            if (HOLD.equals(args[0])) {
                try (FileLock lock = channel.lock()) {
                    System.out.println(LOCKED);
                    System.out.flush();
                    System.in.readAllBytes();
                }
                return;
            }
            FileLock lock = channel.tryLock();
            if (lock == null) {
                System.exit(1);
            }
            lock.release();
        }
    }
}
