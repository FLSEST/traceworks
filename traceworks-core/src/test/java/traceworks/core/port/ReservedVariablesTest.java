package traceworks.core.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names a container variable must not have, because the container runtime's client reads them itself: its value
 * reaches the client's own environment first ({@code -e NAME}).
 */
class ReservedVariablesTest {

    @Test
    void namesTheClientReads_areReserved() {
        assertThat(List.of("PATH", "HOME", "TMPDIR", "LANG", "USER", "TZ", "LC_ALL", "LC_CTYPE",
                "DBUS_SESSION_BUS_ADDRESS", "STORAGE_DRIVER", "STORAGE_OPTS", "REGISTRY_AUTH_FILE", "GODEBUG",
                "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy",
                "no_proxy", "SSH_AUTH_SOCK", "NOTIFY_SOCKET")).allMatch(ReservedVariables::reserved);
    }

    @Test
    void prefixesTheClientReads_areReserved() {
        assertThat(List.of("LD_PRELOAD", "LD_LIBRARY_PATH", "DOCKER_HOST", "DOCKER_CONFIG", "CONTAINER_HOST",
                "CONTAINERS_CONF", "_CONTAINERS_USERNS_CONFIGURED", "PODMAN_USERNS", "BUILDAH_ISOLATION",
                "XDG_RUNTIME_DIR")).allMatch(ReservedVariables::reserved);
        assertThat(ReservedVariables.PREFIXES).containsExactly("LD_", "DOCKER_", "CONTAINER_", "CONTAINERS_",
                "_CONTAINERS_", "PODMAN_", "BUILDAH_", "XDG_", "TINI_");
    }

    /** The container's init reads TINI_* from the container's environment; no route may set them. */
    @Test
    void namesTheContainersInitReads_areReserved() {
        assertThat(List.of("TINI_KILL_PROCESS_GROUP", "TINI_SUBREAPER", "TINI_VERBOSITY"))
                .allMatch(ReservedVariables::reserved);
    }

    @Test
    void ordinaryNames_areNot() {
        assertThat(List.of("TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "GOOGLE_API_KEY",
                "LDAP_PASSWORD", "PATH_PREFIX", "Proxy_user")).noneMatch(ReservedVariables::reserved);
    }
}
