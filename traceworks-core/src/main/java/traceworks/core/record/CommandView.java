package traceworks.core.record;

import traceworks.core.port.BlobRef;

import java.util.List;

/**
 * A command a step ran. The result is {@code null} when the record has no {@code step.command.finished} for it,
 * because the command threw.
 *
 * @param item    the foreach item that ran it; {@code null} outside an item
 * @param imageId      the local ID of the image the container started from; {@code null} on the host and for an
 *                     image that was not present
 * @param network      where the container could connect: {@code none}, {@code open} or {@code hosts}; {@code null}
 *                     for a record that does not say
 * @param networkHosts the hosts of {@code hosts}, each {@code host:port}: the only targets the container could reach,
 *                     through the egress proxy; empty otherwise
 */
public record CommandView(int seq, Integer item, BlobRef command, String workingDir, String image, String imageId,
                          List<Mount> mounts, String network, List<String> networkHosts, List<String> envNames,
                          boolean host, Result result) {

    /** The network of a command that could connect to listed hosts only. */
    public static final String HOSTS = "hosts";

    public CommandView {
        mounts = List.copyOf(mounts);
        networkHosts = List.copyOf(networkHosts);
        envNames = List.copyOf(envNames);
    }

    public record Mount(String host, String container, boolean readOnly) {}

    /**
     * @param egress what the container asked the egress proxy for, by target and verdict; empty for a command that
     *               asked for nothing or had no proxy
     */
    public record Result(Integer exitCode, long durationMs, boolean timedOut, List<Connection> egress) {
        public Result {
            egress = List.copyOf(egress);
        }
    }

    /**
     * The connections to one target that got one verdict.
     *
     * @param verdict {@code ALLOW}, {@code DENY}, {@code FAIL} or {@code LIMIT}
     */
    public record Connection(String target, String verdict, int count) {

        /** Whether the container asked for something it was not allowed: {@code DENY} or {@code LIMIT}. */
        public boolean denied() {
            return "DENY".equals(verdict) || "LIMIT".equals(verdict);
        }
    }

    /** Whether the container could connect to listed hosts only, through the egress proxy. */
    public boolean throughProxy() {
        return HOSTS.equals(network);
    }

    /** How many connections the container asked the proxy for, and how many of them were denied. */
    public int[] connections() {
        int all = 0;
        int denied = 0;
        if (result != null) {
            for (Connection connection : result.egress()) {
                all += connection.count();
                denied += connection.denied() ? connection.count() : 0;
            }
        }
        return new int[] {all, denied};
    }

    CommandView withResult(Result result) {
        return new CommandView(seq, item, command, workingDir, image, imageId, mounts, network, networkHosts,
                envNames, host, result);
    }
}
