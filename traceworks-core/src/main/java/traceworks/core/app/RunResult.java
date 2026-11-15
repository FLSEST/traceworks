package traceworks.core.app;

import traceworks.core.model.RunStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a use case left a run.
 *
 * @param location where the run lives (its folder)
 * @param status   the run's status: final, {@code PARKED}, or the status it still has when a cancel has not taken
 *                 effect yet
 * @param gate     the run's gate verdict ({@code gate_status}, {@code gate_category}, {@code gate_description},
 *                 {@code gate_summary}); empty when none
 * @param error     why the run failed; for a cancel that has not taken effect yet, what became of the request;
 *                  {@code null} otherwise
 * @param completed what a resume of a finished run completed of its finalisation ({@code manifest},
 *                  {@code ledger entry}); empty otherwise
 * @param register  what the run's end did to its register; {@code null} for a workflow without an audit block
 * @param checkout  the checkout of the run's source that stays although the run has finished; {@code null} when the
 *                  run has none, it was removed, or the run has not finished (a parked run keeps it for its resume)
 */
public record RunResult(String runId, String location, RunStatus status, Map<String, Object> gate, String error,
                        List<String> completed, KeptCheckout checkout, RegisterOutcome register) {

    /** A result of a run that fed no register. */
    public RunResult(String runId, String location, RunStatus status, Map<String, Object> gate, String error,
                     List<String> completed, KeptCheckout checkout) {
        this(runId, location, status, gate, error, completed, checkout, null);
    }

    public RunResult {
        gate = gate == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(gate));
        completed = completed == null ? List.of() : List.copyOf(completed);
    }

    /** A result that completed nothing of a finalisation and left no checkout. */
    public RunResult(String runId, String location, RunStatus status, Map<String, Object> gate, String error) {
        this(runId, location, status, gate, error, List.of(), null);
    }

    /** A result that left no checkout. */
    public RunResult(String runId, String location, RunStatus status, Map<String, Object> gate, String error,
                     List<String> completed) {
        this(runId, location, status, gate, error, completed, null);
    }

    /**
     * A checkout of a finished run's source that stays.
     *
     * @param path   where it is
     * @param reason why it stays: a command of the run could write in it, or it could not be removed
     */
    public record KeptCheckout(String path, String reason) {}
}
