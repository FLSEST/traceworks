package traceworks.core.register;

import java.util.Locale;
import java.util.Optional;

/** The severity of a finding, most severe first; comparisons use this order. */
public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW;

    /** The severity {@code text} names, in any case; empty for any other text or {@code null}. */
    public static Optional<Severity> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(text.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
