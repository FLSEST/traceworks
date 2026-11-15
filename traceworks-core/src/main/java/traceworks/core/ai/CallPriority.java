package traceworks.core.ai;

/**
 * Throttle tier of an AI action request: an interactive call (a person waits for the answer) may also take the
 * CLI slots reserved for interactive use. An enum so that it cannot be swapped silently with another flag of the
 * request.
 */
public enum CallPriority {
    /** Workflow runs and other unattended work: all slots except the reserved ones. */
    BACKGROUND,
    /** A person waits for the answer: may use the reserved slots too. */
    INTERACTIVE;

    public static CallPriority of(boolean interactive) {
        return interactive ? INTERACTIVE : BACKGROUND;
    }
}
