package traceworks.core.port;

import java.util.List;
import java.util.Optional;

/**
 * Where registers live: one per key and project, with a hash-chained log, blobs, a derived {@code state.json} and a
 * derived {@code report.html}. The key is the audit workflow's, the project the operator's word for what a run
 * works on: one audit package run on two projects feeds two registers. A register is opened for reading, or for
 * writing under the one lock per register that serialises the writers of every process.
 */
public interface RegisterStore {

    /** One register, open; closing it releases the writer lock when one was taken. */
    interface Opened extends AutoCloseable {
        String key();

        /**
         * The project the register is of; {@code null} for a register from before registers had projects
         * ({@link RegisterStore#openUnassignedForWrite}) until it is assigned to one ({@link #assignTo}).
         */
        String project();

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

        /**
         * Whether the place this register would be moved to as the register of {@code project} is taken: a
         * register lies there, or anything else. Asked before anything is written for an assignment, so that a
         * refused one changes nothing. Only for a register from before registers had projects.
         */
        default boolean placeTaken(String project) {
            throw new UnsupportedOperationException("only a register from before registers had projects is assigned");
        }

        /**
         * Moves this register from before registers had projects to its place as the register of its key and
         * {@code project}. From then on this object is that register: {@link #project} names the project,
         * {@link #location} the new place, and the lock of that register is held until this object is closed. A
         * move that was cut off is finished by this call on the register opened again.
         *
         * @throws IllegalArgumentException if {@code project} is no project key
         * @throws IllegalStateException    if the place is taken; nothing is moved then
         */
        default void assignTo(String project) {
            throw new UnsupportedOperationException("only a register from before registers had projects is assigned");
        }

        @Override
        void close();
    }

    /**
     * The register of {@code key} and {@code project} for reading; it may not exist yet, then its log is empty.
     *
     * @throws IllegalArgumentException if the key is no register key or the project no project key
     */
    Opened open(String key, String project);

    /**
     * The register of {@code key} and {@code project} for writing, under its lock, created when it does not exist.
     *
     * @throws IllegalArgumentException if the key is no register key or the project no project key
     */
    Opened openForWrite(String key, String project);

    /** The keys of the registers that exist, sorted. */
    List<String> keys();

    /**
     * The projects of {@code key}: a project is its place in the store (for files, its folder), with or without a
     * log. So a project whose log is gone still counts, and "the only project the key has" never becomes another
     * one without a word. Sorted; empty for none.
     */
    List<String> projects(String key);

    /**
     * Whether a register from before registers had projects lies at {@code key}: a version that kept one register
     * per key left its log in the folder of the key itself. It is the register of no project until it is assigned
     * to one ({@link #openUnassignedForWrite}, {@link Opened#assignTo}); nothing assigns it by itself. Also true
     * while an assignment that was begun is not finished.
     */
    boolean unassigned(String key);

    /**
     * Removes what an assignment that moved no log left at {@code key}: the place where a register from before is
     * gathered before its move (for files, the folder {@code .assigning}), while it holds no log that has begun.
     * Such a place is nothing to a reader ({@link #unassigned}); the next assignment on the key clears it. Only
     * what an assignment leaves there and that holds nothing is removed: its lock and a log without a byte. Anything
     * else in it stays, and the place with it. Nothing happens where there is no such place.
     */
    default void clearStaging(String key) {
    }

    /**
     * The register from before registers had projects of {@code key}, for writing: to assign it to a project, and
     * for nothing else. Its {@link Opened#project} is {@code null}. The writer holds the lock of the assignment,
     * which is one per key, and the register's own lock, which a writer of the version that left it takes too.
     * Closing it without an assignment leaves the place as it was.
     *
     * @throws IllegalStateException if no such register lies at the key (any more)
     */
    Opened openUnassignedForWrite(String key);

    /**
     * The register from before registers had projects of {@code key}, for reading: to verify it before it is
     * assigned, so that something says what is wrong with one that cannot be. It takes no lock and writes nothing;
     * its {@link Opened#project} is {@code null}.
     *
     * @throws IllegalStateException if no such register lies at the key
     */
    Opened openUnassigned(String key);
}
