package traceworks.core.context;

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
 * command reads it, {@link #read} reports separately.
 *
 * <p>Two readers share it, so both see a shell text the same way: the analyzer ({@code core.analyzer.QuotingRules}),
 * which judges a workflow before it runs, and the resolver of a run ({@link TemplateResolver}), which asks through
 * {@link #occurrences} where each placeholder stands ({@link ShellValuePlace}). It is no shell and never will read
 * every text as bash does. Nothing depends on that: the run puts no value it has to protect into the text, only a
 * reference to a variable, and the place this reader names decides the form of the reference. A place it gets wrong
 * costs a value that is split into words or printed as a reference, never a command.
 *
 * <p>Only a placeholder with a plain key (letters, digits, {@code _}, {@code .}, {@code -} and spaces) is read as
 * one. Any other stays in the text for the shell, a quote in it included, and is read as the text it is.
 */
public final class ShellQuoteScanner {

    private ShellQuoteScanner() {}

    public enum Quote { NONE, SINGLE, DOUBLE }

    /** One placeholder occurrence: its name as written between the braces, its quoting, its 1-based line. */
    public record Placeholder(String name, Quote quote, int line) {}

    /** Where a placeholder stands in a shell text. */
    public enum Context {
        /** A word of its own or part of one. */
        BARE,
        SINGLE_QUOTES,
        DOUBLE_QUOTES,
        BACKTICKS,
        ARITHMETIC,
        HEREDOC,
        COMMENT,
        /** After a backslash. */
        UNREAD
    }

    /**
     * One placeholder of a shell text, comments and heredoc bodies included.
     *
     * @param key     the placeholder's key exactly as the resolver reads it ({@link PlaceholderSyntax#PLACEHOLDER})
     * @param start   where it starts in the text
     * @param before  the text of its command up to it: from the start of the simple command it stands in; empty in
     *                a comment and a heredoc body
     * @param after   the text after it up to the end of its line
     * @param heredoc the heredoc whose body it stands in; {@code null} elsewhere
     */
    public record Occurrence(String key, int start, Context context, String before, String after, Heredoc heredoc) {}

    /**
     * One heredoc: its delimiter, whether the delimiter is quoted (then the shell leaves the body alone), the
     * command the operator belongs to (from the start of that command up to the operator), the rest of the
     * operator's line (redirections, a pipe into the next command) and the placeholder names in the body.
     */
    public record Heredoc(String delimiter, boolean quoted, String command, String rest, List<String> placeholders) {}

    /**
     * What one pass finds: the placeholders outside heredoc bodies and comments, the heredocs, and every placeholder
     * of the text with the place it stands in.
     */
    public record Result(List<Placeholder> placeholders, List<Heredoc> heredocs, List<Occurrence> occurrences) {}

    /**
     * A heredoc operator with its delimiter in all its quoting forms ({@code <<EOF}, {@code <<-'EOF'},
     * {@code <<"EOF"}, {@code <<\EOF}); the delimiter is any word ({@code <<1}, {@code <<.E}, {@code <<'E-F'}).
     */
    private static final Pattern HEREDOC =
            Pattern.compile("<<(-?)[ \\t]*(['\"]?)(\\\\?)([^\\s'\"\\\\<>|&;()`$]+)\\2");

    /** The keys the pass reads as a placeholder; any other is text for the shell. */
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_. -]+");

    /** A heredoc whose operator the pass has read and whose body starts after the line. */
    private record Pending(Heredoc heredoc, boolean stripTabs) {}

    /**
     * Quoting context of one nesting level: the top level, the inside of a {@code $(…)}, or arithmetic
     * ({@code $(( … ))}, {@code (( … ))}), where {@code <<} is a shift and never a heredoc; {@code commandStart}
     * is where its current simple command began.
     */
    private static final class Frame {
        Quote quote = Quote.NONE;
        /** The single quotes are {@code $'…'}, in which a backslash escapes the next character. */
        boolean ansi;
        int parens;
        /** Per open {@code (}: whether it opens an array literal ({@code name=(}), whose words are values. */
        final Deque<Boolean> arrays = new ArrayDeque<>();
        /** Open {@code case} statements: the {@code )} of a pattern closes nothing. */
        int cases;
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

    public static List<Placeholder> scan(String code) {
        return read(code).placeholders();
    }

    /** Every placeholder of {@code code}, in the order of the text, with the place it stands in. */
    public static List<Occurrence> occurrences(String code) {
        return read(code).occurrences();
    }

    public static Result read(String code) {
        List<Placeholder> found = new ArrayList<>();
        List<Heredoc> heredocs = new ArrayList<>();
        if (code == null || code.isEmpty()) return new Result(found, heredocs, List.of());
        Map<Integer, MatchResult> placeholders = placeholderStarts(code);
        Map<Integer, Occurrence> occurrences = new HashMap<>();
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(0, false));
        Deque<Pending> pending = new ArrayDeque<>();
        boolean backticks = false;
        int line = 1;
        int n = code.length();
        for (int i = 0; i < n; i++) {
            Frame frame = frames.peek();
            MatchResult placeholder = placeholders.get(i);
            if (placeholder != null) {
                found.add(new Placeholder(placeholder.group(2).strip(), frame.quote, line));
                // In an array literal the words are values, on whichever line: no command starts there
                boolean inArray = Boolean.TRUE.equals(frame.arrays.peek());
                occurrences.put(i, new Occurrence(placeholder.group(2), i, context(frame, backticks),
                        (inArray ? "=( " : "") + code.substring(Math.min(frame.commandStart, i), i),
                        code.substring(placeholder.end(), endOfLine(code, placeholder.end())), null));
                i = placeholder.end() - 1;
                continue;
            }
            char c = code.charAt(i);
            if (c == '\n') {
                line++;
                if (frame.quote != Quote.NONE) continue;
                frame.commandStart = i + 1;
                // The bodies of the line's heredocs follow one another, each up to its own delimiter line
                int bodyStart = i + 1;
                while (!pending.isEmpty()) {
                    Pending first = pending.poll();
                    int[] skipped = skipHeredocBody(code, bodyStart, first.heredoc().delimiter(), first.stripTabs());
                    int bodyEnd = Math.min(code.length(), skipped[0] + 1);
                    Heredoc heredoc = new Heredoc(first.heredoc().delimiter(), first.heredoc().quoted(),
                            first.heredoc().command(), first.heredoc().rest(),
                            placeholderNames(code.substring(Math.min(bodyStart, bodyEnd), bodyEnd)));
                    heredocs.add(heredoc);
                    mark(placeholders, occurrences, bodyStart, bodyEnd, Context.HEREDOC, heredoc, code);
                    line += skipped[1];
                    if (pending.isEmpty()) {
                        i = skipped[0] - 1;   // the loop handles the newline of the delimiter line itself
                    } else {
                        bodyStart = Math.min(n, skipped[0] + 1);
                        line++;
                    }
                }
                continue;
            }
            char next = i + 1 < n ? code.charAt(i + 1) : '\0';
            switch (frame.quote) {
                case SINGLE -> {
                    if (c == '\\' && frame.ansi) i++;
                    else if (c == '\'') { frame.quote = Quote.NONE; frame.ansi = false; }
                }
                case DOUBLE -> {
                    if (c == '\\') i++;
                    else if (c == '"') frame.quote = Quote.NONE;
                    else if (c == '`') backticks = !backticks;
                    else if (c == '$' && next == '(') { frames.push(Frame.substitution(code, i)); i++; }
                }
                case NONE -> {
                    if (c == '\\') i++;
                    else if (c == '\'') frame.quote = Quote.SINGLE;
                    else if (c == '"') frame.quote = Quote.DOUBLE;
                    else if (c == '`') backticks = !backticks;
                    else if (c == '$' && next == '\'') { frame.quote = Quote.SINGLE; frame.ansi = true; i++; }
                    else if (c == '#' && wordStart(code, i)) {
                        mark(placeholders, occurrences, i, endOfLine(code, i), Context.COMMENT, null, code);
                        i = endOfLine(code, i) - 1;
                    }
                    else if (c == '$' && next == '(') { frames.push(Frame.substitution(code, i)); i++; }
                    else if (c == '(' && next == '(' && !frame.arithmetic) { frames.push(Frame.arithmeticCommand(i)); i++; }
                    else if (c == '(') {
                        frame.parens++;
                        frame.arrays.push(i > 0 && code.charAt(i - 1) == '=');
                        frame.commandStart = i + 1;
                    }
                    else if (separator(code, i)) frame.commandStart = i + 1;
                    else if (c == 'c' && keyword(code, frame, i, "case")) { frame.cases++; i += 3; }
                    else if (c == 'e' && frame.cases > 0 && keyword(code, frame, i, "esac")) { frame.cases--; i += 3; }
                    else if (c == ')') {
                        if (frame.parens > 0) { frame.parens--; frame.arrays.poll(); }
                        else if (frame.cases > 0) frame.commandStart = i + 1;   // the end of a case pattern
                        else if (frames.size() > 1) frames.pop();
                    } else if (c == '<' && next == '<' && frame.arithmetic) {
                        i++;   // a shift
                    } else if (c == '<' && next == '<') {
                        Matcher m = HEREDOC.matcher(code).region(i, endOfLine(code, i));
                        if (m.lookingAt()) {
                            boolean quoted = !m.group(2).isEmpty() || !m.group(3).isEmpty();
                            pending.add(new Pending(new Heredoc(m.group(4), quoted,
                                    code.substring(frame.commandStart, i).strip(),
                                    code.substring(m.end(), endOfLine(code, m.end())).strip(), List.of()),
                                    !m.group(1).isEmpty()));
                            i = m.end() - 1;
                        } else {
                            i++;   // a here-string (<<<) or a shift: nothing to remember
                        }
                    }
                }
            }
        }
        List<Occurrence> inOrder = new ArrayList<>();
        placeholders.keySet().stream().sorted().forEach(start -> {
            Occurrence occurrence = occurrences.get(start);
            inOrder.add(occurrence != null ? occurrence
                    : new Occurrence(placeholders.get(start).group(2), start, Context.UNREAD, "", "", null));
        });
        return new Result(found, heredocs, inOrder);
    }

    /** The place of a placeholder the pass stands on: the innermost quoting decides, backticks first. */
    private static Context context(Frame frame, boolean backticks) {
        if (backticks) return Context.BACKTICKS;
        if (frame.arithmetic) return Context.ARITHMETIC;   // quotes do not keep arithmetic from evaluating
        return switch (frame.quote) {
            case SINGLE -> Context.SINGLE_QUOTES;
            case DOUBLE -> Context.DOUBLE_QUOTES;
            case NONE -> Context.BARE;
        };
    }

    /** Gives every placeholder that starts in {@code [from, to)} the place {@code context}. */
    private static void mark(Map<Integer, MatchResult> placeholders, Map<Integer, Occurrence> occurrences, int from,
                             int to, Context context, Heredoc heredoc, String code) {
        placeholders.forEach((start, match) -> {
            if (start >= from && start < to) {
                occurrences.put(start, new Occurrence(match.group(2), start, context, "",
                        code.substring(match.end(), endOfLine(code, match.end())), heredoc));
            }
        });
    }

    /** The reserved words after which a command starts: {@code then case …}. */
    private static final java.util.Set<String> BEFORE_A_COMMAND = java.util.Set.of("then", "do", "else", "{", "!");

    /**
     * Whether the reserved word {@code word} stands at {@code i} where the shell reads it as one: a word of its own
     * at the start of a command. As an argument ({@code echo case}) it is a plain word.
     */
    private static boolean keyword(String code, Frame frame, int i, String word) {
        if (!code.startsWith(word, i) || !wordStart(code, i)) return false;
        int end = i + word.length();
        if (end < code.length()) {
            char after = code.charAt(end);
            if (!Character.isWhitespace(after) && after != ';' && after != ')' && after != '&' && after != '|') {
                return false;
            }
        }
        String before = frame.commandStart <= i ? code.substring(frame.commandStart, i).strip() : "";
        return before.isEmpty() || BEFORE_A_COMMAND.contains(before);
    }

    private static Map<Integer, MatchResult> placeholderStarts(String code) {
        Map<Integer, MatchResult> starts = new HashMap<>();
        Matcher m = PlaceholderSyntax.PLACEHOLDER.matcher(code);
        while (m.find()) {
            if (PLAIN_KEY.matcher(m.group(2)).matches()) starts.put(m.start(), m.toMatchResult());
        }
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
        while (m.find()) {
            if (PLAIN_KEY.matcher(m.group(2)).matches()) names.add(m.group(2).strip());
        }
        return names;
    }

    /**
     * Skips a heredoc body starting at {@code from}: returns the index of the newline that ends the closing
     * delimiter line (the caller's loop handles that newline itself) and the newlines skipped before it. The body
     * ends at a line that is the delimiter and nothing else, as for the shell; {@code <<-} takes leading tabs off
     * the line first. A line with spaces around the delimiter is body.
     */
    private static int[] skipHeredocBody(String code, int from, String delimiter, boolean stripTabs) {
        int pos = from;
        int lines = 0;
        while (pos < code.length()) {
            int end = endOfLine(code, pos);
            String text = code.substring(pos, end);
            if (stripTabs) text = text.replaceFirst("^\\t+", "");
            boolean last = text.equals(delimiter);
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
        return Character.isWhitespace(before) || before == ';' || before == '&' || before == '|' || before == '('
                || before == ')';
    }
}
