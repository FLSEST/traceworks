package traceworks.core.app;

import java.util.Locale;

/**
 * Where a run runs its steps. The run decides it at its start, {@code run.started} records it as
 * {@code execution}, and a resume keeps it.
 */
public enum RunTarget {

    /** Every command in a container; a command without an image in the built-in {@code shell} image. The default. */
    CONTAINER,

    /**
     * A shell command without an image on the host (a {@code shell} step without {@code container_image},
     * {@code run_if}, a shell condition, {@code items_command}); a step with an image, and every AI step, in a
     * container. The fallback for a machine where no container runtime works, with the operator's consent:
     * {@code allow_host_execution}, or {@code --allow-host-execution}.
     */
    HOST;

    /**
     * The target of a new run: a container run wherever a container runtime works; without one, a host run with the
     * operator's consent, and a container run otherwise, which the start check refuses. The consent allows a host run,
     * it never asks for one.
     */
    static RunTarget of(boolean hostExecutionAllowed, boolean runtimeWorks) {
        return hostExecutionAllowed && !runtimeWorks ? HOST : CONTAINER;
    }

    /** As {@code run.started} records it: {@code container} or {@code host}. */
    public String recorded() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code traceworks resume} of a run needs {@code --allow-host-execution}: it is a host run, and the
     * operator config gives no consent, so the consent of its start came from the flag.
     *
     * @param execution     the target {@code run.started} recorded
     * @param configConsent whether the operator config allows host runs
     */
    static boolean resumeNeedsHostFlag(String execution, boolean configConsent) {
        return ofRecorded(execution) == HOST && !configConsent;
    }

    /** The target {@code run.started} recorded; {@code null} for a value Traceworks does not know, or none. */
    static RunTarget ofRecorded(String execution) {
        for (RunTarget target : values()) {
            if (target.recorded().equals(execution)) {
                return target;
            }
        }
        return null;
    }
}
