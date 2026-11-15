package traceworks.adapters.container;

import traceworks.core.port.CommandRunner.Connection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the egress proxy printed (the image {@code egress}), read from its container's log: {@code READY} once it
 * listens, then one line per request, {@code ALLOW <target>}, {@code DENY <target>} or {@code FAIL <target>}, and
 * {@code LIMIT} when it reached the end of its log. Only lines of exactly that shape count, and only lines that
 * ended: whatever else the log holds never reaches the record.
 */
final class EgressLog {

    private static final String READY = "READY";

    /** A line of the proxy: a target is {@code host:port}, {@code [ipv6]:port} or {@code ?}. */
    private static final Pattern LINE = Pattern.compile("(ALLOW|DENY|FAIL) ([a-z0-9.\\-\\[\\]:]{1,270}|\\?)");

    private EgressLog() {
    }

    /** Whether the proxy said that it listens. */
    static boolean ready(String log) {
        return ended(log).contains(READY);
    }

    /**
     * The requests by target and verdict, counted, in the order of their first appearance. The requests to targets
     * beyond the first {@value Connection#MAX_TARGETS} are counted together, by verdict, under
     * {@value Connection#OTHER_TARGETS}, so a container that asks for ever new targets cannot make the record grow
     * without bound. The proxy's {@code LIMIT} is a connection to {@code *} with that verdict.
     */
    static List<Connection> connections(String log) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, int[]> others = new LinkedHashMap<>();
        boolean limit = false;
        for (String line : ended(log)) {
            if (line.equals(Connection.LIMIT)) {
                limit = true;
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1) + " " + matcher.group(2);
            int[] count = counts.get(key);
            if (count == null && counts.size() >= Connection.MAX_TARGETS) {
                others.computeIfAbsent(matcher.group(1), verdict -> new int[1])[0]++;
            } else if (count == null) {
                counts.put(key, new int[] {1});
            } else {
                count[0]++;
            }
        }
        List<Connection> connections = new ArrayList<>();
        counts.forEach((key, count) -> connections.add(new Connection(key.substring(key.indexOf(' ') + 1),
                key.substring(0, key.indexOf(' ')), count[0])));
        others.forEach((verdict, count) -> connections.add(new Connection(Connection.OTHER_TARGETS, verdict,
                count[0])));
        if (limit) {
            connections.add(new Connection("*", Connection.LIMIT, 1));
        }
        return connections;
    }

    /** The lines of the log that ended with a line feed; a last line that broke off is no line. */
    private static List<String> ended(String log) {
        if (log == null) {
            return List.of();
        }
        return log.substring(0, log.lastIndexOf('\n') + 1).lines().map(line -> line.endsWith("\r")
                ? line.substring(0, line.length() - 1) : line).toList();
    }
}
