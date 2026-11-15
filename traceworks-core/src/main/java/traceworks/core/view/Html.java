package traceworks.core.view;

/** Escaping and cutting of text for the HTML page. Every text that reaches the page goes through here. */
final class Html {

    private Html() {
    }

    /** Escapes {@code & < > " '} so the text can stand in element content and in quoted attribute values. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A one-line text from the record (a name, an option, an image, a path), escaped for the page. Such a text can hold
     * control, direction and tag characters that change how the page reads, and a lone surrogate that no page can
     * show, so each is shown as visible text, its code point in brackets ({@code [U+202E]}): a numeric reference would
     * put the character itself back on the page, since a browser decodes it.
     */
    static String line(String text) {
        return visible(escape(text), Html::hidden);
    }

    /**
     * Content from the record (an output, an answer), escaped for the page: as {@link #escape}, but a character that
     * sets the direction of the text after it is shown by its code point, as {@link #line} shows it. Line breaks, tabs
     * and every other character stay as they are.
     */
    static String text(String text) {
        return visible(escape(text), Html::direction);
    }

    /** Whether {@link #line} shows {@code cp} by its code point. */
    private static boolean hidden(int cp) {
        int type = Character.getType(cp);
        return Character.isISOControl(cp) || type == Character.FORMAT || cp >= 0xE0000 && cp <= 0xE0FFF
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }

    /** The marks, embeddings, overrides and isolates of the Unicode bidirectional algorithm. */
    private static boolean direction(int cp) {
        return cp == 0x061C || cp == 0x200E || cp == 0x200F || cp >= 0x202A && cp <= 0x202E
                || cp >= 0x2066 && cp <= 0x2069;
    }

    private static String visible(String escaped, java.util.function.IntPredicate shown) {
        StringBuilder out = null;
        for (int i = 0; i < escaped.length(); ) {
            int cp = escaped.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (shown.test(cp)) {
                if (out == null) {
                    out = new StringBuilder(escaped.length() + 16).append(escaped, 0, i);
                }
                out.append("[U+").append(String.format("%04X", cp)).append(']');
            } else if (out != null) {
                out.appendCodePoint(cp);
            }
            i = next;
        }
        return out == null ? escaped : out.toString();
    }

    /** The first {@code max} chars of the text, shortened by one if the cut would split a surrogate pair. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }
}
