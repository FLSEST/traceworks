package traceworks.core.app;

import traceworks.core.util.Message;


/**
 * The checkout of a run's source that a failure left behind: a run that a failure ended (a record that cannot be
 * written, a finalisation that fails) returns no result whose summary could name it, so the failure carries this as a
 * suppressed exception, and the command line names the checkout.
 */
public final class KeptCheckoutNotice extends RuntimeException implements Message.Carrier {

    private final String path;

    KeptCheckoutNotice(String path) {
        super("the checkout " + path + " of the run's source stays; remove it when you no longer need it", null,
                false, false);
        this.path = path;
    }

    /** The path is one Traceworks made below its home, so the notice is its own words as a whole. */
    @Override
    public Message parts() {
        return Message.of(getMessage());
    }

    /** Where the checkout is. */
    public String path() {
        return path;
    }
}
