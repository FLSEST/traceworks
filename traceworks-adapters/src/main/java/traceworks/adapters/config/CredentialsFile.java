package traceworks.adapters.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import traceworks.core.port.CredentialStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads {@code <home>/credentials.json}: the values of secrets by name, {@code {"NAME": "value"}}. A
 * name matches {@code [A-Z_][A-Z0-9_]*} and a value is a string.
 *
 * <p>Only its owner may read or change the file: when its group or others can, it is refused, and the message names
 * the fix ({@code chmod 600}). On a file system without POSIX permissions Traceworks cannot tell who may read it, and
 * refuses it as well; the secrets can come from the environment there. No message shows what the file holds: a JSON
 * error names the line and column, not the text it stumbled on, and keeps no cause that would.
 */
public final class CredentialsFile {

    static final String FILE = "credentials.json";

    private static final Pattern NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final Set<PosixFilePermission> OTHERS = Set.of(PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE);

    private CredentialsFile() {
    }

    /**
     * The values in the home's credentials file by name; empty when there is none.
     *
     * @throws IllegalArgumentException when its group or others may read or change it, when the file system cannot
     *                                  say, or when it is not a JSON object of string values under valid names
     * @throws UncheckedIOException     when it cannot be read
     */
    public static Map<String, String> read(Path home) {
        Path file = home.resolve(FILE);
        if (!Files.exists(file)) {
            return Map.of();
        }
        checkPermissions(file);
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readAllBytes(file));
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            throw new IllegalArgumentException(file + " is not valid JSON" + (at == null ? ""
                    : " (line " + at.getLineNr() + ", column " + at.getColumnNr() + ")"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(file + " must hold a JSON object of names and values, such as"
                    + " {\"DEPLOY_TOKEN\": \"...\"}");
        }
        Map<String, String> values = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            String name = entry.getKey();
            if (!NAME.matcher(name).matches()) {
                // The key is not shown: a value pasted where the name belongs would be on the terminal
                throw new IllegalArgumentException("a key of " + file + " is not a secret name: use capital"
                        + " letters, digits and _, not starting with a digit");
            }
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("The value of " + name + " in " + file + " must be a string");
            }
            values.put(name, entry.getValue().textValue());
        }
        return values;
    }

    /** The path of the home's credentials file, for a message. */
    public static String location(Path home) {
        return home.resolve(FILE).toString();
    }

    /** The home's credentials file as the store of the credentials use case. */
    public static CredentialStore store(Path home) {
        return new CredentialStore() {
            @Override
            public String location() {
                return CredentialsFile.location(home);
            }

            @Override
            public Map<String, String> read() {
                return CredentialsFile.read(home);
            }

            @Override
            public void write(Map<String, String> values) {
                CredentialsFile.write(home, values);
            }
        };
    }

    /**
     * Writes the home's credentials file, readable and writable by its owner only. A home that does not exist is
     * created for its owner alone ({@code rwx------}); one that exists keeps its permissions. The values are written
     * to a new file of the owner's alone next to it, which then takes the place of the old one in one step: a reader
     * sees the old file or the new one, and no moment has a file others can read.
     *
     * @throws IllegalArgumentException when an existing file can be read or changed by its group or others (its
     *                                  values may be known to them, so it is not replaced silently), when the file
     *                                  system has no POSIX permissions, or when a name is not a name
     * @throws UncheckedIOException     when the home or the file cannot be written
     */
    public static void write(Path home, Map<String, String> values) {
        Path file = home.resolve(FILE);
        for (String name : values.keySet()) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' is not a secret name: use capital letters, digits"
                        + " and _, not starting with a digit");
            }
        }
        Path temp = null;
        try {
            if (!Files.isDirectory(home)) {
                if (home.getParent() != null) {
                    Files.createDirectories(home.getParent());
                }
                Files.createDirectory(home, only("rwx------"));
            }
            if (Files.exists(file)) {
                checkPermissions(file);
            }
            byte[] json = (new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(new TreeMap<>(values)) + "\n").getBytes(StandardCharsets.UTF_8);
            temp = Files.createTempFile(home, ".credentials-", ".tmp", only("rw-------"));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(json);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (UnsupportedOperationException e) {
            throw new IllegalArgumentException(home + " is on a file system without POSIX permissions, so Traceworks"
                    + " cannot keep a credentials file its owner's alone there; set the secrets as environment"
                    + " variables instead");
        } catch (IOException e) {
            // The exception of a file operation names paths, never what the file holds
            throw new UncheckedIOException("Cannot write " + file + ": " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // the write failed already and says so; the leftover holds what the operator's own file would
                }
            }
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> only(String permissions) {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions));
    }

    /**
     * When the home's credentials file was last modified; {@code null} when there is none, or its time cannot be read.
     * It reads no content.
     */
    public static Instant modified(Path home) {
        try {
            return Files.getLastModifiedTime(home.resolve(FILE)).toInstant();
        } catch (IOException | SecurityException e) {
            return null;   // no file, or no time to compare: no warning, and the resume goes on
        }
    }

    private static void checkPermissions(Path file) {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(file);
        } catch (UnsupportedOperationException e) {
            throw new IllegalArgumentException(file + " is on a file system without POSIX permissions, so Traceworks"
                    + " cannot tell who may read it; remove it and set the secrets as environment variables instead");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the permissions of " + file + ": " + e.getMessage(), e);
        }
        if (permissions.stream().anyMatch(OTHERS::contains)) {
            throw new IllegalArgumentException(file + " can be read or changed by its group or others; only its owner"
                    + " may: run chmod 600 " + file);
        }
    }
}
