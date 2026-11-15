package traceworks.core.port;

import java.time.Instant;

/**
 * Port to the time: the core reads the current instant only through it, so tests can fix the time and the
 * timestamps of a run come from one source.
 */
public interface Clock {

    /** The current instant. */
    Instant now();
}
