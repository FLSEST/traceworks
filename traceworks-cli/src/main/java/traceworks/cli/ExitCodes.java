package traceworks.cli;

import traceworks.core.app.RunResult;
import traceworks.core.engine.RunAdmission;
import traceworks.core.model.RunStatus;

import java.util.Locale;

/** The exit codes of the command line. */
public final class ExitCodes {

    public static final int OK = 0;
    public static final int COMPLETED_WITH_ERRORS = 1;
    public static final int USAGE = 2;
    public static final int FAILED = 3;
    public static final int CANCELLED = 4;
    public static final int PARKED = 5;
    public static final int REFUSED = 6;
    public static final int GATE = 7;
    public static final int VERIFY_FAILED = 8;
    public static final int INTERRUPTED = 9;
    /**
     * {@code approve}, {@code answer} or {@code cancel} put a request into a running run's inbox, and the run's
     * process has not recorded it within the wait (10 s, or 30 s for a cancel).
     */
    public static final int QUEUED = 10;
    /** {@code diff}: both runs were read and verified, and run B did not reproduce run A. */
    public static final int DIFFERS = 11;
    public static final int INTERNAL = 70;

    private ExitCodes() {
    }

    /** The exit code of a run's status; a run that has not ended is no failure. */
    public static int of(RunStatus status) {
        return switch (status) {
            case COMPLETED -> OK;
            case COMPLETED_WITH_ERRORS -> COMPLETED_WITH_ERRORS;
            case FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
            case PARKED -> PARKED;
            case INTERRUPTED -> INTERRUPTED;
            case PENDING, RUNNING, PAUSED -> OK;   // not ended, nothing went wrong: show of a live run exits 0
        };
    }

    /** The exit code of a use case's result: a run that admission refused counts as refused, not failed. */
    public static int of(RunResult result) {
        if (result.status() == RunStatus.FAILED && result.error() != null
                && result.error().startsWith(RunAdmission.MESSAGE_PREFIX)) {
            return REFUSED;
        }
        return of(result.status());
    }

    /**
     * The exit code with a gate threshold: a run that otherwise completed exits {@link #GATE} when its gate is at or
     * above {@code threshold}.
     *
     * @param threshold {@code red}, {@code yellow} or {@code null} for none
     */
    public static int of(RunResult result, String threshold) {
        int code = of(result);
        if (code != OK || threshold == null) {
            return code;
        }
        Object status = result.gate().get("gate_status");
        String gate = status == null ? "" : status.toString().toUpperCase(Locale.ROOT);
        boolean reached = "RED".equals(gate) || ("yellow".equals(threshold) && "YELLOW".equals(gate));
        return reached ? GATE : OK;
    }
}
