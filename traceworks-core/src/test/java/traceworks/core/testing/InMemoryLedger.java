package traceworks.core.testing;

import traceworks.core.port.Ledger;
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
        return "commit-" + manifests.size();
    }

    @Override
    public synchronized Optional<byte[]> find(String runId) {
        byte[] bytes = manifests.get(runId);
        return Optional.ofNullable(bytes == null ? null : bytes.clone());
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
