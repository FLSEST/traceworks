package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web tools of an AI step (package {@code ai-web}; docs/cli.md "Web tools of an AI step", docs/security.md): the
 * {@code web} switch next to {@code tools}, {@code web_hosts} in the call's egress, {@code ai.web} in
 * {@code requires}, the refusals of the run admission and of {@code check}, and what a star refuses.
 *
 * <p>No model is called and nothing needs the internet: the fake Claude shows the tool list it was given, and for the
 * star it asks its proxy for a socket this test opens on the machine's own address.
 */
class AiWebIT extends AiIntegrationTest {

    private JsonNode request(RunFolder folder, String step) {
        return folder.ofStep("ai.request", folder.stepIndex(step)).getFirst();
    }

    private JsonNode command(RunFolder folder, String step) {
        return folder.ofStep("step.command", folder.stepIndex(step)).getFirst();
    }

    /**
     * Web is a switch of its own: {@code tools: none} with {@code web: search} gives the CLI exactly its search tool,
     * {@code fetch} also the page fetch, and only {@code fetch} widens where the container can connect, by its
     * {@code web_hosts}. The record and the views name the mode next to the tools.
     */
    @Test
    void webReachesTheCliAsItsTools_andOnlyFetchWidensTheNetwork() {
        Result run = traceworks.run("ai-web");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("search")).contains("[--tools] [WebSearch] [--dangerously-skip-permissions]");
        assertThat(folder.output("fetch"))
                .contains("[--tools] [Read,Grep,Glob,WebSearch,WebFetch] [--dangerously-skip-permissions]");
        assertThat(folder.output("wrapped")).as("the AI step of a wrapper")
                .contains("[--tools] [WebSearch] [--dangerously-skip-permissions]");
        assertThat(folder.output("plain")).contains("[--tools] []").doesNotContain("WebSearch");

        assertThat(request(folder, "search").path("tools").asText()).isEqualTo("none");
        assertThat(request(folder, "search").path("web").asText()).isEqualTo("search");
        assertThat(request(folder, "search").has("web_hosts")).isFalse();
        assertThat(request(folder, "fetch").path("web").asText()).isEqualTo("fetch");
        assertThat(request(folder, "fetch").path("web_hosts")).extracting(JsonNode::asText)
                .containsExactly("docs.example.org:443", "mirror.example.org:8443");
        assertThat(request(folder, "plain").path("web").asText()).isEqualTo("none");
        assertThat(command(folder, "search").path("network_hosts")).as("search runs at the provider")
                .extracting(JsonNode::asText).containsExactly("api.anthropic.com:443");
        assertThat(command(folder, "fetch").path("network_hosts")).extracting(JsonNode::asText)
                .containsExactly("api.anthropic.com:443", "docs.example.org:443", "mirror.example.org:8443");

