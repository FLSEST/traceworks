package traceworks.core.analyzer;

import traceworks.core.context.PlaceholderSyntax;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the placeholders of a shell text together with the quoting they stand in: none, single or double
 * quotes. A small state machine instead of a regex, because the quotes that matter often sit far from the
 * placeholder ({@code echo 'CHAIN={{ previous_chain_output }}'}) and command substitution opens a fresh context
 * ({@code "$(printf '%s' {{ x }})"}).
 *
 * <p>Left out of {@link #scan} on purpose: comment lines and the bodies of heredocs. In a heredoc body quotes are
 * plain text (Python source, say), so a quote state there means nothing. What a heredoc body holds and which
 * command reads it, {@link #read} reports separately. {@code $'…'}, backticks and {@code case} patterns inside
 * {@code $(…)} are not modelled; a misread there costs a missed finding, never a false one on ordinary scripts.
 */
final class ShellQuoteScanner {

    private ShellQuoteScanner() {}

    enum Quote { NONE, SINGLE, DOUBLE }

    /** One placeholder occurrence: its name as written between the braces, its quoting, its 1-based line. */
    record Placeholder(String name, Quote quote, int line) {}

    /**
     * One heredoc: its delimiter, whether the delimiter is quoted (then the shell leaves the body alone), the
     * command the operator belongs to (from the start of that command up to the operator), the rest of the
     * operator's line (redirections, a pipe into the next command) and the placeholder names in the body.
     */
    record Heredoc(String delimiter, boolean quoted, String command, String rest, List<String> placeholders) {}

    /** What one pass finds: the placeholders outside heredoc bodies, and the heredocs. */
    record Result(List<Placeholder> placeholders, List<Heredoc> heredocs) {}

    /** A heredoc delimiter in all its quoting forms ({@code <<EOF}, {@code <<-'EOF'}, {@code <<"EOF"}, {@code <<\EOF}). */
    private static final Pattern HEREDOC = Pattern.compile("<<-?\\s*(['\"]?)(\\\\?)([A-Za-z_][A-Za-z0-9_]*)\\1");

    /**
     * Quoting context of one nesting level: the top level, the inside of a {@code $(…)}, or arithmetic
     * ({@code $(( … ))}, {@code (( … ))}), where {@code <<} is a shift and never a heredoc; {@code commandStart}
     * is where its current simple command began.
     */
    private static final class Frame {
        Quote quote = Quote.NONE;
        int parens;
        int commandStart;
        final boolean arithmetic;

        Frame(int commandStart, boolean arithmetic) {
            this.commandStart = commandStart;
            this.arithmetic = arithmetic;
        }

        /** The frame a {@code $(} at {@code i} opens; {@code $((} is arithmetic. */
        static Frame substitution(String code, int i) {
            return new Frame(i + 2, i + 2 < code.length() && code.charAt(i + 2) == '(');
        }

        /** The frame a {@code ((} at {@code i} opens: the first {@code )} closes its second paren, the next one it. */
        static Frame arithmeticCommand(int i) {
            Frame frame = new Frame(i + 2, true);
            frame.parens = 1;
            return frame;
        }
    }

    static List<Placeholder> scan(String code) {
        return read(code).placeholders();
    }

    static Result read(String code) {
        List<Placeholder> found = new ArrayList<>();
        List<Heredoc> heredocs = new ArrayList<>();
        if (code == null || code.isEmpty()) return new Result(found, heredocs);
        Map<Integer, MatchResult> placeholders = placeholderStarts(code);
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(0, false));
        Heredoc heredoc = null;
        int line = 1;
        int n = code.length();
        for (int i = 0; i < n; i++) {
            Frame frame = frames.peek();
            MatchResult placeholder = placeholders.get(i);
            if (placeholder != null) {
                found.add(new Placeholder(placeholder.group(2).strip(), frame.quote, line));
                i = placeholder.end() - 1;
                continue;
            }
            char c = code.charAt(i);
            if (c == '\n') {
                line++;
                if (frame.quote != Quote.NONE) continue;
                frame.commandStart = i + 1;
                if (heredoc != null) {
                    int[] skipped = skipHeredocBody(code, i + 1, heredoc.delimiter());
                    heredocs.add(new Heredoc(heredoc.delimiter(), heredoc.quoted(), heredoc.command(), heredoc.rest(),
                            placeholderNames(code.substring(i + 1, Math.min(code.length(), skipped[0] + 1)))));
                    i = skipped[0] - 1;
                    line += skipped[1];
                    heredoc = null;
                }
                continue;
            }
            char next = i + 1 < n ? code.charAt(i + 1) : '\0';
            switch (frame.quote) {
                case SINGLE -> {
                    if (c == '\'') frame.quote = Quote.NONE;
                }
                case DOUBLE -> {
                    if (c == '\\') i++;
                    else if (c == '"') frame.quote = Quote.NONE;
                    else if (c == '$' && next == '(') { frames.push(Frame.substitution(code, i)); i++; }
                }
                case NONE -> {
                    if (c == '\\') i++;
                    else if (c == '\'') frame.quote = Quote.SINGLE;
                    else if (c == '"') frame.quote = Quote.DOUBLE;
                    else if (c == '#' && wordStart(code, i)) i = endOfLine(code, i) - 1;
                    else if (c == '$' && next == '(') { frames.push(Frame.substitution(code, i)); i++; }
                    else if (c == '(' && next == '(' && !frame.arithmetic) { frames.push(Frame.arithmeticCommand(i)); i++; }
                    else if (c == '(') { frame.parens++; frame.commandStart = i + 1; }
                    else if (separator(code, i)) frame.commandStart = i + 1;
                    else if (c == ')') {
                        if (frame.parens > 0) frame.parens--;
                        else if (frames.size() > 1) frames.pop();
                    } else if (c == '<' && next == '<' && frame.arithmetic) {
                        i++;   // a shift
                    } else if (c == '<' && next == '<') {
                        Matcher m = HEREDOC.matcher(code).region(i, endOfLine(code, i));
                        if (m.lookingAt()) {
                            boolean quoted = !m.group(1).isEmpty() || !m.group(2).isEmpty();
                            heredoc = new Heredoc(m.group(3), quoted, code.substring(frame.commandStart, i).strip(),
                                    code.substring(m.end(), endOfLine(code, m.end())).strip(), List.of());
                            i = m.end() - 1;
                        } else {
                            i++;   // a here-string (<<<) or a shift: nothing to remember
                        }
                    }
                }
            }
        }
        return new Result(found, heredocs);
    }

    private static Map<Integer, MatchResult> placeholderStarts(String code) {
        Map<Integer, MatchResult> starts = new HashMap<>();
        Matcher m = PlaceholderSyntax.PLACEHOLDER.matcher(code);
        while (m.find()) starts.put(m.start(), m.toMatchResult());
        return starts;
    }

    /**
     * A {@code ;}, {@code &} or {@code |} that ends a simple command — not the {@code &} of {@code 2>&1},
     * {@code &>file} or {@code <&3}, nor the {@code |} of {@code >|}.
     */
    private static boolean separator(String code, int i) {
        char c = code.charAt(i);
        char before = i > 0 ? code.charAt(i - 1) : '\0';
        char after = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
        return switch (c) {
            case ';' -> true;
            case '&' -> before != '>' && before != '<' && after != '>';
            case '|' -> before != '>';
            default -> false;
        };
    }

    private static List<String> placeholderNames(String text) {
        List<String> names = new ArrayList<>();
        Matcher m = PlaceholderSyntax.PLACEHOLDER.matcher(text);
        while (m.find()) names.add(m.group(2).strip());
        return names;
    }

    /**
     * Skips a heredoc body starting at {@code from}: returns the index of the newline that ends the closing
     * delimiter line (the caller's loop handles that newline itself) and the newlines skipped before it.
     */
    private static int[] skipHeredocBody(String code, int from, String delimiter) {
        int pos = from;
        int lines = 0;
        while (pos < code.length()) {
            int end = endOfLine(code, pos);
            boolean last = code.substring(pos, end).strip().equals(delimiter);
            pos = end < code.length() ? end + 1 : end;
            lines++;
            if (last) break;
        }
        return new int[] {pos > from ? pos - 1 : pos, Math.max(0, lines - 1)};
    }

    private static int endOfLine(String code, int from) {
        int end = code.indexOf('\n', from);
        return end < 0 ? code.length() : end;
    }

    /** A {@code #} starts a comment only at the beginning of a word. */
    private static boolean wordStart(String code, int i) {
        if (i == 0) return true;
        char before = code.charAt(i - 1);
        return Character.isWhitespace(before) || before == ';' || before == '&' || before == '|' || before == '(';
    }
}
