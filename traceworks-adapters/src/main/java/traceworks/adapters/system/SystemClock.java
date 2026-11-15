package traceworks.adapters.system;

import traceworks.core.port.Clock;

import java.time.Instant;

/** The {@link Clock} of the machine. */
public final class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
