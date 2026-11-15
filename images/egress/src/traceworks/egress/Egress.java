package traceworks.egress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only door of an AI step's container: an HTTP proxy that tunnels {@code CONNECT host:port} to the targets it was
 * started with, and refuses everything else. Traceworks starts it next to the step's container, which has no other
 * way out, and reads what it printed into the run's record.
 *
 * <ul>
 *   <li><b>Arguments:</b> the allowed targets, each {@code host:port}; {@code *} also allows any public host on
 *       port 443.</li>
 *   <li><b>A star is no door to the machine or its network.</b> A target that only the star allows is refused when
 *       its port is not 443, when it is an IP literal, and when its name resolves to any address that is not public
 *       ({@link #isPublic}). The name is resolved once, and the connection goes to the address that was checked, so
 *       a second lookup cannot answer differently. A target listed by name is connected to as it is: whoever listed
 *       it named it.</li>
 *   <li><b>Output:</b> {@code READY} once it listens, then exactly one line per request: {@code ALLOW <host:port>},
 *       {@code DENY <host:port>} or {@code FAIL <host:port>} (allowed, but the connection failed). Nothing else of a
 *       request is ever printed: no path, no header, no body. What is no target is printed as {@code ?}.</li>
 *   <li><b>Comparison:</b> a target is compared as the request writes it, in lower case. The address a listed name
 *       resolves to is not listed, so an IP literal passes only when it is listed itself.</li>
 *   <li><b>Refusals:</b> a request that is not {@code CONNECT} (plain HTTP), a target that is not listed and a
 *       request head over {@value #HEAD_LIMIT} bytes get {@code 403} and the connection is closed.</li>
 *   <li><b>A bounded log:</b> after {@value #MAX_LINES} lines it prints {@code LIMIT} once and refuses every further
 *       request without a line, so a container cannot fill the log it is recorded from.</li>
 * </ul>
 *
 * It uses the JDK only, and is compiled when the image is built.
 */
public final class Egress {

    /** The port the proxy listens on in its container. */
    static final int PORT = 3128;

    /** The most a request head may have, in bytes. */
    static final int HEAD_LIMIT = 8192;

    /** The most request lines the proxy prints. */
    static final int MAX_LINES = 10_000;

    /** How long a client may take to send its request head, and a target to accept the connection. */
    private static final int WAIT_MILLIS = 15_000;

    /** How much of what a refused client still sends is read and dropped, and for how long. */
    private static final int DRAIN_BYTES = 64 * 1024;
    private static final int DRAIN_MILLIS = 1_000;

    private static final String ANY = "*";

    /** A host name or an IPv4 literal, or an IPv6 literal in brackets, with a port. */
    private static final Pattern TARGET = Pattern.compile(
            "([a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?|\\[[0-9a-f:.]{2,45}]):([1-9][0-9]{0,4})");

    private static final Pattern HTTP_URL = Pattern.compile("http://([^/?#\\s]+)(?:[/?#]\\S*)?");

    /** Resolves a host name to its addresses; the JDK's lookup, or a test's. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    /** Opens the connection to a target; the JDK's socket, or a test's. */
    @FunctionalInterface
    interface Connector {
        Socket connect(InetSocketAddress address) throws IOException;
    }

    /** The only port a star allows. */
    private static final int STAR_PORT = 443;

    /** What an IPv4 literal looks like in any of its numeric forms: digits and dots only. */
    private static final Pattern NUMERIC = Pattern.compile("[0-9.]+");

    private final Set<String> allowed = new HashSet<>();
    private final boolean any;
    private final PrintStream log;
    private final int maxLines;
    private final Resolver resolver;
    private final Connector connector;
    private int lines;

    /**
     * @param targets  the allowed targets, compared in lower case; {@code *} for any
     * @param log      where the lines go
     * @param maxLines how many request lines are printed before {@code LIMIT}
     */
    Egress(Collection<String> targets, PrintStream log, int maxLines) {
        this(targets, log, maxLines, InetAddress::getAllByName, Egress::connected);
    }

    /**
     * @param resolver  resolves the names a star allows
     * @param connector opens the connection to a target
     */
    Egress(Collection<String> targets, PrintStream log, int maxLines, Resolver resolver, Connector connector) {
        this.connector = connector;
        targets.forEach(target -> allowed.add(target.toLowerCase(Locale.ROOT)));
        this.any = allowed.remove(ANY);
        this.log = log;
        this.maxLines = maxLines;
        this.resolver = resolver;
    }

    public static void main(String[] args) throws IOException {
        Egress egress = new Egress(Set.of(args), System.out, MAX_LINES);
        try (ServerSocket server = new ServerSocket(PORT)) {
            egress.serve(server);
        }
    }

    private static Socket connected(InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(address, WAIT_MILLIS);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** Whether {@code target}, in lower case, is listed by name. */
    boolean listed(String target) {
        return allowed.contains(target);
    }

    /**
     * Whether {@code address} is a public one: not the loopback, not the any-local address, not link-local,
     * site-local (10/8, 172.16/12, 192.168/16), carrier-grade NAT (100.64/10), multicast, "this network" (0/8) or
     * reserved (240/4), and for IPv6 also not unique-local (fc00::/7). An IPv6 address that carries an IPv4 one
     * (IPv4-mapped, IPv4-compatible, NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16}) is as public as that one.
     */
    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first != 0 && first < 240 && !(first == 100 && second >= 64 && second <= 127);
        }
        if ((b[0] & 0xfe) == 0xfc) {
            return false;
        }
        byte[] embedded = null;
        if (address instanceof Inet6Address six && six.isIPv4CompatibleAddress()) {
            embedded = new byte[] {b[12], b[13], b[14], b[15]};
        } else if (b[0] == 0 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && zero(b, 4, 12)) {
            embedded = new byte[] {b[12], b[13], b[14], b[15]};
        } else if (b[0] == 0x20 && b[1] == 0x02) {
            embedded = new byte[] {b[2], b[3], b[4], b[5]};
        } else if (zero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            embedded = new byte[] {b[12], b[13], b[14], b[15]};
        }
        try {
            return embedded == null || isPublic(InetAddress.getByAddress(embedded));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean zero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The address a target that only the star allows is connected to: the first of the addresses its name resolves
     * to, when the port is 443, the host is a name and every one of its addresses is public; {@code null} when the
     * star does not allow it.
     *
     * @throws IOException when the name does not resolve
     */
    private InetAddress starAddress(String host, int port) throws IOException {
        if (port != STAR_PORT || host.startsWith("[") || NUMERIC.matcher(host).matches()) {
            return null;
        }
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("no address");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                return null;
            }
        }
        return addresses[0];
    }

    /** Prints {@code READY} and serves every connection of {@code server} on a thread of its own, until it closes. */
    void serve(ServerSocket server) {
        log.println("READY");
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException e) {
                // the server was closed, or one connection could not be accepted: the next one may
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout(WAIT_MILLIS);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Request request = request(in);
            boolean counted = counted();
            String target = request.target();
            if (!counted || !request.connect() || !any && !listed(target)) {
                if (counted) {
                    print("DENY", target);
                }
                refuse(client, out, "403 Forbidden");
                return;
            }
            Matcher parts = TARGET.matcher(target);
            parts.matches();
            String host = parts.group(1);
            int port = Integer.parseInt(parts.group(2));
            InetSocketAddress address;
            try {
                if (listed(target)) {
                    address = new InetSocketAddress(host.startsWith("[") ? host.substring(1, host.length() - 1)
                            : host, port);
                } else {
                    InetAddress checked = starAddress(host, port);
                    if (checked == null) {
                        print("DENY", target);
                        refuse(client, out, "403 Forbidden");
                        return;
                    }
                    address = new InetSocketAddress(checked, port);
                }
            } catch (IOException | RuntimeException e) {
                print("FAIL", target);
                refuse(client, out, "502 Bad Gateway");
                return;
            }
            Socket connected;
            try {
                connected = connector.connect(address);
            } catch (IOException | RuntimeException e) {
                print("FAIL", target);
                refuse(client, out, "502 Bad Gateway");
                return;
            }
            try (Socket upstream = connected) {
                print("ALLOW", target);
                client.setSoTimeout(0);
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread up = Thread.ofVirtual().start(() -> pump(in, upstream));
                pump(upstream.getInputStream(), client);
                up.join();
            }
        } catch (IOException | InterruptedException e) {
            // a broken client ends its own connection, and no other
        }
    }

    /**
     * Counts one request, whose line the caller prints, and says whether it is within the limit. The first request
     * beyond it prints {@code LIMIT}; that one and every later one get no line and no connection.
     */
    private boolean counted() {
        synchronized (log) {
            if (lines < maxLines) {
                lines++;
                return true;
            }
            if (lines == maxLines) {
                lines++;
                log.println("LIMIT");
            }
            return false;
        }
    }

    private void print(String verdict, String target) {
        synchronized (log) {
            log.println(verdict + " " + target);
        }
    }

    /**
     * What a request asks for.
     *
     * @param target  {@code host:port} in lower case; {@code ?} for whatever is no target
     * @param connect whether it is a {@code CONNECT} with a target, the only request that can be allowed
     */
    private record Request(String target, boolean connect) {
        static final Request NONE = new Request("?", false);
    }

    /**
     * The request whose head {@code in} holds: the target of a {@code CONNECT}, or the host and port of the URL of a
     * plain HTTP request, which is never allowed; {@link Request#NONE} for a head over the limit and for whatever
     * names no target.
     */
    private static Request request(InputStream in) throws IOException {
        String head = head(in);
        if (head == null) {
            return Request.NONE;
        }
        String[] line = head.split("\r\n", 2)[0].split(" ");
        if (line.length != 3) {
            return Request.NONE;
        }
        String written = line[1].toLowerCase(Locale.ROOT);
        if (line[0].equals("CONNECT")) {
            return valid(written) ? new Request(written, true) : Request.NONE;
        }
        Matcher url = HTTP_URL.matcher(written);
        if (!url.matches()) {
            return Request.NONE;
        }
        String authority = url.group(1);
        if (!authority.matches(".*:[0-9]+")) {
            authority += ":80";
        }
        return valid(authority) ? new Request(authority, false) : Request.NONE;
    }

    private static boolean valid(String target) {
        Matcher matcher = TARGET.matcher(target);
        return matcher.matches() && Integer.parseInt(matcher.group(2)) <= 65535;
    }

    /** The request head up to its empty line; {@code null} when it is over the limit or ends before that line. */
    private static String head(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0 || head.size() >= HEAD_LIMIT) {
                return null;
            }
            head.write(b);
            boolean expected = (state % 2 == 0) ? b == '\r' : b == '\n';
            state = expected ? state + 1 : b == '\r' ? 1 : 0;
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    /**
     * Answers with {@code status} and ends the connection: the answer first, then what the client still sends is read
     * for a moment and dropped, so the client gets the answer and not a reset.
     */
    private static void refuse(Socket client, OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        client.shutdownOutput();
        client.setSoTimeout(DRAIN_MILLIS);
        try {
            client.getInputStream().skipNBytes(DRAIN_BYTES);
        } catch (IOException e) {
            // the client closed, or sent nothing more in time
        }
    }

    private static void pump(InputStream in, Socket to) {
        try {
            in.transferTo(to.getOutputStream());
        } catch (IOException e) {
            // one side closed: the tunnel ends
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException e) {
                // already closed
            }
        }
    }
}
