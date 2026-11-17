package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Finder.Result;
import traceworks.selfaudit.areas.Finder.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * For the tests of the check of the changes: the folder the git step hands to the area tool, written without git
 * (a tree as {@code git ls-tree -r -z} prints it, blobs as {@code git cat-file --batch} prints them), and the files
 * of a directory as a base.
 */
final class Handover {

    static final String BASE = "a".repeat(40);
    static final String HEAD = "b".repeat(40);

    private Handover() {
    }

    /** Every regular file below {@code root}, not below {@code .git}, by its path, with its bytes as Latin-1 text. */
    static Map<String, String> files(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String path = root.relativize(file).toString();
                if (path.startsWith(".git/")) continue;
                files.put(path, new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
        return files;
    }

    private static byte[] bytes(String latin1) {
        return latin1.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The tree of these files, as {@code git ls-tree -r -z} prints it. */
    static byte[] tree(Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new TreeMap<>(files).forEach((path, content) -> {
            out.writeBytes(("100644 blob " + Changes.blobId(bytes(content), 40) + "\t").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(path.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        });
        return out.toByteArray();
    }

    /** These contents as {@code git cat-file --batch} prints them. */
    static byte[] batch(Collection<String> contents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String content : contents) {
            byte[] bytes = bytes(content);
            out.writeBytes((Changes.blobId(bytes, 40) + " blob " + bytes.length + "\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(bytes);
            out.write('\n');
        }
        return out.toByteArray();
    }

    /**
     * Writes the folder: the base has {@code baseFiles}, the workspace commit {@code headFiles}, and every content of
     * the base is among the blobs. The base came from the input {@code v1}.
     */
    static Path write(Path dir, Map<String, String> baseFiles, Map<String, String> headFiles) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("base-commit"), BASE + "\n");
        Files.writeString(dir.resolve("head"), HEAD + "\n");
        Files.writeString(dir.resolve("from"), "input\n");
        Files.writeString(dir.resolve("since"), "v1\n");
        Files.writeString(dir.resolve("previous-head"), "\n");
        Files.writeString(dir.resolve("base-date"), "2026-11-10T09:00:00Z\n");
        Files.writeString(dir.resolve("skipped"), "\n");
        Files.write(dir.resolve("base-tree"), tree(baseFiles));
        Files.write(dir.resolve("head-tree"), tree(headFiles));
        Files.write(dir.resolve("blobs"), batch(baseFiles.values()));
        return dir;
    }

    /** A finder's result that read these files of {@code root} and found nothing in them. */
    static Result read(Path root, String... paths) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        for (String path : paths) files.add(new SourceFile(path, Files.readString(root.resolve(path)).lines().toList()));
        return new Result(files, List.of(), List.of(), 0);
    }
}
