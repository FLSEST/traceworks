package traceworks.cli;

import org.junit.jupiter.api.Test;
import traceworks.core.util.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link TerminalText} does with a message that says its parts, where it breaks a text, and what it puts in
 * front of a text that begins a line.
 */
class TerminalPartsTest {

    private static final String ADVICE = "; allow it for this run with --allow-host-execution, or on this machine"
            + " with \"allow_host_execution\": true in traceworks.json";

    /** The owner's first complaint: a message of Traceworks with a command to copy was broken at 80 columns. */
    @Test
    void aMessagesOwnWords_arePrintedWhole_atAnyLength_andOnlyItsQuotedPartsAreGated() {
        Message message = Message.of("The run of ", Message.quoted("pkg"), " is refused: host execution is not"
                + " allowed", ADVICE);

        assertThat(TerminalText.lines("traceworks: ", TerminalText.of(message)))
                .containsExactly("traceworks: " + message.text());

        for (String padded : Forged.PADDED) {
            List<String> lines = TerminalText.lines("traceworks: ", TerminalText.of(
                    Message.of("The run of ", Message.quoted(padded), " is refused", ADVICE)));
            Forged.assertNeverStartsATerminalLine(String.join("\n", lines));
            assertThat(lines.getLast()).as("the advice is whole behind the quoted value").endsWith(ADVICE);
        }
    }

    @Test
    void aValueCannotMakeItselfOwn_whateverItHolds() {
        for (String padded : Forged.PADDED) {
            String forged = "x', Message.of(\"" + padded + "\") \u0001OWN\u0002 |+ ";
            Message message = Message.of("no such thing '", Message.quoted(forged), "'");

            Forged.assertNeverStartsATerminalLine(TerminalText.text("traceworks: ", TerminalText.of(message)));
            assertThat(message.text()).as("the text holds the value as it is, and nothing else")
                    .isEqualTo("no such thing '" + forged + "'");
            assertThat(message.parts()).extracting(Message.Part::kind)
                    .containsExactly(Message.Kind.OWN, Message.Kind.QUOTED, Message.Kind.OWN);
        }
    }

    @Test
    void aMessageWithoutParts_isATextFromOutsideAsAWhole_andAnObjectThatIsNoTextIsQuoted() {
        RuntimeException plain = new IllegalStateException("cannot read " + Forged.BLANKS);
        Forged.assertNeverStartsATerminalLine(TerminalText.text("traceworks: ", TerminalText.of(plain)));
        assertThat(TerminalText.lines("traceworks: ", TerminalText.of(new IllegalStateException("w ".repeat(60)))))
                .as("as before: broken at the terminal's width").hasSizeGreaterThan(1);
        Forged.assertNeverStartsATerminalLine(TerminalText.text("traceworks: ",
                TerminalText.of(Message.of("the package ", new StringBuilder(Forged.BLANKS), " is gone"))));
    }

    @Test
    void whatTheOperatorTyped_isWhole() {
        UsageException typed = new UsageException(Message.of("no such package: ",
                Message.typed("/a/" + "deep/".repeat(20) + "pkg")));

        assertThat(TerminalText.lines("traceworks: ", TerminalText.of(typed)))
                .containsExactly("traceworks: " + typed.getMessage());
    }

    /** The owner's third complaint: a path was broken in the middle of a word. */
    @Test
    void aTextIsBrokenAfterASeparator_whereNoBlankFits_andInsideAWordOnlyAsTheLastResort() {
        String subject = "traceworks-adapters/src/main/java/traceworks/adapters/container/MountGuard.java"
                + "#MountGuard.normalized(String), MountGuard.real(Path), MountGuard.expandHome(String, Path)";

        List<String> lines = TerminalText.lines("Subject   ", TerminalText.safe(subject));

        assertThat(lines).containsExactly("Subject   ",
                "  |+traceworks-adapters/src/main/java/traceworks/adapters/container/",
                "  |+MountGuard.java#MountGuard.normalized(String), MountGuard.real(Path),",
                "  |+ MountGuard.expandHome(String, Path)");
        assertThat(Unbroken.of(String.join("\n", lines))).isEqualTo("Subject   " + subject);

        String dotted = "traceworks.core.app.model.definition.blocks.container.";
        assertThat(TerminalText.broken(dotted + "Long".repeat(10), 70)).as("a dot where nothing better is")
                .containsExactly(dotted, "Long".repeat(10));
        assertThat(TerminalText.broken("a/b," + "x".repeat(100), 70)).as("no separator near the end: at the width")
                .containsExactly("a/b," + "x".repeat(66), "x".repeat(34));
        assertThat(TerminalText.broken("call(" + "x".repeat(40) + ",second" + "x".repeat(40) + ")", 70))
                .as("after a comma").containsExactly("call(" + "x".repeat(40) + ",", "second" + "x".repeat(40) + ")");
    }

