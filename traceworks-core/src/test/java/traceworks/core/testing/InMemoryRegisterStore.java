package traceworks.core.testing;

import traceworks.core.port.BlobStore;
import traceworks.core.port.EventLog;
import traceworks.core.port.RegisterStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/** Registers in memory: one log, blob store, state and view per key, with a lock per key for the writer. */
public final class InMemoryRegisterStore implements RegisterStore {

    private final Map<String, Register> registers = new TreeMap<>();
    private RuntimeException writeFailure;

    /** Every write from now on throws {@code failure}, as a full disk would. */
    public synchronized void failWrites(RuntimeException failure) {
        this.writeFailure = failure;
    }

    private static final class Register {
        final InMemoryEventLog events = new InMemoryEventLog();
        final InMemoryBlobStore blobs = new InMemoryBlobStore();
        final ReentrantLock lock = new ReentrantLock();
        byte[] state;
        byte[] view;
    }

    private synchronized Register register(String key, boolean create) {
        Register register = registers.get(key);
        if (register == null && create) {
            register = new Register();
            registers.put(key, register);
        }
        return register;
    }

    @Override
    public Opened open(String key) {
        Register register = register(key, false);
        return opened(key, register == null ? new Register() : register, false);
    }

    @Override
    public Opened openForWrite(String key) {
        Register register = register(key, true);
        register.lock.lock();
        return opened(key, register, true);
    }

    @Override
    public synchronized List<String> keys() {
        return new ArrayList<>(registers.keySet());
    }

    /** The state file of {@code key} as written, for a test that edits it. */
    public synchronized byte[] stateFile(String key) {
        Register register = registers.get(key);
        return register == null ? null : register.state;
    }

    /** The report.html of {@code key} as written; {@code null} when none was. */
    public synchronized byte[] viewFile(String key) {
        Register register = registers.get(key);
        return register == null ? null : register.view;
    }

    /** The blobs of {@code key}, for a test that edits one in place. */
    public synchronized InMemoryBlobStore blobs(String key) {
        return register(key, true).blobs;
    }

    /** Leaves a torn tail in the log of {@code key}, as a writer killed mid-line would. */
    public synchronized void tearTail(String key, byte[] tail) {
        register(key, true).events.tearTail(tail);
    }

    /** The log of {@code key}, for a test that cuts its end or edits its last line. */
    public synchronized InMemoryEventLog log(String key) {
        return register(key, true).events;
    }

    /** Overwrites the state file of {@code key}, as an editor would. */
    public synchronized void editState(String key, byte[] content) {
        register(key, true).state = content;
    }

    private Opened opened(String key, Register register, boolean locked) {
        return new Opened() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public EventLog events() {
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
                return "memory:" + key;
            }

            @Override
            public Optional<byte[]> state() {
                return Optional.ofNullable(register.state);
            }

            @Override
            public void writeState(byte[] json) {
                requireLock();
                register.state = json.clone();
            }

            @Override
            public void writeView(byte[] html) {
                requireLock();
                register.view = html.clone();
            }

            private void requireLock() {
                if (!locked) {
                    throw new IllegalStateException("the register " + key + " was opened for reading");
                }
            }

            @Override
            public void close() {
                if (locked) {
                    register.lock.unlock();
                }
            }
        };
    }
}
