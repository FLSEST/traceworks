package traceworks.core.testing;

import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.ContainerRuntime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A {@link ContainerRuntime} for tests that starts nothing. It knows a settable set of present images
 * ({@link #present}), answers {@link #imageId} from it, records every {@link ContainerRun} in order and answers it with
 * the result scripted for its image ({@link #on}, by image ID or by a reference of a present image). A run whose image
 * has no script ends with exit code 0 and no output. Building or pulling an image records the call and makes the image
 * present, with an ID derived from its reference. No container may still run unless a test says so
 * ({@link #stillRunning}), and none is refused unless a test says why ({@link #refusing}).
 */
public final class FakeContainerRuntime implements ContainerRuntime {

    private final Map<String, String> images = new HashMap<>();
    private final Map<String, Function<ContainerRun, CommandResult>> scripts = new HashMap<>();
    private final List<ContainerRun> runs = new CopyOnWriteArrayList<>();
    private final List<String> built = new CopyOnWriteArrayList<>();
    private final List<String> pulled = new CopyOnWriteArrayList<>();
    private final List<String> leftoversRemoved = new CopyOnWriteArrayList<>();
    private final Set<String> stillRunning = ConcurrentHashMap.newKeySet();
    private volatile String version = "1.0.0";
    private volatile boolean leftoversConfirmed = true;
    private volatile Function<ContainerRun, Optional<String>> refusal = run -> Optional.empty();
    private final Map<String, String> lookupFailures = new HashMap<>();
    private final List<String> lookups = new CopyOnWriteArrayList<>();
    private volatile Runnable duringLookup = () -> { };

    /** The image {@code reference} is present with the local ID {@code imageId}. */
    public synchronized FakeContainerRuntime present(String reference, String imageId) {
        images.put(reference, imageId);
        return this;
    }

    /** The image {@code reference} is no longer present. */
    public synchronized FakeContainerRuntime absent(String reference) {
        images.remove(reference);
        return this;
    }

    /** A container of the image with this ID, or of this present reference, ends as {@code result}. */
    public FakeContainerRuntime on(String imageIdOrReference, CommandResult result) {
        return on(imageIdOrReference, run -> result);
    }

    /** A container of the image with this ID, or of this present reference, ends as {@code answer} computes. */
    public synchronized FakeContainerRuntime on(String imageIdOrReference,
                                                Function<ContainerRun, CommandResult> answer) {
        scripts.put(imageIdOrReference, answer);
        return this;
    }

    /** What {@link #version()} reports. */
    public FakeContainerRuntime version(String version) {
        this.version = version;
        return this;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public synchronized Optional<String> imageId(String reference) {
        return Optional.ofNullable(images.get(reference));
    }

    /** A lookup of {@code reference} cannot tell whether it is present, for {@code failure}. */
    public synchronized FakeContainerRuntime lookupFails(String reference, String failure) {
        lookupFailures.put(reference, failure);
        return this;
    }

    /** Runs {@code action} inside every lookup, before it answers: what happens while the client is asked. */
    public FakeContainerRuntime duringLookup(Runnable action) {
        duringLookup = action;
        return this;
    }

    @Override
    public synchronized ImageLookup lookup(String reference) {
        lookups.add(reference);
        duringLookup.run();
        String failure = lookupFailures.get(reference);
        return failure != null ? new ImageLookup(null, failure) : new ImageLookup(images.get(reference), null);
    }

    /** The references looked up so far ({@link #lookup}), in order. */
    public List<String> lookups() {
        return List.copyOf(lookups);
    }

    /**
     * Why a container would be refused before it starts, as the real runtime's mount check says ({@link #refusal});
     * such a run starts nothing and ends with exit code 125.
     */
    public FakeContainerRuntime refusing(Function<ContainerRun, Optional<String>> refusal) {
        this.refusal = refusal;
        return this;
    }

    @Override
    public Optional<String> refusal(ContainerRun run) {
        return refusal.apply(run);
    }

    @Override
    public CommandResult run(ContainerRun run) {
        Optional<String> refused = refusal.apply(run);
        if (refused.isPresent()) {
            return new CommandResult(125, "", refused.get(), 0, false);
        }
        runs.add(run);
        Function<ContainerRun, CommandResult> answer = scriptFor(run.imageId());
        return answer != null ? answer.apply(run) : new CommandResult(0, "", "", 1, false);
    }

    private synchronized Function<ContainerRun, CommandResult> scriptFor(String imageId) {
        Function<ContainerRun, CommandResult> byId = scripts.get(imageId);
        if (byId != null) return byId;
        for (Map.Entry<String, String> image : images.entrySet()) {
            if (image.getValue().equals(imageId) && scripts.containsKey(image.getKey())) {
                return scripts.get(image.getKey());
            }
        }
        return null;
    }

    @Override
    public void build(String tag, String contextDirectory) {
        built.add(tag);
        present(tag, idOf(tag));
    }

    @Override
    public void pull(String reference) {
        pulled.add(reference);
        present(reference, idOf(reference));
    }

    /** The runs this runtime was told have ended ({@link #runEnded}), in order. */
    public java.util.List<String> ended() {
        return java.util.List.copyOf(ended);
    }

    private final java.util.List<String> ended = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void runEnded(String runId) {
        ended.add(runId);
    }

    /**
     * Records the call. Like the real runtime, what it cannot confirm gone ({@link #leftoversConfirmed}) counts as a
     * container that may still run.
     */
    @Override
    public boolean removeLeftovers(String runId) {
        leftoversRemoved.add(runId);
        if (!leftoversConfirmed) {
            stillRunning.add(runId);
        }
        return leftoversConfirmed;
    }

    /** Whether {@link #removeLeftovers} can confirm that nothing of a run is left; it can unless a test says no. */
    public FakeContainerRuntime leftoversConfirmed(boolean confirmed) {
        this.leftoversConfirmed = confirmed;
        return this;
    }

    @Override
    public boolean mayStillRun(String runId) {
        return stillRunning.contains(runId);
    }

    /** A container of run {@code runId} may still run, or no longer ({@link #mayStillRun}). */
    public FakeContainerRuntime stillRunning(String runId, boolean running) {
        if (running) {
            stillRunning.add(runId);
        } else {
            stillRunning.remove(runId);
        }
        return this;
    }

    /** The ID an image built or pulled here gets. */
    public static String idOf(String reference) {
        return "sha256:fake-" + Integer.toHexString(reference.hashCode());
    }

    /** Every container run so far, in order. */
    public List<ContainerRun> runs() {
        return List.copyOf(runs);
    }

    /** The tags built so far, in order. */
    public List<String> built() {
        return List.copyOf(built);
    }

    /** The references pulled so far, in order. */
    public List<String> pulled() {
        return List.copyOf(pulled);
    }

    /** The run ids whose leftover containers were removed, in order. */
    public List<String> leftoversRemoved() {
        return List.copyOf(leftoversRemoved);
    }
}
