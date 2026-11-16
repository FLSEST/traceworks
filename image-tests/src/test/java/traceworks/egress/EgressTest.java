package traceworks.egress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The allowlist proxy of the image {@code egress}, against real sockets on the loopback interface. */
class EgressTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final List<AutoCloseable> open = new ArrayList<>();
    /** Opens the proxy's connections: the real socket, unless a test sends them elsewhere. */
    private Egress.Connector connector = address -> {
        Socket socket = new Socket();
        socket.connect(address, 5_000);
        return socket;
    };

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    /** Starts a proxy on a free port that allows {@code targets}, and returns that port. */
    private int proxy(String... targets) throws IOException {
        return proxy(Egress.MAX_LINES, targets);
    }

    private int proxy(int maxLines, String... targets) throws IOException {
        return proxy(maxLines, InetAddress::getAllByName, targets);
    }

    /** A proxy whose names resolve as {@code resolver} says. */
    private int proxy(int maxLines, Egress.Resolver resolver, String... targets) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        open.add(server);
        Egress egress = new Egress(List.of(targets), new PrintStream(printed, true, StandardCharsets.UTF_8), maxLines,
                resolver, connector);
        Thread.ofVirtual().start(() -> egress.serve(server));
        return server.getLocalPort();
    }

    /** An upstream that answers every connection with {@code pong} after it read {@code ping}. */
    private int upstream() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        open.add(server);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    byte[] ping = client.getInputStream().readNBytes(4);
                    client.getOutputStream().write(("pong:" + new String(ping, StandardCharsets.US_ASCII))
                            .getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                } catch (IOException e) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    /** Sends {@code request} to the proxy and returns everything it answers until it closes the connection. */
    private String exchange(int proxyPort, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private List<String> lines() {
        return printed.toString(StandardCharsets.UTF_8).lines().toList();
    }

    /** Waits until the proxy printed {@code count} lines: a line is printed before the answer, but on another thread. */
    private List<String> lines(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (lines().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return lines();
    }

    @Test
    void printsReady_onceItListens() throws Exception {
        proxy("api.example:443");

        assertThat(lines(1)).containsExactly("READY");
    }

    @Test
    void aListedTarget_isTunnelledBothWays_andPrintedAsAllow() throws Exception {
        int upstream = upstream();
        int proxy = proxy("127.0.0.1:" + upstream);

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\nHost: x\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            String head = new String(in.readNBytes("HTTP/1.1 200 Connection established\r\n\r\n".length()),
                    StandardCharsets.US_ASCII);
            assertThat(head).isEqualTo("HTTP/1.1 200 Connection established\r\n\r\n");
            socket.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(new String(in.readNBytes(9), StandardCharsets.US_ASCII)).isEqualTo("pong:ping");
        }

        assertThat(lines(2)).containsExactly("READY", "ALLOW 127.0.0.1:" + upstream);
    }

    @Test
    void aTargetThatIsNotListed_isRefusedWith403_andPrintedAsDeny() throws Exception {
        int upstream = upstream();
        int proxy = proxy("api.example:443");

        String answer = exchange(proxy, "CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 403 ").contains("Connection: close");
        assertThat(lines(2)).containsExactly("READY", "DENY 127.0.0.1:" + upstream);
    }

    @Test
    void aTargetIsComparedAsWritten_inLowerCase() throws Exception {
        int upstream = upstream();
        int proxy = proxy("LocalHost:" + upstream);

        String allowed = exchange(proxy, "CONNECT LOCALHOST:" + upstream + " HTTP/1.1\r\n\r\nping");
        String ipLiteral = exchange(proxy, "CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\n\r\n");
        String otherPort = exchange(proxy, "CONNECT localhost:1 HTTP/1.1\r\n\r\n");

        assertThat(allowed).startsWith("HTTP/1.1 200 ").endsWith("pong:ping");
        assertThat(ipLiteral).as("the address of a listed name is not listed").startsWith("HTTP/1.1 403 ");
        assertThat(otherPort).startsWith("HTTP/1.1 403 ");
        assertThat(lines(4)).containsExactly("READY", "ALLOW localhost:" + upstream, "DENY 127.0.0.1:" + upstream,
                "DENY localhost:1");
    }

    /** An address as its four or sixteen bytes, without any lookup. */
    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A star allows a public host on port 443 and still prints it: the name is resolved once, and the connection goes
     * to the address that was checked. The test's connector notes that address and connects to a local upstream
     * instead, so nothing leaves the machine.
     */
    @Test
    void aStar_allowsAPublicNameOnPort443_andConnectsToTheAddressItChecked() throws Exception {
        int upstream = upstream();
        List<String> asked = new ArrayList<>();
        List<java.net.InetSocketAddress> connectedTo = new ArrayList<>();
        connector = address -> {
            connectedTo.add(address);
            return new Socket(InetAddress.getLoopbackAddress(), upstream);
        };
        int proxy = proxy(Egress.MAX_LINES, host -> {
            asked.add(host);
            return new InetAddress[] {address("203.0.113.9"), address("203.0.113.10")};
        }, "*");

        String answer = exchange(proxy, "CONNECT Docs.Example.org:443 HTTP/1.1\r\n\r\nping");

        assertThat(answer).startsWith("HTTP/1.1 200 ").endsWith("pong:ping");
        assertThat(lines(2)).containsExactly("READY", "ALLOW docs.example.org:443");
        assertThat(asked).as("resolved once").containsExactly("docs.example.org");
        assertThat(connectedTo).as("the address that was checked, not the name")
                .containsExactly(new java.net.InetSocketAddress(address("203.0.113.9"), 443));
        assertThat(connectedTo.getFirst().isUnresolved()).isFalse();
    }

    /** A star is no door to the machine or its network: whatever is not a public name on port 443 is denied. */
    @Test
    void aStar_refusesIpLiterals_otherPorts_andNamesThatResolveToAnAddressThatIsNotPublic() throws Exception {
        int upstream = upstream();
        java.util.Map<String, String> names = java.util.Map.of("loop.example", "127.0.0.1",
                "lan.example", "192.168.1.10", "mixed.example", "203.0.113.9,10.0.0.1", "v6.example", "fd00::1");
        int proxy = proxy(Egress.MAX_LINES, host -> {
            String[] literals = names.get(host).split(",");
            InetAddress[] addresses = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                addresses[i] = address(literals[i]);
            }
            return addresses;
        }, "*");

        for (String target : List.of("127.0.0.1:" + upstream, "127.0.0.1:443", "10.0.0.5:443", "2130706433:443",
                "[::1]:443", "[2001:db8::1]:443", "public.example:80", "public.example:8443", "loop.example:443",
                "lan.example:443", "mixed.example:443", "v6.example:443")) {
            assertThat(exchange(proxy, "CONNECT " + target + " HTTP/1.1\r\n\r\n")).as(target)
                    .startsWith("HTTP/1.1 403 ");
        }

        assertThat(lines(13)).hasSize(13).filteredOn(line -> !line.equals("READY"))
                .allMatch(line -> line.startsWith("DENY ")).contains("DENY 127.0.0.1:" + upstream,
                        "DENY loop.example:443", "DENY mixed.example:443", "DENY public.example:80");
    }

    /** A target listed by name keeps what it had: the operator or the workflow named it, whatever it resolves to. */
    @Test
    void aTargetListedByName_isAllowedNextToAStar_whateverItsAddressOrPort() throws Exception {
        int upstream = upstream();
        int proxy = proxy("*", "127.0.0.1:" + upstream, "localhost:" + upstream);

        assertThat(exchange(proxy, "CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\n\r\nping"))
                .startsWith("HTTP/1.1 200 ").endsWith("pong:ping");
        assertThat(exchange(proxy, "CONNECT localhost:" + upstream + " HTTP/1.1\r\n\r\nping"))
                .startsWith("HTTP/1.1 200 ").endsWith("pong:ping");

        assertThat(lines(3)).containsExactly("READY", "ALLOW 127.0.0.1:" + upstream, "ALLOW localhost:" + upstream);
    }

    /** A name that does not resolve was allowed by the star, and failed. */
    @Test
    void aStar_aNameThatDoesNotResolve_isFail() throws Exception {
        int proxy = proxy(Egress.MAX_LINES, host -> {
            throw new java.net.UnknownHostException(host);
        }, "*");

        assertThat(exchange(proxy, "CONNECT nowhere.example:443 HTTP/1.1\r\n\r\n")).startsWith("HTTP/1.1 502 ");
        assertThat(lines(2)).containsExactly("READY", "FAIL nowhere.example:443");
    }

    /** Which addresses are public: each class a star must not reach, and the forms that carry one inside. */
    @Test
    void isPublic_refusesEveryClassThatIsTheMachineOrItsNetwork() {
        for (String internal : List.of("127.0.0.1", "127.8.9.1", "0.0.0.0", "0.1.2.3", "169.254.169.254",
                "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "100.127.255.255",
                "224.0.0.1", "239.1.2.3", "240.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fec0::1",
                "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:192.168.1.1",
                "::127.0.0.1", "::10.1.2.3", "64:ff9b::10.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1",
                "2002:c0a8:101::1")) {
            assertThat(Egress.isPublic(address(internal))).as(internal).isFalse();
        }
        for (String open : List.of("203.0.113.9", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.0.1",
                "172.32.0.1", "192.167.1.1", "2001:4860:4860::8888", "::ffff:8.8.8.8", "64:ff9b::808:808",
                "2002:808:808::1")) {
            assertThat(Egress.isPublic(address(open))).as(open).isTrue();
        }
    }

    @Test
    void aPlainHttpRequest_isDenied_andOnlyItsHostAndPortArePrinted() throws Exception {
        int proxy = proxy("*");

        String get = exchange(proxy, "GET http://Example.org/secret/path?token=abc HTTP/1.1\r\nHost: example.org\r\n"
                + "Authorization: Bearer hidden\r\n\r\nbody-text");
        String withPort = exchange(proxy, "POST http://example.org:8080/x HTTP/1.1\r\n\r\n");
        String originForm = exchange(proxy, "GET /secret HTTP/1.1\r\nHost: example.org\r\n\r\n");

        assertThat(get).startsWith("HTTP/1.1 403 ");
        assertThat(withPort).startsWith("HTTP/1.1 403 ");
        assertThat(originForm).startsWith("HTTP/1.1 403 ");
        assertThat(lines(4)).containsExactly("READY", "DENY example.org:80", "DENY example.org:8080", "DENY ?");
        assertThat(printed.toString(StandardCharsets.UTF_8)).doesNotContain("secret", "token", "hidden", "body-text");
    }

    /** A plain HTTP request is denied also for a listed target: only a CONNECT opens a tunnel. */
    @Test
    void aPlainHttpRequest_toAListedTarget_isDenied() throws Exception {
        int upstream = upstream();
        int proxy = proxy("127.0.0.1:" + upstream);

        String answer = exchange(proxy, "GET http://127.0.0.1:" + upstream + "/x HTTP/1.1\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 403 ");
        assertThat(lines(2)).containsExactly("READY", "DENY 127.0.0.1:" + upstream);
    }

    /** A request line of more than three parts is no request this proxy reads. */
    @Test
    void aRequestLineOfMoreThanThreeParts_isDenied() throws Exception {
        int upstream = upstream();
        int proxy = proxy("127.0.0.1:" + upstream);

        String answer = exchange(proxy, "CONNECT 127.0.0.1:" + upstream + " HTTP/1.1 more\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 403 ");
        assertThat(lines(2)).containsExactly("READY", "DENY ?");
    }

    @Test
    void anAllowedTargetThatCannotBeReached_is502_andPrintedAsFail() throws Exception {
        int closed;
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = taken.getLocalPort();
        }
        int proxy = proxy("127.0.0.1:" + closed);

        String answer = exchange(proxy, "CONNECT 127.0.0.1:" + closed + " HTTP/1.1\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 502 ");
        assertThat(lines(2)).containsExactly("READY", "FAIL 127.0.0.1:" + closed);
    }

    @Test
    void whatIsNoTarget_isDeniedAsAQuestionMark_andNeverPrintedAsWritten() throws Exception {
        int proxy = proxy("*");

        for (String target : List.of("example.org", "example.org:0", "example.org:65536", "exa_mple.org:443",
                "-x.org:443", "example.org:443\tALLOW", "a".repeat(300) + ":443", "user@example.org:443")) {
            assertThat(exchange(proxy, "CONNECT " + target + " HTTP/1.1\r\n\r\n")).as(target)
                    .startsWith("HTTP/1.1 403 ");
        }
        assertThat(exchange(proxy, "\r\n\r\n")).startsWith("HTTP/1.1 403 ");

        assertThat(lines(10)).hasSize(10).startsWith("READY").filteredOn(line -> !line.equals("READY"))
                .containsOnly("DENY ?");
    }

    @Test
    void aRequestHeadOver8KiB_isDenied() throws Exception {
        int upstream = upstream();
        int proxy = proxy("127.0.0.1:" + upstream);

        String answer = exchange(proxy, "CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\nX-Fill: "
                + "x".repeat(Egress.HEAD_LIMIT) + "\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 403 ");
        assertThat(lines(2)).containsExactly("READY", "DENY ?");
    }

    @Test
    void aBracketedIpv6Literal_isATarget() throws Exception {
        int proxy = proxy("api.example:443");

        assertThat(exchange(proxy, "CONNECT [::1]:443 HTTP/1.1\r\n\r\n")).startsWith("HTTP/1.1 403 ");

        assertThat(lines(2)).containsExactly("READY", "DENY [::1]:443");
    }

    @Test
    void afterTheLineLimit_everythingIsDenied_andOneLineSaysSo() throws Exception {
        int upstream = upstream();
        int proxy = proxy(2, "127.0.0.1:" + upstream);
        String connect = "CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\n\r\nping";

        assertThat(exchange(proxy, connect)).startsWith("HTTP/1.1 200 ");
        assertThat(exchange(proxy, connect)).startsWith("HTTP/1.1 200 ");
        assertThat(exchange(proxy, connect)).as("beyond the limit").startsWith("HTTP/1.1 403 ");
        assertThat(exchange(proxy, connect)).startsWith("HTTP/1.1 403 ");

        assertThat(lines(4)).containsExactly("READY", "ALLOW 127.0.0.1:" + upstream, "ALLOW 127.0.0.1:" + upstream,
                "LIMIT");
    }

    @Test
    void theArgumentsAreTheTargets_inLowerCase() {
        assertThat(new Egress(List.of("API.Example:443", "*"), System.out, 1).listed("api.example:443")).isTrue();
        assertThat(new Egress(List.of("API.Example:443", "*"), System.out, 1).listed("other.example:443")).isFalse();
        assertThat(new Egress(List.of("api.example:443"), System.out, 1).listed("other.example:443")).isFalse();
        assertThat(new Egress(List.of(), System.out, 1).listed("api.example:443")).isFalse();
    }

    /** The highest port there is, 65535, is a port like any other. */
    @Test
    void aTargetOnTheHighestPort_isATarget_andAllowedWhenListed() throws Exception {
        int upstream = upstream();
        connector = address -> new Socket(InetAddress.getLoopbackAddress(), upstream);
        int proxy = proxy("127.0.0.1:65535");

        String answer = exchange(proxy, "CONNECT 127.0.0.1:65535 HTTP/1.1\r\n\r\nping");

        assertThat(answer).startsWith("HTTP/1.1 200 ").endsWith("pong:ping");
        assertThat(lines(2)).containsExactly("READY", "ALLOW 127.0.0.1:65535");
    }

    /** The limit of a request head is 8 KiB to the byte: a head of exactly that size is read, one byte more is not. */
    @Test
    void aRequestHeadOfExactly8KiB_isRead_andOneByteMoreIsDenied() throws Exception {
        int upstream = upstream();
        int proxy = proxy("127.0.0.1:" + upstream);
        String start = "CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\nX-Fill: ";
        String end = "\r\n\r\n";
        String atTheLimit = start + "x".repeat(Egress.HEAD_LIMIT - start.length() - end.length()) + end;
        String oneOver = start + "x".repeat(Egress.HEAD_LIMIT - start.length() - end.length() + 1) + end;

        assertThat(atTheLimit).hasSize(Egress.HEAD_LIMIT);
        assertThat(exchange(proxy, atTheLimit + "ping")).startsWith("HTTP/1.1 200 ");
        assertThat(exchange(proxy, oneOver + "ping")).startsWith("HTTP/1.1 403 ");
        assertThat(lines(3)).containsExactly("READY", "ALLOW 127.0.0.1:" + upstream, "DENY ?");
    }

    /**
     * The wait of 15 seconds is for a client that does not send its request. A tunnel that is open has no such
     * limit: a model that thinks for a while sends nothing, and its connection must still be there afterwards.
     * The test waits a little longer than that limit, so it takes about 16 seconds.
     */
    @Test
    void anOpenTunnel_staysOpen_whenTheClientSendsNothingForLongerThanTheRequestWait() throws Exception {
        int upstream = upstream();
        int proxy = proxy("127.0.0.1:" + upstream);

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            in.readNBytes("HTTP/1.1 200 Connection established\r\n\r\n".length());

            Thread.sleep(16_000);
            socket.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            assertThat(new String(in.readNBytes(9), StandardCharsets.US_ASCII)).isEqualTo("pong:ping");
        }
    }

    /**
     * An HTTP server that keeps the head and the first {@code bodyBytes} bytes of the body of each request it gets,
     * answers {@code hello} and closes, as a server does that was told {@code Connection: close}.
     */
    private int httpServer(List<String> received, int bodyBytes) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        open.add(server);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    StringBuilder request = new StringBuilder();
                    while (!request.toString().endsWith("\r\n\r\n")) {
                        int b = in.read();
                        if (b < 0) {
                            break;
                        }
                        request.append((char) b);
                    }
                    request.append(new String(in.readNBytes(bodyBytes), StandardCharsets.US_ASCII));
                    received.add(request.toString());
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 5\r\n\r\nhello"
                            .getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                } catch (IOException e) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    /**
     * A plain target is a server of the operator's that speaks HTTP without TLS: a plain request to it is sent on
     * in origin form, without the headers meant for the proxy and with {@code Connection: close}, its body and the
     * answer travel as they are, and the line names the target only.
     */
    @Test
    void aPlainHttpRequest_toAPlainTarget_isForwardedInOriginForm_andPrintedAsAllow() throws Exception {
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        int upstream = httpServer(received, 4);
        int proxy = proxy("HTTP://127.0.0.1:" + upstream);

        String answer = exchange(proxy, "POST http://127.0.0.1:" + upstream + "/v1/chat/completions?x=1 HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + upstream + "\r\nProxy-Connection: keep-alive\r\n"
                + "Proxy-Authorization: Basic abc\r\nConnection: keep-alive\r\nContent-Length: 4\r\n"
                + "Authorization: Bearer hidden\r\n\r\nbody");

        assertThat(answer).isEqualTo("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 5\r\n\r\nhello");
        assertThat(received).containsExactly("POST /v1/chat/completions?x=1 HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + upstream + "\r\nContent-Length: 4\r\nAuthorization: Bearer hidden\r\n"
                + "Connection: close\r\n\r\nbody");
        assertThat(lines(2)).containsExactly("READY", "ALLOW 127.0.0.1:" + upstream);
        assertThat(printed.toString(StandardCharsets.UTF_8)).doesNotContain("chat", "hidden", "body");
    }

    /** A plain target is a listed target too: a tunnel to it opens as to any other. */
    @Test
    void aPlainTarget_isTunnelledToo() throws Exception {
        int upstream = upstream();
        int proxy = proxy("http://127.0.0.1:" + upstream);

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("CONNECT 127.0.0.1:" + upstream + " HTTP/1.1\r\n\r\nping")
                    .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            String established = "HTTP/1.1 200 Connection established\r\n\r\n";
            assertThat(new String(in.readNBytes(established.length()), StandardCharsets.US_ASCII))
                    .isEqualTo(established);
            assertThat(new String(in.readNBytes(9), StandardCharsets.US_ASCII)).isEqualTo("pong:ping");
        }
        assertThat(lines(2)).containsExactly("READY", "ALLOW 127.0.0.1:" + upstream);
    }

    /** Next to a plain target every other target is as before: a plain request to it is denied, listed or not. */
    @Test
    void aPlainHttpRequest_toAnyOtherTarget_staysDenied_nextToAPlainTarget() throws Exception {
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        int plain = httpServer(received, 0);
        int listed = httpServer(received, 0);
        int proxy = proxy("http://127.0.0.1:" + plain, "127.0.0.1:" + listed, "*");

        String toListed = exchange(proxy, "GET http://127.0.0.1:" + listed + "/x HTTP/1.1\r\n\r\n");
        String toOther = exchange(proxy, "GET http://example.org/x HTTP/1.1\r\n\r\n");
        String otherPort = exchange(proxy, "GET http://127.0.0.1:1/x HTTP/1.1\r\n\r\n");

        assertThat(toListed).startsWith("HTTP/1.1 403 ");
        assertThat(toOther).startsWith("HTTP/1.1 403 ");
        assertThat(otherPort).startsWith("HTTP/1.1 403 ");
        assertThat(received).isEmpty();
        assertThat(lines(4)).containsExactly("READY", "DENY 127.0.0.1:" + listed, "DENY example.org:80",
                "DENY 127.0.0.1:1");
    }

    /** A plain target that cannot be reached is a failed connection, as for a tunnel. */
    @Test
    void aPlainTargetThatCannotBeReached_is502_andPrintedAsFail() throws Exception {
        connector = address -> {
            throw new IOException("refused");
        };
        int proxy = proxy("http://127.0.0.1:9");

        String answer = exchange(proxy, "GET http://127.0.0.1:9/x HTTP/1.1\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 502 ");
        assertThat(lines(2)).containsExactly("READY", "FAIL 127.0.0.1:9");
    }

    /** The head a server gets: the path of the URL, {@code /} without one, and never the fragment. */
    @Test
    void theForwardedHead_namesThePathOfTheUrl() {
        assertThat(Egress.forwarded("GET http://Host.example:80 HTTP/1.1\r\nHost: host.example\r\n\r\n",
                new String[] {"GET", "http://Host.example:80", "HTTP/1.1"}))
                .isEqualTo("GET / HTTP/1.1\r\nHost: host.example\r\nConnection: close\r\n\r\n");
        assertThat(Egress.forwarded("GET http://h:1?q=A#f HTTP/1.0\r\nKeep-Alive: 5\r\nX-Y: z\r\n\r\n",
                new String[] {"GET", "http://h:1?q=A#f", "HTTP/1.0"}))
                .isEqualTo("GET /?q=A HTTP/1.0\r\nX-Y: z\r\nConnection: close\r\n\r\n");
    }
}
