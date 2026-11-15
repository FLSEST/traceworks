package traceworks.selfaudit.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded HTTP client for the registries: GET and HEAD only, https only (or http to a loopback address, for a
 * test's local registry), a time limit per request, and at most a given number of bytes of a body. It follows a
 * redirect only to the same scheme, host and port, so an answer cannot send a lookup to a host the rule file did not
 * name. What a registry answers is data from outside; the caller parses it strictly.
 */
final class Http {

    static final String USER_AGENT = "traceworks-self-audit-build";

    /** How many redirects one request follows. */
    static final int MAX_REDIRECTS = 5;

    /** One answer: its status, its headers (names in lower case) and at most the bytes the request allowed. */
    record Response(int status, Map<String, List<String>> headers, byte[] body) {

        Optional<String> header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
        }
    }

    /** A body that is larger than the request allowed. */
    static final class TooLarge extends IOException {
        TooLarge(int max) {
            super("the answer is larger than " + max + " bytes");
        }
    }

    /** What the lookups call; a test gives its own. */
    interface Client {
        Response send(String method, URI uri, Map<String, String> headers, int maxBytes) throws IOException;
    }

    private Http() {
    }

    /**
     * The JDK's client with these limits. It follows a redirect itself, at most {@value #MAX_REDIRECTS} times, and
     * only on the origin of the request: a redirect to another scheme, host or port is refused before it is sent.
     */
    static Client jdk(Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (method, uri, headers, maxBytes) -> {
            if (!RuleFile.secure(uri)) throw new IOException("refused: neither https nor http to a loopback address");
            URI target = uri;
            HttpResponse<InputStream> response = send(client, method, target, headers, timeout);
            for (int hops = 0; isRedirect(response.statusCode()); hops++) {
                response.body().close();
                if (hops == MAX_REDIRECTS) throw new IOException("more than " + MAX_REDIRECTS + " redirects");
                target = redirect(target, response.headers().firstValue("location").orElse(""));
                response = send(client, method, target, headers, timeout);
            }
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(maxBytes + 1);
            }
            if (body.length > maxBytes) throw new TooLarge(maxBytes);
            Map<String, List<String>> lower = new java.util.HashMap<>();
            response.headers().map().forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
            return new Response(response.statusCode(), lower, body);
        };
    }

    private static HttpResponse<InputStream> send(HttpClient client, String method, URI uri, Map<String, String> headers,
                                                  Duration timeout) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", USER_AGENT)
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** Where a redirect from {@code from} leads, if it stays on the same origin; else why not. */
    static URI redirect(URI from, String location) throws IOException {
        URI to;
        try {
            to = from.resolve(new URI(location));
        } catch (Exception e) {
            throw new IOException("refused: a redirect to no usable address");
        }
        if (location.isEmpty() || to.getHost() == null) throw new IOException("refused: a redirect to no usable address");
        if (!origin(to).equals(origin(from))) {
            throw new IOException("refused: a redirect to another host (" + address(to) + ")");
        }
        return to;
    }

    /** The host of a URI in lower case, with its port when the URI names one. */
    static String address(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    private static String origin(URI uri) {
        return String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT) + "://" + address(uri);
    }

    /** Why a request failed, in a few words, for a "not measured" line. */
    static String reason(Exception e) {
        if (e instanceof HttpTimeoutException) return "no answer in time";
        if (e instanceof java.net.UnknownHostException) return "the host is not known (no network?)";
        if (e instanceof java.net.ConnectException) return "no connection (no network?)";
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }

    /** Why an answer is not usable: its status, with the wait a rate limit asks for. */
    static String status(Response r) {
        String text = "HTTP " + r.status();
        if (r.status() == 429) {
            text += " (rate limited" + r.header("retry-after").map(s -> ", retry after " + s.replaceAll("[^0-9A-Za-z ,:]", "")
                    + " s").orElse("") + ")";
        }
        return text;
    }
}
