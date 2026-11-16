package traceworks.core.port;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a container can connect: nowhere ({@link #NONE}), anywhere ({@link #OPEN}), or only to listed targets
 * ({@link #hosts}), each {@code host:port}. With listed targets the container has no network of its own: a
 * {@link ContainerRuntime} gives it a proxy as its only door, which connects to these targets and to no other, and
 * reports every connection the container asked for ({@link CommandRunner.CommandResult#connections()}).
 *
 * @param mode  which of the three
 * @param hosts the targets of {@link Mode#HOSTS}, each {@code host:port} in lower case, sorted and distinct, at least
 *              one, and maybe {@value #ANY} among them (first); empty for the other modes
 * @param plain those of the hosts the proxy also forwards a plain HTTP request to: a model server of the operator's
 *              that speaks {@code http} ({@link LocalModels}). To every other target the proxy only opens a tunnel
 * @throws IllegalArgumentException for hosts with another mode than {@link Mode#HOSTS}, for none with it, for a
 *                                  host that is none ({@link #hostProblem}), and for a plain target that is no host
 */
public record Egress(Mode mode, List<String> hosts, List<String> plain) {

    /** An egress without a plain HTTP target. */
    public Egress(Mode mode, List<String> hosts) {
        this(mode, hosts, List.of());
    }

    /** The three kinds of egress. */
    public enum Mode { NONE, OPEN, HOSTS }

    /** No network at all. */
    public static final Egress NONE = new Egress(Mode.NONE, List.of());

    /** The runtime's default network: any target. */
    public static final Egress OPEN = new Egress(Mode.OPEN, List.of());

    /**
     * The one entry among the hosts that stands for any public host on port 443: the egress proxy refuses, for a
     * target only this allows, every other port, every IP literal and every name that resolves to an address of the
     * machine or its network. Only a step's {@code web_hosts} can bring it; {@link #hostProblem} refuses it.
     */
    public static final String ANY = "*";

    /** What a host looks like, before its port. */
    public static final String HOST_SYNTAX = "[a-z0-9]([a-z0-9.-]*[a-z0-9])?";

    private static final Pattern HOST = Pattern.compile("(" + HOST_SYNTAX + "):([1-9][0-9]{0,4})");
    private static final int MAX_NAME_LENGTH = 253;

    public Egress {
        if (mode == null) {
            throw new IllegalArgumentException("An egress has a mode");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String host : hosts == null ? List.<String>of() : hosts) {
            if (ANY.equals(host)) {
                sorted.add(ANY);
                continue;
            }
            String problem = hostProblem(host);
            if (problem != null) {
                throw new IllegalArgumentException("The egress host \"" + host + "\" " + problem);
            }
            sorted.add(host.toLowerCase(Locale.ROOT));
        }
        if (mode == Mode.HOSTS && sorted.isEmpty()) {
            throw new IllegalArgumentException("An egress to listed hosts needs at least one host");
        }
        if (mode != Mode.HOSTS && !sorted.isEmpty()) {
            throw new IllegalArgumentException("Only an egress to listed hosts has hosts");
        }
        hosts = List.copyOf(sorted);
        TreeSet<String> plainSorted = new TreeSet<>();
        for (String host : plain == null ? List.<String>of() : plain) {
            String lower = host == null ? null : host.toLowerCase(Locale.ROOT);
            if (lower == null || ANY.equals(lower) || !sorted.contains(lower)) {
                throw new IllegalArgumentException("The plain HTTP target \"" + host + "\" is not one of the egress"
                        + " hosts");
            }
            plainSorted.add(lower);
        }
        plain = List.copyOf(plainSorted);
    }

    /**
     * Only this one target, which the proxy also forwards a plain HTTP request to when {@code plainHttp}: the
     * egress of a call to a model server of the operator's.
     */
    public static Egress server(String host, boolean plainHttp) {
        return new Egress(Mode.HOSTS, List.of(host), plainHttp ? List.of(host) : List.of());
    }

    /** Only these targets, each {@code host:port}; they are compared in lower case. */
    public static Egress hosts(Collection<String> hosts) {
        return new Egress(Mode.HOSTS, List.copyOf(hosts));
    }

    /** The egress of a step that sets {@code network}: {@link #OPEN} with it, {@link #NONE} without. */
    public static Egress of(boolean network) {
        return network ? OPEN : NONE;
    }

    /**
     * Why {@code host} is no target; {@code null} when it is one: a host name or an IPv4 literal
     * ({@value #HOST_SYNTAX}, compared in lower case, at most 253 characters), a colon and a port from 1 to 65535.
     * No wildcard, no scheme, no path.
     */
    public static String hostProblem(String host) {
        if (host == null) {
            return "is missing";
        }
        Matcher matcher = HOST.matcher(host.toLowerCase(Locale.ROOT));
        if (!matcher.matches() || !host.equals(host.strip())) {
            return "must be host:port, such as api.example.org:443 (a host is " + HOST_SYNTAX + ")";
        }
        if (matcher.group(1).length() > MAX_NAME_LENGTH) {
            return "has a host of more than " + MAX_NAME_LENGTH + " characters";
        }
        if (Integer.parseInt(matcher.group(3)) > 65535) {
            return "has a port above 65535";
        }
        return null;
    }

    /** Whether the container can connect anywhere at all: every mode but {@link Mode#NONE}. */
    public boolean any() {
        return mode != Mode.NONE;
    }

    /** Whether the container connects through the proxy, to listed targets only. */
    public boolean restricted() {
        return mode == Mode.HOSTS;
    }

    /** The mode as the record names it: {@code none}, {@code open} or {@code hosts}. */
    public String label() {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
