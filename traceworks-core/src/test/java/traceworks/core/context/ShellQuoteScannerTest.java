package traceworks.core.context;

import traceworks.core.context.ShellQuoteScanner.Context;
import traceworks.core.context.ShellQuoteScanner.Heredoc;
import traceworks.core.context.ShellQuoteScanner.Placeholder;
import traceworks.core.context.ShellQuoteScanner.Quote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellQuoteScannerTest {

    private static List<Context> contexts(String code) {
        return ShellQuoteScanner.occurrences(code).stream().map(ShellQuoteScanner.Occurrence::context).toList();
    }

    /** Every placeholder of the text has a place, those in comments and heredoc bodies too, in the text's order. */
    @Test
    void occurrences_tellEveryPlace() {
        assertThat(contexts("echo {{ a }} \"{{ b }}\" '{{ c }}' `echo {{ d }}` $(( {{ e }} )) # {{ f }}\n"
                + "cat <<'EOF'\n{{ g }}\nEOF\necho \\{{ h }} \"$(printf '%s' {{ i }})\" x{{ j }}y"))
                .containsExactly(Context.BARE, Context.DOUBLE_QUOTES, Context.SINGLE_QUOTES, Context.BACKTICKS,
                        Context.ARITHMETIC, Context.COMMENT, Context.HEREDOC, Context.UNREAD, Context.BARE,
                        Context.BARE);
        assertThat(ShellQuoteScanner.occurrences("echo {{ a }} {{b}}"))
                .containsExactly(new ShellQuoteScanner.Occurrence("a", 5, Context.BARE, "echo ", " {{b}}", null),
                        new ShellQuoteScanner.Occurrence("b", 13, Context.BARE, "echo {{ a }} ", "", null));
        assertThat(ShellQuoteScanner.occurrences(null)).isEmpty();
    }

    /** In {@code $'…'} a backslash escapes the quote, so the string goes on past it. */
    @Test
    void occurrences_readAnsiQuotesWithTheirBackslash() {
        assertThat(contexts("echo $'it\\'s {{ a }}' {{ b }}"))
                .containsExactly(Context.SINGLE_QUOTES, Context.BARE);
        assertThat(contexts("echo $'\\'' x '{{ a }}'")).containsExactly(Context.SINGLE_QUOTES);
        assertThat(contexts("echo 'a\\' {{ a }}")).as("plain single quotes know no backslash")
                .containsExactly(Context.BARE);
    }

    /** The {@code )} of a case pattern closes no {@code $(}; {@code case} as an argument is a plain word. */
    @Test
    void occurrences_readACaseInsideASubstitution_andCaseAsAWord() {
        assertThat(contexts("X=\"$(case $1 in a) echo \"{{ a }}\";; *) echo {{ b }};; esac)\"; echo {{ c }}"))
                .containsExactly(Context.DOUBLE_QUOTES, Context.BARE, Context.BARE);
        assertThat(contexts("if true; then case x in x) echo '{{ a }}';; esac; fi; echo \"$(echo esac) {{ b }}\""))
                .containsExactly(Context.SINGLE_QUOTES, Context.DOUBLE_QUOTES);
        assertThat(contexts("echo \"$(echo case) {{ a }}\"")).containsExactly(Context.DOUBLE_QUOTES);
    }

    /**
     * A heredoc ends at a line that is its delimiter and nothing else, as for the shell: a delimiter of any word,
     * {@code <<-} with tabs taken off, two heredocs on one line, each with its body.
     */
    @Test
    void occurrences_readHeredocsAsTheShellDoes() {
        assertThat(contexts("cat <<1\n{{ a }}\n1\necho {{ b }}")).containsExactly(Context.HEREDOC, Context.BARE);
        assertThat(contexts("cat <<.E\n{{ a }}\n.E\necho {{ b }}")).containsExactly(Context.HEREDOC, Context.BARE);
        assertThat(contexts("cat <<'E-F'\n{{ a }}\nE-F\necho {{ b }}"))
                .containsExactly(Context.HEREDOC, Context.BARE);
        assertThat(contexts("cat <<EOF\n{{ a }}\n EOF\n{{ b }}\nEOF\necho {{ c }}")).as("an indented delimiter")
                .containsExactly(Context.HEREDOC, Context.HEREDOC, Context.BARE);
        assertThat(contexts("cat <<-EOF\n{{ a }}\n EOF\n{{ b }}\n\tEOF\necho {{ c }}")).as("spaces are no tabs")
                .containsExactly(Context.HEREDOC, Context.HEREDOC, Context.BARE);
        ShellQuoteScanner.Result two = ShellQuoteScanner.read("cat <<A <<'B'\n{{ a }}\nA\n{{ b }}\nB\necho {{ c }}");
        assertThat(two.occurrences()).extracting(ShellQuoteScanner.Occurrence::context)
                .containsExactly(Context.HEREDOC, Context.HEREDOC, Context.BARE);
        assertThat(two.occurrences().get(0).heredoc().quoted()).isFalse();
        assertThat(two.occurrences().get(1).heredoc().quoted()).isTrue();
        assertThat(two.placeholders()).containsExactly(new Placeholder("c", Quote.NONE, 6));
    }

    /** A placeholder whose key holds more than a plain name is text for the shell, its quotes included. */
    @Test
    void occurrences_readAKeyWithAQuoteAsTheTextItIs() {
        assertThat(ShellQuoteScanner.occurrences("echo {{ a'b }} {{ x }} {{ c'd }}"))
                .extracting(ShellQuoteScanner.Occurrence::key, ShellQuoteScanner.Occurrence::context)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("x", Context.SINGLE_QUOTES));
    }

    /** What stands before and after a placeholder in its command, for those who judge the command. */
    @Test
    void occurrences_carryTheCommandUpToThePlaceholder() {
        assertThat(ShellQuoteScanner.occurrences("true; eval \"a {{ x }} b\" c\necho"))
                .containsExactly(new ShellQuoteScanner.Occurrence("x", 14, Context.DOUBLE_QUOTES, " eval \"a ",
                        " b\" c", null));
        assertThat(ShellQuoteScanner.occurrences("list=(\n  {{ x }}\n)").getFirst().before()).as("an array literal")
                .startsWith("=( ");
        assertThat(contexts("(true)# {{ x }}")).as("a comment after a closing paren").containsExactly(Context.COMMENT);
        assertThat(contexts("echo $(( \"{{ x }}\" + 1 ))")).as("quotes keep no arithmetic from evaluating")
                .containsExactly(Context.ARITHMETIC);
    }

    private static List<Placeholder> scan(String code) {
        return ShellQuoteScanner.scan(code);
    }

    @Test
    void should_tellNoneSingleAndDoubleQuotesApart() {
        assertThat(scan("echo '{{ a }}' \"{{ b }}\" {{ c }}")).containsExactly(
                new Placeholder("a", Quote.SINGLE, 1),
                new Placeholder("b", Quote.DOUBLE, 1),
                new Placeholder("c", Quote.NONE, 1));
    }

    @Test
    void should_seeQuotesThatStartFarFromThePlaceholder() {
        assertThat(scan("echo 'CHAIN_OUTPUT={{ previous_chain_output }}'"))
                .containsExactly(new Placeholder("previous_chain_output", Quote.SINGLE, 1));
    }

    @Test
    void should_openAFreshContext_insideCommandSubstitution() {
        assertThat(scan("X=\"$(printf '%s' {{ a }})\"\nY=\"$(printf '%s' '{{ b }}' | head -1)\" {{ c }}"))
                .containsExactly(
                        new Placeholder("a", Quote.NONE, 1),
                        new Placeholder("b", Quote.SINGLE, 2),
                        new Placeholder("c", Quote.NONE, 2));
    }

    @Test
    void should_keepAnApostropheInsideDoubleQuotesLiteral() {
        assertThat(scan("echo \"it's {{ a }}\" {{ b }}")).containsExactly(
                new Placeholder("a", Quote.DOUBLE, 1),
                new Placeholder("b", Quote.NONE, 1));
    }

    @Test
    void should_followAQuoteAcrossLines() {
        assertThat(scan("printf '%s\n{{ a }}' x\necho {{ b }}")).containsExactly(
                new Placeholder("a", Quote.SINGLE, 2),
                new Placeholder("b", Quote.NONE, 3));
    }

    @Test
    void should_skipCommentLines_butNotDollarHash() {
        assertThat(scan("# '{{ a }}'\necho $# '{{ b }}' # '{{ c }}'")).containsExactly(
                new Placeholder("b", Quote.SINGLE, 2));
    }

    @Test
    void should_skipHeredocBodies_inEveryQuotingForm() {
        String code = """
                python3 - <<'PY' > out
                print('{{ a }}')
                PY
                cat <<-EOF
                \tx "{{ b }}"
                \tEOF
                cat <<\\STEP | grep x
                '{{ c }}'
                STEP
                echo '{{ d }}'""";
        assertThat(scan(code)).containsExactly(new Placeholder("d", Quote.SINGLE, 10));
    }

    @Test
    void should_notTakeAHereStringOrAShiftForAHeredoc() {
        assertThat(scan("grep x <<< '{{ a }}'\necho $(( 1 << 2 )) '{{ b }}'\necho '{{ c }}'")).containsExactly(
                new Placeholder("a", Quote.SINGLE, 1),
                new Placeholder("b", Quote.SINGLE, 2),
                new Placeholder("c", Quote.SINGLE, 3));
    }

    @Test
    void should_treatAnEscapedQuoteAsText() {
        assertThat(scan("echo \\'{{ a }} \"\\\"{{ b }}\""))
                .containsExactly(new Placeholder("a", Quote.NONE, 1), new Placeholder("b", Quote.DOUBLE, 1));
    }

    @Test
    void should_notReadTheUnsetCheckPatternAsAPlaceholder() {
        assertThat(scan("case \"$X\" in '{{ '*) X=\"$PREV\";; esac\necho {{ b }}"))
                .containsExactly(new Placeholder("b", Quote.NONE, 2));
    }

    @Test
    void should_reportEachHeredocWithItsCommandQuotingAndBodyPlaceholders() {
        String code = """
                mkdir -p out && python3 - "$ARG" <<'PY' > out/x.json
                x = '''{{ previous_output }}'''
                PY
                BODY=$(cat <<EOF
                {{ a }} and {{ b }}
                EOF
                )
                if cat <<\\STEP | grep -q x; then :; fi
                {{ c }}
                STEP
                python3 << "PYEOF"
                PYEOF
                echo {{ d }}""";
        ShellQuoteScanner.Result result = ShellQuoteScanner.read(code);

        assertThat(result.heredocs()).containsExactly(
                new Heredoc("PY", true, "python3 - \"$ARG\"", "> out/x.json", List.of("previous_output")),
                new Heredoc("EOF", false, "cat", "", List.of("a", "b")),
                new Heredoc("STEP", true, "if cat", "| grep -q x; then :; fi", List.of("c")),
                new Heredoc("PYEOF", true, "python3", "", List.of()));
        assertThat(result.placeholders()).containsExactly(new Placeholder("d", Quote.NONE, 13));
    }

    @Test
    void should_takeTheCommandFromTheStartOfItsLine_acrossAMultiLineQuote() {
        String code = "set -e\npython3 -c \"\nimport sys\nprint(sys.stdin.read())\n\" <<'IN'\n{{ x }}\nIN";

        assertThat(ShellQuoteScanner.read(code).heredocs()).singleElement().satisfies(h -> {
            assertThat(h.command()).isEqualTo("python3 -c \"\nimport sys\nprint(sys.stdin.read())\n\"");
            assertThat(h.placeholders()).containsExactly("x");
        });
    }

    @Test
    void should_notTakeAQuotedOperatorForAHeredoc() {
        assertThat(ShellQuoteScanner.read("echo \"<<EOF\" '<<X'\necho {{ a }}").heredocs()).isEmpty();
    }

    /** A shift inside $(( ... )) or (( ... )) is no heredoc and must not swallow the lines after it. */
    @Test
    void should_notStartAHeredocInsideArithmetic() {
        String code = "echo $(( 1 << n ))\necho '{{ a }}'\n(( x = 1 << SHIFT ))\necho \"{{ b }}\"\n"
                + "y=$((1<<n)) && cat <<EOF\n{{ c }}\nEOF\necho '{{ d }}'";
        ShellQuoteScanner.Result result = ShellQuoteScanner.read(code);

        assertThat(result.heredocs()).containsExactly(new Heredoc("EOF", false, "cat", "", List.of("c")));
        assertThat(result.placeholders()).containsExactly(
                new Placeholder("a", Quote.SINGLE, 2),
                new Placeholder("b", Quote.DOUBLE, 4),
                new Placeholder("d", Quote.SINGLE, 8));
    }

    @Test
    void should_findNothing_inAnEmptyText() {
        assertThat(ShellQuoteScanner.read("").heredocs()).isEmpty();
        assertThat(scan("")).isEmpty();
        assertThat(scan(null)).isEmpty();
    }

    /** A heredoc whose delimiter never comes runs to the end of the text, its last placeholder included. */
    @Test
    void should_readAnUnclosedHeredocToTheEnd() {
        assertThat(ShellQuoteScanner.read("cat <<EOF\nx\n{{ a }}").heredocs()).singleElement()
                .satisfies(h -> assertThat(h.placeholders()).containsExactly("a"));
    }

    /** A text that ends right after {@code $(} is cut off, not a reason to fail. */
    @Test
    void should_readATextThatEndsInAnOpenSubstitution() {
        assertThat(scan("echo {{ a }} $(")).containsExactly(new Placeholder("a", Quote.NONE, 1));
    }

    /** A heredoc's command starts after the separator, the pipe or the paren before it, and keeps its variables. */
    @Test
    void should_takeTheHeredocCommandFromTheStartOfItsSimpleCommand() {
        String code = "printf x | python3 - <<'A'\nA\necho start\n(python3 - <<'B'\nB\n)\n$PY - $ARGS <<'C'\nC";

        assertThat(ShellQuoteScanner.read(code).heredocs()).extracting(Heredoc::command)
                .containsExactly("python3 -", "python3 -", "$PY - $ARGS");
    }

    /** A subshell is no arithmetic: a heredoc in it is one. */
    @Test
    void should_findAHeredocInASubshell() {
        String code = "(cd out && python3 - <<'PY'\nprint('{{ x }}')\nPY\n)";

        assertThat(ShellQuoteScanner.read(code).heredocs()).singleElement().satisfies(h -> {
            assertThat(h.command()).isEqualTo("python3 -");
            assertThat(h.placeholders()).containsExactly("x");
        });
    }

    /** The paren of a subshell inside {@code $(…)} closes that subshell, not the substitution. */
    @Test
    void should_keepTheContextOfASubstitution_pastASubshellInIt() {
        assertThat(scan("echo \"$( (cd /tmp) && printf '%s' {{ x }} )\"")).containsExactly(
                new Placeholder("x", Quote.NONE, 1));
    }

    /** A shift right after a closing paren is still a shift inside arithmetic. */
    @Test
    void should_notStartAHeredoc_atAShiftAfterAParenInArithmetic() {
        String code = "echo $(( (x+1)<<SHIFT ))\necho '{{ a }}'";
        ShellQuoteScanner.Result result = ShellQuoteScanner.read(code);

        assertThat(result.heredocs()).isEmpty();
        assertThat(result.placeholders()).containsExactly(new Placeholder("a", Quote.SINGLE, 2));
    }

    @Test
    void should_keepARedirectionAmpersandInTheHeredocCommand() {
        String code = "python3 - 2>&1 <<'PY'\n{{ x }}\nPY\npython3 - &>out <<'PY'\n{{ y }}\nPY\nls >&2; cat <<'B'\nB";

        assertThat(ShellQuoteScanner.read(code).heredocs()).extracting(Heredoc::command)
                .containsExactly("python3 - 2>&1", "python3 - &>out", "cat");
    }
}
