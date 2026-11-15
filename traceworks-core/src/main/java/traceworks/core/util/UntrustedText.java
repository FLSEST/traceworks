package traceworks.core.util;

import traceworks.core.compat.EarlierNames;

import java.text.Normalizer;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Turns untrusted text (what a tool, an import, a webhook or a model wrote) into a bounded line without the markers a
 * run evaluates and without block boundaries. The SARIF fact sheet of a tool step uses it for what the tool reported;
 * it lives here so that every caller uses the same sanitizer without depending on the others. Multi-line text such as
 * a tool log goes through the same sanitizer line by line, without truncating ({@link #defuseLines}).
 *
 * <p><b>Disguised markers</b>: a model likes to reproduce {@code ＜／audit_findings＞} (full-width) or
 * a {@code todo_item:} with a zero-width space in between as ASCII – and then it takes effect. The canonical form
 * (NFKC, without format characters {@code \p{Cf}}) is therefore checked too; only if it shows a marker or
 * a block boundary does it continue with that form. Normal text stays as it is – also emojis with
 * zero-width joiner, which would fall apart without {@code \p{Cf}}.
 */
public final class UntrustedText {

    /** Upper limit per inserted title – long titles are prompt space controlled by the attacker. */
    public static final int MAX_TITLE_CHARS = 200;

    /** Every line break ({@code \v}: also VT, FF, NEL, U+2028/2029) and tabs. */
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\v\\t]+");
    /** {@code (?U)}: also Unicode whitespace such as NBSP or U+3000. */
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s{2,}");
    /**
     * Output markers that a run or a register evaluates ({@code gate_*}, {@code todo_item}), the marker
     * {@code dashboard_widget} that a system reading the output may evaluate, and the envelope of an audit report,
     * {@code traceworks:audit-result} or its earlier marker, where the parser takes the last one and a reproduced
     * title can come after or instead of the real one – defused by removing the colon.
     */
    private static final Pattern MARKERS = Pattern.compile("(?iU)(todo[_-]item|gate[_-](?:status|category|description)"
            + "|dashboard[_-]widget|(?:traceworks|" + EarlierNames.AUDIT_ENVELOPE_WORD
            + ")(?=[*`\\s]*:[*`\\s]*audit[_-]result))[*`\\s]*:");
    /** The block boundaries themselves ({@code open_todos}, {@code goal_status}, {@code audit_findings}, {@code audit_items}, {@code todo_details}) must not be forgeable from within a title. */
    private static final Pattern DELIMITER = Pattern.compile("(?iU)</?\\s*(open_todos|goal_status|audit_findings|audit_items|todo_details)\\s*>");
    /** Vertical whitespace except {@code \n}: CR (also CRLF), VT, FF, NEL, U+2028/2029. */
    private static final Pattern OTHER_LINE_BREAKS = Pattern.compile("\\r\\n?|[\\u000B\\u000C\\u0085\\u2028\\u2029]");
    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}");

    private UntrustedText() {
    }

    /** One line, truncated to {@code maxChars}, markers defused, block boundaries neutralized. */
    public static String inline(String raw, int maxChars) {
        return inline(raw, maxChars, java.util.List.of());
    }

    /** Like {@link #inline(String, int)}, with a cut that keeps each of {@code keepWhole} (secret values) whole. */
    public static String inline(String raw, int maxChars, java.util.Collection<String> keepWhole) {
        if (raw == null) return "";
        String s = defuseLine(collapseSpaces(LINE_BREAKS.matcher(raw).replaceAll(" ")));
        if (s.length() > maxChars) {
            s = s.substring(0, end(s, maxChars, keepWhole)).trim() + "…";
        }
        return s;
    }

    /** Where a cut after {@code maxChars} falls: never inside an emoji, never inside one of {@code keepWhole}. */
    private static int end(String s, int maxChars, java.util.Collection<String> keepWhole) {
        // never cut an emoji in half: a lone surrogate would no longer be a valid character
        int end = maxChars > 0 && Character.isHighSurrogate(s.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return TruncationUtil.cutBefore(s, end, keepWhole);
    }

    /**
     * Multi-line foreign text, such as a description from an import, defused line by line like {@link #inline}:
     * markers without colon, block boundaries neutralized, disguised forms (NFKC without format characters) detected.
     * Lines are kept, tabs become spaces, truncated to {@code maxChars} with "…".
     */
    public static String block(String raw, int maxChars) {
        return block(raw, maxChars, java.util.List.of());
    }

    /** Like {@link #block(String, int)}, with a cut that keeps each of {@code keepWhole} (secret values) whole. */
    public static String block(String raw, int maxChars, java.util.Collection<String> keepWhole) {
        if (raw == null) return "";
        String normalized = OTHER_LINE_BREAKS.matcher(raw).replaceAll("\n").replace('\t', ' ');
        String s = defuseLines(normalized).stripTrailing();
        if (s.length() > maxChars) {
            s = s.substring(0, end(s, maxChars, keepWhole)).stripTrailing() + "…";
        }
        return s;
    }

    /**
     * Multi-line text, every line defused as in {@link #inline} – markers without colon, block boundaries
     * as {@code [name]}, a disguised marker in the form in which it would take effect –, but untruncated and
     * with its lines. A {@code gate_status} line stays readable that way ({@code gate_status GREEN}); if a
     * model reproduces it, no parser evaluates it as its own verdict.
     */
    public static String defuseLines(String raw) {
        if (raw == null) return "";
        StringJoiner lines = new StringJoiner("\n");
        for (String line : raw.split("\n", -1)) {   // -1: trailing empty lines are kept too
            lines.add(defuseLine(line));
        }
        return lines.toString();
    }

    /** The defusing itself, without truncating and without touching line breaks. */
    private static String defuseLine(String line) {
        String s = line;
        String canonical = canonical(s);
        if (!canonical.equals(s) && (MARKERS.matcher(canonical).find() || DELIMITER.matcher(canonical).find())) {
            s = canonical;   // a disguised marker: continue with the form in which it would take effect
        }
        s = defuseMarkers(s);
        return DELIMITER.matcher(s).replaceAll(m -> "[" + m.group(1).toLowerCase(Locale.ROOT) + "]");
    }

    /** The form in which a model could reproduce the text: NFKC, without invisible format characters. */
    private static String canonical(String s) {
        return collapseSpaces(FORMAT_CHARS.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll(""));
    }

    /** Removes the colon after platform markers so that a title cannot forge a finding or a gate. */
    public static String defuseMarkers(String s) {
        return s == null ? "" : MARKERS.matcher(s).replaceAll("$1");
    }

    /** Collapses runs of whitespace into one space and trims the edges. */
    public static String collapseSpaces(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll(" ").trim();
    }
}
