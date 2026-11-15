package traceworks.adapters.pkg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The digest of a package: SHA-256 over the lines {@code path NUL mode NUL sha256(content) LF} of all
 * regular files, sorted by the UTF-8 bytes of the path. Paths are relative to the package and use {@code /}; the mode
 * is {@code 755} when the owner may execute the file, else {@code 644}. The top-level {@code .git/} directory is not
 * part of the package: git rewrites it without any file of the package changing, so a digest over it would differ
 * from run to run. A step does not see what the digest leaves out: the container runtime covers the package's
 * {@code .git/} with an empty directory ({@code ContainerArguments}). The seal file, the regular file
 * {@value #SEAL_FILE} directly in the package directory, is not part of the package either: it carries the signature
 * over the digest, so it cannot be in it. It is mounted all the same, so the seal check takes it only when it is
 * exactly the seal Traceworks writes, with nothing the signature does not cover ({@code core.seal.Seal}). Everything
 * else is hashed, a file or a directory of that name anywhere else too. A symbolic link anywhere in the package is
 * refused, the seal file included: it could point outside it, so the digest would not describe what runs.
 */
public final class PackageDigest {

    /** The seal file of a package, which its digest leaves out: {@code core.seal.Seal#FILE_NAME}. */
    static final String SEAL_FILE = traceworks.core.seal.Seal.FILE_NAME;

    private PackageDigest() {
    }

    /**
     * The digest of the package in {@code directory}, as lowercase hex.
     *
     * @throws IllegalArgumentException if the package holds a symbolic link
     * @throws UncheckedIOException     if a file cannot be read
     */
    public static String of(Path directory) {
        return of(directory, true);
    }

    /**
     * The digest of all of {@code directory}, a top-level {@code .git} included: for a directory that is handed
     * over whole, like the build context of an image.
     *
     * @throws IllegalArgumentException if the directory holds a symbolic link
     * @throws UncheckedIOException     if a file cannot be read
     */
    public static String ofAll(Path directory) {
        return of(directory, false);
    }

    private static String of(Path directory, boolean asPackage) {
        List<Entry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(directory)) return FileVisitResult.CONTINUE;
                    if (Files.isSymbolicLink(dir)) throw refused(directory, dir);
                    return asPackage && dir.getParent().equals(directory)
                            && dir.getFileName().toString().equals(".git")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) throw refused(directory, file);
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String relative = relative(directory, file);
                    if (asPackage && relative.equals(SEAL_FILE)) return FileVisitResult.CONTINUE;
                    // A name is bytes, and its text is lossy: a byte that is no valid text becomes U+FFFD. Two names
                    // that differ only there would be one path here, and a package could swap the files unseen
                    if (!file.getFileSystem().getPath(file.toString()).equals(file)) {
                        throw new IllegalArgumentException("The package contains a file name that is not valid"
                                + " text in this system's encoding, so the digest could not tell it from another: "
                                + relative);
                    }
                    entries.add(new Entry(relative.getBytes(StandardCharsets.UTF_8), mode(file), sha256(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the package " + directory + ": " + e.getMessage(), e);
        }
        entries.sort(Comparator.comparing(Entry::path, java.util.Arrays::compareUnsigned));
        MessageDigest digest = newSha256();
        for (Entry entry : entries) {
            digest.update(entry.path());
            digest.update((byte) 0);
            digest.update(entry.mode().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(entry.contentHash().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Entry(byte[] path, String mode, String contentHash) {
    }

    private static IllegalArgumentException refused(Path root, Path link) {
        return new IllegalArgumentException("The package contains a symbolic link: " + relative(root, link)
                + " (symbolic links are refused because they could point outside the package)");
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    private static String mode(Path file) throws IOException {
        return Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                .contains(PosixFilePermission.OWNER_EXECUTE) ? "755" : "644";
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
