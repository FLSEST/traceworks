package traceworks.core.util;

import java.util.Collection;
import java.util.List;

/**
 * Deterministic truncation helpers for surfacing long step outputs to the AI assistant.
 * Errors usually sit at the END of an output, so these helpers preserve the tail —
 * unlike naive head-first substring truncation.
 *
 * <p><b>Secret values.</b> The record redacts a secret value only where it is whole, so a cut must
 * never fall inside one: the overloads that take {@code whole} (the run's secret values,
 * {@code ExecutionContext#secretValues}) move a cut that would split a value to its edge, so the value is left out
 * whole and the text is never longer than without them. The blocks, the engine and the context cut only through
 * these overloads ({@code SecretAwareCutsTest}). A text that was cut off before it reaches this class, such as a stream
 * cut at the output limit, ends where {@link #cutBeforeOpenEnd} says.
 */
public final class TruncationUtil {

    private TruncationUtil() {}

    /**
     * Keeps the first {@code max} characters and marks the cut with {@code "..."}; a string within budget is
     * returned unchanged. For labels (item names), where the start identifies the value.
     */
    public static String head(String s, int max) {
        return head(s, max, List.of());
    }

    /** Like {@link #head(String, int)}, with a cut that leaves out a value of {@code whole} it would split. */
    public static String head(String s, int max, Collection<String> whole) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, cutBefore(s, max, whole)) + "..." : s;
    }

    /**
     * Keeps the first {@code head} and last {@code tail} characters with a skip
     * marker in between. Strings within budget are returned unchanged (no marker).
     */
    public static String smart(String s, int head, int tail) {
        return smart(s, head, tail, List.of());
    }

    /**
     * Like {@link #smart(String, int, int)}, but neither cut falls inside one of the {@code whole} values: the head
     * ends before a value its cut would split, the tail starts after it, so the value is left out whole.
     */
    public static String smart(String s, int head, int tail, Collection<String> whole) {
        if (s == null) return "";
        int len = s.length();
        if (len <= head + tail) return s;
        int headEnd = cutBefore(s, head, whole);
        int tailStart = cutAfter(s, len - tail, whole);
        int skipped = tailStart - headEnd;
        return s.substring(0, headEnd)
                + "\n[... skipped " + skipped + " of " + len + " characters ...]\n"
                + s.substring(tailStart);
    }

    /**
     * Where a cut that keeps the text before it falls: {@code at}, or the start of a value of {@code whole} that
     * would be split there (repeated, since that start can split another value). Never after {@code at}.
     */
    public static int cutBefore(String s, int at, Collection<String> whole) {
        int cut = Math.max(0, Math.min(at, s.length()));
        boolean moved = true;
        while (moved) {
            moved = false;
            for (String value : whole) {
                int spanning = spanning(s, cut, value);
                if (spanning >= 0) {
                    cut = spanning;
                    moved = true;
                }
            }
        }
        return cut;
    }

    /**
     * Where a cut that keeps the text after it falls: {@code at}, or the end of a value of {@code whole} that would
     * be split there (repeated, since that end can split another value). Never before {@code at}.
     */
    public static int cutAfter(String s, int at, Collection<String> whole) {
        int cut = Math.max(0, Math.min(at, s.length()));
        boolean moved = true;
        while (moved) {
            moved = false;
            for (String value : whole) {
                int spanning = spanning(s, cut, value);
                if (spanning >= 0) {
                    cut = spanning + value.length();
                    moved = true;
                }
            }
        }
        return cut;
    }

    /**
     * Where a text whose continuation is not known ends without a part of a value of {@code whole}: a stream that a
     * byte limit cut off, say, whose last characters may be the first ones of a value it went on to write. The cut
     * falls before the earliest place from which the rest of the text is the start of a value, and then, as with
     * {@link #cutBefore}, before a value that a cut there would split; without such a place it is the text's length. A
     * value whole at the end is kept. The rest may start a value by chance; it is left out all the same, since what
     * followed it is not known.
     */
    public static int cutBeforeOpenEnd(String s, Collection<String> whole) {
        if (s == null) return 0;
        int length = s.length();
        int cut = length;
        for (String value : whole) {
            if (value == null || value.isEmpty()) continue;
            for (int start = Math.max(0, length - value.length() + 1); start < cut; start++) {
                if (s.regionMatches(start, value, 0, length - start)) {
                    cut = start;
                    break;
                }
            }
        }
        return cutBefore(s, cut, whole);
    }

    /** The start of an occurrence of {@code value} that a cut at {@code at} would split; {@code -1} for none. */
    private static int spanning(String s, int at, String value) {
        if (value == null || value.isEmpty()) return -1;
        for (int i = s.indexOf(value, Math.max(0, at - value.length() + 1)); i >= 0 && i < at;
             i = s.indexOf(value, i + 1)) {
            if (i + value.length() > at) return i;
        }
        return -1;
    }

    /**
     * The end of {@code text}, at most {@code limit} characters, stripped, behind {@code ...} when it is cut. The cut
     * never splits a secret value, which the record could then no longer redact: the end starts at a character no
     * secret value holds ({@code "}, {@code \} or a control character, such as a line break; the rules of
     * {@code Redactor.problem}). When the end holds none, nothing of it is kept. Only the end is copied, never the
     * whole text, which may be all a process printed. For text whose secret values are not at hand, such as what a CLI
     * or a container client printed.
     *
     * @return the empty string for {@code null}
     */
    public static String secretSafeTail(String text, int limit) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = 0;
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (end - start <= limit) {
            return text.substring(start, end);
        }
        for (int i = end - limit; i < end; i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || c == 0x7f) {
                return "..." + text.substring(i, end);
            }
        }
        return "...";
    }

    /**
     * Returns a slice of up to {@code max} characters starting at {@code offset},
     * framed by position markers so the AI can page through long outputs.
     */
    public static String window(String s, int offset, int max) {
        if (s == null) return "";
        int len = s.length();
        int from = Math.max(0, offset);
        if (from >= len && len > 0) {
            return "[offset " + offset + " is beyond output length " + len + "]";
        }
        int to = Math.min(from + max, len);
        StringBuilder sb = new StringBuilder();
        sb.append("[showing characters ").append(from).append("-").append(to)
                .append(" of ").append(len).append("]\n")
                .append(s, from, to);
        if (to < len) {
            sb.append("\n[... ").append(len - to)
                    .append(" more characters. Continue with ?offset=").append(to).append(" ...]");
        }
        return sb.toString();
    }

    /**
     * Single-line tail for compact list entries: keeps the last {@code max}
     * characters prefixed with an ellipsis — no multi-line skip marker.
     */
    public static String tailInline(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "…" + s.substring(s.length() - max);
    }

    /**
     * Keeps the last {@code max} characters with a leading skip marker.
     * Strings within budget are returned unchanged.
     */
    public static String tail(String s, int max) {
        if (s == null) return "";
        int len = s.length();
        if (len <= max) return s;
        return "[... skipped " + (len - max) + " of " + len + " characters ...]\n"
                + s.substring(len - max);
    }
}
