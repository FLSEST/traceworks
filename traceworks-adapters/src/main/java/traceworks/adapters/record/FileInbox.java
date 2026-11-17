package traceworks.adapters.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import traceworks.core.port.Inbox;
import traceworks.core.port.InboxRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The {@code inbox/} folder of a run: one JSON file per request, taken oldest first and moved to {@code done/}.
 * {@code inbox} and {@code done} count only as real directories and a request only as a regular file; a link at any
 * of them is not followed.
 */
final class FileInbox implements Inbox {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;

    /**
     * How large a request file may be. A request is read into memory whole, and the inbox of a run folder opened
     * by its path may hold anything: a larger file is not read to its end, and none is posted.
     */
    static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;

    FileInbox(Path dir) {
        this.dir = dir;
    }

    @Override
    public void post(InboxRequest request) {
        if (!request.id().matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("Not a usable request id: '" + request.id() + "'");
        }
        ObjectNode json = MAPPER.createObjectNode();
        json.put("id", request.id());
        json.put("kind", request.kind());
        json.put("run", request.run());
        json.put("step", request.step());
        json.put("question_seq", request.questionSeq());
        json.put("answer", request.answer());
        ObjectNode values = json.putObject("values");
        request.values().forEach(values::put);
        json.put("comment", request.comment());
        json.put("actor", request.actor());
        json.put("at", request.at().toString());
        try {
            byte[] bytes = MAPPER.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("The request is too large for the inbox of a run: "
                        + bytes.length + " bytes, and at most " + MAX_REQUEST_BYTES + " are taken");
            }
            if (Files.isSymbolicLink(dir)) {
                throw new IOException(dir + " is a link, not the run's inbox");
            }
            Files.createDirectories(dir);
            AtomicFiles.write(dir.resolve(request.at().toEpochMilli() + "-" + request.id() + ".json"), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot post to " + dir + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<InboxRequest> take() {
        List<Path> files;
        try {
            // No link is followed: a run folder opened by path may come from anyone, and an inbox that is a link
            // would have the JSON files of the folder it names (the Traceworks home's, say) moved aside
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".json")
                                && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .sorted().toList();
            }
            Path done = dir.resolve("done");
            if (Files.exists(done, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(done, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(done + " is not a directory of the inbox (a link or a file)");
                }
            } else {
                Files.createDirectory(done);
            }
            List<InboxRequest> taken = new ArrayList<>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                InboxRequest parsed = null;
                try {
                    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] bytes = in.readNBytes(MAX_REQUEST_BYTES + 1);
                        // A file beyond the limit is no request: it is not read further and not parsed
                        parsed = bytes.length > MAX_REQUEST_BYTES ? null : parse(bytes);
                    }
                } catch (IOException | RuntimeException e) {
                    // moved aside below
                }
                try {
                    if (parsed == null) {
                        AtomicFiles.move(file, done.resolve(name + ".invalid"));
                    } else {
                        AtomicFiles.move(file, done.resolve(name));
                        taken.add(parsed);
                    }
                } catch (IOException e) {
                    // stays in the inbox for the next take; the requests already moved are still returned
                }
            }
            return taken;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot take from " + dir + ": " + e.getMessage(), e);
        }
    }

    private static InboxRequest parse(byte[] bytes) throws IOException {
        JsonNode n = MAPPER.readTree(bytes);
        if (n == null || !n.isObject() || !n.path("id").isTextual() || !n.path("kind").isTextual()
                || !n.path("at").isTextual()) {
            throw new IllegalArgumentException("not an inbox request");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.path("values").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            values.put(e.getKey(), e.getValue().asText());
        }
        return new InboxRequest(
                n.get("id").asText(),
                n.get("kind").asText(),
                text(n, "run"),
                integer(n, "step"),
                integer(n, "question_seq"),
                text(n, "answer"),
                values,
                text(n, "comment"),
                text(n, "actor"),
                Instant.parse(n.get("at").asText()));
    }

    private static Integer integer(JsonNode n, String field) {
        return n.path(field).isIntegralNumber() ? n.get(field).asInt() : null;
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).isTextual() ? n.get(field).asText() : null;
    }
}
