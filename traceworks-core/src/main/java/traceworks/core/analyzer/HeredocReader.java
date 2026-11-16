package traceworks.core.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tells whether the body of a heredoc is program source: the command it belongs to, or one it pipes
 * into, is an interpreter that reads its program from standard input ({@code python3 - <<'PY'}, {@code bash -s},
 * a bare {@code node}). With a script file or inline code ({@code python3 tool.py}, {@code python3 -c '…'}) the
 * body is that program's input — data, as for {@code cat}.
 *
 * <p>A heuristic over the words of the command, like {@link ShellQuoteScanner}: an option it does not know costs a
 * missed finding ({@code python3 -X dev script.py} reads as data either way), never a false one on a script file.
 */
final class HeredocReader {

    private HeredocReader() {}

    /** Interpreters by family: the option letters that bring the program along, read it from stdin, take a value. */
    private enum Family {
        PYTHON("(?:python|pypy)[0-9.]*", "cm", "", "XW"),
        NODE("node|nodejs", "ep", "", "r"),
        RUBY("ruby", "e", "", "rICE"),
        PERL("perl[0-9.]*", "eE", "", "IMm"),
        PHP("php[0-9.]*", "rfBRFE", "", "cdz"),
        SHELL("bash|sh|zsh|dash|ksh|mksh", "c", "s", "oO");

        private final Pattern name;
        private final String code;
        private final String stdin;
        private final String withValue;

        Family(String name, String code, String stdin, String withValue) {
            this.name = Pattern.compile(name);
            this.code = code;
            this.stdin = stdin;
            this.withValue = withValue;
        }

        static Family of(String word) {
            for (Family family : values()) {
                if (family.name.matcher(word).matches()) return family;
            }
            return null;
        }

        /** With no program among the arguments, or {@code -}, the interpreter reads it from stdin. */
        boolean readsStdin(List<String> args) {
            for (int a = 0; a < args.size(); a++) {
                String arg = args.get(a);
                if (arg.equals("-")) return true;
                if (arg.equals("--")) return a + 1 >= args.size() || args.get(a + 1).equals("-");
                if (arg.startsWith("--")) {
                    if (arg.startsWith("--eval") || arg.startsWith("--print")) return false;
                    continue;
                }
                if (!arg.startsWith("-") || arg.length() == 1) return false;   // a script file
                for (int p = 1; p < arg.length(); p++) {
                    char flag = arg.charAt(p);
                    if (code.indexOf(flag) >= 0) return false;
                    if (stdin.indexOf(flag) >= 0) return true;
                    if (withValue.indexOf(flag) >= 0) {
                        if (p == arg.length() - 1) a++;   // the value is the next word, else it is attached
                        break;
                    }
                }
            }
            return true;
        }
    }

    /** Reserved words and negation in front of a command. */
    private static final Set<String> RESERVED = Set.of("!", "{", "(", "if", "then", "elif", "else", "do", "while", "until");

    /** Commands that start another one given among their words ({@code env X=1 python3}, {@code docker exec -i c sh}). */
    private static final Set<String> WRAPPERS = Set.of("env", "sudo", "doas", "exec", "command", "nohup", "nice", "ionice",
            "time", "timeout", "stdbuf", "setsid", "flock", "xvfb-run", "uv", "uvx", "poetry", "pipenv", "pdm", "conda",
            "docker", "podman", "kubectl", "ssh");

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\+?=.*", Pattern.DOTALL);

    /** A redirection on its own ({@code >}, {@code 2>>}, {@code &>}) — its target is the next word. */
    private static final Pattern REDIRECT_ALONE = Pattern.compile("(?:\\d*|&)(?:>>?|<|>\\||<>|>&|<&)");
    private static final Pattern REDIRECT = Pattern.compile("(?:\\d*|&)[<>].*", Pattern.DOTALL);

    /**
     * The interpreter that reads the heredoc body as its program, or {@code null} when the body is data.
     *
     * @param command the command the heredoc operator belongs to, up to the operator
     * @param rest    the rest of the operator's line: more words of the command, a pipe into the next one
     */
    static String sourceReader(String command, String rest) {
        List<List<String>> pipeline = pipeline(command + " " + rest);
        for (List<String> words : pipeline) {
            String reader = stdinProgram(words);
            if (reader != null) return reader;
        }
        return null;
    }

    /** The interpreter of one simple command if it reads its program from stdin. */
    private static String stdinProgram(List<String> words) {
        int k = 0;
        while (k < words.size() && (RESERVED.contains(words.get(k)) || ASSIGNMENT.matcher(words.get(k)).matches())) k++;
        if (k >= words.size()) return null;
        String name = basename(words.get(k));
        if (WRAPPERS.contains(name)) {
            for (int j = k + 1; j < words.size(); j++) {
                if (Family.of(basename(words.get(j))) != null) {
                    k = j;
                    name = basename(words.get(j));
                    break;
                }
            }
        }
        Family family = Family.of(name);
        return family != null && family.readsStdin(words.subList(k + 1, words.size())) ? name : null;
    }

    private static String basename(String word) {
        return word.substring(word.lastIndexOf('/') + 1);
    }

    /**
     * The words of the simple commands joined by {@code |}, without redirections; a {@code ;}, {@code &&},
     * {@code ||} or {@code &} ends the pipeline. Quotes are removed, a quoted operator is a word.
     */
    private static List<List<String>> pipeline(String text) {
        List<List<String>> commands = new ArrayList<>();
        List<String> words = new ArrayList<>();
        commands.add(words);
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        boolean skipNext = false;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : ' ';
            char before = i > 0 ? text.charAt(i - 1) : '\0';
            boolean operator = (c == '|' && before != '>') || c == ';'
                    || (c == '&' && !(i + 1 < n && text.charAt(i + 1) == '>') && before != '>' && before != '<');
            if (Character.isWhitespace(c) || operator) {
                if (inWord) {
                    String done = word.toString();
                    if (skipNext) skipNext = false;
                    else if (REDIRECT_ALONE.matcher(done).matches()) skipNext = true;
                    else if (!REDIRECT.matcher(done).matches()) words.add(done);
                    word.setLength(0);
                    inWord = false;
                }
                if (!operator) continue;
                boolean pipe = c == '|' && !(i + 1 < n && text.charAt(i + 1) == '|');
                if (!pipe) break;
                if (i + 1 < n && text.charAt(i + 1) == '&') i++;   // |& pipes stderr along
                words = new ArrayList<>();
                commands.add(words);
                continue;
            }
            if (c == '\\' && i + 1 < n && text.charAt(i + 1) == '\n') {
                i++;   // a line continuation joins the lines
                continue;
            }
            inWord = true;
            if (c == '\\' && i + 1 < n) {
                word.append(text.charAt(++i));
            } else if (c == '\'' || c == '"') {
                int close = i + 1;
                while (close < n && text.charAt(close) != c) close += c == '"' && text.charAt(close) == '\\' ? 2 : 1;
                word.append(text, i + 1, Math.min(close, n));
                i = Math.min(close, n - 1);   // an unclosed quote runs to the end; the last pass still ends the word
            } else {
                word.append(c);
            }
        }
        return commands;
    }
}
