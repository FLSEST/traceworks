package traceworks.core.blocks.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownSectionStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private static final String REPORT = """
            # Scorecard
            Intro
            ## Overall score
            7.4 (+0.3)
            ### Details
            fine
            ## Dimensions
            much text
            ```
            ## No heading in code
            ```
            ## Recommendation
            stabilise tests
            """;

    @Test
    void keepsMatchingSectionsWithSubsections_caseInsensitive() {
        StageResult r = new MarkdownSectionStage().apply(REPORT, Map.of("headings", List.of("OVERALL score", "recommendation")), guard);

        assertThat(r.output()).isEqualTo("## Overall score\n7.4 (+0.3)\n### Details\nfine\n\n## Recommendation\nstabilise tests\n");
        assertThat(r.note()).isEqualTo("2 of 5 sections");
    }

    @Test
    void headingInsideCodeFenceIsIgnored() {
        StageResult r = new MarkdownSectionStage().apply(REPORT, Map.of("headings", List.of("No heading")), guard);

        assertThat(r.output()).isEmpty();
        assertThat(r.note()).isEqualTo("no matching section");
    }

    @Test
    void levelFilter() {
        StageResult r = new MarkdownSectionStage().apply(REPORT, Map.of("headings", List.of("Details"), "level", 3), guard);

        assertThat(r.output()).isEqualTo("### Details\nfine");
    }

    @Test
    void headingMatch_isGuardedByTheDeadline() {
        RegexGuard expired = RegexGuard.startingNow(Duration.ZERO);
        String costlyHeading = "# x" + " ".repeat(900) + "y";

        assertThatThrownBy(() -> new MarkdownSectionStage().apply(costlyHeading, Map.of("headings", List.of("zzz"), "level", 6), expired))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit");
    }

    @Test
    void headingTitle_dropsClosingHashesAndSpaces() {
        StageResult r = new MarkdownSectionStage().apply("## Summary ##  \ntext\n# Other", Map.of("headings", List.of("^Summary$")), guard);

        assertThat(r.output()).isEqualTo("## Summary ##  \ntext");
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> new MarkdownSectionStage().validate(Map.of("headings", List.of())))
                .isInstanceOf(CompressionException.class).hasMessageContaining("headings");
        assertThatThrownBy(() -> new MarkdownSectionStage().validate(Map.of("headings", List.of("x"), "level", 9)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("level");
    }
}
