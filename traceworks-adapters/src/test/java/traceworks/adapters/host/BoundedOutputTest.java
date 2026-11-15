package traceworks.adapters.host;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
}
