package traceworks.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalTextTest {

    @Test
    void aTextThatStaysOnItsLine_isWrittenIntoIt() {
        assertThat(TerminalText.lines("Rule      ", TerminalText.safe("no-secrets"), " (", 3, ")"))
                .containsExactly("Rule      no-secrets (3)");
        assertThat(TerminalText.lines("  x ", TerminalText.safe("a".repeat(76)))).as("to the last column").hasSize(1);
    }

    @Test
    void aTextWithARunOfBlanks_isWrittenOnMarkedLinesOnly_alsoWhereItFits() {
        assertThat(TerminalText.lines("Rule      ", TerminalText.safe("a  " + Forged.HINT), " (new)"))
                .containsExactly("Rule      ", "  |+a  " + Forged.HINT + " (new)");
        for (String blank : List.of("\u00a0", "\u2007", "\u3000", "\u2800", "\u3164", " ")) {
            assertThat(TerminalText.lines("Rule ", TerminalText.safe("a" + blank + blank + "b"))).as(blank).hasSize(2);
        }
        assertThat(TerminalText.lines("Rule ", TerminalText.safe("a b c"))).as("single blanks are no run").hasSize(1);
    }

    @Test
    void aTextThatIsTooLong_goesOnOnMarkedLines_andWhatFollowsItStaysBehindIt() {
        List<String> lines = TerminalText.lines("    error ", TerminalText.safe("word ".repeat(40).strip()), " (cut)");

        assertThat(lines.getFirst()).startsWith("    error word word").endsWith("word");
        assertThat(lines).hasSizeGreaterThan(2).allSatisfy(line ->
                assertThat(line.length()).isLessThanOrEqualTo(80));
        assertThat(lines.subList(1, lines.size())).allSatisfy(line -> assertThat(line).startsWith("      |+ word"));
        assertThat(lines.getLast()).endsWith("word (cut)");
        assertThat(Unbroken.of(String.join("\n", lines))).as("nothing of the text is lost")
                .isEqualTo("    error " + "word ".repeat(40).strip() + " (cut)");
    }

    @Test
    void aPaddedText_neverStartsATerminalLine_whereverItStands() {
        for (String padded : Forged.PADDED) {
            for (int lead = 0; lead < 170; lead += 7) {
                String out = String.join("\n", TerminalText.lines("L".repeat(lead) + " ", TerminalText.safe(padded)));
                Forged.assertNeverStartsATerminalLine(out);
                String oneLine = String.join("\n", TerminalText.lines("L".repeat(lead) + " ",
                        TerminalText.oneLine("first " + padded), " tail"));
                Forged.assertNeverStartsATerminalLine(oneLine);
            }
        }
    }

    @Test
    void aTextWithoutABlank_isBrokenAtTheWidth_andATextAtTheStartOfATerminalLineIsMoved() {
        List<String> lines = TerminalText.lines("id ", TerminalText.safe("a".repeat(200)));
        assertThat(lines).as("a break inside a word says so").containsExactly("id ", "  |+" + "a".repeat(75),
                "  |+" + "a".repeat(75), "  |+" + "a".repeat(50));
        assertThat(Unbroken.of(String.join("\n", lines))).isEqualTo("id " + "a".repeat(200));

        assertThat(TerminalText.lines("L".repeat(80), TerminalText.safe("short"))).as("own text may be wider; the "
                + "text may not start the terminal's next line").containsExactly("L".repeat(80), "  |+short");
        String path = "/home/someone/" + "deep/".repeat(20) + "run";
        assertThat(Unbroken.of(TerminalText.text("traceworks: cannot read ", TerminalText.safe(path), ": gone")))
                .as("a path that is broken is put together again as it was")
                .isEqualTo("traceworks: cannot read " + path + ": gone");
        assertThat(TerminalText.lines("L".repeat(100) + " ", TerminalText.safe("short")))
                .containsExactly("L".repeat(100) + " short");
    }

    /**
     * A text from outside that begins its line, behind an indent or none, is broken into pieces that leave every
     * line within the terminal: a piece wider than that would be broken by the terminal onto a line without a mark.
     */
    @Test
    void aTextThatBeginsItsLine_isBrokenWithinTheTerminal_behindAnIndentToo() {
        for (String lead : List.of("", "    ")) {
            for (String text : List.of("a".repeat(200), "word ".repeat(40), Forged.WORD.repeat(12))) {
                List<String> lines = TerminalText.lines(lead, TerminalText.safe(text));

                assertThat(lines).as("%s behind %d blanks", text, lead.length()).hasSizeGreaterThan(2)
                        .allSatisfy(line -> assertThat(TerminalText.columns(line)).as(line).isLessThanOrEqualTo(80))
                        .allSatisfy(line -> assertThat(line.strip()).startsWith("|"));
                assertThat(Unbroken.of(String.join("\n", lines))).as("nothing of the text is lost")
                        .isEqualTo(lead.substring(Math.min(lead.length(), 2)) + "| " + text.strip());
            }
            Forged.assertNeverStartsATerminalLine(String.join("\n",
                    TerminalText.lines(lead, TerminalText.safe(Forged.WORD.repeat(12)))));
        }
    }

    /**
     * A quoted value stands in a command only where it lies on one terminal line and not at the start of one, and
     * holds no run of blanks.
     */
    @Test
    void inline_refusesAValueAtTheStartOfATerminalLine_acrossOne_orWithARunOfBlanks() {
        assertThat(TerminalText.inline("x".repeat(77), "a", "'a'")).as("to the last column").isTrue();
        assertThat(TerminalText.inline("x".repeat(81), "a", "'a'")).as("on the next terminal line").isTrue();
        assertThat(TerminalText.inline("x", "a b", "'a b'")).as("single blanks").isTrue();

        assertThat(TerminalText.inline("x".repeat(80), "a", "'a'")).as("at the start of a terminal line").isFalse();
        assertThat(TerminalText.inline("x".repeat(160), "a", "'a'")).as("at the start of the third").isFalse();
        assertThat(TerminalText.inline("x".repeat(78), "a", "'a'")).as("across a terminal line").isFalse();
        assertThat(TerminalText.inline("x", "a  b", "'a  b'")).as("a run of blanks").isFalse();
    }

    @Test
    void wideCharacters_countTwoColumns() {
        List<String> lines = TerminalText.lines("t ", TerminalText.safe("\u4e2d".repeat(60)));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).isEqualTo("  |+" + "\u4e2d".repeat(37));
    }

    @Test
    void theOperatorsText_staysWhole_andAComposedLineKeepsItsBlanks() {
        String path = "/home/someone/" + "deep/".repeat(30) + "run";
        assertThat(TerminalText.lines("  record  ", TerminalText.whole(path))).containsExactly("  record  " + path);
        assertThat(TerminalText.lines(TerminalText.composed("  1   build      shell")))
                .containsExactly("  1   build      shell");
        List<String> composed = TerminalText.lines(TerminalText.composed("  1   " + Forged.BLANKS));
        assertThat(composed).hasSizeGreaterThan(1);
        Forged.assertNeverStartsATerminalLine(String.join("\n", composed));
    }

    @Test
    void aPaddedColumn_isFilledWithBlanks_andNestedPartsAreTakenInOrder() {
        assertThat(TerminalText.lines("  0 ", TerminalText.safe("build").padded(10), "|",
                TerminalText.seq(" [", TerminalText.safe("shell"), "]"))).containsExactly("  0 build     | [shell]");
        assertThat(TerminalText.text("a ", TerminalText.safe("b  b"))).isEqualTo("a \n  |+b  b");
    }

    @Test
    void controlCharactersAreReplaced_inEveryForm() {
        String evil = "a\u001b[2Kb\u202ec\nd";
        assertThat(TerminalText.lines(TerminalText.safe(evil))).containsExactly("| a?[2Kb?c?d");
        assertThat(TerminalText.lines(TerminalText.whole(evil))).containsExactly("a?[2Kb?c?d");
        assertThat(TerminalText.lines(TerminalText.composed(evil))).containsExactly("a?[2Kb?c?d");
        assertThat(TerminalText.lines(TerminalText.oneLine(evil))).containsExactly("| a?[2Kb?c d");
        assertThat(TerminalText.lines(TerminalText.word(evil))).containsExactly("| a?[2Kb?c?d");
        assertThat(TerminalText.lines("x ", TerminalText.of(traceworks.core.util.Message.of(
                traceworks.core.util.Message.quoted(evil), " ", traceworks.core.util.Message.typed(evil)))))
                .containsExactly("x a?[2Kb?c?d a?[2Kb?c?d");
        assertThat(TerminalText.block("  ", evil)).containsExactly("  | a?[2Kb?c", "  | d");
    }

    /** The guard: a text joined to a line around the gate shows as a fault, never as itself. */
    @Test
    void aTextIsNoString_soJoiningItToALineShowsAFault() {
        TerminalText text = TerminalText.safe(Forged.BLANKS);

        assertThat("Rule " + text).isEqualTo("Rule " + TerminalText.WITHHELD);
        assertThat(String.format("%-10s|", text)).doesNotContain(Forged.WORD);
        assertThat(String.valueOf(text)).doesNotContain(Forged.WORD);
    }

    @Test
    void aBlock_marksEveryLine_andBreaksALineThatIsTooWide() {
        List<String> lines = TerminalText.block("      ", "short\n" + Forged.BLANKS + "\n  two\n\nlast");

        assertThat(lines).containsExactly("      | short", "      | x", "      |+ " + Forged.HINT, "      |   two",
                        "      | ", "      | last");
        assertThat(TerminalText.block("  ", "x".repeat(100))).as("a line broken inside a word is put together "
                + "again as it was").containsExactly("  | " + "x".repeat(75), "  |+" + "x".repeat(25));
    }

    @Test
    void optionsAreListedOnMarkedLines_whereTheyWouldNotStayOnTheirLine() {
        assertThat(TerminalText.shown("  ", "one of:", " ", "", "", List.of("a", "b"))).containsExactly("one of: a / b");
        assertThat(TerminalText.shown("  ", "one of:", " ", "", "", List.of("a", "b  b")))
                .containsExactly("one of:", "| a", "| b  b");
        assertThat(TerminalText.shown("  ", "", "", "", "", List.of("  two blanks first"))).as("no line of a text"
                + " reads as one that goes on").containsExactly("|   two blanks first");
        assertThat(TerminalText.shown("  ", "q", " [", "]", " [options:]", List.of("Yes", Forged.BLANKS)))
                .containsExactly("q [options:]", "| Yes", "| x", "|+ " + Forged.HINT);
    }

    @Test
    void aPrinter_putsItsIndentIntoEveryLine_soATextIsBrokenWhereTheIndentedLineEnds() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        TerminalText.Printer inner = new TerminalText.Printer(new java.io.PrintStream(bytes, true,
                java.nio.charset.StandardCharsets.UTF_8), "").indented("      ").indented("      ");

        inner.println("  0   ", TerminalText.oneLine("word ".repeat(30)));
        inner.block("      ", "x".repeat(100));

        String printed = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(printed.lines()).hasSizeGreaterThan(3).allSatisfy(line ->
                assertThat(line.length()).isLessThanOrEqualTo(80));
        assertThat(printed.lines().skip(1)).allSatisfy(line -> assertThat(line.strip()).startsWith("|"));
    }

    @Test
    void aLineIsBrokenAtABlankWithinTheWidth() {
        assertThat(TerminalText.broken("a\tb", 76)).containsExactly("a b");
        assertThat(TerminalText.broken("word ".repeat(30).strip(), 76)).allSatisfy(part ->
                assertThat(part.length()).isBetween(70, 76));
        assertThat(TerminalText.broken("", 76)).containsExactly("");
    }
}
