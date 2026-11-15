package traceworks.adapters.ai;

import traceworks.core.port.AiCli.AiInvocation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A system prompt never goes on a command line. It travels base64-encoded in
 * {@code TRACEWORKS_SYSTEM_PROMPT}, and a constant {@code sh -c} script decodes it into a file in the container's own
 * {@code /tmp} before it starts the CLI.
 */
class SystemPromptLaunchTest {

    private static final List<String> CLI = List.of("claude", "-p");
    private static final Map<String, String> ENVIRONMENT = Map.of("DISABLE_AUTOUPDATER", "1");

    @Test
    void withoutASystemPrompt_theCliStartsAsItIs() {
        for (String none : new String[] {null, "", "  \n"}) {
            SystemPromptLaunch launch = SystemPromptLaunch.of(none);

            assertThat(launch.option("--system-prompt-file")).isEmpty();
            AiInvocation invocation = launch.invocation(CLI, ENVIRONMENT, "the prompt");
            assertThat(invocation.args()).containsExactly("claude", "-p");
            assertThat(invocation.environment()).isEqualTo(ENVIRONMENT);
            assertThat(invocation.stdin()).isEqualTo("the prompt");
        }
    }

    @Test
    void aSystemPrompt_travelsInTheVariable_andTheScriptStartsTheCli() {
        SystemPromptLaunch launch = SystemPromptLaunch.of("Be brief.\nAnswer in English.");

        assertThat(launch.option("--append-system-prompt-file"))
                .containsExactly("--append-system-prompt-file", "/tmp/traceworks-system-prompt");
        AiInvocation invocation = launch.invocation(CLI, ENVIRONMENT, "the prompt");
        assertThat(invocation.args()).containsExactly("sh", "-c", SystemPromptLaunch.SCRIPT, "sh", "claude", "-p");
        assertThat(invocation.environment()).containsExactlyInAnyOrderEntriesOf(Map.of("DISABLE_AUTOUPDATER", "1",
                "TRACEWORKS_SYSTEM_PROMPT", "QmUgYnJpZWYuCkFuc3dlciBpbiBFbmdsaXNoLg=="));
        assertThat(invocation.stdin()).isEqualTo("the prompt");
    }

    /**
     * The variable is base64, so it is ASCII whatever the prompt holds and reaches the container byte for
     * byte, whatever encoding the JVM hands process environments in; a NUL character travels too.
     */
    @Test
    void theVariable_isAscii_andDecodesToThePromptByteForByte() {
        String prompt = "Quote 'this', \"that\", $(not run) `nor this`"
                + ", 100%s \\ \u00e9 \u00f8 \ud83d\ude00 \u0000 end\n\n";

        String value = SystemPromptLaunch.of(prompt).invocation(CLI, Map.of(), null).environment()
                .get("TRACEWORKS_SYSTEM_PROMPT");

        assertThat(value).matches("[A-Za-z0-9+/=]+");
        assertThat(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).isEqualTo(prompt);
    }

    /**
     * The script is one constant: in a subshell with umask 077, so the CLI keeps the default umask, it decodes the
     * variable into a private file (mode 600); then it drops the variable and starts the CLI with its arguments. It
     * prints nothing, and the value reaches {@code base64} on stdin, through the shell's builtin {@code printf}.
     */
    @Test
    void theScript_isAConstant_thatWritesTheFileWithMode600_andStartsTheCli() {
        assertThat(SystemPromptLaunch.SCRIPT).isEqualTo("(umask 077 && printf '%s' \"$TRACEWORKS_SYSTEM_PROMPT\""
                + " | base64 -d > /tmp/traceworks-system-prompt) && unset TRACEWORKS_SYSTEM_PROMPT && exec \"$@\"");
        assertThat(SystemPromptLaunch.of("one").invocation(CLI, Map.of(), null).args())
                .isEqualTo(SystemPromptLaunch.of("another\nprompt").invocation(CLI, Map.of(), null).args());
    }

    /**
     * Linux limits one environment string ({@code NAME=value} and its NUL) to 131 072 bytes. Base64 makes 4 characters
     * of 3 bytes, so a system prompt may take 95 KiB in UTF-8: 129 708 characters in the variable.
     */
    @Test
    void aSystemPromptOver95KiB_isRefused_beforeAnythingRuns() {
        String limit = "a".repeat(95 * 1024);
        String twoByteChars = "\u00e9".repeat(48 * 1024);

        String value = SystemPromptLaunch.of(limit).invocation(CLI, Map.of(), null).environment()
                .get("TRACEWORKS_SYSTEM_PROMPT");
        assertThat(("TRACEWORKS_SYSTEM_PROMPT=" + value).length() + 1).as("one environment string, with its NUL")
                .isLessThanOrEqualTo(131_072);
        assertThatThrownBy(() -> SystemPromptLaunch.of(limit + "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The system prompt is 97281 bytes; at most 97280 (95 KiB) reach the AI CLI, which gets it"
                        + " base64-encoded in one environment variable; shorten the system prompt");
        assertThatThrownBy(() -> SystemPromptLaunch.of(twoByteChars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The system prompt is 98304 bytes;");
    }
}
