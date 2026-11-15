package traceworks.core.port;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * The operator's stored credentials: the values of secrets and of the AI providers' credentials by name, kept where
 * only the operator can read them. No message of a store shows a value.
 */
public interface CredentialStore {

    /** Where the credentials are kept, for a message: the path of the file. */
    String location();

    /**
     * The stored values by name; empty when nothing is stored yet.
     *
     * @throws IllegalArgumentException when the store cannot be trusted or understood, such as a file others can read
     * @throws UncheckedIOException     when it cannot be read
     */
    Map<String, String> read();

    /**
     * Replaces everything stored by {@code values}, so that a reader sees the old values or the new ones, never a part,
     * and nobody but the operator can read them at any moment. It creates the store when there is none.
     *
     * @throws IllegalArgumentException when the store cannot be kept the operator's alone, or an existing one cannot
     *                                  be trusted
     * @throws UncheckedIOException     when it cannot be written
     */
    void write(Map<String, String> values);
}
