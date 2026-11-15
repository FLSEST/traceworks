package traceworks.core.context;

import java.util.concurrent.CancellationException;

/**
 * Completes the future of a question that parks the run ({@link ExecutionContext#parkedRequest()}): nobody answers it
 * in this process, so every wait for it unwinds as it does for a cancel. The engine tells the two apart by the parked
 * request: the asking step is not finished, and the run ends {@code PARKED} instead of {@code CANCELLED}.
 */
public final class RunParkedException extends CancellationException {

    public RunParkedException(String message) {
        super(message);
    }
}
