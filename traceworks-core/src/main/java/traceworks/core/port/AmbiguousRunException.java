package traceworks.core.port;

import traceworks.core.util.Message;

import java.util.List;

/** A run id prefix matches more than one run. */
public final class AmbiguousRunException extends IllegalArgumentException implements Message.Carrier {

    private final List<String> candidates;

    public AmbiguousRunException(String runRef, List<String> candidates) {
        super("'" + runRef + "' matches several runs: " + String.join(", ", candidates));
        this.candidates = List.copyOf(candidates);
    }

    /** The reference is what the operator typed; the ids are the names of run folders, and quoted. */
    @Override
    public Message parts() {
        String text = getMessage();
        String lead = text.substring(0, text.length() - String.join(", ", candidates).length());
        return Message.of(lead, Message.quoted(String.join(", ", candidates)));
    }

    /** The ids of the runs the reference matches. */
    public List<String> candidates() {
        return candidates;
    }
}
