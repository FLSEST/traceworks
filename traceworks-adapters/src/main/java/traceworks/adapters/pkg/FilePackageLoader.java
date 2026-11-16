package traceworks.adapters.pkg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.model.WorkflowJson;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageLoader;
import traceworks.core.seal.Seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads a package from a directory: {@code workflow.json} (an unknown top-level key is an error, and so are both
 * spellings of one key: {@link WorkflowJson}), the
 * {@linkplain PackageDigest digest} and the {@linkplain GitInfo git state}, the images under {@code images/} and the
 * bytes of the seal file when the package carries one. A
 * package in the runs' checkouts is refused before anything of it is read ({@link CheckoutsDirectory}).
 */
public final class FilePackageLoader implements PackageLoader {

    /** The most bytes a workflow.json or a catalog.json of a package may have: far more than a workflow needs. */
    static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    static final String WORKFLOW_FILE = "workflow.json";
    /** The rule catalog of a package, next to its workflow file (docs/registers.md). */
    public static final String CATALOG_FILE = "catalog.json";
    static final String IMAGES_DIRECTORY = "images";

    private final ObjectMapper mapper = new ObjectMapper();
    /** The directories steps write, from which no package is loaded: git on the host would read their config. */
    private final List<CheckoutsDirectory> stepWritten;
    private final String gitExecutable;
    private final long gitTimeoutMillis;

    /**
     * @param checkouts where the runs' checkouts lie, {@code <home>/workspaces}: no package is loaded from there
     * @param runs      where the runs' folders with their scratch directories lie, {@code <home>/runs}: nor from
     *                  there
     */
    public FilePackageLoader(Path checkouts, Path runs) {
        this(checkouts, runs, "git", GitInfo.TIMEOUT_SECONDS * 1000L);
    }

    /**
     * @param checkouts where the runs' checkouts lie, {@code <home>/workspaces}: no package is loaded from there
     * @param runs      every folder that holds run folders with their scratch directories: the runs folder of the
     *                  home, and {@code <home>/runs} when the operator named another one, since earlier runs may
     *                  still lie there. No package is loaded from any of them
     */
    public FilePackageLoader(Path checkouts, List<Path> runs) {
        this(CheckoutsDirectory.stepWritten(checkouts, runs), "git", GitInfo.TIMEOUT_SECONDS * 1000L);
    }

    /** For tests: with the runs' checkouts and the runs' folders, another git executable and timeout. */
    FilePackageLoader(Path checkouts, Path runs, String gitExecutable, long gitTimeoutMillis) {
        this(CheckoutsDirectory.stepWritten(checkouts, List.of(runs)), gitExecutable, gitTimeoutMillis);
    }

    /** For tests: with the runs' checkouts below {@code checkouts}, another git executable and timeout. */
    FilePackageLoader(Path checkouts, String gitExecutable, long gitTimeoutMillis) {
        this(List.of(new CheckoutsDirectory(checkouts)), gitExecutable, gitTimeoutMillis);
    }

    /** For tests: without directories to keep out. */
    FilePackageLoader() {
        this(List.of(), "git", GitInfo.TIMEOUT_SECONDS * 1000L);
    }

    /** For tests: without directories to keep out, another git executable and timeout. */
    FilePackageLoader(String gitExecutable, long gitTimeoutMillis) {
        this(List.of(), gitExecutable, gitTimeoutMillis);
    }

    private FilePackageLoader(List<CheckoutsDirectory> stepWritten, String gitExecutable, long gitTimeoutMillis) {
        this.stepWritten = stepWritten;
        this.gitExecutable = gitExecutable;
        this.gitTimeoutMillis = gitTimeoutMillis;
    }

