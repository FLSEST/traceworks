package traceworks.core.port;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where the containers of the AI CLIs can connect: the operator's {@code ai_network} and {@code ai_hosts}. Only the
 * operator sets it; no workflow can. By default ({@link #RESTRICTED}) a call's container reaches the hosts of its
 * provider ({@link AiCli#hosts}) and the operator's hosts for that CLI, through the egress proxy, and nothing else.
 *
 * @param open  whether the containers have the runtime's default network instead ({@code ai_network: "open"}), as
 *              before the allowlist existed
 * @param hosts more hosts for the containers of a CLI ({@code ai_hosts}), by the CLI's id in lower case
 *              ({@code claude}, {@code pi}), each {@code host:port} in lower case, sorted and distinct
 * @throws IllegalArgumentException for an AI CLI that does not exist and for a host that is none
 *                                  ({@link Egress#hostProblem})
 */
public record AiNetwork(boolean open, Map<String, List<String>> hosts) {

    /** {@code ai_network} by default: only the provider's hosts and the operator's. */
    public static final String RESTRICTED_MODE = "restricted";
    /** {@code ai_network} for the runtime's default network. */
    public static final String OPEN_MODE = "open";

    /** The default: restricted, with no hosts of the operator's. */
    public static final AiNetwork RESTRICTED = new AiNetwork(false, Map.of());

    public AiNetwork {
        Map<String, List<String>> sorted = new TreeMap<>();
        if (hosts != null) {
            hosts.forEach((cli, list) -> {
                if (!cliIds().contains(cli)) {
                    throw new IllegalArgumentException("'" + cli + "' is no AI CLI Traceworks runs; the ones that can"
                            + " have hosts are " + String.join(", ", cliIds()));
                }
                SortedSet<String> distinct = new TreeSet<>();
                for (String host : list == null ? List.<String>of() : list) {
                    String problem = Egress.hostProblem(host);
                    if (problem != null) {
                        throw new IllegalArgumentException("the host \"" + host + "\" of " + cli + " " + problem);
                    }
                    distinct.add(host.toLowerCase(Locale.ROOT));
                }
                if (!distinct.isEmpty()) {
                    sorted.put(cli, List.copyOf(distinct));
                }
            });
        }
        hosts = Collections.unmodifiableMap(sorted);
    }

    /** The ids of the AI CLIs in lower case, sorted: the keys of {@code ai_hosts}. */
    public static SortedSet<String> cliIds() {
        SortedSet<String> ids = new TreeSet<>();
        AiCli.CREDENTIALS.keySet().forEach(id -> ids.add(id.toLowerCase(Locale.ROOT)));
        return ids;
    }

    /** Why {@code mode} is no value of {@code ai_network}; {@code null} when it is one. */
    public static String modeProblem(String mode) {
        return RESTRICTED_MODE.equals(mode) || OPEN_MODE.equals(mode) ? null
                : "must be \"" + RESTRICTED_MODE + "\" or \"" + OPEN_MODE + "\"";
    }

    /** {@code restricted} or {@code open}, as {@code ai_network} and the record name it. */
    public String mode() {
        return open ? OPEN_MODE : RESTRICTED_MODE;
    }

    /**
     * Where the container of a call of the CLI {@code cliId} (in any case) can connect: anywhere when the network is
     * open, else the provider's hosts of that call and the operator's hosts for the CLI. Empty when that leaves no
     * host at all: such a call cannot reach anything, and its caller refuses it.
     *
     * @param providerHosts the hosts the CLI needs for the call ({@link AiCli#hosts})
     */
    public Optional<Egress> egress(String cliId, List<String> providerHosts) {
        if (open) {
            return Optional.of(Egress.OPEN);
        }
        List<String> all = new ArrayList<>(providerHosts);
        all.addAll(hosts.getOrDefault(cliId.toLowerCase(Locale.ROOT), List.of()));
        return all.isEmpty() ? Optional.empty() : Optional.of(Egress.hosts(all));
    }
}
