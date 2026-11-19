package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where an AI step's container can connect (packages {@code ai-egress} and {@code ai-egress-slow}; docs/security.md,
 * docs/cli.md "The network of an AI step"): only through the egress proxy, to the hosts of its provider and the
 * operator's {@code ai_hosts}, with every connection in the record; anywhere with {@code ai_network: "open"}, as
 * before the allowlist.
 *
 * <p>Nothing here needs the internet. The target a container may reach is a socket this test opens on this machine
 * ({@link ItEnvironment#machine()}), which the proxy reaches through its outside network; the target it may not
 * reach is a name nobody listed, which the proxy refuses before anything is resolved. The fake Claude CLI asks its proxy for both and tries
 * both directly ({@code [fake:connect=...]}).
 */
class AiEgressIT extends AiIntegrationTest {

    private ServerSocket listener;
    private final AtomicInteger accepted = new AtomicInteger();
    private String target;

    /** A socket on every address of this machine that counts its connections: the host a container may reach. */
    @BeforeEach
    void listenOnTheMachinesOwnAddress() throws IOException {
        listener = new ServerSocket(0);
        target = ItEnvironment.get().machine() + ":" + listener.getLocalPort();
        Thread.ofVirtual().start(() -> {
            while (!listener.isClosed()) {
                try (Socket connection = listener.accept()) {
                    accepted.incrementAndGet();
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void closeTheListener() throws IOException {
        listener.close();
    }


    private JsonNode command(RunFolder folder, String step) {
        return folder.ofStep("step.command", folder.stepIndex(step)).getFirst();
    }

    private JsonNode finished(RunFolder folder, String step) {
        return folder.ofStep("step.command.finished", folder.stepIndex(step)).getFirst();
    }

    /**
     * A listed target is reached through the proxy and only through it; a target nobody listed is denied, the record
     * says so, {@code show} and {@code run.html} mark it, and the step and the run complete all the same.
     */
    @Test
    void aListedTargetIsReached_anyOtherIsDenied_andTheRecordNamesBoth() {
        traceworks.configure(Map.of("ai_hosts", Map.of("claude", List.of(target))));

        Result run = traceworks.run("ai-egress", "--input", "target=" + target);

        assertThat(run.code()).as("a denied connection fails nothing\n" + run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("connect")).isEqualTo("COMPLETED");
        String output = folder.output("connect");
        assertThat(output).contains("proxy: http://traceworks-" + folder.id() + "-1-egress:3128",
                "via proxy " + target + ": HTTP/1.1 200 Connection established",
                "via proxy not-listed.example:443: HTTP/1.1 403 Forbidden");
        assertThat(output).as("the container has no way out but the proxy")
                .containsPattern("direct " + java.util.regex.Pattern.quote(target) + ": (error|timeout)")
                .containsPattern("direct not-listed\\.example:443: (error|timeout)")
                .doesNotContain(": connected");
        assertThat(accepted.get()).as("the one connection the proxy made").isEqualTo(1);

        JsonNode command = command(folder, "connect");
        assertThat(command.path("network").asText()).isEqualTo("hosts");
        assertThat(command.path("network_hosts")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("api.anthropic.com:443", target).isSorted();
        assertThat(command.path("env_names")).extracting(JsonNode::asText)
                .as("the proxy's address is no variable of the step").doesNotContain("HTTPS_PROXY", "HTTP_PROXY");
        JsonNode egress = finished(folder, "connect").path("egress");
        assertThat(egress).hasSize(2);
        assertThat(egress.get(0).toString()).isEqualTo("{\"target\":\"" + target + "\",\"verdict\":\"ALLOW\","
                + "\"count\":1}");
        assertThat(egress.get(1).toString()).isEqualTo("{\"target\":\"not-listed.example:443\",\"verdict\":\"DENY\","
                + "\"count\":1}");
        JsonNode started = folder.only("run.started");
        assertThat(started.path("ai_network").asText()).isEqualTo("restricted");
        assertThat(started.path("ai_hosts").path("claude")).extracting(JsonNode::asText).containsExactly(target);
        assertThat(run.err()).contains("traceworks: note: ai_hosts: the containers of claude also reach " + target);

        Result show = traceworks.exec("show", folder.id());
        assertThat(show.out()).contains("AI net    restricted; more hosts for claude: " + target,
                "Egress    2 connections asked of the egress proxy by 1 command, 1 DENIED",
                "egress ALLOW   " + target + "  x1",
                "egress DENIED  not-listed.example:443  x1   <== not allowed");
        assertThat(folder.html()).contains("egress ALLOW <code>" + target + "</code> &times;1",
                "<strong class=\"problem\">egress DENIED <code>not-listed.example:443</code> &times;1</strong>",
                "<strong class=\"problem\">1 denied</strong>");
    }

    /** Without the operator's {@code ai_hosts} the same target is not listed: only the provider's host is. */
    @Test
    void withoutTheOperatorsHost_onlyTheProvidersHostIsListed_andTheTargetIsDenied() {
        Result run = traceworks.run("ai-egress", "--input", "target=" + target);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("connect")).contains("via proxy " + target + ": HTTP/1.1 403 Forbidden")
                .doesNotContain("200 Connection established", ": connected");
        assertThat(accepted.get()).as("nothing reached the machine").isZero();
        assertThat(command(folder, "connect").path("network_hosts")).extracting(JsonNode::asText)
                .containsExactly("api.anthropic.com:443");
        assertThat(finished(folder, "connect").path("egress")).extracting(c -> c.path("verdict").asText())
                .containsExactly("DENY", "DENY");
        assertThat(folder.only("run.started").path("ai_hosts")).isEmpty();
    }

    /** {@code ai_network: "open"}: the runtime's default network and no proxy, as before, and a note says so. */
    @Test
    void anOpenAiNetwork_isTheDefaultNetworkWithoutAProxy_asBefore() {
        traceworks.configure(Map.of("ai_network", "open"));

        Result run = traceworks.run("ai-egress", "--input", "target=" + target);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("connect")).contains("proxy: none", "direct " + target + ": connected")
                .doesNotContain("via proxy");
        assertThat(accepted.get()).isEqualTo(1);
        JsonNode command = command(folder, "connect");
        assertThat(command.path("network").asText()).isEqualTo("open");
        assertThat(command.has("network_hosts")).isFalse();
        assertThat(finished(folder, "connect").has("egress")).isFalse();
        assertThat(folder.only("run.started").path("ai_network").asText()).isEqualTo("open");
        assertThat(run.err()).contains("traceworks: note: ai_network: open: the container of an AI step can connect"
                + " anywhere, not only to its provider");
        assertThat(traceworks.exec("show", folder.id()).out())
                .contains("AI net    open (every AI container can connect anywhere)").doesNotContain("Egress  ");
    }

    /** Each provider's calls list that provider's host: Pi's default backend is Anthropic's API too. */
    @Test
    void eachCliListsTheHostOfItsProvider_andAFakeThatConnectsNowhereAskedForNothing() {
        Result run = traceworks.run("ai-simple");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        for (String step : new String[] {"claude_say", "pi_say"}) {
            assertThat(command(folder, step).path("network_hosts")).as(step).extracting(JsonNode::asText)
                    .containsExactly("api.anthropic.com:443");
            assertThat(finished(folder, step).path("egress").isArray()).as(step).isTrue();
            assertThat(finished(folder, step).path("egress")).as(step).isEmpty();
        }
        assertThat(traceworks.exec("show", folder.id()).out())
                .contains("Egress    0 connections asked of the egress proxy by 2 commands, none denied");
    }

    /** After a step that ran out of time, its proxy and both networks are gone, and its connections are recorded. */
    @Test
    void afterATimeout_theProxyAndTheNetworksAreGone() {
        Result run = traceworks.run("ai-timeout");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(finished(folder, "claude").path("timed_out").asBoolean()).isTrue();
        assertThat(finished(folder, "claude").path("egress").isArray()).isTrue();
        String runtime = traceworks.environment().runtime();
        assertThat(Containers.of(runtime, folder.id())).as("containers of the run").isEmpty();
        assertThat(Containers.networksOf(runtime, folder.id())).as("networks of the run").isEmpty();
    }

    /**
     * A cancel while the step runs behind its proxy ends the step, and removes its container, the proxy and both
     * networks: while it runs they are there, afterwards nothing of the run is.
     */
    @Test
    void afterACancel_theProxyAndTheNetworksAreGone() throws Exception {
        String runtime = traceworks.environment().runtime();
        CompletableFuture<Result> running = CompletableFuture.supplyAsync(() -> traceworks.run("ai-egress-slow"));
        String id = awaitRunThatRecorded("step.command");
        awaitContainers(runtime, id, 2);
        List<String> containers = Containers.of(runtime, id);
        List<String> networks = Containers.networksOf(runtime, id);

        Result cancel = traceworks.exec("cancel", id);
        Result run = running.get(90, TimeUnit.SECONDS);

        assertThat(containers).containsExactlyInAnyOrder("traceworks-" + id + "-1", "traceworks-" + id + "-1-egress");
        assertThat(networks).containsExactlyInAnyOrder("traceworks-" + id + "-net", "traceworks-" + id + "-out");
        assertThat(cancel.code()).as(cancel.describe()).isZero();
        assertThat(run.code()).as(run.describe()).isEqualTo(4);
        assertThat(Containers.of(runtime, id)).as("containers of the run").isEmpty();
        assertThat(Containers.networksOf(runtime, id)).as("networks of the run").isEmpty();
        RunFolder folder = traceworks.folder(id);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(finished(folder, "slow").path("egress").isArray()).as("the proxy's log was still read").isTrue();
    }

    /** The id of the one run in the home, once its events hold a line of {@code type}; within 60 seconds. */
    private String awaitRunThatRecorded(String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Path runs = traceworks.home().resolve("runs");
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(runs)) {
                try (Stream<Path> found = Files.walk(runs, 2)) {
                    for (Path dir : found.filter(path -> Files.isRegularFile(path.resolve("events.jsonl"))).toList()) {
                        if (Files.readString(dir.resolve("events.jsonl")).contains("\"type\":\"" + type + "\"")) {
                            return dir.getFileName().toString();
                        }
                    }
                } catch (UncheckedIOException e) {
                    // a file of the run changed under the walk: look again
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No run recorded " + type + " within 60 s");
    }

    /** Waits until the run has {@code count} containers, the step's and its proxy; within 60 seconds. */
    private static void awaitContainers(String runtime, String runId, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (Containers.of(runtime, runId).size() < count) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Run " + runId + " did not get " + count + " containers within 60 s: "
                        + Containers.of(runtime, runId));
            }
            Thread.sleep(200);
        }
    }
}
