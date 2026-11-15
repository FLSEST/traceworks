package traceworks.core.port;

/**
 * The head of a register's log as the ledger holds it, outside the register's folder: the line with this sequence
 * number had this hash when it was written.
 *
 * @param seq  the sequence number of the last line of the log at the time
 * @param head the SHA-256 of that line, lowercase hex
 */
public record RegisterAnchor(int seq, String head) {
}
