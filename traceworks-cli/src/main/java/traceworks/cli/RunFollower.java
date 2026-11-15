package traceworks.cli;

import traceworks.core.app.RunServices;
import traceworks.core.record.RecordedEvent;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Follows the events of one run in this process: prints each as a line (unless quiet), tells the person how to answer
 * a question that leaves the run waiting, and learns the run's id from its first event, which a blob lookup needs.
 */
public final class RunFollower implements Consumer<RecordedEvent>, AutoCloseable {

    private static final long ROOT_WAIT_MILLIS = 2_000;

    private final RunServices services;
    private final PrintStream out;
    private final boolean quiet;
    private final TerminalFormat format;
    private final WaitingHints hints;
    private final AtomicReference<String> root = new AtomicReference<>();
    /** The run's folder when the command was given one as a path; {@code null} for a run of the home. */
    private final String folder;

    /**
     * @param quiet       print no event lines
     * @param interactive whether a person is asked on the terminal; if not, waiting questions get a hint
     */
    public RunFollower(RunServices services, PrintStream out, boolean quiet, boolean interactive) {
        this(services, out, quiet, interactive, null);
    }

    /**
     * @param folder the run's folder when the command was given one as a path ({@link Context#runFolder}): its texts
     *               are read from there and the hints name it, since the home may know no run of its id, or another
     *               one; {@code null} for a run of the home, which is read and named by its id
     */
    public RunFollower(RunServices services, PrintStream out, boolean quiet, boolean interactive, String folder) {
        this.folder = folder;
        this.services = services;
        this.out = out;
        this.quiet = quiet;
        this.format = new TerminalFormat(this::blobText);
        this.hints = interactive ? null : new WaitingHints(out);
    }

    @Override
    public void accept(RecordedEvent event) {
        if (event.parent() == null) {
            root.compareAndSet(null, event.run());
        }
        if (!quiet) {
            format.line(event).ifPresent(out::println);
        }
        if (hints != null) {
            hints.accept(event, folder != null ? folder : root.get());
        }
    }

    /** The text of a blob of the followed run; empty when it is deleted or the run is not known yet. */
    public Optional<String> blobText(String sha256) {
        String run = folder != null ? folder : rootId();
        if (run == null) {
            return Optional.empty();
        }
        return services.content(run, sha256).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /** The run's id once its first event came; waits a moment for it, then {@code null}. */
    private String rootId() {
        long end = System.nanoTime() + ROOT_WAIT_MILLIS * 1_000_000;
        while (root.get() == null && System.nanoTime() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return root.get();
    }

    @Override
    public void close() {
        // nothing is held
    }
}
