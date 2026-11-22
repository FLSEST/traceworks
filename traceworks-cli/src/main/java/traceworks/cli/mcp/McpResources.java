package traceworks.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.cli.TerminalFormat;
import traceworks.cli.mcp.McpServer.Caller;
import traceworks.cli.mcp.McpServer.RpcException;
import traceworks.core.model.ProjectKey;
import traceworks.core.port.AmbiguousRunException;
import traceworks.core.port.NoSuchRunException;
import traceworks.core.port.RunReferenceTooShortException;
import traceworks.core.record.RunView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The files of a run as resources, as they are on disk: the record ({@code events.jsonl}) and the page
 * ({@code run.html}). A resource is named by the id of a run of this home and one of the two file names; nothing
 * else of the file system can be named.
 */
final class McpResources {

    /** How many of the newest runs {@code resources/list} names; any run can be read by the template. */
    private static final int LISTED_RUNS = 20;
    /** The largest file handed out whole; the tool {@code events} pages through a larger record. */
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final Pattern URI = Pattern.compile(
            "traceworks://runs/([0-9a-fA-F][0-9a-fA-F-]{3,35})/(events\\.jsonl|run\\.html)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpTools tools;

    McpResources(McpTools tools) {
        this.tools = tools;
    }

    ObjectNode list() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode list = result.putArray("resources");
        for (RunView run : tools.visibleRuns(LISTED_RUNS)) {
            // The id is Traceworks' own; the name of the workflow is text from outside and stays out of a title
            String shortId = TerminalFormat.shortId(run.runId());
            // The project is the operator's word and a key by its form; a record that holds another text there
            // names none here
            String about = run.status() + (ProjectKey.matches(run.project()) ? ", of the project " + run.project()
                    : "");
            list.addObject().put("uri", "traceworks://runs/" + run.runId() + "/events.jsonl")
                    .put("name", "record-" + shortId).put("title", "Record of run " + shortId)
                    .put("description", "The hash-chained event log of the run (" + about + "), one JSON"
                            + " object per line.")
                    .put("mimeType", "application/x-ndjson");
            list.addObject().put("uri", "traceworks://runs/" + run.runId() + "/run.html")
                    .put("name", "page-" + shortId).put("title", "Page of run " + shortId)
                    .put("description", "The self-contained HTML view of the run (" + about + ").")
                    .put("mimeType", "text/html");
        }
        return result;
    }

    ObjectNode templates() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode list = result.putArray("resourceTemplates");
        list.addObject().put("uriTemplate", "traceworks://runs/{run}/events.jsonl").put("name", "run-record")
                .put("title", "Record of a run")
                .put("description", "The hash-chained event log of a run, one JSON object per line. {run} is a run"
                        + " id or its first characters.")
                .put("mimeType", "application/x-ndjson");
        list.addObject().put("uriTemplate", "traceworks://runs/{run}/run.html").put("name", "run-page")
                .put("title", "Page of a run")
                .put("description", "The self-contained HTML view of a run. {run} is a run id or its first"
                        + " characters.")
                .put("mimeType", "text/html");
        return result;
    }

    ObjectNode read(JsonNode params, Caller caller) throws RpcException {
        JsonNode uri = params.path("uri");
        Matcher named = uri.isTextual() ? URI.matcher(uri.asText()) : null;
        if (named == null || !named.matches()) {
            throw notFound(caller, "No such resource: a resource of this server is"
                    + " traceworks://runs/<run-id>/events.jsonl or traceworks://runs/<run-id>/run.html");
        }
        String file = named.group(2);
        Path folder;
        String runId;
        try {
            // Among the runs a client sees: the run of another project is no resource, as a run that is not there
            runId = tools.visibleRun(named.group(1));
            folder = Path.of(tools.services().location(runId));
        } catch (NoSuchRunException | AmbiguousRunException | RunReferenceTooShortException e) {
            throw notFound(caller, "No such resource: no run, or more than one, has this id");
        }
        Path path = folder.resolve(file);
        try {
            // A link in the file's place leads out of the run (a host run can write its own folder): no resource
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw notFound(caller, "No such resource: the run has no " + file + " (yet)");
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes((int) MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw new RpcException(McpServer.INVALID_PARAMS, "The resource is larger than " + MAX_BYTES
                        + " bytes; page through the record with the tool events");
            }
            ObjectNode result = JSON.createObjectNode();
            result.putArray("contents").addObject()
                    .put("uri", "traceworks://runs/" + runId + "/" + file)
                    .put("mimeType", file.endsWith(".html") ? "text/html" : "application/x-ndjson")
                    .put("text", new String(bytes, StandardCharsets.UTF_8));
            return result;
        } catch (IOException e) {
            throw new RpcException(McpServer.INTERNAL_ERROR, "The resource cannot be read: "
                    + e.getClass().getSimpleName());
        }
    }

    /** A resource that is not there: invalid params since 2026-07-28, a code of its own before. */
    private static RpcException notFound(Caller caller, String message) {
        return new RpcException(caller.modern() ? McpServer.INVALID_PARAMS : McpServer.LEGACY_RESOURCE_NOT_FOUND,
                message);
    }
}
