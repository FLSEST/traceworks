package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * The client of one {@code traceworks mcp} process: it writes one JSON-RPC message per line to the standard input
 * of the process and reads one per line from its standard output, as a client of the stdio transport does.
 */
final class McpClient implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final Path err;
    private final BufferedWriter toServer;
    private final BufferedReader fromServer;
    private int nextId = 1;

    McpClient(Process process, Path err) {
        this.process = process;
        this.err = err;
        this.toServer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromServer = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** The handshake of the clients of today: {@code initialize} with the client's name, then the notification. */
    McpClient initialize(String clientName) {
        ObjectNode params = JSON.createObjectNode().put("protocolVersion", "2025-06-18");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", clientName).put("version", "1");
        JsonNode response = request("initialize", params);
        if (!"2025-06-18".equals(response.path("result").path("protocolVersion").asText())) {
            throw new AssertionError("the handshake failed: " + response);
        }
        send(JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        return this;
    }

    /** Sends a request and returns its whole response; every line the server writes must be a message. */
    JsonNode request(String method, ObjectNode params) {
        int id = nextId++;
        ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        request.set("params", params == null ? JSON.createObjectNode() : params);
        send(request);
        try {
            String line = fromServer.readLine();
            if (line == null) {
                throw new AssertionError("the server ended before it answered " + method + "; stderr:\n" + stderr());
            }
            JsonNode response = JSON.readTree(line);
            if (response.path("id").asInt(-1) != id) {
                throw new AssertionError("expected the response to " + id + ", got " + line);
            }
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Calls a tool and returns the result of the call; a protocol error fails the test. */
    JsonNode call(String tool, ObjectNode arguments) {
        ObjectNode params = JSON.createObjectNode().put("name", tool);
        params.set("arguments", arguments == null ? JSON.createObjectNode() : arguments);
        JsonNode response = request("tools/call", params);
        if (!response.has("result")) {
            throw new AssertionError("the call of " + tool + " failed: " + response);
        }
        return response.path("result");
    }

    /** The text of a result of a call: the lines of the command, after the server's first line. */
    static String text(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }

    private void send(ObjectNode message) {
        try {
            toServer.write(message.toString());
            toServer.write('\n');
            toServer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String stderr() {
        try {
            return Files.readString(err, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ends the input of the server, as a client that goes away does, and returns the exit code of the process. */
    int end(long timeoutSeconds) {
        try {
            toServer.close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
                throw new AssertionError("traceworks mcp did not end within " + timeoutSeconds + " s after its"
                        + " input ended and was killed; stderr:\n" + stderr());
            }
            return process.exitValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while traceworks mcp ended", e);
        }
    }

    /** Leaves no process behind, whatever the test did. */
    @Override
    public void close() {
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
