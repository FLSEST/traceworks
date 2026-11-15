package traceworks.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretReaderTest {

    private static String piped(String input) {
        return SecretReader.piped(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))).read("ignored");
    }

    @Test
    void piped_theInputIsTheValue_withoutItsOneLineEnd() {
        assertThat(piped("s3cret-value")).isEqualTo("s3cret-value");
        assertThat(piped("s3cret-value\n")).isEqualTo("s3cret-value");
        assertThat(piped("s3cret-value\r\n")).isEqualTo("s3cret-value");
    }

    /** Only one line end goes: what else is there is part of the value, for the caller to judge. */
    @Test
    void piped_keepsEverythingElse() {
        assertThat(piped("one\ntwo\n\n")).isEqualTo("one\ntwo\n");
        assertThat(piped(" padded \n")).isEqualTo(" padded ");
    }

    @Test
    void piped_anEmptyInputIsNoValue() {
        assertThat(piped("")).isNull();
        assertThat(piped("\n")).isNull();
    }

    @Test
    void piped_refusesMoreThanTheLimit() {
        assertThat(piped("x".repeat(SecretReader.MAX_BYTES))).hasSize(SecretReader.MAX_BYTES);

        assertThatThrownBy(() -> piped("x".repeat(SecretReader.MAX_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 65536 bytes")
                .hasMessageNotContaining("xxxx");
    }

    @Test
    void piped_anInputThatCannotBeRead_saysSo() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("gone");
            }
        };

        assertThatThrownBy(() -> SecretReader.piped(broken).read("ignored"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Cannot read the value from the standard input: gone");
    }
}
