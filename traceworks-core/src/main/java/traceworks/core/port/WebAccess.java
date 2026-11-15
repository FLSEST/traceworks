package traceworks.core.port;

import traceworks.core.ai.ToolPolicy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Whether an AI call may use the web, a switch of its own next to {@link ToolPolicy}: the {@code web} key of a block
 * that owns {@code tools}, and its {@code web_hosts}. {@code tools: none} with {@code web: search} gives the model the
 * search tool and nothing else.
 *
 * <ul>
 *   <li>{@code none} (the default): nothing.</li>
 *   <li>{@code search}: the provider's search tool, which runs at the provider; the call's container reaches no more
 *       than without it.</li>
 *   <li>{@code fetch}: search, and the fetch of a page from the call's container, which then also reaches
 *       {@code web_hosts}: each a host (port 443) or {@code host:port}, or exactly {@code ["*"]} for any public host
 *       on port 443.</li>
 * </ul>
 *
 * What a model reads from the web is untrusted: a step with web behaves in every respect as with
 * {@code untrusted_input: true} ({@code core.context.UntrustedInput.declared}), and an explicit {@code untrusted_input: false}
 * next to it is refused. Every other AI call (an AI condition, {@code compress-ai}, an AI actor) never has web.
 *
 * @param mode  which of the three
 * @param hosts the targets of {@link Mode#FETCH}: each {@code host:port} in lower case, sorted and distinct, or the
 *              single {@value #ANY}; empty for the other modes
 * @throws IllegalArgumentException for hosts without {@link Mode#FETCH}, and for none with it
 */
public record WebAccess(Mode mode, List<String> hosts) {

    /** The three values of {@code web}. */
    public enum Mode { NONE, SEARCH, FETCH }

    /** The block key of the web switch. */
    public static final String KEY = "web";
    /** The block key of the hosts a {@code fetch} may reach. */
    public static final String HOSTS_KEY = "web_hosts";
    /** The one entry of {@code web_hosts} that stands for any public host on port 443. */
    public static final String ANY = Egress.ANY;
    /** The values a workflow may write, as a phrase for messages. */
    public static final String VALUES = "none, search or fetch";

    /** No web: the default. */
    public static final WebAccess NONE = new WebAccess(Mode.NONE, List.of());

    private static final int DEFAULT_PORT = 443;

    /** The block key {@code core.context.UntrustedInput} reads; a step with web cannot set it to false. */
    private static final String UNTRUSTED_INPUT_KEY = "untrusted_input";

    public WebAccess {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        if (mode == null) {
            throw new IllegalArgumentException("A web access has a mode");
        }
        if (mode == Mode.FETCH && hosts.isEmpty()) {
            throw new IllegalArgumentException("web: fetch needs web_hosts");
        }
        if (mode != Mode.FETCH && !hosts.isEmpty()) {
            throw new IllegalArgumentException("web_hosts is only read with web: fetch");
        }
    }

    /**
     * The web access a block's config names in {@value #KEY} and {@value #HOSTS_KEY}: the one parse path, for the
     * executors, the run admission and the analyzer alike.
     *
     * @param config the block's config; {@code null} for none
     * @return {@link #NONE} when {@value #KEY} is absent
     * @throws IllegalArgumentException for a value that is no mode (a placeholder included: a run's data must not
     *                                  widen what its model may do), {@code fetch} without hosts, hosts without
     *                                  {@code fetch}, a host that is none, a star mixed with names, and
     *                                  {@code untrusted_input: false} next to a mode other than {@code none}
     */
    public static WebAccess of(Map<String, Object> config) {
        if (config == null) {
            return NONE;
        }
        Mode mode = mode(config.get(KEY));
        Object configuredHosts = config.get(HOSTS_KEY);
        if (mode != Mode.FETCH) {
            if (configuredHosts != null) {
                throw new IllegalArgumentException("web_hosts is only read with web: fetch — remove it, or set web:"
                        + " fetch");
            }
        }
        Object untrusted = config.get(UNTRUSTED_INPUT_KEY);
        if (mode != Mode.NONE && untrusted != null && !Boolean.TRUE.equals(untrusted)
                && !"true".equalsIgnoreCase(String.valueOf(untrusted).strip())) {
            throw new IllegalArgumentException("untrusted_input: false next to web: " + value(mode) + " — what a"
                    + " model reads from the web is untrusted, so a step with web always runs as with"
                    + " untrusted_input: true; remove untrusted_input: false");
        }
        return mode == Mode.FETCH ? new WebAccess(mode, hosts(configuredHosts)) : new WebAccess(mode, List.of());
    }

    /**
     * Whether the config asks for web at all, without judging it: {@value #KEY} is a text other than {@code none}.
     * For what must hold also when the rest of the config is refused elsewhere.
     */
    public static boolean asked(Map<String, Object> config) {
        return config != null && config.get(KEY) instanceof String text
                && !text.strip().equalsIgnoreCase(value(Mode.NONE));
    }

    private static Mode mode(Object configured) {
        if (configured == null) {
            return Mode.NONE;
        }
        if (configured instanceof String text) {
            String value = text.strip().toLowerCase(Locale.ROOT);
            for (Mode mode : Mode.values()) {
                if (value(mode).equals(value)) {
                    return mode;
                }
            }
        }
        throw new IllegalArgumentException("web: " + (configured instanceof String text ? "\"" + text + "\""
                : String.valueOf(configured)) + " is not one of " + VALUES);
    }

    private static List<String> hosts(Object configured) {
        if (!(configured instanceof List<?> list) || list.isEmpty()) {
            if (configured != null && !(configured instanceof List<?>)) {
                throw new IllegalArgumentException("web_hosts must be a list of hosts, such as [\"docs.example.org\"],"
                        + " or [\"*\"] for any public host");
            }
            throw new IllegalArgumentException("web: fetch needs web_hosts, the hosts a page may be fetched from: a"
                    + " list such as [\"docs.example.org\"], or [\"*\"] for any public host");
        }
        if (list.size() == 1 && ANY.equals(list.getFirst())) {
            return List.of(ANY);
        }
        TreeSet<String> hosts = new TreeSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String text)) {
                throw new IllegalArgumentException("web_hosts holds " + entry + ", which is no host: write a text"
                        + " such as \"docs.example.org\"");
            }
            if (ANY.equals(text)) {
                throw new IllegalArgumentException("web_hosts mixes \"*\" with names — write [\"*\"] alone for any"
                        + " public host, or only names");
            }
            String target = text.contains(":") ? text : text + ":" + DEFAULT_PORT;
            String problem = Egress.hostProblem(target);
            if (problem != null) {
                throw new IllegalArgumentException("web_hosts holds \"" + text + "\", which is no host: a host is "
                        + Egress.HOST_SYNTAX + ", alone (port 443) or as host:port, with no scheme, path, wildcard"
                        + " or placeholder");
            }
            hosts.add(target.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(hosts);
    }

    private static String value(Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** The mode as a workflow writes it: {@code none}, {@code search} or {@code fetch}. */
    public String value() {
        return value(mode);
    }

    /** Whether the model gets a web tool: every mode but {@code none}. */
    public boolean enabled() {
        return mode != Mode.NONE;
    }

    /** Whether the model may fetch pages itself, from the call's container. */
    public boolean fetches() {
        return mode == Mode.FETCH;
    }

    /** Whether {@code web_hosts} is the star: any public host on port 443. */
    public boolean anyHost() {
        return hosts.equals(List.of(ANY));
    }
}
