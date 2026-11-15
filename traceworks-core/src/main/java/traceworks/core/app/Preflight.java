package traceworks.core.app;

import java.util.ArrayList;
import java.util.List;

/**
 * The start check of a run, before the run exists or a resume records anything: every contributor (the container
 * runtime, the images, the AI providers' images among them, the run's directories, the secrets and the AI providers'
 * credentials) adds the reasons the run cannot go ahead, and {@link #check} refuses with all of them at once, so the
 * operator fixes them in one go.
 */
public final class Preflight {

    private final List<String> refusals = new ArrayList<>();

    /** Adds one reason the run cannot go ahead. */
    public Preflight refuse(String reason) {
        refusals.add(reason);
        return this;
    }

    /** Adds every reason of a contributor; an empty list adds nothing. */
    public Preflight refuse(List<String> reasons) {
        refusals.addAll(reasons);
        return this;
    }

    /** The reasons so far, in the order they were added. */
    public List<String> refusals() {
        return List.copyOf(refusals);
    }

    /**
     * @param what what is refused, the start of the message: {@code The run is refused}
     * @throws RunRefusedException naming every reason, separated by {@code ; }, when there is one
     */
    public void check(String what) {
        if (!refusals.isEmpty()) {
            throw new RunRefusedException(what + ": " + String.join("; ", refusals));
        }
    }
}
