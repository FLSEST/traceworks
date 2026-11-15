package traceworks.selfaudit.build;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lookups against a registry of the JDK's HTTP server on the loopback address: no network. */
class RegistriesTest {

    private static final String PINNED = "sha256:" + "a".repeat(64);
    private static final String NEWER = "sha256:" + "b".repeat(64);

    private HttpServer server;
    private String base;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        base = "127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        requests.add(method + " " + path + (auth == null ? "" : " " + auth));
        switch (path) {
            case "/m2/com/example/lib/maven-metadata.xml" -> send(exchange, 200, """
                    <metadata><groupId>com.example</groupId><artifactId>lib</artifactId><versioning><versions>
                    <version>1.0</version><version>1.1</version><version>2.0-rc1</version><version>1.2</version>
                    <version>bad version</version></versions></versioning></metadata>""");
            case "/m2/org/apache/maven/plugins/maven-x-plugin/maven-metadata.xml" -> send(exchange, 200,
                    "<metadata><versioning><versions><version>3.0</version><version>3.1</version></versions>"
                            + "</versioning></metadata>");
            case "/m2/org/current/current/maven-metadata.xml" -> send(exchange, 200,
                    "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>");
            case "/m2/org/rate/limited/maven-metadata.xml" -> {
                exchange.getResponseHeaders().add("Retry-After", "1800");
                send(exchange, 429, "slow down");
            }
            case "/m2/org/big/big/maven-metadata.xml" -> send(exchange, 200, "<metadata>"
                    + "x".repeat(Registries.MAX_METADATA) + "</metadata>");
            case "/m2/org/entity/entity/maven-metadata.xml" -> send(exchange, 200,
                    "<!DOCTYPE m [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><metadata>&x;</metadata>");
            case "/npm/@scope%2Fcli" -> send(exchange, 200, """
                    {"name":"@scope/cli","dist-tags":{"latest":"1.5.2","legacy":"0.74.2"},
                     "versions":{"0.74.2":{},"1.0.0":{},"1.5.2":{},"2.0.0-beta":{}},
                     "time":{"0.74.2":"2026-05-21T10:00:00.000Z","1.5.2":"2026-11-10T12:24:55.226Z"}}""");
            case "/npm/gone" -> send(exchange, 200, "{\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{\"1.0.0\":{}}}");
            case "/npm/broken" -> send(exchange, 200, "{\"dist-tags\":");
            case "/token" -> send(exchange, 200, "{\"token\":\"tok-123\"}");
            case "/m2/org/moved/moved/maven-metadata.xml", "/npm/moved", "/v2/library/cdn/manifests/1" -> {
                exchange.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort()
                        + "/elsewhere");
                send(exchange, path.startsWith("/v2/") ? 307 : 302, "");
            }
            case "/m2/org/relative/relative/maven-metadata.xml" -> {
                exchange.getResponseHeaders().add("Location", "/m2/org/current/current/maven-metadata.xml");
                send(exchange, 301, "");
            }
            case "/v2/library/elsewhere/manifests/1" -> {
                if (!"Bearer tok-123".equals(auth)) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"http://localhost:"
                            + server.getAddress().getPort() + "/token\",service=\"registry\"");
                    send(exchange, 401, "{}");
                    return;
                }
                exchange.getResponseHeaders().add("Docker-Content-Digest", PINNED);
                send(exchange, 200, "{\"manifests\":[]}");
            }
            case "/v2/library/node/manifests/22", "/v2/library/same/manifests/1", "/v2/library/platform/manifests/1" -> {
                if (!"Bearer tok-123".equals(auth)) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"http://" + base
                            + "/token\",service=\"registry\",scope=\"repository:" + path.split("/")[3] + ":pull\"");
                    send(exchange, 401, "{}");
                    return;
                }
                String digest = path.contains("same") ? PINNED : NEWER;
                exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                send(exchange, 200, path.contains("platform")
                        ? "{\"manifests\":[{\"digest\":\"" + PINNED + "\",\"platform\":{}}]}" : "{\"manifests\":[]}");
            }
            default -> send(exchange, 404, "not found");
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void lookUp_comparesEveryVersionWithItsRegistry() {
        Facts offline = new Facts("offline");
        offline.data.put("maven", List.of(
                maven("dependency", "com.example:lib", "1.0", 10),
                maven("managed", "com.example:lib", "1.0", 11),
                maven("plugin-managed", "org.apache.maven.plugins:maven-x-plugin", "3.0", 20),
                maven("dependency", "org.current:current", "1.0", 30)));
        offline.data.put("npm", List.of(Map.of("name", "@scope/cli", "version", "0.74.2", "file", "images/pi/Dockerfile",
                "line", 39L)));
        offline.data.put("images", List.of(
                image(base + "/library/node:22@" + PINNED, "images/a/Dockerfile"),
                image(base + "/library/node:22@" + PINNED, "images/b/Dockerfile"),
                image(base + "/library/same:1@" + PINNED, "images/c/Dockerfile"),
                image(base + "/library/platform:1@" + PINNED, "images/d/Dockerfile"),
                image(base + "/library/notag@" + PINNED, "images/e/Dockerfile")));

        Facts out = lookUp(offline);

        assertThat(out.gaps).isEmpty();
        assertThat(out.items).extracting(i -> i.check() + " " + i.where() + " " + i.subject() + ": " + i.detail())
                .containsExactly(
                        "dependency_update pom.xml:10 com.example:lib: 1.0 -> 1.2 (minor; 2 newer releases)",
                        "plugin_update pom.xml:20 org.apache.maven.plugins:maven-x-plugin: 3.0 -> 3.1 (minor; 1 newer release)",
                        "npm_update images/pi/Dockerfile:39 @scope/cli: 0.74.2 -> 1.5.2 (major; 2 newer releases;"
                                + " pinned 2026-05-21, latest 2026-11-10)",
                        "base_image_moved images/a/Dockerfile:4 " + base + "/library/node:22: the tag now points to"
                                + " sha256:bbbbbbbbbbbb..., the Dockerfile pins sha256:aaaaaaaaaaaa...: a newer build of"
                                + " the tag is out",
                        "base_image_moved images/b/Dockerfile:4 " + base + "/library/node:22: the tag now points to"
                                + " sha256:bbbbbbbbbbbb..., the Dockerfile pins sha256:aaaaaaaaaaaa...: a newer build of"
                                + " the tag is out",
                        "base_no_tag images/e/Dockerfile:4 " + base + "/library/notag@" + PINNED
                                + ": pinned by digest only: there is no tag to compare");
        assertThat(out.facts).containsEntry("maven_artifacts_looked_up", 3L).containsEntry("npm_packages_looked_up", 1L)
                .containsEntry("base_images_looked_up", 3L).containsEntry("lookups_failed", 0L);
        // node:22 is asked once for both Dockerfiles; the token is fetched anonymously and then sent
        assertThat(requests).filteredOn(r -> r.contains("/library/node/")).containsExactly(
                "HEAD /v2/library/node/manifests/22", "HEAD /v2/library/node/manifests/22 Bearer tok-123",
                "GET /v2/library/node/manifests/22", "GET /v2/library/node/manifests/22 Bearer tok-123");
    }

    @Test
    void whatARegistryCannotAnswer_isAGap() {
        Facts offline = new Facts("offline");
        offline.data.put("maven", List.of(
                maven("dependency", "org.gone:gone", "1.0", 1),
                maven("dependency", "org.rate:limited", "1.0", 2),
                maven("dependency", "org.big:big", "1.0", 3),
                maven("dependency", "org.entity:entity", "1.0", 4),
                maven("dependency", "../../etc:passwd", "1.0", 5)));
        offline.data.put("npm", List.of(
                Map.of("name", "gone", "version", "0.9.0", "file", "f", "line", 1L),
                Map.of("name", "broken", "version", "1.0.0", "file", "f", "line", 2L),
                Map.of("name", "Not/A/Name", "version", "1.0.0", "file", "f", "line", 3L)));
        offline.data.put("images", List.of(image(base + "/library/missing:1@" + PINNED, "images/a/Dockerfile"),
                image("UPPER CASE@" + PINNED, "images/b/Dockerfile")));

        Facts out = lookUp(offline);

        assertThat(out.gaps).extracting(g -> g.what() + ": " + g.reason()).containsExactly(
                "maven_update org.gone:gone: HTTP 404 from the repository",
                "maven_update org.rate:limited: HTTP 429 (rate limited, retry after 1800 s) from the repository",
                "maven_update org.big:big: the answer is larger than " + Registries.MAX_METADATA + " bytes",
                "maven_update org.entity:entity: not well-formed XML: " + out.gaps.get(3).reason().substring(21),
                "maven_update ../../etc:passwd: not a groupId:artifactId that can be looked up",
                "npm_update broken: " + out.gaps.get(5).reason(),
                "npm_update Not/A/Name: not an npm package name that can be looked up",
                "base_image " + base + "/library/missing:1@" + PINNED + " (images/a/Dockerfile): HTTP 404 from the registry",
                "base_image UPPER CASE@" + PINNED + ": not an image reference this tool can look up");
        assertThat(out.gaps.get(5).reason()).startsWith("JSON at");
        assertThat(out.items).extracting(i -> i.check() + " " + i.subject()).containsExactly("npm_version_unknown gone@0.9.0");
        assertThat(out.facts).containsEntry("lookups_failed", 9L);
    }

    @Test
    void aRegistryTheRuleFileDoesNotName_isNotContacted() {
        Facts offline = new Facts("offline");
        offline.data.put("images", List.of(
                image("node:22@" + PINNED, "images/a/Dockerfile"),
                image("evil.example.org/x/y:1@" + PINNED, "images/b/Dockerfile"),
                image("evil.example.org/x/y:1@" + PINNED, "images/c/Dockerfile"),
                image("localhost:" + server.getAddress().getPort() + "/library/node:22@" + PINNED, "images/d/Dockerfile"),
                image(base + "/library/same:1@" + PINNED, "images/e/Dockerfile")));
        List<String> asked = new ArrayList<>();
        Http.Client jdk = Http.jdk(Duration.ofSeconds(10));
        Http.Client counting = (method, uri, headers, max) -> {
            asked.add(method + " " + uri);
            return jdk.send(method, uri, headers, max);
        };
        Facts out = new Facts("registries");

        new Registries(RuleFile.parse("image-registry " + base), counting, out).lookUp(offline);

        String localhost = "localhost:" + server.getAddress().getPort();
        assertThat(out.gaps).extracting(g -> g.what() + ": " + g.reason()).containsExactly(
                "base_image node:22@" + PINNED + " (images/a/Dockerfile): the rule file allows no lookup on docker.io;"
                        + " a line 'image-registry docker.io' would allow it",
                "base_image evil.example.org/x/y:1@" + PINNED + " (images/b/Dockerfile): the rule file allows no lookup"
                        + " on evil.example.org; a line 'image-registry evil.example.org' would allow it",
                "base_image evil.example.org/x/y:1@" + PINNED + " (images/c/Dockerfile): the rule file allows no lookup"
                        + " on evil.example.org; a line 'image-registry evil.example.org' would allow it",
                "base_image " + localhost + "/library/node:22@" + PINNED + " (images/d/Dockerfile): the rule file allows"
                        + " no lookup on " + localhost + "; a line 'image-registry " + localhost + "' would allow it");
        // Only the allowed registry was asked, and nothing else at all: no request, so no DNS lookup either
        assertThat(asked).isNotEmpty().allMatch(r -> r.contains(" http://" + base + "/"));
        assertThat(requests).isNotEmpty().noneMatch(r -> r.contains("/library/node/"));
        assertThat(out.facts).containsEntry("base_images_looked_up", 1L).containsEntry("lookups_failed", 4L);
    }

    @Test
    void aTokenAddressOnAnotherHost_isRefusedWithoutARequest() {
        Facts offline = new Facts("offline");
        offline.data.put("images", List.of(image(base + "/library/elsewhere:1@" + PINNED, "images/a/Dockerfile")));
        String localhost = "localhost:" + server.getAddress().getPort();

        Facts refused = new Facts("registries");
        new Registries(RuleFile.parse("image-registry " + base), Http.jdk(Duration.ofSeconds(10)), refused).lookUp(offline);

        assertThat(refused.gaps).extracting(Facts.Gap::reason).containsExactly("refused: the registry " + base
                + " sends for its token to " + localhost + "; a line 'image-registry " + base + " " + localhost
                + "' would allow it");
        assertThat(requests).containsExactly("HEAD /v2/library/elsewhere/manifests/1");

        // The token host its rule line names is asked
        requests.clear();
        Facts allowed = new Facts("registries");
        new Registries(RuleFile.parse("image-registry " + base + " " + localhost), Http.jdk(Duration.ofSeconds(10)),
                allowed).lookUp(offline);

        assertThat(allowed.gaps).isEmpty();
        assertThat(requests).contains("GET /token", "HEAD /v2/library/elsewhere/manifests/1 Bearer tok-123");
    }

    @Test
    void aRedirectToAnotherHost_isNotFollowed() {
        Facts offline = new Facts("offline");
        offline.data.put("maven", List.of(maven("dependency", "org.moved:moved", "1.0", 1),
                maven("dependency", "org.relative:relative", "1.0", 2)));
        offline.data.put("npm", List.of(Map.of("name", "moved", "version", "1.0.0", "file", "f", "line", 1L)));
        offline.data.put("images", List.of(image(base + "/library/cdn:1@" + PINNED, "images/a/Dockerfile")));

        Facts out = lookUp(offline);

        String refused = "refused: a redirect to another host (localhost:" + server.getAddress().getPort() + ")";
        assertThat(out.gaps).extracting(g -> g.what() + ": " + g.reason()).containsExactly(
                "maven_update org.moved:moved: " + refused,
                "npm_update moved: " + refused,
                "base_image " + base + "/library/cdn:1@" + PINNED + " (images/a/Dockerfile): " + refused);
        // A redirect on the same host is followed
        assertThat(requests).contains("GET /m2/org/relative/relative/maven-metadata.xml",
                "GET /m2/org/current/current/maven-metadata.xml").noneMatch(r -> r.contains("/elsewhere"));
        assertThat(out.facts).containsEntry("maven_artifacts_looked_up", 1L);
    }

    @Test
    void noNetwork_isAGapForEachLookup() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        Facts offline = new Facts("offline");
        offline.data.put("maven", List.of(maven("dependency", "com.example:lib", "1.0", 1)));
        offline.data.put("npm", List.of(Map.of("name", "cli", "version", "1.0.0", "file", "f", "line", 1L)));
        Facts out = new Facts("registries");

        new Registries(RuleFile.parse("maven-repository http://127.0.0.1:" + closed + "/m2\nnpm-registry http://127.0.0.1:"
                + closed), Http.jdk(Duration.ofSeconds(5)), out).lookUp(offline);

        assertThat(out.gaps).extracting(Facts.Gap::reason).containsExactly("no connection (no network?)",
                "no connection (no network?)");
    }

    @Test
    void theClient_refusesPlainHttpToAnotherHost() {
        assertThatThrownBy(() -> Http.jdk(Duration.ofSeconds(1)).send("GET", java.net.URI.create("http://example.org/"),
                Map.of(), 10)).hasMessageContaining("refused: neither https nor http to a loopback address");
    }

    @Test
    void metadataVersions_refusesWhatIsNoMetadata() {
        assertThat(Registries.metadataVersions("<metadata><versioning><versions><version>1</version></versions>"
                + "</versioning></metadata>")).containsExactly("1");
        assertThatThrownBy(() -> Registries.metadataVersions("<html/>")).hasMessage("no maven-metadata.xml");
        assertThatThrownBy(() -> Registries.metadataVersions("<metadata/>")).hasMessage("the metadata lists no version");
    }

    private Facts lookUp(Facts offline) {
        Facts out = new Facts("registries");
        new Registries(RuleFile.parse("maven-repository http://" + base + "/m2\nnpm-registry http://" + base + "/npm\n"
                        + "image-registry " + base),
                Http.jdk(Duration.ofSeconds(10)), out).lookUp(offline);
        return out;
    }

    private static Map<String, Object> maven(String kind, String coordinate, String version, long line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("coordinate", coordinate);
        m.put("version", version);
        m.put("file", "pom.xml");
        m.put("line", line);
        return m;
    }

    private static Map<String, Object> image(String reference, String file) {
        return Map.of("reference", reference, "file", file, "line", 4L);
    }
}
