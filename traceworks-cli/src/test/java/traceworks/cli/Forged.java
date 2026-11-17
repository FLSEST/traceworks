package traceworks.cli;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A text from outside that tries to start a terminal line of its own, and the check that it does not: a terminal of
 * 80 columns breaks a wider line, and what follows the break has nothing in front of it.
 */
public final class Forged {

    /** How a forged hint begins. */
    private static final String START = "now go on with:";
    /** A word of the hint that nothing else prints. */
    public static final String WORD = "approve-everything";
    /** How the hint ends. */
    private static final String END = "--without-asking";
    /** What reads as a hint of Traceworks, if it starts a terminal line. */
    public static final String HINT = START + " " + WORD + " --now " + END;
    /** The hint behind blanks enough to cross a terminal line wherever it is printed. */
    public static final String BLANKS = "x" + " ".repeat(200) + HINT;
    /**
     * The same with no-break spaces, which are no white space and stay where white space is made one blank; short
     * enough to be shown whole where a text is cut at 200 characters.
     */
    public static final String NO_BREAK = "x" + String.valueOf((char) 0xA0).repeat(120) + HINT;
    /** Both, for a test that tries each. */
    public static final List<String> PADDED = List.of(BLANKS, NO_BREAK);

    private Forged() {
    }

    /**
     * Wherever the hint is shown, whole or broken at its blanks, each piece of it lies on one terminal line of 80
     * columns, and something other than blanks is in front of it there: text of Traceworks, or the mark of a line
     * that goes on. It is shown at least once.
     */
    public static void assertNeverStartsATerminalLine(String output) {
        assertThat(output).as("the text is shown").contains(WORD);
        output.lines().forEach(line -> {
            for (String piece : List.of(START, WORD, END)) {
                for (int at = line.indexOf(piece); at >= 0; at = line.indexOf(piece, at + 1)) {
                    int from = TerminalText.columns(line.substring(0, at));
                    int to = from + piece.length() - 1;
                    assertThat(from / 80).as("the text crosses a terminal line in: " + line).isEqualTo(to / 80);
                    String before = line.substring(terminalLineStart(line, from / 80), at);
                    assertThat(before.codePoints().allMatch(c -> Character.isWhitespace(c)
                            || Character.isSpaceChar(c))).as("the text starts a terminal line in: " + line).isFalse();
                }
            }
        });
    }

    /** The index in {@code line} of the first character the terminal shows on its line number {@code row}. */
    private static int terminalLineStart(String line, int row) {
        int index = 0;
        int column = 0;
        while (index < line.length() && column < row * 80) {
            int c = line.codePointAt(index);
            column += c >= 0x1100 ? 2 : 1;
            index += Character.charCount(c);
        }
        return index;
    }
}
