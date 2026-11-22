package traceworks.core.record;

import java.util.List;

/**
 * What the register of an audit workflow handed a run at its start, as {@code run.started} records it: the register,
 * the head of its log the run read, and the files of the run's register folder with their digests
 * (docs/registers.md, "The register folder of a run").
 *
 * @param key   the register
 * @param project the project the register is of; {@code null} in a record of a version before registers had
 *              projects
 * @param seq   the sequence number of the last line of the register's log when the run read it; {@code null} when
 *              the register did not exist yet
 * @param head  the SHA-256 of that line; {@code null} when the register did not exist yet
 * @param files the files of the folder, in the order they were written
 */
public record RegisterHandover(String key, String project, Integer seq, String head, List<File> files) {

    public RegisterHandover {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /**
     * A file of the folder.
     *
     * @param sha256 the SHA-256 of its content
     * @param size   its size in bytes
     */
    public record File(String name, String sha256, long size) {
    }

    /** Where the register's log stood: {@code at seq 17}, or {@code before its first line} for no register yet. */
    public String position() {
        return seq == null ? "before its first line" : "at seq " + seq;
    }

    /** The register by its key and its project: {@code hello of shop}; the key alone where the record names none. */
    public String name() {
        return project == null ? key : key + " of " + project;
    }

    /**
     * One line for a person: {@code hello of shop at seq 17 (head 3fa9c2d1e07b), 4 files handed to the steps}.
     */
    public String summary() {
        return name() + " " + position() + (head == null ? "" : " (head " + (head.length() > 12 ? head.substring(0, 12)
                : head) + ")") + ", " + files.size() + (files.size() == 1 ? " file" : " files")
                + " handed to the steps";
    }
}
