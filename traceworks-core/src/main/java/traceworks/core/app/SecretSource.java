package traceworks.core.app;

import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where one start check reads the values of secrets and AI credentials from: {@code credentials.json} and the
 * environment, read on the first need only. A failure is kept as well, so a credentials file that cannot be used is
 * read once and reported once, by the first contributor that needs it; every later one only learns that there are
 * no values.
 */
final class SecretSource {

    private final Supplier<SecretValues> source;
    private boolean read;
    private SecretValues values;

    /**
     * @param source reads the values; it may throw {@link IllegalArgumentException} or {@link UncheckedIOException}
     *               for a credentials file that cannot be used
     */
    SecretSource(Supplier<SecretValues> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * The values, read now if they were not yet.
     *
     * @param what      what needs them, the start of the refusal: {@code the values of the secrets A, B}
     * @param preflight where a failure is reported, when this is the first need
     * @return empty when they cannot be read; the failure is then reported once
     */
    synchronized Optional<SecretValues> values(String what, Preflight preflight) {
        if (!read) {
            read = true;
            try {
                values = source.get();
            } catch (IllegalArgumentException | UncheckedIOException e) {
                preflight.refuse(what + " cannot be read: " + e.getMessage());
            }
        }
        return Optional.ofNullable(values);
    }

    /** The values a contributor read; {@code null} when none needed them, or they could not be read. */
    synchronized SecretValues read() {
        return values;
    }
}
