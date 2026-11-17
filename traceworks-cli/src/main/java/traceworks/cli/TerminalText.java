package traceworks.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The one way a text that is not Traceworks' own gets into a line Traceworks prints on a terminal: the text of a
 * workflow, a model, a step's output, a record, a register or a message that quotes any of them.
 *
 * <p><b>What it keeps.</b> A terminal breaks a line wider than it is, and what follows the break starts a terminal
 * line with nothing in front of it. A text padded with blanks to the terminal's width (or one that is only long)
 * would so put its end where a line of Traceworks is read: a hint with a command to copy, say. So a text is
 * written into its line only where it stays on one terminal line of {@value #WIDTH} columns, not at that line's
 * start, and holds no run of blanks. A text that is too long goes on behind a break on lines that each begin with
 * the mark {@code "|"} behind the indent of their line, and a text with a run of blanks is written on such lines
 * only. No such text starts a terminal line without the mark. Control characters are replaced first
 * ({@link TerminalFormat#safe}), so no text moves the cursor either.
 *
 * <p><b>The marks.</b> {@code "| "} begins a line of a text of several lines, and the first line of a listed
 * option. {@code "|+"} begins a line that goes on with the line above: what follows the mark follows the end of
 * that line, so {@code "|+ word"} goes on behind a blank and {@code "|+ord"} inside a word (a path, a digest), and
 * nobody reads a blank into it. Taking every line feed with the blanks and the {@code "|+"} behind it out of what
 * was printed gives the line as it was.
 *
 * <p><b>The forms.</b> {@link #println} and {@link #lines} take the parts of one line in their order: a
 * {@code String} (or a number, a status) is Traceworks' own and written as it is, however long; a
 * {@code TerminalText} is a text from outside ({@link #safe}, {@link #oneLine}), text of the operator that must
 * stay whole, such as the path of a run they named ({@link #whole}), or a line that the core composed with a
 * text from outside in it ({@link #composed}). {@link #block} marks every line of a text of several lines (a
 * question, an output), {@link #shown} lists options and names that a person types as they are, and
 * {@link #inline} says whether a quoted value may stand in a command.
 *
 * <p><b>What keeps it closed.</b> A {@code TerminalText} is no {@code String}: {@link #toString} gives
 * {@value #WITHHELD}, never the text, so a text that is joined to a line with {@code +} or a format shows as a
 * fault and not as itself. The functions that make a text safe as a {@code String} are visible in this package
 * only, so no command (they live in {@code traceworks.cli.command} and {@code traceworks.cli.main}) can print a
 * text from outside but through this class, and {@code TerminalTextGuardTest} pins the calls in this package.
 *
 * <p><b>What it does not keep.</b> The width is a constant: on a terminal narrower than {@value #WIDTH} columns
 * every line can break, a marked one too. A line of {@link #composed} holds the blanks its composer aligned it
 * with, so only its length is judged. Text that is blank without being white space (the Braille blank, the
 * Hangul fillers) counts as a blank; a font may know others. HTML and JSON output do not pass here.
 */
public final class TerminalText {

    /** The columns of the terminal line that no text from outside may cross. The one place that says it. */
    public static final int WIDTH = 80;
    /** What a line begins with, behind its indent, that goes on with or holds a text from outside. */
    public static final String MARK = "| ";
    /** What a line begins with that goes on with the line above; a blank behind it is the blank of the break. */
    static final String MARK_ON = "|+";
    /** What a text shows as when it is turned into a {@code String} outside this class. */
    static final String WITHHELD = "(text withheld: it was not printed through TerminalText)";
    /** The least columns left for a text on a marked line, however deep the line is indented. */
    private static final int LEAST = 20;

    private enum Kind { OUTSIDE, COMPOSED, WHOLE }

    private final String text;
    private final Kind kind;
    /** The least columns the text takes in its line; blanks fill them. */
    private final int column;

    private TerminalText(String text, Kind kind, int column) {
        this.text = text;
        this.kind = kind;
        this.column = column;
    }

    /** A text from outside, as it is but for its control characters. */
    public static TerminalText safe(String text) {
        return new TerminalText(TerminalFormat.safe(text), Kind.OUTSIDE, 0);
    }

    /** A text from outside on one line: white space runs become one blank, and it is cut at the usual limit. */
    public static TerminalText oneLine(String text) {
        return new TerminalText(TerminalFormat.oneLine(text == null ? "" : text), Kind.OUTSIDE, 0);
    }

    /**
     * A line or a phrase the core composed, with a text from outside somewhere in it and blanks of its own that
     * align it: broken as a text from outside is where it is too long, but a run of blanks does not move it.
     */
    public static TerminalText composed(String text) {
        return new TerminalText(TerminalFormat.safe(text), Kind.COMPOSED, 0);
    }

    /**
     * Text of the operator that is never broken, so that it can be copied: the folder of a run or a package they
     * named, a file of their home. Not for anything a workflow, a step or a record of another's hand can set.
     */
    public static TerminalText whole(String text) {
        return new TerminalText(TerminalFormat.safe(text), Kind.WHOLE, 0);
    }

    /** This text in a column of at least {@code columns}: blanks follow it where it is shorter and not broken. */
    public TerminalText padded(int columns) {
        return new TerminalText(text, kind, columns);
    }

    /** Whether there is no text. */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** The columns a terminal gives the text, for a caller that aligns what follows it. */
    public int width() {
        return columns(text);
    }

    /** Never the text: see the class comment. */
    @Override
    public String toString() {
        return WITHHELD;
    }

    /** Parts that stand for one part, for a place that takes one: the arms of a condition. */
    public static Object seq(Object... parts) {
        return Arrays.asList(parts);
    }

    /** Prints one line of Traceworks with the texts in it; see {@link #lines}. */
    public static void println(PrintStream out, Object... parts) {
        lines(parts).forEach(out::println);
    }

    /** The lines of {@link #lines}, joined by line feeds. */
    public static String text(Object... parts) {
        return String.join("\n", lines(parts));
    }

    /**
     * One line of Traceworks from its parts in their order, as the lines to print: one, where every text stays
     * on its terminal line and holds no run of blanks; else the line up to the break and the rest on marked lines.
     * What follows a broken text goes on behind its last line.
     */
    public static List<String> lines(Object... parts) {
        List<Object> flat = new ArrayList<>();
        flatten(Arrays.asList(parts), flat);
        String indent = indent(flat) + "  ";
        int width = Math.max(LEAST, WIDTH - columns(indent) - MARK_ON.length() - 1);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (Object part : flat) {
            if (!(part instanceof TerminalText outside)) {
                line.append(part);
                continue;
            }
            String text = outside.text;
            if (outside.kind == Kind.WHOLE || text.isEmpty()) {
                line.append(pad(text, outside.column));
                continue;
            }
            boolean blanks = outside.kind == Kind.OUTSIDE && blankRun(text);
            // Whether the text goes on below behind a blank it was broken at; the line's own blanks stay on it
            boolean atBlank = false;
            if (!blanks) {
                int from = columns(line.toString());
                int room = from > 0 && from % WIDTH == 0 ? 0 : WIDTH - from % WIDTH;
                if (columns(text) <= room) {
                    line.append(pad(text, outside.column));
                    continue;
                }
                // The text starts on this line where a blank lets it break there, and goes on below
                int cut = room < 2 ? -1 : lastBlank(text, fitting(text, room));
                if (cut > 0 && !text.substring(0, cut).isBlank()) {
                    line.append(stripEnd(text.substring(0, cut)));
                    text = stripStart(text.substring(cut));
                    atBlank = true;
                }
            }
            if (!line.toString().isBlank()) {
                lines.add(line.toString());
            }
            List<Piece> rest = pieces(text, width);
            for (int i = 0; i < rest.size(); i++) {
                String piece = indent + (lines.isEmpty() ? MARK : MARK_ON)
                        + (i == 0 && atBlank || i > 0 && rest.get(i - 1).atBlank() ? " " : "") + rest.get(i).text();
                if (i < rest.size() - 1) {
                    lines.add(piece);
                } else {
                    line = new StringBuilder(piece);
                }
            }
        }
        lines.add(line.toString());
        return lines;
    }

    private static void flatten(List<?> parts, List<Object> flat) {
        for (Object part : parts) {
            if (part instanceof List<?> nested) {
                flatten(nested, flat);
            } else {
                flat.add(part instanceof TerminalText ? part : String.valueOf(part));
            }
        }
    }

    /** The blanks the line begins with, where it begins with text of Traceworks; never more than half a line. */
    private static String indent(List<Object> flat) {
        int blanks = 0;
        for (Object part : flat) {
            if (!(part instanceof String own)) {
                break;
            }
            int here = 0;
            while (here < own.length() && own.charAt(here) == ' ') {
                here++;
            }
            blanks += here;
            if (here < own.length()) {
                break;
            }
        }
        return " ".repeat(Math.min(blanks, WIDTH / 2));
    }

    private static String pad(String text, int columns) {
        return text + " ".repeat(Math.max(0, columns - columns(text)));
    }

    /**
     * Prints a note or a warning of the core behind {@code lead}: a message of one line or several, in Traceworks'
     * own words with a name or a path from outside in them. Each of its lines is a {@link #composed} one.
     */
    public static void message(PrintStream out, String lead, String message) {
        List<String> lines = TerminalFormat.printable(message).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            println(out, i == 0 ? lead : "", composed(lines.get(i)));
        }
        if (lines.isEmpty()) {
            out.println(lead);
        }
    }

    /**
     * Every line of a text of several lines (a question, an output, a diff), each behind {@code lead} and the mark
     * {@code "| "}. A line wider than the terminal leaves behind them is broken, and each further part of it is
     * behind the lead and {@code "|+"}, since the terminal would break it without a mark. Blanks stay as they are,
     * as every line is marked.
     *
     * @param lead what is before the mark: the indent, or words of Traceworks
     * @param text the text as it is; its control characters other than the line feed are replaced here
     */
    public static List<String> block(String lead, String text) {
        int width = Math.max(LEAST, WIDTH - columns(lead) - MARK_ON.length() - 1);
        List<String> lines = new ArrayList<>();
        TerminalFormat.printable(text).lines()
                .forEach(line -> listed("", line, width).forEach(part -> lines.add(lead + part)));
        return lines;
    }

    /**
     * A stream with an indent for every line it prints, for output that is nested (the steps of a child run): the
     * indent is part of the line, so a text is broken where the terminal would break the indented line.
     */
    public record Printer(PrintStream out, String indent) {

        /** Prints one line from its parts behind the indent; see {@link TerminalText#lines}. */
        public void println(Object... parts) {
            TerminalText.println(out, indent, Arrays.asList(parts));
        }

        /** Prints an empty line. */
        public void println() {
            out.println();
        }

        /** Prints every line of a text behind the indent, {@code lead} and the mark ({@link TerminalText#block}). */
        public void block(String lead, String text) {
            TerminalText.block(indent + lead, text).forEach(out::println);
        }

        /** A printer whose lines are indented by {@code more} beyond this one's. */
        public Printer indented(String more) {
            return new Printer(out, indent + more);
        }
    }

    /**
     * The lines that show texts a person may have to type as they are (options, the name or label of a field)
     * behind a caption of Traceworks, each line to be printed behind {@code lead}: one line, the texts joined by
     * {@code " / "} behind the caption and between {@code open} and {@code close}, where that line fits the
     * terminal and no text holds a run of blanks; else the caption alone (with {@code elsewhere} behind it) and
     * each text on marked lines of its own.
     *
     * @param caption   Traceworks' own text, or the operator's; may be empty
     * @param elsewhere what the caption's line ends with when the texts follow on marked lines
     */
    public static List<String> shown(String lead, String caption, String open, String close, String elsewhere,
                                     List<String> values) {
        List<String> safe = values.stream().map(TerminalFormat::safe).toList();
        String line = caption + open + String.join(" / ", safe) + close;
        List<String> lines = new ArrayList<>();
        if (safe.stream().noneMatch(TerminalText::blankRun) && columns(lead + line) <= WIDTH) {
            lines.add(line);
            return lines;
        }
        if (!(caption + elsewhere).isEmpty()) {
            lines.add(caption + elsewhere);
        }
        safe.forEach(value -> lines.addAll(listed(lead, value)));
        return lines;
    }

    /**
     * One option or name on marked lines that fit the terminal behind {@code lead}; the blanks at a break go.
     *
     * @param value the text, its control characters replaced already
     */
    static List<String> listed(String lead, String value) {
        return listed(lead, value, Math.max(LEAST, WIDTH - columns(lead) - MARK_ON.length() - 1));
    }

    private static List<String> listed(String lead, String value, int width) {
        List<Piece> parts = pieces(value, width);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            lines.add(i == 0 ? MARK + parts.get(i).text()
                    : MARK_ON + (parts.get(i - 1).atBlank() ? " " : "") + parts.get(i).text());
        }
        return lines;
    }

    /**
     * Whether {@code quoted}, written behind {@code before}, lies on one terminal line and not at its start, and
     * {@code value} holds no two blanks in a row. What comes before may be wider than the terminal (a run named by
     * its folder): that is the operator's text and Traceworks' own, and only a text from outside may not cross a
     * line.
     */
    static boolean inline(String before, String value, String quoted) {
        int from = columns(before);
        int to = from + columns(quoted) - 1;
        if (from / WIDTH != to / WIDTH || from > 0 && from % WIDTH == 0) {
            return false;
        }
        return !blankRun(value);
    }

    /**
     * Whether {@code value} holds two blanks in a row: white space of any kind, the no-break spaces, and the
     * characters that show as nothing without being white space.
     */
    static boolean blankRun(String value) {
        boolean blank = false;
        for (int i = 0; i < value.length(); ) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            if (blank && isBlank(c)) {
                return true;
            }
            blank = isBlank(c);
        }
        return false;
    }

    /** Whether a character shows as nothing: white space, a no-break space, the Braille blank, a Hangul filler. */
    private static boolean isBlank(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c)
                || c == 0x2800 || c == 0x3164 || c == 0x115F || c == 0x1160 || c == 0xFFA0;
    }

    /** The index of the last blank of {@code text} at or before {@code upTo}; -1 without one. */
    private static int lastBlank(String text, int upTo) {
        for (int i = Math.min(upTo, text.length() - 1); i >= 0; i--) {
            if (!Character.isLowSurrogate(text.charAt(i)) && isBlank(text.codePointAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripEnd(String text) {
        int end = text.length();
        while (end > 0 && isBlank(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(0, end);
    }

    private static String stripStart(String text) {
        int start = 0;
        while (start < text.length() && isBlank(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        return text.substring(start);
    }

    /**
     * A part of a broken line.
     *
     * @param atBlank whether the line was broken at a blank behind this part, and not inside a word
     */
    private record Piece(String text, boolean atBlank) {}

    /**
     * A line in parts of at most {@code width} columns (at least one character each): broken at the last blank
     * within the width, else at the width; the blanks at a break are dropped. A short line is one part, as it is.
     *
     * <p>The width is counted in the columns a terminal gives a character, not in characters: a part that is wider
     * than the terminal is broken there by the terminal, and what follows shows without a mark. A tab becomes a
     * blank first: it takes up to eight columns, and at the right margin a terminal keeps the cursor in the last
     * column, so text after a row of tabs would wrap.
     */
    static List<String> broken(String line, int width) {
        return pieces(line, width).stream().map(Piece::text).toList();
    }

    private static List<Piece> pieces(String line, int width) {
        List<Piece> parts = new ArrayList<>();
        String rest = stripEnd(line.replace('\t', ' '));
        while (true) {
            int fits = fitting(rest, width);
            if (fits == rest.length()) {
                break;
            }
            int cut = lastBlank(rest, fits);
            // A blank that only leads the line is no place to break: the part before it would be empty
            boolean atBlank = !stripEnd(rest.substring(0, Math.max(cut, 0))).isEmpty();
            if (!atBlank) {
                cut = fits;
            }
            parts.add(new Piece(stripEnd(rest.substring(0, cut)), atBlank));
            rest = stripStart(rest.substring(cut));
        }
        if (!rest.isEmpty() || parts.isEmpty()) {
            parts.add(new Piece(rest, false));
        }
        return parts;
    }

    /**
     * How many chars from the start of {@code text} fit into {@code width} columns, never between the
     * two halves of one character and at least one character, so a break always moves on. A character from U+1100
     * on counts as two columns: every wide character (Hangul, CJK, the ideographic space, the fullwidth forms,
     * emoji) lies there, and a narrow one counted as two only breaks its line earlier.
     */
    private static int fitting(String text, int width) {
        int columns = 0;
        int i = 0;
        while (i < text.length()) {
            int c = text.codePointAt(i);
            columns += c >= 0x1100 ? 2 : 1;
            if (columns > width && i > 0) {
                return i;
            }
            i += Character.charCount(c);
        }
        return i;
    }

    /** The columns a terminal gives {@code text}, counted as {@link #fitting} counts them. */
    static int columns(String text) {
        return text.codePoints().map(c -> c >= 0x1100 ? 2 : 1).sum();
    }
}
