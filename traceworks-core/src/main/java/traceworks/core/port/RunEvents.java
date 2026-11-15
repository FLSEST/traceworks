package traceworks.core.port;

/**
 * Port to the run record: receives what happens during a run, in order. The core persists nothing itself; the adapter
 * behind this port decides where the events go (a run folder, a terminal).
 */
public interface RunEvents {

    /** Records one event of the run. */
    void emit(RunEvent event);
}
