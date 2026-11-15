package traceworks.core.context;

import traceworks.core.util.ProcessTracking.StopHandle;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunProcessRegistrationTest {

    /** A stop handle that counts its calls. */
    private static final class CountingStop implements StopHandle {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void stop() {
            calls.incrementAndGet();
        }
    }

    @Test
    void cancelStopsEveryRegisteredHandleOnce() {
        RunProcessRegistration registration = new RunProcessRegistration();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountingStop shell = new CountingStop();
        CountingStop cli = new CountingStop();
        registration.register(shell, cancelled);
        registration.register(cli, cancelled);

        registration.cancel();
        registration.cancel();

        assertThat(shell.calls).hasValue(1);
        assertThat(cli.calls).hasValue(1);
    }

    @Test
    void finishedHandlesAreNotStopped() {
        RunProcessRegistration registration = new RunProcessRegistration();
        CountingStop done = new CountingStop();
        registration.register(done, new AtomicBoolean());

        registration.unregister(done);
        registration.cancel();

        assertThat(done.calls).hasValue(0);
    }

    @Test
    void registeringAfterCancelStopsImmediately() {
        RunProcessRegistration registration = new RunProcessRegistration();
        CountingStop late = new CountingStop();

        registration.register(late, new AtomicBoolean(true));

        assertThat(late.calls).hasValue(1);
    }
}
