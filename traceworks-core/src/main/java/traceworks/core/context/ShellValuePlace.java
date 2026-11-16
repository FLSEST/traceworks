package traceworks.core.context;

import traceworks.core.context.ShellQuoteScanner.Occurrence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the run does with a value it has to protect (text a model wrote, a value from outside the run) at the place
 * its placeholder stands in a shell text. Such a value never becomes part of the text: the run sets a shell variable
 * to it and writes a reference in the placeholder's place ({@link TemplateResolver#shellLine}). The shell does not
 * read the result of a parameter expansion as code, so the value cannot become a command, whatever it holds and
 * whatever stands around it. The place only decides the form of the reference, or refuses the line:
 *
 * <ul>
 *   <li>a bare word: {@code "${NAME}"}, one argument;</li>
 *   <li>inside the author's double quotes, in the body of a heredoc with an unquoted delimiter, in a comment:
 *       {@code ${NAME}};</li>
 *   <li>refused, because a reference would not expand there, or because the shell or the command evaluates the
 *       <em>content</em> of what it is given: single quotes, a heredoc with a quoted delimiter, after a backslash,
 *       backticks, arithmetic ({@code $(( ))}, {@code (( ))}, {@code $[ ]}, {@code let}, an array subscript, a
 *       {@code ${v:offset}}, {@code [[ … -eq … ]]}), a name the shell evaluates ({@code declare}, {@code read},
 *       {@code printf -v}, {@code test -v}, {@code unset}), a command that runs its argument ({@code eval},
 *       {@code source}, {@code trap}, {@code bash -c} and the code argument of the other interpreters), a heredoc
 *       an interpreter reads as its program, and the place of the command's name.</li>
 * </ul>
 *
 * <p>The list of evaluating places is what is cheap to recognise from the words of the command, and it is not
 * complete: {@code xargs}, {@code find -exec}, {@code ssh}, {@code awk} and {@code sed} programs, a file a later
 * command runs. What a command does with a value it is given stays the workflow author's responsibility. A place
 * {@link ShellQuoteScanner} misreads costs a split or unexpanded value, not a command. The analyzer asks the same
 * class, so {@code traceworks check} reports as an error what the run would refuse.
 *
 * @param form    how the reference is written, or {@link Form#REFUSED}
 * @param refusal for a refused place: where the placeholder stands and why that does not do, such as
 *                {@code inside single quotes, where the shell expands no reference}; {@code null} otherwise
 */
public record ShellValuePlace(Form form, String refusal) {

    public enum Form {
        /** {@code "${NAME}"}. */
        QUOTED,
        /** {@code ${NAME}}: the author's quotes, or the heredoc, already make it one piece of text. */
        PLAIN,
        REFUSED
    }

    private static final ShellValuePlace QUOTED = new ShellValuePlace(Form.QUOTED, null);
    private static final ShellValuePlace PLAIN = new ShellValuePlace(Form.PLAIN, null);

    private static final Set<String> RESERVED =
            Set.of("!", "{", "if", "then", "elif", "else", "do", "while", "until", "time");
    private static final Set<String> WRAPPERS = Set.of("env", "sudo", "doas", "exec", "command", "builtin", "nohup",
            "nice", "ionice", "timeout", "stdbuf", "setsid", "xargs");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\[[^]]*])?\\+?=.*",
            Pattern.DOTALL);
    private static final Set<String> RUNS_ITS_ARGUMENT = Set.of("eval", "source", ".", "trap");
    private static final Set<String> DECLARES = Set.of("declare", "typeset", "local", "export", "readonly");
    private static final Pattern ARITHMETIC_TEST = Pattern.compile("(?s).*\\s-(eq|ne|lt|le|gt|ge)\\s.*");
    /** An open {@code name[} or {@code $[} before the placeholder: an array subscript or the old arithmetic. */
    private static final Pattern OPEN_SUBSCRIPT = Pattern.compile("(?s).*(?:[A-Za-z0-9_]|\\$)\\[[^\\]]*$");
    /** An open {@code ${name:} that is no {@code :-}, {@code :=}, {@code :+} or {@code :?}: an offset or a length. */
    private static final Pattern OPEN_OFFSET =
            Pattern.compile("(?s).*\\$\\{[!#]?[A-Za-z_][A-Za-z0-9_\\[\\]@*]*:(?![-+=?])[^}]*$");

    /** The place of {@code occurrence}; {@code null} (a placeholder the reader did not come by) is refused. */
    public static ShellValuePlace of(Occurrence occurrence) {
        if (occurrence == null) {
            return refused("where Traceworks cannot tell how the shell reads it (a key with characters other than"
                    + " letters, digits, _ . - and spaces)");
        }
        return switch (occurrence.context()) {
            case SINGLE_QUOTES -> refused("inside single quotes, where the shell expands no reference");
            case UNREAD -> refused("after a backslash, where the shell expands no reference");
            case BACKTICKS -> refused("inside backticks, which the shell reads twice (write $( … ) instead)");
            case ARITHMETIC -> refused("inside an arithmetic expression, which evaluates the value");
            case COMMENT -> PLAIN;
            case HEREDOC -> heredoc(occurrence);
            case DOUBLE_QUOTES -> command(occurrence, PLAIN);
            case BARE -> command(occurrence, QUOTED);
        };
    }

    private static ShellValuePlace refused(String why) {
        return new ShellValuePlace(Form.REFUSED, why);
    }

    private static ShellValuePlace heredoc(Occurrence occurrence) {
        ShellQuoteScanner.Heredoc heredoc = occurrence.heredoc();
        if (heredoc == null) return PLAIN;
        if (heredoc.quoted()) {
            return refused("in the body of a heredoc with a quoted delimiter (<<'" + heredoc.delimiter()
                    + "'), where the shell expands no reference (leave the delimiter unquoted)");
        }
        String reader = HeredocReader.sourceReader(heredoc.command(), heredoc.rest());
        return reader == null ? PLAIN
                : refused("in the body of a heredoc that " + reader + " reads as its program, so the value would be"
                + " program text");
    }

    /** A place on a command line: refused where the command or the shell evaluates what it is given. */
    private static ShellValuePlace command(Occurrence occurrence, ShellValuePlace otherwise) {
        String before = occurrence.before();
        if (OPEN_SUBSCRIPT.matcher(before).matches()) {
            return refused("in an array subscript or $[ … ], which evaluates the value as arithmetic");
        }
        if (OPEN_OFFSET.matcher(before).matches()) {
            return refused("in the offset of a ${name:offset}, which evaluates the value as arithmetic");
        }
        Words words = Words.of(before);
        List<String> command = new ArrayList<>(words.complete());
        while (!command.isEmpty() && (RESERVED.contains(command.getFirst()) || WRAPPERS.contains(basename(command.getFirst()))
                || ASSIGNMENT.matcher(command.getFirst()).matches() || command.getFirst().startsWith("-")
                || command.getFirst().matches("\\d+(\\.\\d+)?[smhd]?"))) {
            command.removeFirst();
        }
        boolean startsWord = words.current().isEmpty();
        if (command.isEmpty()) {
            return startsWord ? refused("where the command's name stands, so the value would choose what runs")
                    : otherwise;
        }
        String name = basename(command.getFirst());
        List<String> arguments = command.subList(1, command.size());
        String last = command.getLast();
        if (RUNS_ITS_ARGUMENT.contains(name)) {
            return refused("in an argument of " + name + ", which runs its argument as shell code");
        }
        if (name.equals("let")) {
            return refused("in an argument of let, which evaluates the value as arithmetic");
        }
        if (name.equals("unset") || (name.equals("read") && !before.contains("<<<"))
                || ((name.equals("mapfile") || name.equals("readarray")) && startsWord)) {
            return refused("in an argument of " + name + ", which evaluates a name it is given");
        }
        if (DECLARES.contains(name) && (startsWord
                || arguments.stream().anyMatch(word -> word.matches("[-+][A-Za-z]*[in][A-Za-z]*")))) {
            return refused("in an argument of " + name + ", which evaluates a name, and with -i or -n a value, it"
                    + " is given");
        }
        if (last.equals("-v") && command.size() > 1 && Set.of("printf", "test", "[", "[[").contains(name)) {
            return refused("after " + name + " -v, which evaluates the name it is given");
        }
        if (name.equals("[[") && ARITHMETIC_TEST.matcher(" " + before + " x " + occurrence.after()).matches()) {
            return refused("in a [[ … ]] with an arithmetic comparison, which evaluates the value");
        }
        if (command.size() > 1 && HeredocReader.takesCode(name, last)) {
            return refused("in the code argument of " + name + " " + last + ", which runs it");
        }
        return otherwise;
    }

    private static String basename(String word) {
        return word.substring(word.lastIndexOf('/') + 1);
    }

    /**
     * The words of a command up to a placeholder: those that are complete, quotes taken off, and the part of the
     * word the placeholder stands in ({@code current}, without quote characters; empty when the placeholder starts
     * the word).
     */
    private record Words(List<String> complete, String current) {

        static Words of(String text) {
            List<String> complete = new ArrayList<>();
            StringBuilder word = new StringBuilder();
            boolean inWord = false;
            char quote = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                    else word.append(c);
                } else if (c == '\'' || c == '"') {
                    quote = c;
                    inWord = true;
                } else if (Character.isWhitespace(c)) {
                    if (inWord) complete.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                } else {
                    word.append(c);
                    inWord = true;
                }
            }
            return new Words(complete, word.toString());
        }
    }
}
