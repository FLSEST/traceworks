package traceworks.adapters.host;

import traceworks.core.port.CommandRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * What a runner keeps of one stream of a process: everything up to a limit (the operator's {@code output_limit_mib},
 * {@link CommandRunner#OUTPUT_LIMIT_BYTES} by default, the same for every runner of a run), and of a stream that goes
 * beyond it only the first bytes. The host command runner, the
 * container client and a git call read stdout and stderr with it, each on a thread of its own, so a full pipe never
 * blocks the process.
 *
 * <ul>
 *   <li><b>Up to the limit</b> the text is the stream's bytes decoded as UTF-8, as it always was: a byte that is no
 *       UTF-8 becomes U+FFFD.</li>
 *   <li><b>Beyond it</b> the stream counts as {@linkplain #exceeded() exceeded}, and its text is the first
 *       {@code limit} bytes, cut before a character that the limit would split. The rest is still read, and dropped:
 *       the process does not block on a full pipe while the runner stops it, so it ends on SIGTERM as on a
 *       timeout.</li>
 * </ul>
 *
 * The reader and the runner may look at the same time; what the runner reads is what came so far.
 */
public final class BoundedOutput {

    private static final int CHUNK = 8192;

    private final int limit;
    private byte[] bytes = new byte[CHUNK];
    private int count;
    private volatile boolean exceeded;
    /** Set by {@link #end} when the stream outlasted its bound: nothing more is kept. */
    private boolean ended;
    /** Set by the reader when it read the stream to its end; then {@link #end} cuts nothing. */
    private boolean complete;
    /** What {@link #drain} reads, and its reader; {@code null} before. */
    private volatile InputStream stream;
    private volatile Thread reader;

    /** @param limit how many bytes of the stream are kept at most */
    public BoundedOutput(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("A limit is never negative: " + limit);
        }
        this.limit = limit;
    }

    /**
     * Reads {@code stream} to its end on a virtual thread named {@code name}, and closes it then. A stream that fails
     * (the process was ended) ends the reading; what came before is kept.
     *
     * <p><b>The end is the end of the pipe, not of the process.</b> When a process ends, the JDK takes what is in the
     * pipe at that instant and closes it ({@code ProcessImpl.ProcessPipeInputStream.processExited}, a synchronized
     * method of the stream): whatever a child the process left behind writes afterwards is lost, and the child gets
     * SIGPIPE. A read holds the stream, so that only happened to a reader between two reads. The reader here holds
     * the stream for all of its reading, so the JDK waits until the last writer has closed the pipe. This rests on
     * how the JDK locks, which no documentation promises; {@code BoundedOutputTest} fails when it changes.
     *
     * <p>The reader holds the stream when this method returns. A process that ended before that (within the instant
     * of its start) is still read up to its own end only; a caller that needs more starts the reading before the
     * process gets its work, as the host command runner does with the script on stdin. The container client and a
     * git call do not: both keep that window.
     *
     * <p>A caller waits for the end with {@link #end}, which bounds the reading. The hook runner and the SSH signer
     * read with loops of their own and still have the loss: of a hook only the end of its stderr is kept, for a
     * message, and of {@code ssh-keygen} only what it says when it fails.
     */
    public Thread drain(InputStream stream, String name) {
        CountDownLatch held = new CountDownLatch(1);
        Thread reader = Thread.ofVirtual().name(name).start(() -> {
            byte[] chunk = new byte[CHUNK];
            synchronized (stream) {
                held.countDown();
                try (stream) {
                    for (int n = stream.read(chunk); n != -1; n = stream.read(chunk)) {
                        keep(chunk, n);
                    }
                    reachedTheEnd();
                } catch (IOException ignored) {
                    // the process was ended: on a timeout, a cancel or this limit
                }
            }
        });
        awaitUninterrupted(held);
        this.stream = stream;
        this.reader = reader;
        return reader;
    }

    /**
     * Waits up to {@code millis} for the end of the stream that {@link #drain} reads, and ends the reading when it has
     * not come: a process that still holds the pipe then (a child the command left behind, such as a server that
     * logs) must not be read for the rest of the run. Nothing that arrives afterwards is kept, the stream is closed,
     * and the next write of that process fails (EPIPE, or it dies of SIGPIPE). The reader ends with that write: a
     * read that waits is not woken by the close, so the reader of a process that holds the pipe and never writes
     * stays until that process ends, and keeps nothing. This method does not wait for that reader: it returns at
     * the bound either way, and the stream then counts as {@linkplain #cut() cut}.
     *
     * @throws InterruptedException if the calling thread is interrupted while it waits; the reading goes on
     */
    public void end(long millis) throws InterruptedException {
        Thread reading = reader;
        if (reading == null) {
            return;
        }
        reading.join(millis);
        if (!reading.isAlive()) {
            return;
        }
        synchronized (this) {
            if (complete) {
                return;   // the stream ended in the instant after the wait: nothing is cut
            }
            ended = true;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // closed already
        }
    }

    /** Waits for the reader to hold its stream, which takes no longer than its start; an interrupt stays set. */
    private static void awaitUninterrupted(CountDownLatch held) {
        boolean interrupted = false;
        while (true) {
            try {
                held.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Whether {@link #end} ended the reading at its bound: the stream had not ended by then, so something still held
     * it, and what that wrote afterwards is not kept. {@code false} for a stream that was read to its end, also
     * beyond the limit, and for one that failed.
     */
    public synchronized boolean cut() {
        return ended;
    }

    private synchronized void reachedTheEnd() {
        complete = true;
    }

    /** How many bytes of the stream are kept at most. */
    public int limit() {
        return limit;
    }

    /** Whether the stream brought more than the limit: its text then holds only the first bytes. */
    public boolean exceeded() {
        return exceeded;
    }

    /**
     * The stream so far, decoded as UTF-8; once {@linkplain #exceeded() exceeded}, the first {@code limit} bytes
     * without a character that the limit splits.
     */
    public synchronized String text() {
        int end = exceeded ? boundary(bytes, count) : count;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** The bytes {@link #text()} decodes, as they came: for a caller that needs them undecoded. */
    public synchronized byte[] bytes() {
        return Arrays.copyOf(bytes, exceeded ? boundary(bytes, count) : count);
    }

    private synchronized void keep(byte[] chunk, int n) {
        if (exceeded || ended) {
            return;
        }
        int room = limit - count;
        int taken = Math.min(n, room);
        if (taken > 0) {
            if (count + taken > bytes.length) {
                bytes = Arrays.copyOf(bytes, (int) Math.min(limit, Math.max(2L * bytes.length, count + taken)));
            }
            System.arraycopy(chunk, 0, bytes, count, taken);
            count += taken;
        }
        if (n > room) {
            exceeded = true;
        }
    }

    /**
     * Where the first {@code length} bytes end without a split character: before the last character when its lead
     * byte is there but not all of its continuation bytes. Bytes that are no UTF-8 count as they are.
     */
    static int boundary(byte[] bytes, int length) {
        int lead = length - 1;
        while (lead >= 0 && lead > length - 4 && (bytes[lead] & 0xC0) == 0x80) {
            lead--;
        }
        if (lead < 0) {
            return length;
        }
        int b = bytes[lead] & 0xFF;
        int size = b >= 0xF0 && b < 0xF8 ? 4 : b >= 0xE0 && b < 0xF0 ? 3 : b >= 0xC0 && b < 0xE0 ? 2 : 1;
        return lead + size > length ? lead : length;
    }
}
