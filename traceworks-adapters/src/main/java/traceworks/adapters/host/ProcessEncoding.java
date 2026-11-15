package traceworks.adapters.host;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Whether the JVM can hand a process its text unchanged. The JVM passes a process's arguments and
 * environment in the encoding of its locale ({@code sun.jnu.encoding}), which may be ASCII ({@code LANG=C}); then
 * every character beyond ASCII reaches the process as another byte or {@code ?}, so a path, a secret or a prompt
 * would arrive changed. Such a command is refused before it starts: by the container client
 * ({@code adapters.container.CliContainerRuntime}) and by the host runner ({@link HostCommandRunner}). Under a UTF-8
 * encoding everything passes; under any other, only ASCII. What a process gets on stdin is written as UTF-8 always,
 * so it is not checked.
 */
public final class ProcessEncoding {

    /** The system property that names the encoding of a process's arguments and environment. */
    public static final String PROPERTY = "sun.jnu.encoding";

    private ProcessEncoding() {
    }

    /** The encoding this JVM hands processes their arguments and environment in; {@code null} when it names none. */
    public static String ofTheJvm() {
        return System.getProperty(PROPERTY);
    }

    /**
     * Why {@code command} and {@code environment} cannot reach a process the JVM starts under {@code encoding}: a
     * character beyond ASCII without a UTF-8 encoding. The reason names where it is (the command line, or the
     * variable), never the text.
     *
     * @param encoding the name of the encoding; {@code null} or an unknown name is no UTF-8
     * @return empty when the process gets its text unchanged
     */
    public static Optional<String> refusal(List<String> command, Map<String, String> environment, String encoding) {
        if (utf8(encoding)) {
            return Optional.empty();
        }
        String where = null;
        if (command.stream().anyMatch(ProcessEncoding::beyondAscii)) {
            where = "its command line holds";
        } else {
            for (Map.Entry<String, String> variable : new TreeMap<>(environment).entrySet()) {
                if (beyondAscii(variable.getValue())) {
                    where = "the environment variable " + variable.getKey() + " holds";
                    break;
                }
            }
        }
        if (where == null) {
            return Optional.empty();
        }
        return Optional.of("The command cannot start: " + where + " a character beyond ASCII, which the JVM's"
                + " encoding " + (encoding == null ? "(unknown)" : encoding) + " cannot pass on unchanged; run"
                + " Traceworks with a UTF-8 locale, for example LANG=C.UTF-8");
    }

    private static boolean utf8(String encoding) {
        try {
            return encoding != null && Charset.forName(encoding).equals(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // an illegal or unsupported name
            return false;
        }
    }

    private static boolean beyondAscii(String text) {
        return text != null && text.chars().anyMatch(c -> c > 0x7f);
    }
}