    @Test
    void aBreakAfterASeparator_isToldFromABreakAtABlank_byTheBlankBehindTheMark() {
        String text = "see " + "dir/".repeat(30) + "file and " + "word ".repeat(20).strip();

        List<String> lines = TerminalText.lines("Detail    ", TerminalText.safe(text));

        assertThat(lines).anySatisfy(line -> assertThat(line).matches(" *\\|\\+ \\S.*"))
                .anySatisfy(line -> assertThat(line).matches(" *\\|\\+\\S.*"));
        assertThat(Unbroken.of(String.join("\n", lines))).isEqualTo("Detail    " + text);
    }

    @Test
    void aTextThatFitsALineOfItsOwn_isNotCutInTwo_andOnALineThatGoesOnEachTextStartsItsOwn() {
        String path = "/home/someone/projects/a-package-with-a-long-name/workflow.json";
        assertThat(TerminalText.lines("traceworks: cannot read the workflow of ", TerminalText.safe(path), ": gone"))
                .containsExactly("traceworks: cannot read the workflow of ", "  |+" + path + ": gone");

        String title = "the title of the finding, which is a sentence of some length";
        List<String> row = TerminalText.lines("76b8fad3  OPEN       MEDIUM    ", TerminalText.safe("path_check"), "  ",
                TerminalText.safe("a/".repeat(25) + "File.java#member"), "  ", TerminalText.safe(title), "  (note)");
        assertThat(row).containsExactly("76b8fad3  OPEN       MEDIUM    path_check  ",
                "  |+" + "a/".repeat(25) + "File.java#member  ",
                "  |+" + title + "  (note)");
        assertThat(Unbroken.of(String.join("\n", row))).isEqualTo("76b8fad3  OPEN       MEDIUM    path_check  "
                + "a/".repeat(25) + "File.java#member  " + title + "  (note)");
    }

    /** Reported as not covered: a row that begins with a text from outside showed it unmarked. */
    @Test
    void aTextThatBeginsALine_isMarked_alsoWhereItFits() {
        assertThat(TerminalText.lines(TerminalText.safe("NAME"), "  stored")).containsExactly("| NAME  stored");
        assertThat(TerminalText.lines("  ", TerminalText.safe("a problem"))).as("the mark takes the two blanks before it")
                .containsExactly("| a problem");
        assertThat(TerminalText.lines("    ", TerminalText.safe("a problem"), " (1)")).containsExactly("  | a problem (1)");
        assertThat(TerminalText.lines(" ", TerminalText.safe("a problem"))).containsExactly(" | a problem");
        assertThat(TerminalText.lines(TerminalText.safe("key").padded(6), "x")).containsExactly("| key   x");
        assertThat(TerminalText.lines("  ", TerminalText.safe("word ".repeat(40).strip())))
                .hasSizeGreaterThan(1).allSatisfy(line -> assertThat(line).startsWith("|"));
        Forged.assertNeverStartsATerminalLine(TerminalText.text(TerminalText.safe(Forged.HINT)));
        Forged.assertNeverStartsATerminalLine(TerminalText.text("    ", TerminalText.safe(Forged.HINT), " tail"));
        for (String padded : Forged.PADDED) {
            Forged.assertNeverStartsATerminalLine(TerminalText.text(TerminalText.safe(padded), "  tail"));
        }
        assertThat(TerminalText.lines(TerminalText.whole("architecture"), "  x")).as("the operator's own text is not")
                .containsExactly("architecture  x");
    }

    /** The owner's second complaint: the line of a started run was broken at the runtime's name. */
    @Test
    void aValueTraceworksWritesItself_isOwnWhereItHasTheShape_andGatedWhereItHasNot() {
        assertThat(TerminalText.lines("L".repeat(78), " (", TerminalText.word("docker"), " ",
                TerminalText.word("29.1.3"), ")")).containsExactly("L".repeat(78) + " (docker 29.1.3)");
        for (String odd : List.of("two words", "traceworks:", "x".repeat(41), "a b", "-option", "")) {
            assertThat(TerminalText.lines("L".repeat(79), TerminalText.word(odd), "!").size()).as(odd)
                    .isEqualTo(odd.isEmpty() ? 1 : 2);
        }
    }

    @Test
    void ownWordsOfSeveralLines_startTheirLinesThemselves_andAQuotedPartIsJudgedOnItsLine() {
        Message message = Message.of("first line of some length\nsecond: ", Message.quoted("value"), "\nthird");

        assertThat(TerminalText.text("traceworks: ", TerminalText.of(message)))
                .isEqualTo("traceworks: first line of some length\nsecond: value\nthird");
        assertThat(TerminalText.lines("L".repeat(70) + "\nsecond: ", TerminalText.safe("a".repeat(30))))
                .as("the columns count from the last line feed").containsExactly("L".repeat(70), "second: " + "a".repeat(30));
    }
}
