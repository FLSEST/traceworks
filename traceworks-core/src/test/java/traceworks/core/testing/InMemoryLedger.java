package traceworks.core.testing;

import traceworks.core.port.Ledger;
import traceworks.core.port.LedgerRuns;
import traceworks.core.port.RegisterAnchor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@link Ledger} in memory with the contract of the git ledger; the entry id is a running number. */
public final class InMemoryLedger implements Ledger {

    private final Map<String, byte[]> manifests = new LinkedHashMap<>();
    private final AtomicInteger prepared = new AtomicInteger();

    /** Always usable; counts the calls. */
    @Override
    public void prepare() {
        prepared.incrementAndGet();
    }

    /** How often {@link #prepare} was called. */
    public int prepared() {
        return prepared.get();
    }

    @Override
    public synchronized String record(String runId, String status, byte[] manifestJson) {
        if (manifests.containsKey(runId)) throw new IllegalStateException("Run is already recorded: " + runId);
        manifests.put(runId, manifestJson.clone());
        endNamed.put(runId, status);
        return "commit-" + manifests.size();
    }

    @Override
    public synchronized Optional<byte[]> find(String runId) {
        byte[] bytes = manifests.get(runId);
        return Optional.ofNullable(bytes == null ? null : bytes.clone());
    }

    private final Map<String, byte[]> starts = new LinkedHashMap<>();
    /** What the messages of the commits say, which no later commit takes back. */
    private final java.util.Set<String> startNamed = new java.util.TreeSet<>();
    private final Map<String, String> endNamed = new LinkedHashMap<>();
    private final Map<String, String> acks = new LinkedHashMap<>();
    private RuntimeException startFailure;
    private RuntimeException readFailure;

    @Override
    public synchronized String recordStart(String runId, byte[] startJson) {
        if (startFailure != null) throw startFailure;
        if (starts.containsKey(runId)) throw new IllegalStateException("The start of the run is already noted: " + runId);
        starts.put(runId, startJson.clone());
        startNamed.add(runId);
        return "start-" + starts.size();
    }

    @Override
    public synchronized Optional<byte[]> findStart(String runId) {
        if (readFailure != null) throw readFailure;
        byte[] bytes = starts.get(runId);
        return Optional.ofNullable(bytes == null ? null : bytes.clone());
    }

    @Override
    public synchronized LedgerRuns runs() {
        if (readFailure != null) throw readFailure;
        java.util.Set<String> ids = new java.util.TreeSet<>(startNamed);
        ids.addAll(changes.keySet());
        ids.addAll(starts.keySet());
        ids.addAll(manifests.keySet());
        ids.addAll(endNamed.keySet());
        ids.addAll(acks.keySet());
        List<LedgerRuns.Run> runs = new ArrayList<>();
        for (String id : ids) {
            runs.add(new LedgerRuns.Run(id, startNamed.contains(id), starts.get(id), endNamed.get(id),
                    manifests.get(id), acks.containsKey(id)
                    ? acks.get(id).getBytes(java.nio.charset.StandardCharsets.UTF_8) : null,
                    changes.getOrDefault(id, List.of())));
        }
        return new LedgerRuns(runs);
    }

    private final Map<String, List<String>> changes = new LinkedHashMap<>();
    /** A problem of the anchors of the register {@code key}, as the git ledger words one. */
    public synchronized void anchorProblem(String key, String sentence) {
        changes.computeIfAbsent("registers." + key, id -> new ArrayList<>()).add(sentence);
    }


    @Override
    public synchronized List<String> changes(String runId) {
        if (readFailure != null) throw readFailure;
        return List.copyOf(changes.getOrDefault(runId, List.of()));
    }

    /**
     * Puts {@code json} in the place of the end entry of {@code runId}, as a later commit {@code commit} that
     * changes {@code runs/<run-id>.json} would.
     */
    public synchronized void changeEnd(String runId, byte[] json, String commit) {
        manifests.put(runId, json.clone());
        changes.computeIfAbsent(runId, id -> new ArrayList<>()).add("commit " + commit + " changed runs/" + runId
                + ".json, which another commit added");
    }

    @Override
    public synchronized void acknowledge(String runId, byte[] line) {
        acks.merge(runId, new String(line, java.nio.charset.StandardCharsets.UTF_8).strip() + "\n", String::concat);
    }

    /** Every start from now on throws {@code failure}, as a ledger that cannot be written would; {@code null} ends that. */
    public synchronized void failStarts(RuntimeException failure) {
        this.startFailure = failure;
    }

    /** Every read of the starts and the runs throws {@code failure} from now on; {@code null} ends that. */
    public synchronized void failReads(RuntimeException failure) {
        this.readFailure = failure;
    }

    /** Takes the start entry of {@code runId} out, as a later commit to the ledger would: its message stays. */
    public synchronized void removeStart(String runId) {
        starts.remove(runId);
        missing(runId, "starts/" + runId + ".json");
    }

    private void missing(String runId, String path) {
        changes.computeIfAbsent(runId, id -> new ArrayList<>()).add("commit 000000000000 names " + path
                + ", and the ledger does not hold it: a later commit removed it, or the commit added none");
    }

    /** Puts {@code json} in the place of the start entry of {@code runId}, with the message of a start. */
    public synchronized void replaceStart(String runId, byte[] json) {
        starts.put(runId, json.clone());
        startNamed.add(runId);
    }

    /** Adds an end entry as a commit made by hand would: any content, under the message of {@code status}. */
    public synchronized void forgeEnd(String runId, String status, byte[] json) {
        manifests.put(runId, json.clone());
        endNamed.put(runId, status);
    }

    /** A commit whose message says that {@code runId} started, and that adds no entry. */
    public synchronized void nameStart(String runId) {
        startNamed.add(runId);
        missing(runId, "starts/" + runId + ".json");
    }

    /** Forgets everything, as a ledger that was deleted and made again. */
    public synchronized void startOver() {
        starts.clear();
        startNamed.clear();
        endNamed.clear();
        manifests.clear();
        acks.clear();
        changes.clear();
    }

    /** How many start entries the ledger holds. */
    public synchronized int starts() {
        return starts.size();
    }

    private final Map<String, List<RegisterAnchor>> anchors = new LinkedHashMap<>();
    private RuntimeException anchorFailure;

    @Override
    public synchronized void anchorRegister(String key, int seq, String head) {
        if (anchorFailure != null) throw anchorFailure;
        List<RegisterAnchor> list = anchors.computeIfAbsent(key, k -> new ArrayList<>());
        RegisterAnchor anchor = new RegisterAnchor(seq, head);
        if (list.isEmpty() || !list.getLast().equals(anchor)) list.add(anchor);
    }

    @Override
    public synchronized List<RegisterAnchor> registerAnchors(String key) {
        return List.copyOf(anchors.getOrDefault(key, List.of()));
    }

    /** Every anchoring from now on throws {@code failure}, as a ledger without git would; {@code null} ends that. */
    public synchronized void failAnchors(RuntimeException failure) {
        this.anchorFailure = failure;
    }

    /** Drops the anchors of {@code key}, as a register written before anchors existed has none. */
    public synchronized void forgetAnchors(String key) {
        anchors.remove(key);
    }
}
