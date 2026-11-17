package traceworks.core.blocks;

import traceworks.core.container.RunPaths;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.ShellValues;
import traceworks.core.engine.GateSources;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tool mode of the shell block: a container image runs with its own entrypoint and the block's
 * {@code container_args}, no shell in between. It starts in the project; the run's command runner mounts the
 * workspace (read-only unless {@code workspace_writable: true}), the package and the scratch directory, and the tool
 * gets the values of the block's {@code secrets} as environment variables. With
 * {@code output_format: sarif} a fresh output directory is mounted, the SARIF files there become a fact sheet
 * ({@link SarifFactSheet}) and the directory is removed again. Arguments never pass a shell, so untrusted placeholders
 * are refused instead of quoted: argument injection ({@code --config=...}) cannot be defused in general.
 *
 * <p>File system access: this step creates the output directory and the tool cache directory on the host itself and
 * reads the SARIF files the tool wrote there (see the package documentation).
 */
class ContainerToolStep {

    private final Path cacheRoot;

    /** @param cacheRoot where the persistent cache directory of each image lives; {@code null} refuses {@code cache_dir} */
    ContainerToolStep(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    StepResult execute(BlockDefinition block, ContainerToolSpec spec, ExecutionContext context) {
        if (spec.image() == null) {
            throw new WorkflowExecutionException("Shell block with 'container_args' requires a 'container_image'");
        }
        if (spec.args() == null) {
            throw new WorkflowExecutionException("'container_args' must be a list of strings");
        }
        MountSpecs.refuseDockerSocket(spec.mountDockerSocket());
        ShellValues.resolve(block.getConfig(), context);   // a tool step takes none: refused before anything runs
        Map<String, String> environment = ShellTarget.secretEnvironment(block.getConfig(), context.getSecrets());
        List<String> args = resolveArgs(spec.args(), context);
        List<Mount> extraMounts = MountSpecs.parse(ShellBlockExecutor.resolveMounts(spec.mounts(), context));
        Mount cache = cacheMount(spec, context.getCommandRunner().imageReference(spec.image()));
        Path outputDir = spec.sarif() ? createOutputDir() : null;
        try {
            List<Mount> mounts = new ArrayList<>();
            if (outputDir != null) mounts.add(new Mount(outputDir.toString(), spec.outputDir(), false, false, true));
            if (cache != null) mounts.add(cache);
            mounts.addAll(extraMounts);
            CommandRequest request = CommandRequest.entrypoint(spec.image(), args, context.getProjectPath(),
                            spec.timeoutSeconds(), environment, mounts, spec.network())
                    .withWritableWorkspace(spec.writableWorkspace());
            StepResult step = new StepResult();
            step.setStepIndex(context.nextStepIndex());
            step.setStartedAt(context.getClock().now());
            step.setInput(spec.image() + (args.isEmpty() ? "" : " " + String.join(" ", args)));
            step.setStatus(StepStatus.RUNNING);

            CommandResult ran = context.getCommandRunner().run(request);
            ShellResult result = ShellResult.of(ran, spec.timeoutSeconds(), context.secretValues());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("exitCode", result.exitCode());
            metadata.put("executionMode", "container-tool:" + spec.image());
            metadata.put("outputFormat", spec.sarif() ? "sarif" : "raw");
            if (spec.sarif()) {
                SarifFactSheet.Result sheet = SarifFactSheet.read(outputDir, spec.outputDir(), spec.factPrefix(),
                        result.exitCode(), result.output(), workspaceSeenByTheTool(context),
                        context.secretReplacer(), ran.outputLimit());
                metadata.put("factPrefix", spec.factPrefix());
                metadata.put("measured", sheet.measured());
                metadata.put("findings", sheet.findings());
                finish(step, sheet.text(), sheet.measured(), result, metadata, context);
            } else {
                finish(step, result.output(), result.exitCode() == 0, result, metadata, context);
            }
            if (result.truncated()) {
                GateSources.noGateSource(step);   // its markers are what the tool printed before the cut
            }
            return step;
        } finally {
            if (outputDir != null) delete(outputDir);
        }
    }

    /**
     * Where the tool's container sees the project: {@code /workspace} in a container run, the workspace's host path in
     * a host run, which mounts it there.
     */
    private static String workspaceSeenByTheTool(ExecutionContext context) {
        RunPaths paths = context.getRunPaths();
        String workspace = paths == null ? null : paths.workspaceInContainer();
        return workspace != null ? workspace : SarifLocation.WORKSPACE;
    }

    private static List<String> resolveArgs(List<String> raw, ExecutionContext context) {
        List<String> resolved = new ArrayList<>(raw.size());
        for (String arg : raw) {
            if (context.namesEscapedValue(arg)) {
                throw new WorkflowExecutionException("container_args must not contain untrusted values (untrusted "
                        + "input, tainted block output, platform data, text a model wrote): " + arg);
            }
            traceworks.core.engine.RegisterFolderUse.inCommand(arg, context);
            resolved.add(context.resolveTemplate(arg));
        }
        return resolved;
    }

    /**
     * @param reference what the command runner runs {@code spec.image()} as (a package image is the tag built for its
     *                  package); {@code null} when it has none, so the run is refused and no folder is created
     */
    private Mount cacheMount(ContainerToolSpec spec, String reference) {
        if (spec.cacheDir() == null) return null;
        if (cacheRoot == null) {
            throw new WorkflowExecutionException("'cache_dir' needs a tool cache directory, and none is configured");
        }
        if (reference == null) return null;   // the runner refuses an image it cannot resolve; no folder for it
        Path dir = cacheRoot.resolve(cacheSlug(reference));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new WorkflowExecutionException("Cannot create tool cache " + dir + ": " + e.getMessage(), e);
        }
        return new Mount(dir.toString(), spec.cacheDir(), false, true);
    }

