package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads one run folder from the outside, as docs/events.md describes it: the lines of {@code events.jsonl}, the
 * blobs they refer to, {@code manifest.json}, {@code run.html} and the ledger entry. It reads the files with Jackson and
 * nothing else of Traceworks, and reads them afresh on every call, so it sees what a later command added.
 */
final class RunFolder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    RunFolder(Path dir) {
        this.dir = dir;
    }

    /** The folder {@code <home>/runs/<day>/<runId>}; fails when there is none. */
    static RunFolder find(Path home, String runId) {
        Path runs = home.resolve("runs");
        try (Stream<Path> days = Files.isDirectory(runs) ? Files.list(runs) : Stream.empty()) {
            return days.map(day -> day.resolve(runId)).filter(Files::isDirectory).findFirst().map(RunFolder::new)
                    .orElseThrow(() -> new AssertionError("No run folder of run " + runId + " in " + runs));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path dir() {
        return dir;
    }

    /** The run id: the name of the folder. */
    String id() {
        return dir.getFileName().toString();
    }

    /** Every line of {@code events.jsonl}, in order. */
    List<JsonNode> events() {
        List<JsonNode> events = new ArrayList<>();
        for (String line : lines()) {
            try {
                events.add(JSON.readTree(line));
            } catch (IOException e) {
                throw new AssertionError("A line of " + dir.resolve("events.jsonl") + " is no JSON: " + line, e);
            }
        }
        return events;
    }

    /** The lines of {@code type}, in order. */
    List<JsonNode> events(String type) {
        return events().stream().filter(event -> type.equals(event.path("type").asText())).toList();
    }

    /** The lines of {@code type} of the run itself, without those of its child runs. */
    List<JsonNode> ownEvents(String type) {
        return events(type).stream().filter(event -> id().equals(event.path("run").asText())).toList();
    }

    /** The one line of {@code type} of the run itself; fails when there is none or more than one. */
    JsonNode only(String type) {
        List<JsonNode> found = ownEvents(type);
        if (found.size() != 1) {
            throw new AssertionError("Expected one " + type + " of run " + id() + ", found " + found.size()
                    + " in:\n" + String.join("\n", lines()));
        }
        return found.getFirst();
    }

    /** The event types of the run itself, in order. */
    List<String> types() {
        return events().stream().filter(event -> id().equals(event.path("run").asText()))
                .map(event -> event.path("type").asText()).toList();
    }

    /** The {@code run} ids of every line: the run itself and its child runs. */
    Set<String> runIds() {
        Set<String> ids = new LinkedHashSet<>();
        events().forEach(event -> ids.add(event.path("run").asText()));
        return ids;
    }

    /** The last {@code step.finished} of the run's own step {@code name}. */
    JsonNode finished(String name) {
        return ownEvents("step.finished").stream().filter(event -> name.equals(event.path("name").asText()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No step.finished of step " + name + " in run " + id()));
    }

    /** The status of the run's own step {@code name}, such as {@code COMPLETED} or {@code SKIPPED}. */
    String stepStatus(String name) {
        return finished(name).path("status").asText();
    }

    /** The step index of the run's own step {@code name}. */
    int stepIndex(String name) {
        return finished(name).path("step").asInt();
    }

    /** The output of the run's own step {@code name}: the blob of its last {@code step.output}. */
    String output(String name) {
        JsonNode output = ownEvents("step.output").stream().filter(event -> name.equals(event.path("name").asText()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No step.output of step " + name + " in run " + id()));
        return blob(output.path("output"));
    }

    /** The metadata of the run's own step {@code name}, as JSON. */
    JsonNode metadata(String name) {
        return json(blob(finished(name).path("metadata")));
    }

    /** The lines of {@code type} that belong to step index {@code step} of the run itself. */
    List<JsonNode> ofStep(String type, int step) {
        return ownEvents(type).stream().filter(event -> event.path("step").asInt(-1) == step).toList();
    }

    /** The text of the blob a reference ({@code {sha256, size}}) names; fails for {@code null}. */
    String blob(JsonNode reference) {
        if (reference == null || !reference.hasNonNull("sha256")) {
            throw new AssertionError("No blob reference: " + reference);
        }
        return read(dir.resolve("blobs").resolve(reference.path("sha256").asText()));
    }

    /** {@code run.finished}'s status, or {@code PARKED} for a run that parked and has not finished. */
    String status() {
        List<JsonNode> finished = ownEvents("run.finished");
        if (!finished.isEmpty()) {
            return finished.getLast().path("status").asText();
        }
        return ownEvents("run.parked").isEmpty() ? "UNFINISHED" : "PARKED";
    }

    boolean hasManifest() {
        return Files.isRegularFile(dir.resolve("manifest.json"));
    }

    JsonNode manifest() {
        try {
            return JSON.readTree(dir.resolve("manifest.json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ledger's copy of the manifest, {@code <home>/ledger/runs/<run-id>.json}, as bytes. */
    byte[] ledgerEntry(Path home) {
        try {
            return Files.readAllBytes(home.resolve("ledger").resolve("runs").resolve(id() + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean hasHtml() {
        return Files.isRegularFile(dir.resolve("run.html"));
    }

    String html() {
        return read(dir.resolve("run.html"));
    }

    /** {@code events.jsonl} as written, bytes and all. */
    Path eventsFile() {
        return dir.resolve("events.jsonl");
    }

    /** Every blob of the folder, as text. */
    List<String> blobTexts() {
        try (Stream<Path> blobs = Files.list(dir.resolve("blobs"))) {
            return blobs.sorted().map(RunFolder::read).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks the hash chain by hand, as docs/events.md ("Checking by hand") says: {@code prev} of line 0 is the SHA-256
     * of {@code traceworks-run-v1|<run id>}, {@code prev} of every later line the SHA-256 of the line before it, and
     * {@code seq} counts from 0. Returns the head, the hash of the last line.
     */
    String chainHead() {
        String prev = sha256(("traceworks-run-v1|" + id()).getBytes(StandardCharsets.UTF_8));
        int seq = 0;
        for (String line : lines()) {
            JsonNode event = json(line);
            if (!prev.equals(event.path("prev").asText())) {
                throw new AssertionError("The chain breaks at seq " + seq + ": " + line);
            }
            if (event.path("seq").asInt(-1) != seq) {
                throw new AssertionError("Expected seq " + seq + " in: " + line);
            }
            prev = sha256(line.getBytes(StandardCharsets.UTF_8));
            seq++;
        }
        return prev;
    }

    private List<String> lines() {
        String text = read(eventsFile());
        if (!text.isEmpty() && !text.endsWith("\n")) {
            throw new AssertionError("The last line of " + eventsFile() + " has no line feed");
        }
        String[] parts = text.split("\n", -1);
        return List.of(parts).subList(0, parts.length - 1);
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new AssertionError("No JSON: " + text, e);
        }
    }

    /** A file as UTF-8 text; bytes that are no UTF-8 read as the replacement character. */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
