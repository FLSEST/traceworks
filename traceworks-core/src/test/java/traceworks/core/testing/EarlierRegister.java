package traceworks.core.testing;

import traceworks.core.record.EventChain;
import traceworks.core.register.RegisterEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A register as a version before registers had projects wrote it, byte for byte: the fixture
 * {@code registers/decided-before-agent} of the test resources. Its key is {@value #KEY}, its workflow
 * {@value #WORKFLOW}; its log has ten lines, a report with three findings and four decisions, and names no project.
 * That version kept it in the folder of the key itself, and anchored it in the ledger under the key alone.
 */
public final class EarlierRegister {

    public static final String KEY = "arch";
    public static final String WORKFLOW = "wf-1";

    private static final String FOLDER = "/registers/decided-before-agent/";

    private EarlierRegister() {
    }

    /** The lines of its log, in order, without their line feeds. */
    public static List<byte[]> lines() {
        List<byte[]> lines = new ArrayList<>();
        for (String line : new String(resource("events.jsonl"), StandardCharsets.UTF_8).split("\n")) {
            lines.add(line.getBytes(StandardCharsets.UTF_8));
        }
        return lines;
    }

    /** The blobs its lines point to, by their names. */
    public static Map<String, byte[]> blobs() {
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        for (byte[] line : lines()) {
            for (var fields = RegisterEvent.parse(line).fields().fields(); fields.hasNext(); ) {
                var field = fields.next();
                if (field.getKey().endsWith("_blob") && field.getValue().isTextual()) {
                    blobs.put(field.getValue().asText(), resource("blobs/" + field.getValue().asText()));
                }
            }
        }
        return blobs;
    }

    /** Its {@code state.json}. */
    public static byte[] state() {
        return resource("state.json");
    }

    /** The sequence number of its last line. */
    public static int lastSeq() {
        return lines().size() - 1;
    }

    /** The SHA-256 of its last line: what that version anchored in the ledger after its last write. */
    public static String head() {
        return EventChain.sha256Hex(lines().getLast());
    }

    /**
     * Writes the register into {@code keyFolder} as that version laid it out: {@code events.jsonl}, {@code blobs/},
     * {@code state.json}, {@code report.html} and {@code register.lock}, in the folder of the key itself.
     */
    public static void writeTo(Path keyFolder) {
        try {
            Files.createDirectories(keyFolder.resolve("blobs"));
            Files.write(keyFolder.resolve("events.jsonl"), resource("events.jsonl"));
            for (Map.Entry<String, byte[]> blob : blobs().entrySet()) {
                Files.write(keyFolder.resolve("blobs").resolve(blob.getKey()), blob.getValue());
            }
            Files.write(keyFolder.resolve("state.json"), state());
            Files.writeString(keyFolder.resolve("report.html"), "<html>as that version rendered it</html>\n");
            Files.write(keyFolder.resolve("register.lock"), new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] resource(String name) {
        try (InputStream in = EarlierRegister.class.getResourceAsStream(FOLDER + name)) {
            if (in == null) {
                throw new IllegalStateException("no test resource " + FOLDER + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
