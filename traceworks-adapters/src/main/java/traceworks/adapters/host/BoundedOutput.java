package traceworks.adapters.host;

import traceworks.core.port.CommandRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * What a runner keeps of one stream of a process: everything up to a limit (the operator's {@code output_limit_mib},
 * {@link CommandRunner#OUTPUT_LIMIT_BYTES} by default, the same for every runner of a run), and of a stream that goes
 * beyond it only the first bytes. The host command runner and the
 * container client read stdout and stderr with it, each on a thread of its own, so a full pipe never blocks the process.
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
     */
    public Thread drain(InputStream stream, String name) {
        return Thread.ofVirtual().name(name).start(() -> {
            byte[] chunk = new byte[CHUNK];
            try (stream) {
                for (int n = stream.read(chunk); n != -1; n = stream.read(chunk)) {
                    keep(chunk, n);
                }
            } catch (IOException ignored) {
                // the process was ended: on a timeout, a cancel or this limit
            }
        });
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
        if (exceeded) {
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
