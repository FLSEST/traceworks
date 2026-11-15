package traceworks.core.port;

import java.util.List;
import java.util.Set;

/**
 * The environment variable names a container step must not get. A {@link ContainerRuntime} names each variable on
 * its client's command line ({@code -e NAME}) and puts the value into the client's own environment, where the client
 * takes it from, so no value is ever on a command line. A name the client process reads itself would therefore
 * change the client: where it finds its programs, which libraries it loads, which runtime and registry it talks to,
 * and through which proxy and certificates. Such a name is refused, by the runtime and, for secrets, at the start of
 * a run.
 */
public final class ReservedVariables {

    /** The names, case-sensitive: the proxy variables count in both cases, as the clients read both. */
    public static final Set<String> NAMES = Set.of(
            // what the client gets from the operator's environment, and what every container gets fixed
            "PATH", "HOME", "LANG", "TERM", "TMPDIR", "USER", "LOGNAME", "SHELL", "TZ",
            "LC_ALL", "LC_CTYPE", "LC_MESSAGES", "LC_TIME", "LC_NUMERIC", "LC_COLLATE", "LC_MONETARY", "LC_PAPER",
            "LC_NAME", "LC_ADDRESS", "LC_TELEPHONE", "LC_MEASUREMENT", "LC_IDENTIFICATION",
            "DBUS_SESSION_BUS_ADDRESS",
            // what the clients read besides: image storage, registry logins, the Go runtime, TLS, proxies, sockets
            "STORAGE_DRIVER", "STORAGE_OPTS", "REGISTRY_AUTH_FILE", "GODEBUG", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
            "SSH_AUTH_SOCK", "NOTIFY_SOCKET");

    /**
     * The prefixes: the dynamic loader ({@code LD_}), the clients' own settings (Docker, Podman, Buildah and the
     * containers libraries, also their internal {@code _CONTAINERS_}), the user session ({@code XDG_}), and the
     * settings of the container's init ({@code TINI_}), which Traceworks sets itself
     * ({@code TINI_KILL_PROCESS_GROUP}).
     */
    public static final List<String> PREFIXES = List.of("LD_", "DOCKER_", "CONTAINER_", "CONTAINERS_",
            "_CONTAINERS_", "PODMAN_", "BUILDAH_", "XDG_", "TINI_");

    private ReservedVariables() {
    }

    /** Whether a container variable must not be called {@code name}. */
    public static boolean reserved(String name) {
        return NAMES.contains(name) || PREFIXES.stream().anyMatch(name::startsWith);
    }
}
