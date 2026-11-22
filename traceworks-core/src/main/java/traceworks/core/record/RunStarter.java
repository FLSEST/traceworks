package traceworks.core.record;

/**
 * For which project a run was started, and by whom: what the entries of the ledger name of a run beside its
 * workflow, so that the one ledger of a home says of every run which project it worked on and who started it.
 *
 * @param project the key of the project the run works on, as {@code run.started} records it; {@code null} for a
 *                run that names none
 * @param actor   who started the run: exactly the {@code actor} of {@code run.started}
 */
public record RunStarter(String project, String actor) {
}
