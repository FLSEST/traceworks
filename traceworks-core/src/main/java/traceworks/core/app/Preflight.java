package traceworks.core.app;

import traceworks.core.util.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * The start check of a run, before the run exists or a resume records anything: every contributor (the container
 * runtime, the images, the AI providers' images among them, the run's directories, the secrets and the AI providers'
 * credentials) adds the reasons the run cannot go ahead, and {@link #check} refuses with all of them at once, so the
 * operator fixes them in one go.
 */
public final class Preflight {

    private final List<Message> refusals = new ArrayList<>();

    /**
     * Adds one reason the run cannot go ahead, as a text that says nothing about its parts: it counts as quoted
     * from outside as a whole.
     */
    public Preflight refuse(String reason) {
        refusals.add(Message.of(Message.quoted(reason)));
        return this;
    }

    /** Adds one reason the run cannot go ahead, which says what it quotes. */
    public Preflight refuse(Message reason) {
        refusals.add(reason);
        return this;
    }

    /**
     * Adds every reason of a contributor, each a {@link Message} or a text ({@link #refuse(String)}); an empty
     * list adds nothing.
     */
    public Preflight refuse(List<?> reasons) {
        reasons.forEach(reason -> refusals.add(reason instanceof Message message ? message
                : Message.of(Message.quoted(reason))));
        return this;
    }

    /** The reasons so far, in the order they were added. */
    public List<String> refusals() {
        return refusals.stream().map(Message::text).toList();
    }

    /**
     * @param what what is refused, the start of the message: {@code The run is refused}
     * @throws RunRefusedException naming every reason, separated by {@code ; }, when there is one
     */
    public void check(String what) {
        if (!refusals.isEmpty()) {
            throw new RunRefusedException(Message.of(what + ": ", Message.join("; ", refusals)));
        }
    }
}
