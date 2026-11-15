package traceworks.core.port;

import traceworks.core.model.WorkflowDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A workflow package as loaded from disk.
 *
 * @param directory    the package directory, absolute and normalised
 * @param definition   the parsed {@code workflow.json}
 * @param workflowJson the bytes of {@code workflow.json} as read
 * @param digest       the package digest, lowercase hex; the seal file is not part of it
 * @param gitCommit    the HEAD commit of the git repository holding the package, or {@code null} outside one
 * @param gitDirty     whether the package directory has uncommitted changes, or {@code null} outside a repository
 * @param images       the package's images, by name: each directory under {@code images/} with a Dockerfile and a
 *                     valid name ({@code [a-z0-9][a-z0-9._-]*})
 * @param catalogJson  the bytes of the package's {@code catalog.json} (docs/registers.md); {@code null} without one
 * @param sealJson     the bytes of the package's seal file {@code traceworks.seal}, unjudged; {@code null} when the
 *                     package carries none
 * @param invalidImageDirectories the directories under {@code images/} with a Dockerfile whose name is not a valid
 *                     package image name, sorted: no {@code ./images/} reference can name them, so they are no images
 */
public record LoadedPackage(Path directory, WorkflowDefinition definition, byte[] workflowJson, String digest,
                            String gitCommit, Boolean gitDirty, Map<String, PackageImage> images,
                            List<String> invalidImageDirectories, byte[] catalogJson, byte[] sealJson) {

    /** A package that carries no seal. */
    public LoadedPackage(Path directory, WorkflowDefinition definition, byte[] workflowJson, String digest,
                         String gitCommit, Boolean gitDirty, Map<String, PackageImage> images,
                         List<String> invalidImageDirectories, byte[] catalogJson) {
        this(directory, definition, workflowJson, digest, gitCommit, gitDirty, images, invalidImageDirectories,
                catalogJson, null);
    }

    /** A package without a {@code catalog.json}. */
    public LoadedPackage(Path directory, WorkflowDefinition definition, byte[] workflowJson, String digest,
                         String gitCommit, Boolean gitDirty, Map<String, PackageImage> images,
                         List<String> invalidImageDirectories) {
        this(directory, definition, workflowJson, digest, gitCommit, gitDirty, images, invalidImageDirectories, null);
    }

    public LoadedPackage {
        images = images == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(images));
        invalidImageDirectories = invalidImageDirectories == null ? List.of() : List.copyOf(invalidImageDirectories);
    }

    /** A package without image directories whose name is not valid. */
    public LoadedPackage(Path directory, WorkflowDefinition definition, byte[] workflowJson, String digest,
                         String gitCommit, Boolean gitDirty, Map<String, PackageImage> images) {
        this(directory, definition, workflowJson, digest, gitCommit, gitDirty, images, List.of());
    }

    /** A package without images. */
    public LoadedPackage(Path directory, WorkflowDefinition definition, byte[] workflowJson, String digest,
                         String gitCommit, Boolean gitDirty) {
        this(directory, definition, workflowJson, digest, gitCommit, gitDirty, Map.of());
    }
}
