package traceworks.core.testing;

import traceworks.core.model.AuditConfig;
import traceworks.core.model.ProjectKey;
import traceworks.core.port.BlobStore;
import traceworks.core.port.EventLog;
import traceworks.core.port.RegisterStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registers in memory: one log, blob store, state and view per key and project, with a lock per register for the
 * writer. A test that names a key alone means the register of the only project the key has.
 */
public final class InMemoryRegisterStore implements RegisterStore {

    private final Map<String, Map<String, Register>> registers = new TreeMap<>();
    /** The registers from before registers had projects, by key: what an earlier version left at the key itself. */
    private final Map<String, Register> fromBefore = new TreeMap<>();
    private RuntimeException writeFailure;
    private RuntimeException stateFailure;
    private RuntimeException moveFailure;

    /** Every write of a state file from now on throws {@code failure}; {@code null} ends that. */
    public synchronized void failStateWrites(RuntimeException failure) {
        this.stateFailure = failure;
    }

    private RuntimeException closeFailure;

    /**
     * Every close of a register that was opened for writing from now on throws {@code failure}, after it let go of
     * the lock, as a file that cannot be synced would; {@code null} ends that.
     */
    public synchronized void failCloses(RuntimeException failure) {
        this.closeFailure = failure;
    }

    /** Every move of a register to its project from now on throws {@code failure}; {@code null} ends that. */
    public synchronized void failMoves(RuntimeException failure) {
        this.moveFailure = failure;
    }

    /**
     * Puts a register from before registers had projects at {@code key}: its lines, its blobs and its state file,
     * as an earlier version left them.
     */
    public synchronized void putFromBefore(String key, List<byte[]> lines, Map<String, byte[]> blobs, byte[] state) {
        Register register = new Register();
        lines.forEach(register.events::append);
        blobs.forEach(register.blobs::putRaw);
        register.state = state == null ? null : state.clone();
        fromBefore.put(key, register);
    }

    /** The log of the register from before registers had projects at {@code key}. */
    public synchronized InMemoryEventLog logFromBefore(String key) {
        return fromBefore.get(key).events;
    }

    /** Every write from now on throws {@code failure}, as a full disk would. */
    public synchronized void failWrites(RuntimeException failure) {
        this.writeFailure = failure;
    }

    /**
     * Every read of the log of {@code key} and {@code project} from now on throws {@code failure}, as a file that
     * cannot be opened would; {@code null} ends that.
     */
    public synchronized void failReads(String key, String project, RuntimeException failure) {
        register(key, project, true).readFailure = failure;
    }

    private static final class Register {
        RuntimeException readFailure;
        final InMemoryEventLog events = new InMemoryEventLog();
        final InMemoryBlobStore blobs = new InMemoryBlobStore();
        final ReentrantLock lock = new ReentrantLock();
        byte[] state;
        byte[] view;
    }

    private synchronized Register register(String key, String project, boolean create) {
        if (!key.matches(AuditConfig.KEY)) {
            throw new IllegalArgumentException("'" + key + "' is no register key: it must match " + AuditConfig.KEY);
        }
        ProjectKey.checked(project);
        Map<String, Register> ofKey = registers.get(key);
        if (ofKey == null && create) {
            ofKey = new TreeMap<>();
            registers.put(key, ofKey);
        }
        Register register = ofKey == null ? null : ofKey.get(project);
        if (register == null && create) {
            register = new Register();
            ofKey.put(project, register);
        }
        return register;
    }

    /** The register of the only project {@code key} has. */
    private synchronized Register only(String key) {
        Map<String, Register> ofKey = registers.get(key);
        if (ofKey == null || ofKey.isEmpty()) {
            throw new IllegalStateException("the key " + key + " has no register yet: name its project");
        }
        if (ofKey.size() > 1) {
            throw new IllegalStateException("the key " + key + " has the projects " + ofKey.keySet()
                    + ": name one of them");
        }
        return ofKey.values().iterator().next();
    }

    @Override
    public Opened open(String key, String project) {
        Register register = register(key, project, false);
        return opened(key, project, register == null ? new Register() : register, false);
    }

