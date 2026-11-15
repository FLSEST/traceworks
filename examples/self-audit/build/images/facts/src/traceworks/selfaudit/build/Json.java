package traceworks.selfaudit.build;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the tool needs nothing beyond the JDK. Objects read as {@link LinkedHashMap},
 * arrays as {@link ArrayList}, integral numbers as {@link Long} (else {@link Double}), and {@code null} as
 * {@code null}. The writer writes printable ASCII only. The reader refuses nesting deeper than {@link #MAX_DEPTH}: what
 * it reads may come from a registry on the network. Copied from the security self-audit's tool, without what only that
 * tool needs.
 */
final class Json {

    static final int MAX_DEPTH = 64;

    private Json() {
    }

    /** {@code value} as compact JSON: a map, a list, a string, a number, a boolean or {@code null}. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) throw new IllegalArgumentException("not a JSON number: " + d);
                out.append(d);
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    string(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Everything beyond printable ASCII is escaped, so a line reads the same however a pipe between
                    // the steps treats control, direction or non-ASCII characters
                    if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /**
     * The one JSON value that {@code text} holds, with blanks around it allowed.
     *
     * @throws IllegalArgumentException if the text is no JSON value or holds more than one
     */
    static Object parse(String text) {
        Reader reader = new Reader(text, 0);
        Object value = reader.value(0);
        reader.blanks();
        if (reader.pos != text.length()) throw reader.error("more after the value");
        return value;
    }

    /** A recursive-descent reader over one text. */
    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text, int pos) {
            this.text = text;
            this.pos = pos;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("nested deeper than " + MAX_DEPTH);
            blanks();
            if (pos >= text.length()) throw error("a value is missing");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("unexpected '" + c + "'");
                }
            };
        }

        private Map<String, Object> object(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            blanks();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                blanks();
                if (peek() != '"') throw error("a key is missing");
                String key = string();
                blanks();
                expect(':');
                map.put(key, value(depth + 1));
                blanks();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw error("',' or '}' expected");
            }
        }

        private List<Object> array(int depth) {
            pos++;
            List<Object> list = new ArrayList<>();
            blanks();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value(depth + 1));
                blanks();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw error("',' or ']' expected");
            }
        }

        private String string() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) throw error("the string does not end");
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw error("a control character in a string");
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = next();
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("a \\u escape is cut off");
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("a \\u escape is no hex number");
                        }
                        pos += 4;
                    }
                    default -> throw error("unknown escape \\" + escaped);
                }
            }
        }

        private Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) pos++;
            String literal = text.substring(start, pos);
            try {
                BigDecimal decimal = new BigDecimal(literal);
                if (literal.matches("-?\\d+")) {
                    try {
                        return decimal.longValueExact();
                    } catch (ArithmeticException tooBig) {
                        return decimal.doubleValue();
                    }
                }
                return decimal.doubleValue();
            } catch (NumberFormatException e) {
                throw error("'" + literal + "' is no number");
            }
        }

        private Object word(String word, Object value) {
            if (!text.startsWith(word, pos)) throw error("unexpected text");
            pos += word.length();
            return value;
        }

        void blanks() {
            while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) pos++;
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private char next() {
            if (pos >= text.length()) throw error("the text ends too early");
            return text.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) throw error("'" + c + "' expected");
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON at " + pos + ": " + message);
        }
    }
}
