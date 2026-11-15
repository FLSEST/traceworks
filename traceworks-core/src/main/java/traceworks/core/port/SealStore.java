package traceworks.core.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the seals of a home live: the hash-chained seal log, the operator's allowed signers file, and the seal file
 * each sealed package carries. The log is opened for reading, or for writing under the one lock that serialises its
 * writers of every process.
 */
public interface SealStore {

    /** The seal log, open; closing it releases the writer lock when one was taken. */
    interface Log extends AutoCloseable {
        EventLog events();

        @Override
        void close();
    }

    /** The seal log for reading; it may not exist yet, then it is empty. */
    Log openLog();

    /** The seal log for writing, under its lock, created when it does not exist. */
    Log openLogForWrite();

    /** Where the seal log lives, for a person. */
    String logLocation();

    /** The content of the allowed signers file; empty when there is no such file. */
    Optional<byte[]> allowedSigners();

    /** Where the allowed signers file is, or would be, for a person. */
    String allowedSignersLocation();

    /** Writes the seal file of the package in {@code packageDirectory}, replacing the one it has. */
    void writeSeal(Path packageDirectory, byte[] content);

    /**
     * Removes the seal file of the package in {@code packageDirectory}.
     *
     * @return whether there was one
     */
    boolean removeSeal(Path packageDirectory);
}
