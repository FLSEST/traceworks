package traceworks.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeredocReaderTest {

    private static String reader(String command, String rest) {
        return HeredocReader.sourceReader(command, rest);
    }

    private static String reader(String command) {
        return reader(command, "");
    }

    @Test
    void should_findAnInterpreterThatReadsItsProgramFromStdin() {
        assertThat(reader("python3 -")).isEqualTo("python3");
        assertThat(reader("python3")).isEqualTo("python3");
        assertThat(reader("python3 - \"$TARGET_SLUGS\"")).isEqualTo("python3");
        assertThat(reader("/usr/bin/python3.12 -I -X dev -")).isEqualTo("python3.12");
        assertThat(reader("python -Werror -u")).isEqualTo("python");
        assertThat(reader("node")).isEqualTo("node");
        assertThat(reader("node --input-type=module -")).isEqualTo("node");
        assertThat(reader("ruby -rjson -")).isEqualTo("ruby");
        assertThat(reader("perl -w")).isEqualTo("perl");
        assertThat(reader("php")).isEqualTo("php");
        assertThat(reader("sh")).isEqualTo("sh");
        assertThat(reader("bash -s -- one two")).isEqualTo("bash");
        assertThat(reader("bash -euo pipefail -s")).isEqualTo("bash");
    }

    @Test
    void should_lookPastAssignmentsReservedWordsAndWrappers() {
        assertThat(reader("EFFECTIVE=$EFFECTIVE python3 -")).isEqualTo("python3");
        assertThat(reader("if ! python3 -")).isEqualTo("python3");
        assertThat(reader("env LANG=C timeout 60 python3 -")).isEqualTo("python3");
        assertThat(reader("docker exec -i box python3 -")).isEqualTo("python3");
        assertThat(reader("uv run python -")).isEqualTo("python");
        assertThat(reader("python3 \\\n    -")).as("line continuation").isEqualTo("python3");
    }

    @Test
    void should_followThePipeFromTheHeredocCommand() {
        assertThat(reader("cat", "| python3")).isEqualTo("python3");
        assertThat(reader("cat", "| grep -v '^#' | sh")).isEqualTo("sh");
        assertThat(reader("python3 -", "> out.json 2>&1")).isEqualTo("python3");
        assertThat(reader("python3 - >| out.json")).isEqualTo("python3");
    }

    @Test
    void should_treatTheBodyAsData_whenTheProgramComesFromElsewhere() {
        assertThat(reader("cat")).isNull();
        assertThat(reader("cat > \"$OUT/raw.txt\"")).isNull();
        assertThat(reader("tee report.md")).isNull();
        assertThat(reader("python3 -I bin/backlog.py candidates --source \"$SOURCE\" --max \"$MAX\"")).isNull();
        assertThat(reader("python3 scripts/drill.py ask --topic '{{ topic }}'")).isNull();
        assertThat(reader("python3 -c \"import sys; print(sys.stdin.read())\"")).isNull();
        assertThat(reader("python3 -m json.tool")).isNull();
        assertThat(reader("node -e 'process.stdin.pipe(process.stdout)'")).isNull();
        assertThat(reader("perl -ne 'print if /x/'")).isNull();
        assertThat(reader("php -r 'echo 1;'")).isNull();
        assertThat(reader("bash -c 'cat > f'")).isNull();
        assertThat(reader("bash deploy.sh")).isNull();
        assertThat(reader("uv run tool.py")).isNull();
        assertThat(reader("grep -q python3")).isNull();
        assertThat(reader("\"$PYTHON\" -")).isNull();
    }

    @Test
    void should_stopAtTheEndOfThePipeline() {
        assertThat(reader("cat", "| python3 -c 'import json, sys; json.load(sys.stdin)'")).isNull();
        assertThat(reader("cat", "|| python3")).isNull();
        assertThat(reader("cat", "> out.txt; python3")).isNull();
        assertThat(reader("cat", "> out.txt && sh")).isNull();
        assertThat(reader("cat", "| grep -qiE \"error|failed\"")).isNull();
    }

    /** {@code --} ends the options: what follows is the program, or {@code -} for stdin. */
    @Test
    void should_readTheWordAfterTheEndOfOptions_asTheProgram() {
        assertThat(reader("python3 --")).isEqualTo("python3");
        assertThat(reader("python3 -- -")).isEqualTo("python3");
        assertThat(reader("python3 -- tool.py")).isNull();
        assertThat(reader("node --eval 'console.log(1)'")).isNull();
        assertThat(reader("node --print 1")).isNull();
    }

    /** {@code -c'…'} brings the program in the same word as the option. */
    @Test
    void should_treatTheBodyAsData_whenTheInlineProgramIsAttachedToItsOption() {
        assertThat(reader("python3 -c'print(1)'")).isNull();
        assertThat(reader("perl -e'print 1'")).isNull();
        assertThat(reader("php -r'echo 1;'")).isNull();
    }

    /**
     * An ampersand ends the pipeline only as a command separator; in {@code &>}, {@code 2>&1} and {@code <&3} it is
     * part of a redirection, and {@code |&} pipes stderr along.
     */
    @Test
    void should_followThePipe_pastARedirectionWithAnAmpersand() {
        assertThat(reader("cat", "&>/dev/null | python3")).isEqualTo("python3");
        assertThat(reader("cat", "2>&1 | python3")).isEqualTo("python3");
        assertThat(reader("cat <&3", "| python3")).isEqualTo("python3");
        assertThat(reader("cat", "|& python3")).isEqualTo("python3");
        assertThat(reader("cat", "| python3 &")).as("in the background").isEqualTo("python3");
    }

    /** A line can end in a pipe or a backslash: the command goes on after the heredoc body, beyond this line. */
    @Test
    void should_readALineThatEndsInAPipeOrABackslash() {
        assertThat(reader("cat", "|")).isNull();
        assertThat(reader("FOO=bar")).as("no command at all").isNull();
        assertThat(reader("python3 -", "\\")).isEqualTo("python3");
    }

    /** An escaped or quoted operator is part of a word, and an unclosed quote runs to the end of the line. */
    @Test
    void should_keepEscapedAndQuotedOperatorsInTheirWord() {
        assertThat(reader("cat", "\\| python3")).isNull();
        assertThat(reader("cat", "| grep \"x\\\"|y\" | sh")).isEqualTo("sh");
        assertThat(reader("python3 'tool.py")).isNull();
    }
}
