package traceworks.core.port;

import java.util.List;
import java.util.Optional;

/**
 * Where registers live: one per key, with a hash-chained log, blobs, a derived {@code state.json} and a derived
 * {@code report.html}. A register is opened for reading, or for writing under the one lock per register that
 * serialises the writers of every process.
 */
public interface RegisterStore {

    /** One register, open; closing it releases the writer lock when one was taken. */
    interface Opened extends AutoCloseable {
        String key();

        EventLog events();

        BlobStore blobs();

        /** Where the register lives, for a person: a directory, or a name for a store without files. */
        String location();

        /** The {@code state.json} as last written; empty when none was. */
        Optional<byte[]> state();

        /** Writes {@code state.json} atomically; only when opened for writing. */
        void writeState(byte[] json);

        /** Writes {@code report.html} atomically; only when opened for writing. */
        void writeView(byte[] html);

        @Override
        void close();
    }

    /** The register {@code key} for reading; it may not exist yet, then its log is empty. */
    Opened open(String key);

    /** The register {@code key} for writing, under its lock, created when it does not exist. */
    Opened openForWrite(String key);

    /** The keys of the registers that exist, sorted. */
    List<String> keys();
}
