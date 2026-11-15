package traceworks.core.port;

import traceworks.core.port.AiCli.AiOutcome;

import java.io.IOException;
import java.util.Objects;

/**
 * An AI call that failed (a non-zero exit code, a result the CLI reported as an error, a timeout), as an
 * {@link AiProvider} throws it from {@link AiProvider#reason}. It carries the call's outcome, so the record still
 * holds what the call did: its tool calls, its exit code and its usage.
 */
public final class AiCallFailedException extends IOException {

    private final transient AiOutcome outcome;

    /**
     * @param providerId the provider that made the call
     * @param outcome    what came of the call; its text is why it failed
     */
    public AiCallFailedException(String providerId, AiOutcome outcome) {
        super(providerId + " failed (exit code " + outcome.exitCode() + "): " + outcome.text());
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    /** What came of the call. */
    public AiOutcome outcome() {
        return outcome;
    }
}
