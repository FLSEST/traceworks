package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.ScratchDirectory;
import traceworks.core.port.ScratchEntry;
import traceworks.core.port.ScratchListing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Lists the run scratch directory after a step and works out the {@code step.files} event: the full listing, with a
 * blob for each new or changed regular file of at most 16 MiB; a file that holds a secret value gets neither blob nor
 * hash. It remembers what the record said last, so an
 * unchanged directory records nothing, and a file whose size and modification time did not change keeps its hash
 * without being read. It does its work without any lock of the recorder; its own methods are serialised.
 *
 * <p>It knows each path as the record names it, redacted: a recorder that goes on after a park takes over the last
 * listing from the record ({@link #restore}), which holds no value, so a listing is compared with it by the paths the
 * record would write for it. Two paths that the record cannot tell apart are one to it as well.
 */
final class ScratchFiles {

    static final String TOO_LARGE = "too large";
    static final String SECRET = "contains a secret";
    static final String CHANGED = "unreadable when recorded";

    private final Blobs blobs;
    private ScratchDirectory scratch;

    private Map<String, ObjectNode> files = new TreeMap<>();
    private Map<String, String> links = new TreeMap<>();
    private List<ScratchListing.Unreadable> unreadable = new ArrayList<>();
    /** The modification time of each file of {@link #files} as the last listing saw it. */
    private Map<String, Long> modified = new HashMap<>();
    private boolean truncated;

    ScratchFiles(Blobs blobs) {
        this.blobs = blobs;
    }

    synchronized void attach(ScratchDirectory scratch) {
        this.scratch = scratch;
    }

    /** Takes over what the last {@code step.files} of a record already said, for a recorder that goes on. */
    synchronized void restore(List<RecordedEvent> existing) {
        for (int i = existing.size() - 1; i >= 0; i--) {
            RecordedEvent e = existing.get(i);
            if (RecordTypes.STEP_FILES.equals(e.type())) {
                files = new TreeMap<>();
                links = new TreeMap<>();
                unreadable = new ArrayList<>();
                modified = new HashMap<>();
                e.fields().path("files").forEach(f -> files.put(f.path("path").asText(), (ObjectNode) f.deepCopy()));
                e.fields().path("links").forEach(l -> links.put(l.path("path").asText(), l.path("target").asText()));
                e.fields().path("unreadable").forEach(u -> unreadable.add(new ScratchListing.Unreadable(
                        u.path("path").asText(), u.path("reason").asText())));
                truncated = e.fields().path("truncated").asBoolean();
                return;
            }
        }
    }

    private String known(String path, long size, long time) {
        String recorded = blobs.redact(path);
        ObjectNode before = files.get(recorded);
        Long then = modified.get(recorded);
        return before != null && then != null && then == time && before.path("size").asLong() == size
                ? shaOf(before) : null;
    }

    private static String shaOf(JsonNode file) {
        JsonNode sha = file.get("sha256");
        return sha == null || sha.isNull() ? null : sha.asText();
    }

    /** The fields of a {@code step.files} line, without {@code step}; {@code null} when nothing changed. */
    synchronized ObjectNode changes() {
        if (scratch == null) {
            return null;
        }
        ScratchListing listing = scratch.list(this::known);
        List<ScratchEntry> entries = new ArrayList<>(listing.entries());
        entries.sort(Comparator.comparing(ScratchEntry::path));
        boolean cut = listing.truncated() || entries.size() > ScratchEntry.MAX_ENTRIES;
        if (entries.size() > ScratchEntry.MAX_ENTRIES) {
            entries = entries.subList(0, ScratchEntry.MAX_ENTRIES);
        }
        Map<String, ObjectNode> nowFiles = new TreeMap<>();
        Map<String, String> nowLinks = new TreeMap<>();
        // A list, not a map: several names can read the same as text, and none of them may vanish
        List<ScratchListing.Unreadable> nowUnreadable = new ArrayList<>();
        listing.unreadable().forEach(u -> nowUnreadable.add(new ScratchListing.Unreadable(blobs.redact(u.path()),
                u.reason())));
        Map<String, Long> nowModified = new HashMap<>();
        for (ScratchEntry entry : entries) {
            String recorded = blobs.redact(entry.path());
            if (entry.isLink()) {
                nowLinks.put(recorded, blobs.redact(entry.linkTarget()));
                continue;
            }
            nowModified.put(recorded, entry.modified());
            ObjectNode before = files.get(recorded);
            if (before != null && before.path("size").asLong() == entry.size()
                    && Objects.equals(shaOf(before), entry.sha256()) && entry.reason() == null) {
                nowFiles.put(recorded, before.deepCopy());
                continue;
            }
            ObjectNode node = file(entry, recorded, nowUnreadable);
            if (node != null) {
                nowFiles.put(recorded, node);
            }
        }
        nowUnreadable.sort(Comparator.comparing(ScratchListing.Unreadable::path));
        // A path that cannot be read now, or lies below one, is not gone; a cut listing does not say what is gone
        List<String> removed = new ArrayList<>();
        if (!cut) {
            for (String path : union(files.keySet(), links.keySet())) {
                if (!nowFiles.containsKey(path) && !nowLinks.containsKey(path) && !covered(path, nowUnreadable)) {
                    removed.add(path);
                }
            }
        }
        boolean same = removed.isEmpty() && cut == truncated && nowFiles.equals(files) && nowLinks.equals(links)
                && nowUnreadable.equals(unreadable);
        files = nowFiles;
        links = nowLinks;
        unreadable = nowUnreadable;
        modified = nowModified;
        truncated = cut;
        if (same) {
            return null;
        }
        ObjectNode f = Blobs.MAPPER.createObjectNode();
        ArrayNode fileArray = f.putArray("files");
        nowFiles.values().forEach(fileArray::add);
        ArrayNode linkArray = f.putArray("links");
        nowLinks.forEach((path, target) -> {
            ObjectNode l = linkArray.addObject();
            l.put("path", path);
            l.put("target", target);
        });
        ArrayNode unreadableArray = f.putArray("unreadable");
        nowUnreadable.forEach(entry -> {
            ObjectNode u = unreadableArray.addObject();
            u.put("path", entry.path());
            u.put("reason", entry.reason());
        });
        ArrayNode removedArray = f.putArray("removed");
        removed.forEach(removedArray::add);
        f.put("truncated", cut);
        return f;
    }

    /** Whether the path is an unreadable path or lies below one; the unreadable root {@code ""} covers everything. */
    private static boolean covered(String path, List<ScratchListing.Unreadable> unreadable) {
        for (ScratchListing.Unreadable u : unreadable) {
            if (u.path().isEmpty() || path.equals(u.path()) || path.startsWith(u.path() + "/")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(a);
        all.addAll(b);
        return new ArrayList<>(all);
    }

    /**
     * The record of a new or changed file. The size, the hash and the blob all come from the one read (which stops
     * at the size the listing saw), so they agree even when a step still writes the file. A file that cannot be read
     * goes to {@code failed}; nothing is left out of the record. {@code recorded} is its path as the record names it.
     */
    private ObjectNode file(ScratchEntry entry, String recorded, List<ScratchListing.Unreadable> failed) {
        ObjectNode node = Blobs.MAPPER.createObjectNode();
        node.put("path", recorded);
        if (entry.reason() != null) {
            return withoutBlob(node, entry.size(), entry.sha256(), entry.reason());
        }
        if (entry.size() > ScratchEntry.MAX_BLOB_BYTES) {
            return withoutBlob(node, entry.size(), entry.sha256(), TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = scratch.readScratch(entry.path(), entry.size());
        } catch (RuntimeException e) {
            failed.add(new ScratchListing.Unreadable(recorded, CHANGED));
            return null;
        }
        // One scan of the redactor decides: a file without a value is stored as it is
        JsonNode blob = blobs.ofUnlessSecret(bytes);
        if (blob == null) {
            // No hash either: the hash of a file that holds a short secret is a fingerprint that can be guessed
            return withoutBlob(node, bytes.length, null, SECRET);
        }
        String sha = EventChain.sha256Hex(bytes);
        node.put("size", bytes.length);
        node.put("sha256", sha);
        node.set("blob", blob);
        node.putNull("reason");
        return node;
    }

    private static ObjectNode withoutBlob(ObjectNode node, long size, String sha, String reason) {
        node.put("size", size);
        node.put("sha256", sha);
        node.putNull("blob");
        node.put("reason", reason);
        return node;
    }
}
