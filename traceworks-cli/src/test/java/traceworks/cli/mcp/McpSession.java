package traceworks.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.cli.RuntimeSource;
import traceworks.cli.main.Main;
import traceworks.core.testing.OwnThread;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * An MCP client for the tests: {@code traceworks mcp} runs through {@link Main#run} on a thread of its own, with
 * pipes as its standard input and output, as a client would start the process. No container runtime
 * ({@link RuntimeSource#NONE}).
 */
final class McpSession implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String MODERN = "2026-07-28";

    private final PipedOutputStream toServer = new PipedOutputStream();
    private final BufferedReader fromServer;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    /** A {@code traceworks.json} whose home opens every change to a server; the switches then decide. */
    static final String ALLOW_ALL = "{\"mcp\": {\"allow\": [\"run\", \"answer\", \"decide\"]}}";

    /** The options, with {@code work} as the package folder where runs are allowed and none is named. */
    static String[] withPackages(Path work, String... options) {
        List<String> all = new ArrayList<>(List.of(options));
        if (all.contains("--allow-run") && !all.contains("--packages")) {
            all.addAll(List.of("--packages", work.toString()));
        }
        return all.toArray(String[]::new);
    }

    private final CompletableFuture<Integer> exit;
    private int nextId = 1;
    /** What every request carries in {@code _meta}; {@code null} for a client that shook hands. */
    private ObjectNode meta;

    McpSession(Path home, Path work, Map<String, String> env, String... options) {
        this(home, work, env, RuntimeSource.NONE, options);
    }

    /** A server whose runs get their container runtime from {@code runtimes}. */
    McpSession(Path home, Path work, Map<String, String> env, RuntimeSource runtimes, String... options) {
        try {
            PipedInputStream stdin = new PipedInputStream(toServer, 1 << 16);
            PipedInputStream stdoutOfServer = new PipedInputStream(1 << 16);
            PrintStream stdout = new PrintStream(new PipedOutputStream(stdoutOfServer), true, StandardCharsets.UTF_8);
            fromServer = new BufferedReader(new InputStreamReader(stdoutOfServer, StandardCharsets.UTF_8));
            List<String> args = new ArrayList<>(List.of("mcp", "--home", home.toString()));
            args.addAll(List.of(options));
            exit = OwnThread.start("mcp server", () -> {
                try {
                    return Main.run(args.toArray(String[]::new), stdout,
                            new PrintStream(err, true, StandardCharsets.UTF_8), Map.copyOf(env), work, null,
                            runtimes, null, stdin);
                } finally {
                    stdout.close();
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** From now on every request carries the metadata of the stateless protocol, as the client {@code name}. */
    McpSession modern(String name) {
        meta = JSON.createObjectNode();
        meta.put("io.modelcontextprotocol/protocolVersion", MODERN);
        meta.putObject("io.modelcontextprotocol/clientCapabilities");
        if (name != null) {
            meta.putObject("io.modelcontextprotocol/clientInfo").put("name", name).put("version", "1.0");
        }
        return this;
    }

    /** The handshake of the earlier revisions, as the client {@code name}; returns the result of initialize. */
    JsonNode initialize(String version, String name) {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", version);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", name).put("version", "1.0");
        JsonNode result = request("initialize", params).path("result");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        return result;
    }

    /** Writes one line as it is. */
    void send(String line) {
        try {
            toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            toServer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The next line the server wrote, as JSON; fails when it is none or the server ended. */
    JsonNode read() {
        try {
            String line = fromServer.readLine();
            if (line == null) {
                throw new AssertionError("the server ended; stderr: " + err.toString(StandardCharsets.UTF_8));
            }
            return JSON.readTree(line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sends a request and returns its whole response. */
    JsonNode request(String method, ObjectNode params) {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        int id = nextId++;
        request.put("id", id);
        request.put("method", method);
        ObjectNode sent = params == null ? JSON.createObjectNode() : params.deepCopy();
        if (meta != null && !sent.has("_meta")) {
            sent.set("_meta", meta.deepCopy());
        }
        request.set("params", sent);
        send(request.toString());
        JsonNode response = read();
        if (response.path("id").asInt(-1) != id) {
            throw new AssertionError("expected the response to " + id + ", got " + response);
        }
        return response;
    }

    /** Calls a tool and returns the result of the call; fails on a protocol error. */
    JsonNode call(String tool, ObjectNode arguments) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments == null ? JSON.createObjectNode() : arguments);
        JsonNode response = request("tools/call", params);
        if (!response.has("result")) {
            throw new AssertionError("no result: " + response);
        }
        return response.path("result");
    }

    static ObjectNode args(String... keysAndValues) {
        ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            node.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return node;
    }

    static String text(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }

    String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /** Ends the standard input of the server and returns its exit code. */
    int end() {
        return end(60);
    }

    /** As {@link #end()}, for a server that must be gone within {@code seconds}. */
    int end(int seconds) {
        try {
            toServer.close();
            return exit.get(seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the server did not end: " + OwnThread.threads() + "; stderr: " + stderr(), e);
        }
    }

    @Override
    public void close() {
        if (!exit.isDone()) {
            end();
        }
    }

    OutputStream rawInput() {
        return toServer;
    }
}
