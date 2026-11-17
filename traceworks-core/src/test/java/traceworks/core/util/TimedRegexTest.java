package traceworks.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The time limit is checked where the engine reads a character. A counted repetition of something that reads none
 * never gets there, so such a pattern is refused before the engine sees it.
 */
class TimedRegexTest {

    private static final Duration BUDGET = Duration.ofMillis(200);

    @Test
    void countedRepetitionsThatMultiply_areRefusedBeforeTheMatch() {
        for (String regex : new String[] {
                "(?:^{2147483647}){2147483647}",
                "(?:(?:){2147483647}){2147483647}",
                "(?:(?:(?:(?:(?:){1000}){1000}){1000}){1000}){1000}"}) {
            Pattern pattern = compileIfTheEngineTakesIt(regex);
            if (pattern == null) continue;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                assertThatThrownBy(() -> TimedRegex.find(pattern, "a", BUDGET)).as(regex)
                        .isInstanceOf(TimedRegex.TimedOut.class);
                assertThatThrownBy(() -> TimedRegex.matches(pattern, "a", BUDGET)).as(regex)
                        .isInstanceOf(TimedRegex.TimedOut.class);
            });
        }
    }

    /** The counts are a first line with a clear message; the limit is far above what a real pattern multiplies to. */
    @Test
    void countsThatMultiplyBeyondTheLimit_areNamedBeforeTheMatch_andCountsSideBySideOfARealPatternAreNot() {
        assertThat(TimedRegex.repetitionProblem("(?:^{2147483647}){2147483647}")).contains("1,000,000,000,000");
        assertThat(TimedRegex.repetitionProblem("(?:(?:(?:(?:(?:){1000}){1000}){1000}){1000}){1000}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("[a-z]{1,64}@[a-z]{1,255}\\.[a-z]{2,63}")).isNull();
        assertThat(TimedRegex.matches(Pattern.compile("[a-z]{1,64}@[a-z]{1,255}\\.[a-z]{2,63}"), "flo@example.org",
                BUDGET)).isTrue();
    }

    /**
     * No count at all, and no character read: an empty alternative repeated doubles the engine's time with every
     * repetition, where neither the count check nor the clock in the text can see it. The hard limit ends it.
     */
    @Test
    void aPatternThatSpendsItsTimeWithoutReadingACharacter_isGivenUpAtTheHardLimit_andNotTriedAgain() {
        Pattern pattern = Pattern.compile("(?:|)".repeat(40) + "\\z.");

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThatThrownBy(() -> TimedRegex.find(pattern, "a", BUDGET)).isInstanceOf(TimedRegex.TimedOut.class)
                    .hasMessageContaining("did not end within its time limit");
            long before = System.nanoTime();
            assertThatThrownBy(() -> TimedRegex.find(pattern, "a", BUDGET)).isInstanceOf(TimedRegex.TimedOut.class)
                    .hasMessageContaining("not tried again");
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofMillis(100));
        });
    }

    @Test
    void ordinaryCountedRepetitions_stillMatch() {
        assertThat(TimedRegex.matches(Pattern.compile("\\d{4}-\\d{2}-\\d{2}"), "2026-11-12", BUDGET)).isTrue();
        assertThat(TimedRegex.find(Pattern.compile("^[A-Z]{2,3}-\\d{1,6}$"), "PO-123", BUDGET)).isTrue();
        assertThat(TimedRegex.repetitionProblem("a{1000}b{1000}")).isNull();
        assertThat(TimedRegex.repetitionProblem("(?:a{0}b{0,}){1000000}")).isNull();
    }

    @Test
    void bracesThatAreNoQuantifier_areNotCounted() {
        // An escaped brace, braces in a character class, quoted text and a named property repeat nothing
        assertThat(TimedRegex.repetitionProblem("\\{2000}\\{3000}")).isNull();
        assertThat(TimedRegex.repetitionProblem("[{2000}][{3000}]")).isNull();
        assertThat(TimedRegex.repetitionProblem("[a[b{2000}]{3000}]")).isNull();
        assertThat(TimedRegex.repetitionProblem("\\Qa{2000}{3000}\\E")).isNull();
        assertThat(TimedRegex.repetitionProblem("\\p{Lu}\\x{1F600}")).isNull();
        assertThat(TimedRegex.matches(Pattern.compile("\\{2000}\\{3000}"), "{2000}{3000}", BUDGET)).isTrue();
        assertThat(TimedRegex.matches(Pattern.compile("[{2000}]+[{3000}]"), "{2}0}", BUDGET)).isTrue();
    }

    @Test
    void whatLooksLikeTheEndOfAClassOrAQuote_doesNotHideAQuantifier() {
        // The backslash is escaped, so the brace after it is a quantifier
        assertThat(TimedRegex.repetitionProblem("(?:a\\\\{2000000}){3000000}")).isNotNull();
        // An escaped bracket opens no class, and an escaped one closes none
        assertThat(TimedRegex.repetitionProblem("(?:\\[{2000000}){3000000}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("(?:[\\]]{2000000}){3000000}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("(?:\\Qx\\E{2000000}){3000000}")).isNotNull();
    }

    /** A {@code ]} that closes no class is a character to the engine: it opens nothing that could hide a count. */
    @Test
    void aStrayEndOfAClass_doesNotHideTheCountsAfterIt() {
        assertThat(TimedRegex.repetitionProblem("](?:(?:a){2000000}){3000000}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("a]](?:[b]{2000000}){3000000}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("][(?:(?:a){2000000}){3000000}]")).as("a class after it still hides")
                .isNull();
    }

    /** The reading goes on right after the {@code \E}: what was quoted, a {@code [} among it, opens nothing. */
    @Test
    void quotedText_isSkippedWhole_andTheCountsAfterItStillCount() {
        assertThat(TimedRegex.repetitionProblem("\\Qab\\E(?:(?:a){2000000}){3000000}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("\\Q[\\E(?:(?:a){2000000}){3000000}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("\\Q[\\E(?:a{2})")).isNull();
    }

    /** Nothing between {@code \Q} and {@code \E}: the reading still moves on, and reads the counts that follow. */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anEmptyQuote_endsTheReading_andHidesNoCount() {
        assertThat(TimedRegex.repetitionProblem("\\Q\\E")).isNull();
        assertThat(TimedRegex.repetitionProblem("a\\Q\\Eb{2}")).isNull();
        assertThat(TimedRegex.repetitionProblem("\\Q\\E(?:(?:a){2000000}){3000000}")).isNotNull();
    }

    @Test
    void matches_isFalse_forATextThePatternFindsOnlyPartOf_orNothingOf() {
        Pattern month = Pattern.compile("\\d{4}-\\d{2}");

        assertThat(TimedRegex.matches(month, "2026-11-12", BUDGET)).isFalse();
        assertThat(TimedRegex.find(month, "2026-11-12", BUDGET)).isTrue();
        assertThat(TimedRegex.matches(month, "no month", BUDGET)).isFalse();
        assertThat(TimedRegex.find(month, "no month", BUDGET)).isFalse();
        assertThat(TimedRegex.matches(month, "2026-11", BUDGET)).isTrue();
    }

    /** The counts are read digit by digit, and the limit is the most they may multiply to, not the first refused. */
    @Test
    void theCountsAreReadDigitByDigit_andMayMultiplyToExactlyTheLimit() {
        assertThat(TimedRegex.repetitionProblem("(?:(?:){1234567}){1234567}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("(?:(?:){1234567}){809}")).isNull();
        assertThat(TimedRegex.repetitionProblem("(?:(?:){1000000}){1000000}")).as("exactly the limit").isNull();
        assertThat(TimedRegex.repetitionProblem("(?:(?:){1000000}){1000001}")).isNotNull();
    }

    /** What only looks like the start of a count ends the reading of it without a failure, and counts nothing. */
    @Test
    void anUnclosedCount_countsNothing() {
        assertThat(TimedRegex.repetitionProblem("a{12")).isNull();
    }

    @Test
    void aBraceThatIsNoCount_countsNothing_andTheCountsAfterItStillCount() {
        assertThat(TimedRegex.repetitionProblem("a{x}b{2}")).isNull();
        assertThat(TimedRegex.repetitionProblem("a{x}(?:(?:){2000000}){3000000}")).isNotNull();
    }

    /** An escape at the very end, or a property name without its brace, ends the reading. */
    @Test
    void aBackslashAtTheEnd_endsTheReading() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThat(TimedRegex.repetitionProblem("a{2}\\")).isNull());
    }

    @Test
    void aPropertyWithoutItsName_atTheEnd_endsTheReading() {
        assertThat(TimedRegex.repetitionProblem("a\\p")).isNull();
    }

    @Test
    void aPropertyWhoseBraceNeverCloses_endsTheReading() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThat(TimedRegex.repetitionProblem("\\p{")).isNull());
    }

    /** Quoted text is read from its {@code \Q}: an {@code \E} before it closes nothing. */
    @Test
    void quotedTextAfterAStrayEndOfQuote_countsNothing() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThat(TimedRegex.repetitionProblem("\\E\\Q{2000000}{3000000}\\E")).isNull());
    }

    /** {@code \pL} without braces names a property of one letter: a brace after it is a count. */
    @Test
    void aCountAfterAPropertyWithoutBraces_isCounted() {
        assertThat(TimedRegex.repetitionProblem("(?:\\pL{2000000}){3000000}")).isNotNull();
    }

    /** Comments mode skips every kind of white space between the digits of a count, and so does the reading here. */
    @Test
    void everyWhiteSpaceOfCommentsMode_isSkippedInsideACount() {
        for (String space : new String[] {"\n", "\r", "\f", "\u000B"}) {
            assertThat(TimedRegex.repetitionProblem("(?x)(?:(?:){9" + space + "99999999}){9" + space + "99999999}"))
                    .as("%04X", (int) space.charAt(0)).isNotNull();
        }
    }

    /** On the caller's thread the clock in the text is the only limit: it ends a match that backtracks without end. */
    @Test
    void theClockInTheText_endsAMatchThatBacktracks_andLetsALongOrdinaryMatchEnd() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() ->
                TimedRegex.matcher(Pattern.compile("((a+)\\2?)+b"), "a".repeat(60), BUDGET).matches())
                .isInstanceOf(TimedRegex.TimedOut.class).hasMessage("the match did not end within its time limit"));
        assertThat(TimedRegex.matcher(Pattern.compile("a*"), "a".repeat(10_000), BUDGET).matches()).isTrue();
    }

    /** The engine's stack overflow on a long text is a pattern nested too deeply, not a crash of the caller. */
    @Test
    void aPatternNestedTooDeeplyForTheText_isTimedOut() {
        assertThatThrownBy(() -> TimedRegex.matches(Pattern.compile("(?:a|b)*"), "ab".repeat(500_000),
                Duration.ofSeconds(5))).isInstanceOf(TimedRegex.TimedOut.class)
                .hasMessage("the pattern is nested too deeply for the text");
    }

    @Test
    void within_throwsAnErrorOfTheWork_asItIs() {
        LinkageError error = new LinkageError("a class went missing");

        assertThatThrownBy(() -> TimedRegex.within(BUDGET, () -> {
            throw error;
        })).isSameAs(error);
    }

    /** A thread interrupted while it waits gives the match up and keeps its interrupt for its caller. */
    @Test
    void within_whenTheWaitingThreadIsInterrupted_givesUp_andKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        Throwable thrown;
        boolean interrupted;
        try {
            thrown = org.assertj.core.api.Assertions.catchThrowable(() -> TimedRegex.within(BUDGET, () -> {
                Thread.sleep(10_000);
                return true;
            }));
        } finally {
            interrupted = Thread.interrupted();
        }

        assertThat(thrown).isInstanceOf(TimedRegex.TimedOut.class).hasMessage("the match was interrupted");
        assertThat(interrupted).isTrue();
    }

    /** {@code null} for a count the engine itself refuses: such a pattern never reaches a match. */
    private static Pattern compileIfTheEngineTakesIt(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (java.util.regex.PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * Under {@code (?x)} the engine skips white space and {@code #} comments, also between the digits of a count:
     * {@code {9 99}} is {@code {999}} there. The count is read the same way, so none is missed.
     */
    @Test
    void aCountWrittenWithWhiteSpaceOrAComment_isReadAsTheEngineReadsItInCommentsMode() {
        assertThat(Pattern.compile("(?x)a{1 0}").matcher("aaaaaaaaaa").matches()).isTrue();

        for (String regex : new String[] {
                "(?x)(?:(?:){9 99999999}){9 99999999}",
                "(?x)(?:(?:){999999999 # a comment\n}){999999999}",
                "(?x)(?:(?:){1,\t999999999}){999999999}"}) {
            assertThat(TimedRegex.repetitionProblem(regex)).as(regex).contains("multiply to more than");
        }
        assertThat(TimedRegex.repetitionProblem("(?x) a{1 0} b{2 , 3} # {999999999}{999999999}")).isNotNull();
        assertThat(TimedRegex.repetitionProblem("(?x) a{1 0} b{2 , 3}")).isNull();
    }

    /**
     * Two things the reading of classes cannot follow. A control escape takes the character after it, so
     * {@code \c[} is the escape character and opens no class, and {@code \c\} takes the backslash that would
     * escape a brace or a bracket. And in comments mode, which a pattern can switch on for a part of itself, a
     * {@code [} after a {@code #} is in a comment. A pattern with either has every brace counted, whatever stands
     * before it: more than the engine counts, never less.
     */
    @Test
    void aControlEscapeOrAComment_cannotHideACountBehindAClassThatIsNone() {
        assertThat(Pattern.compile("\\c[").matcher("\u001b").matches()).as("\\c[ is one character").isTrue();
        assertThat(Pattern.compile("(?x)a#[\nb").matcher("ab").matches()).as("a [ in a comment").isTrue();

        for (String regex : new String[] {
                "\\c[?(?:^{2000000}){3000000}",
                "(?:^\\c\\{2000000}){3000000}",
                "[\\c\\](?:^{2000000}){3000000}",
                "(?x)#[\n(?:^{2000000}){3000000}",
                "(?x:#[\n)#(?:^{2000000}){3000000}"}) {
            Pattern.compile(regex);   // each is a pattern the engine takes
            assertThat(TimedRegex.repetitionProblem(regex)).as(regex).contains("multiply to more than");
        }
        assertThat(TimedRegex.repetitionProblem("#\\d{3}-[a-z{}]{2}\\cA{4}")).as("small counts stay fine").isNull();
    }

    private static boolean compiles(String regex) {
        try {
            Pattern.compile(regex);
            return true;
        } catch (java.util.regex.PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * The engine takes the quoting out of a pattern before it reads anything else: an empty quote {@code \Q\E}
     * vanishes, also between the digits of a count, and quoted text becomes escaped characters. The counts are read
     * from the pattern as it is after that, so an empty quote in a count hides nothing, in either way of counting.
     */
    @Test
    void anEmptyQuoteInsideACount_hidesNothing() {
        assertThat(Pattern.compile("a{1\\Q\\E0}").matcher("aaaaaaaaaa").matches()).as("{1\\Q\\E0} is {10}").isTrue();

        for (String regex : new String[] {
                "(?:^{10000000\\Q\\E}){10000000\\Q\\E}",
                "(?:^{\\Q\\E10000000}){\\Q\\E10000000}",
                "(?:^{1000\\Q\\E0000}){1000\\Q\\E\\Q\\E0000}",
                "(?:^{1,\\Q\\E10000000}){10000000}",
                "#(?:^{1000\\Q\\E0000}){1000\\Q\\E0000}",
                "\\cA(?:^{1000\\Q\\E0000}){1000\\Q\\E0000}"}) {
            assertThat(compiles(regex)).as(regex).isTrue();
            assertThat(TimedRegex.repetitionProblem(regex)).as(regex).contains("multiply to more than");
        }
        // A quote that is not empty makes a count no pattern at all: its first digit becomes an escape
        assertThat(compiles("a{\\Q1\\E0}")).isFalse();
        assertThat(compiles("a{1\\Q0\\E}")).isFalse();
        // Quoted braces are text, for the engine and here: no false refusal
        assertThat(Pattern.compile("\\Q{2000000}\\E\\Q{3000000}\\E").matcher("{2000000}{3000000}").matches()).isTrue();
        assertThat(TimedRegex.repetitionProblem("\\Q{2000000}\\E\\Q{3000000}\\E")).isNull();
    }

    /**
     * The quoting is taken out exactly as the engine does it: a pattern and what {@link TimedRegex#withoutQuotes}
     * makes of it compile alike and match alike, for patterns built from the pieces that matter here.
     */
    @Test
    void withoutQuotes_isWhatTheEngineReads() {
        String[] pieces = {"\\Q", "\\E", "\\", "{", "}", "1", "2", ",", "a", "[", "]", " ", "Q", "E", "x", "^"};
        String[] texts = {"", "a", "aa", "a{1}", "{2}", "1", "\\", "a1", "[", "aaaaaaaaaaaa", "\\Q", "E", " "};
        java.util.Random random = new java.util.Random(20261117);
        int compiled = 0;
        for (int n = 0; n < 200_000; n++) {
            StringBuilder regex = new StringBuilder();
            for (int k = random.nextInt(9) + 1; k > 0; k--) {
                regex.append(pieces[random.nextInt(pieces.length)]);
            }
            String plain = TimedRegex.withoutQuotes(regex.toString());
            assertThat(compiles(plain)).as("%s -> %s", regex, plain).isEqualTo(compiles(regex.toString()));
            if (!compiles(plain)) continue;
            compiled++;
            for (String text : texts) {
                assertThat(Pattern.compile(plain).matcher(text).matches()).as("%s -> %s on %s", regex, plain, text)
                        .isEqualTo(Pattern.compile(regex.toString()).matcher(text).matches());
            }
        }
        assertThat(compiled).as("patterns that compile").isGreaterThan(10_000);
    }

    /**
     * The constructs next to those the reading follows, each checked against the engine: what could hide a count is
     * no pattern for the engine at all, and braces that are no counts are not taken for one.
     */
    @Test
    void theNeighbouringConstructs_neitherHideACountNorMakeOne() {
        // No pattern for Java's engine, so whatever the reading makes of them hides nothing
        for (String regex : new String[] {
                "(?#[)(?:^{2000000}){3000000}",          // no (?#...) comments
                "(?:^\\k{2000000}){3000000}", "(?:^\\g{2000000}){3000000}", "(?:^\\o{2000000}){3000000}",
                "(?:^\\u{2000000}){3000000}",
                "(?:^\\p\\{2000000}){3000000}", "(?:^\\x\\{2000000}){3000000}",
                "[\\p{L](?:^{2000000}){3000000}", "(?:^\\x{20000000}){3000000}",
                "[[:alpha:](?:^{2000000}){3000000}"}) {
            assertThat(compiles(regex)).as(regex).isFalse();
        }
        // Patterns the engine takes, with counts behind constructs that end where the reading says they end
        for (String regex : new String[] {
                "[[:alpha:]](?:^{2000000}){3000000}",
                "[a[b]](?:^{2000000}){3000000}",
                "[a&&[^b]](?:^{2000000}){3000000}",
                "[]a](?:^{2000000}){3000000}",
                "[^]a](?:^{2000000}){3000000}",
                "\\p{Lu}\\x{1F600}\\N{LATIN SMALL LETTER A}\\b{g}(?:^{2000000}){3000000}",
                "(?:^{2000000}){3000000}\\\\"}) {
            assertThat(compiles(regex)).as(regex).isTrue();
            assertThat(TimedRegex.repetitionProblem(regex)).as(regex).isNotNull();
        }
        // Braces that are no counts: no false refusal
        for (String regex : new String[] {
                "\\x{10000}\\x{10000}\\x{10000}", "\\p{Lu}{2}\\P{L}{3}", "\\N{DIGIT ONE}{4}", "\\b{g}a{5}",
                "[[:alpha:]{2000000}{3000000}]", "[a[b{2000000}]{3000000}]", "[a&&[^b{2000000}]{3000000}]"}) {
            assertThat(compiles(regex)).as(regex).isTrue();
            assertThat(TimedRegex.repetitionProblem(regex)).as(regex).isNull();
        }
    }
}
