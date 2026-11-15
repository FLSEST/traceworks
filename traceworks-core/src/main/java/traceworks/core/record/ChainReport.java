package traceworks.core.record;

import java.util.List;

/**
 * The result of verifying a run record.
 *
 * @param intact       whether there are no problems; deleted blobs do not count
 * @param events       how many lines were read
 * @param head         the hash of the last line (the genesis for an empty log)
 * @param problems     what is wrong, in log order, each naming the seq
 * @param deletedBlobs the SHA-256 of the blobs the record refers to that are no longer stored
 */
public record ChainReport(boolean intact, int events, String head, List<String> problems,
                          List<String> deletedBlobs) {

    public ChainReport {
        problems = List.copyOf(problems);
        deletedBlobs = List.copyOf(deletedBlobs);
    }
}
