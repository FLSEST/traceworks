package traceworks.core.testing;

import traceworks.core.port.EventLog;
import traceworks.core.port.Inbox;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.NoSuchRunException;
import traceworks.core.port.RunStore;
import traceworks.core.port.ScratchDirectory;
import traceworks.core.port.ScratchEntry;
import traceworks.core.port.ScratchListing;
import traceworks.core.port.StoredRun;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RunStore} in memory, with the semantics of the file store: every {@code open} returns a new handle on the
 * same run, and the writer lock belongs to one handle. A handle appends to the log only while it holds the lock, as
 * the file store's log refuses, so a test notices a write that breaks the single-writer rule. Tests control the lock
 * of another process: {@link InMemoryStoredRun#simulateOtherWriter(boolean)} stands for another process that writes,
 * and {@link InMemoryStoredRun#simulateWriterDied()} for a process that died while holding the lock.
 */
public final class InMemoryRunStore implements RunStore {

    private final Map<String, RunState> runs = new LinkedHashMap<>();
    private int failEventWritesAfter = -1;
    private volatile boolean failScratch;
    private volatile boolean failDiscard;

    @Override
    public synchronized StoredRun create(String runId, Instant startedAt) {
        if (runs.containsKey(runId)) {
            throw new IllegalStateException("Run already exists: " + runId);
        }
        RunState state = new RunState(runId, this);
        if (failEventWritesAfter >= 0) {
            state.log.failAfter(failEventWritesAfter);
        }
        runs.put(runId, state);
        InMemoryStoredRun handle = new InMemoryStoredRun(state);
        handle.tryLockWriter();
        return handle;
    }

    @Override
    public synchronized InMemoryStoredRun open(String runRef) {
        String id = RunStore.match(runRef, runIds());
        RunState state = runs.get(id);
        if (state == null) {
            throw new NoSuchRunException(runRef);
        }
        return new InMemoryStoredRun(state);
    }

    @Override
    public synchronized List<String> runIds() {
        List<String> ids = new ArrayList<>(runs.keySet());
        java.util.Collections.reverse(ids);
        return ids;
    }

    /** The event logs of the runs created from now on fail every append after the first {@code lines} lines. */
    public synchronized void failEventWritesAfter(int lines) {
        failEventWritesAfter = lines;
    }

    /** Whether preparing the scratch directory of any run fails from now on, as for a link in its place. */
    public void failScratchPreparation(boolean fail) {
        failScratch = fail;
    }

    /** Whether discarding a run fails from now on, as for a run folder that cannot be removed. */
    public void failDiscard(boolean fail) {
        failDiscard = fail;
    }

    /** Removes the run, folder and all, as someone who deletes a run folder does. */
    public synchronized void forget(String runId) {
        runs.remove(runId);
    }

    private synchronized void remove(String runId) {
        runs.remove(runId);
    }

    /** What every handle of one run shares. */
    private static final class RunState {
        final String runId;
        final InMemoryEventLog log = new InMemoryEventLog();
        final InMemoryBlobStore blobs = new InMemoryBlobStore();
        final InMemoryInbox inbox = new InMemoryInbox();
        final InMemoryScratch scratch = new InMemoryScratch();
        final AtomicInteger scratchPrepared = new AtomicInteger();
        InMemoryStoredRun owner;
        boolean otherWriter;
        boolean outsideStore;
        byte[] manifest;
        String view;

        final InMemoryRunStore store;

        volatile Map<String, byte[]> registerFolder;

        RunState(String runId, InMemoryRunStore store) {
            this.runId = runId;
            this.store = store;
        }
    }

    /** A handle on a run of the in-memory store. */
    public static final class InMemoryStoredRun implements StoredRun {

        private final RunState state;
        private final EventLog guardedLog;

        InMemoryStoredRun(RunState state) {
            this.state = state;
            this.guardedLog = new GuardedLog();
        }

        @Override
        public String runId() {
            return state.runId;
        }

        @Override
        public String location() {
            return "memory:" + state.runId;
        }

        /** The log as this handle sees it: appending and discarding need this handle's writer lock. */
        @Override
        public EventLog events() {
            return guardedLog;
        }

        /** The shared log itself, for tests that tear a tail or read it without a handle's rules. */
        public InMemoryEventLog log() {
            return state.log;
        }

        @Override
        public InMemoryBlobStore blobs() {
            return state.blobs;
        }

        @Override
        public InMemoryInbox inbox() {
            return state.inbox;
        }

        @Override
        public String scratchDirectory() {
            return "/runs/" + state.runId + "/scratch";
        }

        /**
         * Counts the call; a run folder from before scratch directories gets its directory here.
         *
         * @throws IllegalStateException when the store was told to fail it ({@link #failScratchPreparation})
         */
        @Override
        public String prepareScratchDirectory() {
            state.scratchPrepared.incrementAndGet();
            if (state.store.failScratch) {
                throw new IllegalStateException("The scratch directory of run " + state.runId + " is a link");
            }
            return scratchDirectory();
        }

        @Override
        public String registerDirectory() {
            return "/runs/" + state.runId + "/register";
        }

        @Override
        public String writeRegisterFiles(Map<String, byte[]> files) {
            synchronized (state) {
                if (state.registerFolder != null) {
                    throw new IllegalStateException("The register folder of run " + state.runId + " exists already");
                }
                state.registerFolder = new java.util.concurrent.ConcurrentHashMap<>();
                files.forEach((name, content) -> state.registerFolder.put(name, content.clone()));
            }
            return registerDirectory();
        }

        @Override
        public Optional<Map<String, String>> registerFileDigests() {
            Map<String, byte[]> folder = state.registerFolder;
            if (folder == null) {
                return Optional.empty();
            }
            Map<String, String> digests = new java.util.TreeMap<>();
            folder.forEach((name, content) -> digests.put(name,
                    traceworks.core.record.EventChain.sha256Hex(content)));
            return Optional.of(digests);
        }

        /**
         * The register folder as a test reads and changes it, by file name; {@code null} for a run without one. In
         * the order the files were written.
         */
        public Map<String, byte[]> registerFolder() {
            Map<String, byte[]> folder = state.registerFolder;
            if (folder == null) {
                return null;
            }
            return new java.util.AbstractMap<>() {
                @Override
                public java.util.Set<Entry<String, byte[]>> entrySet() {
                    Map<String, byte[]> ordered = new java.util.LinkedHashMap<>();
                    traceworks.core.register.RegisterFiles.NAMES.forEach(name -> {
                        if (folder.containsKey(name)) ordered.put(name, folder.get(name));
                    });
                    new java.util.TreeMap<>(folder).forEach(ordered::putIfAbsent);
                    return ordered.entrySet();
                }

                @Override
                public byte[] put(String name, byte[] content) {
                    return folder.put(name, content);
                }

                @Override
                public byte[] remove(Object name) {
                    return folder.remove(name);
                }
            };
        }

        /** Removes the register folder, as a person who deleted it from the run folder does. */
        public void removeRegisterFolder() {
            state.registerFolder = null;
        }

        /** Removes the run from the store; it must have no event yet. */
        @Override
        public void discard() {
            if (state.store.failDiscard) {
                throw new java.io.UncheckedIOException(new java.io.IOException("Cannot remove the folder of run "
                        + state.runId));
            }
            if (!state.log.readAll().isEmpty()) {
                throw new IllegalStateException("Run " + state.runId + " has events; it is not discarded");
            }
            close();
            state.store.remove(state.runId);
        }

        /** How often the scratch directory was prepared, by any handle. */
        public int scratchPrepared() {
            return state.scratchPrepared.get();
        }

        /** The scratch directory the tests fill; it is shared by every handle of the run. */
        public InMemoryScratch scratch() {
            return state.scratch;
        }

        @Override
        public ScratchListing list(ScratchListing.Known known) {
            return state.scratch.list(known);
        }

        @Override
        public byte[] readScratch(String path, long maxBytes) {
            return state.scratch.readScratch(path, maxBytes);
        }

        @Override
        public boolean tryLockWriter() {
            synchronized (state) {
                if (state.owner == this) {
                    return true;
                }
                if (state.owner != null || state.otherWriter) {
                    return false;
                }
                state.owner = this;
                return true;
            }
        }

        @Override
        public boolean holdsWriterLock() {
            synchronized (state) {
                return state.owner == this;
            }
        }

        @Override
        public boolean writerAlive() {
            synchronized (state) {
                return state.owner != null || state.otherWriter;
            }
        }

        @Override
        public boolean inStore() {
            synchronized (state) {
                return !state.outsideStore;
            }
        }

        /** Makes the run look like a folder opened by path from outside the runs folder of this home (a copied folder). */
        public void simulateOutsideStore() {
            synchronized (state) {
                state.outsideStore = true;
            }
        }

        /** Makes the lock look held (or free again) by another process. */
        public void simulateOtherWriter(boolean alive) {
            synchronized (state) {
                state.otherWriter = alive;
            }
        }

        /** The process that held the lock died: the lock is free and nobody writes. */
        public void simulateWriterDied() {
            synchronized (state) {
                state.owner = null;
                state.otherWriter = false;
            }
        }

        @Override
        public void writeManifest(byte[] json) {
            synchronized (state) {
                state.manifest = json.clone();
            }
        }

        @Override
        public Optional<byte[]> readManifest() {
            synchronized (state) {
                return state.manifest == null ? Optional.empty() : Optional.of(state.manifest.clone());
            }
        }

        /** Removes the manifest, as a person who deleted {@code manifest.json} from the run folder does. */
        public void removeManifest() {
            synchronized (state) {
                state.manifest = null;
            }
        }

        @Override
        public void writeView(String html) {
            synchronized (state) {
                state.view = html;
            }
        }

        /** The last view written, or empty. */
        public Optional<String> view() {
            synchronized (state) {
                return Optional.ofNullable(state.view);
            }
        }

        @Override
        public void close() {
            synchronized (state) {
                if (state.owner == this) {
                    state.owner = null;
                }
            }
        }

        /** The shared log, writable only through the handle that holds the writer lock. */
        private final class GuardedLog implements EventLog {

            @Override
            public String append(byte[] line) {
                requireLock();
                return state.log.append(line);
            }

            @Override
            public List<byte[]> readAll() {
                return state.log.readAll();
            }

            @Override
            public byte[] tail() {
                return state.log.tail();
            }

            @Override
            public byte[] discardTornTail() {
                requireLock();
                return state.log.discardTornTail();
            }

            private void requireLock() {
                if (!holdsWriterLock()) {
                    throw new IllegalStateException("The writer lock of this run is not held: " + location());
                }
            }
        }
    }

    /** A scratch directory in memory: files and links that a test puts in, listed as the file adapter lists them. */
    public static final class InMemoryScratch implements ScratchDirectory {

        private final java.util.TreeMap<String, byte[]> files = new java.util.TreeMap<>();
        private final java.util.TreeMap<String, Long> modified = new java.util.TreeMap<>();
        private final java.util.TreeMap<String, String> links = new java.util.TreeMap<>();
        private final java.util.TreeMap<String, String> unreadable = new java.util.TreeMap<>();
        private long clock;
        private int reads;

        public synchronized void put(String path, byte[] content) {
            files.put(path, content.clone());
            modified.put(path, ++clock);
        }

        public synchronized void putLink(String path, String target) {
            links.put(path, target);
        }

        /** An entry the listing reports as unreadable. */
        public synchronized void putUnreadable(String path, String reason) {
            unreadable.put(path, reason);
        }

        public synchronized void remove(String path) {
            files.remove(path);
            modified.remove(path);
            links.remove(path);
            unreadable.remove(path);
        }

        /** How many times a file was read with {@link #readScratch}. */
        public synchronized int reads() {
            return reads;
        }

        @Override
        public synchronized ScratchListing list(ScratchListing.Known known) {
            List<ScratchEntry> entries = new ArrayList<>();
            files.forEach((path, bytes) -> {
                long mod = modified.get(path);
                String sha = known.sha256(path, bytes.length, mod);
                entries.add(new ScratchEntry(path, bytes.length, sha != null ? sha
                        : traceworks.core.record.EventChain.sha256Hex(bytes), null, mod, null));
            });
            links.forEach((path, target) -> entries.add(new ScratchEntry(path, 0, null, target)));
            entries.sort(java.util.Comparator.comparing(ScratchEntry::path));
            boolean cut = entries.size() > ScratchEntry.MAX_ENTRIES;
            return new ScratchListing(cut ? entries.subList(0, ScratchEntry.MAX_ENTRIES) : entries,
                    unreadable.entrySet().stream().map(e -> new ScratchListing.Unreadable(e.getKey(), e.getValue()))
                            .toList(), cut);
        }

        @Override
        public synchronized byte[] readScratch(String path, long maxBytes) {
            byte[] bytes = files.get(path);
            if (bytes == null) {
                throw new IllegalArgumentException("Not a regular file in the scratch directory: " + path);
            }
            reads++;
            return java.util.Arrays.copyOf(bytes, (int) Math.min(bytes.length, maxBytes));
        }
    }

    /** An {@link Inbox} in memory; it also keeps every request ever posted, for tests that check what was sent. */
    public static final class InMemoryInbox implements Inbox {

        private final List<InboxRequest> pending = new ArrayList<>();
        private final List<InboxRequest> posted = new ArrayList<>();

        @Override
        public synchronized void post(InboxRequest request) {
            pending.add(request);
            posted.add(request);
            notifyAll();
        }

        /** Every request posted so far, in order, taken or not. */
        public synchronized List<InboxRequest> posted() {
            return List.copyOf(posted);
        }

        /** Waits until {@code count} requests have been posted and returns the last; fails after {@code timeout}. */
        public synchronized InboxRequest awaitPosted(int count, java.time.Duration timeout)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (posted.size() < count) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("only " + posted.size() + " requests posted within " + timeout);
                }
                wait(Math.max(1, left / 1_000_000));
            }
            return posted.get(count - 1);
        }

        @Override
        public synchronized List<InboxRequest> take() {
            List<InboxRequest> taken = List.copyOf(pending);
            pending.clear();
            return taken;
        }
    }
}
