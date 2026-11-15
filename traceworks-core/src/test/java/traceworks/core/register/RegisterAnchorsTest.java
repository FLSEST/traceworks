package traceworks.core.register;

import traceworks.core.port.RegisterAnchor;
import traceworks.core.record.EventChain;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison of a register's log with the anchors the ledger holds: each anchor must still name its line, and
 * the lines after the last one are unproven.
 */
class RegisterAnchorsTest {

    private static final List<byte[]> LOG = List.of(line(0), line(1), line(2), line(3));

    private static byte[] line(int seq) {
        return ("{\"seq\":" + seq + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static RegisterAnchor anchorOf(int seq) {
        return new RegisterAnchor(seq, EventChain.sha256Hex(line(seq)));
    }

    @Test
    void anAnchorOnTheFirstLine_ofALogOfOneLine_holds() {
        RegisterAnchors.Check check = RegisterAnchors.check(List.of(line(0)), List.of(anchorOf(0)));

        assertThat(check.problems()).isEmpty();
        assertThat(check.summary()).isEqualTo("ok (seq 0 is the head the ledger holds, 1 anchors)");
    }

    @Test
    void anAnchorBeyondTheEnd_namesTheCutAndTheHashItHolds() {
        RegisterAnchor beyond = anchorOf(5);

        assertThat(RegisterAnchors.check(LOG, List.of(anchorOf(1), beyond)).problems()).containsExactly(
                "seq 5: the ledger anchors this line (" + beyond.head().substring(0, 12) + "), but the log ends at"
                        + " seq 3, so lines were cut from its end");
        assertThat(RegisterAnchors.check(List.of(line(0)), List.of(anchorOf(1))).problems()).containsExactly(
                "seq 1: the ledger anchors this line (" + anchorOf(1).head().substring(0, 12) + "), but the log ends at"
                        + " seq 0, so lines were cut from its end");
        assertThat(RegisterAnchors.check(List.of(), List.of(anchorOf(0))).problems()).containsExactly(
                "seq 0: the ledger anchors this line (" + anchorOf(0).head().substring(0, 12) + "), but the log is"
                        + " empty, so lines were cut from its end");
        assertThat(RegisterAnchors.check(LOG, List.of(beyond)).summary()).isEqualTo("NOT ok");
    }

    @Test
    void anAnchorWithoutAHead_isAProblem_notACrash() {
        assertThat(RegisterAnchors.check(LOG, List.of(new RegisterAnchor(2, null))).problems()).containsExactly(
                "seq 2: the line is not the one the ledger anchors (?), so it was changed after it was written, or the"
                        + " log was cut and written on");
    }

    @Test
    void oneLineAfterTheLastAnchor_isNamedInTheSingular() {
        assertThat(RegisterAnchors.check(LOG, List.of(anchorOf(1), anchorOf(2))).summary()).isEqualTo(
                "ok up to seq 2; seq 3 is not anchored, so it is unproven: the ledger could not be written when it"
                        + " was, or it was appended without Traceworks, and nothing tells the two apart. The next"
                        + " write takes it as it is, anchors the head and records which lines it found without an"
                        + " anchor (register.unanchored)");
    }

    @Test
    void severalLinesAfterTheLastAnchor_areNamedInThePlural() {
        assertThat(RegisterAnchors.check(LOG, List.of(anchorOf(1))).summary()).isEqualTo(
                "ok up to seq 1; seq 2 to 3 are not anchored, so they are unproven: the ledger could not be written"
                        + " when they were, or they were appended without Traceworks, and nothing tells the two apart."
                        + " The next write takes them as they are, anchors the head and records which lines it found"
                        + " without an anchor (register.unanchored)");
    }
}
