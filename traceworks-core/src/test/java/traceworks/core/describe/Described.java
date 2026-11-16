package traceworks.core.describe;

import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.WorkflowResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Descriptions of workflows written as JSON, for the tests of the description and its renderings. */
public final class Described {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Described() {
    }

    public static WorkflowDefinition definition(String json) {
        try {
            return traceworks.core.model.WorkflowJson.read(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static LoadedPackage pkg(String json) {
        return new LoadedPackage(Path.of("/packages/sample"), definition(json), json.getBytes(StandardCharsets.UTF_8),
                "d1g35t", null, null);
    }

    /** The description of a package without child workflows. */
    public static WorkflowDescription of(String json) {
        return WorkflowDescriber.describe(pkg(json), null);
    }

    /** The description of a package whose references resolve to the given workflows, by reference. */
    public static WorkflowDescription of(String json, Map<String, String> children) {
        return WorkflowDescriber.describe(pkg(json), resolver(children));
    }

    private static WorkflowResolver resolver(Map<String, String> children) {
        Map<String, WorkflowDefinition> parsed = new java.util.HashMap<>();
        children.forEach((ref, json) -> parsed.put(ref, definition(json)));
        return ref -> {
            WorkflowDefinition found = parsed.get(ref);
            if (found == null) {
                throw new IllegalArgumentException("Workflow '" + ref + "' not found; looked in the package path (empty)");
            }
            return found;
        };
    }
}
