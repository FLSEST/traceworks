package traceworks.core.blocks;

import traceworks.core.context.OutputBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatheredOutput} gives exactly the text the wrappers built before: the parts appended to a
 * {@code StringBuilder}, then {@code toString().trim()}.
 */
class GatheredOutputTest {

    static Stream<List<String>> parts() {
        return Stream.of(
                List.of(),
                List.of(""),
                List.of("", "", ""),
                List.of(" \n\t", "\n"),
                List.of("[a]: ", "x", "\n"),
                List.of("[a]: ", "x", "\n", "[b]: ", "y", "\n"),
                List.of("[a]: ", "x", "\n", "[b]: ", "", "\n"),
                List.of("[a]: ", "x", "\n", "[b]: ", "  \n\n", "\n"),
                List.of("  lead", " mid ", "trail  "),
                List.of("\u0000\u001f only control ", " "),
                List.of("\n", "  ", "inner", "  ", "\n"),
                List.of(" nbsp stays "),
                List.of("é", "€", "😀", " "),
                List.of("[iteration 1]: ", "a", "\n", "[condition] ", "The condition failed"));
    }

    @ParameterizedTest
    @MethodSource("parts")
    void text_isTheTrimmedConcatenation(List<String> parts) {
        GatheredOutput gathered = new GatheredOutput();
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            gathered.add(part);
            builder.append(part);
        }

        assertThat(gathered.text()).isEqualTo(builder.toString().trim());
    }

    @Test
    void text_isTheTrimmedConcatenation_ofRandomParts() {
        Random random = new Random(20261109);
        String alphabet = " \n\t\u0000abé€";
        for (int round = 0; round < 2_000; round++) {
            List<String> parts = new ArrayList<>();
            int count = random.nextInt(6);
            for (int p = 0; p < count; p++) {
                StringBuilder part = new StringBuilder();
                int length = random.nextInt(5);
                for (int c = 0; c < length; c++) {
                    part.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                parts.add(part.toString());
            }
            GatheredOutput gathered = new GatheredOutput();
            gathered.add(parts.toArray(String[]::new));

            assertThat(gathered.text()).as("parts %s", parts).isEqualTo(String.join("", parts).trim());
        }
    }

    @Test
    void aNullPiece_isTheTextNull_asAStringBuilderAppendsIt() {
        GatheredOutput gathered = new GatheredOutput();
        gathered.add("[a]: ", null, "\n");

        assertThat(gathered.text()).isEqualTo("[a]: null");
    }

    @Test
    void isEmpty_untilATextWithCharactersIsAdded() {
        GatheredOutput gathered = new GatheredOutput();
        assertThat(gathered.isEmpty()).isTrue();
        gathered.add("");
        assertThat(gathered.isEmpty()).isTrue();
        gathered.add(" ");
        assertThat(gathered.isEmpty()).isFalse();
    }

    @Test
    void aPartThatNeedsNoTrim_isNotCopied() {
        String large = "x".repeat(1_000);
        GatheredOutput gathered = new GatheredOutput();
        gathered.add(large);

        assertThat(gathered.text()).isSameAs(large);
    }

    /** It takes what the joined text will take: once a part is wide, two bytes for every character gathered. */
    @Test
    void itCountsTheJoinedText_twoBytesPerCharacterOnceAPartIsWide_andGivesItAllBack() {
        OutputBudget budget = new OutputBudget(1_000);
        budget.take(7);
        GatheredOutput gathered = new GatheredOutput(budget);

        assertThat(gathered.add("[a]: ", "x".repeat(10), "\n")).isTrue();
        assertThat(budget.heldBytes()).isEqualTo(7 + 16);
        assertThat(gathered.add("[b]: ", "✓", "\n")).isTrue();
        assertThat(budget.heldBytes()).as("all 23 characters at two bytes").isEqualTo(7 + 46);
        assertThat(gathered.add("[c]: ", "y", "\n")).isTrue();
        assertThat(budget.heldBytes()).isEqualTo(7 + 60);

        String text = gathered.text();

        assertThat(budget.heldBytes()).isEqualTo(7);
        assertThat(OutputBudget.sizeOf(text)).as("what the engine then counts for the output").isEqualTo(58);
    }

    @Test
    void aPartThatWouldGoBeyondTheBudget_isNotAdded_andWhatWasTakenStays() {
        OutputBudget budget = new OutputBudget(30);
        GatheredOutput gathered = new GatheredOutput(budget);
        gathered.add("x".repeat(14));

        assertThat(gathered.add("✓")).as("15 characters at two bytes are 30, the budget").isTrue();
        assertThat(gathered.add("y")).isFalse();
        assertThat(budget.exceeded()).isTrue();
        assertThat(budget.heldBytes()).isEqualTo(30);
        assertThat(gathered.text()).isEqualTo("x".repeat(14) + "✓");
        assertThat(budget.heldBytes()).isZero();
    }

    /** A block's own line goes in whether or not it fits, and counts; a long one is cut and says so. */
    @Test
    void aNote_goesInBeyondTheBudget_countsAllTheSame_andALongOneIsCutWithAMarker() {
        OutputBudget budget = new OutputBudget(10);
        GatheredOutput gathered = new GatheredOutput(budget);
        gathered.add("x".repeat(8));

        gathered.note("[condition] failed", List.of());

        assertThat(budget.heldBytes()).isEqualTo(26);
        assertThat(budget.exceeded()).as("a note never refuses").isFalse();
        assertThat(gathered.text()).isEqualTo("x".repeat(8) + "[condition] failed");

        String names = "n".repeat(GatheredOutput.NOTE_MAX_CHARS + 500);
        gathered.note(names, List.of());
        assertThat(gathered.text()).isEqualTo("n".repeat(GatheredOutput.NOTE_MAX_CHARS) + " [cut at "
                + GatheredOutput.NOTE_MAX_CHARS + " of " + names.length() + " characters]");
    }

    @Test
    void aLongNote_isCutBeforeASecretValueItWouldSplit() {
        String secret = "s3cr3t-value";
        String line = "a".repeat(GatheredOutput.NOTE_MAX_CHARS - 4) + secret + " tail";
        GatheredOutput gathered = new GatheredOutput();

        gathered.note(line, List.of(secret));

        assertThat(gathered.text()).startsWith("a".repeat(GatheredOutput.NOTE_MAX_CHARS - 4) + " [cut at");
    }

    @Test
    void text_gathersNothingAfterwards() {
        GatheredOutput gathered = new GatheredOutput();
        gathered.add("a");
        gathered.text();

        assertThat(gathered.isEmpty()).isTrue();
        assertThat(gathered.text()).isEmpty();
    }
}
