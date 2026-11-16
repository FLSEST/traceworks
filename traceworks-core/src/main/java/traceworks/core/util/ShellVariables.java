package traceworks.core.util;

import java.nio.charset.StandardCharsets;
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
 * TRACEWORKS_VALUE() { local LC_ALL=C TRACEWORKS_V; printf -v TRACEWORKS_V '%b' "$3" &amp;&amp; [[ ${#TRACEWORKS_V} -eq $2 ]] &amp;&amp; printf -v "$1" '%s' "$TRACEWORKS_V" || { echo "traceworks: the value of $1 did not arrive whole" &gt;&amp;2; exit 74; }; }
 * TRACEWORKS_VALUE REVIEW 17 'it\x27s\x20\x24\x28not\x20run\x29'
 * unset -f TRACEWORKS_VALUE
 * </pre>
 *
 * <ul>
 *   <li><b>First, and of Traceworks' making alone.</b> The lines stand before the command's text and hold none of
 *       it, so nothing the workflow wrote can change how the shell reads them.</li>
 *   <li><b>Every byte.</b> The value's UTF-8 bytes arrive as they are, trailing newlines included. A NUL is taken out
 *       before ({@code NUL} cannot be in a shell variable), so the text never differs from what was checked.</li>
 *   <li><b>Whole or nothing.</b> Each variable's length in bytes is compared with what was sent; a value that did
 *       not arrive whole ends the shell with exit code {@value #NOT_WHOLE_EXIT} before the command's first line.</li>
 *   <li><b>Not exported.</b> They are the shell's own variables: no process the command starts inherits them
 *       unless the command exports them, and no limit of arguments or the environment binds them.</li>
 *   <li><b>bash only.</b> No program is needed to decode, so it works wherever the command's bash runs.</li>
 * </ul>
 */
public final class ShellVariables {

    /** The exit code of a shell whose value did not arrive whole: {@code EX_IOERR}, as for a script that did not. */
    public static final int NOT_WHOLE_EXIT = 74;

    private static final String SETTER = "TRACEWORKS_VALUE";

    private static final String DEFINE = SETTER + "() { local LC_ALL=C TRACEWORKS_V; printf -v TRACEWORKS_V '%b' \"$3\""
            + " && [[ ${#TRACEWORKS_V} -eq $2 ]] && printf -v \"$1\" '%s' \"$TRACEWORKS_V\""
            + " || { echo \"traceworks: the value of $1 did not arrive whole\" >&2; exit " + NOT_WHOLE_EXIT
            + "; }; }\n";

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
        StringBuilder text = new StringBuilder(DEFINE);
        values.forEach((name, value) -> {
            byte[] bytes = withoutNul(value).getBytes(StandardCharsets.UTF_8);
            text.append(SETTER).append(' ').append(name).append(' ').append(bytes.length).append(" '");
            encode(bytes, text);
            text.append("'\n");
        });
        return text.append("unset -f ").append(SETTER).append('\n').toString();
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
