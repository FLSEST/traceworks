package traceworks.adapters.host;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JVM passes a process's arguments and environment in the encoding of its locale
 * ({@code sun.jnu.encoding}); without UTF-8, a character beyond ASCII would reach the process changed, so such a
 * command is refused. The encoding is a parameter here, since a running JVM cannot change its own.
 */
class ProcessEncodingTest {

    private static final String ASCII = "ANSI_X3.4-1968";
    private static final List<String> COMMAND = List.of("docker", "run", "--mount", "type=bind,src=/work,dst=/w");

    @Test
    void underUtf8_everythingPasses() {
        for (String utf8 : List.of("UTF-8", "UTF8", "utf-8")) {
            assertThat(ProcessEncoding.refusal(List.of("docker", "--mount", "src=/work/caf\u00e9"),
                    Map.of("TOKEN", "caf\u00e9-\ud83d\ude00-value"), utf8)).as(utf8).isEmpty();
        }
    }

    @Test
    void withoutUtf8_asciiPasses() {
        assertThat(ProcessEncoding.refusal(COMMAND, Map.of("TOKEN", "plain-value-1", "HOME", "/home/op"), ASCII))
                .isEmpty();
    }

    @Test
    void withoutUtf8_aCharacterBeyondAsciiOnTheCommandLine_isRefused_withoutShowingIt() {
        assertThat(ProcessEncoding.refusal(List.of("docker", "--mount", "type=bind,src=/work/caf\u00e9,dst=/w"),
                Map.of(), ASCII)).hasValue("The command cannot start: its command line holds a character beyond"
                + " ASCII, which the JVM's encoding ANSI_X3.4-1968 cannot pass on unchanged; run Traceworks with a"
                + " UTF-8 locale, for example LANG=C.UTF-8");
    }

    @Test
    void withoutUtf8_aCharacterBeyondAsciiInTheEnvironment_isRefused_namingTheVariableNotItsValue() {
        String refusal = ProcessEncoding.refusal(COMMAND, Map.of("HOME", "/home/op", "TOKEN", "caf\u00e9-value-1"),
                "ISO-8859-1").orElseThrow();

        assertThat(refusal).isEqualTo("The command cannot start: the environment variable TOKEN holds a character"
                + " beyond ASCII, which the JVM's encoding ISO-8859-1 cannot pass on unchanged; run Traceworks with"
                + " a UTF-8 locale, for example LANG=C.UTF-8").doesNotContain("caf\u00e9");
    }

    @Test
    void anUnknownEncoding_isNoUtf8() {
        assertThat(ProcessEncoding.refusal(COMMAND, Map.of("TOKEN", "\u00e9"), null)).hasValueSatisfying(refusal ->
                assertThat(refusal).contains("the JVM's encoding (unknown)"));
        assertThat(ProcessEncoding.refusal(COMMAND, Map.of("TOKEN", "\u00e9"), "no such charset!")).isPresent();
    }

    @Test
    void theJvmsEncoding_isItsJnuEncoding() {
        assertThat(ProcessEncoding.ofTheJvm()).isEqualTo(System.getProperty("sun.jnu.encoding"));
    }
}
