package traceworks.core.app;

import traceworks.core.record.RunView;

import java.util.List;

/**
 * A run as a list of runs shows it: what its record says, and the models its steps used.
 *
 * @param models the distinct models the steps' metadata names, sorted, as the run's manifest lists them
 */
public record RunListing(RunView view, List<String> models) {

    public RunListing {
        models = List.copyOf(models);
    }
}
