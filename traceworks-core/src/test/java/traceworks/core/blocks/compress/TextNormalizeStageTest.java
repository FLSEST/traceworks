package traceworks.core.blocks.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizeStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final TextNormalizeStage stage = new TextNormalizeStage();

    @Test
    void oscTitleSequencesAndLoneCarriageReturns_areHandled() {
        String input = "\u001B]0;window title\u0007go\rmore\u001B]8;;http://x\u001B\\link";

        assertThat(stage.apply(input, Map.of(), guard).output()).isEqualTo("go\nmorelink");
    }

    @Test
    void leadingIndentationKept_whitespaceOnlyLinesCountAsBlank() {
        String input = "  indented\t\n \t \n\t\nmore";

        assertThat(stage.apply(input, Map.of(), guard).output()).isEqualTo("  indented\n\nmore");
    }

    @Test
    void alreadyNormal_isUnchanged_noteIsZero() {
        StageResult r = stage.apply("a\n\nb", null, guard);

        assertThat(r.output()).isEqualTo("a\n\nb");
        assertThat(r.note()).isEqualTo("−0 chars");
    }

    @Test
    void onlyBlankContent_becomesEmpty() {
        assertThat(stage.apply("\n  \n\r\n", Map.of(), guard).output()).isEmpty();
    }
}
