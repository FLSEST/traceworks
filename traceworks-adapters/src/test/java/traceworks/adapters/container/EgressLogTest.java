package traceworks.adapters.container;

import traceworks.core.port.CommandRunner.Connection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the egress proxy printed, as the connections of a command's result. */
class EgressLogTest {

    @Test
    void lines_areCountedByTargetAndVerdict_inTheOrderOfTheirFirstAppearance() {
        String log = "READY\nALLOW api.example:443\nDENY evil.example:443\nALLOW api.example:443\n"
                + "FAIL api.example:443\nDENY ?\nALLOW api.example:443\nDENY evil.example:443\n";

        assertThat(EgressLog.connections(log)).containsExactly(
                new Connection("api.example:443", "ALLOW", 3),
                new Connection("evil.example:443", "DENY", 2),
                new Connection("api.example:443", "FAIL", 1),
                new Connection("?", "DENY", 1));
    }

    @Test
    void ready_isSeenOnlyAsALineOfItsOwn() {
        assertThat(EgressLog.ready("READY\n")).isTrue();
        assertThat(EgressLog.ready("WARNING: x\nREADY\nALLOW a.example:443\n")).isTrue();
        assertThat(EgressLog.ready("")).isFalse();
        assertThat(EgressLog.ready("NOT READY\n")).isFalse();
        assertThat(EgressLog.ready("READ")).isFalse();
    }

    /** Whatever is no line of the proxy is not a connection: nothing of it reaches the record. */
    @Test
    void whatIsNoLineOfTheProxy_isLeftOut() {
        String log = "READY\nWARNING: something of the JVM\nALLOW\nALLOW a.example:443 extra\nallow a.example:443\n"
                + "DENY a b\nDENY \u001b[31mx:1\nGRANT a.example:443\nALLOW a.example:443";

        assertThat(EgressLog.connections(log)).as("the last line broke off: it has no line feed").isEmpty();
        assertThat(EgressLog.connections("")).isEmpty();
        assertThat(EgressLog.connections(null)).isEmpty();
    }

    @Test
    void theLimitOfTheProxy_isAConnectionOfItsOwn() {
        assertThat(EgressLog.connections("READY\nALLOW a.example:443\nLIMIT\n")).containsExactly(
                new Connection("a.example:443", "ALLOW", 1), new Connection("*", "LIMIT", 1));
    }

    /** A container that asks for ever new targets cannot make the record grow without bound. */
    @Test
    void targetsBeyondTheFirstHundred_areCountedTogether() {
        StringBuilder log = new StringBuilder("READY\n");
        for (int i = 0; i < Connection.MAX_TARGETS + 5; i++) {
            log.append("DENY host").append(i).append(".example:443\n");
        }
        log.append("ALLOW late.example:443\nDENY host0.example:443\n");

        List<Connection> connections = EgressLog.connections(log.toString());

        assertThat(connections).hasSize(Connection.MAX_TARGETS + 2);
        assertThat(connections.getFirst()).isEqualTo(new Connection("host0.example:443", "DENY", 2));
        assertThat(connections.subList(Connection.MAX_TARGETS, connections.size())).containsExactly(
                new Connection(Connection.OTHER_TARGETS, "DENY", 5),
                new Connection(Connection.OTHER_TARGETS, "ALLOW", 1));
    }
}
