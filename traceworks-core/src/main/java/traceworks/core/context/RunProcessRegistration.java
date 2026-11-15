package traceworks.core.context;

import traceworks.core.util.ProcessTracking.StopHandle;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a run currently waits on, as the stop handles its runners reported: a shell block's process and a
 * Claude or Pi CLI can both be registered. A cancel calls each handle at most once. Resolves cancellation racing
 * with process creation without leaving an unstopped child: a handle registered after the cancel is called at once.
 */
final class RunProcessRegistration {
    private final Set<StopHandle> active = ConcurrentHashMap.newKeySet();

    void register(StopHandle stop, AtomicBoolean cancelled) {
        active.add(stop);
        if (cancelled.get() && active.remove(stop)) stop.stop();
    }

    void unregister(StopHandle stop) {
        active.remove(stop);
    }

    void cancel() {
        for (StopHandle stop : List.copyOf(active)) {
            if (active.remove(stop)) stop.stop();
        }
    }
}
