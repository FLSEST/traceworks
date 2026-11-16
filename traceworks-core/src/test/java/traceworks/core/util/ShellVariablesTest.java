package traceworks.core.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text of the lines that set shell variables: what they are made of. That bash sets the variables from them,
 * byte for byte, is shown with a real bash where a real runner is (the host runner's tests, and the engine's).
 */
class ShellVariablesTest {

    @Test
    void noValues_noLines() {
        assertThat(ShellVariables.preamble(Map.of())).isEmpty();
        assertThat(ShellVariables.preamble(null)).isEmpty();
    }

    /** One line that defines the setter, one line per value with its length in bytes, one that removes the setter. */
    @Test
    void preamble_setsEachVariableFromItsEncodedValue_withItsLengthInBytes() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("REVIEW", "it's $(not run)");
        values.put("EMPTY", "");
        values.put("EURO", "5 €\n");

        String marker = ShellVariables.failureMarker(values);
        assertThat(marker).matches("traceworks:[0-9a-f]{32}:");
        assertThat(ShellVariables.preamble(values)).isEqualTo(
                "case \"${BASH_VERSINFO[0]:-0}.${BASH_VERSINFO[1]:-0}\" in [012].*|3.0) echo \"" + marker
                        + " this bash is older than 3.1, which a step with values needs\" >&2; exit 74;; esac\n"
                        + "TRACEWORKS_VALUE() { local LC_ALL=C TRACEWORKS_V TRACEWORKS_A;"
                        + " printf -v TRACEWORKS_V '%b' \"$3\""
                        + " && [[ ${#TRACEWORKS_V} -eq $2 ]] && unset -v \"$1\""
                        + " && printf -v \"$1\" '%s' \"$TRACEWORKS_V\" && export -n \"$1\""
                        + " && [[ ${!1} == \"$TRACEWORKS_V\" ]]"
                        + " && { trap '' PIPE; declare -p \"$1\" 2>/dev/null; :; }"
                        + " | { IFS= read -r -n $((12 + ${#1})) TRACEWORKS_A;"
                        + " [[ $TRACEWORKS_A == \"declare -- $1=\" ]]; }"
                        + " || { echo \"" + marker + " the value of $1 was not delivered\" >&2; exit 74; }; }\n"
                        + "TRACEWORKS_VALUE REVIEW 15 'it\\x27s\\x20\\x24\\x28not\\x20run\\x29'\n"
                        + "TRACEWORKS_VALUE EMPTY 0 ''\n"
                        + "TRACEWORKS_VALUE EURO 6 '5\\x20\\xe2\\x82\\xac\\x0a'\n"
                        + "unset -f TRACEWORKS_VALUE\n");
    }

    /**
     * The marker of a failed delivery is the digest of the lines that carry the values, so it differs with every
     * value, and a failure is one only with the exit code and the marker of these values at the start of a line.
     */
    @Test
    void aFailedDelivery_isKnownByItsExitCodeAndTheMarkerOfItsValues() {
        Map<String, String> values = Map.of("REVIEW", "one");
        String marker = ShellVariables.failureMarker(values);

        assertThat(ShellVariables.failureMarker(Map.of("REVIEW", "two"))).isNotEqualTo(marker);
        assertThat(ShellVariables.failureMarker(Map.of("OTHER", "one"))).isNotEqualTo(marker);
        assertThat(ShellVariables.failureMarker(Map.of())).isNull();
        assertThat(ShellVariables.notDelivered(values, 74, marker + " the value of REVIEW was not delivered\n")).isTrue();
        assertThat(ShellVariables.notDelivered(values, 74, "a warning of the runtime\n" + marker + " …")).isTrue();
        assertThat(ShellVariables.notDelivered(values, 1, marker + " …")).as("another exit code").isFalse();
        assertThat(ShellVariables.notDelivered(values, 74, "said: " + marker)).as("not at the start of a line").isFalse();
        assertThat(ShellVariables.notDelivered(values, 74, ShellVariables.failureMarker(Map.of("REVIEW", "two"))))
                .as("the marker of other values").isFalse();
        assertThat(ShellVariables.notDelivered(values, 74, "traceworks: the value of REVIEW was not delivered"))
                .isFalse();
        assertThat(ShellVariables.notDelivered(Map.of(), 74, "traceworks:")).isFalse();
    }

    /**
     * Whatever a value holds, its place in the text holds letters, digits and {@code \xHH} only: every byte value,
     * every character that means something to a shell or to {@code printf}.
     */
    @Test
    void theEncodedValue_holdsNothingAShellsParserReads() {
        StringBuilder every = new StringBuilder();
        for (int c = 1; c < 0x3000; c++) {
            every.append((char) c);
        }
        every.append("😀 '\"`$\\!#&|;<>(){}[]*?~%\n\r\t");

        String preamble = ShellVariables.preamble(Map.of("V", every.toString()));

        String line = preamble.lines().filter(l -> l.startsWith("TRACEWORKS_VALUE V ")).findFirst().orElseThrow();
        assertThat(line.replaceAll("\\\\x[0-9a-f]{2}", "")).as("what is left without the \\\\xHH")
                .matches("TRACEWORKS_VALUE V \\d+ '[A-Za-z0-9]*'");
        assertThat(line).startsWith("TRACEWORKS_VALUE V " + every.toString().getBytes(StandardCharsets.UTF_8).length
                + " '");
        assertThat(preamble.lines()).hasSize(4);
    }

    /**
     * The first line checks the bash: a {@code case} over two words of plain parameter expansion, which every bash
     * reads (no {@code [[ ]]}, no arithmetic, no expansion that came later). Nothing stands before it.
     */
    @Test
    void theFirstLine_isTheVersionCheck_inSyntaxEveryBashReads() {
        String first = ShellVariables.preamble(Map.of("V", "x")).lines().findFirst().orElseThrow();

        assertThat(first).matches("case \"\\$\\{BASH_VERSINFO\\[0]:-0}\\.\\$\\{BASH_VERSINFO\\[1]:-0}\" in \\[012]\\.\\*\\|3\\.0\\)"
                + " echo \"traceworks:[0-9a-f]{32}: this bash is older than 3\\.1, which a step with values needs\""
                + " >&2; exit 74;; esac");
        assertThat(first).doesNotContain("[[").doesNotContain("((").doesNotContain("@").doesNotContain("$'");
        assertThat(ShellVariables.preamble(Map.of("V", "x"))).as("no process substitution, which POSIX mode refuses")
                .doesNotContain("<(").doesNotContain(">(");
    }

    /** A NUL is taken out before the value is encoded, so the length sent is the length that can arrive. */
    @Test
    void aNul_isTakenOut_beforeTheValueIsSent() {
        assertThat(ShellVariables.withoutNul("a\u0000b\u0000")).isEqualTo("ab");
        assertThat(ShellVariables.withoutNul(null)).isEmpty();
        assertThat(ShellVariables.preamble(Map.of("V", "TRACEWORKS_VALUE_1_EN\u0000D\u0000")))
                .contains("TRACEWORKS_VALUE V 22 'TRACEWORKS\\x5fVALUE\\x5f1\\x5fEND'\n").doesNotContain("\u0000")
                .doesNotContain("\\x00");
    }
}
