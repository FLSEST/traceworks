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
 * which judges a workflow before it runs, and the resolver of a run ({@link TemplateResolver#resolveShell}), which
 * asks through {@link #occurrences} where each placeholder stands before it inserts a value the shell must take as
 * one argument. For the run a misread must never call a place bare that is not: {@code $'…'} with its backslash,
 * backticks, arithmetic, a {@code case} pattern's {@code )} inside {@code $(…)}, a comment and a placeholder after a
 * backslash are told apart, and a placeholder the pass did not come by is {@link Context#UNREAD}.
 */
public final class ShellQuoteScanner {

    private ShellQuoteScanner() {}

    public enum Quote { NONE, SINGLE, DOUBLE }

    /** One placeholder occurrence: its name as written between the braces, its quoting, its 1-based line. */
    public record Placeholder(String name, Quote quote, int line) {}

    /** Where a placeholder stands in a shell text, as the shell reads the value put there. */
    public enum Context {
        /** A word of its own or part of one: a single-quoted value there is one literal argument. */
        BARE("as a bare word"),
        SINGLE_QUOTES("inside single quotes"),
        DOUBLE_QUOTES("inside double quotes"),
        BACKTICKS("inside backticks"),
        ARITHMETIC("inside an arithmetic expression"),
        HEREDOC("in the body of a heredoc"),
        COMMENT("in a comment"),
        /**
         * How the shell reads it is not known: after a backslash, or anywhere in a text the pass did not leave
         * balanced (an open quote, an open {@code $(}), which it then has not understood.
         */
        UNREAD("where Traceworks cannot tell how the shell reads it (after a backslash, or in shell code with an"
                + " open quote or bracket)");

        private final String words;

        Context(String words) {
            this.words = words;
        }

        /** The place in words, such as {@code inside double quotes}. */
        public String words() {
            return words;
        }
    }

    /**
     * One placeholder of a shell text, comments and heredoc bodies included.
     *
     * @param key   the placeholder's key exactly as the resolver reads it ({@link PlaceholderSyntax#PLACEHOLDER})
     * @param start where it starts in the text
     */
    public record Occurrence(String key, int start, Context context) {}

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

    /** A heredoc delimiter in all its quoting forms ({@code <<EOF}, {@code <<-'EOF'}, {@code <<"EOF"}, {@code <<\EOF}). */
    private static final Pattern HEREDOC = Pattern.compile("<<-?\\s*(['\"]?)(\\\\?)([A-Za-z_][A-Za-z0-9_]*)\\1");

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
        Map<Integer, Context> contexts = new HashMap<>();
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(0, false));
        Heredoc heredoc = null;
        boolean backticks = false;
        int line = 1;
        int n = code.length();
        for (int i = 0; i < n; i++) {
            Frame frame = frames.peek();
            MatchResult placeholder = placeholders.get(i);
            if (placeholder != null) {
                found.add(new Placeholder(placeholder.group(2).strip(), frame.quote, line));
                contexts.put(i, context(frame, backticks));
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
                    int bodyEnd = Math.min(code.length(), skipped[0] + 1);
                    heredocs.add(new Heredoc(heredoc.delimiter(), heredoc.quoted(), heredoc.command(), heredoc.rest(),
                            placeholderNames(code.substring(i + 1, bodyEnd))));
                    mark(placeholders, contexts, i + 1, bodyEnd, Context.HEREDOC);
                    i = skipped[0] - 1;
                    line += skipped[1];
                    heredoc = null;
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
                        mark(placeholders, contexts, i, endOfLine(code, i), Context.COMMENT);
                        i = endOfLine(code, i) - 1;
                    }
                    else if (c == '$' && next == '(') { frames.push(Frame.substitution(code, i)); i++; }
                    else if (c == '(' && next == '(' && !frame.arithmetic) { frames.push(Frame.arithmeticCommand(i)); i++; }
                    else if (c == '(') { frame.parens++; frame.commandStart = i + 1; }
                    else if (separator(code, i)) frame.commandStart = i + 1;
                    else if (c == 'c' && keyword(code, frame, i, "case")) { frame.cases++; i += 3; }
                    else if (c == 'e' && frame.cases > 0 && keyword(code, frame, i, "esac")) { frame.cases--; i += 3; }
                    else if (c == ')') {
                        if (frame.parens > 0) frame.parens--;
                        else if (frame.cases > 0) frame.commandStart = i + 1;   // the end of a case pattern
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
        // A text the pass leaves with something open was not understood: no place in it counts as bare
        Frame last = frames.peek();
        boolean understood = frames.size() == 1 && last.quote == Quote.NONE && !backticks && last.cases == 0
                && last.parens == 0;
        List<Occurrence> occurrences = new ArrayList<>();
        placeholders.keySet().stream().sorted().forEach(start -> {
            Context context = contexts.getOrDefault(start, Context.UNREAD);
            occurrences.add(new Occurrence(placeholders.get(start).group(2), start,
                    context == Context.BARE && !understood ? Context.UNREAD : context));
        });
        return new Result(found, heredocs, occurrences);
    }

    /** The place of a placeholder the pass stands on: the innermost quoting decides, backticks first. */
    private static Context context(Frame frame, boolean backticks) {
        if (backticks) return Context.BACKTICKS;
        return switch (frame.quote) {
            case SINGLE -> Context.SINGLE_QUOTES;
            case DOUBLE -> Context.DOUBLE_QUOTES;
            case NONE -> frame.arithmetic ? Context.ARITHMETIC : Context.BARE;
        };
    }

    /** Gives every placeholder that starts in {@code [from, to)} the place {@code context}. */
    private static void mark(Map<Integer, MatchResult> placeholders, Map<Integer, Context> contexts, int from, int to,
                             Context context) {
        for (Integer start : placeholders.keySet()) {
            if (start >= from && start < to) contexts.put(start, context);
        }
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
