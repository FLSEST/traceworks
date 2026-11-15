package traceworks.adapters.ai;

import traceworks.core.port.AiCli.AiInvocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How an AI CLI gets its system prompt without a command line. A prompt can hold a secret value from an
 * earlier step's output, and every process on the machine can read a command line, so the system
 * prompt travels in the environment variable {@value #VARIABLE}: the container runtime names it on the client's
 * command line and passes its value through the client's environment. A step cannot declare a secret by
 * that name, since secret names starting with {@code TRACEWORKS_} are refused. The value is the prompt's UTF-8 bytes
 * in base64: ASCII, so it reaches the container byte for byte whatever encoding the JVM hands process
 * environments in, and any character travels, a NUL too.
 *
 * <p>In the container the constant {@link #SCRIPT} runs first, through the image's {@code sh}: in a subshell with
 * umask 077, it decodes the value into {@value #FILE}, in the container's own {@code /tmp} (a tmpfs nothing else
 * sees), with mode 600, so the CLI itself keeps the default umask; it drops the variable, so the CLI and what it runs
 * do not inherit it; then it starts the CLI with its arguments ({@code "$@"}), which name the file where the CLI wants
 * it ({@link #option}). It prints nothing; the value reaches {@code base64 -d} on stdin, from the builtin
 * {@code printf} of {@code sh}, so it is on no command line in the container either. A call without a system prompt
 * starts its CLI directly.
 *
 * <p>Linux limits one environment string ({@code NAME=value} and its NUL) to 131 072 bytes, and base64 makes 4
 * characters of 3 bytes, so a system prompt over {@value #LIMIT_BYTES} bytes (95 KiB) in UTF-8 is refused before
 * anything runs: at that size the variable takes 129 708 characters.
 */
final class SystemPromptLaunch {

    /** The variable that carries the system prompt to the container. */
    static final String VARIABLE = "TRACEWORKS_SYSTEM_PROMPT";

    /** Where the container's script puts the system prompt for the CLI. */
    static final String FILE = "/tmp/traceworks-system-prompt";

    /** The script that starts the CLI: one constant, which takes no prompt text. */
    static final String SCRIPT = "(umask 077 && printf '%s' \"$" + VARIABLE + "\" | base64 -d > " + FILE + ") && unset "
            + VARIABLE + " && exec \"$@\"";

    /** The largest system prompt, in UTF-8 bytes: 95 KiB, whose base64 fits one environment string. */
    static final int LIMIT_BYTES = 95 * 1024;

    /** The system prompt in base64; {@code null} for none. */
    private final String encoded;

    private SystemPromptLaunch(String encoded) {
        this.encoded = encoded;
    }

    /**
     * The launch of a call with {@code systemPrompt}; a blank one is none.
     *
     * @throws IllegalArgumentException for a system prompt over {@value #LIMIT_BYTES} bytes; the message never shows
     *                                  the prompt
     */
    static SystemPromptLaunch of(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return new SystemPromptLaunch(null);
        }
        byte[] bytes = systemPrompt.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT_BYTES) {
            throw new IllegalArgumentException("The system prompt is " + bytes.length + " bytes; at most " + LIMIT_BYTES
                    + " (95 KiB) reach the AI CLI, which gets it base64-encoded in one environment variable; shorten"
                    + " the system prompt");
        }
        return new SystemPromptLaunch(Base64.getEncoder().encodeToString(bytes));
    }

    /** The CLI's option for its system prompt file, followed by {@value #FILE}; nothing without a system prompt. */
    List<String> option(String option) {
        return encoded == null ? List.of() : List.of(option, FILE);
    }

    /**
     * How the container starts the CLI: {@code cli} as it is without a system prompt; with one, the script with
     * {@code cli} as its arguments, and the system prompt in base64 in {@value #VARIABLE} besides
     * {@code environment}.
     *
     * @param cli   the CLI's program and arguments; with a system prompt, they name {@value #FILE} ({@link #option})
     * @param stdin the user prompt
     */
    AiInvocation invocation(List<String> cli, Map<String, String> environment, String stdin) {
        if (encoded == null) {
            return new AiInvocation(cli, environment, stdin);
        }
        List<String> args = new ArrayList<>(List.of("sh", "-c", SCRIPT, "sh"));
        args.addAll(cli);
        Map<String, String> withPrompt = new HashMap<>(environment);
        withPrompt.put(VARIABLE, encoded);
        return new AiInvocation(args, withPrompt, stdin);
    }
}
