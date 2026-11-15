package traceworks.core.container;

import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.ScratchDirectory;
import traceworks.core.port.ScratchListing;

import java.util.List;
import java.util.Objects;

/**
 * The run's scratch directory as the record lists it after a step: only while no container of the run can still
 * write into it. The engine runs one command at a time, and a container has ended, and been removed, before its
 * step finishes. Only a removal that was not confirmed (after a timeout or a cancel) can leave one
 * running; then the listing is skipped instead of racing the container, and the record says so: the whole directory
 * ({@code ""}) is unreadable for the reason {@value #NOT_LISTED}, so nothing counts as removed, and the next listing
 * shows the directory as it is then.
 */
public final class IdleScratch implements ScratchDirectory {

    /** Why a listing was skipped. */
    public static final String NOT_LISTED = "not listed: a container of the run may still be running";

    private final ScratchDirectory scratch;
    private final ContainerRuntime runtime;
    private final String runId;

    /**
     * @param runtime the runtime that starts the run's containers; {@code null} for a host run without one, whose
     *                listing is never skipped
     */
    public IdleScratch(ScratchDirectory scratch, ContainerRuntime runtime, String runId) {
        this.scratch = Objects.requireNonNull(scratch, "scratch");
        this.runtime = runtime;
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    @Override
    public ScratchListing list(ScratchListing.Known known) {
        if (runtime != null && runtime.mayStillRun(runId)) {
            return new ScratchListing(List.of(), List.of(new ScratchListing.Unreadable("", NOT_LISTED)), false);
        }
        return scratch.list(known);
    }

    @Override
    public byte[] readScratch(String path, long maxBytes) {
        return scratch.readScratch(path, maxBytes);
    }
}