    @Override
    public Opened openForWrite(String key, String project) {
        Register register = register(key, project, true);
        register.lock.lock();
        return opened(key, project, register, true);
    }

    private RuntimeException keysFailure;
    private final Map<String, RuntimeException> projectsFailures = new TreeMap<>();

    /** Every listing of the keys from now on throws {@code failure}, as a folder that cannot be listed would. */
    public synchronized void failKeys(RuntimeException failure) {
        this.keysFailure = failure;
    }

    /** Every listing of what lies at {@code key} from now on throws {@code failure}; {@code null} ends that. */
    public synchronized void failProjects(String key, RuntimeException failure) {
        if (failure == null) {
            projectsFailures.remove(key);
        } else {
            projectsFailures.put(key, failure);
        }
    }

    @Override
    public synchronized List<String> keys() {
        if (keysFailure != null) {
            throw keysFailure;
        }
        java.util.Set<String> keys = new java.util.TreeSet<>(registers.keySet());
        keys.addAll(fromBefore.keySet());
        return new ArrayList<>(keys);
    }

    @Override
    public synchronized boolean unassigned(String key) {
        if (projectsFailures.containsKey(key)) {
            throw projectsFailures.get(key);
        }
        return fromBefore.containsKey(key);
    }

    @Override
    public Opened openUnassignedForWrite(String key) {
        Register register;
        synchronized (this) {
            register = fromBefore.get(key);
        }
        if (register == null) {
            throw new IllegalStateException("no register from before registers had projects lies at " + key);
        }
        register.lock.lock();
        synchronized (this) {
            if (fromBefore.get(key) != register) {
                register.lock.unlock();
                throw new IllegalStateException("no register from before registers had projects lies at " + key);
            }
        }
        return opened(key, null, register, true);
    }

    @Override
    public Opened openUnassigned(String key) {
        Register register;
        synchronized (this) {
            register = fromBefore.get(key);
        }
        if (register == null) {
            throw new IllegalStateException("no register from before registers had projects lies at " + key);
        }
        return opened(key, null, register, false);
    }

    /** The blobs of the register from before registers had projects at {@code key}, for a test that removes one. */
    public synchronized InMemoryBlobStore blobsFromBefore(String key) {
        return fromBefore.get(key).blobs;
    }

    /** The state file of the register from before registers had projects at {@code key}, as it is now. */
    public synchronized byte[] stateFromBefore(String key) {
        return fromBefore.get(key).state;
    }

    private synchronized boolean taken(String key, String project) {
        return registers.getOrDefault(key, Map.of()).containsKey(project);
    }

    private synchronized void move(String key, String project, Register register) {
        ProjectKey.checked(project);
        if (moveFailure != null) {
            throw moveFailure;
        }
        if (taken(key, project)) {
            throw new IllegalStateException("the place of the register " + key + " of " + project + " is taken");
        }
        fromBefore.remove(key);
        registers.computeIfAbsent(key, k -> new TreeMap<>()).put(project, register);
    }

    /** A project is its register here, with or without a line in its log: what a folder is to the file store. */
    @Override
    public synchronized List<String> projects(String key) {
        if (projectsFailures.containsKey(key)) {
            throw projectsFailures.get(key);
        }
        return new ArrayList<>(registers.getOrDefault(key, Map.of()).keySet());
    }

    /**
     * Makes the register of {@code key} and {@code to} a copy of that of {@code from}: its log, its blobs and its
     * state, as a folder copied by hand would be.
     */
    public synchronized void copy(String key, String from, String to) {
        Register source = register(key, from, true);
        Register target = register(key, to, true);
        source.events.readAll().forEach(target.events::append);
        source.blobs.all().forEach(target.blobs::put);
        target.state = source.state == null ? null : source.state.clone();
        target.view = source.view == null ? null : source.view.clone();
    }

    /** Removes the register of {@code key} and {@code project}, as a folder removed by hand would be gone. */
    public synchronized void remove(String key, String project) {
        registers.getOrDefault(key, new TreeMap<>()).remove(project);
    }

