package traceworks.selfaudit.architecture;

/**
 * Non-comment lines of code: the lines of a Java file that hold anything besides blanks and comments. A small lexer
 * reads the text, so a {@code //} or {@code /*} inside a string, a character or a text block is no comment, and a line
 * inside a text block counts as code.
 */
final class Ncloc {

    private Ncloc() {
    }

    /** The non-comment lines of {@code text}. */
    static int of(String text) {
        int count = 0;
        boolean code = false;
        State state = State.CODE;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            if (c == '\n' || (c == '\r' && next != '\n')) {
                if (code) count++;
                code = false;
                if (state == State.LINE_COMMENT) state = State.CODE;
                // A string or character literal cannot span lines; a broken one ends here
                if (state == State.STRING || state == State.CHAR) state = State.CODE;
                continue;
            }
            switch (state) {
                case CODE -> {
                    if (c == '/' && next == '/') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"' && text.startsWith("\"\"\"", i)) {
                        state = State.TEXT_BLOCK;
                        code = true;
                        i += 2;
                    } else if (c == '"') {
                        state = State.STRING;
                        code = true;
                    } else if (c == '\'') {
                        state = State.CHAR;
                        code = true;
                    } else if (!Character.isWhitespace(c)) {
                        code = true;
                    }
                }
                case LINE_COMMENT -> {
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        state = State.CODE;
                        i++;
                    }
                }
                case STRING, CHAR -> {
                    code = true;
                    if (c == '\\') {
                        if (next != '\n' && next != '\r') i++;
                    } else if ((state == State.STRING && c == '"') || (state == State.CHAR && c == '\'')) {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (!Character.isWhitespace(c)) code = true;
                    if (c == '\\') {
                        // A backslash at the end of a line joins the lines; the line break still ends this line
                        if (next != '\n' && next != '\r') i++;
                    } else if (text.startsWith("\"\"\"", i)) {
                        state = State.CODE;
                        i += 2;
                    }
                }
            }
        }
        if (code) count++;
        return count;
    }

    private enum State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK }
}