    /** A name the slug cannot confuse with another: only letters, digits, "." and "-" between its slashes. */
    private static final java.util.regex.Pattern PLAIN_NAME =
            java.util.regex.Pattern.compile("[a-z0-9.-]+(?:/[a-z0-9.-]+)*");

    /**
     * Image reference without tag or digest, as one safe path segment: {@code aquasec/trivy:1} becomes
     * {@code aquasec_trivy}.
     *
     * <p>A folder must belong to one image name. The slug writes {@code _} for every run of characters outside
     * {@code a-z 0-9 . _ -}, so for {@code /} and for the {@code :} of a port, and a name may hold a {@code _}
     * itself: {@code a_b/c} and {@code a/b_c}, {@code host:5000/x} and {@code host_5000/x} would join to the same
     * text, for registry images and for a package's own ({@code traceworks-pkg/<package id>/<name>}) alike, and a
     * package could choose a name that lands in the folder of another one's tool. So a name that holds anything but
     * letters, digits, {@code .} and {@code -} between single slashes gets {@code __} and the first 12 hex digits
     * of the SHA-256 of the name at its end. A name without such a character has only the {@code _} of its slashes
     * and never two in a row, so it is unambiguous as it is, and no such name can equal one with the suffix.
     */
    static String cacheSlug(String image) {
        String name = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int slash = name.lastIndexOf('/');
        int colon = name.lastIndexOf(':');
        if (colon > slash) name = name.substring(0, colon);
        name = name.toLowerCase(Locale.ROOT);
        String slug = name.replaceAll("[^a-z0-9._-]+", "_");
        if (!PLAIN_NAME.matcher(name).matches() && !name.isEmpty()) {
            slug += "__" + sha256Hex(name).substring(0, 12);
        }
        return slug.isEmpty() || slug.startsWith(".") ? "_" + slug : slug;
    }

    private static String sha256Hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path createOutputDir() {
        try {
            return Files.createTempDirectory("traceworks-tool-");
        } catch (IOException e) {
            throw new WorkflowExecutionException("Cannot create the tool output directory: " + e.getMessage(), e);
        }
    }

    private static void finish(StepResult step, String output, boolean ok, ShellResult result,
                               Map<String, Object> metadata, ExecutionContext context) {
        step.setOutput(output);
        step.setStatus(ok ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setCompletedAt(context.getClock().now());
        step.setDurationMs(result.durationMs());
        step.setMetadata(metadata);
    }

    /**
     * Deletes the directory without following symbolic links: a link the tool left behind is removed, not its target.
     * A leftover temporary directory is harmless; the step result must not depend on it.
     */
    private static void delete(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException e) throws IOException {
                    Files.deleteIfExists(visited);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best effort, see above
        }
    }
}
