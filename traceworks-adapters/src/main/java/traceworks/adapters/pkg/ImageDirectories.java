package traceworks.adapters.pkg;

import traceworks.core.container.ImageCatalog;
import traceworks.core.port.PackageImage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads image directories: a directory with a {@code Dockerfile} is an image, and its digest is the package digest. */
public final class ImageDirectories {

    static final String DOCKERFILE = "Dockerfile";

    private static final Pattern NAME = Pattern.compile(ImageCatalog.NAME_SYNTAX);

    private ImageDirectories() {
    }

    /** The most bytes a Dockerfile of a package may have: far more than one needs. */
    static final int MAX_DOCKERFILE_BYTES = 1024 * 1024;

    /**
     * The text of a package's Dockerfile, a symbolic link not followed. A package is untrusted and the file is read
     * into memory whole on every load, so one beyond {@link #MAX_DOCKERFILE_BYTES} is refused.
     *
     * @throws IllegalArgumentException if the file is larger than the limit
     */
    private static String bounded(Path file) throws IOException {
        try (var in = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_DOCKERFILE_BYTES + 1);
            if (bytes.length > MAX_DOCKERFILE_BYTES) {
                throw new IllegalArgumentException("Not a package: " + file + " is larger than the limit of "
                        + MAX_DOCKERFILE_BYTES / (1024 * 1024) + " MiB for a Dockerfile");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * The image in {@code directory}, its digest taken over the directory the same way as for a package
     * ({@link PackageDigest}), but over all of it: the directory is the build context, and the runtime sends a
     * {@code .git} in it like any other directory.
     *
     * @param build whether the image keeps the path of {@code directory} as its build context
     * @throws UncheckedIOException if the Dockerfile cannot be read
     */
    public static PackageImage of(String name, Path directory, boolean build) {
        try {
            String dockerfile = bounded(directory.resolve(DOCKERFILE));
            return new PackageImage(name, build ? directory.toAbsolutePath().normalize().toString() : null,
                    PackageDigest.ofAll(directory), dockerfile);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + directory.resolve(DOCKERFILE) + ": " + e.getMessage(), e);
        }
    }

    /**
     * The images under a package's {@code images/} directory, by name and sorted by name: each subdirectory with a
     * valid name ({@value ImageCatalog#NAME_SYNTAX}) and a Dockerfile. Empty when there is no such directory.
     */
    public static Map<String, PackageImage> in(Path imagesDirectory) {
        Map<String, PackageImage> images = new LinkedHashMap<>();
        for (Path child : withDockerfile(imagesDirectory)) {
            String name = child.getFileName().toString();
            if (NAME.matcher(name).matches()) {
                images.put(name, of(name, child, true));
            }
        }
        return images;
    }

    /**
     * The subdirectories of a package's {@code images/} directory that have a Dockerfile but a name that is not a valid
     * package image name, sorted: no {@code ./images/} reference can name them, so they are no images, and
     * {@code images build} and {@code check} name them.
     */
    public static List<String> invalidNames(Path imagesDirectory) {
        List<String> names = new ArrayList<>();
        for (Path child : withDockerfile(imagesDirectory)) {
            String name = child.getFileName().toString();
            if (!NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /** The subdirectories of {@code imagesDirectory} with a Dockerfile, sorted; empty when it does not exist. */
    private static List<Path> withDockerfile(Path imagesDirectory) {
        if (!Files.isDirectory(imagesDirectory)) return List.of();
        try (Stream<Path> children = Files.list(imagesDirectory)) {
            return children.sorted().filter(child -> Files.isDirectory(child)
                    && Files.isRegularFile(child.resolve(DOCKERFILE))).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + imagesDirectory + ": " + e.getMessage(), e);
        }
    }
}
