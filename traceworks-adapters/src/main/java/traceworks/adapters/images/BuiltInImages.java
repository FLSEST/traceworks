package traceworks.adapters.images;

import traceworks.adapters.pkg.ImageDirectories;
import traceworks.core.port.BuiltInImageSource;
import traceworks.core.port.PackageImage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The built-in images, read from the resources under {@code traceworks-images/<name>/} that the build copies from the
 * repository's {@code images/}. Their digests are computed over the directory like a package's, from a temporary copy.
 */
public final class BuiltInImages implements BuiltInImageSource {

    /** The built-in images, in the order they are built. */
    static final List<String> NAMES = List.of("shell", "claude", "pi", "egress");

    private static final String RESOURCE_ROOT = "traceworks-images";

    private List<PackageImage> all;

    @Override
    public synchronized List<PackageImage> all() {
        if (all == null) {
            Path root = null;
            try {
                root = Files.createTempDirectory("traceworks-images-");
                List<PackageImage> images = new ArrayList<>();
                for (String name : NAMES) {
                    Path directory = root.resolve(name);
                    copyResources(name, directory);
                    images.add(ImageDirectories.of(name, directory, false));
                }
                all = List.copyOf(images);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the built-in images: " + e.getMessage(), e);
            } finally {
                delete(root);
            }
        }
        return all;
    }

    @Override
    public BuildContext context(PackageImage image) throws IOException {
        if (!NAMES.contains(image.name())) {
            throw new IllegalArgumentException("'" + image.name() + "' is no built-in image");
        }
        Path directory = Files.createTempDirectory("traceworks-image-" + image.name() + "-");
        try {
            copyResources(image.name(), directory);
        } catch (IOException | RuntimeException e) {
            delete(directory);
            throw e;
        }
        return new BuildContext() {
            @Override
            public String directory() {
                return directory.toString();
            }

            @Override
            public void close() {
                delete(directory);
            }
        };
    }

    /**
     * Copies the resources of one image into {@code target}. Synchronised because the jar's file system is one per
     * JVM: a second caller that found it open would share it, and the first would close it under the second's feet
     * ({@code ClosedFileSystemException}), which two runs in one process did.
     */
    private static synchronized void copyResources(String name, Path target) throws IOException {
        URL url = BuiltInImages.class.getResource("/" + RESOURCE_ROOT + "/" + name);
        if (url == null) {
            throw new IOException("The built-in image '" + name + "' is missing from this distribution");
        }
        FileSystem opened = null;
        try {
            URI uri = url.toURI();
            Path source;
            if (uri.getScheme().equals("jar")) {
                try {
                    opened = FileSystems.newFileSystem(uri, Map.of());
                    source = opened.getPath("/" + RESOURCE_ROOT + "/" + name);
                } catch (FileSystemAlreadyExistsException shared) {
                    source = FileSystems.getFileSystem(uri).getPath("/" + RESOURCE_ROOT + "/" + name);
                }
            } else {
                source = Path.of(uri);
            }
            copyTree(source, target);
        } catch (URISyntaxException e) {
            throw new IOException("Cannot read the built-in image '" + name + "': " + e.getMessage(), e);
        } finally {
            if (opened != null) opened.close();
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(file, destination);
                }
            }
        }
    }

    private static void delete(Path directory) {
        if (directory == null) return;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | UncheckedIOException ignored) {
            // A temporary directory that stays is only clutter in the temp directory
        }
    }
}
