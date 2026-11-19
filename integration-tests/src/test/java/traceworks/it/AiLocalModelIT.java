package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code PI} step on a model server of the operator's ({@code local_models}; docs/cli.md, "Local models"), with
 * the <b>real Pi CLI</b> of the built-in image {@code pi} and the real egress proxy. No model is called and no
 * credential of a provider exists in the sandbox: the server is a stand-in this test starts on the machine's own
 * address ({@link StandIn}), which answers the chat completions API with a fixed reply and keeps what it was asked.
 *
 * <p>Unlike the other AI tests of the suite, which run fake CLIs, this one needs the built-in image
 * {@code traceworks/pi:<h>}: build it once with {@code traceworks images build}. The suite neither builds nor
 * changes it; a run without it is refused, and the test fails with that refusal. What Pi does with a server of the
 * operator's is known from its documentation only, so every assertion describes itself with what Pi printed, what
 * the stand-in was asked and what the proxy recorded ({@link #diagnosis}).
 */
class AiLocalModelIT extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPLY = "The stand-in model says hello.";
    private static final String AFTER_TOOL = "The probe is done.";
    /**
     * The secret of the stand-in server in the test that gives it one: a made-up word that is no key of anything,
     * plain enough that no scanner takes it for one.
     */
    private static final String SERVER_KEY = "dummy-dummy-dummy-dummy-dummy";
    private static final List<String> PROVIDER_HOSTS = List.of("api.anthropic.com:443", "api.openai.com:443",
            "generativelanguage.googleapis.com:443", "openrouter.ai:443");

    /** An address reserved for documentation (TEST-NET-1): nothing answers there, and no name has to be looked up. */
    private static final String NO_SUCH_ADDRESS = "192.0.2.1";

    /**
     * What the stand-in model has Pi's {@code bash} tool run, in the step's container. Through the step's proxy it
     * asks for a tunnel to a provider's host, and sends a plain HTTP request to another host and to another port of
     * the stand-in server: each must be answered with 403. Without the proxy it tries a provider's host by name
     * (which fails at the lookup already), an IP address (which fails only when there is no route: the documentation
     * address, so nothing leaves the machine when the network is closed as it should be) and the stand-in server
     * itself, which the container must reach through the proxy only. It prints one line for each.
     *
     * @param address the address of the stand-in server
     * @param port    its port
     */
    private static String probe(String address, int port) {
        return "node -e 'const net = require(\"net\");"
                + " const p = process.env.HTTPS_PROXY || process.env.https_proxy;"
                + " const ask = (label, request) => new Promise(done => {"
                + " if (!p) { console.log(label + \": no proxy variable\"); return done(); }"
                + " const u = new URL(p);"
                + " const s = net.connect(Number(u.port), u.hostname, () => s.write(request));"
                + " s.setTimeout(8000, () => { console.log(label + \": timeout\"); s.destroy(); done(); });"
                + " s.on(\"data\", d => { console.log(label + \": \" + String(d).split(\"\\r\\n\")[0]); s.destroy();"
                + " done(); });"
                + " s.on(\"error\", e => { console.log(label + \": error \" + e.code); done(); }); });"
                + " const direct = (label, port, host) => new Promise(done => {"
                + " const d = net.connect(port, host, () => { console.log(label + \": connected\"); d.destroy();"
                + " done(); });"
                + " d.setTimeout(4000, () => { console.log(label + \": timeout\"); d.destroy(); done(); });"
                + " d.on(\"error\", e => { console.log(label + \": error \" + e.code); done(); }); });"
                + " (async () => {"
                + " await ask(\"tunnel\", \"CONNECT api.anthropic.com:443 HTTP/1.1\\r\\nHost: api.anthropic.com:443"
                + "\\r\\n\\r\\n\");"
                + " await ask(\"plain other host\", \"GET http://example.org/ HTTP/1.1\\r\\nHost: example.org"
                + "\\r\\n\\r\\n\");"
                + " await ask(\"plain other port\", \"GET http://" + address + ":9/ HTTP/1.1\\r\\nHost: " + address
                + ":9\\r\\n\\r\\n\");"
                + " await direct(\"direct name\", 443, \"api.anthropic.com\");"
                + " await direct(\"direct address\", 443, \"" + NO_SUCH_ADDRESS + "\");"
                + " await direct(\"direct server\", " + port + ", \"" + address + "\");"
                + " })();'";
    }

    private StandIn server;
    private String address;
    private String target;
    private String url;

    @BeforeEach
    void startTheStandInServer_andTheEgressImage() throws IOException {
        server = new StandIn();
        address = ItEnvironment.get().machine();
        target = address + ":" + server.port();
        url = "http://" + target + "/v1";
        traceworks.environment().egressImage();
    }

    @AfterEach
    void stopTheStandInServer() {
        if (server != null) {
            server.stop();
        }
    }


    /** {@code local_models} of the sandbox: the backend {@code llm} is the stand-in, with or without a secret. */
    private void declare(String secret) {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("url", url);
        backend.put("api", "openai-completions");
        backend.put("models", List.of("stand-in-1", "stand-in:7b"));
        if (secret != null) {
            backend.put("secret", secret);
        }
        traceworks.configure(Map.of("local_models", Map.of("llm", backend)));
    }

    /**
     * Everything a failed assertion needs to be understood: what the command printed, what the stand-in was asked,
     * and for each command of each run its exit code, what it asked the proxy for, and every text it left (Pi's
     * stdout and stderr), then the error of each AI call.
     */
    private String diagnosis(Result run) {
        StringBuilder out = new StringBuilder(run.describe());
        out.append("\n--- the stand-in server at ").append(url).append(" was asked ").append(server.requests.size())
                .append(" time(s)\n");
        server.requests.forEach(request -> out.append(request).append('\n'));
        for (RunFolder folder : traceworks.folders()) {
            for (JsonNode event : folder.events()) {
                String type = event.path("type").asText();
                if (!type.equals("step.command") && !type.equals("step.command.finished")
                        && !type.equals("ai.response") && !type.equals("ai.tool_call")
                        && !type.equals("run.finished")) {
                    continue;
                }
                out.append("--- ").append(type).append(' ').append(cut(event.toString(), 1500)).append('\n');
                event.properties().forEach(field -> blobs(folder, field.getKey(), field.getValue(), out));
                event.path("fields").properties().forEach(field ->
                        blobs(folder, field.getKey(), field.getValue(), out));
            }
        }
        return out.toString();
    }

    private static void blobs(RunFolder folder, String name, JsonNode value, StringBuilder out) {
        if (value.isObject() && value.hasNonNull("sha256") && !name.equals("command")) {
            try {
                out.append("    ").append(name).append(": ").append(cut(folder.blob(value), 6000)).append('\n');
            } catch (RuntimeException | AssertionError e) {
                out.append("    ").append(name).append(": (blob not readable)\n");
            }
        }
    }

    private static String cut(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + " ... (" + text.length() + " characters)";
    }

    private List<JsonNode> egressOf(RunFolder folder, String step) {
        List<JsonNode> asked = new ArrayList<>();
        folder.ofStep("step.command.finished", folder.stepIndex(step)).forEach(finished ->
                finished.path("egress").forEach(asked::add));
        return asked;
    }

    /**
     * The whole path: the run completes with the stand-in's reply as the step's output, the stand-in got the prompt,
     * the record names the local backend and its URL, the proxy's record shows the connection to the server and
     * none to a provider, no credential of a provider was asked for or passed, and {@code show} and
     * {@code run.html} say where the call went.
     */
    @Test
    void aLocalStep_runsTheRealPiAgainstTheStandIn_reachesNothingElse_andTheRecordSaysSo() {
        declare(null);

        Result run = traceworks.run("ai-local-model");

        String why = diagnosis(run);
        assertThat(run.code()).as("no credential of any provider is stored, and the run needs none\n" + why).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("ask")).as(why).isEqualTo("COMPLETED");
        assertThat(folder.output("ask").strip()).as("the stand-in's reply is the step's output\n" + why)
                .isEqualTo(REPLY);

        assertThat(server.chatBodies()).as("the stand-in got the prompt\n" + why)
                .anySatisfy(body -> assertThat(body).contains("Reply to world with one sentence."));
        assertThat(server.chatBodies()).as("and was asked for the model of the call\n" + why)
                .allSatisfy(body -> assertThat(body).contains("\"model\":\"stand-in-1\""));

        JsonNode started = folder.only("run.started");
        assertThat(started.path("local_models").path("llm").path("url").asText()).as(why).isEqualTo(url);
        assertThat(started.path("model_choices").path("local").path("model").asText()).isEqualTo("llm/stand-in-1");
        JsonNode asked = folder.ofStep("ai.request", folder.stepIndex("ask")).getFirst();
        assertThat(asked.path("provider").asText()).isEqualTo("PI");
        assertThat(asked.path("model").asText()).isEqualTo("llm/stand-in-1");
        assertThat(asked.path("local").toString()).as("the record names the local backend\n" + why)
                .isEqualTo("{\"backend\":\"llm\",\"url\":\"" + url + "\",\"api\":\"openai-completions\"}");

        JsonNode command = folder.ofStep("step.command", folder.stepIndex("ask")).getFirst();
        assertThat(command.path("image").asText()).as("the real Pi CLI, in the built-in image")
                .startsWith("traceworks/pi:");
        assertThat(command.path("network").asText()).isEqualTo("hosts");
        assertThat(command.path("network_hosts")).extracting(JsonNode::asText)
                .as("the server alone: no provider's host").containsExactly(target);
        assertThat(command.path("env_names")).extracting(JsonNode::asText)
                .as("the configuration travels in a variable; no credential of a provider is passed")
                .contains("TRACEWORKS_PI_MODELS").doesNotContainAnyElementsOf(Traceworks.CREDENTIAL_NAMES);

        List<JsonNode> egress = egressOf(folder, "ask");
        assertThat(egress).as("the proxy's record shows the connection to the server\n" + why).isNotEmpty()
                .anySatisfy(entry -> {
                    assertThat(entry.path("target").asText()).isEqualTo(target);
                    assertThat(entry.path("verdict").asText()).isEqualTo("ALLOW");
                });
        assertThat(egress).as("and none to a provider, and nothing denied: Pi asked for nothing else\n" + why)
                .allSatisfy(entry -> {
                    assertThat(entry.path("target").asText()).isEqualTo(target);
                    assertThat(entry.path("verdict").asText()).isEqualTo("ALLOW");
                });
        assertThat(server.requests).as("what Pi asks a server of the operator's for: the chat completion\n" + why)
                .anySatisfy(request -> assertThat(request).startsWith("POST /v1/chat/completions "));

        Result show = traceworks.exec("show", folder.id());
        assertThat(show.out()).contains("model llm/stand-in-1  local backend llm at " + url);
        assertThat(folder.html()).contains("local backend <code>llm</code> at <code>" + url + "</code>");
        assertThat(run.err()).contains("traceworks: note: local_models: a step with the model llm/<model> runs"
                + " against " + url + " and reaches nothing else");
    }

    /**
     * A server with a key: Pi sends the value of the secret, which travels in the container's environment and is
     * named, never written, in the configuration; the record holds it nowhere.
     */
    @Test
    void theSecretOfTheServer_reachesTheServerAsItsKey_andIsNowhereInTheRecord() {
        declare("STANDIN_KEY");
        traceworks.credentials(Map.of("STANDIN_KEY", SERVER_KEY));
        server.replyWith("Your key is " + SERVER_KEY);

        Result run = traceworks.run("ai-local-model");

        String why = diagnosis(run);
        assertThat(run.code()).as(why).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(server.authorizations).as("Pi sends the secret as the server's key (Authorization: Bearer)\n"
                + why).contains("Bearer " + SERVER_KEY);
        assertThat(folder.output("ask")).as("the step's output is redacted\n" + why)
                .contains("[redacted:STANDIN_KEY]").doesNotContain(SERVER_KEY);
        JsonNode command = folder.ofStep("step.command", folder.stepIndex("ask")).getFirst();
        assertThat(command.path("env_names")).extracting(JsonNode::asText)
                .contains("STANDIN_KEY", "TRACEWORKS_PI_MODELS")
                .doesNotContainAnyElementsOf(Traceworks.CREDENTIAL_NAMES);
        assertThat(folder.blobTexts()).as("no blob holds the key").noneMatch(text -> text.contains(SERVER_KEY));
        assertThat(String.join("\n", folder.events().stream().map(JsonNode::toString).toList()))
                .as("no line of the event log holds the key").doesNotContain(SERVER_KEY);
        assertThat(folder.only("run.started").path("local_models").path("llm").path("secret").asText())
                .isEqualTo("STANDIN_KEY");
    }

    /** The other model of the server, picked by the run's input: a name with a colon, as Ollama writes its tags. */
    @Test
    void aModelNameWithAColon_isAskedForAsItIsWritten() {
        declare(null);

        Result run = traceworks.run("ai-local-model", "--input", "local=tagged");

        String why = diagnosis(run);
        assertThat(run.code()).as(why).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("ask").strip()).as(why).isEqualTo(REPLY);
        assertThat(server.chatBodies()).as("the server is asked for the model as the operator listed it\n" + why)
                .isNotEmpty().allSatisfy(body -> assertThat(body).contains("\"model\":\"stand-in:7b\""));
        assertThat(folder.ofStep("ai.request", folder.stepIndex("ask")).getFirst().path("model").asText())
                .isEqualTo("llm/stand-in:7b");
    }

    /**
     * The step tries to get out and is denied. The stand-in model answers with a call of Pi's {@code bash} tool
     * ({@code tools: shell}); the command ({@link #probe}) asks the step's proxy for a provider's host, for another
     * host and for another port of the server, and tries a name, an IP address and the server itself without the
     * proxy. The proxy refuses all three, each {@code DENY} is in the record, nothing connects directly, and the only
     * connection made is the one to the server. That the server is not reachable past the proxy needs Docker 28 or
     * newer, which leaves the host without an address on the step's network (docs/security.md).
     */
    @Test
    void aLocalStepThatTriesAProvidersHost_isDenied_andTheRecordShowsTheAttempt() {
        declare(null);
        server.callBashFirst(probe(address, server.port()));

        Result run = traceworks.run("ai-local-model-tools");

        String why = diagnosis(run);
        assertThat(run.code()).as("a denied connection fails nothing\n" + why).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("probe").strip()).as(why).isEqualTo(AFTER_TOOL);
        assertThat(server.toolResults).as("Pi ran the command and handed its output back to the model: the proxy"
                + " answered 403 to the tunnel and to both plain requests\n" + why)
                .anySatisfy(result -> assertThat(result).contains("tunnel: HTTP/1.1 403",
                        "plain other host: HTTP/1.1 403", "plain other port: HTTP/1.1 403"));
        assertThat(server.toolResults).as("without the proxy nothing connects: not a name, which does not even"
                + " resolve, not an IP address, for which there is no route, and not the server itself (Docker 28"
                + " or newer: the host has no address on the step's network)\n" + why)
                .anySatisfy(result -> assertThat(result).containsPattern("direct name: (error|timeout)")
                        .containsPattern("direct address: (error|timeout)")
                        .containsPattern("direct server: (error|timeout)").doesNotContain(": connected"));

        List<JsonNode> egress = egressOf(folder, "probe");
        assertThat(egress).extracting(entry -> entry.path("target").asText() + " " + entry.path("verdict").asText())
                .as("each attempt is in the record as denied: the tunnel, the plain request to another host, and"
                        + " the plain request to another port of the server\n" + why)
                .contains("api.anthropic.com:443 DENY", "example.org:80 DENY", address + ":9 DENY");
        assertThat(egress).as("nothing but the server was reached\n" + why)
                .filteredOn(entry -> entry.path("verdict").asText().equals("ALLOW"))
                .isNotEmpty().allSatisfy(entry -> assertThat(entry.path("target").asText()).isEqualTo(target));
        assertThat(egress).extracting(entry -> entry.path("target").asText() + " " + entry.path("verdict").asText())
                .doesNotContainAnyElementsOf(PROVIDER_HOSTS.stream().map(host -> host + " ALLOW").toList());
        assertThat(folder.ofStep("ai.tool_call", folder.stepIndex("probe"))).as("the tool call is recorded\n" + why)
                .isNotEmpty();

        Result show = traceworks.exec("show", folder.id());
        assertThat(show.out()).contains("egress DENIED  api.anthropic.com:443");
    }

    /**
     * A stand-in for a model server that speaks the chat completions API of OpenAI: the JDK's own HTTP server, on
     * every address of the machine. It answers {@code POST .../chat/completions} with a fixed reply, as a stream of
     * server-sent events when the request asks for one and as one JSON object otherwise, and a list of its models to
     * {@code GET .../models}. It calls no model. It keeps every request line, the {@code Authorization} header of
     * each, the body of each chat request, and the results of the tool calls it was sent.
     */
    private static final class StandIn {

        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> authorizations = new CopyOnWriteArrayList<>();
        final List<String> toolResults = new CopyOnWriteArrayList<>();
        private final List<String> chatBodies = new CopyOnWriteArrayList<>();
        private final HttpServer http;
        private volatile String reply = REPLY;
        private volatile String bashCommand;

        StandIn() throws IOException {
            http = HttpServer.create(new InetSocketAddress(0), 0);
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            http.createContext("/", this::handle);
            http.start();
        }

        int port() {
            return http.getAddress().getPort();
        }

        void stop() {
            http.stop(0);
        }

        /** The text of every answer from now on. */
        void replyWith(String text) {
            reply = text;
        }

        /**
         * The model first calls the tool {@code bash} with {@code command}; once a request carries a tool's result,
         * it answers {@link #AFTER_TOOL}.
         */
        void callBashFirst(String command) {
            bashCommand = command;
        }

        List<String> chatBodies() {
            return List.copyOf(chatBodies);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getPath();
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " (Host: "
                        + exchange.getRequestHeaders().getFirst("Host") + ", Authorization: "
                        + (authorization == null ? "none" : authorization.replace(SERVER_KEY, "<the server's key>"))
                        + ", " + body.length() + " characters) " + cut(body.replace(SERVER_KEY,
                        "<the server's key>"), 3000));
                if (authorization != null) {
                    authorizations.add(authorization);
                }
                if (exchange.getRequestMethod().equals("POST") && path.endsWith("/chat/completions")) {
                    chatBodies.add(body);
                    chat(exchange, JSON.readTree(body));
                } else if (exchange.getRequestMethod().equals("GET") && path.endsWith("/models")) {
                    send(exchange, 200, "application/json", "{\"object\":\"list\",\"data\":[{\"id\":\"stand-in-1\","
                            + "\"object\":\"model\"},{\"id\":\"stand-in:7b\",\"object\":\"model\"}]}");
                } else {
                    send(exchange, 404, "application/json", "{\"error\":{\"message\":\"the stand-in has no "
                            + exchange.getRequestMethod() + " " + path + "\",\"type\":\"not_found\"}}");
                }
            } catch (RuntimeException e) {
                requests.add("(the stand-in failed: " + e + ")");
                throw e;
            }
        }

        private void chat(HttpExchange exchange, JsonNode request) throws IOException {
            String model = request.path("model").asText("stand-in-1");
            boolean toolAnswered = false;
            for (JsonNode message : request.path("messages")) {
                if (message.path("role").asText().equals("tool")) {
                    toolAnswered = true;
                    JsonNode content = message.path("content");
                    toolResults.add(content.isTextual() ? content.asText() : content.toString());
                }
            }
            boolean callTool = bashCommand != null && !toolAnswered;
            String text = bashCommand != null ? AFTER_TOOL : reply;
            if (request.path("stream").asBoolean(false)) {
                stream(exchange, model, callTool, text,
                        request.path("stream_options").path("include_usage").asBoolean(false));
            } else {
                ObjectNode message = JSON.createObjectNode().put("role", "assistant");
                if (callTool) {
                    message.putNull("content");
                    message.set("tool_calls", toolCalls(false));
                } else {
                    message.put("content", text);
                }
                ObjectNode answer = chunk(model, "chat.completion");
                ObjectNode choice = ((ArrayNode) answer.get("choices")).addObject().put("index", 0);
                choice.set("message", message);
                choice.put("finish_reason", callTool ? "tool_calls" : "stop");
                answer.set("usage", usage());
                send(exchange, 200, "application/json", answer.toString());
            }
        }

        private void stream(HttpExchange exchange, String model, boolean callTool, String text, boolean usage)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                ObjectNode first = JSON.createObjectNode().put("role", "assistant");
                if (callTool) {
                    first.putNull("content");
                    first.set("tool_calls", toolCalls(true));
                } else {
                    first.put("content", text);
                }
                event(out, delta(model, first, null));
                event(out, delta(model, JSON.createObjectNode(), callTool ? "tool_calls" : "stop"));
                if (usage) {
                    ObjectNode last = chunk(model, "chat.completion.chunk");
                    last.set("usage", usage());
                    event(out, last);
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        private ArrayNode toolCalls(boolean streamed) {
            ArrayNode calls = JSON.createArrayNode();
            ObjectNode call = calls.addObject();
            if (streamed) {
                call.put("index", 0);
            }
            call.put("id", "call_standin_1").put("type", "function");
            call.putObject("function").put("name", "bash")
                    .put("arguments", JSON.createObjectNode().put("command", bashCommand).toString());
            return calls;
        }

        private static ObjectNode chunk(String model, String object) {
            ObjectNode chunk = JSON.createObjectNode().put("id", "chatcmpl-standin").put("object", object)
                    .put("created", 1_763_251_200L).put("model", model);
            chunk.putArray("choices");
            return chunk;
        }

        private static ObjectNode delta(String model, ObjectNode delta, String finishReason) {
            ObjectNode chunk = chunk(model, "chat.completion.chunk");
            ObjectNode choice = ((ArrayNode) chunk.get("choices")).addObject().put("index", 0);
            choice.set("delta", delta);
            choice.put("finish_reason", finishReason);
            return chunk;
        }

        private static ObjectNode usage() {
            return JSON.createObjectNode().put("prompt_tokens", 11).put("completion_tokens", 7)
                    .put("total_tokens", 18);
        }

        private static void event(OutputStream out, ObjectNode data) throws IOException {
            out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
