package traceworks.core.context;

import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputBudgetTest {

    @Test
    void take_takesWhatFits_exactlyUpToTheBudget() {
        OutputBudget budget = new OutputBudget(100);

        assertThat(budget.take(60)).isTrue();
        assertThat(budget.take(40)).isTrue();
        assertThat(budget.heldBytes()).isEqualTo(100);
        assertThat(budget.exceeded()).isFalse();
    }

    @Test
    void take_refusesWhatWouldGoBeyond_takesNothing_andTheBudgetStaysExceeded() {
        OutputBudget budget = new OutputBudget(100);
        budget.take(60);

        assertThat(budget.take(41)).isFalse();
        assertThat(budget.heldBytes()).isEqualTo(60);
        assertThat(budget.exceeded()).isTrue();
        assertThat(budget.take(1)).as("what fits is still taken").isTrue();
        assertThat(budget.exceeded()).as("but the run ends all the same").isTrue();
    }

    @Test
    void hold_countsWithoutACheck_soTheNextTakeFails() {
        OutputBudget budget = new OutputBudget(100);
        budget.hold(150);

        assertThat(budget.exceeded()).isFalse();
        assertThat(budget.take(0)).isFalse();
        assertThat(budget.exceeded()).isTrue();
    }

    @Test
    void releaseTo_givesBackWhatWasTakenSinceTheMark() {
        OutputBudget budget = new OutputBudget(100);
        budget.take(30);
        long mark = budget.heldBytes();
        budget.take(50);

        budget.releaseTo(mark);

        assertThat(budget.heldBytes()).isEqualTo(30);
        assertThat(budget.take(70)).isTrue();
    }

    @Test
    void release_givesBackExactlyWhatItIsTold_neverBelowNothing() {
        OutputBudget budget = new OutputBudget(100);
        budget.take(30);
        budget.take(50);

        budget.release(30);
        assertThat(budget.heldBytes()).isEqualTo(50);
        budget.release(80);
        assertThat(budget.heldBytes()).isZero();
    }

    @Test
    void none_neverRunsOut() {
        OutputBudget budget = OutputBudget.none();
        budget.hold(Long.MAX_VALUE - 1);
        budget.hold(10);

        assertThat(budget.heldBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat(budget.limitBytes()).isEqualTo(OutputBudget.NONE);
        assertThat(OutputBudget.none().take(Long.MAX_VALUE)).isTrue();
    }

    @Test
    void aBudgetBelowOneByte_isRefused() {
        assertThatThrownBy(() -> new OutputBudget(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefault_isAtLeast128MiB_andEightTimesTheOutputLimit() {
        assertThat(OutputBudget.defaultMib(1)).isEqualTo(128);
        assertThat(OutputBudget.defaultMib(16)).isEqualTo(128);
        assertThat(OutputBudget.defaultMib(17)).isEqualTo(136);
        assertThat(OutputBudget.defaultMib(1024)).isEqualTo(8192);
    }

    @Test
    void theNote_namesTheBudget_asTheOutputLimitsNoteNamesTheLimit() {
        assertThat(new OutputBudget(128L * 1024 * 1024).note()).isEqualTo("The output this run holds in memory would"
                + " exceed its budget of 128 MiB; Traceworks failed this step and ended the run");
        assertThat(new OutputBudget(100).note()).contains("its budget of 100 bytes;");
    }

    @Test
    void fail_failsTheStep_withTheNoteOnItsOwnLine_andTheFlag_once() {
        OutputBudget budget = new OutputBudget(100);
        StepResult step = StepResult.builder().status(StepStatus.COMPLETED).output("all of it")
                .metadata(Map.of("exitCode", 0)).build();

        budget.fail(step);
        budget.fail(step);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).isEqualTo("all of it\n" + budget.note());
        assertThat(step.getMetadata()).containsEntry(OutputBudget.METADATA_KEY, true).containsEntry("exitCode", 0);
        assertThat(OutputBudget.failedFor(step)).isTrue();
    }

    /** The flag decides, not the text: a step that prints the note itself is no budget failure, and gets it again. */
    @Test
    void aStepThatPrintsTheNoteItself_isNoBudgetFailure_untilTheBudgetFailsIt() {
        OutputBudget budget = new OutputBudget(100);
        StepResult step = StepResult.builder().status(StepStatus.COMPLETED).output("log: " + budget.note()).build();

        assertThat(OutputBudget.failedFor(step)).isFalse();
        budget.fail(step);

        assertThat(step.getOutput()).isEqualTo("log: " + budget.note() + "\n" + budget.note());
        assertThat(OutputBudget.failedFor(step)).isTrue();
    }

    @Test
    void fail_ofAStepWithoutOutput_givesItTheNote() {
        OutputBudget budget = new OutputBudget(100);
        StepResult step = StepResult.builder().status(StepStatus.COMPLETED).build();

        budget.fail(step);

        assertThat(step.getOutput()).isEqualTo(budget.note());
    }

    @Test
    void aStep_addsItsInputAndItsOutput() {
        StepResult step = StepResult.builder().input("echo é").output("✓ ok").build();

        assertThat(OutputBudget.sizeOf(step)).as("a Latin-1 input, a byte each; the check mark makes two each")
                .isEqualTo(6 + 2 * 4);
        assertThat(OutputBudget.sizeOf(StepResult.builder().build())).isZero();
    }

    /** As Java holds a string: a byte per character while every one is at most U+00FF. */
    @ParameterizedTest
    @ValueSource(strings = {"", "plain ascii", "café", "ÿ max latin-1", "\u0000 nul"})
    void sizeOf_aLatin1Text_isItsLength(String text) {
        assertThat(OutputBudget.sizeOf(text)).isEqualTo(text.length());
    }

    /** One character above U+00FF makes the whole string two bytes per character. */
    @ParameterizedTest
    @ValueSource(strings = {"Ā", "tests ✓", "€ 5", "😀 smile", "lone \ud83d high",
            "� replacement", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx─"})
    void sizeOf_aTextWithACharacterAboveLatin1_isTwiceItsLength(String text) {
        assertThat(OutputBudget.sizeOf(text)).isEqualTo(2L * text.length());
    }

    @Test
    void sizeOf_isTheMemoryOfTheString_aByteOrTwoPerCharacter() {
        Random random = new Random(20261109);
        for (int round = 0; round < 1_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(12);
            for (int i = 0; i < length; i++) {
                text.append((char) (random.nextBoolean() ? random.nextInt(0x100) : random.nextInt(0x10000)));
            }
            String s = text.toString();
            boolean latin1 = s.chars().allMatch(c -> c <= 0xFF);
            assertThat(OutputBudget.sizeOf(s)).as("text %s", s.chars().boxed().toList())
                    .isEqualTo(latin1 ? s.getBytes(StandardCharsets.ISO_8859_1).length
                            : s.getBytes(StandardCharsets.UTF_16LE).length);
        }
        assertThat(OutputBudget.sizeOf((CharSequence) null)).isZero();
    }

    @Test
    void sizeOf_aValue_countsItsTexts() {
        assertThat(OutputBudget.sizeOf((Object) "abc")).isEqualTo(3);
        assertThat(OutputBudget.sizeOf((Object) List.of("ab", "✓", 7))).isEqualTo(2 + 2);
        assertThat(OutputBudget.sizeOf((Object) Map.of("key", "value"))).isEqualTo(3 + 5);
        assertThat(OutputBudget.sizeOf((Object) 42)).isZero();
        assertThat(OutputBudget.sizeOf((Object) null)).isZero();
    }
}
