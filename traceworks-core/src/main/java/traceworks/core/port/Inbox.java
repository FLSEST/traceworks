package traceworks.core.port;

import java.util.List;

/** Port to the inbox of a run: how another process talks to the one that writes the run. */
public interface Inbox {

    /** Drops a request into the inbox. */
    void post(InboxRequest request);

    /** Removes and returns the pending requests, the oldest first. */
    List<InboxRequest> take();
}
