package traceworks.adapters.record;

import traceworks.core.port.StoredRun;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The register folder of a run in its folder: the files the register of an audit workflow handed the run at its
 * start. {@link FileStoredRun} writes it once and reads the digests of its files through this class.
 */
final class FileRegisterFolder {

    private final Path folder;

    FileRegisterFolder(Path folder) {
        this.folder = folder;
    }

    String directory() {
        return folder.toString();
    }

    /**
     * The folder is made new and stays writable for the owner, so that the run folder can be removed like any other;
     * each file is for reading only. That is a hint, not a guard: a container mounts the folder read-only, and a host
     * run compares the files with the digests it recorded ({@link #digests}).
     */
    String write(Map<String, byte[]> files) {
        try {
            Files.createDirectory(folder);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("The register folder " + folder + " exists already: a run gets it once");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the register folder " + folder, e);
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (!file.getKey().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
                throw new IllegalArgumentException("Not a file name for the register folder: " + file.getKey());
            }
            Path target = folder.resolve(file.getKey());
            try {
                Files.write(target, file.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                if (!target.toFile().setWritable(false, false)) {
                    throw new IOException("its write permission cannot be taken away");
                }
                target.toFile().setReadable(true, false);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write " + target, e);
            }
        }
        return folder.toString();
    }

    /**
     * The files of the folder as they are now, each with the SHA-256 of its bytes, or with
     * {@link StoredRun#NOT_A_FILE} for an entry that is no regular file; empty when the run has no such folder.
     */
    Optional<Map<String, String>> digests() {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();   // not there, or a link or a file in its place: no folder of the run
        }
        Map<String, String> digests = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    digests.put(name, StoredRun.NOT_A_FILE);
                    continue;
                }
                try (InputStream in = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)) {
                    digests.put(name, sha256(in));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the register folder " + folder, e);
        }
        return Optional.of(digests);
    }

    private static String sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
