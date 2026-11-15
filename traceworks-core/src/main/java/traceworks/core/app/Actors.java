package traceworks.core.app;

import java.util.Map;

/** Who a run is attributed to. */
public final class Actors {

    /** The environment variable that sets the actor. */
    public static final String ENV = "TRACEWORKS_ACTOR";

    private Actors() {
    }

    /** {@code TRACEWORKS_ACTOR}, else the configured actor, else {@code user@host}; blank values do not count. */
    public static String resolve(OperatorSettings settings, Map<String, String> env, String user, String host) {
        String fromEnv = env.get(ENV);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.strip();
        String configured = settings.actor();
        if (configured != null && !configured.isBlank()) return configured.strip();
        return user + "@" + host;
    }
}