        Result show = traceworks.exec("show", folder.id());
        assertThat(show.out()).contains("tools none  web search", "tools read  web fetch (docs.example.org:443,"
                + " mirror.example.org:8443)", "simple web search", "simple web fetch");
        assertThat(folder.html()).contains("tools <code>none</code> web <code>search</code>",
                "web <code>fetch</code> <code>docs.example.org:443</code> <code>mirror.example.org:8443</code>");
    }

    /**
     * What a model reads from the web is untrusted: a step with web gets the notice a step with
     * {@code untrusted_input: true} gets, and an instruction aimed at an AI in its input turns the run's gate yellow.
     */
    @Test
    void aStepWithWeb_runsAsWithUntrustedInput() {
        Result run = traceworks.run("ai-web");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        for (String step : new String[] {"search", "fetch", "wrapped", "injected"}) {
            assertThat(folder.blob(request(folder, step).path("system_prompt"))).as(step)
                    .contains("## Foreign content", "untrusted_instruction:");
        }
        assertThat(request(folder, "plain").path("system_prompt").isNull()
                || !folder.blob(request(folder, "plain").path("system_prompt")).contains("Foreign content"))
                .as("a step without web gets no such notice").isTrue();
        assertThat(folder.metadata("injected").path("untrustedInstructions")).as("found in the step's input")
                .isNotEmpty();
        assertThat(folder.metadata("search").has("untrustedInstructions")).isFalse();
        assertThat(folder.only("run.finished").path("gate").path("status").asText()).isEqualTo("YELLOW");
    }

    /** With an open AI network web still works; the hosts are recorded, and the views say nothing enforces them. */
    @Test
    void withAnOpenAiNetwork_webWorks_andItsHostsAreRecordedButNotEnforced() {
        traceworks.configure(Map.of("ai_network", "open"));

        Result run = traceworks.run("ai-web");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("fetch")).contains("WebSearch,WebFetch");
        assertThat(request(folder, "fetch").path("web_hosts")).hasSize(2);
        assertThat(command(folder, "fetch").path("network").asText()).isEqualTo("open");
        assertThat(command(folder, "fetch").has("network_hosts")).isFalse();
        assertThat(traceworks.exec("show", folder.id()).out()).contains("AI net    open (every AI container can"
                + " connect anywhere; the web_hosts of a step are recorded, but not enforced)");
        assertThat(folder.html()).contains("the web_hosts of a step are recorded, but not enforced");
    }

    /** A copy of {@code ai-web} in the sandbox with the first block's config replaced by {@code config}. */
    private Path variant(String name, String requires, String config) throws IOException {
        Path copy = Files.createDirectories(traceworks.workspace().resolve(name));
        Files.writeString(copy.resolve("workflow.json"), "{\"format\": \"traceworks-workflow\", \"format_version\": 1,"
                + " \"id\": \"9a1b2c3d-0000-4000-8000-00000000000" + (name.length() % 10) + "\", \"name\": \"IT " + name
                + "\", \"requires\": " + requires + ", \"blocks\": [{\"type\": \"simple\", \"name\": \"look\","
                + " \"config\": " + config + "}]}");
        return copy;
    }

    private Result runOf(Path pkg) {
        return traceworks.exec("run", pkg.toString(), "--workspace", traceworks.workspace().toString(), "--no-input");
    }

    /** {@code check} reports, and the run admission refuses: one copy of the rules. */
    @Test
    void checkReports_andTheRunRefuses_whatWebMustNotBe() throws IOException {
        String[][] cases = {
                {"missing", "[]", "{\"command\": \"x\", \"web\": \"search\"}", "missing-requirement",
                        "web: search needs \"ai.web\" in requires"},
                {"pi", "[\"ai.web\"]", "{\"command\": \"x\", \"web\": \"search\", \"provider\": \"PI\"}",
                        "unsupported-web", "Pi has no web tool"},
                {"nohosts", "[\"ai.web\"]", "{\"command\": \"x\", \"web\": \"fetch\"}", "invalid-config-value",
                        "web: fetch needs web_hosts"},
                {"hostsonly", "[\"ai.web\"]", "{\"command\": \"x\", \"web\": \"search\", \"web_hosts\": [\"a.example\"]}",
                        "invalid-config-value", "web_hosts is only read with web: fetch"},
                {"mixed", "[\"ai.web\"]", "{\"command\": \"x\", \"web\": \"fetch\", \"web_hosts\": [\"*\","
                        + " \"a.example\"]}", "invalid-config-value", "web_hosts mixes \"*\" with names"},
                {"placeholder", "[\"ai.web\"]", "{\"command\": \"x\", \"web\": \"fetch\", \"web_hosts\":"
                        + " [\"{{ host }}\"]}", "invalid-config-value", "which is no host"},
                {"trusted", "[\"ai.web\"]", "{\"command\": \"x\", \"web\": \"search\", \"untrusted_input\": false}",
                        "invalid-config-value", "untrusted_input: false next to web: search"},
        };
        for (String[] c : cases) {
            Path pkg = variant("web-" + c[0], c[1], c[2]);

            Result check = traceworks.exec("check", pkg.toString());
            Result run = runOf(pkg);

            assertThat(check.code()).as(c[0] + "\n" + check.describe()).isEqualTo(6);
            assertThat(check.out()).as(c[0]).containsPattern("(?m)^ERROR +" + c[3] + " ").contains(c[4]);
            assertThat(run.code()).as(c[0] + "\n" + run.describe()).isNotZero();
            assertThat(run.both()).as(c[0]).contains(c[4]);
        }
        assertThat(traceworks.folders()).as("no step of a refused workflow ran")
                .allSatisfy(folder -> assertThat(folder.ownEvents("step.started")).isEmpty());

        Path unused = variant("web-unused", "[\"ai.web\"]", "{\"command\": \"x\"}");
        Result check = traceworks.exec("check", unused.toString());
        assertThat(check.out()).containsPattern("(?m)^WARNING +unused-requirement ").contains("\"ai.web\"");
    }

    /**
     * A star is no door to the machine: a request for the machine's own address through {@code web_hosts: ["*"]} is
     * denied, and so is a name that resolves to the loopback, while the same address listed by name is allowed.
     */
    @Test
    void throughAStar_theMachinesOwnAddressIsDenied_listedByNameItIsAllowed() throws IOException {
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                while (!listener.isClosed()) {
                    try (Socket connection = listener.accept()) {
                        accepted.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            String target = ownAddress() + ":" + listener.getLocalPort();
            String connect = "\"command\": \"[fake:connect=" + target + ",localhost:443] try\", \"web\": \"fetch\"";
            Path star = variant("web-star", "[\"ai.web\"]", "{" + connect + ", \"web_hosts\": [\"*\"]}");
            Path named = variant("web-named", "[\"ai.web\"]", "{" + connect + ", \"web_hosts\": [\"" + target + "\"]}");

            Result throughStar = runOf(star);
            int reachedThroughStar = accepted.get();
            Result byName = runOf(named);

            assertThat(throughStar.code()).as(throughStar.describe()).isZero();
            RunFolder starFolder = traceworks.folder(throughStar);
            assertThat(starFolder.output("look")).contains("via proxy " + target + ": HTTP/1.1 403 Forbidden",
                    "via proxy localhost:443: HTTP/1.1 403 Forbidden").doesNotContain("200 Connection established");
            assertThat(reachedThroughStar).as("nothing reached the machine through the star").isZero();
            assertThat(command(starFolder, "look").path("network_hosts")).extracting(JsonNode::asText)
                    .containsExactly("*", "api.anthropic.com:443");
            assertThat(starFolder.ofStep("step.command.finished", 0).getFirst().path("egress"))
                    .extracting(JsonNode::toString).containsExactly(
                            "{\"target\":\"" + target + "\",\"verdict\":\"DENY\",\"count\":1}",
                            "{\"target\":\"localhost:443\",\"verdict\":\"DENY\",\"count\":1}");

            assertThat(byName.code()).as(byName.describe()).isZero();
            RunFolder namedFolder = traceworks.folder(byName);
            assertThat(namedFolder.output("look"))
                    .contains("via proxy " + target + ": HTTP/1.1 200 Connection established");
            assertThat(accepted.get()).as("the one connection the proxy made for the listed target").isEqualTo(1);
            assertThat(namedFolder.ofStep("step.command.finished", 0).getFirst().path("egress").get(0).toString())
                    .isEqualTo("{\"target\":\"" + target + "\",\"verdict\":\"ALLOW\",\"count\":1}");
        }
    }

    /** An IPv4 address of this machine that is not the loopback: a container reaches the machine by it. */
    private static String ownAddress() throws IOException {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        throw new AssertionError("This machine has no IPv4 address besides the loopback");
    }
}
