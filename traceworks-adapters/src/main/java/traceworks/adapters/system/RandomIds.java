package traceworks.adapters.system;

import traceworks.core.port.Ids;

import java.util.UUID;

/** Random UUIDs as run ids. */
public final class RandomIds implements Ids {

    @Override
    public String newRunId() {
        return UUID.randomUUID().toString();
    }
}