    /** The state file of {@code key} as written, for a test that edits it. */
    public synchronized byte[] stateFile(String key) {
        return registers.containsKey(key) ? only(key).state : null;
    }

    /** The state file of {@code key} and {@code project} as written; {@code null} when none was. */
    public synchronized byte[] stateFile(String key, String project) {
        Register register = register(key, project, false);
        return register == null ? null : register.state;
    }

    /** The report.html of {@code key} as written; {@code null} when none was. */
    public synchronized byte[] viewFile(String key) {
        return registers.containsKey(key) ? only(key).view : null;
    }

    /** The blobs of {@code key}, for a test that edits one in place. */
    public synchronized InMemoryBlobStore blobs(String key) {
        return only(key).blobs;
    }

    /** Leaves a torn tail in the log of {@code key}, as a writer killed mid-line would. */
    public synchronized void tearTail(String key, byte[] tail) {
        only(key).events.tearTail(tail);
    }

    /** The log of {@code key}, for a test that cuts its end or edits its last line. */
    public synchronized InMemoryEventLog log(String key) {
        return only(key).events;
    }

    /** The log of {@code key} and {@code project}. */
    public synchronized InMemoryEventLog log(String key, String project) {
        return register(key, project, true).events;
    }

    /** Overwrites the state file of {@code key}, as an editor would. */
    public synchronized void editState(String key, byte[] content) {
        only(key).state = content;
    }

    private Opened opened(String key, String openedFor, Register register, boolean locked) {
        return new Opened() {
            private String project = openedFor;

            @Override
            public String key() {
                return key;
            }

            @Override
            public String project() {
                return project;
            }

            @Override
            public boolean placeTaken(String target) {
                requireFromBefore();
                return taken(key, target);
            }

            @Override
            public void assignTo(String target) {
                requireFromBefore();
                move(key, target, register);
                project = target;
            }

            private void requireFromBefore() {
                if (project != null) {
                    throw new UnsupportedOperationException("only a register from before registers had projects is"
                            + " assigned");
                }
            }

            @Override
            public EventLog events() {
                if (register.readFailure != null) {
                    return new EventLog() {
                        @Override
                        public String append(byte[] line) {
                            return register.events.append(line);
                        }

                        @Override
                        public List<byte[]> readAll() {
                            throw register.readFailure;
                        }

                        @Override
                        public byte[] tail() {
                            throw register.readFailure;
                        }

                        @Override
                        public byte[] discardTornTail() {
                            throw register.readFailure;
                        }
                    };
                }
                if (locked && writeFailure != null) {
                    return new EventLog() {
                        @Override
                        public String append(byte[] line) {
                            throw writeFailure;
                        }

                        @Override
                        public List<byte[]> readAll() {
                            return register.events.readAll();
                        }

                        @Override
                        public byte[] tail() {
                            return register.events.tail();
                        }

                        @Override
                        public byte[] discardTornTail() {
                            return register.events.discardTornTail();
                        }
                    };
                }
                return register.events;
            }

            @Override
            public BlobStore blobs() {
                return register.blobs;
            }

            @Override
            public String location() {
                return "memory:" + key + (project == null ? "" : "/" + project);
            }

            @Override
            public Optional<byte[]> state() {
                return Optional.ofNullable(register.state);
            }

            @Override
            public void writeState(byte[] json) {
                requireLock();
                if (stateFailure != null) {
                    throw stateFailure;
                }
                register.state = json.clone();
            }

            @Override
            public void writeView(byte[] html) {
                requireLock();
                register.view = html.clone();
            }

            private void requireLock() {
                if (!locked) {
                    throw new IllegalStateException("the register " + key + " of " + project
                            + " was opened for reading");
                }
            }

            @Override
            public void close() {
                if (locked) {
                    register.lock.unlock();
                    RuntimeException failure;
                    synchronized (InMemoryRegisterStore.this) {
                        failure = closeFailure;
                    }
                    if (failure != null) {
                        throw failure;
                    }
                }
            }
        };
    }
}
