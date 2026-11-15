package traceworks.core.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * Unified reasoning-effort levels across AI providers: the intersection of Claude's {@code --effort} and pi's
 * {@code --thinking} level sets. Persisted and serialized in lowercase; null or unset means the provider default
 * (no effort flag is emitted at all).
 */
public enum ReasoningEffort {
    LOW, MEDIUM, HIGH, XHIGH;

    /** Lowercase form used for CLI flags and persistence. */
    public String cliValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses case-insensitively; empty for null, blank or unknown values. */
    public static Optional<ReasoningEffort> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
