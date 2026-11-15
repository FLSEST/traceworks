package traceworks.core.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.util.SecretReplacer;

import java.net.URI;

/**
 * Where a SARIF result points, as a repo-relative {@code path[:line]}. A {@code uri} may be relative
 * to a {@code uriBaseId} that the run defines in {@code originalUriBaseIds} — {@code trivy fs /workspace/sub} writes
 * {@code ROOTPATH = file:///workspace/sub/} and {@code uri = creds.txt}. Bases may chain; the path where the tool's
 * container sees the project is cut off: {@code /workspace/} in a container run, the workspace's host path in a host
 * run.
 */
final class SarifLocation {

    /** Where a container run's tool sees the project. */
    static final String WORKSPACE = "/workspace";
    private static final int MAX_BASE_DEPTH = 4;

    private SarifLocation() {}

    /** The location for a tool that sees the project at {@value #WORKSPACE}, without secrets. */
    static String of(JsonNode result, JsonNode bases) {
        return of(result, bases, WORKSPACE);
    }

    /** The location in a run without secrets. */
    static String of(JsonNode result, JsonNode bases, String workspace) {
        return of(result, bases, workspace, SecretReplacer.none());
    }

    /**
     * First physical location of {@code result}; {@code bases} is the run's {@code originalUriBaseIds}.
     *
     * <p>The path is rebuilt from the tool's URIs: joined to a base, percent-decoded, the workspace cut off. Each of
     * these can change a secret's value or leave a part of it, which the record's exact match would not find. So the
     * values are replaced in every URI as the tool wrote it, and again in the joined and decoded path, before the
     * workspace is cut off.
     *
     * @param workspace where the tool's container sees the project; a path below it is made relative to it
     * @param secrets   replaces the run's secret values
     */
    static String of(JsonNode result, JsonNode bases, String workspace, SecretReplacer secrets) {
        JsonNode physical = result.path("locations").path(0).path("physicalLocation");
        JsonNode artifact = physical.path("artifactLocation");
        String uri = secrets.replace(artifact.path("uri").asText(""));
        if (uri.isBlank()) return "(no location)";
        String path = secrets.replace(filePath(withBase(uri, artifact.path("uriBaseId").asText(""), bases, 0,
                secrets)));
        String prefix = workspace.endsWith("/") ? workspace : workspace + "/";
        if (path.startsWith(prefix)) path = path.substring(prefix.length());
        int line = physical.path("region").path("startLine").asInt(0);
        return line > 0 ? path + ":" + line : path;
    }

    /** Prefixes the base's uri (recursively); an unknown base leaves the uri as it is, already repo-relative. */
    private static String withBase(String uri, String baseId, JsonNode bases, int depth, SecretReplacer secrets) {
        if (baseId.isEmpty() || depth >= MAX_BASE_DEPTH || uri.startsWith("file:") || uri.startsWith("/")) return uri;
        JsonNode base = bases.path(baseId);
        String baseUri = secrets.replace(base.path("uri").asText(""));
        if (baseUri.isEmpty()) return uri;
        String prefix = withBase(baseUri, base.path("uriBaseId").asText(""), bases, depth + 1, secrets);
        return prefix.endsWith("/") ? prefix + uri : prefix + "/" + uri;
    }

    private static String filePath(String uri) {
        if (!uri.startsWith("file:")) return uri;
        try {
            String path = URI.create(uri).getPath();
            if (path != null) return path;
        } catch (IllegalArgumentException e) {
            // fall through: a file uri with characters URI rejects (spaces) is still a path
        }
        return uri.substring("file:".length()).replaceFirst("^/+", "/");
    }
}
