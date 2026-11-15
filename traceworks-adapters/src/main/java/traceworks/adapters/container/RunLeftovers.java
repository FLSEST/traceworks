package traceworks.adapters.container;

import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.util.ProcessTracking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * The ledger of what may be left of each run in the runtime: the containers and networks whose removal was not
 * confirmed, and the runs whose containers or networks could not even be listed. {@link CliContainerRuntime} writes
 * into it where a removal of its own fails ({@link #containerUnconfirmed}, {@link #networksUnconfirmed}), and hands
 * it the two questions of the {@link ContainerRuntime} port that are about leftovers: {@link #removeLeftovers}
 * lists what carries the run's label ({@link ContainerArguments#RUN_LABEL}), removes it and confirms that it is
 * gone, and {@link #mayStillRun} asks the client again about what was not confirmed.
 *
 * <p>It knows the client only as {@link ClientCall}: one call with its arguments, which the runtime bounds in time
 * and output. The ledger is per runtime and lives as long as it does; it is in memory only, since the label finds
 * the leftovers of an earlier process.
 *
 * <p><b>Threads:</b> the sets take entries from any thread, without the lock: a step's thread notes a removal that
 * failed while another cleans up. The lock {@code removal} serialises the two sequences that list, remove and
 * confirm ({@link #removeLeftovers} and the check behind {@link #mayStillRun}), since Ctrl-C and the run's thread
 * may ask at once, and one must not confirm on a listing the other is about to change.
 */
final class RunLeftovers {

    /** One call of the runtime's client, {@code rm -f <name>} for one, with the bounds of a listing or a removal. */
    @FunctionalInterface
    interface ClientCall {
        CommandResult call(String... arguments);
    }

    /** What a container name looks like; anything else could reach the client as an option. */
    private static final Pattern CONTAINER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final ClientCall client;
    /** By run id, the containers whose removal was not confirmed ({@link #mayStillRun}). */
    private final Map<String, Set<String>> unconfirmed = new ConcurrentHashMap<>();
    /** By run id, the networks whose removal was not confirmed. */
    private final Map<String, Set<String>> unconfirmedNetworks = new ConcurrentHashMap<>();
    /** The runs whose containers {@link #removeLeftovers} could not list, so any of them may still run. */
    private final Set<String> unlisted = ConcurrentHashMap.newKeySet();
    /** Serialises the removals and checks of leftovers: Ctrl-C and the run's thread may ask at once. */
    private final Object removal = new Object();

    /** @param client how the runtime's client is called to list and to remove */
    RunLeftovers(ClientCall client) {
        this.client = client;
    }

    /** Notes a container of the run whose removal was not confirmed, so {@link #mayStillRun} asks about it. */
    void containerUnconfirmed(String runId, String name) {
        unconfirmed(runId).add(name);
    }

    /** Notes networks of the run whose removal was not confirmed, so {@link #mayStillRun} asks about them. */
    void networksUnconfirmed(String runId, Collection<String> names) {
        unconfirmedNetworks(runId).addAll(names);
    }

    private Set<String> unconfirmed(String runId) {
        return unconfirmed.computeIfAbsent(runId, id -> ConcurrentHashMap.newKeySet());
    }

    private Set<String> unconfirmedNetworks(String runId) {
        return unconfirmedNetworks.computeIfAbsent(runId, id -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Removes every container that carries the run's label ({@code ps -a --filter label=...=<run id>}, then
     * {@code rm -f}), and confirms it with a second listing, which must find none; then, the same way, every network
     * that carries it ({@code network ls --filter label=...}, {@code network rm}), the networks of its containers
     * with listed hosts. What it cannot
     * confirm is kept for {@link #mayStillRun}: the names still listed, or the whole run when it cannot list them.
     * It runs on a cancel too: the calls belong to no step ({@link ProcessTracking.Tracker#NONE}), so a cancelled
     * step's tracker does not end them, and an interrupt of the calling thread is set aside for them and restored
     * afterwards.
     */
    boolean removeLeftovers(String runId) {
        return untracked(() -> {
            synchronized (removal) {
                // both, also when a container stays: a network without a container on it can still go
                boolean containers = containersRemoved(runId);
                boolean networks = leftoverNetworksRemoved(runId);
                return containers && networks && confirmed(runId);
            }
        });
    }

    private boolean containersRemoved(String runId) {
        Optional<List<String>> listed = containersOf(runId);
        if (listed.isEmpty()) {
            unlisted.add(runId);
            return false;
        }
        if (listed.get().isEmpty()) {
            return true;
        }
        boolean removed = removed(listed.get());
        Optional<List<String>> left = containersOf(runId);
        if (left.isEmpty()) {
            unlisted.add(runId);
            unconfirmed(runId).addAll(listed.get());
            return false;
        }
        if (removed && left.get().isEmpty()) {
            return true;
        }
        unconfirmed(runId).addAll(left.get());
        return false;
    }

    private boolean leftoverNetworksRemoved(String runId) {
        Optional<List<String>> listed = networksOf(runId);
        if (listed.isEmpty()) {
            unlisted.add(runId);
            return false;
        }
        if (listed.get().isEmpty()) {
            return true;
        }
        boolean removed = networksRemoved(listed.get());
        Optional<List<String>> left = networksOf(runId);
        if (left.isEmpty()) {
            unlisted.add(runId);
            unconfirmedNetworks(runId).addAll(listed.get());
            return false;
        }
        if (removed && left.get().isEmpty()) {
            return true;
        }
        unconfirmedNetworks(runId).addAll(left.get());
        return false;
    }

    /**
     * Whether a container of the run may still be there: one whose removal was not confirmed, or any of the run's
     * when {@link #removeLeftovers} could not even list them. Without either, nothing is asked. Otherwise the run's
     * containers are listed by their label: a name that is gone is confirmed, one that is still there is removed once
     * more ({@code rm -f}), and the answer is true unless that removal succeeds. A listing that fails answers true as
     * well. Like {@link #removeLeftovers}, the calls belong to no step, so a cancelled step's tracker does not end
     * them.
     */
    boolean mayStillRun(String runId) {
        Set<String> names = unconfirmed.get(runId);
        Set<String> networks = unconfirmedNetworks.get(runId);
        if (!unlisted.contains(runId) && (names == null || names.isEmpty())
                && (networks == null || networks.isEmpty())) {
            return false;
        }
        return untracked(() -> stillThere(runId));
    }

    private boolean stillThere(String runId) {
        synchronized (removal) {
            Set<String> names = unconfirmed(runId);
            Set<String> networks = unconfirmedNetworks(runId);
            Optional<List<String>> listed = containersOf(runId);
            Optional<List<String>> listedNetworks = networksOf(runId);
            if (listed.isEmpty() || listedNetworks.isEmpty()) {
                return true;
            }
            if (unlisted.remove(runId)) {
                // Nothing of the run could be listed before: whatever carries its label now may be left from then
                names.addAll(listed.get());
                networks.addAll(listedNetworks.get());
            }
            names.retainAll(listed.get());
            if (!names.isEmpty()) {
                if (!removed(names)) {
                    return true;
                }
                names.clear();
            }
            // a network goes only once no container is on it, so after the containers
            networks.retainAll(listedNetworks.get());
            if (!networks.isEmpty()) {
                if (!networksRemoved(networks)) {
                    return true;
                }
                networks.clear();
            }
            return false;
        }
    }

    /** Nothing of the run is left: nothing of it may still run. */
    private boolean confirmed(String runId) {
        unlisted.remove(runId);
        unconfirmed.remove(runId);
        unconfirmedNetworks.remove(runId);
        return true;
    }

    /** Runs {@code body} as no step's, so a cancelled step's tracker does not end its calls; an interrupt waits. */
    static boolean untracked(BooleanSupplier body) {
        boolean interrupted = Thread.interrupted();
        try {
            return ProcessTracking.bind(ProcessTracking.Tracker.NONE, body::getAsBoolean);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * The names of the containers that carry the run's label, stopped ones included; empty when the client cannot
     * list them.
     */
    private Optional<List<String>> containersOf(String runId) {
        CommandResult listed = client.call("ps", "-a", "--filter",
                "label=" + ContainerArguments.RUN_LABEL + "=" + runId, "--format", "{{.Names}}");
        if (listed.exitCode() != 0 || listed.timedOut()) {
            return Optional.empty();
        }
        return Optional.of(listed.stdout().lines().map(String::strip).filter(name -> !name.isEmpty()).distinct()
                .toList());
    }

    /**
     * The names of the networks that carry the run's label, the two of each of its containers with listed hosts;
     * empty when the client cannot list them.
     */
    private Optional<List<String>> networksOf(String runId) {
        CommandResult listed = client.call("network", "ls", "--filter",
                "label=" + ContainerArguments.RUN_LABEL + "=" + runId, "--format", "{{.Name}}");
        if (listed.exitCode() != 0 || listed.timedOut()) {
            return Optional.empty();
        }
        return Optional.of(listed.stdout().lines().map(String::strip).filter(name -> !name.isEmpty()).distinct()
                .toList());
    }

    /** Whether {@code network rm} of the networks succeeded; a name that could pass for an option is not removed. */
    private boolean networksRemoved(Collection<String> names) {
        List<String> valid = names.stream().filter(name -> CONTAINER_NAME.matcher(name).matches()).sorted().toList();
        if (valid.isEmpty()) {
            return false;
        }
        List<String> remove = new ArrayList<>(List.of("network", "rm"));
        remove.addAll(valid);
        return !failed(client.call(remove.toArray(String[]::new))) && valid.size() == names.size();
    }

    /**
     * Whether {@code rm -f} of the containers succeeded. A name that is no container name (it could pass for an
     * option) is not handed to the client, so it is not removed.
     */
    private boolean removed(Collection<String> names) {
        List<String> valid = names.stream().filter(name -> CONTAINER_NAME.matcher(name).matches()).sorted().toList();
        if (valid.isEmpty()) {
            return false;
        }
        List<String> remove = new ArrayList<>(List.of("rm", "-f"));
        remove.addAll(valid);
        CommandResult result = client.call(remove.toArray(String[]::new));
        return result.exitCode() == 0 && !result.timedOut() && valid.size() == names.size();
    }

    private static boolean failed(CommandResult result) {
        return result.exitCode() != 0 || result.timedOut();
    }
}