    @Override
    public LoadedPackage load(Path location) {
        Path given = location.toAbsolutePath().normalize();
        Path directory = Files.isDirectory(given) ? given : given.getParent();
        Path file = directory.resolve(WORKFLOW_FILE);
        for (CheckoutsDirectory kept : stepWritten) {
            if (kept.holds(directory)) {
                throw new IllegalArgumentException(kept.refusal("The package", directory));
            }
        }
        if (!Files.isDirectory(given) && !given.getFileName().toString().equals(WORKFLOW_FILE)) {
            throw new IllegalArgumentException("Not a package: " + given + " is neither a directory nor a "
                    + WORKFLOW_FILE);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a package: " + file + " (" + WORKFLOW_FILE + ") does not exist");
        }
        try {
            // The digest first: it refuses a symbolic link anywhere in the package, workflow.json included, before
            // anything is read, so a link to a file of the operator never reaches the parser and its error message
            String digest = PackageDigest.of(directory);
            byte[] json = bounded(file);
            WorkflowDefinition definition = WorkflowJson.read(json);
            GitInfo git = GitInfo.of(directory, gitExecutable, gitTimeoutMillis);
            Path images = directory.resolve(IMAGES_DIRECTORY);
            Path catalog = directory.resolve(CATALOG_FILE);
            byte[] catalogJson = null;
            if (Files.isRegularFile(catalog, LinkOption.NOFOLLOW_LINKS)) {
                catalogJson = bounded(catalog);
            }
            return new LoadedPackage(directory, definition, json, digest, git.commit(), git.dirty(),
                    ImageDirectories.in(images), ImageDirectories.invalidNames(images), catalogJson,
                    seal(directory));
        } catch (JsonProcessingException e) {
            if (e instanceof com.fasterxml.jackson.databind.JsonMappingException mapping
                    && !mapping.getPath().isEmpty() && "model_choices".equals(mapping.getPath().getFirst()
                    .getFieldName()) && mapping.getPath().size() == 1) {
                // The parser's own words for a value of the wrong shape say nothing of what the key is for
                throw new IllegalArgumentException("Cannot read " + file + ": model_choices must be an object that"
                        + " maps the name of each model choice to its default and options (invalid-model-choice;"
                        + " docs/cli.md, \"Model choices\")", e);
            }
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * The bytes of the package's seal file, unjudged; {@code null} when the package carries none. The digest refused a
     * link before this is called. A file beyond the limit is refused, never taken for no seal: a package that carries
     * anything under that name is either sealed or not run.
     */
    private static byte[] seal(Path directory) throws IOException {
        Path file = directory.resolve(Seal.FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.size(file) > Seal.MAX_BYTES) {
            throw new IllegalArgumentException("Not a package that can run: " + file + " is larger than the limit of "
                    + Seal.MAX_BYTES / 1024 + " KiB for a seal");
        }
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(Seal.MAX_BYTES + 1);
            if (bytes.length > Seal.MAX_BYTES) {
                throw new IllegalArgumentException("Not a package that can run: " + file + " is larger than the limit"
                        + " of " + Seal.MAX_BYTES / 1024 + " KiB for a seal");
            }
            return bytes;
        }
    }

    @Override
    public List<Path> packagesIn(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve(WORKFLOW_FILE)))
                    .map(child -> child.toAbsolutePath().normalize()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the directory " + directory + ": " + e.getMessage(), e);
        }
    }

    /** A {@code workflow.json} that is a symbolic link is not read, as in {@link #load}: the package has no id. */
    @Override
    public Optional<String> idOf(Path packageDirectory) {
        try {
            JsonNode id = mapper.readTree(bounded(packageDirectory.resolve(WORKFLOW_FILE))).get("id");
            return id != null && id.isTextual() ? Optional.of(id.textValue()) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The bytes of a JSON file of a package, a symbolic link not followed. A package is untrusted and the file is
     * read into memory whole, so one beyond {@link #MAX_JSON_BYTES} is refused: the parser's own limits bound nesting
     * and the length of a string, not the length of the document. The stream is bounded too, for a file that grows
     * after its size was asked.
     *
     * @throws IllegalArgumentException if the file is larger than the limit
     */
    private static byte[] bounded(Path file) throws IOException {
        if (Files.size(file) > MAX_JSON_BYTES) {
            throw tooLarge(file);
        }
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) {
                throw tooLarge(file);
            }
            return bytes;
        }
    }

    private static IllegalArgumentException tooLarge(Path file) {
        return new IllegalArgumentException("Not a package: " + file + " is larger than the limit of "
                + MAX_JSON_BYTES / (1024 * 1024) + " MiB for a " + file.getFileName());
    }
}
