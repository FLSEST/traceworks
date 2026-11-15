package traceworks.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads a secret value a person gives to a command: typed on a terminal without being shown, or piped into the
 * standard input. A value is never an argument, since arguments stay in the shell's history and show in the process
 * list.
 */
@FunctionalInterface
public interface SecretReader {

    /** The most a piped value may hold; a credential is far shorter. */
    int MAX_BYTES = 64 * 1024;

    /**
     * The value.
     *
     * @param prompt what a terminal shows before the person types; a pipe shows nothing
     * @return the value; {@code null} when the input ended without one
     * @throws IllegalArgumentException when the input is no value, such as one longer than {@value #MAX_BYTES} bytes
     * @throws UncheckedIOException     when the input cannot be read
     */
    String read(String prompt);

    /**
     * A reader of a piped standard input: everything in it is the value, without the one line end a command such as
     * {@code echo} or a file adds. A value of several lines stays as it is, for the caller to refuse.
     */
    static SecretReader piped(InputStream in) {
        return prompt -> {
            byte[] bytes;
            try {
                bytes = in.readNBytes(MAX_BYTES + 1);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the value from the standard input: " + e.getMessage(), e);
            }
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("the standard input holds more than " + MAX_BYTES
                        + " bytes, which is no credential");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.endsWith("\r\n")) {
                text = text.substring(0, text.length() - 2);
            } else if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            return text.isEmpty() ? null : text;
        };
    }
}
