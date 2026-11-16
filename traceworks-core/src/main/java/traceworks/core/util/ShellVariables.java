package traceworks.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * The lines that set shell variables to given values before a command's own text, without one byte of a value in
 * the text the shell parses ({@link #preamble}).
 *
 * <p>A workflow hands a value to a shell line by name ({@code values} in a block's config). Such a value may be text
 * a model wrote or text from outside the run, so it must never be shell code, and no rule about quotes can promise
 * that for text placed into a script: whoever decides from the shell text where a value may stand has to read that
 * text as bash does. So the value is not placed into the script at all. It travels encoded, in an alphabet that
 * means nothing to the shell's parser (letters, digits and {@code \xHH}, inside single quotes), and bash itself
 * decodes it into the variable ({@code printf %b}): what a variable holds, bash does not parse.
 *
 * <pre>
 * TRACEWORKS_VALUE() { local LC_ALL=C TRACEWORKS_V; printf -v TRACEWORKS_V '%b' "$3" &amp;&amp; [[ ${#TRACEWORKS_V} -eq $2 ]] &amp;&amp; unset -v "$1" &amp;&amp; printf -v "$1" '%s' "$TRACEWORKS_V" &amp;&amp; export -n "$1" &amp;&amp; [[ ${!1} == "$TRACEWORKS_V" &amp;&amp; -z ${!1@a} ]] || { echo "traceworks:3f0c…: the value of $1 was not delivered" &gt;&amp;2; exit 74; }; }
 * TRACEWORKS_VALUE REVIEW 17 'it\x27s\x20\x24\x28not\x20run\x29'
 * unset -f TRACEWORKS_VALUE
 * </pre>
 *
 * <ul>
 *   <li><b>First, and of Traceworks' making alone.</b> The lines stand before the command's text and hold none of
 *       it, so nothing the workflow wrote can change how the shell reads them.</li>
 *   <li><b>Every byte.</b> The value's UTF-8 bytes arrive as they are, trailing newlines included. A NUL is taken out
 *       before ({@code NUL} cannot be in a shell variable), so the text never differs from what was checked.</li>
 *   <li><b>Whole or nothing, checked on the variable itself.</b> What bash decoded is compared with the length that
 *       was sent, and after the assignment the variable of that name is compared with what was decoded. A variable
 *       that does not hold the value (a readonly one, one the shell sets itself) ends the shell with exit code
 *       {@value #NOT_DELIVERED_EXIT} before the command's first line.</li>
 *   <li><b>Not exported.</b> The name is unset first, which takes it out of the environment the step started with,
 *       the export attribute is taken off after the assignment (a shell started with {@code allexport} sets it),
 *       and a variable that still has any attribute fails as above. No process the command starts inherits the
 *       value unless the command exports it, and no limit of arguments or the environment binds it.</li>
 *   <li><b>A failure the engine knows.</b> The line the failing shell writes holds a token that is the digest of
 *       the lines that carry the values ({@link #failureMarker}). A value cannot write it: it would have to hold
 *       the digest of a text that holds the value. So {@link #notDelivered} tells a failed delivery from a command
 *       that ends with {@value #NOT_DELIVERED_EXIT} by itself, and a condition never reads it as "false".</li>
 *   <li><b>bash only.</b> No program is needed to decode, so it works wherever the command's bash runs (4.4 or
 *       later; an earlier one fails the delivery).</li>
 * </ul>
 */
public final class ShellVariables {

    /** The exit code of a shell whose value was not delivered: {@code EX_IOERR}, as for a script that did not arrive. */
    public static final int NOT_DELIVERED_EXIT = 74;

    private static final String SETTER = "TRACEWORKS_VALUE";

    private static final String TOKEN = "@TOKEN@";

    private static final String DEFINE = SETTER + "() { local LC_ALL=C TRACEWORKS_V; printf -v TRACEWORKS_V '%b' \"$3\""
            + " && [[ ${#TRACEWORKS_V} -eq $2 ]] && unset -v \"$1\" && printf -v \"$1\" '%s' \"$TRACEWORKS_V\""
            + " && export -n \"$1\" && [[ ${!1} == \"$TRACEWORKS_V\" && -z ${!1@a} ]]"
            + " || { echo \"traceworks:" + TOKEN + ": the value of $1 was not delivered\" >&2; exit "
            + NOT_DELIVERED_EXIT + "; }; }\n";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ShellVariables() {
    }

    /**
     * The lines that set each variable of {@code values} to its value; empty for no values. The names are shell
     * variable names the caller has checked; a value holds no NUL ({@link #withoutNul}).
     */
    public static String preamble(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        String lines = lines(values);
        return DEFINE.replace(TOKEN, token(lines)) + lines + "unset -f " + SETTER + "\n";
    }

    /**
     * What the shell writes on its standard error, at the start of a line, when a value of {@code values} was not
     * delivered: {@code traceworks:<token>:}, the token being the digest of the lines that carry these values.
     * {@code null} for no values.
     */
    public static String failureMarker(Map<String, String> values) {
        return values == null || values.isEmpty() ? null : "traceworks:" + token(lines(values)) + ":";
    }

    /**
     * Whether a shell that ran {@link #preamble} for {@code values} ended because a value was not delivered: its
     * exit code is {@value #NOT_DELIVERED_EXIT} and a line of its standard error starts with the
     * {@link #failureMarker}. The command's own text did not run then.
     */
    public static boolean notDelivered(Map<String, String> values, int exitCode, String stderr) {
        String marker = failureMarker(values);
        if (marker == null || exitCode != NOT_DELIVERED_EXIT || stderr == null) {
            return false;
        }
        return stderr.startsWith(marker) || stderr.contains("\n" + marker);
    }

    /** One line per value: the setter, the name, the length in bytes and the encoded bytes. */
    private static String lines(Map<String, String> values) {
        StringBuilder text = new StringBuilder();
        values.forEach((name, value) -> {
            byte[] bytes = withoutNul(value).getBytes(StandardCharsets.UTF_8);
            text.append(SETTER).append(' ').append(name).append(' ').append(bytes.length).append(" '");
            encode(bytes, text);
            text.append("'\n");
        });
        return text.toString();
    }

    /** The first 32 hex digits of the SHA-256 of {@code lines}. */
    private static String token(String lines) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(lines.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(HEX[(digest[i] >> 4) & 0xf]).append(HEX[digest[i] & 0xf]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** {@code value} without its NUL characters, which no shell variable can hold; {@code ""} for {@code null}. */
    public static String withoutNul(String value) {
        if (value == null) {
            return "";
        }
        return value.indexOf('\u0000') < 0 ? value : value.replace("\u0000", "");
    }

    /** Letters and digits as they are, every other byte as {@code \xHH}: nothing a shell's parser reads. */
    private static void encode(byte[] bytes, StringBuilder out) {
        for (byte b : bytes) {
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')) {
                out.append((char) b);
            } else {
                out.append("\\x").append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
            }
        }
    }
}
