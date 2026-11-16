package traceworks.adapters.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** What a runner keeps of a stream: all of it up to the limit, then the first bytes, cut between two characters. */
class BoundedOutputTest {

    private static BoundedOutput drained(int limit, byte[] bytes) throws InterruptedException {
        BoundedOutput output = new BoundedOutput(limit);
        output.drain(new ByteArrayInputStream(bytes), "test-output").join(5000);
        return output;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void belowAndAtTheLimit_theTextIsWhole_andNotExceeded() throws InterruptedException {
        BoundedOutput below = drained(8, utf8("abcdefg"));
        BoundedOutput at = drained(8, utf8("abcdefgh"));

        assertThat(below.text()).isEqualTo("abcdefg");
        assertThat(below.exceeded()).isFalse();
        assertThat(at.text()).isEqualTo("abcdefgh");
        assertThat(at.exceeded()).as("exactly the limit is still whole").isFalse();
    }

    @Test
    void bytes_areTheStreamUndecoded_andCutLikeTheText() throws InterruptedException {
        byte[] raw = {'a', (byte) 0xFF, 'b'};

        assertThat(drained(8, raw).bytes()).isEqualTo(raw);
        assertThat(drained(4, utf8("abc\u00e9")).bytes()).isEqualTo(utf8("abc"));
    }

    @Test
    void beyondTheLimit_keepsTheFirstBytes_andIsExceeded() throws InterruptedException {
        BoundedOutput output = drained(8, utf8("abcdefghi"));

        assertThat(output.text()).isEqualTo("abcdefgh");
        assertThat(output.exceeded()).isTrue();
    }

    /** "é" is two bytes, "€" three and "😀" four: each one that the limit splits is left out whole. */
    @Test
    void aCharacterThatStraddlesTheLimit_isLeftOutWhole() throws InterruptedException {
        assertThat(drained(8, utf8("abcdefgé!")).text()).isEqualTo("abcdefg");
        assertThat(drained(8, utf8("abcdef€!")).text()).isEqualTo("abcdef");
        assertThat(drained(8, utf8("abcde€!")).text()).as("one that fits just").isEqualTo("abcde€");
        assertThat(drained(8, utf8("abcde😀!")).text()).isEqualTo("abcde");
        assertThat(drained(8, utf8("abcd😀!")).text()).isEqualTo("abcd😀");
    }

    /** Bytes that are no UTF-8 are not taken for a character: they decode as before, to U+FFFD. */
    @Test
    void bytesThatAreNoUtf8_areCutAtTheLimit() throws InterruptedException {
        byte[] continuations = {'a', 'b', 'c', 'd', (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 'x'};

        assertThat(drained(8, continuations).text()).isEqualTo("abcd����");
    }

    /** Below the limit, bytes that are no UTF-8 decode as they did before the limit: to U+FFFD. */
    @Test
    void belowTheLimit_decodesAsBefore() throws InterruptedException {
        byte[] cut = {'a', (byte) 0xC3};

        assertThat(drained(8, cut).text()).isEqualTo("a�");
    }

    /** Everything after the limit is read and dropped, so the writer never blocks on a full pipe. */
    @Test
    void afterTheLimit_theStreamIsReadToItsEnd() throws InterruptedException {
        AtomicLong left = new AtomicLong(10_000_000);
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return left.getAndDecrement() > 0 ? 'y' : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                long n = Math.min(len, left.get());
                if (n <= 0) return -1;
                left.addAndGet(-n);
                java.util.Arrays.fill(b, off, off + (int) n, (byte) 'y');
                return (int) n;
            }
        };
        BoundedOutput output = new BoundedOutput(1024);

        Thread reader = output.drain(endless, "test-output");
        reader.join(10_000);

        assertThat(reader.isAlive()).isFalse();
        assertThat(left.get()).as("read to its end").isLessThanOrEqualTo(0);
        assertThat(output.text()).hasSize(1024);
        assertThat(output.exceeded()).isTrue();
    }

