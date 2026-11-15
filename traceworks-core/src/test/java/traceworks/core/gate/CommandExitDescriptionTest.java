package traceworks.core.gate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The synthetic gate description of a checked_command, written and recognised in one place. */
class CommandExitDescriptionTest {

    @Test
    void whatTheBlockWrites_isRecognised() {
        assertThat(CommandExitDescription.of(0)).isEqualTo("Command exit 0");
        assertThat(CommandExitDescription.isSynthetic(CommandExitDescription.of(0))).isTrue();
        assertThat(CommandExitDescription.isSynthetic(CommandExitDescription.of(137))).isTrue();
        assertThat(CommandExitDescription.isSynthetic(CommandExitDescription.of(-1))).isTrue();
        assertThat(CommandExitDescription.isSynthetic(" Command exit 1 ")).isTrue();
        assertThat(CommandExitDescription.notRun(125)).isEqualTo("Command did not run (exit 125)");
        assertThat(CommandExitDescription.isSynthetic(CommandExitDescription.notRun(125))).isTrue();
    }

    @Test
    void aDescriptionWithWordsOfItsOwn_isNotSynthetic() {
        assertThat(CommandExitDescription.isSynthetic("Command exit 0, 214 tests green")).isFalse();
        assertThat(CommandExitDescription.isSynthetic("Health good")).isFalse();
        assertThat(CommandExitDescription.isSynthetic("Command did not run (exit 125), image missing")).isFalse();
        assertThat(CommandExitDescription.isSynthetic("")).isFalse();
        assertThat(CommandExitDescription.isSynthetic(null)).isFalse();
    }
}
