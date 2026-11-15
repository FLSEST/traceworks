package traceworks.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The containers and networks a run left behind, asked of the runtime's own client: every container of a run, and
 * every network of an AI step's egress proxy, carries the label {@value #RUN_LABEL} with the run id as its value
 * (docs/security.md, docs/cli.md "resume").
 */
final class Containers {

    /** The label of a run's containers, as docs/cli.md names it. */
    static final String RUN_LABEL = "traceworks.run";

    /** How long one call of the client may take. */
    private static final long CLIENT_TIMEOUT_SECONDS = 60;

    private Containers() {
    }

    /** The names of the containers of run {@code runId}, running or not. */
    static List<String> of(String runtime, String runId) {
        String printed = client(runtime, "ps", "-a", "--filter", "label=" + RUN_LABEL + "=" + runId, "--format",
                "{{.Names}}");
        return printed.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * Fails when a container of any of {@code runIds} is left, naming each with its run; the containers found are
     * removed first, so one failed test does not leave them for the next.
     */
    static void assertNoneLeft(String runtime, Collection<String> runIds) {
        Map<String, List<String>> left = new LinkedHashMap<>();
        for (String runId : runIds) {
            List<String> names = of(runtime, runId);
            if (!names.isEmpty()) {
                left.put(runId, names);
                List<String> remove = new ArrayList<>(List.of("rm", "-f"));
                remove.addAll(names);
                client(runtime, remove.toArray(String[]::new));
            }
        }
        if (!left.isEmpty()) {
            throw new AssertionError("Containers left behind (now removed), by run: " + left);
        }
    }

    /** The names of the networks of run {@code runId}: the two its containers behind an egress proxy share. */
    static List<String> networksOf(String runtime, String runId) {
        String printed = client(runtime, "network", "ls", "--filter", "label=" + RUN_LABEL + "=" + runId, "--format",
                "{{.Name}}");
        return printed.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * Watches the networks Docker creates, from now until {@link #close}: for what a run costs the host, where every
     * network is a bridge device. It follows the runtime's events as they come: the daemon keeps only its last few
     * hundred events, so asking afterwards would miss the networks of a run with many containers. The stream starts
     * a second in the past, so a network created while the client attaches is in it too.
     */
    static final class NetworkWatch implements AutoCloseable {

        private final Process events;
        private final Path output;

        NetworkWatch(String runtime) {
            try {
                output = Files.createTempFile("traceworks-it-networks-", ".txt");
                events = new ProcessBuilder(runtime, "events", "--since",
                        Long.toString(java.time.Instant.now().getEpochSecond() - 1), "--filter", "type=network",
                        "--filter", "event=create", "--format", "{{.Actor.Attributes.name}}")
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                events.getOutputStream().close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** The names of the networks created so far, in order; the event of the last one may take a moment. */
        List<String> created() {
            try {
                return Files.readString(output, StandardCharsets.UTF_8).lines().map(String::strip)
                        .filter(line -> !line.isEmpty()).toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            events.destroyForcibly();
            try {
                events.waitFor(10, TimeUnit.SECONDS);
                Files.deleteIfExists(output);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Fails when a network of any of {@code runIds} is left, naming each with its run; the networks found are removed
     * first. Call it after {@link #assertNoneLeft}, which removes the containers that would hold them.
     */
    static void assertNoNetworkLeft(String runtime, Collection<String> runIds) {
        Map<String, List<String>> left = new LinkedHashMap<>();
        for (String runId : runIds) {
            List<String> names = networksOf(runtime, runId);
            if (!names.isEmpty()) {
                left.put(runId, names);
                List<String> remove = new ArrayList<>(List.of("network", "rm"));
                remove.addAll(names);
                client(runtime, remove.toArray(String[]::new));
            }
        }
        if (!left.isEmpty()) {
            throw new AssertionError("Networks left behind (now removed), by run: " + left);
        }
    }

    /** Runs the client and returns what it printed; fails when it does not end with 0 in time. */
    private static String client(String runtime, String... arguments) {
        List<String> command = new ArrayList<>(List.of(runtime));
        command.addAll(List.of(arguments));
        try {
            Path output = Files.createTempFile("traceworks-it-client-", ".txt");
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(output.toFile()).start();
                process.getOutputStream().close();
                boolean ended = process.waitFor(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!ended) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                }
                String printed = Files.readString(output, StandardCharsets.UTF_8);
                if (!ended || process.exitValue() != 0) {
                    throw new AssertionError(command + (ended ? " ended with " + process.exitValue()
                            : " did not end within " + CLIENT_TIMEOUT_SECONDS + " s") + ": " + printed);
                }
                return printed;
            } finally {
                Files.deleteIfExists(output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while " + command + " ran", e);
        }
    }
}