    @Test
    void aStreamThatFails_keepsWhatCameBefore() throws InterruptedException {
        InputStream failing = new InputStream() {
            private boolean sent;

            @Override
            public int read() throws IOException {
                if (!sent) {
                    sent = true;
                    return 'a';
                }
                throw new IOException("Stream closed");
            }
        };
        BoundedOutput output = new BoundedOutput(8);

        output.drain(failing, "test-output").join(5000);

        assertThat(output.text()).isEqualTo("a");
        assertThat(output.exceeded()).isFalse();
    }

    /**
     * A process ends while the reader is between two reads, and a child it left behind writes afterwards. The JDK
     * closes the stream of a process at its end with what is in the pipe at that instant, unless a thread holds the
     * stream; the reader holds it from before {@code drain} returns, so the child's line is read.
     *
     * <p>The shell waits for the end of its stdin, which comes after {@code drain}. The test holds the output, so the
     * reader stops after its first read, where it keeps the bytes, until the shell has ended and the child has
     * written.
     */
    @Test
    void aProcessThatEndsBetweenTwoReads_isReadToTheEndOfItsOutput(@TempDir Path dir) throws Exception {
        Path written = dir.resolve("late-written");
        Process process = new ProcessBuilder("/bin/sh", "-c",
                "read go; (trap '' PIPE; sleep 0.2; echo late; touch \"$0\") 2>/dev/null & echo first",
                written.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BoundedOutput output = new BoundedOutput(1024);

        Thread reader;
        synchronized (output) {
            reader = output.drain(process.getInputStream(), "test-output");
            process.getOutputStream().close();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("the shell ended").isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(written) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(written).as("the child wrote its line").exists();
        }
        reader.join(10_000);

        // Protects the platforms where the stream of a process is the JDK's ProcessPipeInputStream, whose
        // processExited() is synchronized on the stream: Linux and macOS with a JDK 21. Elsewhere this fails too,
        // which is the point: the reading then has the old loss.
        assertThat(reader.isAlive()).isFalse();
        assertThat(output.text())
                .as("what a child wrote after its process ended. Without it, the JDK closed the stream at the end of "
                        + "the process although the reader held it: the JDK's locking of a process stream may have "
                        + "changed, and the reader relies on it (the javadoc of BoundedOutput#drain says how)")
                .isEqualTo("first\nlate\n");
    }

    /**
     * A child the process left behind keeps writing. The reading has a bound: after it the stream is closed, the
     * reader ends with the child's next write, nothing more is kept, and the child finds its output closed.
     */
    @Test
    void end_afterItsBound_closesTheStream_soTheReaderEndsAndNothingMoreIsKept(@TempDir Path dir) throws Exception {
        Path closed = dir.resolve("found-its-output-closed");
        Process process = new ProcessBuilder("/bin/sh", "-c",
                "read go; (trap '' PIPE; while echo tick; do sleep 0.05; done; touch \"$0\") 2>/dev/null & echo first",
                closed.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BoundedOutput output = new BoundedOutput(1024 * 1024);
        Thread reader = output.drain(process.getInputStream(), "test-output");
        process.getOutputStream().close();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("the shell ended").isTrue();

        long before = System.nanoTime();
        output.end(300);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        String kept = output.text();

        assertThat(waited).as("the bound, not the child's life").isBetween(300L, 3000L);
        assertThat(kept).startsWith("first\ntick\n");
        reader.join(10_000);
        assertThat(reader.isAlive()).as("the reader still runs").isFalse();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(closed) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(closed).as("the child found its output closed").exists();
        assertThat(output.text()).as("nothing was kept after the bound").isEqualTo(kept);
    }

    /** A stream that ends within the bound is read whole, and the wait ends with it. */
    @Test
    void end_ofAStreamThatEndsInTime_keepsAllOfIt() throws InterruptedException {
        BoundedOutput output = new BoundedOutput(8);
        output.drain(new ByteArrayInputStream(utf8("abc")), "test-output");

        output.end(5000);

        assertThat(output.text()).isEqualTo("abc");
    }
}
